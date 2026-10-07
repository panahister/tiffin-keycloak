package io.portable.identity.events;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;

public final class TopicAdmin {
    private TopicAdmin() {}

    public static void main(String[] arguments) throws Exception {
        KafkaAdminConfig config = KafkaAdminConfig.load();
        Map<String, String> topicConfig = new LinkedHashMap<>();
        topicConfig.put("min.insync.replicas", Integer.toString(config.minimumInSyncReplicas));
        topicConfig.put("cleanup.policy", "delete");
        String retention = config.retentionMs;
        if (!retention.isBlank()) topicConfig.put("retention.ms", Long.toString(Long.parseLong(retention)));
        List<String> names = config.topics;
        List<NewTopic> topics = new ArrayList<>();
        for (String name : names) topics.add(new NewTopic(name, config.partitions, config.replicationFactor).configs(topicConfig));
        try (AdminClient admin = AdminClient.create(config.properties)) {
            try { admin.createTopics(topics).all().get(); }
            catch (ExecutionException failure) {
                if (!(root(failure) instanceof TopicExistsException)) throw failure;
            }
            Map<String, org.apache.kafka.clients.admin.TopicDescription> descriptions =
                admin.describeTopics(names).allTopicNames().get();
            Map<ConfigResource, Config> configurations = admin.describeConfigs(
                names.stream().map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name)).toList()
            ).all().get();
            for (String name : names) {
                var description = descriptions.get(name);
                if (description == null || description.partitions().size() != config.partitions) {
                    throw new IllegalStateException("topic topology mismatch: " + name);
                }
                if (description.partitions().stream().anyMatch(item -> item.replicas().size() != config.replicationFactor)) {
                    throw new IllegalStateException("topic replication mismatch: " + name);
                }
                Config configuration = configurations.get(new ConfigResource(ConfigResource.Type.TOPIC, name));
                String actualMinimumInSync = configuration == null || configuration.get("min.insync.replicas") == null
                    ? null : configuration.get("min.insync.replicas").value();
                if (!Integer.toString(config.minimumInSyncReplicas).equals(actualMinimumInSync)) {
                    throw new IllegalStateException("topic min ISR mismatch: " + name);
                }
            }
            System.out.println("validated identity event topics count=" + names.size());
        }
    }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }
}
