package de.tu_berlin.dos.arm.iot_traffic_experiment.common.events;

import java.io.Serializable;
import java.util.Objects;

public class Point implements Serializable {

    public double lt;
    public double lg;

    public Point() { }

    public Point(double lt, double lg) {
        this.lt = lt;
        this.lg = lg;
    }

    public double getLt() {
        return lt;
    }

    public void setLt(double lt) {
        this.lt = lt;
    }

    public double getLg() {
        return lg;
    }

    public void setLg(double lg) {
        this.lg = lg;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Point point = (Point) o;
        return Double.compare(point.lt, lt) == 0 &&
               Double.compare(point.lg, lg) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lt, lg);
    }

    @Override
    public String toString() {
        return "{lt:" + lt + ",ln:" + lg + "}";
    }
}
