#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/Flower_Delivery}"
SERVICE_NAME="${SERVICE_NAME:-flower-delivery}"
CONFIG_PATH="${CONFIG_PATH:-/opt/Flower_Delivery/application.properties}"
LOG_PATH="${LOG_PATH:-/opt/Flower_Delivery/bot.log}"
JAR_NAME="${JAR_NAME:-Flower_Delivery-0.0.1-SNAPSHOT.jar}"

echo "== Flower Delivery VPS deploy =="
echo "APP_DIR=$APP_DIR"
echo "SERVICE_NAME=$SERVICE_NAME"

cd "$APP_DIR"

CURRENT_BRANCH="$(git branch --show-current)"
echo "Current branch: $CURRENT_BRANCH"

echo "[1/5] Pull latest code"
git pull --ff-only origin "$CURRENT_BRANCH"

echo "[2/5] Ensure gradle wrapper is executable"
chmod +x gradlew

echo "[3/5] Build bootJar"
./gradlew --no-daemon clean bootJar

JAR_PATH="$APP_DIR/build/libs/$JAR_NAME"
if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH" >&2
  exit 1
fi

echo "[4/5] Restart application"
if systemctl list-unit-files | grep -q "^${SERVICE_NAME}\.service"; then
  systemctl restart "$SERVICE_NAME"
  systemctl --no-pager --full status "$SERVICE_NAME" | sed -n '1,20p'
else
  pkill -f "$JAR_NAME" || true
  nohup java -Xms256m -Xmx1024m -jar "$JAR_PATH" \
    --spring.config.additional-location="file:$CONFIG_PATH" \
    > "$LOG_PATH" 2>&1 &
  sleep 3
fi

echo "[5/5] Recent logs"
if systemctl list-unit-files | grep -q "^${SERVICE_NAME}\.service"; then
  journalctl -u "$SERVICE_NAME" -n 50 --no-pager
else
  tail -n 50 "$LOG_PATH"
fi
