'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  REPORT_CODES, MAX_REPORTS, REPORT_TTL_SECONDS, redact, parseReport, rateLimit
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
