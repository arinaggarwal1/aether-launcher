# Repository map

This repository keeps build entry points and Android resources at stable paths. Documentation is
organized by task through the [documentation index](../README.md), so existing links and tooling do
not need to move when guides are added.

```text
refract-launcher/
├── README.md                  Project overview and quick links
├── AGENTS.md                  Personal fork and device-operation instructions
├── CONTRIBUTING.md            Coding, UI, performance and test conventions
├── AndroidManifest.xml        Package, components, permissions and intent filters
├── build.sh                   Signed build entry point
├── config/                    Public fork configuration and certificate pin
├── src/
│   ├── com/arinaggarwal/refract/   Launcher, settings and device controls
│   │   └── present/               Presentation server, storage and player
│   └── io/nayuki/qrcodegen/       Vendored QR code generator
├── res/                       Android icons, styles, animations and XML resources
├── assets/
│   ├── fonts/                 Inter fonts and their license
│   ├── wallpapers/            Wallpaper metadata
│   ├── app-art/               App artwork and local upload page
│   └── present/               Presentation browser interface
├── docs/
│   ├── README.md              Documentation index
│   ├── guides/                Focused device and usage guides
│   ├── reference/             Project and technical reference
│   ├── images/                Screenshots and branding used by the docs
│   └── *.md                   Existing guides with stable links
├── tools/
│   ├── release.py             Signed build and version reservations
│   ├── tv.py                  Explicit, serial-scoped TV operations
│   ├── tests/                 Infrastructure and Android test harnesses
│   ├── test_*_android.py      Explicit Android instrumentation runners
│   └── brand.py, fonts/       Branding generation and its font assets
└── .github/workflows/         Signed GitHub release workflow
```

`build/`, `backups/`, the local build lock, signing keys, and Python caches are ignored local state;
they are not source files. `backups/` can contain private TV data. Signing material lives outside the
repository and must never be committed. `config/signing-cert.sha256` is the **public** pin.

For class responsibilities and design details, see [Architecture](../architecture.md). For the
complete workflow, see [Contributing](../../CONTRIBUTING.md).
