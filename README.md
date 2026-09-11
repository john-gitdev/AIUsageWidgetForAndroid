# Claude API Usage Widget

An Android home screen widget that tracks and displays your real-time [Claude API](https://console.anthropic.com/) usage and remaining limits.
### Widget Previews
| 2x2 Compact Widget | 4x2 Wide Widget |
| :---: | :---: |
| <img src="widget-2x2.jpg" height="250"/> | <img src="widget-4x2.jpg" height="250"/> |

## Features
- **Real-Time Usage Tracking**: Displays your current Session limit usage and Weekly limit usage natively on your Android home screen.
- **Responsive Widget Layouts**: Automatically switches between a compact vertical layout (optimized for 2x2 up to 4x4) and a horizontal wide layout (optimized for 3x1 and 4x1) depending on how you resize it.
- **Auto-Refresh Integration**: Configurable background sync (via Android `WorkManager`) allows you to automatically fetch new usage data every 15m, 30m, 1h, 2h, 4h, or set it to "Never" for manual-only refreshes.
- **Instant Manual Refresh**: Tapping anywhere on the widget instantly triggers a one-shot sync and provides immediate "Refreshing..." visual feedback.
- **Smart Error Redirect**: If your session expires or encounters a network error, tapping the widget will automatically open the app so you can log back in.
- **Cloudflare & Google Auth Bypass**: Leverages a secure, in-app `WebView` for Google OAuth login. It dynamically extracts the required cookies (`cf_clearance`, `sessionKey`) and exact `User-Agent` to silently authenticate background API requests, fully bypassing Cloudflare's strict 403 Forbidden bot protection.

## Installation
1. Clone this repository:
   ```bash
   git clone https://github.com/john-gitdev/claudewidget.git
   ```
2. Open the project in **Android Studio**.
3. Build the project and install the APK on your device.

## Usage
1. Open the **Claude Widget** app from your app drawer.
2. Log in using your Claude/Anthropic credentials (or Google OAuth) via the secure in-app browser.
3. Once you see the "Login Successful" screen, go to your home screen and add the **Claude Widget**.
4. You can re-open the app at any time to configure your **Auto Refresh Interval** or explicitly log out.

## Technical Notes
If you are interested in how the major technical hurdles were overcome (such as bypassing Cloudflare's bot protection, fixing Google OAuth WebView restrictions, or navigating Android's strict `RemoteViews` constraints), see the in-depth [Development Notes (notes.md)](notes.md) file.
