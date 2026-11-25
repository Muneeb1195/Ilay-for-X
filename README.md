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
Every tagged release (`v*`) and CI run produces **R8-optimized, resource-shrunk Release builds** with `SHA256SUMS.txt` verification checksums:

| Artifact | Best For |
| :--- | :--- |
| `ilay-for-x-*-arm64-v8a.apk` | Most modern 64-bit Android phones & tablets *(recommended)* |
| `ilay-for-x-*-armeabi-v7a.apk` | Older 32-bit ARM Android devices |
| `ilay-for-x-*-x86_64.apk` | 64-bit Android emulators, Chromebooks, & x86 tablets |
| `ilay-for-x-*-universal.apk` | Single APK compatible with all CPU architectures |
| `ilay-for-x-*.aab` | Android App Bundle for Google Play distribution |

- **Stable Releases**: Grab the right APK for your device from the **Releases** page whenever a `v*` tag is published.
- **Latest CI Builds**: Open the **Actions** tab, select the latest green **Android Release CI** run, and download the `ilay-for-x-release-artifacts` bundle.

### Permanent Release Signing in GitHub Actions
To ensure every published APK and AAB uses your permanent signing key (allowing seamless in-place updates without reinstalling), add these 3 repository secrets under **Settings → Secrets and variables → Actions**:
- `RELEASE_KEYSTORE_BASE64`: Base64-encoded `.jks`/`.p12` keystore containing key alias `upload`
- `STORE_PASSWORD`: Keystore password
- `KEY_PASSWORD`: `upload` key password

*(Until these secrets are configured, GitHub Actions automatically generates an ephemeral signing key per run so CI builds still succeed.)*

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
