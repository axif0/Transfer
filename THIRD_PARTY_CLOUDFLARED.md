# Third-party: cloudflared

This application redistributes **cloudflared** binaries from the
[cloudflare/cloudflared](https://github.com/cloudflare/cloudflared) project
to provide Cloudflare Quick Tunnels on Android.

| Field | Value |
|-------|--------|
| Component | cloudflared |
| Version | 2025.8.1 |
| Upstream | https://github.com/cloudflare/cloudflared |
| License | Apache License 2.0 |
| Copyright | Copyright (c) Cloudflare, Inc. |

The Apache License 2.0 text is available at:
https://www.apache.org/licenses/LICENSE-2.0

Binaries are packaged under `app/src/main/jniLibs/` as `libcloudflared.so`
(Android native-library naming so the OS extracts them with execute permission).
They are unmodified upstream Linux static builds (`linux-arm64` / `linux-amd64`)
verified by SHA-256 against the official GitHub release checksums.

Transfer (this app) remains MIT-licensed; cloudflared remains under Apache-2.0.
