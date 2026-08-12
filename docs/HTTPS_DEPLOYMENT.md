# playball.ai.kr HTTPS deployment

The production frontend Nginx container terminates TLS. It mounts the host's
Let's Encrypt directory read-only and proxies `/api/`, `/ws/`, and
`/actuator/health` to the existing backend service.

HSTS is intentionally not enabled yet. Enable it only after HTTPS and automatic
renewal have operated successfully for a sufficient period; a premature HSTS
policy can make recovery from certificate or DNS mistakes difficult.

## 1. Production environment

Keep the real `.env` only on the EC2 instance at
`/home/ubuntu/KBO_Predictor/.env`. Set at least these HTTPS-related values:

```dotenv
APP_FRONTEND_ORIGIN=https://playball.ai.kr
SESSION_COOKIE_SECURE=true
FRONTEND_PORT=80
HTTPS_PORT=443
LETSENCRYPT_PATH=/etc/letsencrypt
CERTBOT_WEBROOT_PATH=/home/ubuntu/KBO_Predictor/certbot/www
```

Do not commit `.env`, certificates, private keys, or AWS credentials. The
production model remains `baseline-v1`; HTTPS deployment does not change model
selection.

## 2. Bootstrap HTTP and issue the first certificate

Before issuance, confirm that the domain's A record resolves to the EC2 Elastic
IP and that TCP ports 80 and 443 are open in the security group. Then deploy the
current source. If the certificate files are absent, the frontend automatically
starts with the HTTP bootstrap configuration so the ACME webroot remains
reachable.

Run the following commands on EC2:

```bash
cd /home/ubuntu/KBO_Predictor
mkdir -p certbot/www
docker compose up -d --build frontend

sudo snap install core
sudo snap refresh core
sudo snap install --classic certbot

sudo /snap/bin/certbot certonly \
  --webroot \
  --webroot-path /home/ubuntu/KBO_Predictor/certbot/www \
  --domain playball.ai.kr \
  --email YOUR_EMAIL@example.com \
  --agree-tos \
  --no-eff-email

bash /home/ubuntu/KBO_Predictor/ops/reload-frontend-nginx.sh
```

Replace `YOUR_EMAIL@example.com` with the address that should receive Let's Encrypt
expiry and account notices. The reload helper selects the HTTPS configuration,
runs `nginx -t`, and reloads Nginx without rebuilding the image.

The Nginx container mounts all of `/etc/letsencrypt`, not only `live/`, because
files in `live/` are symbolic links to versioned files under `archive/`.

## 3. Verify HTTPS

```bash
curl -I http://playball.ai.kr/
curl -fsS https://playball.ai.kr/healthz
curl -fsS https://playball.ai.kr/actuator/health
docker compose ps
docker compose logs --tail=100 frontend
```

The first response must be `301` with a `Location` under
`https://playball.ai.kr`. The health endpoints must return successfully. Verify
signup, login, page refresh, and logout in a browser and confirm that the
`JSESSIONID` cookie is `Secure`, `HttpOnly`, and `SameSite=Lax`.

`/healthz` on port 80 is the one deliberate redirect exception. It is used by
the container and SSM deployment health checks and returns only the literal
`ok`; public application paths still redirect to HTTPS.

## 4. Automatic renewal and Nginx reload

The Certbot snap installs a systemd renewal timer. Add a deploy hook so a
successfully renewed certificate is loaded by the running frontend container:

```bash
sudo install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-playball-nginx.sh > /dev/null <<'HOOK'
#!/usr/bin/env bash
set -euo pipefail
bash /home/ubuntu/KBO_Predictor/ops/reload-frontend-nginx.sh
HOOK
sudo chmod 750 /etc/letsencrypt/renewal-hooks/deploy/reload-playball-nginx.sh

sudo /snap/bin/certbot renew --dry-run
systemctl list-timers | grep certbot
```

The deploy hook runs only after a successful renewal. The entire certificate
directory remains a read-only container mount; Certbot alone writes certificate
material on the host.

## 5. CI/CD impact

GitHub Actions continues to use OIDC and SSM Run Command. No new GitHub
Variable or Secret is required. Frontend CI validates the Compose file, builds
the image, and runs `nginx -t` against the certificate-absent bootstrap
configuration. After the real certificate is issued, the reload helper runs
`nginx -t` against the HTTPS configuration before reloading it. Deployment
health checks use the local HTTP `/healthz` exception, so first-certificate
issuance and public certificate trust do not make deployments brittle. SSH
deployment and static AWS credentials are not used.
