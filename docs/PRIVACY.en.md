# Privacy notice · WeType Quick

[繁體中文](../PRIVACY.md) · [简体中文](PRIVACY.zh-CN.md) · English

Reviewed 2026-10-05. This describes the added Quick code in the source/patch release, based on Q5r2 with additional session-cache cleanup. It is a source/static review, not an end-to-end audit of Tencent WeType or a guarantee of privacy.

## The important limit

The Quick dictionary lookup and learning code has no network client. The complete locally patched keyboard still contains the original proprietary WeType host, including network, cloud speech recognition, reporting, clipboard and dictionary-related features. Those features have not been comprehensively audited or removed. Do not describe the complete app as offline, upload-free, anonymous, or safe for all sensitive input. The local patch model avoids distributing the original APK; it does not isolate the added code from the host or remove host permissions.

## What the added Quick code stores

- `quick-learning.bin`: Quick query/code plus an explicitly selected character or phrase, a selection count, and a relative recency counter. These choices can reveal words you typed. They are not an encrypted or anonymous dataset. There are no wall-clock timestamps, app/account identifiers, or surrounding-message contents in this file's schema.
- The limits are 4,096 character records and 4,096 phrase records. A phrase is at most eight characters. Least-recently selected records are evicted when their respective limits are exceeded. The file is capped at 1 MiB.
- `quick-native-actions.bin`: your explicit candidate pin/block choices, stored as query plus candidate text and action. There are at most 4,096 records total and a 1 MiB file cap. It does not add selection counts or timestamps.
- Both files are created in Android's app-private `getNoBackupFilesDir()` directory. Android Auto Backup excludes this directory. This is not protection against the host itself, privileged/root access, manual copies, a compromised device, or every possible device-transfer mechanism. [Android backup documentation](https://developer.android.com/identity/data/autobackup)
- The stores use checksums and temporary `.new`/recovery `.bak` files for interrupted-write handling. Checksums are corruption checks, not encryption or tamper-proof authentication. Successful saves remove the old backup; failures or interruptions can leave recovery files.
- The selected-Quick setting is also saved as a boolean in app-private shared preferences named `quick-native-backend`.
- There is no time-based retention limit. Records remain until cleared, evicted, or the app's data is removed. Entering a private field does not erase earlier records.

## When learning happens, and when it does not

In an ordinary supported text field, an explicit candidate choice that the editor accepts is learned automatically. The current patch adds no separate global learning-off switch. Lookup, canceled composition, failed commits, and automatic settling when the keyboard is hidden do not add choices.

Quick is disabled for non-text classes and the recognized password, visible-password and web-password variations. Quick learning, learned ranking, and local pin/block actions are suppressed for URL, email and web-email variations and when the editor supplies `IME_FLAG_NO_PERSONALIZED_LEARNING`. Missing editor information is handled conservatively. [Android's editor flag documentation](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)

The engine holds its latest query/candidate plan in process memory. This source release drops the plan reference when private mode is entered or reasserted, including an already-private session's finish/start boundary. Existing learned records are preserved. No disk, logging or network write from this cache was found. Dropping a reference is not memory overwriting or forensic zeroization: Java garbage-collection timing and references held by the original host or other objects are not guaranteed.

These are safeguards in the added Quick layer. They depend on the app correctly identifying its input fields; they are not a universal incognito detector and do not certify the original host's behavior. The keyboard still has to process text to enter it in the target app.

## Clearing and older local records

The original personal-dictionary clear/reset commands are hooked to clear both Quick learning and local pin/block choices, including before Quick initialization. A successful clear writes an empty profile and removes old recovery data. An I/O failure displays an incomplete-clear warning; retry instead of assuming the data is gone. Clearing these records is not a promise to clear all of the original host's caches, clipboard records, or any server-side data, and is not forensic secure erasure.

The learning reader accepts the older v1 local file format and the current v2 format; subsequent saves write v2. This is local compatibility, not a network migration. Unreadable learning files cause a memory-only fallback and a warning; existing unreadable disk files can remain until explicit clearing. Unreadable action files disable those actions until a successful reset.

## Context used for deletion

The prefix-deletion safety check requests up to 2,048 UTF-16 units before and 32 after the cursor, to avoid unsafe character-boundary deletion. It also requests selection metadata. A fallback extracted-text request asks for one character, but Android hints are not strict limits and an editor may return more; this code inspects the returned metadata/length rather than copying that text. The planner does not store, log or upload the context. It returns a deletion plan with positions/counts and fixed diagnostic reasons. This statement covers this added planner, not all original host context handling.

## Candidate reporting and voice

Version-pinned hooks suppress identified native candidate-reporting entry points while Quick owns the pending input, or when a Quick candidate marker appears, including stale markers. This is a targeted guard, not a blanket shutdown of host telemetry. Original lifecycle callbacks, non-Quick reporting, networking and other host paths remain.

The microphone helper only requests the phone's built-in microphone for designated original voice-input recorder paths and then restores the previous preference. It does not read PCM samples, start recording on its own, change speech-recognition providers, or add an audio uploader. Its added log output consists of fixed routing-status/error strings; it does not log audio, typed text, device names, addresses or device IDs.

The original host does contain voice-data request/reporting paths. Voice input may send audio and related data to the original provider. This review did not establish its live destinations, retention, consent behavior or server processing. A changed APK signature may also make original voice services unavailable. Microphone routing does not make speech recognition offline or bypass those checks.

## Permissions and remaining host risks

The original manifest retains INTERNET and RECORD_AUDIO, as well as declarations related to network/Wi-Fi state, Bluetooth, SMS, camera, storage, location on older Android versions, nearby Wi-Fi, notifications, overlays and package installation. A declaration does not show that Android granted the permission or that data was collected. Clipboard functionality also remains; absence of a separate clipboard permission is not evidence that the keyboard cannot access it.

The original network-security configuration permits cleartext traffic. That configuration is a potential transport risk, not proof that a particular request uses cleartext. Reporting/crash-related components, voice-data messages, cloud-dictionary support, clipboard integration and host update-related components remain in the original host. Their actual runtime behavior and payloads are outside this audit.

## Building, signing, updates and reporting issues

The public build takes a user-supplied original WeType 3.5.4 APK and rejects anything except SHA-256 `c036f49444b3230fc2fc7c119ae79f32b33766374fd5dc6d48246dea378ff889` (224,668,031 bytes). Obtain your own lawful copy from a source you trust. A hash identifies exact bytes; it does not prove legal permission or that those bytes are safe.

The main tool download script fetches SHA-256-pinned development tools from the listed upstream HTTPS sources. Optional host-test setup uses Maven repositories and downloads test dependencies; not all transitive dependencies have an independently pinned checksum in this repository. Download services can observe ordinary connection metadata. These scripts do not upload your APK or keyboard records. The reviewed build creates a local unsigned APK and does not install, launch or sign it.

A locally signed modified APK is not signed by Tencent. Android normally requires compatible signing certificates for in-place updates; the same package signed differently cannot be treated as an ordinary update of the official installation. Uninstalling an existing app can delete local data. Keep private signing material private and retain your own key securely for your own future builds. Never disable Android signature/security checks to install an update. [Android signing documentation](https://developer.android.com/studio/publish/app-signing)

The added project has no time-based expiry, payment validation, license server, new analytics, or automatic updater. The original host's update-related code is retained. Future update or payment features would need a separate privacy/security design and explicit disclosure.

Do not publish your original or patched APK, private keys, credentials, personal dictionaries, typed content, audio, or unredacted logs in a public issue. Share only a minimal synthetic reproduction. Use a verified private reporting channel for sensitive findings.

## Verification limits and next steps

Source and synthetic tests cover storage, private-field handling, clearing, candidate guards and routing state. Framework-backed tests use authored host doubles; they do not execute the complete original app. No real user text/audio, live network capture or physical-device privacy test was used in this review. Native libraries, remote services, all host data flows, and all Android/OEM behavior remain unaudited.

Before recommending this experimental keyboard for sensitive daily use, independent reviewers should test it in an isolated test profile with synthetic text/audio, inspect network behavior and consent/settings changes, verify deletion on-device, review host update behavior, and assess signing/provenance. A separate explicit learning-off control would improve user choice. Those tests and features are recommendations, not claims that they already exist or have passed.
