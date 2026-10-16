// Live, tested for real (Run workflow "Test Lockedintool Live"): two phones in headless Chromium against this
// repo's message server (wrangler dev) and Cloudflare's real live video service. Phone A goes live, phone B watches,
// hearts and comments; the picture must really arrive. Exit code 1 = Live doesn't work.
import { chromium } from 'playwright';
const t = Date.now() % 100000, errs = [], say = (...a)=>console.log(...a);
const b = await chromium.launch({ args:['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] });
async function phone(label){
  const ctx = await b.newContext({ viewport:{ width:393, height:800 }, permissions:['camera', 'microphone'] });
  await ctx.addInitScript(()=>{ if(!localStorage.getItem('story-msg-api')) localStorage.setItem('story-msg-api', 'http://127.0.0.1:8797'); });
  const pg = await ctx.newPage(); pg.on('pageerror', e=>errs.push(label + ': ' + e.message));
  await pg.goto('http://127.0.0.1:8800/index.html'); await pg.waitForTimeout(1500);
  await pg.locator('[data-auth="to-signup"]').click();
  await pg.fill('.lit-auth-name', label); await pg.fill('.lit-auth-user', label.toLowerCase() + t); await pg.fill('.lit-auth-pw', 'livetest123'); await pg.fill('.lit-auth-pw2', 'livetest123');
  await pg.locator('[data-auth="country"]').click(); await pg.locator('.lit-auth-citems button').first().click();
  await pg.locator('.lit-auth-go').click(); await pg.waitForFunction(()=>litSignedIn(), null, { timeout:30000 });
  const eng = await ctx.newPage(); eng.on('pageerror', e=>errs.push(label + ' engine: ' + e.message));
  await eng.goto('http://127.0.0.1:8800/index.html?s3=1&profile=1'); await eng.waitForTimeout(1000);
  await eng.evaluate(()=>{ if(!document.getElementById('story-profile')) window.storyProfileOnly(); });
  await eng.waitForFunction(()=>msgWsOpen, null, { timeout:30000 });
  return { eng, id: await eng.evaluate(()=>msgAccount().id) };
}
let ok = true; const check = (name, pass, extra)=>{ say((pass ? 'PASS ' : 'FAIL ') + name + (extra ? ' - ' + extra : '')); if(!pass) ok = false; };
const A = await phone('Host'), B = await phone('Viewer');
await B.eng.evaluate(id=>msgApi('/v1/follow', { id, on:true }), A.id);
await B.eng.evaluate(()=>{ window.__notes = []; const ws = msgWs, o = ws.onmessage; ws.onmessage = e=>{ try{ const m = JSON.parse(e.data); if(m.t === 'fnote') window.__notes.push(m.kind); }catch(x){} return o.call(ws, e); }; });
// A goes live from Create's Live box
await A.eng.evaluate(()=>{ window.storyS3Page('create'); liveOpenHost(); }); await A.eng.waitForTimeout(2500);
check('host camera preview', await A.eng.evaluate(()=>!!(LIVE.stream && LIVE.stream.getVideoTracks().length)));
await A.eng.evaluate(()=>liveGo()); await A.eng.waitForTimeout(6000);
const host = await A.eng.evaluate(()=>({ id: LIVE.id, start: LIVE.startAt, state: LIVE.state, conn: LIVE.pc && LIVE.pc.connectionState }));
check('host is live', !!host.id && !!host.start, JSON.stringify(host));
check('follower told', (await B.eng.evaluate(()=>window.__notes)).includes('live'));
const list = await B.eng.evaluate(()=>msgApi('/v1/live/list'));
check('live in the follower\'s Updates', (list.lives || []).some(l=>l.id === host.id), JSON.stringify(list.lives || []).slice(0, 200));
// B watches
await B.eng.evaluate(id=>{ window.storyS3Page('updates'); liveWatch(id); }, host.id);
await B.eng.waitForTimeout(12000);
const v = await B.eng.evaluate(()=>{ const x = document.querySelector('.pf-live .lv-video'); return { state: LIVE.state, conn: LIVE.pc && LIVE.pc.connectionState, tracks: LIVE.stream ? LIVE.stream.getTracks().map(t=>t.kind) : [], w: x && x.videoWidth, h: x && x.videoHeight, time: x && x.currentTime }; });
check('viewer gets the picture', v.w > 0 && v.h > 0, JSON.stringify(v));
check('viewer gets the sound', v.tracks.includes('audio'), JSON.stringify(v.tracks));
check('host sees 1 watching', await A.eng.evaluate(()=>LIVE.viewers) === 1);
await B.eng.evaluate(()=>liveHeart()); await B.eng.evaluate(()=>{ document.querySelector('.pf-live .lv-in').value = 'Great setup'; liveComment(); }); await A.eng.waitForTimeout(2000);
const hs = await A.eng.evaluate(()=>({ hearts: LIVE.hearts, comments: LIVE.comments.map(c=>c.text) }));
check('host gets hearts and comments', hs.hearts === 1 && hs.comments.includes('Great setup'), JSON.stringify(hs));
await A.eng.evaluate(()=>liveGo()); await B.eng.waitForTimeout(2500);   // End live
check('viewer sees it end', await B.eng.evaluate(()=>LIVE.ended), '');
say('page errors:', JSON.stringify(errs));
await b.close();
process.exit(ok && !errs.length ? 0 : 1);
