package com.mla.demo.service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class PurgeOlderOutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(PurgeOlderOutboxEventService.class);
    private final OutboxEventRepository outboxEventRepository;

    @Value("${app.outbox.purge.retention-period-days:30}")
    private long retentionPeriodDays;

    public PurgeOlderOutboxEventService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(cron = "${app.outbox.purge.cron:0 0 6 * * *}")
    public void purgeOlderOutboxEvents() {
        log.info("Starting to purge outbox events older than {} days", retentionPeriodDays);
        Instant cutoffDate = Instant.now().minus(Duration.ofDays(retentionPeriodDays));
        outboxEventRepository.deleteOlderThan(cutoffDate);
        log.info("Finished purging older outbox events");
    }
}
