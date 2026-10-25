const express = require('express');

const app = express();
const PORT = process.env.PORT || 3000;
const HOST = '0.0.0.0';
const NDOT_KEY = process.env.NDOT_KEY || '';
const CACHE_KEY = 'fieldtwin:cameras';
const CACHE_TTL = 60 * 60 * 6;
const ALLOWED = (process.env.ALLOWED_ORIGINS || '*')
  .split(',')
  .map(s => s.trim())
  .filter(Boolean);

// The Field Twin phone app loads from these addresses and is always allowed.
const APP_ORIGINS = ['http://tauri.localhost', 'https://tauri.localhost', 'tauri://localhost', 'https://field-twin-teal.vercel.app'];

process.on('unhandledRejection', err => {
  console.error('unhandled rejection', err && err.message ? err.message : err);
});
process.on('uncaughtException', err => {
  console.error('uncaught exception', err && err.message ? err.message : err);
});

let redis = null;
if (process.env.REDIS_URL) {
  try {
    const Redis = require('ioredis');
    redis = new Redis(process.env.REDIS_URL, {
      maxRetriesPerRequest: 1,
      enableOfflineQueue: false,
      retryStrategy: times => (times > 5 ? null : Math.min(times * 500, 3000))
    });
    redis.on('error', err => console.error('redis error', err.message));
    redis.on('connect', () => console.log('redis connected'));
  } catch (e) {
    console.error('redis init failed, continuing without cache', e.message);
    redis = null;
  }
} else {
  console.log('no REDIS_URL set, using in memory cache only');
}

let memoryCache = { at: 0, list: null };

app.use(express.json({ limit: '12mb' }));

app.use((req, res, next) => {
  const origin = req.headers.origin;
  if (ALLOWED.indexOf('*') !== -1) {
    res.setHeader('Access-Control-Allow-Origin', '*');
  } else if (origin && (ALLOWED.indexOf(origin) !== -1 || APP_ORIGINS.indexOf(origin) !== -1)) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Vary', 'Origin');
  }
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  res.setHeader('Access-Control-Max-Age', '86400');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

async function cacheGet() {
  if (redis && redis.status === 'ready') {
    try {
      const raw = await redis.get(CACHE_KEY);
      if (raw) return JSON.parse(raw);
    } catch (e) {
      console.error('cache read failed', e.message);
    }
  }
  if (memoryCache.list && Date.now() - memoryCache.at < CACHE_TTL * 1000) {
    return memoryCache.list;
  }
  return null;
}

async function cacheSet(list) {
  memoryCache = { at: Date.now(), list: list };
  if (redis && redis.status === 'ready') {
    try {
      await redis.set(CACHE_KEY, JSON.stringify(list), 'EX', CACHE_TTL);
    } catch (e) {
      console.error('cache write failed', e.message);
    }
  }
}

async function fetchCameras() {
  if (!NDOT_KEY) throw new Error('NDOT_KEY not configured');
  const url = 'https://www.nvroads.com/api/v2/get/cameras?format=json&key=' + encodeURIComponent(NDOT_KEY);
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error('upstream http ' + res.status);
  const raw = await res.json();
  if (!Array.isArray(raw)) throw new Error('unexpected upstream shape');
  return raw
    .filter(c => Number.isFinite(c.Latitude) && Number.isFinite(c.Longitude))
    .map(c => {
      const views = Array.isArray(c.Views) ? c.Views : [];
      const view = views.filter(v => v.Status === 'Enabled')[0] || views[0] || null;
      return {
        id: c.Id,
        roadway: c.Roadway || '',
        direction: c.Direction || '',
        lat: c.Latitude,
        lon: c.Longitude,
        stream: view ? view.VideoUrl || '' : ''
      };
    })
    .filter(c => c.stream);
}


const ANTHROPIC_KEY = process.env.ANTHROPIC_API_KEY || '';
const VISION_MODEL = process.env.VISION_MODEL || 'claude-haiku-4-5-20251001';

const RECEIPT_PROMPT = [
  'Read this receipt photo and return ONLY a JSON object, no markdown fences and no commentary.',
  'Use this exact shape, with null for anything not printed on the receipt:',
  '{"store":string|null,"address":string|null,"city":string|null,"state":string|null,',
  '"date":string|null,"time":string|null,"total":number|null,"subtotal":number|null,',
  '"tax":number|null,"saved":number|null,"payment":string|null,"card_last4":string|null,',
  '"fuel":{"gallons":number|null,"price_per_gallon":number|null,"pump":string|null}|null,',
  '"items":[{"name":string,"price":number}],"confidence":"high"|"medium"|"low"}',
  'Rules: total is the final amount charged, never the subtotal.',
  'For prepaid fuel receipts the total is the amount authorized at the pump.',
  'saved is the sum of any discount or savings lines, including per gallon discounts multiplied by gallons.',
  'date as YYYY-MM-DD. time as HH:MM in 24 hour form. Amounts as plain numbers with no currency symbol.',
  'List every purchased line item in items, in the order printed, with the name as printed and the price charged for that line. Discounts and coupons are not items.',
  'Never invent values that are not visible.'
].join(' ');

const SB_URL = process.env.SUPABASE_URL || 'https://zasyjjoypkpvyqdueums.supabase.co';
const SB_ANON = process.env.SUPABASE_ANON_KEY || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inphc3lqam95cGtwdnlxZHVldW1zIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEyMjk1NjQsImV4cCI6MjEwNjgwNTU2NH0.PZrUvG4qsTpMQeihXbbzaFYUAAJ9YV7FtxLTkcQGFVI';
const okSessions = new Map();

async function isSignedInUser(token) {
  if (!token || token.split('.').length !== 3) return false;
  const hit = okSessions.get(token);
  if (hit && hit > Date.now()) return true;
  try {
    const r = await fetch(SB_URL + '/auth/v1/user', { headers: { apikey: SB_ANON, Authorization: 'Bearer ' + token } });
    if (!r.ok) return false;
    const u = await r.json();
    if (!u || !u.id) return false;
    okSessions.set(token, Date.now() + 10 * 60 * 1000);
    if (okSessions.size > 500) okSessions.delete(okSessions.keys().next().value);
    return true;
  } catch (e) {
    return false;
  }
}

app.post('/receipts/extract', async (req, res) => {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const required = process.env.API_TOKEN || '';
  if (required && token !== required && !(await isSignedInUser(token))) return res.sendStatus(401);
  if (!ANTHROPIC_KEY) return res.status(503).json({ error: 'vision key not configured' });

  const image = req.body && req.body.image;
  if (!image || typeof image !== 'string') {
    return res.status(400).json({ error: 'image required as a data url or base64 string' });
  }

  const match = image.match(/^data:(image\/(?:jpeg|jpg|png|webp));base64,(.+)$/);
  const mediaType = match ? match[1].replace('image/jpg', 'image/jpeg') : 'image/jpeg';
  const b64 = match ? match[2] : image;

  try {
    const apiRes = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': ANTHROPIC_KEY,
        'anthropic-version': '2023-06-01'
      },
      body: JSON.stringify({
        model: VISION_MODEL,
        max_tokens: 6000,
        messages: [{
          role: 'user',
          content: [
            { type: 'image', source: { type: 'base64', media_type: mediaType, data: b64 } },
            { type: 'text', text: RECEIPT_PROMPT }
          ]
        }]
      })
    });

    if (!apiRes.ok) {
      const detail = await apiRes.text();
      console.error('vision call failed', apiRes.status, detail.slice(0, 300));
      let reason = '';
      try {
        const j = JSON.parse(detail);
        reason = (j.error && j.error.message) ? j.error.message : '';
      } catch (e) {
        reason = detail.slice(0, 160);
      }
      return res.status(502).json({
        error: 'vision call failed, status ' + apiRes.status + (reason ? ', ' + reason : ''),
        upstreamStatus: apiRes.status
      });
    }

    const data = await apiRes.json();
    const text = (data.content || [])
      .filter(b => b.type === 'text')
      .map(b => b.text)
      .join('')
      .replace(/```json|```/g, '')
      .trim();

    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch (e) {
      const braceStart = text.indexOf('{');
      const braceEnd = text.lastIndexOf('}');
      if (braceStart === -1 || braceEnd === -1) throw new Error('model did not return json');
      parsed = JSON.parse(text.slice(braceStart, braceEnd + 1));
    }

    const usage = data.usage || {};
    res.json({
      receipt: parsed,
      usage: { input: usage.input_tokens || 0, output: usage.output_tokens || 0 }
    });
  } catch (e) {
    console.error('extract failed', e.message);
    res.status(502).json({ error: e.message });
  }
});

const DEBRIEF_MODEL = process.env.DEBRIEF_MODEL || 'claude-sonnet-5-5';

const DEBRIEF_PROMPT = [
  'You are writing the end of day debrief for a field service technician who repairs parcel lockers around Las Vegas.',
  'His workday usually starts from 550 Conestoga Way in Henderson, and he commutes there from a desert camp in Arizona.',
  'Below is the day log from his location logging app, Field Twin: a summary, a timeline of entries with the place tag he chose for each, odometer readings, receipts, and things the app flagged as not captured.',
  'Some days also include his earlier debriefs and his replies to them; use those to compare this day against past days and to remember what he already explained.',
  'Write the debrief as plain prose paragraphs. No headings, no bullet points, no numbered lists, no bold, no em dashes or en dashes.',
  'He listens to this read aloud, so keep figures few and rounded, and say them in words, like about forty miles or a little over two hours. Never write digits.',
  'Never use the words honest, honestly, actually or actual. Never describe or guess at his emotional state.',
  'Cover where he went and in what order, about how long he spent at each place, how many miles he drove and how they split between work and personal when the readings allow it, and when the day started and ended.',
  'Then point out outliers worth a second look: a long stay at a food or personal stop, a stretch with no odometer reading, a day missing a start or end reading, stops with no place name or tag, a work site that took far longer or shorter than usual, and miles that do not line up with the stops.',
  'Then name what the app failed to capture today and one concrete change to how he logs, or to the app itself, that would have captured it.',
  'Finish with a single short question asking him to explain the biggest unexplained outlier, if there is one.',
  'Never invent places, times or readings that are not in the log. If the log is thin, say what is missing instead of filling it in.'
].join(' ');

async function debriefCall(model, body) {
  return fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-api-key': ANTHROPIC_KEY, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify(Object.assign({ model: model }, body))
  });
}

app.post('/debrief', async (req, res) => {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const required = process.env.API_TOKEN || '';
  if (required && token !== required && !(await isSignedInUser(token))) return res.sendStatus(401);
  if (!ANTHROPIC_KEY) return res.status(503).json({ error: 'model key not configured' });
  const log = req.body && req.body.log;
  if (!log || typeof log !== 'string') return res.status(400).json({ error: 'log required as text' });
  const history = Array.isArray(req.body.history) ? req.body.history.slice(0, 7) : [];
  let content = DEBRIEF_PROMPT + '\n\nTODAY\n' + log.slice(0, 40000);
  if (history.length) {
    content += '\n\nEARLIER DEBRIEFS AND HIS REPLIES, newest first';
    history.forEach(h => {
      if (!h || typeof h !== 'object') return;
      content += '\n\n' + String(h.day || '').slice(0, 40) + '\nDebrief: ' + String(h.debrief || '').slice(0, 3000) + '\nHis reply: ' + (String(h.reply || '').slice(0, 2000) || 'none');
    });
  }
  const body = { max_tokens: 2000, messages: [{ role: 'user', content: content }] };
  try {
    let apiRes = await debriefCall(DEBRIEF_MODEL, body);
    if (!apiRes.ok && apiRes.status >= 400 && apiRes.status < 500 && apiRes.status !== 401 && DEBRIEF_MODEL !== VISION_MODEL) {
      console.error('debrief model refused, falling back', apiRes.status);
      apiRes = await debriefCall(VISION_MODEL, body);
    }
    if (!apiRes.ok) {
      const detail = await apiRes.text();
      console.error('debrief call failed', apiRes.status, detail.slice(0, 300));
      let reason = '';
      try { const j = JSON.parse(detail); reason = j.error && j.error.message ? j.error.message : ''; } catch (e) { reason = detail.slice(0, 160); }
      return res.status(502).json({ error: 'debrief call failed, status ' + apiRes.status + (reason ? ', ' + reason : '') });
    }
    const data = await apiRes.json();
    const text = (data.content || []).filter(b => b.type === 'text').map(b => b.text).join('').trim();
    const usage = data.usage || {};
    res.json({ debrief: text, model: data.model || '', usage: { input: usage.input_tokens || 0, output: usage.output_tokens || 0 } });
  } catch (e) {
    console.error('debrief failed', e.message);
    res.status(502).json({ error: e.message });
  }
});

app.get('/', (req, res) => {
  res.json({ service: 'fieldtwin-api', endpoints: ['/health', '/cameras', '/status', '/receipts/extract', '/debrief', '/events', '/crashes'] });
});

const CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;
const PROBE_TIMEOUT_MS = 8000;
const PROBE_CONCURRENCY = 8;
const SWEEP_KEY = 'fieldtwin:sweep';
const LOCK_KEY = 'fieldtwin:sweep:lock';
const LOCK_TTL_SEC = 120;
const LOCK_RENEW_MS = 30000;
const MAX_PASSES = 3;
const INSTANCE_ID = 'inst-' + Math.random().toString(36).slice(2, 10) + '-' + process.pid;

let lockTimer = null;

let sweep = {
  id: null,
  startedAt: null,
  completedAt: null,
  total: 0,
  checked: 0,
  online: 0,
  offline: 0,
  passes: 0,
  unconfirmed: 0,
  coverage: 0,
  holder: null,
  running: false
};

async function acquireLock() {
  if (!redis || redis.status !== 'ready') {
    if (sweep.running) return false;
    return true;
  }
  try {
    const ok = await redis.set(LOCK_KEY, INSTANCE_ID, 'EX', LOCK_TTL_SEC, 'NX');
    if (ok !== 'OK') {
      const holder = await redis.get(LOCK_KEY);
      console.log('sweep lock held by ' + holder + ', standing down');
      return false;
    }
    lockTimer = setInterval(async () => {
      try {
        const current = await redis.get(LOCK_KEY);
        if (current === INSTANCE_ID) await redis.expire(LOCK_KEY, LOCK_TTL_SEC);
      } catch (e) {
        console.error('lock renew failed', e.message);
      }
    }, LOCK_RENEW_MS);
    return true;
  } catch (e) {
    console.error('lock acquire failed', e.message);
    return false;
  }
}

async function releaseLock() {
  if (lockTimer) {
    clearInterval(lockTimer);
    lockTimer = null;
  }
  if (!redis || redis.status !== 'ready') return;
  try {
    const current = await redis.get(LOCK_KEY);
    if (current === INSTANCE_ID) await redis.del(LOCK_KEY);
  } catch (e) {
    console.error('lock release failed', e.message);
  }
}

async function sweepLoad() {
  if (redis && redis.status === 'ready') {
    try {
      const raw = await redis.get(SWEEP_KEY);
      if (raw) {
        const saved = JSON.parse(raw);
        saved.running = false;
        sweep = saved;
      }
    } catch (e) {
      console.error('sweep load failed', e.message);
    }
  }
}

async function sweepSave() {
  if (redis && redis.status === 'ready') {
    try {
      await redis.set(SWEEP_KEY, JSON.stringify(sweep));
    } catch (e) {
      console.error('sweep save failed', e.message);
    }
  }
}

function sweepIsComplete() {
  return Boolean(sweep.completedAt) && sweep.unconfirmed === 0;
}

function sweepIsStale() {
  if (!sweep.completedAt) return false;
  return Date.now() - new Date(sweep.completedAt).getTime() >= CHECK_INTERVAL_MS;
}

async function probeStream(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  try {
    const res = await fetch(url, { method: 'GET', signal: controller.signal });
    if (!res.ok) return false;
    const text = await res.text();
    return text.indexOf('#EXTM3U') !== -1;
  } catch (e) {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function runHealthCheck(force) {
  const got = await acquireLock();
  if (!got) return;

  sweep.running = true;
  sweep.holder = INSTANCE_ID;
  try {
    if (!force && sweepIsComplete() && !sweepIsStale()) {
      console.log('sweep ' + sweep.id + ' complete and current, nothing due');
      return;
    }

    let list = await cacheGet();
    if (!list) list = await fetchCameras();

    const resuming = !force && sweep.id && !sweepIsComplete();
    if (!resuming) {
      sweep.id = 'sweep-' + Date.now().toString(36);
      sweep.startedAt = new Date().toISOString();
      sweep.completedAt = null;
      sweep.checked = 0;
      sweep.online = 0;
      sweep.offline = 0;
      sweep.passes = 0;
      list.forEach(c => { c.sweepId = null; });
    }
    sweep.total = list.length;
    await sweepSave();

    let pass = 0;
    while (pass < MAX_PASSES) {
      const pending = list.filter(c => c.sweepId !== sweep.id);
      if (!pending.length) break;
      pass++;
      sweep.passes++;
      console.log('sweep ' + sweep.id + ' pass ' + sweep.passes + ', ' + pending.length + ' cameras pending');

      for (let i = 0; i < pending.length; i += PROBE_CONCURRENCY) {
        const batch = pending.slice(i, i + PROBE_CONCURRENCY);
        const results = await Promise.all(batch.map(c => probeStream(c.stream).catch(() => null)));
        results.forEach((ok, idx) => {
          if (ok === null) return;
          batch[idx].online = ok;
          batch[idx].checkedAt = new Date().toISOString();
          batch[idx].sweepId = sweep.id;
          sweep.checked++;
          if (ok) sweep.online++; else sweep.offline++;
        });
        await cacheSet(list);
        await sweepSave();
      }
    }

    const confirmed = list.filter(c => c.sweepId === sweep.id).length;
    sweep.unconfirmed = list.length - confirmed;
    sweep.coverage = list.length ? Math.round((confirmed / list.length) * 100) : 0;
    sweep.completedAt = new Date().toISOString();
    await cacheSet(list);
    console.log('sweep ' + sweep.id + ' finished at ' + sweep.coverage + ' percent, ' +
      sweep.online + ' online, ' + sweep.unconfirmed + ' unconfirmed');
  } catch (e) {
    console.error('sweep failed', e.message);
  } finally {
    sweep.running = false;
    sweep.holder = null;
    await sweepSave();
    await releaseLock();
  }
}

app.get('/status', async (req, res) => {
  const list = (await cacheGet()) || [];
  const confirmed = list.filter(c => c.sweepId === sweep.id).length;
  let lockHolder = null;
  if (redis && redis.status === 'ready') {
    try { lockHolder = await redis.get(LOCK_KEY); } catch (e) {}
  }
  res.json({
    sweepId: sweep.id,
    state: sweep.running
      ? 'running'
      : (sweepIsComplete() ? 'complete' : (sweep.id ? 'partial' : 'never run')),
    startedAt: sweep.startedAt,
    completedAt: sweep.completedAt,
    passes: sweep.passes,
    total: sweep.total,
    confirmed: confirmed,
    unconfirmed: Math.max(0, list.length - confirmed),
    coveragePercent: sweep.coverage,
    online: sweep.online,
    offline: sweep.offline,
    lockHolder: lockHolder,
    thisInstance: INSTANCE_ID,
    dueForNextSweep: sweepIsStale() || (Boolean(sweep.id) && !sweepIsComplete()),
    nextSweepInHours: sweep.completedAt && sweepIsComplete()
      ? Math.max(0, Math.round((CHECK_INTERVAL_MS - (Date.now() - new Date(sweep.completedAt).getTime())) / 3600000))
      : 0
  });
});

app.get('/check', async (req, res) => {
  const secret = process.env.REFRESH_SECRET || '';
  if (!secret || req.query.secret !== secret) return res.sendStatus(403);
  runHealthCheck(req.query.force === '1');
  res.json({ started: true, forced: req.query.force === '1' });
});

app.get('/health', (req, res) => {
  res.json({
    ok: true,
    keyConfigured: Boolean(NDOT_KEY),
    redis: redis ? redis.status : 'none',
    cached: Boolean(memoryCache.list)
  });
});

app.get('/cameras', async (req, res) => {
  try {
    const cached = await cacheGet();
    if (cached) {
      res.setHeader('X-Cache', 'hit');
      return res.json({ count: cached.length, cameras: cached });
    }
    const list = await fetchCameras();
    await cacheSet(list);
    res.setHeader('X-Cache', 'miss');
    res.json({ count: list.length, cameras: list });
  } catch (e) {
    if (memoryCache.list) {
      res.setHeader('X-Cache', 'stale');
      return res.json({ count: memoryCache.list.length, cameras: memoryCache.list });
    }
    res.status(502).json({ error: e.message });
  }
});

app.get('/cameras/refresh', async (req, res) => {
  const secret = process.env.REFRESH_SECRET || '';
  if (!secret || req.query.secret !== secret) return res.sendStatus(403);
  try {
    const list = await fetchCameras();
    await cacheSet(list);
    res.json({ refreshed: list.length });
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

const AZ511_KEY = process.env.AZ511_KEY || '';
const EVENTS_HASH = 'fieldtwin:events';
const EVENTS_SEEN = 'fieldtwin:events:seen';
const EVENTS_LOCK = 'fieldtwin:events:lock';
const EVENTS_STATE = 'fieldtwin:events:state';
const EVENT_POLL_MS = 10 * 60 * 1000;
const EVENT_KEEP_DAYS = 730;
const memEvents = new Map();
let eventState = { lastPoll: null, lastOk: {}, lastError: {}, counts: {} };

function pickNum(v) { const n = Number(v); return Number.isFinite(n) ? n : null; }
function unixMs(v) {
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) return null;
  return n < 1e12 ? n * 1000 : n;
}

function trimEvent(src, e, now) {
  const type = String(e.EventType || '').trim();
  const keepLine = type !== 'accidentsAndIncidents';
  return {
    id: src + ':' + String(e.ID != null ? e.ID : e.Id),
    src: src,
    type: type,
    sub: e.EventSubType || '',
    road: e.RoadwayName || '',
    dir: e.DirectionOfTravel || '',
    desc: String(e.Description || '').slice(0, 600),
    lanes: e.LanesAffected || '',
    severity: e.Severity || '',
    closure: Boolean(e.IsFullClosure),
    lat: pickNum(e.Latitude),
    lon: pickNum(e.Longitude),
    lat2: pickNum(e.LatitudeSecondary) || null,
    lon2: pickNum(e.LongitudeSecondary) || null,
    poly: keepLine && e.EncodedPolyline ? String(e.EncodedPolyline).slice(0, 8000) : null,
    reported: unixMs(e.Reported),
    start: unixMs(e.StartDate),
    end: unixMs(e.PlannedEndDate),
    firstSeen: now,
    lastSeen: now
  };
}

async function fetchEvents(src) {
  const cfg = src === 'nv'
    ? { key: NDOT_KEY, url: 'https://www.nvroads.com/api/v2/get/event' }
    : { key: AZ511_KEY, url: 'https://az511.com/api/v2/get/event' };
  if (!cfg.key) return null;
  const res = await fetch(cfg.url + '?format=json&key=' + encodeURIComponent(cfg.key), { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error('upstream http ' + res.status);
  const raw = await res.json();
  if (!Array.isArray(raw)) throw new Error('unexpected upstream shape');
  return raw;
}

async function storeEvents(list) {
  if (redis && redis.status === 'ready') {
    const ids = list.map(e => e.id);
    const old = ids.length ? await redis.hmget(EVENTS_HASH, ...ids) : [];
    const multi = redis.multi();
    list.forEach((e, i) => {
      if (old[i]) {
        try {
          const prev = JSON.parse(old[i]);
          e.firstSeen = prev.firstSeen || e.firstSeen;
        } catch (err) {}
      }
      multi.hset(EVENTS_HASH, e.id, JSON.stringify(e));
      multi.zadd(EVENTS_SEEN, e.lastSeen, e.id);
    });
    await multi.exec();
    return;
  }
  list.forEach(e => {
    const prev = memEvents.get(e.id);
    if (prev) e.firstSeen = prev.firstSeen;
    memEvents.set(e.id, e);
  });
}

async function pruneEvents() {
  const cutoff = Date.now() - EVENT_KEEP_DAYS * 86400000;
  if (redis && redis.status === 'ready') {
    const gone = await redis.zrangebyscore(EVENTS_SEEN, 0, cutoff, 'LIMIT', 0, 5000);
    if (gone.length) {
      await redis.hdel(EVENTS_HASH, ...gone);
      await redis.zrem(EVENTS_SEEN, ...gone);
    }
    return;
  }
  for (const [k, v] of memEvents) if (v.lastSeen < cutoff) memEvents.delete(k);
}

async function pollEvents() {
  let locked = false;
  if (redis && redis.status === 'ready') {
    try {
      locked = (await redis.set(EVENTS_LOCK, INSTANCE_ID, 'EX', 300, 'NX')) === 'OK';
      if (!locked) return;
    } catch (e) { return; }
  }
  try {
    const now = Date.now();
    for (const src of ['nv', 'az']) {
      try {
        const raw = await fetchEvents(src);
        if (!raw) continue;
        const list = raw.map(e => trimEvent(src, e, now)).filter(e => e.lat !== null && e.lon !== null && !(Math.abs(e.lat) < 0.5 && Math.abs(e.lon) < 0.5) && e.type);
        await storeEvents(list);
        eventState.lastOk[src] = new Date(now).toISOString();
        eventState.counts[src] = list.length;
        delete eventState.lastError[src];
      } catch (e) {
        eventState.lastError[src] = e.message;
        console.error('event poll ' + src + ' failed', e.message);
      }
    }
    eventState.lastPoll = new Date(now).toISOString();
    await pruneEvents();
    if (redis && redis.status === 'ready') await redis.set(EVENTS_STATE, JSON.stringify(eventState));
  } finally {
    if (locked) {
      try { if ((await redis.get(EVENTS_LOCK)) === INSTANCE_ID) await redis.del(EVENTS_LOCK); } catch (e) {}
    }
  }
}

app.get('/events', async (req, res) => {
  const days = Math.max(0.04, Math.min(EVENT_KEEP_DAYS, Number(req.query.days) || 30));
  const types = String(req.query.types || 'accidentsAndIncidents,roadwork,closures').split(',').map(s => s.trim()).filter(Boolean);
  const since = Date.now() - days * 86400000;
  try {
    let list = [];
    if (redis && redis.status === 'ready') {
      const ids = await redis.zrangebyscore(EVENTS_SEEN, since, '+inf');
      for (let i = 0; i < ids.length; i += 500) {
        const chunk = await redis.hmget(EVENTS_HASH, ...ids.slice(i, i + 500));
        chunk.forEach(r => { if (r) { try { list.push(JSON.parse(r)); } catch (e) {} } });
      }
      try { const st = await redis.get(EVENTS_STATE); if (st) eventState = JSON.parse(st); } catch (e) {}
    } else {
      list = Array.from(memEvents.values()).filter(e => e.lastSeen >= since);
    }
    const activeCut = Date.now() - 2 * EVENT_POLL_MS - 60000;
    list = list.filter(e => types.indexOf(e.type) !== -1).map(e => Object.assign(e, { active: e.lastSeen >= activeCut }));
    let oldest = null;
    if (redis && redis.status === 'ready') {
      try { const o = await redis.zrange(EVENTS_SEEN, 0, 0, 'WITHSCORES'); if (o.length) oldest = new Date(Number(o[1])).toISOString(); } catch (e) {}
    }
    res.json({ days: days, count: list.length, collectingSince: oldest, state: eventState, sources: { nv: Boolean(NDOT_KEY), az: Boolean(AZ511_KEY) }, events: list });
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

const CRASH_CACHE = 'fieldtwin:crashes:v1';
const CRASH_TTL = 7 * 86400;
const CRASH_STATES = { nv: 32, az: 4 };
let crashMem = null;

function fieldOf(o, name) {
  if (o[name] !== undefined) return o[name];
  const k = Object.keys(o).find(x => x.toLowerCase() === name.toLowerCase());
  return k ? o[k] : undefined;
}

function flattenFars(j) {
  let r = j && (j.Results || j.results);
  if (!Array.isArray(r)) return [];
  if (r.length && Array.isArray(r[0])) r = [].concat(...r);
  return r;
}

async function fetchFarsYear(state, year) {
  const url = 'https://crashviewer.nhtsa.dot.gov/CrashAPI/FARSData/GetFARSData?dataset=Accident&FromYear=' + year + '&ToYear=' + year + '&State=' + state + '&format=json';
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error('fars http ' + res.status);
  const rows = flattenFars(await res.json());
  return rows.map(o => {
    const lat = pickNum(fieldOf(o, 'LATITUDE'));
    const lon = pickNum(fieldOf(o, 'LONGITUD'));
    if (lat === null || lon === null || lat < 25 || lat > 50 || lon < -130 || lon > -100) return null;
    const y = pickNum(fieldOf(o, 'YEAR')) || year, m = pickNum(fieldOf(o, 'MONTH')), d = pickNum(fieldOf(o, 'DAY'));
    const h = pickNum(fieldOf(o, 'HOUR')), mi = pickNum(fieldOf(o, 'MINUTE'));
    return {
      id: 'fars:' + state + ':' + y + ':' + (fieldOf(o, 'ST_CASE') || Math.random().toString(36).slice(2)),
      lat: Math.round(lat * 1e5) / 1e5,
      lon: Math.round(lon * 1e5) / 1e5,
      date: m && m <= 12 && d && d <= 31 ? y + '-' + String(m).padStart(2, '0') + '-' + String(d).padStart(2, '0') : String(y),
      time: h !== null && h < 24 ? String(h).padStart(2, '0') + ':' + String(mi !== null && mi < 60 ? mi : 0).padStart(2, '0') : '',
      fatals: pickNum(fieldOf(o, 'FATALS')) || 1,
      road: String(fieldOf(o, 'TWAY_ID') || '').trim(),
      vehicles: pickNum(fieldOf(o, 'VE_TOTAL'))
    };
  }).filter(Boolean);
}

async function loadCrashes(force) {
  if (!force) {
    if (crashMem && Date.now() - crashMem.at < CRASH_TTL * 1000) return crashMem.data;
    if (redis && redis.status === 'ready') {
      try { const raw = await redis.get(CRASH_CACHE); if (raw) { crashMem = { at: Date.now(), data: JSON.parse(raw) }; return crashMem.data; } } catch (e) {}
    }
  }
  const thisYear = new Date().getFullYear();
  const out = { years: [], missing: [], crashes: [] };
  for (let y = thisYear - 1; y >= thisYear - 8; y--) {
    let got = 0;
    for (const st of Object.keys(CRASH_STATES)) {
      try {
        const rows = await fetchFarsYear(CRASH_STATES[st], y);
        rows.forEach(r => { r.state = st; });
        out.crashes.push(...rows);
        got += rows.length;
      } catch (e) {
        console.error('fars ' + st + ' ' + y + ' failed', e.message);
      }
      await new Promise(r => setTimeout(r, 400));
    }
    if (got) out.years.push(y); else out.missing.push(y);
  }
  out.fetchedAt = new Date().toISOString();
  if (out.crashes.length) {
    crashMem = { at: Date.now(), data: out };
    if (redis && redis.status === 'ready') {
      try { await redis.set(CRASH_CACHE, JSON.stringify(out), 'EX', CRASH_TTL); } catch (e) {}
    }
  }
  return out;
}

let crashLoading = null;
app.get('/crashes', async (req, res) => {
  try {
    if (!crashLoading) crashLoading = loadCrashes(false).finally(() => { crashLoading = null; });
    const data = await crashLoading;
    const years = Math.max(1, Math.min(8, Number(req.query.years) || 5));
    const keep = data.years.slice(0, years);
    res.json({ years: keep, missing: data.missing, fetchedAt: data.fetchedAt, crashes: data.crashes.filter(c => keep.indexOf(Number(String(c.date).slice(0, 4))) !== -1) });
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

app.listen(PORT, HOST, () => {
  console.log('fieldtwin-api listening on ' + HOST + ':' + PORT);
  sweepLoad().then(() => {
    setTimeout(() => runHealthCheck(false), 15000);
    setInterval(() => runHealthCheck(false), 60 * 60 * 1000);
    setTimeout(pollEvents, 5000);
    setTimeout(() => loadCrashes(false).catch(() => {}), 60000);
    setInterval(pollEvents, EVENT_POLL_MS);
  });
});
