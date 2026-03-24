FROM quay.io/debezium/connect:3.4

USER root

RUN set -eux; \
  microdnf install -y ca-certificates curl tar findutils && microdnf clean all; \
  mkdir -p /kafka/connect /usr/local/bin /kafka/config; \
  curl -fSL https://client.hub.confluent.io/confluent-hub-client-latest.tar.gz -o /tmp/chub.tgz; \
  tar -xz -C /usr/local -f /tmp/chub.tgz; \
  rm -f /tmp/chub.tgz; \
  test -x /usr/local/bin/confluent-hub; \
  printf '%s\n' \
    'bootstrap.servers=dummy:9092' \
    'key.converter=org.apache.kafka.connect.storage.StringConverter' \
    'value.converter=org.apache.kafka.connect.json.JsonConverter' \
    'plugin.path=/kafka/connect' \
    > /tmp/worker.properties; \
  confluent-hub install --no-prompt \
    --component-dir /kafka/connect \
    --worker-configs /tmp/worker.properties \
    confluentinc/kafka-connect-avro-converter:7.5.0; \
  rm -f /tmp/worker.properties; \
  cp /kafka/connect/confluentinc-kafka-connect-avro-converter/lib/*.jar /kafka/connect/debezium-connector-postgres/; \
  cp /kafka/connect/debezium-connector-oracle/guava-*.jar /kafka/connect/debezium-connector-postgres/; \
  cp /kafka/connect/debezium-connector-oracle/failureaccess-*.jar /kafka/connect/debezium-connector-postgres/; \
  cp /kafka/connect/debezium-connector-oracle/error_prone_annotations-*.jar /kafka/connect/debezium-connector-postgres/; \
  cp /kafka/connect/debezium-connector-oracle/j2objc-annotations-*.jar /kafka/connect/debezium-connector-postgres/; \
  cp /kafka/connect/debezium-connector-oracle/jsr305-*.jar /kafka/connect/debezium-connector-postgres/

COPY --chown=kafka:kafka docker/connect/log4j2.xml /kafka/config/log4j2.xml
COPY --chown=kafka:kafka docker/connect/healthcheck.sh /usr/local/bin/healthcheck.sh

RUN chmod +x /usr/local/bin/healthcheck.sh

ENV CONNECT_PLUGIN_PATH="/kafka/connect"
ENV KAFKA_LOG4J_OPTS="-Dlog4j.configurationFile=/kafka/config/log4j2.xml"

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=12 CMD ["/usr/local/bin/healthcheck.sh"]

USER kafka

EXPOSE 8083

