package io.portable.identity.events;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;

public final class TopicProbe {
    private TopicProbe() {}

    public static void main(String[] arguments) {
        ProviderConfig config = ProviderConfig.load();
        if (!config.runtimeMode.equals("bundled")) {
            throw new IllegalArgumentException("payload-consuming topic probe is local bundled-mode only");
        }
        Properties properties = new Properties();
        properties.putAll(config.producerProperties);
        properties.remove("key.serializer");
        properties.remove("value.serializer");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "identity-event-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        int users = 0, admins = 0, security = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(config.userTopic, config.adminTopic, config.securityTopic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && (users == 0 || admins == 0)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    String payload = record.value();
                    if (payload.contains("forbidden-secret-canary")) {
                        throw new IllegalStateException("secret canary crossed Kafka boundary");
                    }
                    if (record.topic().equals(config.userTopic)) users++;
                    else if (record.topic().equals(config.adminTopic)) admins++;
                    else if (record.topic().equals(config.securityTopic)) security++;
                }
            }
        }
        if (users == 0 || admins == 0) {
            throw new IllegalStateException("Kafka integration probe lacked user or admin events");
        }
        System.out.println("identity-event Kafka probe passed user=" + users + " admin=" + admins + " security=" + security);
    }
}
