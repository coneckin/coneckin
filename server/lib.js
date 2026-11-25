'use strict';

// Logika murni server: validasi, penyamaran pesan galat, dan pembatas request.
// Dipisah dari server.js supaya bisa diuji tanpa Express maupun MongoDB.

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

// Mengembalikan dokumen laporan yang sudah bersih, atau null bila isian tidak valid.
function parseReport(body) {
  const b = body || {};
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

  if (!valid) return null;
  return { host, code, androidSdk, appCode, appVersion, manufacturer };
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

module.exports = {
  MAX_REPORTS, REPORT_TTL_SECONDS, REPORT_CODES,
  HOST_RE, VERSION_RE, MAKER_RE,
  redact, parseReport, rateLimit
};
