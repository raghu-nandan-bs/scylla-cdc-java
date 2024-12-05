package com.scylladb.cdc.replicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.scylladb.cdc.lib.CDCConsumer;
import com.scylladb.cdc.model.TableName;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import sun.misc.Signal;

public class Main {
    public static void main(String[] args) {
        // Parse command-line arguments.
        Namespace parsedArguments = parseArguments(args);
        Mode replicatorMode = Mode.fromString(parsedArguments.getString("mode"));
        String source = parsedArguments.getString("source");
        String destination = parsedArguments.getString("destination");
        String keyspace = parsedArguments.getString("keyspace");
        String table = parsedArguments.getString("table");
        ConsistencyLevel consistencyLevel = ConsistencyLevel.valueOf(parsedArguments.getString("consistency_level").toUpperCase());
        String sourceUsername = parsedArguments.getString("source_username");
        String sourcePassword = parsedArguments.getString("source_password");
        String destinationUsername = parsedArguments.getString("destination_username");
        String destinationPassword = parsedArguments.getString("destination_password");

        // Start replicating changes from source cluster to destination cluster
        // of selected tables.
        startReplicator(replicatorMode, source, destination, keyspace, table, consistencyLevel, sourceUsername, sourcePassword, destinationUsername, destinationPassword);
    }

    private static void startReplicator(Mode mode, String source, String destination, String keyspace, String tables,
                                        ConsistencyLevel consistencyLevel, String sourceUsername, String sourcePassword, String destinationUsername, String destinationPassword) {
        // Connect to the destination cluster with authentication if provided
        Cluster.Builder destinationBuilder = Cluster.builder().addContactPoint(destination);
        if (destinationUsername != null) {
            destinationBuilder.withCredentials(
                destinationUsername,
                destinationPassword
            );
        }
        
        try (Cluster destinationCluster = destinationBuilder.build();
             Session destinationSession = destinationCluster.connect()) {

            List<CDCConsumer> startedConsumers = new ArrayList<>();
            String[] tablesToReplicate = tables.split(",");

            for (String table : tablesToReplicate) {
                final String sourceTable;
                final String destinationTable;
                if (table.contains(":")) {
                    sourceTable = table.split(":")[0];
                    destinationTable = table.split(":")[1];
                } else {
                    sourceTable = table;
                    destinationTable = table;
                }
                validateTableExists(destinationCluster, keyspace, destinationTable,
                        "Before running the replicator, create the corresponding tables in your destination cluster.");

                // Start a CDCConsumer for each replicated table,
                // which will read the RawChanges and apply them
                // onto the destination cluster.
                CDCConsumer consumer = CDCConsumer.builder()
                        .addContactPoint(source)
                        // Add source authentication if provided
                        .withCredentials(
                            sourceUsername,
                            sourcePassword)
                        .withConsumerProvider((threadId) ->
                                new ReplicatorConsumer(mode, destinationCluster, destinationSession,
                                        keyspace, destinationTable, consistencyLevel))
                        .addTable(new TableName(keyspace, sourceTable))
                        .withWorkersCount(1)
                        .build();

                Optional<Throwable> validation = consumer.validate();
                if (validation.isPresent()) {
                    throw new ReplicatorValidationException("Validation error of the source table: " + validation.get().getMessage());
                }
                consumer.start();

                startedConsumers.add(consumer);
            }

            // Wait for SIGINT and gracefully terminate CDCConsumers.
            try {
                CountDownLatch terminationLatch = new CountDownLatch(1);
                Signal.handle(new Signal("INT"), signal -> terminationLatch.countDown());
                terminationLatch.await();

                for (CDCConsumer consumer : startedConsumers) {
                    consumer.stop();
                }
            } catch (InterruptedException e) {
                // Ignore exception.
            }
        } catch (ReplicatorValidationException ex) {
            System.err.println(ex.getMessage());
            System.exit(1);
        }
    }

    private static void validateTableExists(Cluster cluster, String keyspace, String table, String hint) throws ReplicatorValidationException {
        String clusterName = cluster.getClusterName() + " (" + cluster.getMetadata().getAllHosts().toString() + ")";

        KeyspaceMetadata keyspaceMetadata = cluster.getMetadata().getKeyspace(keyspace);
        if (keyspaceMetadata == null) {
            throw new ReplicatorValidationException(String.format("Missing keyspace %s in cluster: %s. %s",
                    keyspace, clusterName, hint));
        }

        TableMetadata tableMetadata = keyspaceMetadata.getTable(table);
        if (tableMetadata == null) {
            throw new ReplicatorValidationException(String.format("Missing table %s.%s in cluster: %s. %s",
                    keyspace, table, clusterName, hint));
        }
    }

    private static Namespace parseArguments(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("./scylla-cdc-replicator").build().defaultHelp(true);
        parser.addArgument("-m", "--mode").setDefault("delta").help("Mode of operation. Can be delta, preimage or postimage. Default is delta");
        parser.addArgument("-k", "--keyspace").required(true).help("Keyspace name");
        parser.addArgument("-t", "--table").required(true).help("Table names, provided as a comma delimited string. Optionally, each entry can be of format source_table_name:destination_table_name");
        parser.addArgument("-s", "--source").required(true).help("Address of a node in source cluster");
        parser.addArgument("-d", "--destination").required(true).help("Address of a node in destination cluster");
        parser.addArgument("-cl", "--consistency-level").setDefault("quorum")
                .help("Consistency level of writes. QUORUM by default");
        
        // Add new authentication arguments
        parser.addArgument("--source-username").help("Username for source cluster authentication");
        parser.addArgument("--source-password").help("Password for source cluster authentication");
        parser.addArgument("--destination-username").help("Username for destination cluster authentication");
        parser.addArgument("--destination-password").help("Password for destination cluster authentication");

        try {
            return parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(-1);
            return null;
        }
    }

    public enum Mode {
        DELTA, PRE_IMAGE, POST_IMAGE;

        public static Mode fromString(String mode) {
            switch (mode.toLowerCase()) {
                case "delta":
                    return DELTA;

                case "pre_image":
                case "preimage":
                    return PRE_IMAGE;

                case "post_image":
                case "postimage":
                    return POST_IMAGE;

                default:
                    throw new IllegalStateException("Unknown mode: " + mode);
            }
        }
    }
}