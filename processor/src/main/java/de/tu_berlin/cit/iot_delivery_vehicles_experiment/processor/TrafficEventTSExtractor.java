package de.tu_berlin.cit.iot_delivery_vehicles_experiment.processor;

import de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.iot_delivery_vehicles.TrafficEvent;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.windowing.time.Time;

public class TrafficEventTSExtractor extends BoundedOutOfOrdernessTimestampExtractor<TrafficEvent> {

    public TrafficEventTSExtractor(int maxEventDelay) {
        super(Time.seconds(maxEventDelay));
    }

    @Override
    public long extractTimestamp(TrafficEvent trafficEvent) {
        return trafficEvent.getTimestamp().toInstant().toEpochMilli();
    }
}
