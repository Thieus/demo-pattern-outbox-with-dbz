package com.mla.demo.service.outbox;

import org.apache.avro.specific.SpecificRecord;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class OutboxProducer<K, V extends SpecificRecord> {

    private final OutboxEventPublisher outboxEventPublisher;
    private final String topic;

    public OutboxProducer(OutboxEventPublisher outboxEventPublisher, String topic) {
        this.outboxEventPublisher = outboxEventPublisher;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(K aggregateId, V payload, OutboxEventType eventType) {
        outboxEventPublisher.publish(topic, aggregateId, payload, eventType);
    }
}
