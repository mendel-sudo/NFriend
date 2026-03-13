# 🛡️ NFriend

**Privacy-first proximity.** See friends nearby without anyone—including the server—knowing who or where you are.

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/android)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

---

## 🚀 What is NFriend?

NFriend is a decentralized proximity and communication app designed for the privacy-conscious. It allows you to find friends nearby and chat with them without ever creating an account, uploading a contact list, or trusting a central cloud.

### The "Zero Trust" Philosophy:
- **No Accounts:** Your identity is a cryptographic keypair generated on your device.
- **No Cloud Tracking:** The server is a "Blind Relay"—it holds encrypted messages but has no idea who is sending them or where they are.
- **Privacy by Design:** Even friends only see your approximate location based on your chosen privacy settings.

---

## ✨ Key Features

### 📡 Proximity Scanning
Find friends within a range you control. Use a precision-based slider to adjust how far you broadcast your presence (from 100 feet to city-wide).

### 💬 E2EE Private Chat
End-to-end encrypted messaging using X25519 and `crypto_box_seal`. Messages are ephemeral—they vanish from the network after ~10 minutes.

### 📍 Secure Location Pins
Share your precise coordinates within an encrypted chat. Tapping a pin opens Google Maps directly, making meetups seamless.

### ⛓️ Offline Mesh Networking
If cellular or Wi-Fi signal is lost, NFriend automatically falls back to Bluetooth/Wi-Fi Direct. It can even use nearby friends as "bridges" to reach the internet!

---

## 🛠️ How It Works

### Blind Relay Architecture
NFriend uses a unique "Blind Relay" system. Instead of constant GPS tracking, the app hashes your location into "Geohashes" and salts them with a rotating key.
1. **Drop:** You drop an encrypted "envelope" at your geohash.
2. **Pickup:** Your friends look for envelopes at that same hash.
3. **Open:** Only the intended recipient has the private key to open and read the envelope.

### Geohash Precision
The app allows you to choose your **Broadcast Range** (how precisely you share) and **Visibility Range** (how far you look). This ensures you only share as much detail as you're comfortable with.

---

## 📂 Project Organization

```text
NFriend/
├── android/          # Native Android app (Kotlin)
│   ├── app/          # Core logic, E2EE engine, Mesh networking
│   └── build.gradle  # Build and dependency configuration
├── backend/          # Node.js Blind Relay server
│   ├── routes/       # API endpoints for envelope storage
│   └── tests/        # 14+ integration tests for privacy verification
├── deploy/           # Deployment scripts for Oracle Cloud/Linux
└── .github/          # CI/CD pipelines
```

---

## 📦 Getting Started

### Prerequisites
- Android Studio (Jellyfish or newer)
- Java 21+

### Building the App
1. Clone the repository.
2. Open the `android/` folder in Android Studio.
3. Wait for Gradle to sync.
4. Click **Run** to install on your device or emulator.

### Running the Server (Local)
```bash
cd backend
npm install
npm run dev
```

---

## ⚖️ License

Distributed under the MIT License. See `LICENSE` for more information.
