package io.portable.identity.events;

import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.admin.AdminClient;

final class RuntimeTopologyValidator {
    private RuntimeTopologyValidator() {}

    static void validate(ProviderConfig config) {
        List<String> topics = List.of(
            config.userTopic, config.adminTopic, config.securityTopic, config.deadLetterTopic
        );
        try (AdminClient admin = AdminClient.create(config.producerProperties)) {
            var descriptions = admin.describeTopics(topics).allTopicNames()
                .get(Duration.ofMillis(config.deliveryTimeoutMs).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            for (String topic : topics) {
                var description = descriptions.get(topic);
                if (description == null || description.partitions().size() != config.expectedPartitions) {
                    throw new IllegalStateException("runtime topic topology mismatch: " + topic);
                }
                if (description.partitions().stream().anyMatch(
                    partition -> partition.replicas().size() != config.expectedReplicationFactor)) {
                    throw new IllegalStateException("runtime topic replication mismatch: " + topic);
                }
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("runtime topic validation failed", failure);
        }
    }
}
