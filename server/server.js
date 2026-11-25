'use strict';

const { MongoClient } = require('mongodb');
const { redact } = require('./lib');
const { createApp, prepareDatabase } = require('./app');

const MONGODB_URI = process.env.MONGODB_URI;
const DB_NAME = process.env.DB_NAME || 'coneckin_db';
const PORT = process.env.PORT || 3000;
const BIND_IP = process.env.IP || process.env.HOST || '0.0.0.0';

const GITHUB_REPO = 'coneckin/coneckin';
const DOWNLOAD_URL =
  `https://github.com/${GITHUB_REPO}/releases/latest/download/coneckin.apk`;

// ---------- MongoDB ----------
let db = null;
let connecting = false;

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
    await prepareDatabase(database);
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

const app = createApp({
  getDb: () => db,
  getLatestRelease: latestRelease,
  downloadUrl: DOWNLOAD_URL,
  getAdminToken: () => process.env.ADMIN_TOKEN || ''
});

if (require.main === module) {
  process.on('unhandledRejection', (err) => {
    console.error('Unhandled rejection:', err && err.message);
  });

  app.listen(PORT, BIND_IP, () => {
    console.log(`Coneckin API berjalan di ${BIND_IP}:${PORT}`);
    connectDb();
  });
  setInterval(connectDb, 30 * 1000);
}

module.exports = { app, connectDb, latestRelease };
