package de.tu_berlin.cit.iot_delivery_vehicles_experiment.processor;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Cluster.Builder;
import de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.iot_delivery_vehicles.Point;
import de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.iot_delivery_vehicles.TrafficEvent;
import de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.iot_delivery_vehicles.TrafficEvent.VehicleType;
import de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.utils.FileReader;
import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.tuple.Tuple6;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.cassandra.ClusterBuilder;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer.Semantic;
import org.apache.flink.streaming.connectors.kafka.KafkaSerializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.log4j.Logger;

import java.util.Properties;

public class Run {

    // traffic events are at most 60 sec out-of-order.
    private static final int MAX_EVENT_DELAY = 60;
    private static final Logger LOG = Logger.getLogger(Run.class);

    // class to filter traffic events within point of interest
    public static class POIFilter implements FilterFunction<TrafficEvent> {

        public final Point point;
        public final int radius;
        public VehicleType type;

        public POIFilter(Point point, int radius) {

            this.point = point;
            this.radius = radius;
        }

        @Override
        public boolean filter(TrafficEvent event) throws Exception {

            // Use Geodesic Inverse function to find distance in meters
            GeodesicData g1 = Geodesic.WGS84.Inverse(
                point.latitude,
                point.longitude,
                event.getPoint().latitude,
                event.getPoint().longitude);
            // determine if it is in the radius of the POE or not
            return g1.s12 <= radius;
        }
    }

    // Window to aggregate traffic events and calculate average speed in km/h
    public static class AvgSpeedWindow extends ProcessWindowFunction<TrafficEvent, Tuple6<Long, String, String, Double, Double, Integer>, String, TimeWindow> {

        public final int updateInterval;

        public AvgSpeedWindow(int updateInterval) {

            this.updateInterval = updateInterval;
        }

        @Override
        public void process(
                String vehicleId, Context context, Iterable<TrafficEvent> events,
                Collector<Tuple6<Long, String, String, Double, Double, Integer>> out) {

            String vehicleTypeId = "0";
            Point previous = null;
            double distance = 0;
            int count = 0;
            for (TrafficEvent event : events) {
                if (previous != null) {
                    GeodesicData g1 = Geodesic.WGS84.Inverse(
                        previous.latitude,
                        previous.longitude,
                        event.getPoint().latitude,
                        event.getPoint().longitude);
                    distance += g1.s12;
                    count++;
                }
                previous = event.getPoint();
                vehicleTypeId = event.getVehicleSegment();
            }
            // calculate time in hours
            double time = (count * updateInterval) / 3600000d;
            int avgSpeed = 0;
            if (time != 0) avgSpeed = (int) ((distance/1000) / time);
            assert previous != null;
            out.collect(new Tuple6<>(context.window().getEnd(), vehicleId, vehicleTypeId, previous.latitude, previous.longitude, avgSpeed));
        }
    }

    // filter to determine if traffic vehicle is traveling over the speed limit
    public static class SpeedingFilter implements FilterFunction<Tuple6<Long, String, String, Double, Double, Integer>> {

        public final int speedLimit;

        public SpeedingFilter(int speedLimit) {

            this.speedLimit = speedLimit;
        }

        @Override
        public boolean filter(Tuple6<Long, String, String, Double, Double, Integer> trafficVehicle) throws Exception {

            return trafficVehicle.f5 >= speedLimit;
        }
    }

    // Retrieve vehicle type from database and parse json, builder is parsed to stop serialization error
    public static class VehicleTypeMapper extends RichMapFunction<Tuple6<Long, String, String, Double, Double, Integer>, String> {

        //private ClusterBuilder builder;

        public VehicleTypeMapper(ClusterBuilder builder) {

            //this.builder = builder;
        }

        @Override
        public String map(Tuple6<Long, String, String, Double, Double, Integer> notification) throws Exception {

            String description = "unknown";
            //if (VehicleSegmentCache.containsKey(notification.f2)) {

                //description = VehicleSegmentCache.get(notification.f2);
            //}
            //else {

                //String query = "SELECT description FROM TrafficKeySpace.Vehicle_Segments where id='" + notification.f2 + "';";
                //ResultSet result = Cassandra.execute(query, builder);
                //Iterator<Row> rows = result.iterator();
                //while (rows.hasNext()) {

                    //description = rows.next().getString("description");
                    //VehicleSegmentCache.put(notification.f2, description);
                //}
            //}

            return "{ timestamp: " + notification.f0 +
                    ", vehicleId: '" + notification.f1 + "'" +
                    ", description: '" + description + "'" +
                    ", latitude: " + notification.f3 +
                    ", longitude: " + notification.f4 +
                    ", avgSpeed: " + notification.f5 + "}";
        }
    }

    public static void main(String[] args) throws Exception {

        // ensure checkpoint interval is supplied as an argument
        if (args.length != 1) {
            throw new IllegalStateException("Required Command line argument: [CHECKPOINT_INTERVAL]");
        }
        int interval = Integer.parseInt(args[0]);

        // retrieve properties from file
        Properties props = FileReader.GET.read("processor.properties", Properties.class);
        int updateInterval = Integer.parseInt(props.getProperty("traffic.updateInterval"));
        int speedLimit = Integer.parseInt(props.getProperty("traffic.speedLimit"));
        String hosts = props.getProperty("cassandra.host");
        int port = Integer.parseInt(props.getProperty("cassandra.port"));
        int windowSize = Integer.parseInt(props.getProperty("traffic.windowSize"));

        // setup cassandra cluster builder
        ClusterBuilder builder = new ClusterBuilder() {

            @Override
            protected Cluster buildCluster(Builder builder) {
            return builder
                .addContactPoints(hosts.split(","))
                .withPort(port)
                .build();
            }
        };

        // setup cassandra keyspace and tables if they dont exist
        /*Cassandra.execute("CREATE KEYSPACE IF NOT EXISTS TrafficKeySpace WITH replication = {'class':'SimpleStrategy', 'replication_factor':1};", builder);
        Cassandra.execute("DROP TABLE IF EXISTS TrafficKeySpace.Vehicle_Segments;", builder);
        Cassandra.execute("CREATE TABLE IF NOT EXISTS TrafficKeySpace.Vehicle_Segments(id text, description text, PRIMARY KEY (id));", builder);
        for (VehicleSegment segment : VehicleSegment.values()) {

            Cassandra.execute("INSERT INTO TrafficKeySpace.Vehicle_Segments(id, description) VALUES('" + segment + "', '" + segment.description + "');", builder);
        }*/

        // setup Kafka consumer
        Properties kafkaConsumerProps = new Properties();

        kafkaConsumerProps.setProperty("bootstrap.servers", props.getProperty("kafka.brokers")); // Broker default host:port
        kafkaConsumerProps.setProperty("group.id", props.getProperty("kafka.consumer.group"));   // Consumer group ID
        kafkaConsumerProps.setProperty("auto.offset.reset", "latest");                           // Always read topic from start

        FlinkKafkaConsumer<TrafficEvent> myConsumer =
            new FlinkKafkaConsumer<>(
                props.getProperty("kafka.consumer.topic"),
                new TrafficEventSchema(),
                kafkaConsumerProps);

        // setup Kafka producer
        Properties kafkaProducerProps = new Properties();
        kafkaProducerProps.setProperty("bootstrap.servers", props.getProperty("kafka.brokers")); // Broker default host:port
        kafkaProducerProps.setProperty(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG,"900000");
        kafkaProducerProps.setProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG,"my-transaction");
        kafkaProducerProps.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        FlinkKafkaProducer<String> myProducer =
            new FlinkKafkaProducer<>(
                props.getProperty("kafka.producer.topic"),
                (KafkaSerializationSchema<String>) (notification, aLong) -> {
                    return new ProducerRecord<>(props.getProperty("kafka.producer.topic"), notification.getBytes());
                },
                kafkaProducerProps,
                Semantic.EXACTLY_ONCE);

        // Start configurations ****************************************************************************************

        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Disable Operator chaining for fine grain monitoring
        env.disableOperatorChaining();

        // setting global properties from file
        /*Map<String, String> propsMap = new HashMap<>();
        for (final String name: props.stringPropertyNames()) {
            propsMap.put(name, props.getProperty(name));
        }
        env.getConfig().setGlobalJobParameters(ParameterTool.fromMap(propsMap));
        LOG.info(env.getConfig());*/

        // configuring RocksDB state backend to use HDFS
        String backupFolder = props.getProperty("hdfs.backupFolder");
        StateBackend backend = new RocksDBStateBackend(backupFolder, true);
        env.setStateBackend(backend);

        // start a checkpoint based on supplied interval
        env.enableCheckpointing(interval);

        // set mode to exactly-once (this is the default)
        env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);

        // make sure 500 ms of progress happen between checkpoints
        //env.getCheckpointConfig().setMinPauseBetweenCheckpoints(500);

        // checkpoints have to complete within two minute, or are discarded
        env.getCheckpointConfig().setCheckpointTimeout(380000);

        // no external services which could take some time to respond, therefore 1
        // allow only one checkpoint to be in progress at the same time
        //env.getCheckpointConfig().setMaxConcurrentCheckpoints(10);

        // enable externalized checkpoints which are deleted after job cancellation
        env.getCheckpointConfig().enableExternalizedCheckpoints(ExternalizedCheckpointCleanup.DELETE_ON_CANCELLATION);

        // allow job recovery fallback to checkpoint when there is a more recent savepoint
        env.getCheckpointConfig().setPreferCheckpointForRecovery(true);

        //env.getCheckpointConfig().setFailOnCheckpointingErrors();

        // End configurations ******************************************************************************************

        // configure event-time and watermarks
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        env.getConfig().setAutoWatermarkInterval(1000L);

        // assign a timestamp extractor to the consumer
        myConsumer.assignTimestampsAndWatermarks(new TrafficEventTSExtractor(MAX_EVENT_DELAY));

        // create direct kafka stream
        DataStream<TrafficEvent> trafficEventStream =
            env.addSource(myConsumer)
                .name("KafkaSource")
                .setParallelism(Integer.parseInt(props.getProperty("kafka.partitions")));

        // Point of interest
        Point point = new Point(52.51623249582298, 13.385324913035554); // centroid

        DataStream<String> trafficNotificationStream =
            trafficEventStream
                .filter(new POIFilter(point, 1000))
                .name("POIFilter")
                .keyBy(TrafficEvent::getVehicleId)
                .timeWindow(Time.milliseconds(windowSize))
                .process(new AvgSpeedWindow(updateInterval))
                .name("Window")
                .filter(new SpeedingFilter(speedLimit))
                .name("SpeedFilter")
                .map(new VehicleTypeMapper(builder))
                .name("TypeMap");//.startNewChain();

        // write notifications to kafka
        myProducer.setWriteTimestampToKafka(true);
        trafficNotificationStream
            .addSink(myProducer)
            .name("KafkaSink");

        env.execute("Traffic");
    }
}
