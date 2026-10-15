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
// The official Lockedintool profile (owner: "only us can use our name and username"). Its account is the official
// one; later team accounts are added here. Nobody else may have "lockedintool" in their name or @username.
const REPORT_REASONS = { spam: 'spam', scam: 'scam or fraud', abuse: 'bullying or harassment', hate: 'hate speech', violence: 'violence or threats', nudity: 'nudity or sexual content', other: 'breaking the rules' };
const OFFICIAL_USERNAMES = ['officiallockedintoolprofile'];
const BRAND = 'lockedintool';
const brandIn = s => String(s || '').toLowerCase().replace(/[^a-z0-9]/g, '').includes(BRAND);
const NAME_WAIT = 60 * 86400000, USERNAME_WAIT = 100 * 86400000;   // owner: name once every 60 days, @username once every 100
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
    try { this.sql.exec(`ALTER TABLE groups ADD COLUMN team INTEGER`); } catch (e) {}
    try { this.sql.exec(`ALTER TABLE gposts ADD COLUMN files TEXT`); } catch (e) {}   // pictures / videos / documents / audio on group posts   // Your community (owner): one per person
    this.sql.exec(`CREATE TABLE IF NOT EXISTS paybank (uid TEXT PRIMARY KEY, name TEXT, bank TEXT, acct TEXT, updated INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS payments (id TEXT PRIMARY KEY, uid TEXT, to_uid TEXT, to_name TEXT, to_bank TEXT, to_mask TEXT, cents INTEGER, ref TEXT, stitch TEXT, status TEXT, created INTEGER, updated INTEGER)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS payments_uid ON payments(uid, created)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS payments_to ON payments(to_uid, created)`);
    try { this.sql.exec(`ALTER TABLE payments ADD COLUMN kind TEXT`); } catch (e) {}
    this.sql.exec(`CREATE TABLE IF NOT EXISTS subs (uid TEXT PRIMARY KEY, plan TEXT, until INTEGER, updated INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)`);
    // Updates (owner): following, profile posts and 24-hour statuses, and the pictures / videos they show.
    try { this.sql.exec(`ALTER TABLE users ADD COLUMN about TEXT`); } catch (e) {}
    try { this.sql.exec(`ALTER TABLE users ADD COLUMN bio TEXT`); } catch (e) {}
    try { this.sql.exec(`ALTER TABLE users ADD COLUMN photo TEXT`); } catch (e) {}
    try { this.sql.exec(`ALTER TABLE users ADD COLUMN name_at INTEGER`); } catch (e) {}
    try { this.sql.exec(`ALTER TABLE users ADD COLUMN username_at INTEGER`); } catch (e) {}
    try { this.sql.exec(`ALTER TABLE users ADD COLUMN official INTEGER`); } catch (e) {}
    // Accounts (owner): sign up with a password, log in on any phone, log out. The password never comes here - only a
    // key worked out from it on the phone (auth), kept hashed. Your chats and your message key are backed up LOCKED
    // with a second key from the password (bksalt) - the server can't open them.
    for (const c of ['pw TEXT', 'pwsalt TEXT', 'bksalt TEXT', 'keybox TEXT', 'backup TEXT']) { try { this.sql.exec(`ALTER TABLE users ADD COLUMN ${c}`); } catch (e) {} }
    this.sql.exec(`CREATE TABLE IF NOT EXISTS logins (k TEXT PRIMARY KEY, n INTEGER, until INTEGER)`);
    for (const un of OFFICIAL_USERNAMES) this.sql.exec('UPDATE users SET official = 1 WHERE username = ?', un);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS follows (uid TEXT, fid TEXT, at INTEGER, PRIMARY KEY (uid, fid))`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS follows_fid ON follows(fid)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS media (id TEXT, i INTEGER, n INTEGER, uid TEXT, mime TEXT, data BLOB, at INTEGER, PRIMARY KEY (id, i))`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS uposts (id TEXT PRIMARY KEY, uid TEXT, kind TEXT, type TEXT, media TEXT, thumb TEXT, at INTEGER)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS uposts_uid ON uposts(uid, kind, at)`);
    // Posts in Updates (owner): a title, text and up to 20 pictures / videos (items = JSON [{type, media, thumb}]).
    try { this.sql.exec(`ALTER TABLE uposts ADD COLUMN title TEXT`); } catch (e) {}
    try { this.sql.exec(`ALTER TABLE uposts ADD COLUMN text TEXT`); } catch (e) {}
    try { this.sql.exec(`ALTER TABLE uposts ADD COLUMN items TEXT`); } catch (e) {}
    this.sql.exec(`CREATE INDEX IF NOT EXISTS uposts_feed ON uposts(kind, at)`);
    // Hearts and comments on statuses (owner).
    this.sql.exec(`CREATE TABLE IF NOT EXISTS ulikes (pid TEXT, uid TEXT, at INTEGER, PRIMARY KEY (pid, uid))`);
    // Who you've messaged (Profile privacy: a private profile shows to people its owner has messaged).
    this.sql.exec(`CREATE TABLE IF NOT EXISTS contacts (uid TEXT, cid TEXT, at INTEGER, PRIMARY KEY (uid, cid))`);
    // Block (owner): either side blocking cuts messages, calls, profiles, statuses, follows and search between the two.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS blocks (uid TEXT, bid TEXT, at INTEGER, PRIMARY KEY (uid, bid))`);
    // Reports (owner): anyone can report a profile / status / post / comment / group post / public group / chat;
    // the official profile reviews them (remove the content, ban the account, or dismiss).
    // Delete account (owner): a request confirmed with a code on a web page; deleted accounts' sign-in keys are
    // remembered (hashed) so their phones know the account is gone; why people leave is kept with no name on it.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS delreq (token TEXT PRIMARY KEY, uid TEXT, code TEXT, exp INTEGER, tries INTEGER, ok INTEGER, reason TEXT, note TEXT)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS tombs (tok TEXT PRIMARY KEY, at INTEGER)`);
    try { this.sql.exec(`ALTER TABLE tombs ADD COLUMN why TEXT`); } catch (e) {}   // 'deleted' (by its owner) | 'banned'
    this.sql.exec(`CREATE TABLE IF NOT EXISTS delreasons (reason TEXT, note TEXT, at INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, uid TEXT, kind TEXT, target TEXT, owner TEXT, reason TEXT, text TEXT, at INTEGER, status TEXT)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS ucomments (id TEXT PRIMARY KEY, pid TEXT, uid TEXT, text TEXT, at INTEGER)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS ucomments_pid ON ucomments(pid, at)`);
    // STORY's own Stories feed (Community > Stories): one shared public group everyone posts their stories to.
    this.sql.exec(`INSERT OR IGNORE INTO groups (id, name, about, type, image, category, country, creator, calljoin, created) VALUES ('g-stories', 'Stories', 'Real people sharing their life and success stories, and the advice that got them there.', 'stories', '', 'Life', 'Global', 'story', 'open', 0)`);
  }

  one(q, ...a) { const r = this.sql.exec(q, ...a).toArray(); return r[0] || null; }
  phoneHash(p) { return sha('story-phone-v1|' + (this.env.PHONE_SALT || '') + '|' + p); }
  async userByToken(t) { return t ? this.one('SELECT * FROM users WHERE token = ?', await sha('tok|' + t)) : null; }
  privacy(u) { try { return Object.assign({ messages: 'everyone', calls: 'everyone', callPeople: [], status: 'followers', profile: 'public' }, JSON.parse(u.privacy || '{}')); } catch (e) { return { messages: 'everyone', calls: 'everyone', callPeople: [], status: 'followers', profile: 'public' }; } }
  // Profile privacy (owner): Public = everyone can see your profile and find it in search. Private = only people
  // you follow, people you've messaged and people in a group / community with you can see it; no one can find it.
  isPrivate(u) { return this.privacy(u).profile === 'private'; }
  canSeeProfile(viewer, owner) {
    if (!viewer || viewer.id === owner.id) return true;
    if (this.blocked(viewer.id, owner.id)) return false;
    if (!this.isPrivate(owner)) return true;
    return this.follows(owner.id, viewer.id)
      || !!this.one('SELECT 1 AS x FROM contacts WHERE uid = ? AND cid = ?', owner.id, viewer.id)
      || !!this.one('SELECT 1 AS x FROM gmembers a JOIN gmembers b ON a.gid = b.gid WHERE a.uid = ? AND b.uid = ? LIMIT 1', owner.id, viewer.id);
  }
  blocked(a, b) { return !!(a && b && a !== b && this.one('SELECT 1 AS x FROM blocks WHERE (uid = ? AND bid = ?) OR (uid = ? AND bid = ?) LIMIT 1', a, b, b, a)); }
  canCall(from, to) { if (this.blocked(from.id, to.id)) return false; const p = this.privacy(to); return p.calls === 'everyone' || (p.calls === 'people' && (p.callPeople || []).includes(from.id)); }
  canMsg(from, to) { if (this.blocked(from.id, to.id)) return false; const p = this.privacy(to); return p.messages !== 'none' && p.messages !== 'nobody'; }
  pubView(u, me) { const see = this.canSeeProfile(me, u); return { id: u.id, name: u.name || '', username: u.username || '', pub: u.pub, canCall: me ? this.canCall(me, u) : true, canMsg: me ? this.canMsg(me, u) : true, canPay: !!this.one('SELECT uid FROM paybank WHERE uid = ?', u.id), photo: see ? this.mediaUrl(u.photo) : '', about: see ? u.about || '' : '', official: !!u.official, locked: !see }; }

  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    this.origin = url.origin;
    try {
      if (path === '/v1/ws') return this.ws(req, url);
      // Pictures / videos of profiles, posts and statuses: anyone with the link (long random ids, only handed to
      // people allowed to see them). Uploads come in pieces of up to 1.5 MB (signed in).
      if (path.startsWith('/v1/media/') && path !== '/v1/media/put' && req.method === 'GET') return this.mediaGet(path.slice(10), req);
      if (path === '/v1/media/put') {
        const who = await this.userByToken((req.headers.get('authorization') || '').replace(/^Bearer\s+/i, ''));
        return who ? this.mediaPut(who, url, req) : json({ error: 'signin' }, 401);
      }
      if (path === '/v1/release-ping') return this.releasePing();
      if (path === '/v1/health') {
        // Payments: can the server really sign in to Stitch? (just a sign-in - no payment is made)
        let pay = this.expressReady() ? 'keys' : 'no-keys';
        if (url.searchParams.get('pay') === '1' && this.expressReady()) { try { await this.expressToken(); pay = 'ok'; } catch (e) { pay = String(e.message || e).slice(0, 160); } }
        return json({ ok: true, sms: !!(this.env.TWILIO_VERIFY_SID || this.env.TWILIO_FROM), pay, subs: this.subReady(), transfers: this.payReady() });
      }
      if (path === '/v1/pay/return') return new Response('<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><body style="margin:0;background:#000;color:#fff;font:600 18px system-ui;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center;padding:24px;box-sizing:border-box">Done - go back to Lockedintool to see your payment.</body>', { headers: { 'content-type': 'text/html; charset=utf-8' } });
      if (path === '/v1/delete') return this.deletePage(req, url);
      if (path === '/v1/login/salt') return this.loginSalt(url.searchParams.get('u') || '');
      const body = req.method === 'POST' ? await req.json().catch(() => ({})) : {};
      if (path === '/v1/register') return this.register(body, req.headers.get('cf-connecting-ip') || '');
      if (path === '/v1/login') return this.login(body);
      const tok = (req.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
      const me = await this.userByToken(tok);
      const tomb = !me && tok ? this.one('SELECT why FROM tombs WHERE tok = ?', await sha('tok|' + tok)) : null;
      if (!me) return json({ error: tomb ? (tomb.why === 'banned' ? 'banned' : 'deleted') : 'signin' }, 401);
      if (path === '/v1/start') return this.start(body);
      if (path === '/v1/verify') return this.verify(me, body);
      if (path === '/v1/me') return this.saveMe(me, body);
      if (path === '/v1/find') return this.find(me, url.searchParams.get('q') || '');
      if (path.startsWith('/v1/groups/')) return this.groups(me, path.slice(11), body, url.searchParams);
      if (path.startsWith('/v1/pay/')) return this.pay(me, path.slice(8), body, url);
      if (path.startsWith('/v1/sub/')) return this.sub(me, path.slice(8), body, url);
      if (path === '/v1/follow') return this.follow(me, body);
      if (path === '/v1/profile') return this.profile(me, url.searchParams.get('id') || me.id);
      if (path === '/v1/updates') return this.updates(me);
      if (path === '/v1/feed') return this.feed(me, +url.searchParams.get('before') || 0);
      if (path === '/v1/block') return this.block(me, body);
      if (path === '/v1/blocks') return this.blocksOf(me);
      if (path === '/v1/report') return this.report(me, body);
      if (path === '/v1/reports') return this.reportsList(me);
      if (path === '/v1/reports/act') return this.reportAct(me, body);
      if (path === '/v1/auth/set') return this.authSet(me, body);
      if (path === '/v1/backup/set') return this.backupSet(me, body);
      if (path === '/v1/backup/get') return json({ backup: me.backup ? JSON.parse(me.backup) : null, keybox: me.keybox ? JSON.parse(me.keybox) : null, bksalt: me.bksalt || '' });
      if (path === '/v1/logout') { this.sql.exec('UPDATE users SET token = ? WHERE id = ?', await sha('tok|' + rand(32)), me.id); return json({ ok: true }); }
      if (path === '/v1/account/delete-start') return this.deleteStart(me, body);
      if (path === '/v1/account/delete') return json({ error: 'use-link' }, 400);   // only through the link + code (owner)
      if (path.startsWith('/v1/uposts/')) return this.uposts(me, path.slice(11), body);
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
  meView(u) { return { id: u.id, name: u.name || '', username: u.username || '', nameAt: u.name_at || 0, usernameAt: u.username_at || 0, official: !!u.official }; }
  saveMe(me, b) {
    const sets = [], vals = [], now = Date.now();
    const no = (error, extra) => json(Object.assign({ error }, this.meView(me), extra || {}), 409);
    let official = !!me.official;
    if (b.username !== undefined) {
      const un = String(b.username || '').toLowerCase();
      if (un !== (me.username || '')) {
        if (un && !USERNAME.test(un)) return json({ error: 'username' }, 400);
        const officialName = OFFICIAL_USERNAMES.includes(un);
        if (!official && !officialName && brandIn(un)) return no('reserved');
        if (!official && me.username_at && now - me.username_at < USERNAME_WAIT) return no('username-wait', { until: me.username_at + USERNAME_WAIT });
        if (un) { const t = this.one('SELECT id FROM users WHERE username = ? AND id != ?', un, me.id); if (t) return json({ error: 'username-taken' }, 409); }
        sets.push('username = ?', 'username_at = ?'); vals.push(un || null, now);
        if (officialName) { sets.push('official = 1'); official = true; }
      }
    }
    if (b.name !== undefined) {
      const name = String(b.name || '').slice(0, 60);
      if (name !== (me.name || '')) {
        if (!official && brandIn(name)) return no('reserved');
        if (!official && me.name_at && now - me.name_at < NAME_WAIT) return no('name-wait', { until: me.name_at + NAME_WAIT });
        sets.push('name = ?', 'name_at = ?'); vals.push(name || null, now);
      }
    }
    if (b.privacy !== undefined) {
      const p = b.privacy || {};
      const clean = { messages: ['everyone', 'requests', 'nobody'].includes(p.messages) ? p.messages : 'everyone', calls: ['everyone', 'people', 'nobody'].includes(p.calls) ? p.calls : p.calls === 'none' ? 'nobody' : 'everyone', callPeople: (Array.isArray(p.callPeople) ? p.callPeople : []).map(String).slice(0, 500), status: ['everyone', 'followers', 'nobody'].includes(p.status) ? p.status : 'followers', profile: p.profile === 'private' ? 'private' : 'public' };
      sets.push('privacy = ?'); vals.push(JSON.stringify(clean));
    }
    if (b.pub !== undefined) { const pub = String(b.pub || ''); if (pub && pub.length <= 400) { sets.push('pub = ?'); vals.push(pub); } }
    if (b.about !== undefined) { sets.push('about = ?'); vals.push(String(b.about || '').slice(0, 150) || null); }
    if (b.bio !== undefined) { sets.push('bio = ?'); vals.push(String(b.bio || '').slice(0, 150) || null); }
    if (b.photo !== undefined) {
      const ph = String(b.photo || '');
      if (ph && !this.mediaDone(ph, me.id)) return json({ error: 'media' }, 400);
      if (me.photo && me.photo !== ph) this.sql.exec('DELETE FROM media WHERE id = ?', me.photo);
      sets.push('photo = ?'); vals.push(ph || null);
    }
    if (sets.length) this.sql.exec('UPDATE users SET ' + sets.join(', ') + ' WHERE id = ?', ...vals, me.id);
    return json(this.meView(this.one('SELECT * FROM users WHERE id = ?', me.id)));
  }
  // Finding people (owner): by their @username or their name - never phone numbers. Exact @username first, then
  // usernames / names that start with or contain what you typed (up to 20).
  async find(me, q) {
    const t = q.trim().slice(0, 40), un = t.replace(/^@/, '').toLowerCase();
    if (un.length < 2) return json({ people: [] });
    const like = '%' + un.replace(/[%_\\]/g, m => '\\' + m) + '%', start = un.replace(/[%_\\]/g, m => '\\' + m) + '%';
    const rows = this.sql.exec(
      `SELECT * FROM users WHERE id != ? AND (username LIKE ? ESCAPE '\\' OR (? = 0 AND lower(name) LIKE ? ESCAPE '\\'))
       ORDER BY (username = ?) DESC, (username LIKE ? ESCAPE '\\') DESC, (lower(name) LIKE ? ESCAPE '\\') DESC, name LIMIT 80`,
      me.id, like, t.startsWith('@') ? 1 : 0, like, un, start, start).toArray().filter(u => !this.isPrivate(u) && !this.blocked(me.id, u.id)).slice(0, 20);  // a private profile is never found
    return json({ people: rows.map(u => Object.assign(this.pubView(u, me), { isFollowing: this.follows(me.id, u.id) })) });
  }

  // ---- Updates (owner): follow anyone instantly; profile posts; statuses that last 24 hours, seen by who the
  //      owner's privacy setting allows (everyone / followers / no one). ----
  mediaUrl(id) { return id ? (this.origin || '') + '/v1/media/' + id : ''; }
  mediaDone(id, uid) {
    const r = this.one('SELECT COUNT(*) AS c, MAX(n) AS n, MIN(uid) AS u, MAX(uid) AS u2 FROM media WHERE id = ?', id);
    return !!(r && r.c && r.c === r.n && r.u === uid && r.u2 === uid);
  }
  async mediaPut(me, url, req) {
    const data = await req.arrayBuffer();   // always read the whole body first
    const id = url.searchParams.get('id') || '', i = +url.searchParams.get('i'), n = +url.searchParams.get('n'), mime = url.searchParams.get('mime') || '';
    if (!/^[A-Za-z0-9_-]{16,40}$/.test(id) || !(i >= 0 && i < n && n <= 40 && Number.isInteger(i) && Number.isInteger(n)) || !['image/jpeg', 'video/mp4', 'application/octet-stream'].includes(mime)) return json({ error: 'bad' }, 400);
    const owner = this.one('SELECT uid FROM media WHERE id = ? LIMIT 1', id);
    if (owner && owner.uid !== me.id) return json({ error: 'bad' }, 403);
    if (!data.byteLength || data.byteLength > 1572864) return json({ error: 'size' }, 413);
    this.sql.exec('INSERT OR REPLACE INTO media (id, i, n, uid, mime, data, at) VALUES (?, ?, ?, ?, ?, ?, ?)', id, i, n, me.id, mime, data, Date.now());
    return json({ ok: true, done: this.mediaDone(id, me.id) });
  }
  mediaGet(id, req) {
    const rows = this.sql.exec('SELECT i, n, mime, data FROM media WHERE id = ? ORDER BY i', id).toArray();
    if (!rows.length || rows.length !== rows[0].n) return new Response('Not found', { status: 404, headers: { 'access-control-allow-origin': '*' } });
    const parts = rows.map(r => new Uint8Array(r.data)), len = parts.reduce((s, p) => s + p.length, 0), all = new Uint8Array(len);
    let o = 0; for (const p of parts) { all.set(p, o); o += p.length; }
    const h = { 'content-type': rows[0].mime, 'cache-control': 'public, max-age=31536000, immutable', 'access-control-allow-origin': '*', 'accept-ranges': 'bytes' };
    const m = /bytes=(\d*)-(\d*)/.exec(req.headers.get('range') || '');
    if (m && len) {
      const s = m[1] ? +m[1] : 0, e = Math.min(m[2] ? +m[2] : len - 1, len - 1);
      if (s <= e) return new Response(all.slice(s, e + 1), { status: 206, headers: Object.assign(h, { 'content-range': `bytes ${s}-${e}/${len}`, 'content-length': String(e - s + 1) }) });
    }
    return new Response(all, { headers: Object.assign(h, { 'content-length': String(len) }) });
  }
  follows(a, b) { return !!this.one('SELECT 1 AS x FROM follows WHERE uid = ? AND fid = ?', a, b); }
  canSeeStatus(viewer, owner) {
    if (viewer.id === owner.id) return true;
    if (!this.canSeeProfile(viewer, owner)) return false;
    const s = this.privacy(owner).status;
    return s === 'everyone' || (s === 'followers' && this.follows(viewer.id, owner.id));
  }
  dropOldStatuses() {
    const old = this.sql.exec("SELECT id, media, thumb FROM uposts WHERE kind = 'status' AND at < ?", Date.now() - 86400000).toArray();
    for (const p of old) { this.sql.exec('DELETE FROM media WHERE id = ? OR id = ?', p.media, p.thumb || ''); this.sql.exec('DELETE FROM uposts WHERE id = ?', p.id); this.sql.exec('DELETE FROM ulikes WHERE pid = ?', p.id); this.sql.exec('DELETE FROM ucomments WHERE pid = ?', p.id); }
  }
  postItems(p) {
    let it = []; try { it = JSON.parse(p.items || '[]') || []; } catch (e) {}
    if (!it.length && p.media) it = [{ type: p.type, media: p.media, thumb: p.thumb }];
    return it;
  }
  postView(p, me) {
    const items = this.postItems(p).map(i => ({ type: i.type === 'video' ? 'video' : 'photo', url: this.mediaUrl(i.media), thumb: this.mediaUrl(i.thumb) }));
    const v = { id: p.id, kind: p.kind, type: items[0] ? items[0].type : 'text', url: items[0] ? items[0].url : '', thumb: items[0] ? items[0].thumb : '', at: p.at, title: p.title || '', text: p.text || '', items };
    if (p.kind === 'status' || me) Object.assign(v, {
      hearts: this.one('SELECT COUNT(*) AS n FROM ulikes WHERE pid = ?', p.id).n,
      hearted: !!(me && this.one('SELECT 1 AS x FROM ulikes WHERE pid = ? AND uid = ?', p.id, me.id)),
      comments: this.one('SELECT COUNT(*) AS n FROM ucomments WHERE pid = ?', p.id).n });
    return v;
  }
  personView(u, me) {
    const n = q => this.one(q, u.id).n;
    const v = this.pubView(u, me);
    if (v.locked) return Object.assign(v, { private: true, isFollowing: this.follows(me.id, u.id), me: false });
    return Object.assign(v, {
      private: this.isPrivate(u), bio: u.bio || '',
      followers: n('SELECT COUNT(*) AS n FROM follows WHERE fid = ?'), following: n('SELECT COUNT(*) AS n FROM follows WHERE uid = ?'),
      posts: n("SELECT COUNT(*) AS n FROM uposts WHERE uid = ? AND kind = 'post'"),
      isFollowing: u.id !== me.id && this.follows(me.id, u.id), followsYou: u.id !== me.id && this.follows(u.id, me.id), me: u.id === me.id });
  }
  statusesOf(u, me) {
    if (!this.canSeeStatus(me, u)) return [];
    return this.sql.exec("SELECT * FROM uposts WHERE uid = ? AND kind = 'status' AND at >= ? ORDER BY at", u.id, Date.now() - 86400000).toArray().map(p => this.postView(p, me));
  }
  follow(me, b) {
    const u = this.one('SELECT * FROM users WHERE id = ?', String(b.id || ''));
    if (!u || u.id === me.id || this.blocked(me.id, u.id)) return json({ error: 'notfound' }, 404);
    if (b.on === false) this.sql.exec('DELETE FROM follows WHERE uid = ? AND fid = ?', me.id, u.id);
    else this.sql.exec('INSERT OR IGNORE INTO follows (uid, fid, at) VALUES (?, ?, ?)', me.id, u.id, Date.now());
    return json(this.personView(u, me));
  }
  profile(me, id) {
    const u = this.one('SELECT * FROM users WHERE id = ?', id);
    if (!u) return json({ error: 'notfound' }, 404);
    if (!this.canSeeProfile(me, u)) return json(this.personView(u, me));  // private: only who it is
    this.dropOldStatuses();
    const posts = this.sql.exec("SELECT * FROM uposts WHERE uid = ? AND kind = 'post' ORDER BY at DESC LIMIT 90", u.id).toArray().map(p => this.postView(p, me));
    return json(Object.assign(this.personView(u, me), { postList: posts, statuses: this.statusesOf(u, me) }));
  }
  updates(me) {
    this.dropOldStatuses();
    const lite = u => { const v = this.pubView(u, me); return { id: v.id, name: v.name, username: v.username, photo: v.photo, about: v.about, official: v.official, isFollowing: this.follows(me.id, u.id), followers: this.one('SELECT COUNT(*) AS n FROM follows WHERE fid = ?', u.id).n }; };
    const people = this.sql.exec("SELECT DISTINCT u.* FROM uposts p JOIN users u ON u.id = p.uid WHERE p.kind = 'status' AND p.at >= ? AND (u.id = ? OR u.id IN (SELECT fid FROM follows WHERE uid = ?))", Date.now() - 86400000, me.id, me.id).toArray();
    const statuses = people.map(u => ({ user: lite(u), items: this.statusesOf(u, me) })).filter(s => s.items.length)
      .sort((a, b) => (b.user.id === me.id) - (a.user.id === me.id) || b.items[b.items.length - 1].at - a.items[a.items.length - 1].at);
    const profiles = this.sql.exec(`SELECT u.*, (SELECT COUNT(*) FROM follows f WHERE f.fid = u.id) AS fc FROM users u
       WHERE u.id != ? AND (u.name IS NOT NULL OR u.username IS NOT NULL) ORDER BY (u.official = 1) DESC, fc DESC, u.created DESC LIMIT 300`, me.id).toArray().filter(u => this.canSeeProfile(me, u)).slice(0, 60).map(lite);
    return json({ me: this.personView(me, me), statuses, profiles });
  }
  uposts(me, op, b) {
    if (op === 'create') {
      const kind = b.kind === 'status' ? 'status' : 'post';
      // A post: title + text + up to 20 pictures / videos (any of them can be left out, but not all). A status: one.
      let items = Array.isArray(b.items) ? b.items.slice(0, 20).map(i => ({ type: i && i.type === 'video' ? 'video' : 'photo', media: String(i && i.media || ''), thumb: String(i && i.thumb || '') })) : [];
      if (!items.length && b.media) items = [{ type: b.type === 'video' ? 'video' : 'photo', media: String(b.media), thumb: String(b.thumb || '') }];
      for (const i of items) if (!this.mediaDone(i.media, me.id) || (i.thumb && !this.mediaDone(i.thumb, me.id))) return json({ error: 'media' }, 400);
      const title = kind === 'post' ? String(b.title || '').trim().slice(0, 100) : '', text = kind === 'post' ? String(b.text || '').trim().slice(0, 2000) : '';
      if (kind === 'status' ? items.length !== 1 : !items.length && !title && !text) return json({ error: 'empty' }, 400);
      const id = 'u' + rand(10), first = items[0] || {};
      this.sql.exec('INSERT INTO uposts (id, uid, kind, type, media, thumb, at, title, text, items) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        id, me.id, kind, first.type || 'text', first.media || null, first.thumb || null, Date.now(), title || null, text || null, items.length ? JSON.stringify(items) : null);
      this.dropOldStatuses();
      return json(this.postView(this.one('SELECT * FROM uposts WHERE id = ?', id), me));
    }
    if (op === 'get') {
      const p = this.one("SELECT * FROM uposts WHERE id = ? AND kind = 'post'", String(b.id || ''));
      const owner = p && this.one('SELECT * FROM users WHERE id = ?', p.uid);
      if (!p || !owner || !this.canSeeProfile(me, owner)) return json({ error: 'notfound' }, 404);
      return json(Object.assign(this.postView(p, me), { user: this.feedUser(owner, me) }));
    }
    // A status / post you're allowed to see: heart it, comment under it, read its comments.
    if (['heart', 'comment', 'comments', 'uncomment'].includes(op)) {
      const p = this.one("SELECT * FROM uposts WHERE id = ? AND (kind = 'post' OR (kind = 'status' AND at >= ?))", String(b.id || ''), Date.now() - 86400000);
      const owner = p && this.one('SELECT * FROM users WHERE id = ?', p.uid);
      if (!p || !owner || !(p.kind === 'status' ? this.canSeeStatus(me, owner) : this.canSeeProfile(me, owner))) return json({ error: 'notfound' }, 404);
      if (op === 'heart') {
        if (b.on === false) this.sql.exec('DELETE FROM ulikes WHERE pid = ? AND uid = ?', p.id, me.id);
        else this.sql.exec('INSERT OR IGNORE INTO ulikes (pid, uid, at) VALUES (?, ?, ?)', p.id, me.id, Date.now());
      }
      if (op === 'comment') {
        const text = String(b.text || '').trim().slice(0, 500);
        if (!text) return json({ error: 'empty' }, 400);
        this.sql.exec('INSERT INTO ucomments (id, pid, uid, text, at) VALUES (?, ?, ?, ?, ?)', 'c' + rand(9), p.id, me.id, text, Date.now());
      }
      if (op === 'uncomment') this.sql.exec('DELETE FROM ucomments WHERE id = ? AND pid = ? AND (uid = ? OR ? = ?)', String(b.cid || ''), p.id, me.id, p.uid, me.id);
      const list = this.sql.exec('SELECT c.*, u.name, u.username, u.photo FROM ucomments c LEFT JOIN users u ON u.id = c.uid WHERE c.pid = ? ORDER BY c.at LIMIT 300', p.id).toArray()
        .filter(c => !this.blocked(me.id, c.uid)).map(c => ({ id: c.id, text: c.text, at: c.at, mine: c.uid === me.id, canDelete: c.uid === me.id || p.uid === me.id, user: { id: c.uid, name: c.name || '', username: c.username || '', photo: this.mediaUrl(c.photo) } }));
      return json(Object.assign(this.postView(p, me), { list }));
    }
    if (op === 'delete') {
      const p = this.one('SELECT * FROM uposts WHERE id = ? AND uid = ?', String(b.id || ''), me.id);
      if (!p) return json({ error: 'notfound' }, 404);
      for (const i of this.postItems(p)) this.sql.exec('DELETE FROM media WHERE id = ? OR id = ?', i.media || '', i.thumb || '');
      this.sql.exec('DELETE FROM media WHERE id = ? OR id = ?', p.media, p.thumb || '');
      this.sql.exec('DELETE FROM uposts WHERE id = ?', p.id);
      this.sql.exec('DELETE FROM ulikes WHERE pid = ?', p.id); this.sql.exec('DELETE FROM ucomments WHERE pid = ?', p.id);
      return json({ ok: true });
    }
    return json({ error: 'notfound' }, 404);
  }

  // ---- The feed (owner): everything posted on Lockedintool, newest first, that you're allowed to see. ----
  feedUser(u, me) { const v = this.pubView(u, me); return { id: v.id, name: v.name, username: v.username, photo: v.photo, official: v.official }; }
  feed(me, before) {
    const out = [], seen = new Map(); let cursor = before || Date.now() + 1, more = false;
    for (let round = 0; round < 5 && out.length < 15; round++) {
      const rows = this.sql.exec("SELECT * FROM uposts WHERE kind = 'post' AND at < ? ORDER BY at DESC LIMIT 60", cursor).toArray();
      if (!rows.length) break;
      for (const p of rows) {
        cursor = p.at;
        if (!seen.has(p.uid)) { const u = this.one('SELECT * FROM users WHERE id = ?', p.uid); seen.set(p.uid, u && this.canSeeProfile(me, u) ? u : null); }
        const u = seen.get(p.uid); if (!u) continue;
        if (out.length >= 15) { more = true; break; }
        out.push(Object.assign(this.postView(p, me), { user: this.feedUser(u, me) }));
      }
      if (rows.length < 60 && !more) break;
      if (out.length >= 15) more = true;
    }
    return json({ posts: out, more, next: out.length ? out[out.length - 1].at : 0 });
  }

  // A file in a chat / group / post (owner): only its description travels - the bytes are in media (locked ones can
  // only be opened by the people the key was sent to).
  fileMeta(f) {
    if (!f || typeof f !== 'object' || !/^[A-Za-z0-9_-]{16,40}$/.test(String(f.m || ''))) return null;
    return { m: String(f.m), name: String(f.name || 'file').slice(0, 120), mime: String(f.mime || 'application/octet-stream').slice(0, 80),
      size: Math.max(0, +f.size || 0), kind: ['photo', 'video', 'doc', 'audio'].includes(f.kind) ? f.kind : 'doc' };
  }

  // ---- Accounts: password, log in, log out, locked backup (owner) ----
  async fakeSalt(u) { let s = (this.one("SELECT v FROM meta WHERE k = 'fake'") || {}).v; if (!s) { s = rand(24); this.sql.exec("INSERT OR REPLACE INTO meta (k, v) VALUES ('fake', ?)", s); } return (await sha('salt|' + s + '|' + u)).slice(0, 24); }
  async loginSalt(u) {
    u = String(u).toLowerCase().replace(/^@/, '').slice(0, 30);
    const r = u && this.one('SELECT pwsalt FROM users WHERE username = ? AND pw IS NOT NULL', u);
    return json({ salt: r ? r.pwsalt : await this.fakeSalt(u) });   // unknown names get a steady made-up salt (no guessing who's on)
  }
  async login(b) {
    const u = String(b.username || '').toLowerCase().replace(/^@/, '').slice(0, 30), now = Date.now();
    const lock = this.one('SELECT * FROM logins WHERE k = ?', u);
    if (lock && lock.until > now) return json({ error: 'locked', until: lock.until }, 429);
    const me = u && this.one('SELECT * FROM users WHERE username = ? AND pw IS NOT NULL', u);
    if (!me || (await sha('pw|' + String(b.auth || ''))) !== me.pw) {
      const n = (lock && lock.until <= now && lock.until ? 0 : (lock ? lock.n : 0)) + 1;
      this.sql.exec('INSERT OR REPLACE INTO logins (k, n, until) VALUES (?, ?, ?)', u, n >= 5 ? 0 : n, n >= 5 ? now + 15 * 60000 : 0);
      return json({ error: 'wrong' }, 401);
    }
    this.sql.exec('DELETE FROM logins WHERE k = ?', u);
    const token = rand(32);
    this.sql.exec('UPDATE users SET token = ? WHERE id = ?', await sha('tok|' + token), me.id);
    for (const ws of this.ctx.getWebSockets(me.id)) { try { ws.close(4002, 'signed-in-elsewhere'); } catch (e) {} }   // the phone it was on is signed out
    return json({ id: me.id, token, name: me.name || '', username: me.username || '', bksalt: me.bksalt || '', keybox: me.keybox ? JSON.parse(me.keybox) : null, backup: me.backup ? JSON.parse(me.backup) : null });
  }
  authSet(me, b) {
    if (me.pw) return json({ error: 'has-password' }, 409);
    const auth = String(b.auth || ''), pwsalt = String(b.pwsalt || ''), bksalt = String(b.bksalt || '');
    if (auth.length < 20 || !/^[A-Za-z0-9_-]{16,40}$/.test(pwsalt) || !/^[A-Za-z0-9_-]{16,40}$/.test(bksalt)) return json({ error: 'bad' }, 400);
    const kb = b.keybox && typeof b.keybox === 'object' ? JSON.stringify({ box: String(b.keybox.box || '').slice(0, 4000), iv: String(b.keybox.iv || '').slice(0, 40) }) : null;
    return sha('pw|' + auth).then(h => { this.sql.exec('UPDATE users SET pw = ?, pwsalt = ?, bksalt = ?, keybox = ? WHERE id = ?', h, pwsalt, bksalt, kb, me.id); return json({ ok: true }); });
  }
  backupSet(me, b) {
    const media = String(b.media || ''), iv = String(b.iv || '');
    if (!this.mediaDone(media, me.id) || !/^[A-Za-z0-9+/=]{8,40}$/.test(iv)) return json({ error: 'bad' }, 400);
    const old = me.backup ? JSON.parse(me.backup) : null;
    if (old && old.media && old.media !== media) this.sql.exec('DELETE FROM media WHERE id = ?', old.media);
    this.sql.exec('UPDATE users SET backup = ? WHERE id = ?', JSON.stringify({ media, iv, at: Date.now(), z: b.z ? 1 : 0 }), me.id);
    return json({ ok: true });
  }

  // ---- Delete account (owner): reason -> warning -> the phone's PIN / biometrics -> Lockedintool sends a message with a
  //      link and a code -> the link's page: enter the code, Confirm, then Delete account. ----
  async deleteStart(me, b) {
    const now = Date.now();
    const reasons = ['not-using', 'notifications', 'privacy', 'new-account', 'not-working', 'other'];
    this.sql.exec('DELETE FROM delreq WHERE uid = ? OR exp < ?', me.id, now);
    const token = rand(18), code = String(100000 + (crypto.getRandomValues(new Uint32Array(1))[0] % 900000));
    this.sql.exec('INSERT INTO delreq (token, uid, code, exp, tries, ok, reason, note) VALUES (?, ?, ?, ?, 0, 0, ?, ?)',
      token, me.id, await sha('del|' + token + '|' + code), now + 30 * 60000, reasons.includes(b.reason) ? b.reason : 'other', String(b.note || '').slice(0, 300));
    const link = (this.origin || '') + '/v1/delete?t=' + token;
    this.alert(me.id, 'You asked to delete your Lockedintool account. To confirm, open this link and enter the code ' + code + ':\n' + link + '\nThe link and code work for 30 minutes. If this wasn’t you, ignore this message - your account stays.');
    return json({ ok: true, exp: now + 30 * 60000 });
  }
  async deletePage(req, url) {
    const page = (inner) => new Response('<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Delete account</title><style>' +
      ':root{color-scheme:dark}body{margin:0;background:#000;color:#fff;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}main{max-width:420px;margin:0 auto;padding:48px 20px}' +
      'h1{font-size:22px;margin:0 0 10px}p{color:#bbb;margin:0 0 16px}input{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #444;background:#000;color:#fff;font-size:22px;letter-spacing:6px;text-align:center}' +
      'button{width:100%;margin-top:14px;padding:15px;border:none;border-radius:12px;font-size:16px;font-weight:700;cursor:pointer;background:#fff;color:#000}button.red{background:#ff3b30;color:#fff}.err{color:#ff6b60}ul{color:#bbb;padding-left:20px;margin:0 0 8px}</style></head><body><main>' + inner + '</main></body></html>',
      { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-frame-options': 'DENY' } });
    const esc = v => String(v).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
    let f = {};
    if (req.method === 'POST') { try { f = Object.fromEntries(new URLSearchParams(await req.text())); } catch (e) {} }
    const token = String(f.t || url.searchParams.get('t') || '').slice(0, 40);
    const r = token && this.one('SELECT * FROM delreq WHERE token = ?', token);
    if (!r || r.exp < Date.now()) return page('<h1>This link has expired</h1><p>Start again in Lockedintool: Settings &gt; Privacy &gt; Delete account.</p>');
    const form = (body) => '<form method="post"><input type="hidden" name="t" value="' + esc(token) + '">' + body + '</form>';
    if (req.method === 'POST' && f.step === 'delete') {
      if (!r.ok) return page('<h1>Enter the code first</h1>');
      const u = this.one('SELECT * FROM users WHERE id = ?', r.uid);
      if (u) this.deleteUser(u.id);
      this.sql.exec('INSERT INTO delreasons (reason, note, at) VALUES (?, ?, ?)', r.reason || 'other', r.note || '', Date.now());
      this.sql.exec('DELETE FROM delreq WHERE token = ?', token);
      return page('<h1>Your account is deleted</h1><p>Everything that came with Lockedintool is gone - Lockedintool on your phone starts fresh. Nothing outside Lockedintool was touched. You can close this page.</p>');
    }
    if (req.method === 'POST' && f.step === 'code') {
      if (r.tries >= 5) return page('<h1>Too many tries</h1><p>Start again in Lockedintool: Settings &gt; Privacy &gt; Delete account.</p>');
      if ((await sha('del|' + token + '|' + String(f.code || '').replace(/\D/g, ''))) === r.code) {
        this.sql.exec('UPDATE delreq SET ok = 1 WHERE token = ?', token);
        return page('<h1>Code confirmed</h1><p>Deleting your account can’t be undone. Everything that comes with Lockedintool goes:</p><ul><li>your profile, name and @username</li><li>your followers and who you follow</li><li>your posts, statuses and comments</li><li>your chats and your community</li><li>your Pages, notes, money and wallet history, cards, trading accounts and settings on your phone</li><li>your Lockedintool plan - payments can’t be refunded</li></ul><p>Nothing outside Lockedintool is touched - your bank, payments, broker accounts and phone stay exactly as they are. It’s like deleting an app.</p>' +
          form('<input type="hidden" name="step" value="delete"><button class="red" type="submit">Delete account</button>'));
      }
      this.sql.exec('UPDATE delreq SET tries = tries + 1 WHERE token = ?', token);
      const left = 4 - r.tries;
      return page('<h1>Delete your account</h1><p class="err">That code isn’t right' + (left > 0 ? ' - ' + left + (left === 1 ? ' try' : ' tries') + ' left.' : '.') + '</p>' +
        form('<input type="hidden" name="step" value="code"><input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000" autofocus><button type="submit">Confirm</button>'));
    }
    if (r.ok) return page('<h1>Code confirmed</h1><p>This deletes your account and everything that comes with Lockedintool. It can’t be undone. Nothing outside Lockedintool is touched.</p>' + form('<input type="hidden" name="step" value="delete"><button class="red" type="submit">Delete account</button>'));
    return page('<h1>Delete your account</h1><p>Enter the 6-digit code Lockedintool sent you in Messages.</p>' +
      form('<input type="hidden" name="step" value="code"><input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000" autofocus><button type="submit">Confirm</button>'));
  }

  // ---- Block, report, delete account (owner) ----
  block(me, b) {
    const u = this.one('SELECT id FROM users WHERE id = ?', String(b.id || ''));
    if (!u || u.id === me.id) return json({ error: 'notfound' }, 404);
    if (b.on === false) this.sql.exec('DELETE FROM blocks WHERE uid = ? AND bid = ?', me.id, u.id);
    else {
      this.sql.exec('INSERT OR IGNORE INTO blocks (uid, bid, at) VALUES (?, ?, ?)', me.id, u.id, Date.now());
      this.sql.exec('DELETE FROM follows WHERE (uid = ? AND fid = ?) OR (uid = ? AND fid = ?)', me.id, u.id, u.id, me.id);
    }
    return json({ ok: true, blocked: b.on !== false });
  }
  blocksOf(me) {
    const rows = this.sql.exec('SELECT b.bid, b.at, u.name, u.username FROM blocks b LEFT JOIN users u ON u.id = b.bid WHERE b.uid = ? ORDER BY b.at DESC', me.id).toArray();
    return json({ people: rows.map(r => ({ id: r.bid, name: r.name || '', username: r.username || '', at: r.at })) });
  }
  // What a report points at: who it belongs to and a short look at it (the official profile sees this).
  reportTarget(kind, id) {
    if (kind === 'profile' || kind === 'chat') { const u = this.one('SELECT * FROM users WHERE id = ?', id); return u ? { owner: u.id, look: (u.name || '') + (u.username ? ' @' + u.username : '') + (u.bio ? ' - ' + u.bio : '') } : null; }
    if (kind === 'status' || kind === 'post') { const p = this.one('SELECT * FROM uposts WHERE id = ?', id); return p ? { owner: p.uid, look: [p.title, p.text].filter(Boolean).join(' - ') || p.kind + ' (' + p.type + ')', media: this.mediaUrl(p.type === 'video' ? p.thumb || p.media : p.media) } : null; }
    if (kind === 'comment') { const c = this.one('SELECT * FROM ucomments WHERE id = ?', id); return c ? { owner: c.uid, look: c.text } : null; }
    if (kind === 'gpost') { const p = this.one('SELECT * FROM gposts WHERE id = ?', id); return p ? { owner: p.uid, look: p.text || '(picture)' } : null; }
    if (kind === 'gcomment') { const c = this.one('SELECT * FROM gcomments WHERE id = ?', id); return c ? { owner: c.uid, look: c.text } : null; }
    if (kind === 'group') { const g = this.one("SELECT * FROM groups WHERE id = ? AND type = 'public'", id); return g ? { owner: g.creator, look: g.name + (g.about ? ' - ' + g.about : '') } : null; }
    return null;
  }
  report(me, b) {
    const kind = String(b.kind || ''), id = String(b.id || '').slice(0, 60);
    const t = this.reportTarget(kind, id);
    if (!t) return json({ error: 'notfound' }, 404);
    const recent = this.one('SELECT COUNT(*) AS n FROM reports WHERE uid = ? AND at > ?', me.id, Date.now() - 3600000).n;
    if (recent >= 20) return json({ error: 'slow' }, 429);
    const reason = ['spam', 'abuse', 'nudity', 'violence', 'scam', 'hate', 'other'].includes(b.reason) ? b.reason : 'other';
    this.sql.exec('INSERT INTO reports (id, uid, kind, target, owner, reason, text, at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
      'r' + rand(9), me.id, kind, id, t.owner, reason, String(b.text || '').slice(0, 2000), Date.now(), 'open');
    // Lockedintool warns the account (owner): how many reports came in about what - never who reported it.
    const n = this.one('SELECT COUNT(*) AS n FROM reports WHERE kind = ? AND target = ?', kind, id).n;
    if ([1, 3, 5, 10, 20].includes(n) && t.owner) this.alert(t.owner, 'Your ' + this.reportWhat(kind, id) + ' has been reported ' + (n === 1 ? 'once' : n + ' times') + ' for ' + REPORT_REASONS[reason] + '. Please keep to Lockedintool’s rules - accounts that keep breaking them can be removed.');
    return json({ ok: true });
  }
  alert(uid, text) { this.deliver(uid, { t: 'alert', text, at: Date.now() }); }
  reportWhat(kind, id) {
    const cut = (x, n) => { x = String(x || '').trim(); return x.length > n ? x.slice(0, n) + '…' : x; };
    if (kind === 'profile') return 'profile';
    if (kind === 'chat') return 'messages';
    if (kind === 'status') return 'status';
    if (kind === 'post') { const p = this.one('SELECT title, text FROM uposts WHERE id = ?', id) || {}; const s = cut(p.title || p.text, 40); return 'post' + (s ? ' “' + s + '”' : ''); }
    if (kind === 'comment' || kind === 'gcomment') { const c = this.one(`SELECT text FROM ${kind === 'comment' ? 'ucomments' : 'gcomments'} WHERE id = ?`, id) || {}; return 'comment' + (c.text ? ' “' + cut(c.text, 40) + '”' : ''); }
    if (kind === 'gpost') { const p = this.one('SELECT text FROM gposts WHERE id = ?', id) || {}; return 'group post' + (p.text ? ' “' + cut(p.text, 40) + '”' : ''); }
    if (kind === 'group') { const g = this.one('SELECT name FROM groups WHERE id = ?', id) || {}; return 'group' + (g.name ? ' “' + cut(g.name, 40) + '”' : ''); }
    return kind;
  }
  reportsList(me) {
    if (!me.official) return json({ error: 'official' }, 403);
    const who = id => { const u = this.one('SELECT name, username FROM users WHERE id = ?', id); return u ? (u.name || '') + (u.username ? ' @' + u.username : '') : '(deleted)'; };
    // One card per reported thing: how many reports, for what - never who reported it (owner).
    const rows = this.sql.exec("SELECT * FROM reports WHERE status = 'open' ORDER BY at DESC LIMIT 1000").toArray(), cards = new Map();
    for (const r of rows) {
      const k = r.kind + '|' + r.target;
      if (!cards.has(k)) { const t = this.reportTarget(r.kind, r.target) || {}; cards.set(k, { id: r.id, kind: r.kind, at: r.at, count: 0, reasons: {}, text: '', owner: r.owner, ownerName: who(r.owner), look: t.look || '(already gone)', media: t.media || '', gone: !t.owner }); }
      const c = cards.get(k); c.count++; c.reasons[r.reason] = (c.reasons[r.reason] || 0) + 1; if (!c.text && r.text) c.text = r.text;
    }
    return json({ reports: [...cards.values()].slice(0, 200) });
  }
  reportAct(me, b) {
    if (!me.official) return json({ error: 'official' }, 403);
    const r = this.one('SELECT * FROM reports WHERE id = ?', String(b.id || ''));
    if (!r) return json({ error: 'notfound' }, 404);
    const act = b.action;
    if (act === 'remove') {
      const what = this.reportWhat(r.kind, r.target);
      const top = this.one("SELECT reason, COUNT(*) AS n FROM reports WHERE kind = ? AND target = ? GROUP BY reason ORDER BY n DESC LIMIT 1", r.kind, r.target);
      this.removeContent(r.kind, r.target);
      if (r.owner && ['status', 'post', 'comment', 'gpost', 'gcomment', 'group'].includes(r.kind)) this.alert(r.owner, 'Your ' + what + ' was removed by Lockedintool after reports for ' + REPORT_REASONS[(top || {}).reason || 'other'] + '.');
    }
    if (act === 'ban' && r.owner) { const o = this.one('SELECT official FROM users WHERE id = ?', r.owner); if (o && !o.official) this.deleteUser(r.owner, 'banned'); }
    // Everything else about the same thing is done too.
    this.sql.exec("UPDATE reports SET status = ? WHERE kind = ? AND target = ? AND status = 'open'", act === 'dismiss' ? 'dismissed' : 'done', r.kind, r.target);
    if (act === 'ban') this.sql.exec("UPDATE reports SET status = 'done' WHERE owner = ? AND status = 'open'", r.owner);
    return this.reportsList(me);
  }
  removeContent(kind, id) {
    if (kind === 'status' || kind === 'post') {
      const p = this.one('SELECT * FROM uposts WHERE id = ?', id); if (!p) return;
      for (const i of this.postItems(p)) this.sql.exec('DELETE FROM media WHERE id = ? OR id = ?', i.media || '', i.thumb || '');
      this.sql.exec('DELETE FROM media WHERE id = ? OR id = ?', p.media, p.thumb || ''); this.sql.exec('DELETE FROM uposts WHERE id = ?', p.id);
      this.sql.exec('DELETE FROM ulikes WHERE pid = ?', p.id); this.sql.exec('DELETE FROM ucomments WHERE pid = ?', p.id);
    }
    if (kind === 'comment') this.sql.exec('DELETE FROM ucomments WHERE id = ?', id);
    if (kind === 'gpost') { this.sql.exec('DELETE FROM gcomments WHERE pid = ?', id); this.sql.exec('DELETE FROM glikes WHERE pid = ?', id); this.sql.exec('DELETE FROM gposts WHERE id = ?', id); }
    if (kind === 'gcomment') this.sql.exec('DELETE FROM gcomments WHERE id = ?', id);
    if (kind === 'group') {
      const g = this.one("SELECT * FROM groups WHERE id = ? AND type = 'public'", id); if (!g) return;
      const ids = this.memberIds(g.id);
      for (const p of this.sql.exec('SELECT id FROM gposts WHERE gid = ?', g.id).toArray()) { this.sql.exec('DELETE FROM gcomments WHERE pid = ?', p.id); this.sql.exec('DELETE FROM glikes WHERE pid = ?', p.id); }
      this.sql.exec('DELETE FROM gposts WHERE gid = ?', g.id); this.sql.exec('DELETE FROM gmembers WHERE gid = ?', g.id); this.sql.exec('DELETE FROM groups WHERE id = ?', g.id);
      for (const uid of ids) this.deliver(uid, { t: 'group', g: { id: g.id, gone: true } });
    }
  }
  // Delete account (owner; POPIA): everything Lockedintool's server keeps about you goes. Payment records stay, as
  // the law requires (they never hold your card or bank login).
  deleteUser(uid, why = 'deleted') {
    for (const m of this.sql.exec('SELECT gid FROM gmembers WHERE uid = ?', uid).toArray()) {
      this.sql.exec('DELETE FROM gmembers WHERE gid = ? AND uid = ?', m.gid, uid);
      const left = this.memberIds(m.gid);
      if (!left.length) { for (const p of this.sql.exec('SELECT id FROM gposts WHERE gid = ?', m.gid).toArray()) { this.sql.exec('DELETE FROM gcomments WHERE pid = ?', p.id); this.sql.exec('DELETE FROM glikes WHERE pid = ?', p.id); } this.sql.exec('DELETE FROM gposts WHERE gid = ?', m.gid); this.sql.exec("DELETE FROM groups WHERE id = ? AND type != 'stories'", m.gid); continue; }
      if (!this.one("SELECT uid FROM gmembers WHERE gid = ? AND role = 'leader'", m.gid)) {
        const next = this.one('SELECT uid FROM gmembers WHERE gid = ? ORDER BY joined LIMIT 1', m.gid);
        this.sql.exec("UPDATE gmembers SET role = 'leader' WHERE gid = ? AND uid = ?", m.gid, next.uid);
      }
      this.groupTell(m.gid);
    }
    for (const p of this.sql.exec('SELECT id FROM uposts WHERE uid = ?', uid).toArray()) { this.sql.exec('DELETE FROM ulikes WHERE pid = ?', p.id); this.sql.exec('DELETE FROM ucomments WHERE pid = ?', p.id); }
    for (const p of this.sql.exec('SELECT id FROM gposts WHERE uid = ?', uid).toArray()) { this.sql.exec('DELETE FROM gcomments WHERE pid = ?', p.id); this.sql.exec('DELETE FROM glikes WHERE pid = ?', p.id); }
    this.sql.exec('UPDATE gposts SET likes = likes - 1 WHERE id IN (SELECT pid FROM glikes WHERE uid = ?)', uid);
    const del = [['uposts', 'uid'], ['media', 'uid'], ['ulikes', 'uid'], ['ucomments', 'uid'], ['gposts', 'uid'], ['glikes', 'uid'], ['gcomments', 'uid'],
      ['follows', 'uid'], ['follows', 'fid'], ['contacts', 'uid'], ['contacts', 'cid'], ['blocks', 'uid'], ['blocks', 'bid'], ['queue', 'uid'],
      ['paybank', 'uid'], ['subs', 'uid'], ['reports', 'uid']];
    for (const [t, c] of del) this.sql.exec(`DELETE FROM ${t} WHERE ${c} = ?`, uid);
    const gone = this.one('SELECT token FROM users WHERE id = ?', uid);
    if (gone && gone.token) this.sql.exec('INSERT OR IGNORE INTO tombs (tok, at, why) VALUES (?, ?, ?)', gone.token, Date.now(), why);   // its phone learns the account is gone
    this.sql.exec('DELETE FROM users WHERE id = ?', uid);
    for (const ws of this.ctx.getWebSockets(uid)) { try { ws.close(why === 'banned' ? 4003 : 4001, why); } catch (e) {} }
  }

  // ---- Groups ----
  member(gid, uid) { return this.one('SELECT role FROM gmembers WHERE gid = ? AND uid = ?', gid, uid); }
  memberIds(gid) { return this.sql.exec('SELECT uid FROM gmembers WHERE gid = ?', gid).toArray().map(r => r.uid); }
  groupView(g, me, full) {
    const count = this.one('SELECT COUNT(*) AS n FROM gmembers WHERE gid = ?', g.id).n;
    const mine = me ? this.member(g.id, me.id) : null;
    const v = { id: g.id, name: g.name, about: g.about || '', type: g.type, image: g.image || '', category: g.category || 'Community', country: g.country || 'Global',
      creator: g.creator, callJoin: g.calljoin || 'open', created: g.created, count, team: !!g.team, member: !!mine, leader: !!mine && mine.role === 'leader',
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
      const team = !!b.team, name = str(b.name, 60), type = team || b.type === 'private' ? 'private' : 'public';
      if (!name) return json({ error: 'name' }, 400);
      // Your community (owner): ONE community you make - a private, locked team room; only you add people.
      if (team) { const had = this.one('SELECT * FROM groups WHERE creator = ? AND team = 1', me.id); if (had && this.member(had.id, me.id)) return json(this.groupView(had, me, true)); }
      if (type === 'public' && this.isPrivate(me)) return json({ error: 'private-account' }, 403);
      const image = String(b.image || '');
      const id = 'grp' + rand(9);
      this.sql.exec('INSERT INTO groups (id, name, about, type, image, category, country, creator, calljoin, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
        id, name, str(b.about, 500), type, image.length < 300000 ? image : '', str(b.category, 30) || 'Community', str(b.country, 40) || 'Global', me.id, 'open', now);
      if (team) this.sql.exec('UPDATE groups SET team = 1 WHERE id = ?', id);
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
      if (this.isPrivate(me)) return json({ error: 'private-account' }, 403);   // private accounts have only their own community (owner)
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
      if (g.team && g.creator !== me.id) return json({ error: 'leader' }, 403);   // Your community: only you add people
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
      if (g.type === 'public' && this.isPrivate(me)) return json({ error: 'private-account' }, 403);
      const text = str(b.text, 600), image = /^data:image\/(png|jpeg|webp);base64,/.test(String(b.image || '')) && String(b.image).length < 600000 ? String(b.image) : '';
      const files = (Array.isArray(b.files) ? b.files : []).slice(0, 10).map(f => this.fileMeta(f)).filter(f => f && this.mediaDone(f.m, me.id));
      if (!text && !image && !files.length) return json({ error: 'empty' }, 400);
      this.sql.exec('INSERT INTO gposts (id, gid, uid, text, image, at, files) VALUES (?, ?, ?, ?, ?, ?, ?)', 'p' + rand(9), g.id, me.id, text, image, now, files.length ? JSON.stringify(files) : null);
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
    return this.sql.exec('SELECT * FROM gposts WHERE gid = ? ORDER BY at DESC LIMIT 100', gid).toArray().filter(p => !this.blocked(me.id, p.uid)).map(p => ({
      id: p.id, author: p.uid === me.id ? 'You' : who(p.uid), mine: p.uid === me.id, text: p.text, image: p.image || '', at: p.at, likes: p.likes, shares: p.shares || 0,
      files: (() => { try { return (JSON.parse(p.files || '[]') || []).map(f => Object.assign({}, f, { url: this.mediaUrl(f.m) })); } catch (e) { return []; } })(),
      liked: !!this.one('SELECT pid FROM glikes WHERE pid = ? AND uid = ?', p.id, me.id),
      comments: this.sql.exec('SELECT * FROM gcomments WHERE pid = ? ORDER BY at LIMIT 200', p.id).toArray().filter(c => !this.blocked(me.id, c.uid)).map(c => ({ id: c.id, author: c.uid === me.id ? 'You' : who(c.uid), mine: c.uid === me.id, text: c.text, at: c.at }))
    }));
  }

  // ---- Live connection ----
  // ---- Everything fast (owner): the moment a new version is live, every connected phone is told (t:'release') and
  //      picks it up at once, instead of waiting for its next look. Anyone may call this - the server checks the truth
  //      itself (the live screens' source.txt + the live app's version.json) and only tells phones when it changed. ----
  async releasePing() {
    const now = Date.now(), last = +(this.one("SELECT v FROM meta WHERE k = 'ping_at'") || {}).v || 0;
    if (now - last < 4000) return json({ ok: true, wait: true });
    this.sql.exec("INSERT OR REPLACE INTO meta (k, v) VALUES ('ping_at', ?)", String(now));
    const get = async u => { try { const r = await fetch(u + '?t=' + now, { cf: { cacheTtl: 0 } }); return r.ok ? (await r.text()).trim().slice(0, 200) : ''; } catch (e) { return ''; } };
    const [screens, app] = await Promise.all([get('https://bethestoryownyourstory.github.io/story-lockedintool/live-source.txt'), get('https://github.com/bethestoryownyourstory/story-lockedintool/releases/download/live/version.json')]);
    const v = screens + '|' + app, before = (this.one("SELECT v FROM meta WHERE k = 'release'") || {}).v || '';
    if (!screens && !app) return json({ ok: false });
    if (v === before) return json({ ok: true, changed: false });
    this.sql.exec("INSERT OR REPLACE INTO meta (k, v) VALUES ('release', ?)", v);
    let n = 0; const msg = JSON.stringify({ t: 'release', screens, app });
    for (const ws of this.ctx.getWebSockets()) { try { ws.send(msg); n++; } catch (e) {} }
    return json({ ok: true, changed: true, told: n });
  }
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
    return { connected: this.subReady(), enforced: this.env.SUB_ENFORCE === '1', plan: active ? r.plan : null, until: r ? r.until : 0, active, plans: this.subPlans() };
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
        this.sql.exec('INSERT OR IGNORE INTO contacts (uid, cid, at) VALUES (?, ?, ?)', uid, to.id, now);
        this.deliver(to.id, { t: 'msg', from: uid, id: String(m.id).slice(0, 40), box: String(m.box), iv: String(m.iv || ''), at: now, name: me.name || '', username: me.username || '' });
        ws.send(JSON.stringify({ t: 'sent', id: m.id, at: now }));
        return; }
      case 'seen': case 'read':
        if (to && !this.blocked(uid, to.id)) this.deliver(to.id, { t: 'status', chat: uid, status: m.t, at: now });
        return;
      case 'typing':
        if (m.group) {
          const g = this.one('SELECT id FROM groups WHERE id = ?', String(m.group));
          if (g && this.member(g.id, uid)) for (const id of this.memberIds(g.id)) if (id !== uid) this.sendTo(id, { t: 'typing', chat: g.id, who: me.name || (me.username ? '@' + me.username : ''), at: now });
          return;
        }
        if (to && !this.blocked(uid, to.id)) this.sendTo(to.id, { t: 'typing', chat: uid, at: now });
        return;
      case 'gmsg': {
        // A group message: private groups send a locked box for each member; public groups send the text itself.
        const g = this.one('SELECT * FROM groups WHERE id = ?', String(m.group || ''));
        if (!g || !this.member(g.id, uid)) return ws.send(JSON.stringify({ t: 'fail', id: m.id, error: 'member' }));
        const id = String(m.id).slice(0, 40), boxes = m.boxes || {};
        for (const mid of this.memberIds(g.id)) {
          if (mid === uid) continue;
          if (g.type === 'private') { const bx = boxes[mid]; if (!bx || String(bx.box || '').length > 64000) continue; this.deliver(mid, { t: 'msg', group: g.id, from: uid, id, box: String(bx.box), iv: String(bx.iv || ''), at: now, name: me.name || '', username: me.username || '' }); }
          else this.deliver(mid, { t: 'msg', group: g.id, from: uid, id, text: String(m.text || '').slice(0, 4000), file: this.fileMeta(m.file), at: now, name: me.name || '', username: me.username || '' });
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
