#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# NFriend Backend — Oracle Cloud Setup Script
# Run as root on a fresh Ubuntu 22.04+ ARM VM (Oracle Cloud Free Tier)
# ─────────────────────────────────────────────────────────────────────
set -euo pipefail

DOMAIN="${1:-}"
APP_DIR="/opt/nfriend/backend"

echo "╔═══════════════════════════════════════════╗"
echo "║  NFriend Backend Deployment               ║"
echo "╚═══════════════════════════════════════════╝"

# ── 1. System Updates ─────────────────────────────────────────────────
echo "[1/7] Updating system..."
apt update && apt upgrade -y

# ── 2. Install Node.js 20 LTS ────────────────────────────────────────
echo "[2/7] Installing Node.js 20..."
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y nodejs build-essential

# ── 3. Install Nginx ──────────────────────────────────────────────────
echo "[3/7] Installing nginx..."
apt install -y nginx

# ── 4. Create app user & deploy code ─────────────────────────────────
echo "[4/7] Creating app user and deploying..."
useradd --system --shell /usr/sbin/nologin nfriend || true
mkdir -p "$APP_DIR/data"

# Copy the backend code (assumes you've SCPed or git cloned it)
if [ ! -f "$APP_DIR/package.json" ]; then
    echo "  ⚠  Copy your backend code to $APP_DIR first, then re-run."
    echo "  Example: scp -r backend/* root@YOUR_VM:$APP_DIR/"
    exit 1
fi

cd "$APP_DIR"
npm ci --production
chown -R nfriend:nfriend "$APP_DIR"

# ── 5. Create production .env ─────────────────────────────────────────
echo "[5/7] Writing production .env..."
cat > "$APP_DIR/.env" << EOF
PORT=3000
DB_PATH=$APP_DIR/data/nfriend.db
DEFAULT_TTL_SECONDS=300
SALT_EPOCH_SECONDS=300
POW_DIFFICULTY=16
EOF
chown nfriend:nfriend "$APP_DIR/.env"
chmod 600 "$APP_DIR/.env"

# ── 6. Install systemd service ────────────────────────────────────────
echo "[6/7] Installing systemd service..."
cp /opt/nfriend/deploy/nfriend.service /etc/systemd/system/nfriend.service
systemctl daemon-reload
systemctl enable nfriend
systemctl start nfriend

echo "  ✓ Service running: $(systemctl is-active nfriend)"

# ── 7. Configure nginx + SSL ─────────────────────────────────────────
echo "[7/7] Configuring nginx..."
if [ -n "$DOMAIN" ]; then
    # Replace placeholder domain in nginx config
    sed "s/YOUR_DOMAIN_OR_IP/$DOMAIN/g; s/YOUR_DOMAIN/$DOMAIN/g" \
        /opt/nfriend/deploy/nginx-nfriend.conf > /etc/nginx/sites-available/nfriend
    ln -sf /etc/nginx/sites-available/nfriend /etc/nginx/sites-enabled/
    rm -f /etc/nginx/sites-enabled/default

    # Install certbot and get SSL cert
    apt install -y certbot python3-certbot-nginx
    certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --email admin@$DOMAIN

    nginx -t && systemctl reload nginx
    echo "  ✓ HTTPS configured for $DOMAIN"
else
    echo "  ⚠  No domain specified. Skipping SSL."
    echo "  Run: certbot --nginx -d YOUR_DOMAIN"

    # Use a simple HTTP-only config
    cat > /etc/nginx/sites-available/nfriend << 'NGINXCONF'
server {
    listen 80;
    server_name _;
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        client_max_body_size 4k;
    }
}
NGINXCONF
    ln -sf /etc/nginx/sites-available/nfriend /etc/nginx/sites-enabled/
    rm -f /etc/nginx/sites-enabled/default
    nginx -t && systemctl reload nginx
fi

# ── 8. Open firewall ──────────────────────────────────────────────────
echo "  Opening ports 80 & 443 in iptables..."
iptables -I INPUT -p tcp --dport 80 -j ACCEPT
iptables -I INPUT -p tcp --dport 443 -j ACCEPT
netfilter-persistent save 2>/dev/null || true

echo ""
echo "╔═══════════════════════════════════════════╗"
echo "║  ✅ Deployment complete!                  ║"
echo "║  Backend: http://$(hostname -I | awk '{print $1}'):3000    ║"
echo "║  Health:  curl http://localhost:3000/health ║"
echo "╚═══════════════════════════════════════════╝"
