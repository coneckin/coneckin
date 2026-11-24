'use strict';

const express = require('express');
const { MongoClient } = require('mongodb');

const MONGODB_URI = process.env.MONGODB_URI;
const DB_NAME = process.env.DB_NAME || 'coneckin_db';
const PORT = process.env.PORT || 3000;
const BIND_IP = process.env.IP || process.env.HOST || '0.0.0.0';

const GITHUB_REPO = 'coneckin/coneckin';
const DOWNLOAD_URL =
  `https://github.com/${GITHUB_REPO}/releases/latest/download/coneckin.apk`;

const MAX_REPORTS = 20000;
const REPORT_TTL_SECONDS = 90 * 24 * 60 * 60;

// Kode hasil yang boleh dilaporkan. Tidak ada teks bebas, supaya tidak ada
// data pribadi yang bisa ikut terkirim.
const REPORT_CODES = new Set([
  'NO_WIFI', 'UNKNOWN_PORTAL', 'LOAD_FAILED', 'TIMEOUT', 'HOST_MISMATCH',
  'NO_USER_FIELD', 'NO_SUBMIT', 'NO_PASSWORD_FIELD', 'LOGIN_TIMEOUT', 'OTHER'
]);
const HOST_RE = /^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$/;
const VERSION_RE = /^[0-9A-Za-z._-]{1,20}$/;
const MAKER_RE = /^[a-z0-9 _-]{0,30}$/;

function redact(message) {
  return String(message).replace(/mongodb(\+srv)?:\/\/\S+/g, '[uri]');
}

// ---------- MongoDB ----------
let db = null;
let connecting = false;

async function prepare(database) {
  await database.collection('reports')
    .createIndex({ createdAt: 1 }, { expireAfterSeconds: REPORT_TTL_SECONDS });
  const portals = database.collection('portals');
  await portals.createIndex({ host: 1 }, { unique: true });
  if ((await portals.countDocuments()) === 0) {
    await portals.insertOne({
      name: 'ULBI',
      host: 'ypbpi.hotspot',
      verified: true,
      createdAt: new Date()
    });
  }
  await database.collection('app_config').updateOne(
    { _id: 'main' },
    { $setOnInsert: { message: '', minSupportedCode: 0 } },
    { upsert: true }
  );
}

async function connectDb() {
  if (db || connecting) return;
  if (!MONGODB_URI) {
    console.error('MONGODB_URI belum diatur.');
    return;
  }
  connecting = true;
  try {
    const client = new MongoClient(MONGODB_URI, { serverSelectionTimeoutMS: 8000 });
    await client.connect();
    const database = client.db(DB_NAME);
    await prepare(database);
    db = database;
    console.log('MongoDB terhubung.');
  } catch (err) {
    console.error('Gagal terhubung ke MongoDB:', err.name, redact(err.message));
  } finally {
    connecting = false;
  }
}

// ---------- Versi terbaru dari GitHub Releases (cache 10 menit) ----------
let releaseCache = { until: 0, data: null };

async function latestRelease() {
  const now = Date.now();
  if (now < releaseCache.until) return releaseCache.data;
  try {
    const res = await fetch(
      `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`,
      {
        headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'coneckin-api' },
        signal: AbortSignal.timeout(5000)
      }
    );
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const json = await res.json();
    const m = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(String(json.tag_name || ''));
    const data = m ? { version: `${m[1]}.${m[2]}.${m[3]}`, code: Number(m[3]) } : null;
    releaseCache = { until: now + 10 * 60 * 1000, data };
  } catch (err) {
    console.error('Gagal membaca rilis GitHub:', err.message);
    releaseCache = { until: now + 60 * 1000, data: releaseCache.data };
  }
  return releaseCache.data;
}

// ---------- Pembatas request per IP ----------
function rateLimit({ windowMs, max }) {
  const hits = new Map();
  setInterval(() => {
    const now = Date.now();
    for (const [key, entry] of hits) if (entry.reset <= now) hits.delete(key);
  }, windowMs).unref();

  return (req, res, next) => {
    const key = req.ip || 'unknown';
    const now = Date.now();
    let entry = hits.get(key);
    if (!entry || entry.reset <= now) {
      entry = { count: 0, reset: now + windowMs };
      hits.set(key, entry);
    }
    entry.count += 1;
    if (entry.count > max) {
      res.set('Retry-After', String(Math.ceil((entry.reset - now) / 1000)));
      return res.status(429).json({ error: 'terlalu_banyak_permintaan' });
    }
    next();
  };
}

const readLimiter = rateLimit({ windowMs: 60 * 1000, max: 120 });
const writeLimiter = rateLimit({ windowMs: 60 * 60 * 1000, max: 10 });

// ---------- Aplikasi ----------
const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);
app.use((req, res, next) => {
  res.set('X-Content-Type-Options', 'nosniff');
  next();
});
app.use(express.json({ limit: '2kb' }));

app.get('/', (req, res) => {
  res.json({ name: 'Coneckin API', ok: true });
});

app.get('/health', readLimiter, (req, res) => {
  res.json({ ok: true, db: db !== null, time: new Date().toISOString() });
});

app.get('/portals', readLimiter, async (req, res, next) => {
  try {
    if (!db) return res.status(503).json({ error: 'database_belum_siap' });
    const portals = await db.collection('portals')
      .find({ verified: true }, { projection: { _id: 0, name: 1, host: 1 } })
      .sort({ name: 1 })
      .limit(200)
      .toArray();
    res.json({ portals });
  } catch (err) {
    next(err);
  }
});

app.get('/config', readLimiter, async (req, res, next) => {
  try {
    const release = await latestRelease();
    let cfg = {};
    if (db) cfg = (await db.collection('app_config').findOne({ _id: 'main' })) || {};
    res.json({
      latestVersion: release ? release.version : null,
      latestCode: release ? release.code : null,
      downloadUrl: DOWNLOAD_URL,
      minSupportedCode: Number(cfg.minSupportedCode) || 0,
      message: typeof cfg.message === 'string' ? cfg.message.slice(0, 500) : ''
    });
  } catch (err) {
    next(err);
  }
});

app.post('/reports', writeLimiter, async (req, res, next) => {
  try {
    const b = req.body || {};
    const host = typeof b.host === 'string' ? b.host.trim().toLowerCase() : '';
    const code = typeof b.code === 'string' ? b.code : '';
    const appVersion = typeof b.appVersion === 'string' ? b.appVersion : '';
    const manufacturer =
      typeof b.manufacturer === 'string' ? b.manufacturer.trim().toLowerCase() : '';
    const androidSdk = b.androidSdk;
    const appCode = b.appCode;

    const valid =
      host.length > 0 && host.length <= 253 && HOST_RE.test(host) &&
      REPORT_CODES.has(code) &&
      Number.isInteger(androidSdk) && androidSdk >= 21 && androidSdk <= 60 &&
      Number.isInteger(appCode) && appCode >= 0 && appCode <= 1000000 &&
      VERSION_RE.test(appVersion) &&
      MAKER_RE.test(manufacturer);

    if (!valid) return res.status(400).json({ error: 'data_tidak_valid' });
    if (!db) return res.status(503).json({ error: 'database_belum_siap' });

    const reports = db.collection('reports');
    if ((await reports.estimatedDocumentCount()) >= MAX_REPORTS) {
      return res.status(503).json({ error: 'penyimpanan_penuh' });
    }

    await reports.insertOne({
      host, code, androidSdk, appCode, appVersion, manufacturer,
      createdAt: new Date()
    });
    res.status(201).json({ ok: true });
  } catch (err) {
    next(err);
  }
});

app.use((req, res) => {
  res.status(404).json({ error: 'tidak_ditemukan' });
});

app.use((err, req, res, next) => {
  if (err && err.type === 'entity.too.large') {
    return res.status(413).json({ error: 'terlalu_besar' });
  }
  if (err && (err.type === 'entity.parse.failed' || err.status === 400)) {
    return res.status(400).json({ error: 'json_tidak_valid' });
  }
  console.error('Kesalahan:', err && err.message);
  res.status(500).json({ error: 'kesalahan_server' });
});

process.on('unhandledRejection', (err) => {
  console.error('Unhandled rejection:', err && err.message);
});

app.listen(PORT, BIND_IP, () => {
  console.log(`Coneckin API berjalan di ${BIND_IP}:${PORT}`);
  connectDb();
});
setInterval(connectDb, 30 * 1000);