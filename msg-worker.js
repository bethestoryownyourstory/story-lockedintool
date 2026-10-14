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
//  - Groups (owner): public groups (Community - anyone can find and join them) and private groups (made in Messages -
//    only people a leader adds; never listed anywhere). Private group messages are locked for each member; public
//    group posts and chat are public by nature (anyone can join).
//  - Money transfer (owner: send money profile to profile, and to anyone - even people not on STORY): needs a payout
//    partner - Stitch Enterprise (STITCH_ENT_CLIENT_ID / STITCH_ENT_CLIENT_SECRET; Express can't pay other people): the payer approves the
//    payment in their OWN bank (Stitch's page) and it goes straight into the receiver's bank account. A STORY profile
//    receives into the bank account they added (kept only here, never shown to anyone - only its last 3 digits to its
//    owner); anyone else by their name + bank + account number. STORY never holds the money.
// Everything lives in ONE Durable Object (SQLite storage) so every phone's connection meets in the same place.

const JSONH = { 'content-type': 'application/json', 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type, authorization', 'access-control-allow-methods': 'GET, POST, OPTIONS' };
const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: JSONH });
const enc = new TextEncoder();
const b64u = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const sha = async s => b64u(await crypto.subtle.digest('SHA-256', enc.encode(s)));
const rand = n => b64u(crypto.getRandomValues(new Uint8Array(n)));
const PHONE = /^\+[1-9]\d{6,14}$/;
const USERNAME = /^(?!.*\.\.)(?!\.)(?!.*\.$)[a-z0-9_.]{3,30}$/;
// South African banks Stitch pays into (its BankBeneficiaryBankId names) - shown by their own names.
const PAY_BANKS = { absa: 'Absa', african_bank: 'African Bank', capitec: 'Capitec', discovery_bank: 'Discovery Bank', fnb: 'FNB', investec: 'Investec', nedbank: 'Nedbank', standard_bank: 'Standard Bank', tymebank: 'TymeBank' };

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
    this.sql.exec(`CREATE TABLE IF NOT EXISTS groups (id TEXT PRIMARY KEY, name TEXT, about TEXT, type TEXT, image TEXT, category TEXT, country TEXT, creator TEXT, calljoin TEXT, created INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS gmembers (gid TEXT, uid TEXT, role TEXT, joined INTEGER, PRIMARY KEY (gid, uid))`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS gmembers_uid ON gmembers(uid)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS gposts (id TEXT PRIMARY KEY, gid TEXT, uid TEXT, text TEXT, image TEXT, at INTEGER, likes INTEGER DEFAULT 0, shares INTEGER DEFAULT 0)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS gposts_gid ON gposts(gid, at)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS glikes (pid TEXT, uid TEXT, PRIMARY KEY (pid, uid))`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS gcomments (id TEXT PRIMARY KEY, pid TEXT, uid TEXT, text TEXT, at INTEGER)`);
    try { this.sql.exec(`ALTER TABLE groups ADD COLUMN shares INTEGER DEFAULT 0`); } catch (e) {}
    this.sql.exec(`CREATE TABLE IF NOT EXISTS paybank (uid TEXT PRIMARY KEY, name TEXT, bank TEXT, acct TEXT, updated INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS payments (id TEXT PRIMARY KEY, uid TEXT, to_uid TEXT, to_name TEXT, to_bank TEXT, to_mask TEXT, cents INTEGER, ref TEXT, stitch TEXT, status TEXT, created INTEGER, updated INTEGER)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS payments_uid ON payments(uid, created)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS payments_to ON payments(to_uid, created)`);
    try { this.sql.exec(`ALTER TABLE payments ADD COLUMN kind TEXT`); } catch (e) {}
    this.sql.exec(`CREATE TABLE IF NOT EXISTS subs (uid TEXT PRIMARY KEY, plan TEXT, until INTEGER, updated INTEGER)`);
    // STORY's own Stories feed (Community > Stories): one shared public group everyone posts their stories to.
    this.sql.exec(`INSERT OR IGNORE INTO groups (id, name, about, type, image, category, country, creator, calljoin, created) VALUES ('g-stories', 'Stories', 'Real people sharing their life and success stories, and the advice that got them there.', 'stories', '', 'Life', 'Global', 'story', 'open', 0)`);
  }

  one(q, ...a) { const r = this.sql.exec(q, ...a).toArray(); return r[0] || null; }
  phoneHash(p) { return sha('story-phone-v1|' + (this.env.PHONE_SALT || '') + '|' + p); }
  async userByToken(t) { return t ? this.one('SELECT * FROM users WHERE token = ?', await sha('tok|' + t)) : null; }
  privacy(u) { try { return Object.assign({ messages: 'everyone', calls: 'everyone', callPeople: [] }, JSON.parse(u.privacy || '{}')); } catch (e) { return { messages: 'everyone', calls: 'everyone', callPeople: [] }; } }
  canCall(from, to) { const p = this.privacy(to); return p.calls === 'everyone' || (p.calls === 'people' && (p.callPeople || []).includes(from.id)); }
  canMsg(from, to) { const p = this.privacy(to); return p.messages !== 'none' && p.messages !== 'nobody'; }
  pubView(u, me) { return { id: u.id, name: u.name || '', username: u.username || '', pub: u.pub, canCall: me ? this.canCall(me, u) : true, canMsg: me ? this.canMsg(me, u) : true, canPay: !!this.one('SELECT uid FROM paybank WHERE uid = ?', u.id) }; }

  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    try {
      if (path === '/v1/ws') return this.ws(req, url);
      if (path === '/v1/health') {
        // Payments: can the server really sign in to Stitch? (just a sign-in - no payment is made)
        let pay = this.expressReady() ? 'keys' : 'no-keys';
        if (url.searchParams.get('pay') === '1' && this.expressReady()) { try { await this.expressToken(); pay = 'ok'; } catch (e) { pay = String(e.message || e).slice(0, 160); } }
        return json({ ok: true, sms: !!(this.env.TWILIO_VERIFY_SID || this.env.TWILIO_FROM), pay, subs: this.subReady(), transfers: this.payReady() });
      }
      if (path === '/v1/pay/return') return new Response('<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><body style="margin:0;background:#000;color:#fff;font:600 18px system-ui;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center;padding:24px;box-sizing:border-box">Done - go back to Lockedintool to see your payment.</body>', { headers: { 'content-type': 'text/html; charset=utf-8' } });
      const body = req.method === 'POST' ? await req.json().catch(() => ({})) : {};
      if (path === '/v1/register') return this.register(body, req.headers.get('cf-connecting-ip') || '');
      const me = await this.userByToken((req.headers.get('authorization') || '').replace(/^Bearer\s+/i, ''));
      if (!me) return json({ error: 'signin' }, 401);
      if (path === '/v1/start') return this.start(body);
      if (path === '/v1/verify') return this.verify(me, body);
      if (path === '/v1/me') return this.saveMe(me, body);
      if (path === '/v1/find') return this.find(me, url.searchParams.get('q') || '');
      if (path.startsWith('/v1/groups/')) return this.groups(me, path.slice(11), body, url.searchParams);
      if (path.startsWith('/v1/pay/')) return this.pay(me, path.slice(8), body, url);
      if (path.startsWith('/v1/sub/')) return this.sub(me, path.slice(8), body, url);
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
      const r = await this.twilio('/2010-04-01/Accounts/' + env.TWILIO_ACCOUNT_SID + '/Messages.json', { From: env.TWILIO_FROM, To: phone, Body: 'Your Lockedintool code is ' + code + '. It works for 10 minutes. Never share it.' });
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
  // Finding people (owner): by their @username or their name - never phone numbers. Exact @username first, then
  // usernames / names that start with or contain what you typed (up to 20).
  async find(me, q) {
    const t = q.trim().slice(0, 40), un = t.replace(/^@/, '').toLowerCase();
    if (un.length < 2) return json({ people: [] });
    const like = '%' + un.replace(/[%_\\]/g, m => '\\' + m) + '%', start = un.replace(/[%_\\]/g, m => '\\' + m) + '%';
    const rows = this.sql.exec(
      `SELECT * FROM users WHERE id != ? AND (username LIKE ? ESCAPE '\\' OR (? = 0 AND lower(name) LIKE ? ESCAPE '\\'))
       ORDER BY (username = ?) DESC, (username LIKE ? ESCAPE '\\') DESC, (lower(name) LIKE ? ESCAPE '\\') DESC, name LIMIT 20`,
      me.id, like, t.startsWith('@') ? 1 : 0, like, un, start, start).toArray();
    return json({ people: rows.map(u => this.pubView(u, me)) });
  }

  // ---- Groups ----
  member(gid, uid) { return this.one('SELECT role FROM gmembers WHERE gid = ? AND uid = ?', gid, uid); }
  memberIds(gid) { return this.sql.exec('SELECT uid FROM gmembers WHERE gid = ?', gid).toArray().map(r => r.uid); }
  groupView(g, me, full) {
    const count = this.one('SELECT COUNT(*) AS n FROM gmembers WHERE gid = ?', g.id).n;
    const mine = me ? this.member(g.id, me.id) : null;
    const v = { id: g.id, name: g.name, about: g.about || '', type: g.type, image: g.image || '', category: g.category || 'Community', country: g.country || 'Global',
      creator: g.creator, callJoin: g.calljoin || 'open', created: g.created, count, member: !!mine, leader: !!mine && mine.role === 'leader',
      // Real numbers for Trending: members, times the group was shared, likes and comments on its posts.
      shares: g.shares || 0,
      likes: this.one('SELECT COALESCE(SUM(likes), 0) AS n FROM gposts WHERE gid = ?', g.id).n,
      comments: this.one('SELECT COUNT(*) AS n FROM gcomments c JOIN gposts p ON p.id = c.pid WHERE p.gid = ?', g.id).n };
    if (full) v.members = this.sql.exec('SELECT m.uid, m.role, u.name, u.username, u.pub FROM gmembers m JOIN users u ON u.id = m.uid WHERE m.gid = ? ORDER BY m.role = \'leader\' DESC, m.joined LIMIT 500', g.id).toArray()
      .map(r => ({ id: r.uid, role: r.role, name: r.name || '', username: r.username || '', pub: g.type === 'private' ? r.pub : undefined }));
    return v;
  }
  // Tell everyone in the group (and anyone just added / removed) what the group looks like now.
  groupTell(gid, extra = []) {
    const g = this.one('SELECT * FROM groups WHERE id = ?', gid);
    const ids = new Set(this.memberIds(gid).concat(extra));
    for (const uid of ids) {
      const u = this.one('SELECT * FROM users WHERE id = ?', uid);
      if (!u) continue;
      this.deliver(uid, { t: 'group', g: g ? this.groupView(g, u, true) : { id: gid, gone: true } });
    }
  }
  async groups(me, op, b, q) {
    const now = Date.now();
    const str = (v, n) => String(v || '').trim().slice(0, n);
    const get = id => this.one('SELECT * FROM groups WHERE id = ?', String(id || ''));
    if (op === 'create') {
      const name = str(b.name, 60), type = b.type === 'private' ? 'private' : 'public';
      if (!name) return json({ error: 'name' }, 400);
      const image = String(b.image || '');
      const id = 'grp' + rand(9);
      this.sql.exec('INSERT INTO groups (id, name, about, type, image, category, country, creator, calljoin, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        id, name, str(b.about, 500), type, image.length < 300000 ? image : '', str(b.category, 30) || 'Community', str(b.country, 40) || 'Global', me.id, 'open', now);
      this.sql.exec('INSERT INTO gmembers (gid, uid, role, joined) VALUES (?, ?, ?, ?)', id, me.id, 'leader', now);
      for (const uid of (Array.isArray(b.members) ? b.members : []).map(String).slice(0, 255))
        if (uid !== me.id && this.one('SELECT id FROM users WHERE id = ?', uid)) this.sql.exec('INSERT OR IGNORE INTO gmembers (gid, uid, role, joined) VALUES (?, ?, ?, ?)', id, uid, 'member', now);
      this.groupTell(id);
      return json(this.groupView(get(id), me, true));
    }
    if (op === 'mine') {
      const rows = this.sql.exec("SELECT g.* FROM groups g JOIN gmembers m ON m.gid = g.id WHERE m.uid = ? AND g.type != 'stories' ORDER BY g.created DESC", me.id).toArray();
      return json({ groups: rows.map(g => this.groupView(g, me, g.type === 'private')) });
    }
    if (op === 'public') {
      // Private groups are never listed (owner). Busiest first.
      const t = str(q.get('q'), 40).toLowerCase(), like = '%' + t.replace(/[%_\\]/g, m => '\\' + m) + '%';
      const rows = this.sql.exec(`SELECT g.*, (SELECT COUNT(*) FROM gmembers m WHERE m.gid = g.id) AS n FROM groups g WHERE g.type = 'public' AND (? = '' OR lower(g.name) LIKE ? ESCAPE '\') ORDER BY n DESC, g.created DESC LIMIT 200`, t, like).toArray();
      return json({ groups: rows.map(g => this.groupView(g, me, false)) });
    }
    const g = get(b.id || q.get('id'));
    if (!g) return json({ error: 'notfound' }, 404);
    // Stories: everyone reads it, and posting / liking / commenting there just needs you to be on STORY.
    if (g.type === 'stories' && !this.member(g.id, me.id) && ['post', 'like', 'comment', 'share'].includes(op)) this.sql.exec('INSERT OR IGNORE INTO gmembers (gid, uid, role, joined) VALUES (?, ?, ?, ?)', g.id, me.id, 'member', now);
    if (g.type === 'stories' && op === 'posts') return json({ posts: this.postsOf(g.id, me) });
    if (op === 'share-group') { if (g.type === 'public') this.sql.exec('UPDATE groups SET shares = COALESCE(shares, 0) + 1 WHERE id = ?', g.id); return json({ ok: true }); }
    const mine = this.member(g.id, me.id);
    if (g.type === 'private' && !mine) return json({ error: 'notfound' }, 404);   // private groups: members only
    if (op === 'get') return json(this.groupView(g, me, true));
    if (op === 'join') {
      if (g.type !== 'public') return json({ error: 'private' }, 403);   // public = free to join (owner)
      if (!mine) { this.sql.exec('INSERT INTO gmembers (gid, uid, role, joined) VALUES (?, ?, ?, ?)', g.id, me.id, 'member', now); this.groupTell(g.id); }
      return json(this.groupView(g, me, true));
    }
    if (!mine) return json({ error: 'member' }, 403);
    if (op === 'leave') {
      this.sql.exec('DELETE FROM gmembers WHERE gid = ? AND uid = ?', g.id, me.id);
      const left = this.memberIds(g.id);
      if (!left.length) { this.sql.exec('DELETE FROM groups WHERE id = ?', g.id); this.sql.exec('DELETE FROM gposts WHERE gid = ?', g.id); }
      else if (!this.one("SELECT uid FROM gmembers WHERE gid = ? AND role = 'leader'", g.id)) {
        const next = this.one('SELECT uid FROM gmembers WHERE gid = ? ORDER BY joined LIMIT 1', g.id);
        this.sql.exec("UPDATE gmembers SET role = 'leader' WHERE gid = ? AND uid = ?", g.id, next.uid);   // someone always leads
      }
      this.groupTell(g.id, [me.id]);
      return json({ ok: true });
    }
    if (op === 'add') {
      // Private: only its leaders add people. Public: any member can bring people in.
      if (g.type === 'private' && mine.role !== 'leader') return json({ error: 'leader' }, 403);
      const added = [];
      for (const uid of (Array.isArray(b.members) ? b.members : []).map(String).slice(0, 255))
        if (this.one('SELECT id FROM users WHERE id = ?', uid) && !this.member(g.id, uid)) { this.sql.exec('INSERT INTO gmembers (gid, uid, role, joined) VALUES (?, ?, ?, ?)', g.id, uid, 'member', now); added.push(uid); }
      this.groupTell(g.id);
      return json(this.groupView(g, me, true));
    }
    if (op === 'update') {
      if (mine.role !== 'leader') return json({ error: 'leader' }, 403);
      if (b.name !== undefined && str(b.name, 60)) this.sql.exec('UPDATE groups SET name = ? WHERE id = ?', str(b.name, 60), g.id);
      if (b.about !== undefined) this.sql.exec('UPDATE groups SET about = ? WHERE id = ?', str(b.about, 500), g.id);
      if (b.callJoin === 'open' || b.callJoin === 'ask') this.sql.exec('UPDATE groups SET calljoin = ? WHERE id = ?', b.callJoin, g.id);
      if (b.leader && this.member(g.id, String(b.leader))) this.sql.exec("UPDATE gmembers SET role = 'leader' WHERE gid = ? AND uid = ?", g.id, String(b.leader));
      this.groupTell(g.id);
      return json(this.groupView(get(g.id), me, true));
    }
    if (op === 'posts') return json({ posts: this.postsOf(g.id, me) });
    if (op === 'post') {
      if (g.type !== 'public' && g.type !== 'stories') return json({ error: 'public' }, 400);
      const text = str(b.text, 600), image = /^data:image\/(png|jpeg|webp);base64,/.test(String(b.image || '')) && String(b.image).length < 600000 ? String(b.image) : '';
      if (!text && !image) return json({ error: 'empty' }, 400);
      this.sql.exec('INSERT INTO gposts (id, gid, uid, text, image, at) VALUES (?, ?, ?, ?, ?, ?)', 'p' + rand(9), g.id, me.id, text, image, now);
      return json({ posts: this.postsOf(g.id, me) });
    }
    if (op === 'like') {
      const pid = String(b.post || ''); if (!this.one('SELECT id FROM gposts WHERE id = ? AND gid = ?', pid, g.id)) return json({ error: 'notfound' }, 404);
      if (this.one('SELECT pid FROM glikes WHERE pid = ? AND uid = ?', pid, me.id)) { this.sql.exec('DELETE FROM glikes WHERE pid = ? AND uid = ?', pid, me.id); this.sql.exec('UPDATE gposts SET likes = likes - 1 WHERE id = ?', pid); }
      else { this.sql.exec('INSERT INTO glikes (pid, uid) VALUES (?, ?)', pid, me.id); this.sql.exec('UPDATE gposts SET likes = likes + 1 WHERE id = ?', pid); }
      return json({ posts: this.postsOf(g.id, me) });
    }
    if (op === 'share') {
      const pid = String(b.post || ''); this.sql.exec('UPDATE gposts SET shares = COALESCE(shares, 0) + 1 WHERE id = ? AND gid = ?', pid, g.id);
      return json({ posts: this.postsOf(g.id, me) });
    }
    if (op === 'comment') {
      const pid = String(b.post || ''), text = str(b.text, 400);
      if (!text || !this.one('SELECT id FROM gposts WHERE id = ? AND gid = ?', pid, g.id)) return json({ error: 'notfound' }, 404);
      this.sql.exec('INSERT INTO gcomments (id, pid, uid, text, at) VALUES (?, ?, ?, ?, ?)', 'c' + rand(9), pid, me.id, text, now);
      return json({ posts: this.postsOf(g.id, me) });
    }
    return json({ error: 'notfound' }, 404);
  }
  postsOf(gid, me) {
    const who = uid => { const u = this.one('SELECT name, username FROM users WHERE id = ?', uid); return u ? (u.name || (u.username ? '@' + u.username : 'Someone')) : 'Someone'; };
    return this.sql.exec('SELECT * FROM gposts WHERE gid = ? ORDER BY at DESC LIMIT 100', gid).toArray().map(p => ({
      id: p.id, author: p.uid === me.id ? 'You' : who(p.uid), mine: p.uid === me.id, text: p.text, image: p.image || '', at: p.at, likes: p.likes, shares: p.shares || 0,
      liked: !!this.one('SELECT pid FROM glikes WHERE pid = ? AND uid = ?', p.id, me.id),
      comments: this.sql.exec('SELECT * FROM gcomments WHERE pid = ? ORDER BY at LIMIT 200', p.id).toArray().map(c => ({ author: c.uid === me.id ? 'You' : who(c.uid), text: c.text, at: c.at }))
    }));
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
  // ---- LOCKED IN TOOL subscription (owner: Basic $1 / Premium $5 a month; no plan = STORY doesn't open). Paid through
  //      Stitch EXPRESS (the owner's Stitch account - STITCH_CLIENT_ID / STITCH_CLIENT_SECRET): a payment link (card,
  //      Capitec, Apple / Google Pay...) into LOCKED IN TOOL's Express balance, withdrawn to the owner's verified bank.
  //      Each payment = 30 days. Prices in rand (Express is rand only): SUB_BASIC_ZAR / SUB_PREMIUM_ZAR. ----
  subPlans() {
    return { basic: { usd: 1, zar: Number(this.env.SUB_BASIC_ZAR || 19) }, premium: { usd: 5, zar: Number(this.env.SUB_PREMIUM_ZAR || 95) } };
  }
  subReady() { return this.expressReady(); }
  subView(me) {
    const r = this.one('SELECT * FROM subs WHERE uid = ?', me.id), now = Date.now();
    const active = !!(r && r.until > now);
    return { connected: this.subReady(), plan: active ? r.plan : null, until: r ? r.until : 0, active, plans: this.subPlans() };
  }
  // Stitch Express REST API (express.stitch.money/api/v1, its own OpenAPI spec): a 15-minute token from clientId +
  // clientSecret, then payment links. Amounts are in cents.
  expressReady() { return !!(this.env.STITCH_CLIENT_ID && this.env.STITCH_CLIENT_SECRET); }
  expressBase() { return this.env.STITCH_EXPRESS_BASE || 'https://express.stitch.money'; }   // (local tests: a stand-in)
  async expressToken() {
    if (this.exTok && this.exTok.exp > Date.now() + 60000) return this.exTok.v;
    const r = await fetch(this.expressBase() + '/api/v1/token', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ clientId: this.env.STITCH_CLIENT_ID, clientSecret: this.env.STITCH_CLIENT_SECRET, scope: 'client_paymentrequest' }) });
    const j = await r.json().catch(() => ({}));
    const tok = j && j.data && j.data.accessToken;
    if (!r.ok || !tok) throw new Error('stitch-signin: ' + (r.status === 400 ? 'invalid credentials (the secret changes every time it is viewed in the dashboard)' : ((j.generalErrors || [])[0] || r.status)));
    this.exTok = { v: tok, exp: Date.now() + 14 * 60000 };
    return tok;
  }
  async express(method, path, body) {
    const go = async () => fetch(this.expressBase() + path, { method, headers: { 'content-type': 'application/json', authorization: 'Bearer ' + await this.expressToken() }, body: body ? JSON.stringify(body) : undefined });
    let r = await go();
    if (r.status === 401) { this.exTok = null; r = await go(); }   // token ran out - once more with a fresh one
    const j = await r.json().catch(() => ({}));
    if (!r.ok || j.success === false) throw new Error('stitch: ' + (((j.generalErrors || [])[0]) || JSON.stringify(j.fieldErrors || {}) || r.status));
    return j.data || {};
  }
  // Where your bank / card page sends you back to (registered with Express once; it allows up to 5).
  async expressBack(url) {
    const back = this.env.STITCH_REDIRECT || (url.origin + '/v1/pay/return');
    if (!this.exBackDone) { try { await this.express('POST', '/api/v1/redirect-urls', { redirectUrl: back }); } catch (e) {} this.exBackDone = true; }
    return back;
  }
  // A plan payment's state at Stitch; paid = 30 days of that plan (same plan adds on, a different plan starts now).
  async subCheck(me, p) {
    if (p.status !== 'pending' || !this.expressReady()) return p.status;
    const now = Date.now();
    const d = await this.express('GET', '/api/v1/payment-links/' + encodeURIComponent(p.stitch));
    const ls = (d.payment && d.payment.status) || 'PENDING';
    const st = ls === 'PAID' ? 'paid' : ls === 'CANCELLED' ? 'cancelled' : ls === 'EXPIRED' || ls === 'FAILED' ? 'expired' : 'pending';
    if (st !== 'pending') {
      this.sql.exec('UPDATE payments SET status = ?, updated = ? WHERE id = ?', st, now, p.id);
      p.status = st;
      if (st === 'paid') {
        const plan = p.kind.slice(4), cur = this.one('SELECT * FROM subs WHERE uid = ?', me.id);
        const from = cur && cur.plan === plan && cur.until > now ? cur.until : now;
        this.sql.exec('INSERT OR REPLACE INTO subs (uid, plan, until, updated) VALUES (?, ?, ?, ?)', me.id, plan, from + 30 * 86400000, now);
      }
    }
    return p.status;
  }
  // Cancel a plan payment that isn't paid yet (Stitch: POST payment-links/{id}/cancel - the link can't be paid any more).
  // If Stitch says it's already paid / going through, the real state is read instead (paid still counts).
  async subCancel(me, p) {
    if (p.status !== 'pending') return p.status;
    if (this.expressReady()) {
      try { await this.express('POST', '/api/v1/payment-links/' + encodeURIComponent(p.stitch) + '/cancel'); }
      catch (e) { try { await this.subCheck(me, p); } catch (e2) {} return p.status; }
    }
    this.sql.exec('UPDATE payments SET status = ?, updated = ? WHERE id = ?', 'cancelled', Date.now(), p.id);
    p.status = 'cancelled';
    return p.status;
  }
  async sub(me, op, b, url) {
    const now = Date.now();
    if (op === 'info') return json(this.subView(me));
    if (op === 'buy') {
      const plans = this.subPlans(), plan = String(b.plan || '');
      if (!plans[plan]) return json({ error: 'plan' }, 400);
      if (!this.subReady()) return json({ error: 'not-connected' }, 503);
      // Changed your mind before paying (owner): any plan you started but didn't pay is cancelled at Stitch first,
      // so only the newest one can be paid. One that was already paid still counts (subCheck credits it).
      for (const old of this.sql.exec("SELECT * FROM payments WHERE uid = ? AND kind LIKE 'sub:%' AND status = 'pending'", me.id).toArray()) await this.subCancel(me, old);
      const cents = Math.round(plans[plan].zar * 100), id = 'p' + rand(9);
      const who = String(me.name || (me.username ? '@' + me.username : '') || 'LOCKED IN member').replace(/[^\p{L}\p{N} .@'_-]/gu, '').trim();
      const d = await this.express('POST', '/api/v1/payment-links', {
        amount: cents, payerName: (who.length >= 3 ? who : 'LOCKED IN member').slice(0, 40),
        merchantReference: ('LIT-' + plan + '-' + me.id + '-' + id).replace(/[^A-Za-z0-9 -]/g, '').slice(0, 50) });
      const link = d.payment || {};
      if (!link.id || !link.link) throw new Error('stitch: no payment link');
      this.sql.exec('INSERT INTO payments (id, uid, to_uid, to_name, to_bank, to_mask, cents, ref, stitch, status, created, updated, kind) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        id, me.id, 'LOCKED IN TOOL ' + plan, '', '', cents, 'LIT ' + plan.toUpperCase(), link.id, 'pending', now, now, 'sub:' + plan);
      const back = await this.expressBack(url);
      return json({ id, url: link.link + (link.link.includes('?') ? '&' : '?') + 'redirect_url=' + encodeURIComponent(back) });
    }
    if (op === 'status' || op === 'cancel') {
      const p = this.one("SELECT * FROM payments WHERE id = ? AND uid = ? AND kind LIKE 'sub:%'", (op === 'cancel' ? b.id : url.searchParams.get('id')) || '', me.id);
      if (!p) return json({ error: 'notfound' }, 404);
      if (op === 'cancel') await this.subCancel(me, p); else await this.subCheck(me, p);
      return json(Object.assign({ status: p.status }, this.subView(me)));
    }
    return json({ error: 'notfound' }, 404);
  }

  // ---- Money transfer (Stitch) ----
  // Sending money to OTHER people needs a payout partner: Stitch Express only takes payments into LOCKED IN TOOL and
  // withdraws to the owner's own bank. This uses Stitch's Enterprise API (its own keys STITCH_ENT_CLIENT_ID /
  // STITCH_ENT_CLIENT_SECRET) - until those exist, Send says it connects once a payout partner is set up.
  // (STITCH_BASE: local tests only - a stand-in Stitch, like TWILIO_BASE)
  payReady() { return !!(this.env.STITCH_ENT_CLIENT_ID && this.env.STITCH_ENT_CLIENT_SECRET); }
  async stitchToken() {
    if (this.stitchTok && this.stitchTok.exp > Date.now() + 60000) return this.stitchTok.v;
    const r = await fetch((this.env.STITCH_BASE || 'https://secure.stitch.money') + '/connect/token', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'client_credentials', client_id: this.env.STITCH_ENT_CLIENT_ID, client_secret: this.env.STITCH_ENT_CLIENT_SECRET,
        scope: 'client_paymentrequest', audience: 'https://secure.stitch.money/connect/token' }) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.access_token) throw new Error('stitch-signin: ' + (j.error_description || j.error || r.status));
    this.stitchTok = { v: j.access_token, exp: Date.now() + (j.expires_in || 3600) * 1000 };
    return j.access_token;
  }
  async stitch(query, variables) {
    const r = await fetch((this.env.STITCH_BASE || 'https://api.stitch.money') + '/graphql', { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + await this.stitchToken() }, body: JSON.stringify({ query, variables }) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || j.errors) throw new Error('stitch: ' + ((j.errors && j.errors[0] && j.errors[0].message) || r.status));
    return j.data;
  }
  bankOk(b) {
    const bank = String(b.bank || ''), acct = String(b.acct || '').replace(/\s+/g, ''), name = String(b.name || '').trim().slice(0, 60);
    if (!PAY_BANKS[bank] || !/^\d{6,16}$/.test(acct) || name.length < 2) return null;
    return { bank, acct, name };
  }
  payView(p, me) {
    const out = p.uid === me.id;
    const other = out ? (p.to_uid ? this.one('SELECT name, username FROM users WHERE id = ?', p.to_uid) : null) : this.one('SELECT name, username FROM users WHERE id = ?', p.uid);
    return { id: p.id, dir: out ? 'out' : 'in', amount: p.cents / 100, currency: 'ZAR', ref: p.ref || '', status: p.status, at: p.created,
      who: out ? (other ? (other.name || '@' + other.username) : p.to_name) : (other ? (other.name || '@' + (other.username || '')) : 'Someone'),
      bank: out && !p.to_uid ? (PAY_BANKS[p.to_bank] || '') + (p.to_mask ? ' ••' + p.to_mask : '') : '' };   // a STORY profile's bank is never shown
  }
  async pay(me, op, b, url) {
    const now = Date.now();
    if (op === 'info') {
      const mine = this.one('SELECT * FROM paybank WHERE uid = ?', me.id);
      return json({ connected: this.payReady(), banks: PAY_BANKS, bank: mine ? { name: mine.name, bank: mine.bank, mask: mine.acct.slice(-3) } : null });
    }
    if (op === 'bank') {
      if (b.remove) { this.sql.exec('DELETE FROM paybank WHERE uid = ?', me.id); return json({ ok: true, bank: null }); }
      const k = this.bankOk(b); if (!k) return json({ error: 'bank-details' }, 400);
      this.sql.exec('INSERT OR REPLACE INTO paybank (uid, name, bank, acct, updated) VALUES (?, ?, ?, ?, ?)', me.id, k.name, k.bank, k.acct, now);
      return json({ ok: true, bank: { name: k.name, bank: k.bank, mask: k.acct.slice(-3) } });
    }
    if (op === 'history') {
      const rows = this.sql.exec("SELECT * FROM payments WHERE (kind IS NULL OR kind NOT LIKE 'sub:%') AND (uid = ? OR (to_uid = ? AND status = ?)) ORDER BY created DESC LIMIT 100", me.id, me.id, 'paid').toArray();
      return json({ payments: rows.map(p => this.payView(p, me)) });
    }
    if (op === 'send') {
      const cents = Math.round(Number(b.amount) * 100);
      if (!(cents >= 100 && cents <= 5000000)) return json({ error: 'amount' }, 400);   // R1 - R50 000 a payment
      let to = null, toUid = null;
      if (b.to) {
        const u = this.one('SELECT * FROM users WHERE id = ?', String(b.to)); if (!u) return json({ error: 'notfound' }, 404);
        if (u.id === me.id) return json({ error: 'self' }, 400);
        const pb = this.one('SELECT * FROM paybank WHERE uid = ?', u.id); if (!pb) return json({ error: 'no-bank' }, 409);
        to = { name: pb.name, bank: pb.bank, acct: pb.acct }; toUid = u.id;
      } else { to = this.bankOk(b.bank || {}); if (!to) return json({ error: 'bank-details' }, 400); }
      if (!this.payReady()) return json({ error: 'not-connected' }, 503);
      const ref = String(b.ref || '').replace(/[^A-Za-z0-9 .\-]/g, '').trim().slice(0, 20) || 'Lockedintool';
      const id = 'p' + rand(9);
      const d = await this.stitch(`mutation Pay($amount: MoneyInput!, $payerReference: String!, $beneficiaryReference: String!, $externalReference: String, $name: String!, $bankId: BankBeneficiaryBankId!, $acct: String!) {
        clientPaymentInitiationRequestCreate(input: { amount: $amount, payerReference: $payerReference, beneficiaryReference: $beneficiaryReference, externalReference: $externalReference,
          beneficiary: { bankAccount: { name: $name, bankId: $bankId, accountNumber: $acct } } }) { paymentInitiationRequest { id url } } }`,
        { amount: { quantity: (cents / 100).toFixed(2), currency: 'ZAR' }, payerReference: ref.slice(0, 12), beneficiaryReference: ref, externalReference: id, name: to.name, bankId: to.bank, acct: to.acct });
      const pr = d.clientPaymentInitiationRequestCreate.paymentInitiationRequest;
      this.sql.exec('INSERT INTO payments (id, uid, to_uid, to_name, to_bank, to_mask, cents, ref, stitch, status, created, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        id, me.id, toUid, toUid ? null : to.name, to.bank, toUid ? '' : to.acct.slice(-3), cents, ref, pr.id, 'pending', now, now);
      const back = this.env.STITCH_REDIRECT || (url.origin + '/v1/pay/return');
      return json({ id, url: pr.url + (pr.url.includes('?') ? '&' : '?') + 'redirect_uri=' + encodeURIComponent(back) });
    }
    if (op === 'status') {
      const p = this.one("SELECT * FROM payments WHERE id = ? AND uid = ? AND (kind IS NULL OR kind NOT LIKE 'sub:%')", url.searchParams.get('id') || '', me.id);
      if (!p) return json({ error: 'notfound' }, 404);
      if (p.status === 'pending' && this.payReady()) {
        const d = await this.stitch(`query St($id: ID!) { node(id: $id) { ... on PaymentInitiationRequest { status { __typename } } } }`, { id: p.stitch });
        const tn = d && d.node && d.node.status && d.node.status.__typename || '';
        const st = /Completed/.test(tn) ? 'paid' : /Cancelled/.test(tn) ? 'cancelled' : /Expired/.test(tn) ? 'expired' : 'pending';
        if (st !== 'pending') {
          this.sql.exec('UPDATE payments SET status = ?, updated = ? WHERE id = ?', st, now, p.id);
          p.status = st;
          if (st === 'paid' && p.to_uid) this.deliver(p.to_uid, { t: 'paid', id: p.id, amount: p.cents / 100, currency: 'ZAR', ref: p.ref || '', from: me.id, name: me.name || '', username: me.username || '', at: now });
        }
      }
      return json(this.payView(p, me));
    }
    return json({ error: 'notfound' }, 404);
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
        if (row) { this.sql.exec('DELETE FROM queue WHERE seq = ?', s); const b = JSON.parse(row.body); if (b.t === 'msg') this.deliver(b.from, { t: 'status', chat: b.group || uid, id: b.id, status: 'delivered', at: now }); }
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
        if (m.group) {
          const g = this.one('SELECT id FROM groups WHERE id = ?', String(m.group));
          if (g && this.member(g.id, uid)) for (const id of this.memberIds(g.id)) if (id !== uid) this.sendTo(id, { t: 'typing', chat: g.id, who: me.name || (me.username ? '@' + me.username : ''), at: now });
          return;
        }
        if (to) this.sendTo(to.id, { t: 'typing', chat: uid, at: now });
        return;
      case 'gmsg': {
        // A group message: private groups send a locked box for each member; public groups send the text itself.
        const g = this.one('SELECT * FROM groups WHERE id = ?', String(m.group || ''));
        if (!g || !this.member(g.id, uid)) return ws.send(JSON.stringify({ t: 'fail', id: m.id, error: 'member' }));
        const id = String(m.id).slice(0, 40), boxes = m.boxes || {};
        for (const mid of this.memberIds(g.id)) {
          if (mid === uid) continue;
          if (g.type === 'private') { const bx = boxes[mid]; if (!bx || String(bx.box || '').length > 64000) continue; this.deliver(mid, { t: 'msg', group: g.id, from: uid, id, box: String(bx.box), iv: String(bx.iv || ''), at: now, name: me.name || '', username: me.username || '' }); }
          else this.deliver(mid, { t: 'msg', group: g.id, from: uid, id, text: String(m.text || '').slice(0, 4000), at: now, name: me.name || '', username: me.username || '' });
        }
        ws.send(JSON.stringify({ t: 'sent', id: m.id, at: now }));
        return; }
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
