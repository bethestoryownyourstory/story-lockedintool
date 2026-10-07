# STORY - working rules

- All source files live in the repo root, flat (no folders). `.github/workflows/android.yml` turns them into an Android project at build time; `android.yml` / `screens.yml` / `deploy-worker.yml` in the root are copies of the workflows - keep them identical.
- Fixes to STORY go in these real app files, committed and pushed **straight to `main`**.
- After every push, confirm "Build STORY APK" is green. It fails unless the APK is signed with the repo key (`debug.keystore.b64`, SHA-256 FC58...3569) and its versionCode (run number + 100) is higher than the published one. Only `main` builds are published to phones.
- Two channels. Test = `latest` release + gh-pages `/preview/` (the owner's phone, owner mode, updates silently). Live = `live` release + gh-pages `/` (all users; "Update available" with Update now / Later, required after 3 days).
- Nothing goes Live until the owner publishes: the "Publish live update" button (PIN checked by the Cloudflare Worker in `release-worker.js`) or the owner saying "approve" in chat - then run "STORY screens - Test, then Approve". Never put the PIN in the app or the repo.
- The release server address lives in `release.json`. "Deploy STORY release server" puts `release-worker.js` on Cloudflare (needs repo secrets CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID).
