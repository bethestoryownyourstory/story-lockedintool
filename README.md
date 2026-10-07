# STORY (Android overlay)

STORY floats over your phone. Your home screen, icon pack, lock screen and apps are not touched.

What you get once it's running:
- **Station 3** button: corner button at the bottom right, over any app. Tap it to open Station 3 on its own.
  It stays open until you tap its corner arrow, and its window covers only Station 3, so the app underneath keeps working.
- **STORY bar** on the phone's gesture line (bottom centre):
  - tap = Apps | Pages switch ("Apps" = your real home screen, "Pages" = Money / Wallet / Focus)
  - swipe up = your real home screen
  - hold, then swipe up = the phone's real recents
- Inside STORY: Dashboard -> Apps lists your real apps with their real icons; tapping one opens the real app.

## Install
1. Put this folder in a GitHub repo (the `.github` folder at the top level).
2. Repo -> Actions -> "Build STORY launcher APK" -> when green, download **story-launcher-apk**, unzip, install `app-debug.apk`.
3. Open **STORY**: (1) allow display over other apps, (2) turn on "STORY system actions" in Accessibility, (3) Start STORY.

## Releases: Test and Live
All files live in the repo root (no folders). The workflows arrange them into an Android project when they build.

- **Test channel** (the owner's phone): every push to `main` is built and published as the `latest` release, and the newest
  `index.html` goes to `/preview/`. The owner's phone installs new builds by itself within a couple of minutes.
- **Live channel** (everyone else): the `live` release and the live screens. They only change when the owner publishes.
  Users see **Update available** (Update now / Later). If they choose Later for 3 days, the update becomes required.
- **Publishing:** in owner mode, the STORY setup screen has **Publish live update**. It asks for the PIN and sends it to the
  release server (`release-worker.js`, a Cloudflare Worker). The server checks the PIN and runs "STORY screens - Test, then
  Approve", which copies the Test app build and screens to Live.
- **Owner mode:** on the setup screen, tap the build line at the bottom 7 times and enter the PIN (checked by the server).
- **Changing the PIN:** Cloudflare -> Workers -> story-release -> Settings -> Variables and Secrets -> `PIN`. No app update needed.
- **Server address:** `release.json` (`"server"`). The app reads it from GitHub, so changing it needs no app update.

The website is published from the `gh-pages` branch (`/` = live, `/preview/` = Test).
