# Ilay for X

> **Privacy-first, ad-free Material 3 Expressive Android client for X with AMOLED dark mode, transparent on-device For You ranking, and a configurable Nitter fallback.**

[![Platform](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

---

## Overview

**Ilay for X** is an open-source, lightweight Android client for X (formerly Twitter) built with **Kotlin** and **Jetpack Compose**. Designed for speed, readability, and user control, Ilay strips out promoted posts everywhere, replaces opaque server-side recommendation black boxes with a transparent on-device ranking engine, and pairs direct X GraphQL browsing with a clean, configurable Nitter fallback so you can read posts, threads, profiles, and search results reliably.

---

## Features

### Privacy-First & Ad-Free
- **Zero Promoted Posts**: Ads and promoted entries are unconditionally filtered across timelines, reply threads, user profiles, and search results.
- **No Trackers or Telemetry**: Zero third-party analytics SDKs; all preferences, bookmarks, drafts, and ranking signals stay strictly on your device.
- **Clean Link Sharing**: Strips tracking query parameters when copying or sharing links.

### Material 3 Expressive & AMOLED Design
- **AMOLED True Black Mode**: Dedicated pure-black theme alongside Material You dynamic color palettes and light/dark modes.
- **Smooth Chrome Continuity**: Top and bottom navigation bars preserve their collapsed/expanded state smoothly when opening posts and returning to the feed.
- **Adaptive Media Framing**: Photos, GIFs, and video carousels clamp cleanly between `1:1` and `16:9` based on the media's natural aspect ratio so tall posts never overwhelm your timeline.
- **Responsive Layouts**: Adapts seamlessly across phones, foldables, and tablets with bottom navigation bars and navigation rails.

### Transparent On-Device "For You" Ranking
- **Local Affinity & Topic Engine**: Scores your feed locally using Room-stored author interactions and topic interests rather than opaque remote tracking.
- **Interest Inspector**: Inspect, tune, or reset the exact authors and topics influencing your personal feed at any time.
- **Mute Rules & Moderation**: Local keyword mutes, user mutes, and block lists persisted in Room.

### Resilient Networking & Configurable Fallback
- **Authenticated X GraphQL Client**: Fast native access to timelines, replies, bookmarks, notifications, likes, reposts, and posting when signed in.
- **Single Configurable Nitter Fallback**: Full HTML parsing for profiles, timelines (Posts, Replies, Media), full thread conversations (ancestors, focal post, and replies), and paginated post/people search—defaulting to `https://nitter.netbub.com` and editable or resettable anytime in **Settings → Advanced**.
- **FxTwitter JSON Safety Net**: Lightweight backup for single posts and profile metadata if your configured Nitter instance is temporarily unavailable.

### Power-User Reading & Media Tools
- **In-Profile Search**: Search posts directly within any user's profile.
- **Distraction-Free Media Viewer**: Full-screen image and video viewer with a 3-second auto-hiding HUD and gesture controls.
- **Save Post as Image**: Renders crisp, preloaded post screenshots with instant handoff to Android's system image markup/editor or share sheet.
- **Local Bookmarks, Drafts & Multi-Account Support**: Organize bookmarks into local folders, queue drafts offline, and switch seamlessly between multiple accounts.

---

## Architecture

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI (Material 3)                     │
│   Home • Explore • Profile (+Search) • Thread • Bookmarks • Settings       │
└─────────────────────────────────────┬──────────────────────────────────────┘
                                      │ StateFlow / ViewModels
┌─────────────────────────────────────▼──────────────────────────────────────┐
│                   Repositories & On-Device Ranking Engine                  │
│   • Promoted Post Filtering  • Local Affinity & Topic Scoring  • Caching   │
└──────────────┬──────────────────────────────────────────────┬──────────────┘
               │ Local Persistence                            │ Networking
               ▼                                              ▼
┌──────────────────────────────┐               ┌─────────────────────────────┐
│    Room DB + DataStore       │               │  1. X GraphQL Client (Auth) │
│  Accounts, Cache, Bookmarks, │               │  2. Nitter HTML Fallback    │
│  Affinities, Mutes, Drafts   │               │     (nitter.netbub.com)     │
│                              │               │  3. FxTwitter JSON Safety   │
└──────────────────────────────┘               └─────────────────────────────┘
```

### Tech Stack
| Layer | Libraries & Tools |
| :--- | :--- |
| **Language & Concurrency** | Kotlin, Kotlin Coroutines & Flow |
| **UI & Navigation** | Jetpack Compose, Material 3, Navigation Compose, Edge-to-Edge |
| **Persistence** | Room (KSP), Preferences DataStore, WorkManager |
| **Networking & Media** | OkHttp 5, Moshi (KSP codegen), Coil 3, AndroidX Browser |
| **Testing** | JUnit 4, Robolectric, Coroutines Test |

---

## Download & Install

### Prebuilt Release Binaries (GitHub Releases & Actions)
Every push to `main` (and any `v*` tag or manual **Run workflow** dispatch) automatically runs unit tests, builds **R8-optimized, resource-shrunk Release binaries**, and publishes them to **GitHub Releases** with `SHA256SUMS.txt` checksums:

| Artifact | Best For |
| :--- | :--- |
| `ilay-for-x-*-arm64-v8a.apk` | Most modern 64-bit Android phones & tablets *(recommended)* |
| `ilay-for-x-*-armeabi-v7a.apk` | Older 32-bit ARM Android devices |
| `ilay-for-x-*-x86_64.apk` | 64-bit Android emulators, Chromebooks, & x86 tablets |
| `ilay-for-x-*-universal.apk` | Single APK compatible with all CPU architectures |
| `ilay-for-x-*.aab` | Android App Bundle for Google Play distribution |

- **Automatic Releases on `main`**: Pushing a commit to `main` automatically publishes a new Release tagged `v<versionName>.<run_number>` (e.g., `v1.0.5`) with auto-generated release notes.
- **Custom Version Releases**: Either push a git tag (`git tag v1.1.0 && git push origin v1.1.0`) or click **Actions → Android Release CI → Run workflow** and enter a custom `release_tag`.
- **Pull Request Artifacts**: Open the **Actions** tab on any PR run to download `ilay-for-x-release-artifacts` without publishing a public Release.

### Setting Up Your Permanent Release Keystore (Linux)
To sign every release with the same key (so users can install updates in place without uninstalling), generate a permanent upload keystore once on Linux and store it in GitHub Secrets:

#### 1. Generate the PKCS12 keystore (`alias: upload`)
```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -storetype PKCS12 \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Ilay for X, O=Ilay for X, C=US"
```
Choose a strong password when prompted (for PKCS12, your keystore password and key password are the same).

> **Keep `my-upload-key.jks` safe and private!** Back it up in a password manager or encrypted offline drive, and **never commit it to Git** (`*.jks` is already gitignored).

#### 2. Base64-encode as a single line
```bash
base64 -w 0 my-upload-key.jks > keystore.b64
cat keystore.b64
```
Copy the entire single-line output (with no extra spaces or newlines), then delete the temporary text file:
```bash
rm -f keystore.b64
```

#### 3. Add the 3 GitHub Actions Secrets
In your GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret Name | Value |
| :--- | :--- |
| `RELEASE_KEYSTORE_BASE64` | Single-line base64 string from Step 2 |
| `STORE_PASSWORD` | Password you chose in Step 1 |
| `KEY_PASSWORD` | Same password you chose in Step 1 (PKCS12) |

*(Until these 3 secrets are added, GitHub Actions signs builds with an ephemeral temporary keystore so CI still passes, and displays a warning in the workflow summary.)*

---

## Building from Source

### Prerequisites
- **JDK 21** (or JDK 17+)
- **Android SDK** with `compileSdk = 36` (`minSdk = 24`)
- **Gradle 9.3.1** (or Android Studio with Gradle 9.3.1+)

### 1. Clone the repository
```bash
git clone https://github.com/<your-username>/ilay-for-x.git
cd ilay-for-x
```

### 2. Run unit tests
```bash
gradle :app:testDebugUnitTest
```

### 3. Build R8-Optimized Release APKs & App Bundle
Provide your signing environment variables (key alias `upload`):
```bash
export KEYSTORE_PATH="/path/to/my-upload-key.jks"
export STORE_PASSWORD="your-store-password"
export KEY_PASSWORD="your-key-password"

# Multi-ABI (arm64-v8a, armeabi-v7a, x86_64) + universal APKs
gradle :app:assembleRelease -PenableAbiSplits=true

# Android App Bundle (.aab)
gradle :app:bundleRelease
```

### 4. Local Debug Build (Development)
Decode or generate the gitignored `debug.keystore`, then assemble:
```bash
base64 -d debug.keystore.base64 > debug.keystore
gradle :app:assembleDebug
```

---

## Disclaimer

Ilay for X is an independent, community-driven open-source project and is **not affiliated with, endorsed by, or officially connected to X Corp.** All trademarks, logos, and brand names belong to their respective owners. Use this application responsibly and in accordance with applicable terms and local laws.

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see the [LICENSE](LICENSE) file for full details.
