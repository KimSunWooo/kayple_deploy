#!/usr/bin/env bash
# Local Playwright E2E against isolated MySQL + WireMock (does not touch production).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if docker info >/dev/null 2>&1; then
  D() { docker "$@"; }
  DC() { docker compose -f "$ROOT/docker-compose.e2e.yml" "$@"; }
elif sg docker -c 'docker info' >/dev/null 2>&1; then
  D() { sg docker -c "docker $*"; }
  DC() { sg docker -c "docker compose -f '$ROOT/docker-compose.e2e.yml' $*"; }
else
  echo "Docker is not usable. Run: newgrp docker"
  exit 1
fi

BACKEND_PID=""
FRONTEND_PID=""
cleanup() {
  [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" 2>/dev/null || true
  [[ -n "$FRONTEND_PID" ]] && kill "$FRONTEND_PID" 2>/dev/null || true
  DC down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Starting MySQL + WireMock"
DC up -d
echo "==> Waiting for MySQL health"
MYSQL_READY=0
for i in $(seq 1 90); do
  # Use mysqladmin (no SQL spaces) so sg-docker quoting cannot treat args as a DB name.
  if DC exec -T mysql mysqladmin ping -h 127.0.0.1 -uapp -papppass --silent >/dev/null 2>&1; then
    echo "MySQL ready"
    MYSQL_READY=1
    break
  fi
  sleep 2
done
if [[ "$MYSQL_READY" -ne 1 ]]; then
  echo "MySQL did not become ready with app credentials"
  exit 1
fi

export DB_URL='jdbc:mysql://127.0.0.1:3307/app?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8'
export DB_USERNAME=app
export DB_PASSWORD=apppass
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD=adminpass
export SMS_API_KEY=test-e2e-key
export SMS_API_URL='http://127.0.0.1:8089/v1/messages'
export SESSION_COOKIE_SECURE=false

echo "==> Building backend JAR"
(cd "$ROOT/backend" && ./mvnw -q -DskipTests package)

echo "==> Starting backend on 127.0.0.1:8080"
java -jar "$ROOT/backend/target/reservation-0.0.1-SNAPSHOT.jar" > /tmp/kayple-e2e-backend.log 2>&1 &
BACKEND_PID=$!
BACKEND_READY=0
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8080/api/health >/dev/null 2>&1; then
    echo "Backend ready"
    BACKEND_READY=1
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "Backend process exited early; see /tmp/kayple-e2e-backend.log"
    exit 1
  fi
  sleep 1
done
if [[ "$BACKEND_READY" -ne 1 ]]; then
  echo "Backend health check timed out; see /tmp/kayple-e2e-backend.log"
  exit 1
fi
curl -fsS http://127.0.0.1:8080/api/health
echo

echo "==> Starting frontend Vite"
(cd "$ROOT/frontend" && npm run dev -- --host 127.0.0.1 --port 5173 > /tmp/kayple-e2e-frontend.log 2>&1) &
FRONTEND_PID=$!
FRONTEND_READY=0
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:5173 >/dev/null 2>&1; then
    echo "Frontend ready"
    FRONTEND_READY=1
    break
  fi
  sleep 1
done
if [[ "$FRONTEND_READY" -ne 1 ]]; then
  echo "Frontend did not become ready; see /tmp/kayple-e2e-frontend.log"
  exit 1
fi

echo "==> Running Playwright"
cd "$ROOT/frontend"
export E2E_BASE_URL=http://127.0.0.1:5173
npm run test:e2e | tee /tmp/kayple-e2e-playwright.log
echo "==> E2E finished OK"
