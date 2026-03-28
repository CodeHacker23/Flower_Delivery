# VPS Deployment

## Goal

Run the bot on a non-RU VPS and update it with one deploy command instead of manual `git pull`, `bootJar`, `nohup`, and log tailing every time.

## Server layout

- Project repo: `/opt/Flower_Delivery`
- External config: `/opt/Flower_Delivery/application.properties`
- App log: `/opt/Flower_Delivery/bot.log`
- Deploy script: `/opt/Flower_Delivery/scripts/deploy_vps.sh`
- Systemd unit target name: `flower-delivery.service`

## One-time setup

### 1. Copy the service file

```bash
sudo cp /opt/Flower_Delivery/deploy/flower-delivery.service /etc/systemd/system/flower-delivery.service
sudo systemctl daemon-reload
sudo systemctl enable flower-delivery
```

### 2. Make deploy script executable

```bash
cd /opt/Flower_Delivery
chmod +x gradlew
chmod +x scripts/deploy_vps.sh
```

### 3. External config example

Create `/opt/Flower_Delivery/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/FlowerDeliwery
spring.datasource.username=flower_user
spring.datasource.password=flower_pass_123

telegram.bot.token=PUT_REAL_TOKEN_HERE
telegram.bot.username=FlowerDelivery74bot

app.telegram.proxy.enabled=false
```

## Normal workflow

### Local machine

1. Change code in IDE.
2. Run local checks when possible.
3. Commit and push.

### VPS

Run one command:

```bash
cd /opt/Flower_Delivery
./scripts/deploy_vps.sh
```

The script will:

1. Pull the current branch.
2. Build `bootJar`.
3. Restart `flower-delivery.service` if it exists.
4. Otherwise fallback to a `nohup` launch.
5. Print recent logs.

## Useful commands

### Service status

```bash
systemctl status flower-delivery --no-pager
```

### Restart service manually

```bash
systemctl restart flower-delivery
```

### Follow service logs

```bash
journalctl -u flower-delivery -f
```

### Read bot log file

```bash
tail -n 100 /opt/Flower_Delivery/bot.log
```

### Check running bot process

```bash
ps aux | grep Flower_Delivery
```

## If the bot stops responding

### 1. Check for duplicate polling

If logs contain `409 Conflict`, another process uses the same token.

```bash
pkill -f 'Flower_Delivery-0.0.1-SNAPSHOT.jar'
systemctl restart flower-delivery
```

Also make sure no local machine still runs the same bot token.

### 2. Check webhook

```bash
TOKEN=$(grep '^telegram\.bot\.token=' /opt/Flower_Delivery/application.properties | cut -d= -f2-)
curl -s "https://api.telegram.org/bot$TOKEN/getWebhookInfo"
curl -s "https://api.telegram.org/bot$TOKEN/deleteWebhook?drop_pending_updates=true"
```

### 3. Check database

```bash
PGPASSWORD='flower_pass_123' psql -h localhost -U flower_user -d FlowerDeliwery -c '\dt'
```

## Data import reminder

The VPS database is separate from the local database. Existing local orders, shops, couriers, and users are not copied automatically.

If you need old data, create a local dump and restore it on VPS.
