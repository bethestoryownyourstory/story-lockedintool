// STORY trade server (Cloudflare Worker "story-trade"): connects MT4 / MT5 accounts through MetaApi - the only way an app
// can reach MT5. STORY's MetaApi key (secret METAAPI_TOKEN) stays here, never in the app. Each MT5 account added in
// STORY gets its own access key (an HMAC of the account id) that only the phone that added it holds, so a phone can
// only ever see and trade its own accounts.
//
//   POST /mt5/connect            {login, password, server, platform: 'mt5'|'mt4'}  -> {accountId, key, currency}
//   GET  /mt5/<id>/live                                       -> {balance, equity, margin, freeMargin, pl, positions[]}
//   GET  /mt5/<id>/focus?symbol=EURUSD&res=1H                 -> {quote:{bid,ask,change}, bars:[{o,h,l,c}], name}
//   GET  /mt5/<id>/symbols                                    -> {symbols:[...]}
//   POST /mt5/<id>/order         {side, type, qty, price?, sl?, tp?, symbol?}
//   POST /mt5/<id>/close         {id}
//   POST /mt5/<id>/modify        {id, sl?, tp?}      (move a position's stop loss / take profit)
//   DELETE /mt5/<id>                                          (removes it from MetaApi)
// Every /mt5/<id>/... call needs the header X-Story-Key: <key>.

const PROV = 'https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai';
const client = r => `https://mt-client-api-v1.${r || 'new-york'}.agiliumtrade.ai`;
const market = r => `https://mt-market-data-client-api-v1.${r || 'new-york'}.agiliumtrade.ai`;
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, X-Story-Key, X-Story-Device', 'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS' };
const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json', ...CORS } });

async function hmac(secret, text) {
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(text));
  return [...new Uint8Array(sig)].map(b => b.toString(16).padStart(2, '0')).join('');
}
async function meta(env, url, init = {}) {
  const r = await fetch(url, { ...init, headers: { 'auth-token': env.METAAPI_TOKEN, 'Content-Type': 'application/json', Accept: 'application/json', ...(init.headers || {}) } });
  const text = await r.text();
  let body = null; try { body = text ? JSON.parse(text) : null; } catch (e) {}
  if (!r.ok) throw { status: r.status, message: (body && (body.message || body.error)) || ('MetaApi ' + r.status) };
  return body;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const TF = { '1H': '1h', '4H': '4h', '1D': '1d', '1W': '1w' };

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    const parts = url.pathname.split('/').filter(Boolean);   // ['mt5', id|'connect', action]
    if (parts[0] !== 'mt5') return json({ ok: true, service: 'story-trade' });
    if (!env.METAAPI_TOKEN) return json({ error: 'MT5 connects once STORY’s MetaApi key is added' }, 503);
    try {
      if (parts[1] === 'connect' && req.method === 'POST') {
        const b = await req.json();
        if (!b.login || !b.password || !b.server) return json({ error: 'Login, password and server are needed' }, 400);
        const acc = await meta(env, PROV + '/users/current/accounts', { method: 'POST', body: JSON.stringify({
          name: 'STORY ' + b.login, login: String(b.login), password: b.password, server: b.server, platform: b.platform === 'mt4' ? 'mt4' : 'mt5', magic: 0, type: 'cloud-g2', application: 'MetaApi' }) });
        const id = acc.id;
        // Wait (up to ~50s) until MetaApi has signed in to the broker.
        let info = null, region = 'new-york';
        for (let i = 0; i < 25; i++) {
          const a = await meta(env, PROV + '/users/current/accounts/' + id);
          region = a.region || region;
          if (a.state === 'DEPLOYED' && a.connectionStatus === 'CONNECTED') break;
          if (a.state === 'DEPLOY_FAILED') return json({ error: 'The broker refused the login - check login, password and server' }, 400);
          await sleep(2000);
        }
        try { info = await meta(env, client(region) + '/users/current/accounts/' + id + '/account-information'); }
        catch (e) { return json({ error: 'Couldn’t sign in to that MT5 account - check login, password and server' }, 400); }
        const key = await hmac(env.METAAPI_TOKEN, id + '|' + region);
        return json({ accountId: id + '.' + region, key, currency: info.currency || 'USD' });
      }
      const [id, region] = String(parts[1] || '').split('.');
      if (!id) return json({ error: 'No account' }, 400);
      const want = await hmac(env.METAAPI_TOKEN, id + '|' + (region || 'new-york'));
      if (req.headers.get('X-Story-Key') !== want) return json({ error: 'Not your account' }, 403);
      const base = client(region) + '/users/current/accounts/' + id;
      const action = parts[2] || '';
      if (req.method === 'DELETE' && !action) { await meta(env, PROV + '/users/current/accounts/' + id, { method: 'DELETE' }); return json({ ok: true }); }
      if (action === 'live') {
        const [info, pos] = await Promise.all([meta(env, base + '/account-information'), meta(env, base + '/positions')]);
        const positions = (pos || []).map(p => ({ id: String(p.id), type: /SELL/.test(p.type) ? 'sell' : 'buy', qty: p.volume, symbol: p.symbol,
          label: (/SELL/.test(p.type) ? 'SELL ' : 'BUY ') + p.volume + ' ' + p.symbol, entry: p.openPrice, current: p.currentPrice, sl: p.stopLoss || null, tp: p.takeProfit || null,
          pl: Math.round(((p.profit || 0) + (p.swap || 0) + (p.commission || 0)) * 100) / 100, profit: ((p.profit || 0) + (p.swap || 0) + (p.commission || 0)) >= 0 }));
        return json({ status: 'ok', balance: info.balance, equity: info.equity, margin: info.margin, freeMargin: info.freeMargin, leverage: info.leverage,
          pl: Math.round(((info.equity || 0) - (info.balance || 0)) * 100) / 100, positions });
      }
      if (action === 'focus') {
        const sym = url.searchParams.get('symbol') || 'EURUSD';
        const q = await meta(env, base + '/symbols/' + encodeURIComponent(sym) + '/current-price');
        let bars = [];
        try {
          const c = await meta(env, market(region) + '/users/current/accounts/' + id + '/historical-market-data/symbols/' + encodeURIComponent(sym) + '/timeframes/' + (TF[url.searchParams.get('res')] || '1h') + '/candles?limit=150');
          bars = (c || []).map(x => ({ t: Math.floor(Date.parse(x.time) / 1000), o: x.open, h: x.high, l: x.low, c: x.close }));
        } catch (e) {}
        const first = bars.length ? bars[0].o : null;
        return json({ quote: { bid: q.bid, ask: q.ask, change: first ? ((q.bid - first) / first) * 100 : null }, bars, name: sym });
      }
      if (action === 'symbols') return json({ symbols: await meta(env, base + '/symbols') });
      if (action === 'order' && req.method === 'POST') {
        const b = await req.json();
        const t = b.type === 'limit' ? '_LIMIT' : b.type === 'stop' ? '_STOP' : '';
        const body = { actionType: 'ORDER_TYPE_' + (b.side === 'sell' ? 'SELL' : 'BUY') + t, symbol: b.symbol || url.searchParams.get('symbol') || 'EURUSD', volume: Number(b.qty) };
        if (t) body.openPrice = Number(b.price);
        if (b.sl) body.stopLoss = Number(b.sl);
        if (b.tp) body.takeProfit = Number(b.tp);
        const r = await meta(env, base + '/trade', { method: 'POST', body: JSON.stringify(body) });
        if (r && r.numericCode && ![10008, 10009, 10010].includes(r.numericCode)) return json({ error: r.message || r.stringCode || 'The broker refused the order' }, 400);
        return json({ ok: true, orderId: r && r.orderId });
      }
      if (action === 'modify' && req.method === 'POST') {
        const b = await req.json();
        const body = { actionType: 'POSITION_MODIFY', positionId: String(b.id) };
        if (b.sl != null) body.stopLoss = Number(b.sl);
        if (b.tp != null) body.takeProfit = Number(b.tp);
        const r = await meta(env, base + '/trade', { method: 'POST', body: JSON.stringify(body) });
        if (r && r.numericCode && ![10008, 10009, 10010].includes(r.numericCode)) return json({ error: r.message || 'The broker refused the change' }, 400);
        return json({ ok: true });
      }
      if (action === 'close' && req.method === 'POST') {
        const b = await req.json();
        const r = await meta(env, base + '/trade', { method: 'POST', body: JSON.stringify({ actionType: 'POSITION_CLOSE_ID', positionId: String(b.id) }) });
        if (r && r.numericCode && ![10008, 10009, 10010].includes(r.numericCode)) return json({ error: r.message || 'The broker refused to close it' }, 400);
        return json({ ok: true });
      }
      return json({ error: 'Unknown request' }, 404);
    } catch (e) {
      return json({ error: e.message || 'Something went wrong' }, e.status && e.status < 500 ? 400 : 502);
    }
  },
};
