# Changelog

All notable changes to AI Usage Widget are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Released APKs are attached to each [GitHub release](https://github.com/john-gitdev/AIUsageWidgetForAndroid/releases).

**From 1.0.9 on, releases install over each other normally.** They are signed with a
stable release key, so updating is just a matter of installing the new APK.

**Upgrading from 1.0.8 or earlier needs one uninstall.** Every release up to and
including 1.0.8 was debug-signed, and because each CI run generated a throwaway debug
key, *no two of those releases shared a certificate*. Android keys upgrades off the
signing certificate, so installing 1.0.9 over an older build fails until the old copy is
removed. Uninstalling clears app data, so you will need to sign in to Claude and ChatGPT
again — once.

## [1.0.9] - 2026-09-22

### Changed
- **Releases are signed with a stable release key**, so from this version on updates
  install over each other without uninstalling. Builds are now `assembleRelease` rather
  than `assembleDebug`, and CI verifies the signing certificate against a pinned
  fingerprint before publishing, so a lost or swapped keystore fails the build instead of
  shipping a release that silently orphans every install.
- **Application ID moved from `com.example.claudewidget` to `dev.johngitdev.aiusagewidget`.**
  `com.example.*` is a placeholder namespace that app stores reject. This was done in the
  same version as the signing change so that both would cost only the single uninstall
  that the signing change already required.

### Upgrading
Uninstall any earlier version first, then install this one. Your home screen widgets will
need to be added again, and you will need to log in to Claude and ChatGPT once more.
Updates after this one will not need any of that.

## [1.0.8] - 2026-09-22

### Added
- **In-app log.** Refresh results, HTTP errors (status plus the start of the error
  body, which reveals things like a Cloudflare challenge page) and login events are
  appended to `files/app_log.txt`, capped at 128 KB. Open it with **Log** in the tab
  bar to read it newest-first, with Copy and Clear. Cookies, tokens and the bodies of
  successful responses are never written to it.

### Changed
- **One login browser per service.** Claude and ChatGPT each get their own WebView,
  Google sign-in popup and login poller, tied to the service rather than to whichever
  tab is open. Switching tabs only changes which browser is visible, so a half-finished
  login is still there when you come back. Once a login is detected that browser loads
  `about:blank`, so the site's scripts stop running in the background.
- **"Log out completely" is now scoped to the current service.** The other service stays
  signed in, since its widget and tab read the session saved in preferences.
- Widgets open the app through `MainActivity.openTabIntent()`
  (`NEW_TASK | CLEAR_TOP | SINGLE_TOP`), so an already-running app switches tabs via
  `onNewIntent` instead of just coming to the front.

### Fixed
- **Re-login left the old ChatGPT session in place.** Deleting a `__Secure-` or `__Host-`
  prefixed cookie (such as `__Secure-next-auth.session-token`) requires the `Secure`
  attribute; without it the browser silently ignored the deletion. Expiry strings now
  include it.
- **Widgets opened the wrong tab.** The Claude widget never said which tab to open, and
  because it sent a plain launcher intent a running app simply came to the front.
- The success screen claimed "Connected!" even when the last refresh had failed. It now
  reports the failure, for example "Claude Session Expired".

## [1.0.7] - 2026-09-22

### Added
- **Show Usage As** setting — display how much you have used, or how much is left.
- Option to link **Show Usage As** across Claude and ChatGPT so both widgets match.

### Changed
- Refresh state is kept in sync between the app and its widgets.

> **Note on versioning.** An intermediate commit briefly set the version to `1.1.0`
> before it was walked back to `1.0.7`. That number was never tagged or released, so no
> build was ever published as 1.1.0. Both commits shared `versionCode` 10; only the
> 1.0.7 build shipped with it.

## [1.0.6] - 2026-09-21

### Added
- **ChatGPT widget**, alongside the existing Claude widget.
- Tabbed in-app UI to switch between the two services, each with its own login flow.

### Changed
- Project renamed to **AI Usage Widget** to reflect multi-service support.

## [1.0.5] - 2026-09-21

### Changed
- The Google session is cached on re-login, so signing back in is a single tap.

## [1.0.4] - 2026-09-12

### Fixed
- Google login now works by supporting multiple windows (the sign-in popup) and polling
  for the session cookie, which single-page apps do not surface synchronously.

### Added
- Privacy & Security section in the README.

## [1.0.3] - 2026-09-11

### Changed
- Revamped the 4x2 wide widget layout.

### Fixed
- Widget resize detection.

## [1.0.2] - 2026-09-11

### Changed
- Larger text throughout the widget; all widget text is now white.

## [1.0.1] - 2026-09-11

### Added
- Configurable widget tap action.

## [1.0] - 2026-09-11

Initial release.

[1.0.9]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0.8...v1.0.9
[1.0.8]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/compare/v1.0...v1.0.1
[1.0]: https://github.com/john-gitdev/AIUsageWidgetForAndroid/releases/tag/v1.0
