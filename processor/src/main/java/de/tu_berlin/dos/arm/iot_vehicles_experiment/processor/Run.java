package de.tu_berlin.dos.arm.iot_vehicles_experiment.processor;

/*import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Builder;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.Bucket;*/
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.tu_berlin.dos.arm.iot_vehicles_experiment.common.events.Point;
import de.tu_berlin.dos.arm.iot_vehicles_experiment.common.events.TrafficEvent;
import de.tu_berlin.dos.arm.iot_vehicles_experiment.common.utils.FileReader;
import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.flink.api.common.eventtime.*;
import org.apache.flink.api.common.eventtime.WatermarkGeneratorSupplier.Context;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.tuple.Tuple5;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer.Semantic;
import org.apache.flink.streaming.connectors.kafka.KafkaSerializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.log4j.Logger;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

public class Run {

    // traffic events are at most 60 sec out-of-order.
    private static final int MAX_EVENT_DELAY = 60;
    private static final Logger LOG = Logger.getLogger(Run.class);

    // class to filter traffic events within point of interest
    public static class POIFilter implements FilterFunction<TrafficEvent> {

        public final Point point;
        public final int radius;

        public POIFilter(Point point, int radius) {

            this.point = point;
            this.radius = radius;
        }

        @Override
        public boolean filter(TrafficEvent event) throws Exception {

            // Use Geodesic Inverse function to find distance in meters
            GeodesicData g1 = Geodesic.WGS84.Inverse(
                point.lt,
                point.lg,
                event.getPt().lt,
                event.getPt().lg);
            // determine if it is in the radius of the POE or not
            return g1.s12 <= radius;
        }
    }

    // Window to aggregate traffic events and calculate average speed in km/h
    public static class AvgSpeedWindow extends ProcessWindowFunction<TrafficEvent, Tuple5<Long, String, Float, Float, Integer>, String, TimeWindow> {

        public final int updateInterval;

        public AvgSpeedWindow(int updateInterval) {

            this.updateInterval = updateInterval;
        }

        @Override
        public void process(
                String vehicleId, Context context, Iterable<TrafficEvent> events,
                Collector<Tuple5<Long, String, Float, Float, Integer>> out) {

            Point previous = null;
            double distance = 0;
            int count = 0;
            for (TrafficEvent event : events) {
                if (previous != null) {
                    GeodesicData g1 = Geodesic.WGS84.Inverse(
                        previous.lt,
                        previous.lg,
                        event.getPt().lt,
                        event.getPt().lg);
                    distance += g1.s12;
                    count++;
                }
                previous = event.getPt();
            }
            // calculate time in hours
            double time = (count * updateInterval) / 3600000d;
            int avgSpeed = 0;
            if (time != 0) avgSpeed = (int) ((distance/1000) / time);
            if (previous != null) {
                out.collect(new Tuple5<>(context.window().getEnd(), vehicleId, previous.lt, previous.lg, avgSpeed));
            }
        }
    }

    // filter to determine if traffic vehicle is traveling over the speed limit
    public static class SpeedingFilter implements FilterFunction<Tuple5<Long, String, Float, Float, Integer>> {

        public final int speedLimit;

        public SpeedingFilter(int speedLimit) {

            this.speedLimit = speedLimit;
        }

        @Override
        public boolean filter(Tuple5<Long, String, Float, Float, Integer> trafficVehicle) throws Exception {

            return trafficVehicle.f4 >= speedLimit;
        }
    }

    // Retrieve vehicle type from database and parse json, builder is parsed to stop serialization error
    public static class VehicleEnricher extends RichMapFunction<Tuple5<Long, String, Float, Float, Integer>, String> {


        public VehicleEnricher() {

        }

        @Override
        public String map(Tuple5<Long, String, Float, Float, Integer> value) throws Exception {

            return String.format(
                "{ts: %d, lp: '%s', lat: %f, long: %f, avgSpeed: %d}",
                value.f0, value.f1, value.f2, value.f3, value.f4);
        }
    }

    public static void main(String[] args) throws Exception {

        // ensure checkpoint interval is supplied as an argument
        if (args.length != 6) {
            throw new IllegalStateException("Required Command line argument: jobName brokerList consumerTopic producerTopic partitions checkpointInterval");
        }
        String jobName = args[0];
        String brokerList = args[1];
        String consumerTopic = args[2];
        String producerTopic = args[3];
        int partitions = Integer.parseInt(args[4]);
        int checkpointInterval = Integer.parseInt(args[5]);

        // retrieve properties from file
        Properties props = FileReader.GET.read("processor.properties", Properties.class);
        int updateInterval = Integer.parseInt(props.getProperty("traffic.updateInterval"));
        int speedLimit = Integer.parseInt(props.getProperty("traffic.speedLimit"));

        // setup Kafka consumer
        Properties kafkaConsumerProps = new Properties();

        kafkaConsumerProps.setProperty("bootstrap.servers", brokerList);            // Broker default host:port
        kafkaConsumerProps.setProperty("group.id", UUID.randomUUID().toString());   // Consumer group ID
        kafkaConsumerProps.setProperty("auto.offset.reset", "latest");              // Always read topic from end

        FlinkKafkaConsumer<TrafficEvent> myConsumer =
            new FlinkKafkaConsumer<>(
                consumerTopic,
                new TrafficEventSchema(),
                kafkaConsumerProps);

        // setup Kafka producer
        Properties kafkaProducerProps = new Properties();
        kafkaProducerProps.setProperty("bootstrap.servers", brokerList);
        kafkaProducerProps.setProperty(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, "3600000");
        kafkaProducerProps.setProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        kafkaProducerProps.setProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG, UUID.randomUUID().toString());
        kafkaProducerProps.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        kafkaProducerProps.setProperty(ProducerConfig.LINGER_MS_CONFIG, "1000");
        kafkaProducerProps.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        kafkaProducerProps.setProperty(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");
        kafkaProducerProps.setProperty(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000");
        kafkaProducerProps.setProperty(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "120000");

        FlinkKafkaProducer<String> myProducer =
            new FlinkKafkaProducer<>(
                producerTopic,
                (KafkaSerializationSchema<String>) (value, aLong) -> {
                    return new ProducerRecord<>(producerTopic, value.getBytes());
                },
                kafkaProducerProps,
                Semantic.EXACTLY_ONCE,
                10);

        // Start configurations ****************************************************************************************

        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setBufferTimeout(-1);

        // Disable Operator chaining for fine grain monitoring
        env.disableOperatorChaining();

        /*Properties sysProps = new Properties();
        sysProps.setProperty("s3.host", "rook-ceph-rgw-checkpoint-store.rook-ceph");
        sysProps.setProperty("s3.endpoint", "10.233.4.155:80");
        sysProps.setProperty("s3.access-key", "X2YST2JA3UYHNB5Y9BB1");
        sysProps.setProperty("s3.secret-key", "X1ZpZoSAfo1B5tPWlvXvA5mRgu8pZ4y8vY8K2VlU");
        sysProps.setProperty("s3.ssl.enabled", "false");
        System.setProperties(new Properties());*/

        // configuring RocksDB state backend to use HDFS
        //String backupFolder = props.getProperty("ceph.backupFolder");
        String backupFolder = props.getProperty("hdfs.backupFolder");
        //"s3p://iot/" + jobName
        StateBackend backend = new RocksDBStateBackend(backupFolder, true);
        env.setStateBackend(backend);

        // start a checkpoint based on supplied interval
        env.enableCheckpointing(checkpointInterval);

        // set mode to exactly-once (this is the default)
        env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);

        // make sure 500 ms of progress happen between checkpoints
        //env.getCheckpointConfig().setMinPauseBetweenCheckpoints(checkpointInterval);

        // checkpoints have to complete within two minute, or are discarded
        env.getCheckpointConfig().setCheckpointTimeout(3600000);
        env.getCheckpointConfig().setTolerableCheckpointFailureNumber(3);

        // no external services which could take some time to respond, therefore 1
        // allow only one checkpoint to be in progress at the same time
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(10);

        // enable externalized checkpoints which are deleted after job cancellation
        env.getCheckpointConfig().enableExternalizedCheckpoints(ExternalizedCheckpointCleanup.DELETE_ON_CANCELLATION);


        // enables the experimental unaligned checkpoints
        //env.getCheckpointConfig().enableUnalignedCheckpoints();

        // End configurations ******************************************************************************************

        // configure event-time and watermarks
        //env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        //env.getConfig().setAutoWatermarkInterval(1000L);

        // assign a timestamp extractor to the consumer
        //myConsumer.assignTimestampsAndWatermarks(new TrafficEventTSExtractor(MAX_EVENT_DELAY));
        myConsumer.assignTimestampsAndWatermarks(WatermarkStrategy.forBoundedOutOfOrderness(Duration.ofSeconds(1)));

        // create direct kafka stream
        DataStream<TrafficEvent> trafficEventStream =
            env.addSource(myConsumer)
                .name("KafkaSource")
                .setParallelism(partitions);

        // Point of interest
        Point point = new Point(52.51623f, 13.38532f); // centroid
        DataStream<String> trafficNotificationStream =
            trafficEventStream
                .filter(new POIFilter(point, 1000))
                .name("POIFilter")
                .uid("poi-filter-id")
                .keyBy(TrafficEvent::getLp)
                .window(SlidingEventTimeWindows.of(Time.seconds(5), Time.seconds(1)))
                .process(new AvgSpeedWindow(updateInterval))
                .name("AvgSpeedWindow")
                .uid("window-id")
                .filter(new SpeedingFilter(speedLimit))
                .name("SpeedFilter")
                .uid("speed-filter-id")
                .map(new VehicleEnricher())
                .name("VehicleEnricher")//.startNewChain();
                .uid("enricher-id");

        // write notifications to kafka
        myProducer.setWriteTimestampToKafka(true);
        trafficNotificationStream
            .addSink(myProducer)
            .name("KafkaSink-" + RandomStringUtils.random(10, true, true))
            .uid("sink-id");

        env.execute(jobName);
    }
}
