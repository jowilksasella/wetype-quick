# Security and privacy

Do not post keyboard input, passwords, personal dictionaries, keystores, tokens or
unredacted logs in a public issue. No security-contact address is configured yet;
for a sensitive finding, ask the maintainer for a private reporting channel without
posting exploit details or personal data publicly.

This repository's new engine has no network implementation. The full proprietary
host has not received an end-to-end privacy/security audit. Treat a locally rebuilt
keyboard as experimental and avoid relying on it for high-sensitivity input.

The build verifies the exact original APK and fixed tool hashes, refuses unknown
inputs, preserves host signature-gate classes, and checks that compile-only stubs
are absent from added DEX output. These checks do not establish legal clearance,
absence of vulnerabilities, or real-device compatibility.

## Trust boundaries

- The new adapter and proprietary host share the same app process and sandbox.
  Local-only code is not isolated from host code, permissions or native libraries.
- The user trusts the exact original APK, Java runtime, pinned development tools,
  optional test dependency supply chain and the device/OS. Hashes identify bytes;
  they do not prove those components are trustworthy.
- Password/private-field protection relies on correct editor metadata. It is not
  protection against a compromised host, malicious target app, root access or
  offline analysis of unencrypted learning files.
- Signing keys authenticate a user's own builds. Keep them outside the source
  tree; losing or leaking one can prevent trusted updates or enable impersonation.
- Build-time decoded/patched proprietary files must remain local. Publish only
  the export generated from `public-files.txt`, never the working directory.

See the full [privacy notice](PRIVACY.md) for retained data, deletion, network/voice
limits and the scope of the session-cache cleanup.
