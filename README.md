# AI Usage Widget

Android home screen widgets that track and display your real-time [Claude](https://claude.ai/) and [ChatGPT](https://chatgpt.com/) usage and remaining limits.
### Widget Previews
| 2x2 Compact Widget | 4x2 Wide Widget |
| :---: | :---: |
| <img src="widget-2x2.jpg" height="250"/> | <img src="widget-4x2.jpg" height="250"/> |

## Features
- **Real-Time Usage Tracking**: Displays your current Session limit usage and Weekly limit usage natively on your Android home screen.
- **Responsive Widget Layouts**: Automatically switches between a compact vertical layout and a horizontal wide layout depending on how you resize it. The widget is optimized for **2x2** and **4x2** sizes (with 2x2 being the default).
- **Auto-Refresh Integration**: Configurable shared background sync (via Android `WorkManager`) updates both widgets and any enabled quota notifications every 15m, 30m, 1h, 2h, 4h, or can be set to "Never" for manual-only refreshes.
- **Instant Manual Refresh**: Tapping anywhere on the widget instantly triggers a one-shot sync and provides immediate "Refreshing..." visual feedback, including across Wi-Fi → mobile-data handoffs.
- **Ongoing Quota Notifications**: Optionally keep a separate Claude and/or ChatGPT quota notification in the notification shade. Each shows session and weekly usage/reset text, includes the last refreshed time in its title, opens the matching service tab when tapped, and has a separate **Refresh** action.
- **Smart Error Redirect**: If your session expires or encounters a network error, tapping the widget opens the app on that service's tab so you can log back in.
- **Shared Settings**: The **Settings** tab contains the auto-refresh interval, shared widget tap action, optional screen-on refresh, and the app log. Screen-on refresh is best-effort while Android keeps the app process alive and uses a two-minute cooldown after recent updates.
- **Error Log**: Open **Settings → View Log** to see recent refreshes, errors, and logins (newest first). You can copy it to share when reporting a problem.
- **Cloudflare & Google Auth Bypass**: Leverages a secure, in-app `WebView` for Google OAuth login. It dynamically extracts the required cookies (`cf_clearance`, `sessionKey`) and exact `User-Agent` to silently authenticate background API requests. Features a custom multi-window popup implementation to natively support Google Sign-In and smart session polling for instant login detection on Single Page Applications.

## Privacy & Security (Open Source)
Because this app requires you to log in to your Claude and/or ChatGPT account (which may have access to billing or private conversations), **security and trust are paramount.** 
- **100% Open Source:** The entire codebase is public. You are encouraged to audit the code (specifically `MainActivity.kt` and `UpdateWidgetWorker.kt`) to verify exactly how your credentials and cookies are handled.
- **No Third-Party Servers:** Your cookies and session data are stored **only** locally on your device using Android's private `SharedPreferences`. The app communicates *directly* with `claude.ai` and `chatgpt.com` to fetch your usage. It does not send your data, telemetry, or credentials anywhere else.

## Installation
For normal installs and upgrades, use the APK attached to a GitHub release. Releases from
1.0.9 onward use the same pinned signing key and install over each other normally.

A locally built **debug** APK uses Android's debug key and cannot replace an installed release
APK with the same application ID; Android reports that as a package conflict. To upgrade an
installed 1.0.9+ build locally, build `assembleRelease` with the same release keystore by setting
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and
`RELEASE_KEY_PASSWORD`. The build log prints whether release signing is enabled.

To work on the source locally:
1. Clone this repository:
   ```bash
   git clone https://github.com/john-gitdev/AIUsageWidgetForAndroid.git
   ```
2. Open the project in **Android Studio**.

## Usage
1. Open the **AI Usage Widget** app from your app drawer.
2. Pick the **Claude** or **ChatGPT** tab and log in (or use Google OAuth) via the secure in-app browser.
3. Once you see the "Connected!" screen, go to your home screen and add the **Claude Widget** or **ChatGPT Widget**.
4. Use the **Settings** tab for shared refresh/log options. Service-specific display and notification options remain on the Claude and ChatGPT tabs.

## Technical Notes
If you are interested in how the major technical hurdles were overcome (such as bypassing Cloudflare's bot protection, fixing Google OAuth WebView restrictions, or navigating Android's strict `RemoteViews` constraints), see the in-depth [Development Notes (notes.md)](notes.md) file.
