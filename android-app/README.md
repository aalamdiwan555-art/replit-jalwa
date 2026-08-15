# ATPILOT

ATPILOT is an offline-first Android testing utility for controlled, user-started visual matching workflows. It keeps account data, administrator controls, and reference templates on the device, and runs only one configured test action after a valid match.

## Safety and scope

This project is intended for legitimate testing of software and devices you own or are authorized to test. It does not implement anti-bot evasion, anti-cheat bypasses, rate-limit evasion, credential collection, screen-data upload, or unattended automation.

The accessibility service:

- starts only after the user explicitly presses Start;
- performs one configured system test action;
- stops when the user presses Stop or Android disables the service;
- does not retrieve unrelated accessibility content or write screenshots to disk.

## Features

- Local Room database for users, administrators, and template metadata.
- Salted, iterated SHA-256 password hashing; no plaintext passwords.
- Five-attempt-per-minute login limiter.
- Hidden administrator entry point by tapping the signed-in user's name ten times within a short sequence.
- Secure first-run administrator initialization for `diwanatik84@gmail.com`.
- User approval, rejection, disable/re-enable, and subscription durations.
- 1-day, 2-day, 3-day, custom, and lifetime subscriptions using timestamps.
- Admin-only template import into `files/private_templates`.
- Sixteen seed templates from the requested `Detail-Report` repository, copied to private storage on first run.
- No Gallery, MediaStore, Downloads, Firebase, analytics, advertising, or cloud upload.
- Permission Center for accessibility, overlay, and private-storage status.
- User-consented MediaProjection capture with a visible foreground notification.
- Floating WindowManager controller with drag, collapse, pause, resume, and stop.
- In-memory pixel matcher with bounded frame processing, cooldown/debounce, and optional safe region.
- Dark UI with responsive Compose layouts.

## Build in Android Studio

1. Open the `android-app` directory.
2. Use Android Studio Ladybug or newer with JDK 17.
3. Allow Android Studio to download the Android SDK platform 35 and build tools.
4. Sync Gradle.
5. Run the `app` configuration on an Android 8.0+ device or emulator.

Live screen capture requires Android 8.0 (API 26) or newer and always uses Android's user-approved MediaProjection dialog. Accessibility is separate and is used only for the configured system test action.

Command-line builds:

```bash
bash ./gradlew assembleDebug
```

The wrapper script downloads the pinned Gradle wrapper bootstrap jar on first use if it is not already present. Android Studio can also regenerate the standard wrapper from the project files.

## First run

1. Tap **Create an account**, register a local user, and sign in.
2. On the signed-in dashboard, tap the user's **Name** value ten times within a short sequence to open **Administrator access**.
3. Choose **First-run setup**, use the designated administrator email, and create a unique 12+ character password.
4. Approve the user and assign a subscription.
5. Import a reference image from the admin-only picker. It is copied into app-private storage; the normal user never sees it.
6. Sign in as the user, grant permissions through Permission Center, and press Start.

### Seed-template privacy note

The seed images are bundled under `app/src/main/assets/templates` so a fresh install can initialize them locally. The app never displays them to normal users and copies them to private internal storage on first run. However, anything bundled in an APK can be extracted by someone who has the APK. For genuinely confidential templates, remove the seed assets and use the administrator-only import flow instead.

## Project layout

- `app/src/main/java/com/replit/jalwa/data` — Room entities, security, subscriptions, and private file storage.
- `app/src/main/java/com/replit/jalwa/capture` — MediaProjection manager and foreground service.
- `app/src/main/java/com/replit/jalwa/detection` — in-memory matcher, frame processor, and detection state machine.
- `app/src/main/java/com/replit/jalwa/overlay` — visible WindowManager floating controller.
- `app/src/main/java/com/replit/jalwa/actions` — single configured action boundary.
- `app/src/main/java/com/replit/jalwa/accessibility` — minimal user-controlled accessibility service.
- `app/src/main/java/com/replit/jalwa/MainActivity.kt` — Compose UI and screen flows.
- `app/src/main/assets/templates` — requested seed images, copied into private storage during first run.
- `app/src/test` — local unit tests for hashing, subscriptions, and matching.

## License

New source in this project is provided under the MIT license in `LICENSE`.

The official Smart AutoClicker/Klick’r project is GPL-3.0 licensed. This build does not copy Smart AutoClicker source files; it implements its data, security, UI, and business logic independently. The upstream project is listed in `THIRD_PARTY_NOTICES.md`, and the full GPL-3.0 text is included under `third-party-licenses/GPL-3.0.txt`.