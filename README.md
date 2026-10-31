# Top 25 Scores — Android home screen widget

A resizable widget that shows Top 25 college football games with live scores.

## What it does

- **Resizable:** drag it from 2×1 up to full screen.
  - **1 row tall:** shows just the top game (live games come first) plus a refresh button.
  - **2+ rows tall:** shows a scrollable list of every Top 25 game.
  - **Narrow (2 wide):** uses abbreviations (ND, NAVY).
  - **Wide (4+ wide):** shows full names, logos, and records.
  - **Big (4×3+):** also shows TV networks.
- **Refresh schedule:**
  - Every 15 minutes in the background.
  - **Every 2 minutes while any game is live**, or within 15 minutes of kickoff.
  - Right away when you tap the **⟳** button.
- **Order:** live games, then upcoming games by kickoff, then finals.
- **Tap a game** to open your Top 25 site.

Scores come from ESPN's public college football scoreboard. Without a filter, that feed returns games involving ranked teams.

## Install it, no computer needed

1. On github.com, create a new repository, for example `top25-widget`.
2. Upload everything in this folder, including the hidden `.github` folder, and commit to `main`.
   - Desktop browser: drag the whole folder into "Add file → Upload files."
3. Open the repo's **Actions** tab. The "Build widget APK" run takes about 5 minutes.
4. When it finishes, open **Releases** and download `Top25Widget.apk` on your phone.
5. Open the APK. Android will ask you to allow installs from your browser or Files app. Allow it.
6. Open the **Top 25 Scores** app and tap **Add widget to home screen**.
   - Or: long-press your home screen → Widgets → Top 25 Scores.
7. To resize, long-press the widget and drag its edges.

**Samsung tip:** if updates stop when the phone is idle, go to Settings → Apps → Top 25 Scores → Battery → **Unrestricted**.

## Or build with Android Studio

Open this folder in Android Studio, let it sync, and press Run with your phone connected.

## Customize

Edit `app/src/main/java/.../Data.kt` → `Config`:

| Setting | What it does |
|---|---|
| `SITE_URL` | Page opened when you tap a game. Set this to your exact Scores page URL. |
| `LIVE_REFRESH_MINUTES` | Refresh speed during live games. The default is 2. Lower means fresher scores but more battery. |
| `SCOREBOARD_URL` | Data source. Point it at your own JSON instead if you prefer. |

Colors are in `Palette` at the top of `ScoresWidget.kt`. The resize breakpoints are in the `companion object` just below it.
