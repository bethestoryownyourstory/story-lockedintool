// The owner's rules, checked automatically before ANY screens update reaches a phone (owner, 4 Nov 2026: 'everything that
// I told you, all the problems and fixes I ever told you about - the app is supposed to keep on checking that these
// mistakes don't ever happen again'). screens.yml runs this on every push; one failed rule = the update is stopped.
// Every owner-reported problem that a browser can see gets a check here, written in the owner's words.
// Run: node owner-checks.mjs [url]   (default http://localhost:8800/index.html)
import { chromium } from 'playwright';
const URL = process.argv[2] || 'http://localhost:8800/index.html';
const SIZES = [[360, 780], [393, 852], [412, 892]];
const SCREENS = ['dashboard', 'money', 'wallet', 'trading', 'notes', 'apps', 'community', 'layouts', 'subscription', 'settings', 'information'];
const MAY_SCROLL = new Set(['settings', 'information', 'apps']);   // owner: 'only settings and information' (+ the Apps list)
const UA = 'Mozilla/5.0 (Linux; Android 16; SM-A155F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36';
const fails = [];
const fail = (rule, where, what) => { fails.push(`${rule} | ${where} | ${what}`); };
const b = await chromium.launch();
for (const [w, h] of SIZES) {
  const ctx = await b.newContext({ viewport: { width: w, height: h }, isMobile: true, hasTouch: true, userAgent: UA });
  await ctx.addInitScript(() => { try { localStorage.setItem('story-msg-account', JSON.stringify({ id: 'check', token: 't', pw: true, username: 'check' })); } catch (e) {} });
  const pg = await ctx.newPage();
  const errs = [];
  pg.on('pageerror', e => errs.push(String(e && e.message || e).slice(0, 200)));
  await pg.goto(URL); await pg.waitForTimeout(2500);
  for (const s of SCREENS) {
    errs.length = 0;
    await pg.evaluate(s => window.storyShow && window.storyShow(s, false), s);
    await pg.waitForTimeout(900);
    const r = await pg.evaluate(() => {
      const a = document.getElementById('content-area'); if (!a) return null;
      const ab = a.getBoundingClientRect(), cs = getComputedStyle(a);
      let cut = [];
      a.querySelectorAll('*').forEach(e => { const er = e.getBoundingClientRect(); const st = getComputedStyle(e);
        if (er.height > 0 && er.width > 0 && st.visibility !== 'hidden' && st.position !== 'fixed' && er.bottom > ab.bottom + 3 && !e.closest('[class*="scroll"], .pay-list, .notes-ghosts')) cut.push(e.tagName + '.' + String(e.className || '').slice(0, 30)); });
      const shell = a.firstElementChild, sr = shell ? shell.getBoundingClientRect() : null;
      const text = a.innerText || '';
      return { cur: (window.state || {}).current, canScroll: cs.overflowY !== 'hidden' && a.scrollHeight > a.clientHeight + 1,
        cut: cut.slice(0, 3), fill: sr ? (sr.bottom - ab.top) / ab.height : 0, text };
    });
    const where = `${s} @ ${w}x${h}`;
    if (!r) { fail('screen opens', where, 'no content area'); continue; }
    // 'I told you only settings and information must have scrolling ... not even a tiny bit' (4 Nov 2026)
    if (!MAY_SCROLL.has(s) && r.canScroll) fail('no scrolling except Settings / Information', where, 'the screen scrolls');
    // 'make sure nothing is cutting'
    if (!MAY_SCROLL.has(s) && r.cut.length) fail('nothing cut', where, 'runs off the bottom: ' + r.cut.join(', '));
    // 'pages are always have to be full screen full' (3 Nov 2026)
    if (!MAY_SCROLL.has(s) && r.fill < 0.9) fail('every page fills the whole screen', where, 'fills only ' + Math.round(r.fill * 100) + '%');
    // 'Story is never shown anywhere in the app' (STORY is a separate company)
    if (/\bstor(y|ies)\b/i.test(r.text)) fail("no 'Story' anywhere", where, (r.text.match(/.{0,30}\bstor(y|ies)\b.{0,30}/i) || [''])[0]);
    // 'Focus should never be alone - should be called focus page' (4 Nov 2026)
    const lone = r.text.match(/.{0,20}\b(Focus|FOCUS)\b(?!\s*(page|PAGE)).{0,20}/);   // the page's name (the plain word 'focus' in a sentence is fine)
    if (lone) fail("'Focus' is always 'Focus page'", where, lone[0]);
    // the app must never break: no errors while opening a screen
    if (errs.length) fail('no errors', where, errs[0]);
  }
  await ctx.close();
}
await b.close();
if (fails.length) { console.log('OWNER RULES BROKEN (' + fails.length + '):\n' + fails.join('\n')); process.exit(1); }
console.log('All owner rules pass');
