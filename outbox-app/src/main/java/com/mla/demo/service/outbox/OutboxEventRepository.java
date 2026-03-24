package com.mla.demo.service.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEventJpaEntity o WHERE o.createdAt < :cutoffDate")
    void deleteOlderThan(@Param("cutoffDate") Instant cutoffDate);
}
