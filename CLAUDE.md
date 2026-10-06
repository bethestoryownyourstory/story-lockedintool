# STORY - working rules

- All source files live in the repo root, flat (no folders). `.github/workflows/android.yml` turns them into an Android project at build time; `android.yml` / `screens.yml` in the root are copies of the workflows - keep them identical.
- Fixes to STORY go in these real app files, committed and pushed **straight to `main`**.
- After every push, confirm "Build STORY APK" is green. It fails unless the APK is signed with the repo key (`debug.keystore.b64`, SHA-256 FC58...3569) and its versionCode (run number + 100) is higher than the published one. Only `main` builds are published to phones.
- Phones update themselves from the `latest` release; the owner never has to install anything by hand.
- Screens (`index.html`) go to the Test channel (gh-pages `/preview/`). They go live only when the owner says "approve" in chat - then run the "STORY screens - Test, then Approve" workflow.
