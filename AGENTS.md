# AGENTS.md - Guide for AI Coding Agents

## Project Overview

This is a **Transactional Outbox Pattern** demonstration using Spring Boot 3, PostgreSQL, Debezium, Kafka, and Apache Avro. The core architecture guarantees exactly-once delivery of business events through database-level consistency.

## Architecture & Key Patterns

### Data Flow & Event Publishing

The codebase implements a **write-ahead event log** pattern with CDC (Change Data Capture):

1. **Business Logic writes atomically** (JPA transaction):
   - Business entities (`TeamEntity`, `MemberEntity`) saved to PostgreSQL
   - Outbox record simultaneously inserted in same transaction (ensures atomicity)
   - Outbox payload is pre-serialized Avro binary (see `OutboxEventPublisher.publish()`)

2. **Debezium captures change** (CDC via PostgreSQL logical replication):
   - Monitors `outbox_events` table using WAL (Write-Ahead Log)
   - `EventRouter` SMT extracts topic from `topic` column and payload from `payload` column
   - Routes directly to Kafka topics named by event (e.g., `TeamEvent`)
   - Maintains `debezium_heartbeat` table to advance WAL position

3. **Kafka has the event stream** ready for consumption

### Critical Classes & Their Responsibilities

| Class | Purpose | Key Pattern |
|-------|---------|-------------|
| `OutboxEventPublisher` | Serializes Avro payload + saves outbox row | `@Transactional(propagation = MANDATORY)` - fails if not in transaction |
| `OutboxProducer<K,V>` | Generic wrapper for topic-specific publishing | Avoids boilerplate; topic name passed in constructor |
| `TeamService.createTeamWithMembers()` | Business logic + event emission | Single `@Transactional` method; publishes via `teamEventProducer.publish()` |
| `OutboxEventJpaEntity` | Database row mapping | Stores: `topic`, `aggregateId`, `eventType`, `payload` (bytes), `createdAt` |
| `TeamEvent` (Avro generated) | Event schema in Avro binary | Generated from `src/main/avro/teamEvent.avsc` during `mvnw clean install` |

### Transactionality & Consistency

**Critical invariant**: Outbox events published within the same Spring `@Transactional` method as business entity saves.

```java
@Transactional  // Single DB transaction
public void createTeamWithMembers(...) {
    teamRepository.save(team);  // persists team
    teamEventProducer.publish(...);  // publishes event in SAME transaction
}
```

This guarantees:
- If save fails, event publish doesn't happen (no orphaned events)
- If event publish fails, save is rolled back (event always exists if entity exists)
- Debezium catches the committed event and routes to Kafka

**Important**: `OutboxEventPublisher.publish()` uses `propagation = MANDATORY`, meaning it **requires** an active transaction and will fail if called outside one.

## Build & Development Workflow

### Initial Setup

```bash
# 1. Generate Avro classes & build JAR
./mvnw clean install

# 2. Start infrastructure (PostgreSQL, Kafka, Debezium, Schema Registry, Kafka UI)
docker compose up -d --build

# 3. Run the Spring Boot app
./mvnw spring-boot:run

# 4. Register Debezium connector (after Kafka Connect is ready at :8083)
./register-connector.sh
```

### Key Endpoints & Commands

- **API docs**: http://localhost:8080/swagger-ui.html
- **Kafka UI**: http://localhost:8085
- **Create team**: `curl -X POST "http://localhost:8080/api/teams" -H "Content-Type: application/json" -d '{...}'`
- **Verify Avro events**: `docker exec -it schema-registry kafka-avro-console-consumer --bootstrap-server kafka:29092 --topic TeamEvent --from-beginning`
- **Check connector status**: `curl -s http://localhost:8083/connectors/debezium-postgres-source-connector/status | jq`

### Maven & Avro Code Generation

The `avro-maven-plugin` auto-generates Java classes from `.avsc` files during `mvnw clean install`:
- `src/main/avro/teamEvent.avsc` → `target/generated-sources/com/mla/demo/avro/TeamEvent.java`
- These are Avro `SpecificRecord` implementations with builder patterns
- Always regenerate after modifying `.avsc` schemas

## Project-Specific Conventions

### Event Publishing Pattern

New event types follow the `OutboxProducer<K, V>` + service pattern:

```java
// In service class constructor:
private final OutboxProducer<UUID, MemberEvent> memberEventProducer;

public SomeService(OutboxEventPublisher publisher) {
    this.memberEventProducer = new OutboxProducer<>(publisher, "MemberEvent");
}

// In @Transactional business method:
memberEventProducer.publish(aggregateId, avroEvent, OutboxEventType.CREATE);
```

### Database Schema & Migrations

Flyway migrations in `src/main/resources/db/migration/`:
- `V1__init.sql`: Outbox table + debezium heartbeat
- `V2__team_and_member.sql`: Business entities
- Indices on `outbox_events(topic, aggregate_id, created_at DESC)` are critical for CDC performance

### Avro Schema Design

Schemas in `src/main/avro/`:
- Record names match Kafka topic names (e.g., `TeamEvent` publishes to `TeamEvent` topic)
- Namespace must be `com.mla.demo.avro` (matches generated class package)
- Use `["null", "type"]` unions for optional fields with `"default": null`
- Rebuild code after any schema changes: `mvnw clean install`

### Configuration & Secrets

- `application.yml`: Spring config, datasource, schema registry URL
- Schema Registry endpoint: `${app.kafka.schema-registry-url}` (default: `http://localhost:8081`)
- PostgreSQL WAL must be set to `logical` level (see `docker-compose.yml` postgres config)

## Integration Points & Dependencies

### PostgreSQL WAL Requirement

Debezium uses PostgreSQL **logical replication** to capture changes. The container is configured with `wal_level=logical`. This is non-negotiable for CDC to work.

### Schema Registry Integration

`OutboxConfig.java` creates a `KafkaAvroSerializer` bean that:
- Points to Confluent Schema Registry at runtime
- Pre-serializes Avro records to binary payload before storing in DB
- Decouples schema evolution from database schema

### Debezium Connector Configuration

`debezium-postgres-source-connector.json` registers a source connector that:
- Uses PostgreSQL plugin (pgoutput) for logical replication
- Applies `EventRouter` SMT to extract topic from `topic` column
- Applies `ExtractField` SMT to extract binary `payload` into message value
- Routes events to Kafka topics dynamically based on outbox row's `topic` field

## Common Development Tasks

### Adding a New Event Type

1. Create `.avsc` schema in `src/main/avro/` (e.g., `memberEvent.avsc`)
2. Ensure namespace is `com.mla.demo.avro`, record name matches topic
3. Run `mvnw clean install` to generate Java class
4. Create `OutboxProducer<K, MemberEvent>` in relevant service
5. Emit via `publish(aggregateId, event, eventType)` within `@Transactional` method

### Debugging Event Flow

- **Check DB**: `SELECT * FROM outbox_events WHERE topic = 'TeamEvent' ORDER BY created_at DESC`
- **Check Kafka**: Use Kafka UI at `http://localhost:8085` or console consumer
- **Check Connector logs**: `docker logs kafka-connect` (if using custom image)
- **Verify Schema Registry**: `curl http://localhost:8081/subjects`

### Testing the Pattern

- Create team via `/api/teams` endpoint
- Poll `outbox_events` table to verify row exists
- Poll Kafka `TeamEvent` topic to verify Debezium routed the message
- Confirm event is Avro binary format (use console consumer with schema registry)

## Build Artifacts & Clean State

- `./mvnw clean` removes `target/` and all generated Avro classes
- `docker compose down -v` removes containers and volumes (resets PostgreSQL)
- Regenerate Avro classes after any `.avsc` changes with `./mvnw install`

