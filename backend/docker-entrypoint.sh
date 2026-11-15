#!/bin/sh
set -eu

if [ -n "${RENDER_DATABASE_URL:-}" ]; then
  # Render injects the PostgreSQL URI and the credentials separately.
  # Remove the scheme and user info before composing the JDBC URL.
  database_address=${RENDER_DATABASE_URL##*@}
  export SPRING_DATASOURCE_URL="jdbc:postgresql://${database_address}?sslmode=require"
fi

exec java -jar app.jar "$@"
