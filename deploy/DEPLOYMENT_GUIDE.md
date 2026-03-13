# NFriend Deployment Guide

## 1. Oracle Cloud Free Tier Setup

### Create the VM
1. Sign up at [cloud.oracle.com](https://cloud.oracle.com) (free, no credit card for Always Free)
2. Create a **Compute Instance**:
   - Shape: **VM.Standard.A1.Flex** (ARM, 4 OCPUs + 24GB RAM — free!)
   - Image: **Ubuntu 22.04**
   - Add your SSH public key
3. In **Networking → Security Lists**, add ingress rules:
   - TCP port 80 (HTTP)
   - TCP port 443 (HTTPS)

### Deploy the Backend
```bash
# From your local machine:
scp -r backend deploy root@YOUR_VM_IP:/opt/nfriend/

# SSH into the VM:
ssh root@YOUR_VM_IP

# Run the setup script (with your domain for SSL):
bash /opt/nfriend/deploy/setup-server.sh your-domain.com

# Or without a domain (HTTP only, for testing):
bash /opt/nfriend/deploy/setup-server.sh
```

### Verify
```bash
curl https://your-domain.com/health
# → {"status":"ok","timestamp":...}
```

---

## 2. Android Keystore (for Play Store signing)

```bash
keytool -genkeypair \
  -alias nfriend \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -keystore android/app/keystore.jks \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=NFriend, O=NFriend, L=City, ST=State, C=US"
```

> ⚠️ **Back up `keystore.jks` safely.** If you lose it, you can never update the app on Play Store.

---

## 3. GitHub Secrets (for CI/CD)

Go to **Settings → Secrets → Actions** in your GitHub repo and add:

| Secret | Value |
|---|---|
| `SERVER_HOST` | Your Oracle Cloud VM IP |
| `SERVER_USER` | `root` (or deploy user) |
| `SERVER_SSH_KEY` | Your SSH private key |
| `KEYSTORE_BASE64` | `base64 -w0 android/app/keystore.jks` |
| `KEYSTORE_PASSWORD` | Your keystore password |
| `KEY_ALIAS` | `nfriend` |
| `KEY_PASSWORD` | Your key password |

---

## 4. Google Play Store

1. Pay the $25 one-time fee at [play.google.com/console](https://play.google.com/console)
2. Create a new app → fill in listing details + privacy policy
3. Upload the signed APK from GitHub Releases (or Actions artifact)
4. Submit for review

---

## 5. F-Droid (free, optional)

1. Ensure your repo is public on GitHub
2. Add an [F-Droid metadata file](https://f-droid.org/docs/Build_Metadata_Reference/):
   ```
   android/metadata/com.nfriend.app.yml
   ```
3. Submit an [inclusion request](https://gitlab.com/fdroid/rfp/-/issues/new)

---

## 6. Update the App's Server URL

Before building for production, update the relay URL in:

[RelayClient.kt](file:///f:/NFriend/android/app/src/main/java/com/nfriend/app/network/RelayClient.kt) — line 18:
```kotlin
private val baseUrl: String = "https://your-domain.com"
```

Also lock down `network_security_config.xml` to remove cleartext exceptions.
