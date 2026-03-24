#!/bin/bash
set -e

CONNECT_URL="http://localhost:8083"
CONNECTOR_CONFIG_FILE="debezium-postgres-source-connector.json"

echo "Waiting for Kafka Connect to start..."
while [ $(curl -s -o /dev/null -w %{http_code} ${CONNECT_URL}/connectors) -ne 200 ] ; do
  echo -e "\tKafka Connect is not ready yet..."
  sleep 5
done

echo "Kafka Connect is up! Registering connector..."

CONNECTOR_NAME=$(grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' "$CONNECTOR_CONFIG_FILE" | head -1 | sed 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')

if [ -z "$CONNECTOR_NAME" ]; then
    echo "Unable to extract connector name"
    exit 1
fi

check_response=$(curl -s -o /dev/null -w "%{http_code}" "$CONNECT_URL/connectors/$CONNECTOR_NAME")

if [ "$check_response" -eq 200 ]; then
    echo "Updating existing connector $CONNECTOR_NAME..."
    config_json=$(awk '/"config": \{/,/^  \}/' "$CONNECTOR_CONFIG_FILE" | sed '1s/.*"config": //' | sed '$s/^  }/}/')
    curl -s -X PUT -H "Content-Type: application/json" -d "$config_json" "$CONNECT_URL/connectors/$CONNECTOR_NAME/config"
else
    echo "Creating new connector $CONNECTOR_NAME..."
    curl -s -X POST -H "Content-Type: application/json" -d @"$CONNECTOR_CONFIG_FILE" "$CONNECT_URL/connectors"
fi

echo -e "\nConnector registration completed!"
