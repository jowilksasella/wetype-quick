# 本機建置 / Local build

本指南只產生使用者本機檔案。請先確認你有權取得及使用指定原版，並了解適用的法律與條款。
This workflow only creates local files. Check your rights to the original and the applicable terms/law first.

This is a computer-based patch workflow, not an Android patch-manager app.
The main Python command does not require Bash or WSL. It has been tested on Linux.
Windows/macOS portability remains unverified; fail-closed checks are retained.
On Windows, install Python 3.10+ and Java 21 from their official sources, ensure
Java and curl are on PATH, then run in the extracted source folder:

```powershell
py -3 scripts/build_local.py "C:\path\original-3.5.4.apk"
```

Do not assume that an untested platform will build successfully. Report a
sanitized error if it does not; do not bypass the input or tool hashes.

## 1. 準備 / Requirements

- Python 3.10+, Java 21 and curl; Bash only for the optional shell workflows
- About 3 GB of free disk space for decoding/building; optional framework tests need more
- Exact WeType 3.5.4 original, 224,668,031 bytes
- SHA-256: `c036f49444b3230fc2fc7c119ae79f32b33766374fd5dc6d48246dea378ff889`

Obtain the host separately through an authorized source. This project supplies no
host download or redistribution link. Unknown bytes are rejected, including a
different repackaging that happens to show the same version number.

One command checks the original, fetches verified tools, runs the source tests and builds:

```sh
python3 scripts/build_local.py /absolute/path/to/original-3.5.4.apk
```

Or run the stages separately:

```sh
python3 scripts/toolchain.py fetch
python3 scripts/toolchain.py verify
bash scripts/test.sh
bash scripts/build.sh /absolute/path/to/original-3.5.4.apk
```

`toolchain.json` pins official tool URLs and SHA-256 hashes. A bad existing download
is rejected, not silently trusted. Remove only that failed download and fetch again.
An existing verified tool directory can be supplied through `WETYPE_TOOLS`.

The build reads the original, extracts a local DEX-only input, applies 26 pinned
class changes, compiles the new Quick code, and produces:

- `.local/build/wetype-quick-0.1.0-local-unsigned.apk`
- `.local/build/local-unsigned-validation.json`
- `.local/build/quick-abi-validation.json`
- `.local/build/packaged-gate-validation.json`

The original file is not modified. Original native libraries and resources are
preserved; signature records are removed because modified bytes cannot retain the
original signature. Original ASR signature checks are preserved. There is no
signature-check bypass. The changed app label explicitly says “非官方”.

Local staging, DEX, APKs, dependencies and reports are private build material and
are excluded from source releases. Do not upload `.local/` or any test `build/` directory.

## 2. 可選框架測試 / Optional framework-backed tests

Install Apache Maven from its official distribution and make `mvn` available.

```sh
bash scripts/setup-host-tests.sh
bash scripts/test-host.sh
```

These use Robolectric's Android 16 framework plus authored host doubles. They do
not contain the original app's translated implementation and do not test a full
running IME. Maven dependencies are pinned at top-level in `tests/pom.xml`;
transitive resolution is not represented as a complete supply-chain audit.

## 3. 自行簽署 / Sign yourself

The unsigned file is not installable. Android requires a signing identity.
Use Android SDK Build Tools (`zipalign` and `apksigner`) from the official Android
SDK and a keystore you control. Keep the key outside this repository, never commit
it, and do not put passwords in shell arguments or public logs.

If you do not already have a key, create one interactively using the JDK's `keytool`,
with a private local filename and your chosen alias. Review the prompts yourself.
The project scripts never generate keys and do not ship a shared signing identity.

Example commands, replacing the keystore path and alias with your own values:

```sh
zipalign -P 16 -f 4 .local/build/wetype-quick-0.1.0-local-unsigned.apk .local/build/aligned.apk
apksigner sign --ks /private/path/to/your.keystore --ks-key-alias YOUR_ALIAS --out .local/build/wetype-quick-local.apk .local/build/aligned.apk
apksigner verify --verbose --print-certs .local/build/wetype-quick-local.apk
```

Let `apksigner` request the password interactively. Keep a secure backup of the
keystore if you intend to update your installation later. Verify the certificate
and signing result before installing. Do not treat a successful signature check as
proof of safe behavior or legal permission to distribute the result.

## 4. 安裝 / Install

Use Android's normal installer for your locally signed APK, then enable the IME
in system settings and select “速成” in its keyboard picker. Installation requires
your explicit action. The build scripts do not touch a connected phone.

The package name stays the same as the original. A different signing key cannot
update it, and both cannot be installed side by side under that name. A key you
create also cannot update an earlier build signed by another person. Uninstalling
can destroy local dictionaries/settings. Back up first; this guide does not tell
you to uninstall automatically.

Only install on a device you control after accepting the experimental limits.
Actual startup, keyboard behavior, microphone routing and voice service still need
device checks. A unit test or DEX verification pass is not a guarantee of any of these.
