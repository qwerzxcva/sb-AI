# sb-AI Project

Android application for managing singbox configuration with advanced routing, DNS rules, and load balancing features.

## Features

- **Routing Rules**: Single-line input for domains/IPs, multi-select for network types (TCP/UDP/all) and protocols (HTTP/TLS/QUIC/gRPC/etc.), logical operators (AND/OR/invert)
- **DNS Configuration**: Separate page for DNS servers and DNS groups/策略组
- **Load Balancing**: Multiple modes including round-robin, consistent hash, random, passive check, URL test, and auto mode
- **Config Generation**: Automatic singbox config generation from rules

## Tech Stack

- Kotlin
- Android (minSdk 24, targetSdk 34)
- Material Design 3
- ViewBinding
- Kotlinx Serialization

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

## Structure

```
lib/
├── models/          # Data models (RouteRule, DnsServer, LoadBalanceRule, etc.)
├── screens/         # UI Fragments and dialogs
├── services/        # Config generator service
└── theme/           # App theming

app/src/main/
├── java/com/sbai/   # Kotlin source code
└── res/             # Resources
```

## Screenshots

(App will have three main tabs: Routing, DNS, Settings)

## Contributing

1. Fork the repo
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Inspired by ThroneForAndroid, LxBox, and AsteriskBOX projects
