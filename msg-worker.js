// STORY's message + call server (Cloudflare Worker "story-msg", deploy-msg.yml, settings in msg-wrangler.toml).
// What it does - and what it never does:
//  - Your STORY account is made by the phone itself (owner: no phone number in Messages): the phone sends only its
//    PUBLIC key and gets back its id + a sign-in key. People find each other by @username only.
//  - Phone numbers are only for when STORY needs to verify something (owner): a text code (Twilio Verify when
//    TWILIO_VERIFY_SID is set, otherwise a code STORY makes and texts from TWILIO_FROM); kept only as a salted hash.
//  - Each phone makes its own key pair; only the PUBLIC key comes here. Messages arrive already locked (AES-GCM with a
//    key only the two phones can work out) - this server carries locked boxes and can't read any of them.
//  - Live delivery over a WebSocket (Delivered / Seen / Read, typing), and a queue for phones that are offline.
//  - Calls: it only passes the call set-up between the two phones (WebRTC offer / answer / network paths) - the
//    call itself goes phone to phone, encrypted. "Who can call you" / "who can message you" are checked here.
// Everything lives in ONE Durable Object (SQLite storage) so every phone's connection meets in the same place.

const JSONH = { 'content-type': 'application/json', 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type, authorization', 'access-control-allow-methods': 'GET, POST, OPTIONS' };
const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: JSONH });
const enc = new TextEncoder();
const b64u = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const sha = async s => b64u(await crypto.subtle.digest('SHA-256', enc.encode(s)));
const rand = n => b64u(crypto.getRandomValues(new Uint8Array(n)));
const PHONE = /^\+[1-9]\d{6,14}$/;
const USERNAME = /^(?!.*\.\.)(?!\.)(?!.*\.$)[a-z0-9_.]{3,30}$/;

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: JSONH });
    const hub = env.HUB.get(env.HUB.idFromName('story'));
    return hub.fetch(req);
  }
};

export class Hub {
  constructor(ctx, env) {
    this.ctx = ctx; this.env = env; this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, phone TEXT UNIQUE, token TEXT, pub TEXT, name TEXT, username TEXT UNIQUE, privacy TEXT, created INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS codes (phone TEXT PRIMARY KEY, code TEXT, exp INTEGER, tries INTEGER, sent INTEGER, hour INTEGER, nhour INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS joins (ip TEXT PRIMARY KEY, hour INTEGER, n INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS queue (seq INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT, body TEXT, at INTEGER)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS queue_uid ON queue(uid)`);
  }

  one(q, ...a) { const r = this.sql.exec(q, ...a).toArray(); return r[0] || null; }
  phoneHash(p) { return sha('story-phone-v1|' + (this.env.PHONE_SALT || '') + '|' + p); }
  async userByToken(t) { return t ? this.one('SELECT * FROM users WHERE token = ?', await sha('tok|' + t)) : null; }
  privacy(u) { try { return Object.assign({ messages: 'everyone', calls: 'everyone', callPeople: [] }, JSON.parse(u.privacy || '{}')); } catch (e) { return { messages: 'everyone', calls: 'everyone', callPeople: [] }; } }
  canCall(from, to) { const p = this.privacy(to); return p.calls === 'everyone' || (p.calls === 'people' && (p.callPeople || []).includes(from.id)); }
  canMsg(from, to) { const p = this.privacy(to); return p.messages !== 'none' && p.messages !== 'nobody'; }
  pubView(u, me) { return { id: u.id, name: u.name || '', username: u.username || '', pub: u.pub, canCall: me ? this.canCall(me, u) : true, canMsg: me ? this.canMsg(me, u) : true }; }

  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    try {
      if (path === '/v1/ws') return this.ws(req, url);
      if (path === '/v1/health') return json({ ok: true, sms: !!(this.env.TWILIO_VERIFY_SID || this.env.TWILIO_FROM) });
      const body = req.method === 'POST' ? await req.json().catch(() => ({})) : {};
      if (path === '/v1/register') return this.register(body, req.headers.get('cf-connecting-ip') || '');
      const me = await this.userByToken((req.headers.get('authorization') || '').replace(/^Bearer\s+/i, ''));
      if (!me) return json({ error: 'signin' }, 401);
      if (path === '/v1/start') return this.start(body);
      if (path === '/v1/verify') return this.verify(me, body);
      if (path === '/v1/me') return this.saveMe(me, body);
      if (path === '/v1/find') return this.find(me, url.searchParams.get('q') || '');
      if (path === '/v1/user') { const u = this.one('SELECT * FROM users WHERE id = ?', url.searchParams.get('id') || ''); return u ? json(this.pubView(u, me)) : json({ error: 'notfound' }, 404); }
      return json({ error: 'notfound' }, 404);
    } catch (e) { return json({ error: 'server', detail: String(e && e.message || e).slice(0, 200) }, 500); }
  }

  // ---- Your account: made by the phone (only its public key comes here) ----
  async register(b, ip) {
    const pub = String(b.pub || '');
    if (!pub || pub.length > 400) return json({ error: 'key' }, 400);
    const hour = Math.floor(Date.now() / 3600000), ipk = await sha('ip|' + ip);
    const j = this.one('SELECT * FROM joins WHERE ip = ?', ipk), n = j && j.hour === hour ? j.n + 1 : 1;
    if (n > 20) return json({ error: 'too-many' }, 429);
    this.sql.exec('INSERT OR REPLACE INTO joins (ip, hour, n) VALUES (?, ?, ?)', ipk, hour, n);
    const id = 'u' + rand(9), token = rand(32);
    this.sql.exec('INSERT INTO users (id, phone, token, pub, name, username, privacy, created) VALUES (?, NULL, ?, ?, NULL, NULL, ?, ?)', id, await sha('tok|' + token), pub, '{}', Date.now());
    return json({ id, token });
  }

  // ---- Verifying a phone number (only when STORY needs to verify something): text code ----
  async twilio(path, form, host = 'https://api.twilio.com') {
    if (this.env.TWILIO_BASE) host = this.env.TWILIO_BASE;   // local tests only: a stand-in Twilio
    const r = await fetch(host + path, { method: 'POST', headers: { authorization: 'Basic ' + btoa(this.env.TWILIO_ACCOUNT_SID + ':' + this.env.TWILIO_AUTH_TOKEN), 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(form) });
    const j = await r.json().catch(() => ({}));
    return { ok: r.ok, j };
  }
  async start(b) {
    const phone = String(b.phone || '').replace(/[^\d+]/g, '');
    if (!PHONE.test(phone)) return json({ error: 'phone' }, 400);
    const env = this.env, h = await this.phoneHash(phone), now = Date.now();
    if (!env.TWILIO_ACCOUNT_SID || !env.TWILIO_AUTH_TOKEN || !(env.TWILIO_VERIFY_SID || env.TWILIO_FROM)) return json({ error: 'sms-not-ready' }, 503);
    const c = this.one('SELECT * FROM codes WHERE phone = ?', h);
    if (c && now - c.sent < 30000) return json({ error: 'wait', wait: Math.ceil((30000 - (now - c.sent)) / 1000) }, 429);
    const hour = Math.floor(now / 3600000), nhour = c && c.hour === hour ? c.nhour + 1 : 1;
    if (nhour > 5) return json({ error: 'too-many' }, 429);
    let code = '';
    if (env.TWILIO_VERIFY_SID) {
      const r = await this.twilio('/v2/Services/' + env.TWILIO_VERIFY_SID + '/Verifications', { To: phone, Channel: 'sms' }, 'https://verify.twilio.com');
      if (!r.ok) return json({ error: 'sms-failed', detail: r.j.message || '' }, 502);
    } else {
      code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, '0');
      const r = await this.twilio('/2010-04-01/Accounts/' + env.TWILIO_ACCOUNT_SID + '/Messages.json', { From: env.TWILIO_FROM, To: phone, Body: 'Your STORY code is ' + code + '. It works for 10 minutes. Never share it.' });
      if (!r.ok) return json({ error: 'sms-failed', detail: r.j.message || '' }, 502);
    }
    this.sql.exec('INSERT OR REPLACE INTO codes (phone, code, exp, tries, sent, hour, nhour) VALUES (?, ?, ?, 0, ?, ?, ?)', h, code ? await sha('code|' + h + '|' + code) : '', now + 600000, now, hour, nhour);
    return json({ ok: true });
  }
  // The code is right -> that number is marked as verified on YOUR account (never used to find you).
  async verify(me, b) {
    const phone = String(b.phone || '').replace(/[^\d+]/g, ''), code = String(b.code || '').replace(/\D/g, '');
    if (!PHONE.test(phone) || code.length !== 6) return json({ error: 'code' }, 400);
    const h = await this.phoneHash(phone), c = this.one('SELECT * FROM codes WHERE phone = ?', h), now = Date.now();
    if (!c || c.exp < now) return json({ error: 'expired' }, 400);
    if (c.tries >= 5) return json({ error: 'too-many' }, 429);
    this.sql.exec('UPDATE codes SET tries = tries + 1 WHERE phone = ?', h);
    let good = false;
    if (this.env.TWILIO_VERIFY_SID && !c.code) {
      const r = await this.twilio('/v2/Services/' + this.env.TWILIO_VERIFY_SID + '/VerificationCheck', { To: phone, Code: code }, 'https://verify.twilio.com');
      good = r.ok && r.j.status === 'approved';
    } else good = c.code === await sha('code|' + h + '|' + code);
    if (!good) return json({ error: 'wrong', left: Math.max(0, 4 - c.tries) }, 400);
    this.sql.exec('DELETE FROM codes WHERE phone = ?', h);
    const other = this.one('SELECT id FROM users WHERE phone = ? AND id != ?', h, me.id);
    if (other) return json({ error: 'phone-used' }, 409);
    this.sql.exec('UPDATE users SET phone = ? WHERE id = ?', h, me.id);
    return json({ verified: true });
  }

  // ---- Your profile (name, @username, privacy) ----
  saveMe(me, b) {
    const sets = [], vals = [];
    if (b.name !== undefined) { sets.push('name = ?'); vals.push(String(b.name || '').slice(0, 60) || null); }
    if (b.username !== undefined) {
      const un = String(b.username || '').toLowerCase();
      if (un && !USERNAME.test(un)) return json({ error: 'username' }, 400);
      if (un) { const t = this.one('SELECT id FROM users WHERE username = ? AND id != ?', un, me.id); if (t) return json({ error: 'username-taken' }, 409); }
      sets.push('username = ?'); vals.push(un || null);
    }
    if (b.privacy !== undefined) {
      const p = b.privacy || {};
      const clean = { messages: ['everyone', 'requests', 'nobody'].includes(p.messages) ? p.messages : 'everyone', calls: ['everyone', 'people', 'nobody'].includes(p.calls) ? p.calls : p.calls === 'none' ? 'nobody' : 'everyone', callPeople: (Array.isArray(p.callPeople) ? p.callPeople : []).map(String).slice(0, 500) };
      sets.push('privacy = ?'); vals.push(JSON.stringify(clean));
    }
    if (sets.length) this.sql.exec('UPDATE users SET ' + sets.join(', ') + ' WHERE id = ?', ...vals, me.id);
    const u = this.one('SELECT * FROM users WHERE id = ?', me.id);
    return json({ id: u.id, name: u.name || '', username: u.username || '' });
  }
  async find(me, q) {
    // @username only - never phone numbers (owner).
    const un = q.trim().replace(/^@/, '').toLowerCase();
    const u = USERNAME.test(un) ? this.one('SELECT * FROM users WHERE username = ?', un) : null;
    return u && u.id !== me.id ? json(this.pubView(u, me)) : json({ error: 'notfound' }, 404);
  }

  // ---- Live connection ----
  async ws(req, url) {
    if (req.headers.get('upgrade') !== 'websocket') return json({ error: 'websocket' }, 426);
    const me = await this.userByToken(url.searchParams.get('token') || '');
    if (!me) return json({ error: 'signin' }, 401);
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], [me.id]);
    pair[1].serializeAttachment({ uid: me.id });
    // Everything that waited while this phone was offline.
    const rows = this.sql.exec('SELECT seq, body FROM queue WHERE uid = ? ORDER BY seq LIMIT 500', me.id).toArray();
    for (const r of rows) pair[1].send(JSON.stringify(Object.assign(JSON.parse(r.body), { seq: r.seq })));
    pair[1].send(JSON.stringify({ t: 'hello', id: me.id }));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }
  sendTo(uid, o) { let n = 0; for (const ws of this.ctx.getWebSockets(uid)) { try { ws.send(JSON.stringify(o)); n++; } catch (e) {} } return n; }
  // Kept until the phone says it has it (ack), so nothing is lost if the connection drops.
  deliver(uid, o) {
    this.sql.exec('INSERT INTO queue (uid, body, at) VALUES (?, ?, ?)', uid, JSON.stringify(o), Date.now());
    const seq = this.one('SELECT last_insert_rowid() AS s').s;
    this.sendTo(uid, Object.assign({}, o, { seq }));
    // Old undelivered items expire after 30 days.
    this.sql.exec('DELETE FROM queue WHERE at < ?', Date.now() - 30 * 86400000);
  }
  async webSocketMessage(ws, data) {
    const a = ws.deserializeAttachment() || {}; const uid = a.uid; if (!uid) return;
    let m; try { m = JSON.parse(typeof data === 'string' ? data : new TextDecoder().decode(data)); } catch (e) { return; }
    const me = this.one('SELECT * FROM users WHERE id = ?', uid); if (!me) return;
    const to = m.to ? this.one('SELECT * FROM users WHERE id = ?', String(m.to)) : null;
    const now = Date.now();
    switch (m.t) {
      case 'ping': ws.send(JSON.stringify({ t: 'pong' })); return;
      case 'ack': { const s = +m.seq; const row = this.one('SELECT body FROM queue WHERE seq = ? AND uid = ?', s, uid);
        if (row) { this.sql.exec('DELETE FROM queue WHERE seq = ?', s); const b = JSON.parse(row.body); if (b.t === 'msg') this.deliver(b.from, { t: 'status', chat: uid, id: b.id, status: 'delivered', at: now }); }
        return; }
      case 'msg': {
        if (!to) return ws.send(JSON.stringify({ t: 'fail', id: m.id, error: 'notfound' }));
        if (!this.canMsg(me, to)) return ws.send(JSON.stringify({ t: 'fail', id: m.id, error: 'blocked' }));
        if (String(m.box || '').length > 64000) return ws.send(JSON.stringify({ t: 'fail', id: m.id, error: 'big' }));
        this.deliver(to.id, { t: 'msg', from: uid, id: String(m.id).slice(0, 40), box: String(m.box), iv: String(m.iv || ''), at: now, name: me.name || '', username: me.username || '' });
        ws.send(JSON.stringify({ t: 'sent', id: m.id, at: now }));
        return; }
      case 'seen': case 'read':
        if (to) this.deliver(to.id, { t: 'status', chat: uid, status: m.t, at: now });
        return;
      case 'typing':
        if (to) this.sendTo(to.id, { t: 'typing', chat: uid, at: now });
        return;
      case 'call': {
        // Call set-up only: offer / answer / ice / ring / end / decline / busy.
        if (!to) return ws.send(JSON.stringify({ t: 'call', kind: 'unavailable', callId: m.callId, from: m.to }));
        if (m.kind === 'offer') {
          if (!this.canCall(me, to)) return ws.send(JSON.stringify({ t: 'call', kind: 'blocked', callId: m.callId, from: to.id }));
          if (!this.ctx.getWebSockets(to.id).length) return ws.send(JSON.stringify({ t: 'call', kind: 'unavailable', callId: m.callId, from: to.id }));
        }
        this.sendTo(to.id, { t: 'call', kind: String(m.kind), callId: String(m.callId || ''), video: !!m.video, sdp: m.sdp, cand: m.cand, from: uid, name: me.name || '', username: me.username || '', at: now });
        return; }
    }
  }
  webSocketClose(ws) { try { ws.close(); } catch (e) {} }
  webSocketError(ws) { try { ws.close(); } catch (e) {} }
}
