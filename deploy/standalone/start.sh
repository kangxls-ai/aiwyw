#!/bin/sh
set -eu
cd "$(dirname "$0")"
root=$(pwd -P)
umask 077
mkdir -p run
[ -r config/application.properties ] || { echo "Missing config/application.properties" >&2; exit 1; }
[ -r aiwyw-server.jar ] && [ -d lib ] || { echo "Missing JAR or lib" >&2; exit 1; }
if [ -r run/server.pid ]; then
  pid=$(cat run/server.pid)
  case "$pid" in ''|*[!0-9]*|0|1) echo "Invalid run/server.pid" >&2; exit 1;; esac
  if kill -0 "$pid" 2>/dev/null; then echo "PID $pid already running; check run/server.pid" >&2; exit 1; fi
fi
java=java
if [ -n "${JAVA_HOME:-}" ]; then java="$JAVA_HOME/bin/java"; fi
nohup "$java" "-Dloader.path=$root/lib" -jar "$root/aiwyw-server.jar" \
  --spring.config.additional-location=file:./config/ \
  </dev/null >>run/server.log 2>&1 &
pid=$!
printf '%s\n' "$pid" >run/server.pid
sleep 2
kill -0 "$pid" 2>/dev/null || { echo "Startup failed; check run/server.log" >&2; exit 1; }
echo "Spring Boot PID $pid; see run/server.log and /api/install/status for readiness"
