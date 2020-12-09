package de.tu_berlin.cit.iot_delivery_vehicles_experiment.common.iot_delivery_vehicles;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.*;

public class TrafficEvent {

    public enum VehicleType {
        HUMAN("Human"),
        SELF_DRIVING("Self-Driving");

        private static final List<VehicleType> VALUES = Collections.unmodifiableList(Arrays.asList(values()));
        private static final int SIZE = VALUES.size();
        private static final Random RANDOM = new Random();

        public static String getRandomVehicleType() {
            int low = 1;
            int high = 100;
            int result = RANDOM.nextInt(high-low) + low;
            if (result <= 20) return SELF_DRIVING.toString();
            else return HUMAN.toString();
        }

        public final String description;

        VehicleType(String description) {

            this.description = description;
        }
    }

    private String vehicleId;
    private String vehicleSegment;
    private Point point;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone="UTC")
    private Date timestamp;

    public TrafficEvent() { }

    public TrafficEvent(String vehicleId, String vehicleSegment, Point point, Date timestamp/*, double speed*/) {
        this.vehicleId = vehicleId;
        this.vehicleSegment = vehicleSegment;
        this.point = point;
        this.timestamp = timestamp;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public void setVehicleSegment(String vehicleSegment) {
        this.vehicleSegment = vehicleSegment;
    }

    public void setPoint(Point point) {
        this.point = point;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public String getVehicleSegment() {
        return vehicleSegment;
    }

    public Point getPoint() {
        return point;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "{vehicleId:" + vehicleId + ", vehicleSegment:" + vehicleSegment + ", location:" + point + ", timestamp:" + timestamp + '}';
    }
}
