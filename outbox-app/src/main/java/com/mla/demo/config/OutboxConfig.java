package com.mla.demo.config;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;


@Configuration
public class OutboxConfig {

    @Value("${app.kafka.schema-registry-url}")
    private String schemaRegistryUrl;

    @Bean
    public KafkaAvroSerializer kafkaAvroSerializer() {
        Map<String, Object> config = new HashMap<>();
        config.put(SchemaRegistryClientConfig.CLIENT_NAMESPACE + "url", schemaRegistryUrl);

        KafkaAvroSerializer serializer = new KafkaAvroSerializer();
        serializer.configure(config, false);
        return serializer;
    }
}
