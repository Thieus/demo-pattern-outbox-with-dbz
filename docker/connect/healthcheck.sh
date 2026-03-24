#!/bin/sh
set -eu

curl -fsS http://localhost:8083/connectors >/dev/null
