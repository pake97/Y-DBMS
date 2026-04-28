#!/usr/bin/env bash
# Seed the COVID-19 causal DAG into the local Neo4j instance.
# Run this after Neo4j has started (i.e. after `mvn package -pl plugin -P deploy -DskipTests`).
#
# Usage:
#   ./dev/seed.sh                          # uses default password 'neo4j'
#   NEO4J_PASSWORD=mypassword ./dev/seed.sh

set -euo pipefail

NEO4J_HOME="${NEO4J_HOME}"
CYPHER_SHELL="$NEO4J_HOME/bin/cypher-shell"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PASSWORD="${NEO4J_PASSWORD:-neo4j}"

echo "Waiting for Neo4j to accept connections..."
until "$CYPHER_SHELL" -u neo4j -p "$PASSWORD" "RETURN 1 AS ok" > /dev/null 2>&1; do
    printf "."
    sleep 2
done
echo " ready."

echo "Seeding COVID-19 DAG..."
"$CYPHER_SHELL" -u neo4j -p "$PASSWORD" -f "$SCRIPT_DIR/seed-dag.cypher"

echo "Done. You can now compile the graph with:"
echo "  CALL causal.compile() YIELD variableCount, compiledAt"
