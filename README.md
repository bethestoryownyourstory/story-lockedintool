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

## Updating
All files live in the repo root (no folders). The workflows arrange them into an Android project when they build.

- **App changes** (`.kt`, `.xml` files): push them. "Build STORY APK" builds the app and publishes it. Installed phones update themselves: While the screen is on, STORY checks every minute (and on every unlock and whenever it is opened), so a new build arrives about a minute after it is built.
- **Screen changes** (`index.html`): push it. It goes to the **Test channel** only.
  1. On a test phone: STORY setup screen -> **Test channel: ON**. That phone now shows the new screens.
  2. When you're happy, say "approve" in the Claude chat and Claude runs **STORY screens - Test, then Approve** for you. Every phone gets the new screens.
     (By hand, if ever needed: Actions -> that workflow -> **Run workflow**.)

One-time setting for the Test channel: Settings -> Pages -> Build and deployment -> Source "Deploy from a branch" -> branch **gh-pages**, folder **/ (root)**.
(The `gh-pages` branch is created by the first run of "STORY screens". `/` is live and `/preview/` is Test.)
