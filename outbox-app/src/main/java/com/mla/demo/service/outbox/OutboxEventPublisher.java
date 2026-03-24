package com.mla.demo.service.outbox;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaAvroSerializer kafkaAvroSerializer;

    public OutboxEventPublisher(OutboxEventRepository outboxEventRepository,
                                KafkaAvroSerializer kafkaAvroSerializer) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaAvroSerializer = kafkaAvroSerializer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <K, V extends SpecificRecord> void publish(String topic, K aggregateId, V payload, OutboxEventType eventType) {

        OutboxEventJpaEntity outboxEvent = new OutboxEventJpaEntity();
        outboxEvent.setTopic(topic);
        outboxEvent.setAggregateId(aggregateId.toString());
        outboxEvent.setEventType(eventType);
        outboxEvent.setPayload(kafkaAvroSerializer.serialize(topic, payload));
        outboxEvent.setCreatedAt(Instant.now());

        outboxEventRepository.save(outboxEvent);
    }
}
