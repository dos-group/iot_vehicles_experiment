package de.tu_berlin.cit.iot_delivery_vehicles_experiment.processor;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import org.apache.flink.streaming.connectors.cassandra.ClusterBuilder;

import java.io.Closeable;
import java.io.IOException;

public class Cassandra {

    public static class Connector implements Closeable {

        private static Connector self = null;
        public final Session session;

        private Connector(ClusterBuilder builder) {

            this.session = builder.getCluster().connect();
        }

        public static synchronized Connector getInstance(ClusterBuilder builder) {
            if (self == null) self = new Connector(builder);
            return self;
        }

        @Override
        public void close() throws IOException {

            this.session.close();
        }
    }

    public static ResultSet execute(String query, ClusterBuilder builder) {

        Connector connector = Connector.getInstance(builder);
        return connector.session.execute(query);
    }
}
