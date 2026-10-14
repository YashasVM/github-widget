# GH-widgets

**Current version: v2.3.0** — 7 fonts, a redesigned app and widget, and a much lighter battery footprint.

Your GitHub contribution graph, live on your Android homescreen. Customize it, draw on it, use your own background, track your activity.

<p align="center">
  <a href="https://github.com/mrduhlol/github-widget/releases/latest/download/GH-widget.v2.3.0.apk">
    <img height="64" alt="Download GH-widgets for Android" src="https://img.shields.io/badge/GET_GH%E2%80%90WIDGETS_FOR_ANDROID-238636?style=for-the-badge&logo=android&logoColor=white" />
  </a>
  <br />
  <sub>Tap the button on your phone — the APK installs straight from your files.</sub>
  <br /><br />
  <img alt="Latest release" src="https://img.shields.io/github/v/release/mrduhlol/github-widget?style=flat-square&label=version&color=1F6FEB" />
  <img alt="APK size" src="https://img.shields.io/badge/size-%7E6.2_MB-0D1117?style=flat-square" />
  <img alt="Android" src="https://img.shields.io/badge/android-8.0%2B-39D353?style=flat-square" />
  <img alt="License" src="https://img.shields.io/badge/free-no_sign%E2%80%90in-8B949E?style=flat-square" />
</p>

## Features

- Live contribution graph on your homescreen
- Fonts — System, Inter, Space Grotesk, JetBrains Mono, Outfit, Press Start 2P (pixel) and DM Serif Display for the widget, preview and share cards
- Battery-friendly refresh — one shared fetch for all widgets, scheduled with JobScheduler (network + battery-not-low), skips the network when data is fresh, respects Battery Saver and Data Saver, ETag requests so unchanged data costs almost nothing
- Refresh settings — background interval (Off, 1h, 3h, 6h, 12h, 24h) and Wi‑Fi only
- Redesigned app — pinned live preview with Presets · Colors · Graph · Background · Text tabs, onboarding, loading and error states, Undo, accessible controls that scale to 200% font
- Widget fills its space at every size, single-line header, refresh icon, real picker preview, reconfigurable on Android 12+
- Accessible colors (WCAG AA text contrast), clear selected states, 48dp touch targets
- Premium share cards — circular profile avatar with accent ring, stat band, records row
- 5 widget styles — Classic, Minimal, Compact, Terminal, Glass
- 5 graph themes — Matrix, Nebula, Abyss, Ember, Neon — plus spectrum color picker
- Custom contribution palette — per-level colors or generated from the theme color
- Custom backgrounds — solid, gradient (linear/radial, 2–3 colors), your own image, transparent
- Background opacity, blur and color overlays, image-through-graph mode
- Drawing Studio — brush, eraser, highlighter, shapes, fill, undo/redo, PNG export, use as background
- Graph range (3 months, 6 months, 1 year), cell shape (square, rounded, soft, circle), cell size and spacing
- Content toggles — count, streaks, active days, last updated, custom label, text size
- Activity analytics — weekly/monthly stats, averages, weekday breakdown, insights, personal records
- 8 data-based achievements with progress bars and streak milestones
- Share Studio — 5 card templates (GitHub, Minimal, Terminal, Neon, Poster) via the native share sheet
- Each widget keeps its own look — setup runs when a widget is added
- Settings — data, look import/export as JSON, reset, privacy, about
- Works offline — last fetched graph stays on screen with its age
- No sign-in, no token — just your username

## Install

1. Tap **DOWNLOAD APK** above on your phone
2. Open the downloaded file
3. Allow **Install unknown apps** when asked
4. Tap **Install**

## Use

1. Open **GH-widgets**, enter your GitHub username — your graph appears instantly
2. Style it in the Studio tabs under the pinned preview — every change previews live
3. Long-press your homescreen → **Widgets** → **GH-widgets** → style it during setup
4. Tap the widget graph for analytics, the header for your GitHub profile

## Configuration

- The app holds the defaults for new widgets; each placed widget can keep its own look
- Share a look as JSON from the Studio or Settings, import it on another device
- Reset the look anytime — username and cached data are never touched

## Privacy

- Username, look settings and the last fetched graph stay on this device only
- Refresh sends your username to the public contribution API — no sign-in, no token
- Images and drawings stay in the app's private storage; no telemetry, nothing uploaded

## Architecture

```
GitHub API → Cache → Analytics
                  ↓
Visual config (global defaults + per-widget overrides + Studio background)
                  ↓
CardRenderer (solid / gradient / image / transparent → blur → overlay)
                  ↓
GraphRenderer (palette, shape, size, spacing, image tint)
                  ↓
Widget / Studio preview / Share card (one pipeline)
```

Refresh: `RefreshJobService` (JobScheduler, constrained) and the widget's refresh icon both go through `Refresher` — one conditional GET (ETag) per batch, then every widget repaints from the cache. Rendered backgrounds and image samples are cached on disk, keyed by their inputs.

- `SharedPreferences` for settings (never images); drawings as PNG files; share via `FileProvider`
- Photo Picker for images — no storage permission

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17. Every push to `main` builds in CI; tags like `v2.0.0` publish the APK to Releases.

## Contributing

Issues and pull requests are welcome — https://github.com/mrduhlol/github-widget/issues
