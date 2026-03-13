# NFriend — Continuation Notes

## Current Status (March 13, 2026)

**Everything below is scaffolded and ready to build — no placeholder files remain in the critical path.**

### ✅ What's Done

| Component | Status | Details |
|---|---|---|
| **Architecture Doc** | Complete | Threat model, DB schema, API specs, deployment strategy |
| **Backend** | Complete + tested | `f:\NFriend\backend` — 14/14 Jest tests pass |
| **Android Crypto** | Fully implemented | X25519 (Lazysodium), `crypto_box_seal` E2EE, HMAC tokens, PoW solver |
| **Android UI** | Fully implemented | Dark theme, 4-tab nav, onboarding, all fragments |
| **Location Service** | Implemented | Foreground service, FusedLocation, 60s interval |
| **QR Exchange** | Implemented | CameraX + ML Kit scanner, ZXing generator, auto-ECDH on scan |
| **Migration** | Implemented | Nearby Connections P2P, verification codes, send/receive |
| **Deployment configs** | Complete | systemd, nginx, certbot, CI/CD workflows, signing config |

### 🔨 What Needs to Happen Next

#### Immediate (before first test on device)

1. **Open `android/` in Android Studio** → Gradle sync → fix any import issues
   - The project has never been built yet — expect minor issues like missing `ic_launcher` mipmap
   - Generate default launcher icons in Android Studio: **right-click res → New → Image Asset**
   
2. **Add Gradle Wrapper files** — the project needs `gradlew`/`gradlew.bat`:
   ```bash
   cd android
   gradle wrapper --gradle-version 8.4
   ```
   Or just open in Android Studio and it will generate them.

3. **Test on emulator** — verify the onboarding flow: alias → key generation → main screen

4. **Update `RelayClient.kt` server URL** for production:
   ```kotlin
   // Line 18 — change from emulator localhost to real server
   private val baseUrl: String = "https://your-domain.com"
   ```

5. **Lock down `network_security_config.xml`** — remove cleartext exceptions for production

#### Before Release

6. **Generate keystore** for Play Store signing (see `deploy/DEPLOYMENT_GUIDE.md`)

7. **Deploy backend to Oracle Cloud**:
   ```bash
   scp -r backend deploy root@VM_IP:/opt/nfriend/
   ssh root@VM_IP "bash /opt/nfriend/deploy/setup-server.sh your-domain.com"
   ```

8. **Set up GitHub Secrets** for CI/CD (see DEPLOYMENT_GUIDE.md section 3)

9. **Write a privacy policy** (required for Play Store — template needed)

10. **Create Play Store listing** — screenshots, description, etc.

#### Nice-to-Have Improvements

- Add proper Jetpack Navigation (currently using manual fragment transactions)
- Add Room database for friend storage (currently EncryptedSharedPreferences)
- Add battery optimization settings (currently fixed 60s interval)
- Add offline mesh fallback via Nearby Connections advertising/discovery
- Add distance/direction visualization (compass bearing from haversine)
- Add unit tests for the Android crypto layer (KeyManager, E2EEEngine, GeohashEncoder)

### Key Technical Decisions Made

| Decision | Rationale |
|---|---|
| **Lazysodium** over Tink | Need raw X25519 key material for QR exchange, ECDH shared secrets, and migration export. Tink's opaque KeysetHandle doesn't expose raw bytes. |
| **better-sqlite3** over sql.js | Native performance, WAL journaling (crash-safe), no manual save interval. Needed VS Build Tools installed. |
| **crypto_box_seal** for E2EE | Anonymous sender — only recipient can decrypt. Server never sees who sent what. |
| **EncryptedSharedPreferences** for friends | Simpler than Room+SQLCipher for MVP. Friend list is small (< 50 typically). |
| **OnboardingActivity as launcher** | Checks `keyManager.hasIdentity()` and redirects to MainActivity if identity exists. Clean first-run flow. |

### Environment Notes

- Node.js v24.13.1 on Windows
- VS Build Tools installed (needed for better-sqlite3 native compilation)
- Android Studio needed for building the Android project
- Backend tests: `cd backend && npm test` (14/14 passing)
