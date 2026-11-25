'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  REPORT_CODES, MAX_REPORTS, REPORT_TTL_SECONDS,
  redact, normalizeHost, isValidObjectId,
  parseReport, parsePortalCreate, parsePortalUpdate,
  parseConfigUpdate, parseReportQuery, summarizeReports,
  rateLimit, requireAdmin, publicCors
} = require('../lib');

const valid = () => ({
  host: 'ypbpi.hotspot', code: 'LOGIN_TIMEOUT', androidSdk: 34,
  appCode: 14, appVersion: '1.0.14', manufacturer: 'samsung'
});

// ---------- konstanta ----------
test('konstanta sesuai ketentuan: 10 kode, 20.000 dokumen, TTL 90 hari', () => {
  assert.equal(REPORT_CODES.size, 10);
  assert.equal(MAX_REPORTS, 20000);
  assert.equal(REPORT_TTL_SECONDS, 90 * 24 * 60 * 60);
});

// ---------- redact ----------
test('redact menyamarkan URI MongoDB termasuk srv', () => {
  assert.equal(redact('gagal mongodb://user:pw@host/db lalu'), 'gagal [uri] lalu');
  assert.equal(redact('x mongodb+srv://u:p@c.example.net/d'), 'x [uri]');
});

test('redact membiarkan pesan biasa dan menerima non-string', () => {
  assert.equal(redact('timeout'), 'timeout');
  assert.equal(redact(42), '42');
});

// ---------- parseReport ----------
test('parseReport menerima laporan valid', () => {
  assert.deepEqual(parseReport(valid()), valid());
});

test('parseReport merapikan host dan manufacturer (trim, huruf kecil)', () => {
  const doc = parseReport({ ...valid(), host: '  YPBPI.Hotspot ', manufacturer: ' Samsung ' });
  assert.equal(doc.host, 'ypbpi.hotspot');
  assert.equal(doc.manufacturer, 'samsung');
});

test('parseReport menolak body kosong atau bukan objek', () => {
  assert.equal(parseReport(undefined), null);
  assert.equal(parseReport(null), null);
  assert.equal(parseReport({}), null);
});

test('parseReport menolak kode di luar daftar tetap', () => {
  for (const code of ['', 'login gagal', 'no_wifi', 123, null]) {
    assert.equal(parseReport({ ...valid(), code }), null, String(code));
  }
});

test('parseReport menerima setiap kode yang diizinkan', () => {
  for (const code of REPORT_CODES) {
    assert.notEqual(parseReport({ ...valid(), code }), null, code);
  }
});

test('parseReport menolak host tidak valid', () => {
  const bad = ['', 'bad host', '-bad.com', 'bad.com-', 'user@evil.com', 'host:8080', 'a'.repeat(254)];
  for (const host of bad) assert.equal(parseReport({ ...valid(), host }), null, host);
});

test('parseReport menolak host non-string', () => {
  assert.equal(parseReport({ ...valid(), host: 123 }), null);
});

test('parseReport memeriksa batas androidSdk (21..60, bilangan bulat)', () => {
  assert.notEqual(parseReport({ ...valid(), androidSdk: 21 }), null);
  assert.notEqual(parseReport({ ...valid(), androidSdk: 60 }), null);
  for (const androidSdk of [20, 61, 34.5, '34', null]) {
    assert.equal(parseReport({ ...valid(), androidSdk }), null, String(androidSdk));
  }
});

test('parseReport memeriksa batas appCode (0..1.000.000, bilangan bulat)', () => {
  assert.notEqual(parseReport({ ...valid(), appCode: 0 }), null);
  assert.notEqual(parseReport({ ...valid(), appCode: 1000000 }), null);
  for (const appCode of [-1, 1000001, 1.5, '14']) {
    assert.equal(parseReport({ ...valid(), appCode }), null, String(appCode));
  }
});

test('parseReport memeriksa format appVersion', () => {
  assert.equal(parseReport({ ...valid(), appVersion: '' }), null);
  assert.equal(parseReport({ ...valid(), appVersion: 'a'.repeat(21) }), null);
  assert.equal(parseReport({ ...valid(), appVersion: '1.0 14' }), null);
  assert.notEqual(parseReport({ ...valid(), appVersion: '1.0.14-beta_2' }), null);
});

test('parseReport memeriksa format manufacturer (boleh kosong, maks 30)', () => {
  assert.notEqual(parseReport({ ...valid(), manufacturer: '' }), null);
  assert.equal(parseReport({ ...valid(), manufacturer: 'a'.repeat(31) }), null);
  assert.equal(parseReport({ ...valid(), manufacturer: 'sam<sung>' }), null);
});

test('parseReport membuang field tambahan agar tidak ada teks bebas tersimpan', () => {
  const doc = parseReport({ ...valid(), password: 'rahasia', note: 'halo' });
  assert.deepEqual(Object.keys(doc).sort(),
    ['androidSdk', 'appCode', 'appVersion', 'code', 'host', 'manufacturer']);
});

// ---------- rateLimit ----------
function fakeRes() {
  return {
    statusCode: 200, headers: {}, body: undefined,
    set(k, v) { this.headers[k] = v; return this; },
    status(c) { this.statusCode = c; return this; },
    json(b) { this.body = b; return this; }
  };
}

test('rateLimit meneruskan permintaan sampai batas lalu mengembalikan 429', () => {
  let t = 1000;
  const limiter = rateLimit({ windowMs: 60000, max: 3, now: () => t });
  let passed = 0;
  const next = () => { passed += 1; };

  for (let i = 0; i < 3; i++) limiter({ ip: '1.1.1.1' }, fakeRes(), next);
  assert.equal(passed, 3);

  const res = fakeRes();
  limiter({ ip: '1.1.1.1' }, res, next);
  assert.equal(passed, 3);
  assert.equal(res.statusCode, 429);
  assert.deepEqual(res.body, { error: 'terlalu_banyak_permintaan' });
  assert.equal(res.headers['Retry-After'], '60');
});

test('rateLimit menghitung per IP', () => {
  const limiter = rateLimit({ windowMs: 60000, max: 1, now: () => 0 });
  let passed = 0;
  const next = () => { passed += 1; };
  limiter({ ip: 'a' }, fakeRes(), next);
  limiter({ ip: 'b' }, fakeRes(), next);
  assert.equal(passed, 2);
});

test('rateLimit membuka kembali setelah jendela waktu lewat', () => {
  let t = 0;
  const limiter = rateLimit({ windowMs: 1000, max: 1, now: () => t });
  let passed = 0;
  const next = () => { passed += 1; };
  limiter({ ip: 'a' }, fakeRes(), next);
  assert.equal(fakeRes().statusCode, 200);
  const blocked = fakeRes();
  limiter({ ip: 'a' }, blocked, next);
  assert.equal(blocked.statusCode, 429);
  t = 1000;
  limiter({ ip: 'a' }, fakeRes(), next);
  assert.equal(passed, 2);
});

test('rateLimit memakai kunci "unknown" bila IP tidak ada', () => {
  const limiter = rateLimit({ windowMs: 1000, max: 1, now: () => 0 });
  let passed = 0;
  const next = () => { passed += 1; };
  limiter({}, fakeRes(), next);
  const res = fakeRes();
  limiter({}, res, next);
  assert.equal(passed, 1);
  assert.equal(res.statusCode, 429);
});

test('rateLimit membersihkan entri kedaluwarsa lewat timer berkala', (t) => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  let now = 0;
  const limiter = rateLimit({ windowMs: 1000, max: 1, now: () => now });
  let passed = 0;
  const next = () => { passed += 1; };
  limiter({ ip: 'a' }, fakeRes(), next);   // entri dibuat, reset di 1000
  now = 1500;
  t.mock.timers.tick(1000);                 // pembersihan menghapus entri lama
  limiter({ ip: 'a' }, fakeRes(), next);   // dihitung sebagai permintaan baru
  assert.equal(passed, 2);
});

// ---------- normalizeHost & isValidObjectId ----------
test('normalizeHost membersihkan huruf dan menolak input tidak valid', () => {
  assert.equal(normalizeHost('  YPBPI.Hotspot  '), 'ypbpi.hotspot');
  assert.equal(normalizeHost('a'), 'a');
  assert.equal(normalizeHost(''), null);
  assert.equal(normalizeHost('bad host'), null);
  assert.equal(normalizeHost('a'.repeat(254)), null);
  assert.equal(normalizeHost(123), null);
});

test('isValidObjectId memeriksa 24 karakter heksadesimal', () => {
  assert.equal(isValidObjectId('507f1f77bcf86cd799439011'), true);
  assert.equal(isValidObjectId('507F1F77BCF86CD799439011'), true);
  assert.equal(isValidObjectId('pendek'), false);
  assert.equal(isValidObjectId('507f1f77bcf86cd79943901g'), false);
  assert.equal(isValidObjectId(null), false);
});

// ---------- parsePortalCreate & parsePortalUpdate ----------
test('parsePortalCreate menerima input valid dan default verified=true', () => {
  assert.deepEqual(
    parsePortalCreate({ name: '  ULBI  ', host: ' YPBPI.Hotspot ' }),
    { name: 'ULBI', host: 'ypbpi.hotspot', verified: true }
  );
  assert.deepEqual(
    parsePortalCreate({ name: 'Kampus B', host: 'wifi.b.ac.id', verified: false }),
    { name: 'Kampus B', host: 'wifi.b.ac.id', verified: false }
  );
});

test('parsePortalCreate menolak input tidak valid', () => {
  assert.equal(parsePortalCreate(null), null);
  assert.equal(parsePortalCreate({ name: '', host: 'a.b' }), null);
  assert.equal(parsePortalCreate({ name: 'x'.repeat(61), host: 'a.b' }), null);
  assert.equal(parsePortalCreate({ name: 'OK', host: 'bad host' }), null);
  assert.equal(parsePortalCreate({ name: 'OK', host: 'a.b', verified: 'ya' }), null);
});

test('parsePortalUpdate menerima ubah parsial dan menolak body kosong atau tidak valid', () => {
  assert.deepEqual(parsePortalUpdate({ name: '  ULBI Baru ' }), { name: 'ULBI Baru' });
  assert.deepEqual(parsePortalUpdate({ verified: false }), { verified: false });
  assert.deepEqual(
    parsePortalUpdate({ name: 'Kampus', verified: true }),
    { name: 'Kampus', verified: true }
  );
  assert.equal(parsePortalUpdate(null), null);
  assert.equal(parsePortalUpdate({}), null);
  assert.equal(parsePortalUpdate({ name: 123 }), null);
  assert.equal(parsePortalUpdate({ name: '   ' }), null);
  assert.equal(parsePortalUpdate({ name: 'a'.repeat(61) }), null);
  assert.equal(parsePortalUpdate({ verified: 'true' }), null);
});

// ---------- parseConfigUpdate ----------
test('parseConfigUpdate menerima pesan dan minSupportedCode valid', () => {
  assert.deepEqual(parseConfigUpdate({ message: '  Info baru  ' }), { message: 'Info baru' });
  assert.deepEqual(parseConfigUpdate({ minSupportedCode: 14 }), { minSupportedCode: 14 });
  assert.deepEqual(
    parseConfigUpdate({ message: '', minSupportedCode: 0 }),
    { message: '', minSupportedCode: 0 }
  );
});

test('parseConfigUpdate menolak input kosong atau melampaui batas', () => {
  assert.equal(parseConfigUpdate(null), null);
  assert.equal(parseConfigUpdate({}), null);
  assert.equal(parseConfigUpdate({ message: 123 }), null);
  assert.equal(parseConfigUpdate({ message: 'm'.repeat(501) }), null);
  assert.equal(parseConfigUpdate({ minSupportedCode: -1 }), null);
  assert.equal(parseConfigUpdate({ minSupportedCode: 1000001 }), null);
  assert.equal(parseConfigUpdate({ minSupportedCode: 1.5 }), null);
  assert.equal(parseConfigUpdate({ minSupportedCode: '14' }), null);
});

// ---------- parseReportQuery & summarizeReports ----------
test('parseReportQuery membangun filter host, code, dan limit', () => {
  assert.deepEqual(parseReportQuery(), { filter: {}, limit: 50 });
  assert.deepEqual(
    parseReportQuery({ host: ' YPBPI.Hotspot ', code: 'TIMEOUT', limit: '25' }),
    { filter: { host: 'ypbpi.hotspot', code: 'TIMEOUT' }, limit: 25 }
  );
});

test('parseReportQuery menolak parameter tidak valid', () => {
  assert.equal(parseReportQuery({ host: 'bad host' }), null);
  assert.equal(parseReportQuery({ code: 'TIDAK_ADA' }), null);
  assert.equal(parseReportQuery({ limit: '0' }), null);
  assert.equal(parseReportQuery({ limit: '201' }), null);
  assert.equal(parseReportQuery({ limit: 'abc' }), null);
});

test('summarizeReports menghitung jumlah per kode dan per host', () => {
  const s = summarizeReports([
    { host: 'a.hotspot', code: 'TIMEOUT' },
    { host: 'a.hotspot', code: 'TIMEOUT' },
    { host: 'b.hotspot', code: 'NO_SUBMIT' }
  ]);
  assert.deepEqual(s, {
    byCode: { TIMEOUT: 2, NO_SUBMIT: 1 },
    byHost: { 'a.hotspot': 2, 'b.hotspot': 1 }
  });
});

// ---------- requireAdmin & publicCors ----------
test('requireAdmin mengembalikan 503 bila ADMIN_TOKEN belum diatur', () => {
  const mw = requireAdmin(() => '');
  const res = fakeRes();
  let called = false;
  mw({ headers: {} }, res, () => { called = true; });
  assert.equal(called, false);
  assert.equal(res.statusCode, 503);
  assert.deepEqual(res.body, { error: 'admin_belum_diatur' });
});

test('requireAdmin menolak header hilang atau token salah (401) dan meloloskan token benar', () => {
  const mw = requireAdmin(() => 'rahasia-super-kuat');

  const r1 = fakeRes();
  mw({ headers: {} }, r1, () => assert.fail('tidak boleh lolos'));
  assert.equal(r1.statusCode, 401);

  const r2 = fakeRes();
  mw({ headers: { authorization: 'Bearer salah' } }, r2, () => assert.fail('tidak boleh lolos'));
  assert.equal(r2.statusCode, 401);

  const r3 = fakeRes();
  let ok = false;
  mw({ headers: { authorization: 'Bearer rahasia-super-kuat' } }, r3, () => { ok = true; });
  assert.equal(ok, true);
});

test('publicCors mengatur header CORS dan merespons preflight OPTIONS 204', () => {
  const mw = publicCors();
  let statusSent = null;
  let nextCalled = false;
  const headers = {};
  const res = {
    set(k, v) { headers[k] = v; return this; },
    sendStatus(c) { statusSent = c; return this; }
  };

  mw({ method: 'GET' }, res, () => { nextCalled = true; });
  assert.equal(nextCalled, true);
  assert.equal(headers['Access-Control-Allow-Origin'], '*');

  mw({ method: 'OPTIONS' }, res, () => assert.fail('OPTIONS harus langsung 204'));
  assert.equal(statusSent, 204);
});
