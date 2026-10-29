// Lockedintool's website Worker (owner: 'the word Story is never shown anywhere' - 'These are now two different
// companies'). Everything people see lives on Lockedintool's own addresses:
//  - lockedintool.com/download - the Lockedintool app (Lockedintool.apk), fetched from where it's built behind the
//    scenes, so no other address ever shows. While Lockedintool isn't public this is the owner's own version
//    (DOWNLOAD_RELEASE 'latest'); on launch day it becomes the public one ('live').
//  - api.lockedintool.com - Lockedintool's message server (password reset, delete account and payment pages, ...):
//    every request is passed straight on to the server (WebSockets too) and the answer comes back unchanged.
//  - everything else - the website itself (site-build.mjs).
const SERVER = 'https://story-msg.percyjuniormaseko0.workers.dev';
const DOWNLOAD_RELEASE = 'latest';
const APK = 'https://github.com/bethestoryownyourstory/story-lockedintool/releases/download/' + DOWNLOAD_RELEASE + '/app-debug.apk';

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.hostname.startsWith('api.')) {
      const to = new Request(SERVER + url.pathname + url.search, req);
      return fetch(to);
    }
    if (url.pathname === '/download' || url.pathname === '/download/') {
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
