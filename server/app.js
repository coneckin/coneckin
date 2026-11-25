'use strict';

const express = require('express');
const { ObjectId } = require('mongodb');
const {
  MAX_REPORTS, REPORT_TTL_SECONDS,
  redact, normalizeHost, isValidObjectId,
  parseReport, parsePortalCreate, parsePortalUpdate,
  parseConfigUpdate, parseReportQuery, summarizeReports,
  rateLimit, requireAdmin, publicCors
} = require('./lib');

// Menyiapkan indeks TTL 90 hari, indeks unik host, dan data awal coneckin_db.
async function prepareDatabase(database) {
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

// Membuat instance Express dengan dependensi yang dapat disuntikkan saat pengujian.
function createApp({
  getDb,
  getLatestRelease,
  downloadUrl,
  getAdminToken = () => process.env.ADMIN_TOKEN || '',
  createObjectId = (id) => ObjectId.createFromHexString(id),
  readLimiter = rateLimit({ windowMs: 60 * 1000, max: 120 }),
  writeLimiter = rateLimit({ windowMs: 60 * 60 * 1000, max: 10 }),
  adminLimiter = rateLimit({ windowMs: 60 * 1000, max: 60 }),
  logger = console
}) {
  const app = express();
  const cors = publicCors();
  const adminAuth = requireAdmin(getAdminToken);

  app.disable('x-powered-by');
  app.set('trust proxy', 1);
  app.use((req, res, next) => {
    res.set('X-Content-Type-Options', 'nosniff');
    next();
  });
  app.use(express.json({ limit: '4kb' }));

  // Preflight CORS untuk endpoint baca publik (kompatibel Express 5).
  const PUBLIC_READ_PATHS = new Set(['/', '/health', '/portals', '/config']);
  app.use((req, res, next) => {
    if (PUBLIC_READ_PATHS.has(req.path) && (req.method === 'GET' || req.method === 'OPTIONS')) {
      return cors(req, res, next);
    }
    next();
  });

  // ---------- Endpoint Publik ----------
  app.get('/', (req, res) => {
    res.json({ name: 'Coneckin API', ok: true });
  });

  app.get('/health', readLimiter, (req, res) => {
    res.json({ ok: true, db: getDb() !== null, time: new Date().toISOString() });
  });

  // Read daftar portal. Default hanya verified=true (aplikasi Android & landing page).
  // Parameter ?all=true memerlukan token admin untuk melihat seluruh data termasuk unverified.
  app.get('/portals', readLimiter, async (req, res, next) => {
    try {
      const db = getDb();
      if (req.query.all === 'true') {
        return adminAuth(req, res, async () => {
          try {
            if (!db) return res.status(503).json({ error: 'database_belum_siap' });
            const portals = await db.collection('portals')
              .find({}, { projection: { _id: 0, name: 1, host: 1, verified: 1, createdAt: 1 } })
              .sort({ name: 1 })
              .limit(200)
              .toArray();
            res.json({ portals });
          } catch (err) {
            next(err);
          }
        });
      }

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

  // Create portal kampus (Admin).
  app.post('/portals', adminLimiter, adminAuth, async (req, res, next) => {
    try {
      const doc = parsePortalCreate(req.body);
      if (!doc) return res.status(400).json({ error: 'data_tidak_valid' });
      const db = getDb();
      if (!db) return res.status(503).json({ error: 'database_belum_siap' });

      await db.collection('portals').insertOne({ ...doc, createdAt: new Date() });
      res.status(201).json({ ok: true, portal: doc });
    } catch (err) {
      if (err && err.code === 11000) {
        return res.status(409).json({ error: 'portal_sudah_ada' });
      }
      next(err);
    }
  });

  // Update portal kampus berdasarkan host (Admin).
  app.put('/portals/:host', adminLimiter, adminAuth, async (req, res, next) => {
    try {
      const host = normalizeHost(req.params.host);
      if (!host) return res.status(400).json({ error: 'host_tidak_valid' });
      const patch = parsePortalUpdate(req.body);
      if (!patch) return res.status(400).json({ error: 'data_tidak_valid' });
      const db = getDb();
      if (!db) return res.status(503).json({ error: 'database_belum_siap' });

      const updated = await db.collection('portals').findOneAndUpdate(
        { host },
        { $set: patch },
        { returnDocument: 'after', projection: { _id: 0, name: 1, host: 1, verified: 1 } }
      );
      if (!updated) return res.status(404).json({ error: 'tidak_ditemukan' });
      res.json({ ok: true, portal: updated });
    } catch (err) {
      next(err);
    }
  });

  // Delete portal kampus berdasarkan host (Admin).
  app.delete('/portals/:host', adminLimiter, adminAuth, async (req, res, next) => {
    try {
      const host = normalizeHost(req.params.host);
      if (!host) return res.status(400).json({ error: 'host_tidak_valid' });
      const db = getDb();
      if (!db) return res.status(503).json({ error: 'database_belum_siap' });

      const result = await db.collection('portals').deleteOne({ host });
      if (!result || result.deletedCount === 0) {
        return res.status(404).json({ error: 'tidak_ditemukan' });
      }
      res.json({ ok: true, deleted: host });
    } catch (err) {
      next(err);
    }
  });

  // Read konfigurasi aplikasi & rilis GitHub terbaru (Publik).
  app.get('/config', readLimiter, async (req, res, next) => {
    try {
      const release = await getLatestRelease();
      const db = getDb();
      let cfg = {};
      if (db) cfg = (await db.collection('app_config').findOne({ _id: 'main' })) || {};
      res.json({
        latestVersion: release ? release.version : null,
        latestCode: release ? release.code : null,
        downloadUrl,
        minSupportedCode: Number(cfg.minSupportedCode) || 0,
        message: typeof cfg.message === 'string' ? cfg.message.slice(0, 500) : ''
      });
    } catch (err) {
      next(err);
    }
  });

  // Update konfigurasi aplikasi (Admin, upsert dokumen _id: 'main').
  app.put('/config', adminLimiter, adminAuth, async (req, res, next) => {
    try {
      const patch = parseConfigUpdate(req.body);
      if (!patch) return res.status(400).json({ error: 'data_tidak_valid' });
      const db = getDb();
      if (!db) return res.status(503).json({ error: 'database_belum_siap' });

      const defaults = {};
      if (patch.message === undefined) defaults.message = '';
      if (patch.minSupportedCode === undefined) defaults.minSupportedCode = 0;

      const updateOp = Object.keys(defaults).length > 0
        ? { $set: patch, $setOnInsert: defaults }
        : { $set: patch };

      const col = db.collection('app_config');
      await col.updateOne({ _id: 'main' }, updateOp, { upsert: true });
      const saved = (await col.findOne({ _id: 'main' })) || {};
      res.json({
        ok: true,
        config: {
          minSupportedCode: Number(saved.minSupportedCode) || 0,
          message: typeof saved.message === 'string' ? saved.message.slice(0, 500) : ''
        }
      });
    } catch (err) {
      next(err);
    }
  });

  // Create laporan masalah anonim opt-in (Publik dengan rate limit ketat).
  app.post('/reports', writeLimiter, async (req, res, next) => {
    try {
      const doc = parseReport(req.body);
      if (!doc) return res.status(400).json({ error: 'data_tidak_valid' });
      const db = getDb();
      if (!db) return res.status(503).json({ error: 'database_belum_siap' });

      const reports = db.collection('reports');
      if ((await reports.estimatedDocumentCount()) >= MAX_REPORTS) {
        return res.status(503).json({ error: 'penyimpanan_penuh' });
      }

      await reports.insertOne({ ...doc, createdAt: new Date() });
      res.status(201).json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  // Read daftar & ringkasan laporan masalah dengan filter opsional (Admin).
  app.get('/reports', adminLimiter, adminAuth, async (req, res, next) => {
    try {
      const parsed = parseReportQuery(req.query);
      if (!parsed) return res.status(400).json({ error: 'parameter_tidak_valid' });
      const db = getDb();
      if (!db) return res.status(503).json({ error: 'database_belum_siap' });

      const col = db.collection('reports');
      const total = await col.countDocuments(parsed.filter);
      const items = await col.find(parsed.filter)
        .sort({ createdAt: -1 })
        .limit(parsed.limit)
        .toArray();
      res.json({
        total,
        count: items.length,
        summary: summarizeReports(items),
        reports: items
      });
    } catch (err) {
      next(err);
    }
  });

  // Delete laporan masalah spesifik untuk moderasi (Admin).
  app.delete('/reports/:id', adminLimiter, adminAuth, async (req, res, next) => {
    try {
      const id = req.params.id;
      if (!isValidObjectId(id)) return res.status(400).json({ error: 'id_tidak_valid' });
      const db = getDb();
      if (!db) return res.status(503).json({ error: 'database_belum_siap' });

      const result = await db.collection('reports').deleteOne({ _id: createObjectId(id) });
      if (!result || result.deletedCount === 0) {
        return res.status(404).json({ error: 'tidak_ditemukan' });
      }
      res.json({ ok: true, deleted: id });
    } catch (err) {
      next(err);
    }
  });

  // ---------- 404 & Penanganan Kesalahan ----------
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
    logger.error('Kesalahan:', redact(err && err.message));
    res.status(500).json({ error: 'kesalahan_server' });
  });

  return app;
}

module.exports = { createApp, prepareDatabase };
