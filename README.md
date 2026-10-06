# STORY (Android overlay)

STORY floats over your phone. Your home screen, icon pack, lock screen and apps are not touched.

What you get once it's running:
- **Station 3** button: round button at the bottom right, over any app. Tap it to open STORY with Station 3 open.
- **STORY bar** on the phone's gesture line (bottom centre):
  - tap = Apps | Pages switch ("Apps" = your real home screen, "Pages" = Money / Wallet / Focus)
  - swipe up = your real home screen
  - hold, then swipe up = the phone's real recents
- Inside STORY: Dashboard -> Apps lists your real apps with their real icons; tapping one opens the real app.

## Install
1. Put this folder in a GitHub repo (the `.github` folder at the top level).
2. Repo -> Actions -> "Build STORY launcher APK" -> when green, download **story-launcher-apk**, unzip, install `app-debug.apk`.
3. Open **STORY**: (1) allow display over other apps, (2) turn on "STORY system actions" in Accessibility, (3) Start STORY.

To update the screens: replace `app/src/main/assets/index.html` with the new `lockedin.html` (renamed) and push.
