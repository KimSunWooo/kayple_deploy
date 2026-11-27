#!/usr/bin/env bash
# Deploy Kayple reservation to ksw.recruit.kayple.com
# Usage (from repo root on local machine):
#   ./deploy/deploy.sh
#
# Prerequisites:
#   - SSH key at ~/.ssh/kayple/recruit-ksw.pem
#   - /etc/kayple/app.env already filled on the server by the operator
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/kayple/recruit-ksw.pem}"
REMOTE="${REMOTE:-ubuntu@ksw.recruit.kayple.com}"
SSH=(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$REMOTE")
SCP=(scp -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new)

echo "==> Building frontend"
cd "$ROOT_DIR/frontend"
npm ci
npm run build

echo "==> Building backend (on remote if local mvn missing)"
cd "$ROOT_DIR"
if [[ -x "$ROOT_DIR/backend/mvnw" ]]; then
  (cd backend && ./mvnw -q -DskipTests package)
  LOCAL_JAR="$ROOT_DIR/backend/target/reservation-0.0.1-SNAPSHOT.jar"
elif command -v mvn >/dev/null 2>&1; then
  (cd backend && mvn -q -DskipTests package)
  LOCAL_JAR="$ROOT_DIR/backend/target/reservation-0.0.1-SNAPSHOT.jar"
else
  LOCAL_JAR=""
fi

echo "==> Preparing remote directories"
"${SSH[@]}" 'mkdir -p /home/ubuntu/kayple-reservation/backend /home/ubuntu/kayple-reservation/frontend-dist /tmp/kayple-upload'

if [[ -n "$LOCAL_JAR" && -f "$LOCAL_JAR" ]]; then
  echo "==> Uploading prebuilt JAR"
  "${SCP[@]}" "$LOCAL_JAR" "$REMOTE:/home/ubuntu/kayple-reservation/backend/app.jar"
else
  echo "==> Uploading backend sources for remote Maven build"
  rsync -az -e "ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new" \
    --exclude target --exclude .idea \
    "$ROOT_DIR/backend/" "$REMOTE:/home/ubuntu/kayple-reservation/backend-src/"
  "${SSH[@]}" 'bash -s' <<'REMOTE_BUILD'
set -euo pipefail
if ! command -v mvn >/dev/null 2>&1; then
  curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz -o /tmp/maven.tgz
  sudo mkdir -p /opt/maven
  sudo tar -xzf /tmp/maven.tgz -C /opt/maven --strip-components=1
  export PATH="/opt/maven/bin:$PATH"
fi
cd /home/ubuntu/kayple-reservation/backend-src
mvn -q -DskipTests package
cp target/reservation-0.0.1-SNAPSHOT.jar /home/ubuntu/kayple-reservation/backend/app.jar
REMOTE_BUILD
fi

echo "==> Uploading frontend dist"
rsync -az --delete -e "ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new" \
  "$ROOT_DIR/frontend/dist/" "$REMOTE:/home/ubuntu/kayple-reservation/frontend-dist/"

echo "==> Uploading systemd template (Nginx is managed separately — not overwritten)"
"${SCP[@]}" "$ROOT_DIR/deploy/kayple.service" "$REMOTE:/tmp/kayple.service"
"${SCP[@]}" "$ROOT_DIR/deploy/app.env.example" "$REMOTE:/tmp/app.env.example"

echo "==> Applying remote deploy steps"
"${SSH[@]}" 'bash -s' <<'REMOTE'
set -euo pipefail

sudo mkdir -p /etc/kayple
if [[ ! -f /etc/kayple/app.env ]]; then
  sudo cp /tmp/app.env.example /etc/kayple/app.env
  sudo chmod 600 /etc/kayple/app.env
  sudo chown root:root /etc/kayple/app.env
  echo "WARNING: /etc/kayple/app.env created from example — fill secrets, then restart kayple."
fi

sudo rsync -a --delete /home/ubuntu/kayple-reservation/frontend-dist/ /var/www/html/
sudo chown -R www-data:www-data /var/www/html

sudo cp /tmp/kayple.service /etc/systemd/system/kayple.service
sudo systemctl daemon-reload
sudo systemctl enable kayple.service
sudo systemctl restart kayple.service

sleep 2
curl -fsS http://127.0.0.1:8080/api/health || true
systemctl is-active kayple.service
REMOTE

echo "==> App artifacts deployed (Nginx left untouched)"
echo "Verify: https://ksw.recruit.kayple.com/api/health"
