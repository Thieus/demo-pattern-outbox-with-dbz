CREATE TABLE IF NOT EXISTS outbox_events (
    id                  UUID PRIMARY KEY,
    topic               VARCHAR(255) NOT NULL,
    aggregate_id        VARCHAR(255) NOT NULL,
    event_type          VARCHAR(50) NOT NULL,
    payload             BYTEA NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_topic ON outbox_events (topic);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id ON outbox_events (aggregate_id);
CREATE INDEX IF NOT EXISTS idx_outbox_created_at ON outbox_events(created_at DESC);

CREATE TABLE IF NOT EXISTS debezium_heartbeat (
    id INT PRIMARY KEY,
    last_heartbeat TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO debezium_heartbeat (id, last_heartbeat) VALUES (1, CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING;
