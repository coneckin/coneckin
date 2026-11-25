'use strict';

// Logika murni server: validasi CRUD, penyamaran pesan galat, CORS publik,
// otorisasi admin, dan pembatas request. Dipisah dari server.js agar mudah diuji.

const crypto = require('node:crypto');

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
const OBJECT_ID_RE = /^[0-9a-fA-F]{24}$/;

function redact(message) {
  return String(message).replace(/mongodb(\+srv)?:\/\/\S+/g, '[uri]');
}

// Membersihkan dan memvalidasi host portal. Mengembalikan null bila tidak valid.
function normalizeHost(input) {
  if (typeof input !== 'string') return null;
  const host = input.trim().toLowerCase();
  if (host.length === 0 || host.length > 253 || !HOST_RE.test(host)) return null;
  return host;
}

function isValidObjectId(id) {
  return typeof id === 'string' && OBJECT_ID_RE.test(id);
}

// Mengembalikan dokumen laporan yang sudah bersih, atau null bila isian tidak valid.
function parseReport(body) {
  const b = body || {};
  const host = normalizeHost(b.host);
  const code = typeof b.code === 'string' ? b.code : '';
  const appVersion = typeof b.appVersion === 'string' ? b.appVersion : '';
  const manufacturer =
    typeof b.manufacturer === 'string' ? b.manufacturer.trim().toLowerCase() : '';
  const androidSdk = b.androidSdk;
  const appCode = b.appCode;

  const valid =
    host !== null &&
    REPORT_CODES.has(code) &&
    Number.isInteger(androidSdk) && androidSdk >= 21 && androidSdk <= 60 &&
    Number.isInteger(appCode) && appCode >= 0 && appCode <= 1000000 &&
    VERSION_RE.test(appVersion) &&
    MAKER_RE.test(manufacturer);

  if (!valid) return null;
  return { host, code, androidSdk, appCode, appVersion, manufacturer };
}

// Validasi payload POST /portals.
function parsePortalCreate(body) {
  if (!body || typeof body !== 'object') return null;
  const host = normalizeHost(body.host);
  const name = typeof body.name === 'string' ? body.name.trim() : '';
  const verified = body.verified === undefined ? true : body.verified;

  if (!host || name.length === 0 || name.length > 60 || typeof verified !== 'boolean') {
    return null;
  }
  return { name, host, verified };
}

// Validasi payload PUT /portals/:host (minimal satu field valid: name dan/atau verified).
function parsePortalUpdate(body) {
  if (!body || typeof body !== 'object') return null;
  const patch = {};

  if (body.name !== undefined) {
    if (typeof body.name !== 'string') return null;
    const name = body.name.trim();
    if (name.length === 0 || name.length > 60) return null;
    patch.name = name;
  }
  if (body.verified !== undefined) {
    if (typeof body.verified !== 'boolean') return null;
    patch.verified = body.verified;
  }

  return Object.keys(patch).length > 0 ? patch : null;
}

// Validasi payload PUT /config (minimal satu field valid: message dan/atau minSupportedCode).
function parseConfigUpdate(body) {
  if (!body || typeof body !== 'object') return null;
  const patch = {};

  if (body.message !== undefined) {
    if (typeof body.message !== 'string') return null;
    const message = body.message.trim();
    if (message.length > 500) return null;
    patch.message = message;
  }
  if (body.minSupportedCode !== undefined) {
    const code = body.minSupportedCode;
    if (!Number.isInteger(code) || code < 0 || code > 1000000) return null;
    patch.minSupportedCode = code;
  }

  return Object.keys(patch).length > 0 ? patch : null;
}

// Validasi query parameter GET /reports (?host=&code=&limit=).
function parseReportQuery(query) {
  const q = query || {};
  const filter = {};

  if (q.host !== undefined && q.host !== '') {
    const host = normalizeHost(q.host);
    if (!host) return null;
    filter.host = host;
  }
  if (q.code !== undefined && q.code !== '') {
    if (typeof q.code !== 'string' || !REPORT_CODES.has(q.code)) return null;
    filter.code = q.code;
  }

  let limit = 50;
  if (q.limit !== undefined && q.limit !== '') {
    const parsed = Number(q.limit);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 200) return null;
    limit = parsed;
  }

  return { filter, limit };
}

// Rekap jumlah laporan berdasarkan kode kesalahan dan host portal.
function summarizeReports(items) {
  const byCode = {};
  const byHost = {};
  for (const item of items) {
    byCode[item.code] = (byCode[item.code] || 0) + 1;
    byHost[item.host] = (byHost[item.host] || 0) + 1;
  }
  return { byCode, byHost };
}

// Pembatas request per IP. `now` bisa diganti agar mudah diuji.
function rateLimit({ windowMs, max, now = Date.now }) {
  const hits = new Map();
  setInterval(() => {
    const t = now();
    for (const [key, entry] of hits) if (entry.reset <= t) hits.delete(key);
  }, windowMs).unref();

  return (req, res, next) => {
    const key = req.ip || 'unknown';
    const t = now();
    let entry = hits.get(key);
    if (!entry || entry.reset <= t) {
      entry = { count: 0, reset: t + windowMs };
      hits.set(key, entry);
    }
    entry.count += 1;
    if (entry.count > max) {
      res.set('Retry-After', String(Math.ceil((entry.reset - t) / 1000)));
      return res.status(429).json({ error: 'terlalu_banyak_permintaan' });
    }
    next();
  };
}

function sha256(text) {
  return crypto.createHash('sha256').update(text, 'utf8').digest();
}

// Middleware otorisasi token admin (Authorization: Bearer <ADMIN_TOKEN>).
// Memakai timingSafeEqual atas hash SHA-256 agar panjang token tidak bocor.
function requireAdmin(getToken) {
  return (req, res, next) => {
    const expected = typeof getToken === 'function' ? getToken() : '';
    if (typeof expected !== 'string' || expected.trim().length === 0) {
      return res.status(503).json({ error: 'admin_belum_diatur' });
    }
    const header = typeof req.headers?.authorization === 'string'
      ? req.headers.authorization
      : '';
    const match = /^Bearer\s+(.+)$/i.exec(header.trim());
    if (!match) {
      return res.status(401).json({ error: 'tidak_diizinkan' });
    }
    const provided = match[1].trim();
    if (!crypto.timingSafeEqual(sha256(provided), sha256(expected.trim()))) {
      return res.status(401).json({ error: 'tidak_diizinkan' });
    }
    next();
  };
}

// Middleware CORS untuk endpoint baca publik (dikonsumsi landing page coneckin.github.io).
function publicCors() {
  return (req, res, next) => {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'GET, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type');
    if (req.method === 'OPTIONS') return res.sendStatus(204);
    next();
  };
}

module.exports = {
  MAX_REPORTS, REPORT_TTL_SECONDS, REPORT_CODES,
  HOST_RE, VERSION_RE, MAKER_RE, OBJECT_ID_RE,
  redact, normalizeHost, isValidObjectId,
  parseReport, parsePortalCreate, parsePortalUpdate,
  parseConfigUpdate, parseReportQuery, summarizeReports,
  rateLimit, requireAdmin, publicCors
};
