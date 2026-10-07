// STORY release server - a Cloudflare Worker.
// The app sends the PIN here; it is checked HERE, never inside the app.
//   POST /verify  {"pin": "..."} -> {"ok": true} when the PIN is right (unlocks owner mode)
//   POST /publish {"pin": "..."} -> runs "STORY screens - Test, then Approve" on GitHub,
//                                   which copies the Test version (app + screens) to Live.
// Settings (Cloudflare -> this Worker -> Settings -> Variables and Secrets):
//   PIN           (secret) the owner PIN. Change it there any time - no app update needed.
//   GITHUB_TOKEN  (secret) fine-grained GitHub token: this repo only, "Actions: Read and write".
const REPO = 'bethestoryownyourstory/story-lockedintool';
const WORKFLOW = 'screens.yml';

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

// Compare without leaking how much of the PIN was right.
async function samePin(a, b) {
  const enc = new TextEncoder();
  const [x, y] = await Promise.all([a, b].map(s => crypto.subtle.digest('SHA-256', enc.encode(String(s)))));
  const u = new Uint8Array(x), v = new Uint8Array(y);
  let diff = 0;
  for (let i = 0; i < u.length; i++) diff |= u[i] ^ v[i];
  return diff === 0;
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method !== 'POST') return json({ ok: false, error: 'STORY release server' }, 405);
    if (!env.PIN) return json({ ok: false, error: 'PIN is not set on the server.' }, 500);
    let body = {};
    try { body = await request.json(); } catch (_) {}
    if (!(await samePin(body.pin ?? '', env.PIN))) {
      await new Promise(r => setTimeout(r, 1500));  // slows down guessing
      return json({ ok: false, error: 'Wrong PIN.' }, 401);
    }
    if (path === '/verify') return json({ ok: true });
    if (path === '/publish') {
      if (!env.GITHUB_TOKEN) return json({ ok: false, error: 'GITHUB_TOKEN is not set on the server.' }, 500);
      const r = await fetch(`https://api.github.com/repos/${REPO}/actions/workflows/${WORKFLOW}/dispatches`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${env.GITHUB_TOKEN}`,
          Accept: 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28',
          'User-Agent': 'story-release-server',
        },
        body: JSON.stringify({ ref: 'main' }),
      });
      if (r.status === 204) return json({ ok: true });
      return json({ ok: false, error: `GitHub refused (${r.status}): ${(await r.text()).slice(0, 200)}` }, 502);
    }
    return json({ ok: false, error: 'Not found' }, 404);
  },
};
