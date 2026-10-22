// Lockedintool's website (owner, Oct 2026: 'a website for the company where the plans will be at - like the
// Lockedintool website'; the owner puts its address on CIPC's eServices and buys a domain for it).
// Built from the app's OWN words in index.html - the plan texts (SUB_PLANS), the two options (SUB_OPTIONS), the
// Information page and the Privacy Policy / Terms of Service (LEGAL) - so the website always says exactly what the app
// says. Prices come from the message server (deploy-site.yml passes /v1/health's plans in PLANS_JSON).
//   node site-build.mjs <out dir>      (deploy-site.yml, then Cloudflare serves the folder - site-wrangler.toml)
import fs from 'node:fs';
import path from 'node:path';

const OUT = process.argv[2] || '_site';
const SRC = fs.readFileSync(new URL('./index.html', import.meta.url), 'utf8');

// The value that starts at the bracket right after `marker` (strings and template literals skipped).
function grab(marker, from = 0) {
  const at = SRC.indexOf(marker, from);
  if (at < 0) throw new Error('not found in index.html: ' + marker);
  let i = at + marker.length;
  while (!'[{\''.includes(SRC[i])) i++;
  if (SRC[i] === '\'') return SRC.slice(i, SRC.indexOf('\'', i + 1) + 1);
  const open = SRC[i], close = open === '[' ? ']' : '}';
  let depth = 0, q = '';
  for (let j = i; j < SRC.length; j++) {
    const c = SRC[j];
    if (q) { if (c === '\\') j++; else if (c === q) q = ''; continue; }
    if (c === '\'' || c === '"' || c === '`') q = c;
    else if (c === open) depth++;
    else if (c === close && --depth === 0) return SRC.slice(i, j + 1);
  }
  throw new Error('unbalanced: ' + marker);
}
const value = src => new Function('return (' + src + ')')();

const PLANS = value(grab('const SUB_PLANS ='));
const OPTIONS = value(grab('const SUB_OPTIONS ='));
const LEGAL = value(grab('const LEGAL ='));
const UPDATED = value(grab('const LEGAL_UPDATED ='));
// The Information page's blocks (not 'COMING NEXT' - the plans are on the website itself).
const infoAt = SRC.indexOf('function informationScreen(){');
const INFO = [];
for (const t of ['WHAT LOCKEDINTOOL IS', 'HOW IT WORKS', 'EVERYTHING IS REAL', 'YOUR PRIVACY'])
  INFO.push([t, value(grab("block('" + t + "',", infoAt))]);
let PRICES = { basic: { usd: 5, zar: 95 }, premium: { usd: 15, zar: 285 } };
try { const p = JSON.parse(process.env.PLANS_JSON || 'null'); if (p && p.basic && p.premium) PRICES = p; } catch (e) {}

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const money = (cur, n) => cur + (Number.isInteger(+n) ? String(+n) : Number(n).toFixed(2));
const YEAR = new Date().getFullYear();
const MAIL = 'lockedintool@gmail.com';
const GET = 'https://bethestoryownyourstory.github.io/story-lockedintool/get/';

const CSS = `
:root{--bg:#000;--line:#262626;--text:#fff;--dim:#9a9a9a;--btn:#fff;--btn-text:#000;--green:#30d158}
*{box-sizing:border-box}
html{scrollbar-width:none;scroll-behavior:smooth}
::-webkit-scrollbar{display:none}
html,body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;-webkit-text-size-adjust:100%}
a{color:var(--text)}
.wrap{max-width:980px;margin:0 auto;padding:0 16px}
header{position:sticky;top:0;z-index:5;background:rgba(0,0,0,.92);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
header .wrap{display:flex;align-items:center;justify-content:space-between;height:58px;gap:12px}
.brand{display:flex;align-items:center;gap:10px;text-decoration:none;font-weight:800;font-size:18px;letter-spacing:.3px}
.brand img{width:34px;height:34px;border-radius:9px}
nav{display:flex;gap:18px;font-size:14px}
nav a{text-decoration:none;color:var(--dim)}
nav a:hover{color:var(--text)}
.hero{text-align:center;padding:56px 0 30px}
.hero img{width:140px;height:140px;border-radius:32px}
.hero h1{margin:18px 0 0;font-size:40px;letter-spacing:.5px}
.tag{margin:8px 0 0;color:var(--dim);font-size:17px}
.lead{max-width:560px;margin:18px auto 0;font-size:17px;line-height:1.55}
.btns{display:flex;gap:12px;justify-content:center;flex-wrap:wrap;margin-top:28px}
.btn{display:inline-block;min-width:200px;padding:15px 22px;border-radius:14px;background:var(--btn);color:var(--btn-text);font-weight:700;font-size:16px;text-decoration:none;text-align:center}
.btn.line{background:transparent;color:var(--text);border:1px solid #444}
#get[hidden]{display:none}
h2.sec{margin:46px 0 12px;font-size:13px;letter-spacing:1.4px;color:var(--dim);font-weight:700}
.grid{display:grid;grid-template-columns:1fr;gap:12px}
@media (min-width:760px){.grid.two{grid-template-columns:1fr 1fr}}
.card{border:1px solid var(--line);border-radius:16px;padding:16px 18px}
.card h3{margin:0 0 4px;font-size:16px}
.card p{margin:0;color:var(--dim);font-size:15px;line-height:1.5}
.rows .card{padding:0}
.row{padding:14px 18px;border-bottom:1px solid var(--line)}
.row:last-child{border-bottom:0}
.row b{display:block;font-size:15px;margin-bottom:3px}
.row span{color:var(--dim);font-size:14px;line-height:1.5}
.plans-head{text-align:center;margin:60px 0 6px}
.plans-head h2{margin:0;font-size:30px;letter-spacing:.5px}
.plans-head p{margin:8px auto 0;max-width:560px;color:var(--dim);font-size:15px;line-height:1.5}
.opt h3{display:flex;align-items:center;gap:8px}
ul{margin:10px 0 0;padding-left:20px;line-height:1.7;font-size:15px}
.plan{border:1px solid var(--line);border-radius:18px;padding:22px 20px;display:flex;flex-direction:column}
.plan.top{border-color:#555}
.plan .name{display:flex;justify-content:space-between;align-items:baseline;gap:10px}
.plan .name h3{margin:0;font-size:26px}
.plan .price{font-size:24px;font-weight:800;white-space:nowrap}
.plan .price small{font-size:14px;font-weight:600;color:var(--dim)}
.badge{align-self:flex-start;margin-top:8px;padding:3px 10px;border-radius:8px;background:rgba(48,209,88,.15);color:var(--green);font-size:13px;font-weight:700}
.plan .t{margin:14px 0 6px;font-weight:700;font-size:17px}
.plan .x{margin:0;color:#d6d6d6;font-size:15px;line-height:1.55}
.plan ul{flex:1}
.zar{margin-top:14px;padding-top:12px;border-top:1px solid var(--line);color:var(--dim);font-size:14px;line-height:1.5}
.how{margin-top:14px;text-align:center;color:var(--dim);font-size:15px;line-height:1.6}
.norefund{margin-top:10px;text-align:center;font-weight:700;font-size:15px}
.agree{margin-top:6px;text-align:center;color:var(--dim);font-size:14px}
footer{margin:60px 0 0;border-top:1px solid var(--line);padding:26px 0 40px;color:var(--dim);font-size:14px;line-height:1.7;text-align:center}
footer a{color:var(--text);text-decoration:none}
.legal h1{margin:40px 0 4px;font-size:32px}
.legal .date{color:var(--dim);font-size:14px}
.legal h2{margin:30px 0 8px;font-size:13px;letter-spacing:1.3px;color:var(--dim)}
.legal .card p{color:#e6e6e6;margin:0 0 10px;line-height:1.6}
.legal .card p:last-child{margin-bottom:0}
@media (max-width:520px){nav a.wide{display:none}.hero h1{font-size:34px}}
`;

const page = (title, desc, rel, body) => `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${esc(title)}</title>
<meta name="description" content="${esc(desc)}">
<meta name="theme-color" content="#000000">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(desc)}">
<meta property="og:image" content="${rel}icon-512.png">
<link rel="icon" href="${rel}icon-192.png">
<link rel="apple-touch-icon" href="${rel}icon-192.png">
<style>${CSS}</style>
</head>
<body>
<header><div class="wrap">
  <a class="brand" href="${rel}"><img src="${rel}icon-192.png" alt="">Lockedintool</a>
  <nav><a href="${rel}#plans">Plans</a><a class="wide" href="${rel}privacy/">Privacy</a><a class="wide" href="${rel}terms/">Terms</a><a href="${rel}#contact">Contact</a></nav>
</div></header>
<main class="wrap">
${body}
</main>
<footer><div class="wrap">
  © ${YEAR} Lockedintool · South Africa<br>
  <a href="${rel}privacy/">Privacy Policy</a> · <a href="${rel}terms/">Terms of Service</a> · <a href="mailto:${MAIL}">${MAIL}</a>
</div></footer>
</body>
</html>
`;

const info = INFO.map(([t, rows]) => `<h2 class="sec">${esc(t)}</h2>
<div class="grid two">${rows.map(([n, s]) => `<div class="card"><h3>${esc(n)}</h3><p>${esc(s)}</p></div>`).join('')}</div>`).join('\n');

const options = OPTIONS.map(o => `<div class="card opt"><h3>${esc(o.name)}</h3><p>${esc(o.text)}</p><ul>${o.list.map(x => `<li>${esc(x)}</li>`).join('')}</ul></div>`).join('');

const plans = PLANS.map(p => { const pr = PRICES[p.key] || {}; return `<div class="plan${p.badge ? ' top' : ''}">
  <div class="name"><h3>${esc(p.name)}</h3><div class="price">${money('$', pr.usd)}<small>/month</small></div></div>
  ${p.badge ? `<div class="badge">${esc(p.badge)}</div>` : ''}
  <div class="t">${esc(p.tag)}</div>
  <p class="x">${esc(p.text)}</p>
  <ul>${p.list.map(x => `<li>${esc(x)}</li>`).join('')}</ul>
  <div class="zar">In South Africa: ${money('R', pr.zar)} a month, paid in rand from your own bank.</div>
</div>`; }).join('');

const home = page('Lockedintool', 'Lockedintool works as one with your phone - your money, your cards, your trading, your notes and your people in one place.', '', `
<section class="hero">
  <img src="icon-512.png" alt="Lockedintool">
  <h1>Lockedintool</h1>
  <p class="tag">Works as one with your phone.</p>
  <p class="lead">Your money, your cards, your trading, your notes and your people - without switching between apps.</p>
  <div class="btns"><a class="btn" href="#plans">See the plans</a><a class="btn line" id="get" href="${GET}" hidden>Download for Android</a></div>
</section>
${info}
<section id="plans">
  <div class="plans-head"><h2>LOCKED IN TOOL</h2><p>Choose how Lockedintool works on your phone, then choose your plan.</p></div>
  <h2 class="sec">TWO WAYS TO USE IT</h2>
  <div class="grid two">${options}</div>
  <h2 class="sec">PLANS</h2>
  <div class="grid two">${plans}</div>
  <p class="how">You choose your plan inside the Lockedintool app: create your account, then open Subscriptions on the Dashboard. You pay on the payment provider’s own secure page.</p>
  <p class="norefund">No refunds - payments can’t be refunded once made.</p>
  <p class="agree">By choosing a plan you agree to the <a href="terms/">Terms of Service</a> and <a href="privacy/">Privacy Policy</a>.</p>
</section>
<section id="contact">
  <h2 class="sec">CONTACT</h2>
  <div class="card"><h3>Lockedintool</h3><p>South Africa · <a href="mailto:${MAIL}">${MAIL}</a></p></div>
</section>
<script>
  // 'Download for Android' only once Lockedintool is out for everyone (owner: nothing goes public before the launch).
  fetch('https://bethestoryownyourstory.github.io/story-lockedintool/launched.txt', { cache: 'no-store' })
    .then(function (r) { if (r.ok) document.getElementById('get').hidden = false; }).catch(function () {});
</script>`);

const legal = key => { const L = LEGAL[key]; return page(L.title + ' - Lockedintool', L.title + ' for Lockedintool.', '../', `
<article class="legal">
  <h1>${esc(L.title)}</h1>
  <div class="date">Last updated ${esc(UPDATED)}</div>
  ${L.sections.map(([h, ps]) => `<h2>${esc(String(h).toUpperCase())}</h2><div class="card">${ps.map(x => `<p>${esc(x)}</p>`).join('')}</div>`).join('\n  ')}
</article>`); };

const notFound = page('Lockedintool', 'Lockedintool', '/', `<section class="hero"><h1>Page not found</h1><p class="tag">This page doesn’t exist.</p><div class="btns"><a class="btn" href="/">Go to Lockedintool</a></div></section>`);

fs.rmSync(OUT, { recursive: true, force: true });
for (const d of ['', 'privacy', 'terms']) fs.mkdirSync(path.join(OUT, d), { recursive: true });
fs.writeFileSync(path.join(OUT, 'index.html'), home);
fs.writeFileSync(path.join(OUT, 'privacy', 'index.html'), legal('legal-privacy'));
fs.writeFileSync(path.join(OUT, 'terms', 'index.html'), legal('legal-terms'));
fs.writeFileSync(path.join(OUT, '404.html'), notFound);
for (const f of ['icon-192.png', 'icon-512.png']) fs.copyFileSync(new URL('./' + f, import.meta.url), path.join(OUT, f));
console.log('Lockedintool website built in ' + OUT + ': ' + PLANS.length + ' plans, ' + OPTIONS.length + ' options, ' + INFO.length + ' information blocks, prices ' + JSON.stringify(PRICES));
