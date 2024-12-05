# Use Maven image for building
FROM maven:3.8-openjdk-11


# Set working directory
WORKDIR /app

COPY target/fat-jar/scylla-cdc-replicator-1.3.4-SNAPSHOT-jar-with-dependencies.jar /app/scylla-cdc-replicator.jar
COPY src/main/resources/logging.properties /app/logging.properties

# Download JMX Exporter
RUN wget https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.19.0/jmx_prometheus_javaagent-0.19.0.jar -O /app/jmx_prometheus_javaagent.jar

# Create JMX Exporter config
RUN echo '{}' > /app/jmx_config.yaml

# Set default memory values
ENV JAVA_XMS="5g"
ENV JAVA_XMX="5g"

# Set the logging properties with configurable memory controls
ENV JAVA_OPTS="-Xms${JAVA_XMS} -Xmx${JAVA_XMX} -XX:+UseG1GC -Djava.util.logging.config.file=/app/logging.properties -javaagent:/app/jmx_prometheus_javaagent.jar=8080:/app/jmx_config.yaml"

# Create an entrypoint script that allows environment variable expansion
RUN echo '#!/bin/sh\n\
java ${JAVA_OPTS} -cp /app/scylla-cdc-replicator.jar "com.scylladb.cdc.replicator.Main" "$@"' > /app/entrypoint.sh && \
chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]