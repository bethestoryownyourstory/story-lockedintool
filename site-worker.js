// Lockedintool's website Worker (owner: 'the word Story is never shown anywhere' - 'These are now two different
// companies'). Everything people see lives on Lockedintool's own addresses:
//  - lockedintool.com/download - the Lockedintool app (Lockedintool.apk), fetched from where it's built behind the
//    scenes, so no other address ever shows. While Lockedintool isn't public this is the owner's own version
//    (DOWNLOAD_RELEASE 'latest'); on launch day it becomes the public one ('live').
//  - api.lockedintool.com - Lockedintool's message server (password reset, delete account and payment pages, ...):
//    every request is passed straight on to the server (WebSockets too) and the answer comes back unchanged.
//  - everything else - the website itself (site-build.mjs).
const SERVER = 'https://lockedintool-msg.percyjuniormaseko0.workers.dev';
// Lockedintool's other servers, each on its own Lockedintool address (the names behind them are never seen).
const HOSTS = { api: SERVER, trade: 'https://lockedintool-trade.percyjuniormaseko0.workers.dev', release: 'https://lockedintool-release.percyjuniormaseko0.workers.dev' };
// Where the app's screens and builds are kept today. Only these lines change when that home moves (Lockedintool's own
// GitHub account) - every phone keeps using lockedintool.com/app/ and lockedintool.com/releases/.
const SCREENS = 'https://lockedintool.github.io/story-lockedintool/';
const BUILDS = 'https://github.com/lockedintool/story-lockedintool/releases/download/';
const CONFIG = 'https://raw.githubusercontent.com/lockedintool/story-lockedintool/main/release.json';
const DOWNLOAD_RELEASE = 'latest';
// Private until launch (owner's PAUSE rule): the download answers only at /download/<this code>; on launch day set it
// to '' so the plain lockedintool.com/download works for everyone (and DOWNLOAD_RELEASE to 'live').
const DOWNLOAD_CODE = 'uje5Mg0x9j7yONbB';
const APK = BUILDS + DOWNLOAD_RELEASE + '/app-debug.apk';

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const sub = url.hostname.split('.')[0];
    if (HOSTS[sub] && url.hostname.endsWith('lockedintool.com') && url.hostname !== 'lockedintool.com') {
      return fetch(new Request(HOSTS[sub] + url.pathname + url.search, req));
    }
    // The app's screens (lockedintool.com/app/ and /app/preview/) - fresh every time, like before.
    if (url.pathname === '/app' || url.pathname.startsWith('/app/')) {
      const r = await fetch(SCREENS + url.pathname.slice(5) + url.search, { cf: { cacheTtl: 0 } });
      const h = new Headers(r.headers); h.set('cache-control', 'no-store');
      return new Response(r.body, { status: r.status, headers: h });
    }
    // The app's builds and its release settings, for its own updates.
    if (url.pathname === '/releases/release.json') {
      const r = await fetch(CONFIG + url.search, { cf: { cacheTtl: 0 } });
      return new Response(r.body, { status: r.status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
    }
    if (url.pathname.startsWith('/releases/')) {
      const rest = url.pathname.slice(10);
      if (!/^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/.test(rest)) return new Response('Not found', { status: 404 });
      const r = await fetch(BUILDS + rest, { redirect: 'follow', cf: { cacheTtl: 0 } });
      const h = new Headers(); h.set('cache-control', 'no-store');
      for (const k of ['content-type', 'content-length']) { const v = r.headers.get(k); if (v) h.set(k, v); }
      return new Response(r.body, { status: r.status, headers: h });
    }
    const dl = DOWNLOAD_CODE ? '/download/' + DOWNLOAD_CODE : '/download';
    if (url.pathname === dl || url.pathname === dl + '/') {
      const r = await fetch(APK, { redirect: 'follow', cf: { cacheTtl: 60 } });
      if (!r.ok) return new Response('The download isn’t available right now - try again in a minute.', { status: 503, headers: { 'content-type': 'text/plain; charset=utf-8' } });
      const h = new Headers();
      h.set('content-type', 'application/vnd.android.package-archive');
      h.set('content-disposition', 'attachment; filename="Lockedintool.apk"');
      h.set('cache-control', 'no-store');
      const len = r.headers.get('content-length'); if (len) h.set('content-length', len);
      return new Response(r.body, { status: 200, headers: h });
    }
    return env.ASSETS.fetch(req);
  }
};
