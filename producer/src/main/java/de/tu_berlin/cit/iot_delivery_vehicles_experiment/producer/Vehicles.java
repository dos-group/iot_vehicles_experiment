package de.tu_berlin.cit.iot_delivery_vehicles_experiment.producer;

import akka.actor.AbstractActor;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.Status.Failure;
import de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.iot_delivery_vehicles.Point;
import de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.iot_delivery_vehicles.TrafficEvent;
import de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.iot_delivery_vehicles.TrafficEvent.VehicleType;
import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.log4j.Logger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class Vehicles {

    static final Logger LOG = Logger.getLogger(Run.class);
    static final ActorSystem SYSTEM = ActorSystem.create("vehicle-system");

    public static class VehicleActor extends AbstractActor {

        static final Logger LOG = Logger.getLogger(VehicleActor.class);
        static final AtomicInteger VEHICLE_COUNT = new AtomicInteger(0);
        static final Random RAND = new Random();

        private int updateInterval;
        private List<Point> waypoints;

        static Props props(int updateInterval, List<Point> waypoints) {
            return Props.create(VehicleActor.class, updateInterval, waypoints);
        }

        static final class Emit {
            String topic;
            KafkaProducer<String, TrafficEvent> kafkaProducer;

            Emit(String topic, KafkaProducer<String, TrafficEvent> kafkaProducer) {
                this.topic = topic;
                this.kafkaProducer = kafkaProducer;
            }
        }

        public VehicleActor(int updateInterval, List<Point> waypoints) {
            this.updateInterval = updateInterval;
            this.waypoints = waypoints;
        }

        @Override
        public void preStart() {
            VEHICLE_COUNT.getAndIncrement();
        }

        @Override
        public void postStop() {
            VEHICLE_COUNT.getAndDecrement();
        }

        private List<TrafficEvent> generateTrafficEvents() {
            // generate static vehicle event values
            String vehicleId = UUID.randomUUID().toString();
            String vehicleType = VehicleType.getRandomVehicleType();
            // mark initial timestamp and instantiate list for all vehicle events
            Date timestamp = new Date();
            List<TrafficEvent> trafficEvents = new ArrayList<>();
            // loop through waypoints in pairs to process road section
            for (int i = 0; i < this.waypoints.size() - 1; ++i) {
                // get waypoint pairs
                Point p1 = this.waypoints.get(i);
                Point p2 = this.waypoints.get(i + 1);
                // Use Geodesic Inverse function to find distance in meters and angle between waypoints
                GeodesicData g1 = Geodesic.WGS84.Inverse(p1.latitude, p1.longitude, p2.latitude, p2.longitude);
                // generate random average speed for road section
                double avgSpeed = RAND.nextInt(80 - 10) + 10;
                // set distance to zero for start of road section and loop until distance is reached
                double currDistance = 0.0;
                while (currDistance < g1.s12) {
                    // Use Geodesic Direct function to determine current lat and long coordinates
                    GeodesicData g2 = Geodesic.WGS84.Direct(g1.lat1, g1.lon1, g1.azi1, currDistance);
                    // create vehicle event and add to vehicle event list
                    trafficEvents.add(new TrafficEvent(vehicleId, vehicleType, new Point(g2.lat2, g2.lon2), timestamp));
                    // increment timestamp by time interval for next event
                    timestamp = Date.from(timestamp.toInstant().plusMillis(this.updateInterval));
                    // calculate new distance based on average speed (in meters per second) and update interval (in milliseconds)
                    currDistance += (avgSpeed / 3.6) * (this.updateInterval / 1000f);
                }
            }
            return trafficEvents;
        }

        @Override
        public Receive createReceive() {

            return receiveBuilder()
                .match(Emit.class, e -> {
                    try {
                        // generate list of vehicle events
                        List<TrafficEvent> events = this.generateTrafficEvents();
                        // loop through events and emit them to kafka
                        for (int i = 0; i < events.size(); ++i) {
                            TrafficEvent event = events.get(i);
                            // determine how long we need to wait to emit this event
                            long wait = event.getTimestamp().getTime() - System.currentTimeMillis();
                            // overdue, so emit event to kafka
                            if (wait <= 0) {
                                e.kafkaProducer.send(new ProducerRecord<>(e.topic, event));
                            }
                            // ensure actor stays alive until after all last message has been sent
                            else if (i == events.size() - 1) {
                                SYSTEM.scheduler()
                                    .scheduleOnce(Duration.ofMillis(wait), () -> {
                                        e.kafkaProducer.send(new ProducerRecord<>(e.topic, event.getVehicleId(), event));
                                        context().stop(self());
                                    }, SYSTEM.dispatcher());
                            }
                            // if event is not overdue, schedule it to be sent at the appropriate time
                            else {
                                SYSTEM.scheduler()
                                    .scheduleOnce(Duration.ofMillis(wait), () -> {
                                        e.kafkaProducer.send(new ProducerRecord<>(e.topic, event));
                                    }, SYSTEM.dispatcher());
                            }
                        }
                    }
                    catch (Exception ex) {
                        getSender().tell(new Failure(ex), getSelf());
                    }
                })
                .matchAny(o -> LOG.error("received unknown message: " + o))
                .build();
        }
    }
}
