#!/bin/sh
set -eu
cd "$(dirname "$0")"
root=$(pwd -P)
[ -r run/server.pid ] || { echo "Spring Boot is stopped"; exit 0; }
pid=$(cat run/server.pid)
case "$pid" in ''|*[!0-9]*|0|1) echo "Invalid run/server.pid" >&2; exit 1;; esac
if ! kill -0 "$pid" 2>/dev/null; then rm -f run/server.pid; echo "Spring Boot is stopped"; exit 0; fi
command=$(ps -p "$pid" -o args=)
case "$command" in *"-jar $root/aiwyw-server.jar --spring.config.additional-location=file:./config/"*) ;;
  *) echo "PID does not belong to this deployment" >&2; exit 1;;
esac
kill -TERM "$pid"
remaining=60
while kill -0 "$pid" 2>/dev/null; do
  state=$(ps -p "$pid" -o stat= 2>/dev/null || true)
  case "$state" in Z*|'') break;; esac
  [ "$remaining" -gt 0 ] || { echo "Shutdown is still in progress" >&2; exit 1; }
  remaining=$((remaining - 1))
  sleep 1
done
rm -f run/server.pid
echo "Spring Boot stopped"
