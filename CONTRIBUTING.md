# Contributing

This is a source-only, experimental interoperability project. Start with a small
issue or pull request that explains a reproducible problem and the proposed fix.

- Run `bash scripts/test.sh`; run optional host-adapter tests when changing adapters.
- Preserve dictionary provenance, attribution, license copies and reproducibility.
- Do not commit host APKs, decompiled/translated host code, resources, native
  binaries, generated patched classes, signing keys, personal text, unredacted
  logs or private links. `.gitignore` is only a convenience, not a release audit.
- Do not remove signature/license checks or add unsupported host-version fallbacks.
- New tests should use authored fixtures or user-local extraction, with limits
  stated clearly. Do not turn component tests into full-device compatibility claims.
- A changed adapter must be tested against the exact supported host before release.

By submitting a contribution, identify any third-party source or license you used.
Do not submit code or data you lack permission to share.
