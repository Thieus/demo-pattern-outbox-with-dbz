# Demo Pattern Outbox with Debezium and Avro

This project is a demonstration of the **Transactional Outbox Pattern** using **Debezium**, **Kafka**, and **Apache Avro**. It is built with Spring Boot 3, Spring Data JPA, and PostgreSQL.

## Architecture Overview
1. **Spring Boot App**: The business logic creates `Team` and `Member` entities and simultaneously inserts an `outbox_events` record in a single database transaction. The outbox payload is a strict Avro binary.
2. **PostgreSQL**: Stores the business entities, the outbox events table, and the heartbeat table.
3. **Debezium Connector**: Monitors the `outbox_events` table using PostgreSQL logical replication. It uses `EventRouter` (SMT) to route events based on the outbox `topic` column and extracts the `payload` (Avro format) directly into Kafka.
4. **Heartbeat**: Debezium maintains a heartbeat table (`debezium_heartbeat`) to advance the Postgres WAL.

## Getting Started

### 1. Build the application and generate Avro classes
```bash
./mvnw clean install
```
This step will download dependencies and use the `avro-maven-plugin` to generate Java classes for `TeamEvent` based on `src/main/avro/teamEvent.avsc`.

### 2. Start the Infrastructure (Docker Compose)
Start PostgreSQL, Zookeeper, Kafka, Schema Registry, Kafka Connect (Debezium), and Kafka UI:
```bash
docker compose up -d --build
```
Kafka UI is then available at `http://localhost:8085` to inspect topics, messages, schemas, and the Kafka Connect cluster.

### 3. Run the Application
Run the Spring Boot application from your IDE or using Maven:
```bash
./mvnw spring-boot:run
```
When you run the application for the first time, 
it will automatically create the necessary database tables (`team`, `member`, `outbox_events`, `debezium_heartbeat`) in PostgreSQL.

### OpenAPI / Swagger UI
Once the application is running, the API documentation is automatically available here:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`

### 4. Deploy the Debezium Connector PostgreSQL Source Connector
Once Kafka Connect is up (`curl localhost:8083`), register the connector:
```bash
./register-connector.sh
```
Check status:
```bash
curl -s http://localhost:8083/connectors/debezium-postgres-source-connector/status | jq
```

### 5. Check PostgreSQL replication slots
Use this ready-to-run command to monitor Debezium replication slot retention and activity:
```bash
docker exec -it postgres \
  psql -U postgres -d demo \
  -c "SELECT slot_name, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained, active, restart_lsn, confirmed_flush_lsn FROM pg_replication_slots;"
```

### 6. Create a Team and Member
Use the Swagger UI or any REST client to create a team and a member:
```bash
curl -X POST "http://localhost:8080/api/teams" \
     -H "Content-Type: application/json" \
     -d '{
           "name": "Team Alpha",
           "members": [
             {"name": "Alice"},
             {"name": "Bob"}
           ]
         }'
```

### 7. Verify the Outbox Message
You can also inspect the topic directly from Kafka UI at `http://localhost:8085`.

-> Topic `TeamEvent` is auto-created by Debezium when it routes the outbox events. You should see messages with the Avro payload corresponding to the team and member creation events.
-> Schema Registry will show the registered Avro schema for `TeamEvent` when you inspect the topic in Kafka UI.

