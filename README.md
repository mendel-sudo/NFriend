# NFriend

Privacy-first proximity app. See friends nearby without anyone—including the
server—knowing who or where you are.

## Project Structure

```
NFriend/
├── backend/          # Node.js blind relay server
│   └── npm test      # 14 integration tests
├── android/          # Android Kotlin app
│   └── Open in Android Studio
├── deploy/           # Deployment configs
│   ├── setup-server.sh
│   ├── nfriend.service
│   └── nginx-nfriend.conf
└── .github/workflows # CI/CD
```

## Quick Start

### Backend (local dev)
```bash
cd backend
npm install
npm run dev      # http://localhost:3000
npm test         # 14 tests
```

### Android
Open `android/` in Android Studio → run on emulator/device.

### Deploy (Oracle Cloud)
```bash
scp -r backend deploy root@YOUR_VM:/opt/nfriend/
ssh root@YOUR_VM "bash /opt/nfriend/deploy/setup-server.sh your-domain.com"
```

## Architecture

- **Server:** Dumb mailbox — stores only HMAC'd geohashes + encrypted blobs
- **Crypto:** X25519 ECDH + `crypto_box_seal` (Lazysodium)
- **Privacy:** Rotating HMAC salts, ephemeral sender tokens, proof-of-work
- **Migration:** Nearby Connections P2P with verification codes

## License

MIT
