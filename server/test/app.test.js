'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const { ObjectId } = require('mongodb');
const { createApp, prepareDatabase } = require('../app');

const ADMIN_TOKEN = 'test-admin-secret-token';
const AUTH_HEADER = { Authorization: `Bearer ${ADMIN_TOKEN}` };
const JSON_ADMIN = { 'Content-Type': 'application/json', ...AUTH_HEADER };

// Membuat tiruan MongoDB in-memory untuk pengujian rute Express tanpa jaringan.
function createFakeDb() {
  const state = {
    portals: [
      { _id: new ObjectId(), name: 'ULBI', host: 'ypbpi.hotspot', verified: true, createdAt: new Date('2026-01-01T00:00:00Z') },
      { _id: new ObjectId(), name: 'Kampus Draft', host: 'draft.hotspot', verified: false, createdAt: new Date('2026-01-02T00:00:00Z') }
    ],
    app_config: [
      { _id: 'main', message: 'Selamat datang', minSupportedCode: 10 }
    ],
    reports: [],
    indexes: [],
    forceError: null
  };

  const matchFilter = (doc, filter) =>
    Object.entries(filter || {}).every(([k, v]) => {
      if (v instanceof ObjectId && doc[k] instanceof ObjectId) return doc[k].equals(v);
      return doc[k] === v;
    });

  const projectDoc = (doc, projection) => {
    if (!projection) return { ...doc };
    const out = {};
    for (const [k, v] of Object.entries(projection)) {
      if (v === 1 && doc[k] !== undefined) out[k] = doc[k];
    }
    return out;
  };

  const makeCursor = (list, projection) => {
    let items = list.slice();
    const cursor = {
      sort(spec) {
        const [[field, dir]] = Object.entries(spec);
        items.sort((a, b) => {
          if (a[field] < b[field]) return -1 * dir;
          if (a[field] > b[field]) return 1 * dir;
          return 0;
        });
        return cursor;
      },
      limit(n) {
        items = items.slice(0, n);
        return cursor;
      },
      async toArray() {
        return items.map((d) => projectDoc(d, projection));
      }
    };
    return cursor;
  };

  const db = {
    state,
    collection(name) {
      if (state.forceError) throw state.forceError;
      const arr = state[name];
      return {
        async createIndex(keys, opts) {
          state.indexes.push({ name, keys, opts });
        },
        async countDocuments(filter = {}) {
          return arr.filter((d) => matchFilter(d, filter)).length;
        },
        async estimatedDocumentCount() {
          return arr.length;
        },
        find(filter = {}, opts = {}) {
          return makeCursor(arr.filter((d) => matchFilter(d, filter)), opts.projection);
        },
        async findOne(filter = {}) {
          return arr.find((d) => matchFilter(d, filter)) || null;
        },
        async insertOne(doc) {
          if (name === 'portals' && arr.some((p) => p.host === doc.host)) {
            const dup = new Error('duplicate key');
            dup.code = 11000;
            throw dup;
          }
          const inserted = { _id: doc._id || new ObjectId(), ...doc };
          arr.push(inserted);
          return { insertedId: inserted._id };
        },
        async findOneAndUpdate(filter, update, opts = {}) {
          const idx = arr.findIndex((d) => matchFilter(d, filter));
          if (idx === -1) return null;
          arr[idx] = { ...arr[idx], ...(update.$set || {}) };
          return projectDoc(arr[idx], opts.projection);
        },
        async updateOne(filter, update, opts = {}) {
          const idx = arr.findIndex((d) => matchFilter(d, filter));
          if (idx === -1) {
            if (!opts.upsert) return { matchedCount: 0, modifiedCount: 0 };
            arr.push({ ...filter, ...(update.$setOnInsert || {}), ...(update.$set || {}) });
            return { matchedCount: 0, upsertedCount: 1 };
          }
          if (update.$set) arr[idx] = { ...arr[idx], ...update.$set };
          return { matchedCount: 1, modifiedCount: 1 };
        },
        async deleteOne(filter) {
          const idx = arr.findIndex((d) => matchFilter(d, filter));
          if (idx === -1) return { deletedCount: 0 };
          arr.splice(idx, 1);
          return { deletedCount: 1 };
        }
      };
    }
  };
  return db;
}

async function startTestServer(options = {}) {
  const fakeDb = options.db === undefined ? createFakeDb() : options.db;
  let currentDb = fakeDb;
  let release = options.release === undefined ? { version: '1.0.14', code: 14 } : options.release;
  let adminToken = options.adminToken === undefined ? ADMIN_TOKEN : options.adminToken;

  const noopLimiter = (req, res, next) => next();
  const app = createApp({
    getDb: () => currentDb,
    getLatestRelease: async () => release,
    downloadUrl: 'https://github.com/coneckin/coneckin/releases/latest/download/coneckin.apk',
    getAdminToken: () => adminToken,
    readLimiter: noopLimiter,
    writeLimiter: noopLimiter,
    adminLimiter: noopLimiter,
    logger: { error() {} }
  });

  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    fakeDb,
    setDb(nextDb) { currentDb = nextDb; },
    setRelease(nextRel) { release = nextRel; },
    setAdminToken(nextTok) { adminToken = nextTok; },
    close: () => new Promise((resolve) => server.close(resolve))
  };
}

// ---------- prepareDatabase ----------
test('prepareDatabase membuat indeks TTL, indeks unik host, dan seed awal bila kosong', async () => {
  const fakeDb = createFakeDb();
  fakeDb.state.portals = [];
  fakeDb.state.app_config = [];

  await prepareDatabase(fakeDb);
  assert.equal(fakeDb.state.portals.length, 1);
  assert.equal(fakeDb.state.portals[0].host, 'ypbpi.hotspot');
  assert.equal(fakeDb.state.app_config.length, 1);
  assert.equal(fakeDb.state.indexes.length, 2);

  // Memanggil ulang tidak menduplikasi seed ULBI.
  await prepareDatabase(fakeDb);
  assert.equal(fakeDb.state.portals.length, 1);
});

// ---------- Rute Publik & CORS ----------
test('GET /, GET /health, dan preflight OPTIONS CORS bekerja', async (t) => {
  const ctx = await startTestServer();
  t.after(() => ctx.close());

  const root = await fetch(`${ctx.baseUrl}/`);
  assert.equal(root.status, 200);
  assert.equal(root.headers.get('access-control-allow-origin'), '*');
  assert.equal(root.headers.get('x-content-type-options'), 'nosniff');
  assert.deepEqual(await root.json(), { name: 'Coneckin API', ok: true });

  const health = await fetch(`${ctx.baseUrl}/health`);
  assert.equal(health.status, 200);
  const hBody = await health.json();
  assert.equal(hBody.ok, true);
  assert.equal(hBody.db, true);

  const preflight = await fetch(`${ctx.baseUrl}/portals`, { method: 'OPTIONS' });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get('access-control-allow-origin'), '*');
});

// ---------- CRUD /portals ----------
test('CRUD penuh /portals: GET publik, GET all admin, POST, PUT, DELETE', async (t) => {
  const ctx = await startTestServer();
  t.after(() => ctx.close());

  // Read publik hanya mengembalikan portal verified=true
  const pubRes = await fetch(`${ctx.baseUrl}/portals`);
  assert.equal(pubRes.status, 200);
  assert.deepEqual(await pubRes.json(), {
    portals: [{ name: 'ULBI', host: 'ypbpi.hotspot' }]
  });

  // Read ?all=true ditolak tanpa token, sukses dengan token
  assert.equal((await fetch(`${ctx.baseUrl}/portals?all=true`)).status, 401);
  const allRes = await fetch(`${ctx.baseUrl}/portals?all=true`, { headers: AUTH_HEADER });
  assert.equal(allRes.status, 200);
  assert.equal((await allRes.json()).portals.length, 2);

  // Create portal baru
  const createRes = await fetch(`${ctx.baseUrl}/portals`, {
    method: 'POST',
    headers: JSON_ADMIN,
    body: JSON.stringify({ name: 'ITB', host: 'WIFI.ITB.AC.ID', verified: true })
  });
  assert.equal(createRes.status, 201);
  assert.deepEqual(await createRes.json(), {
    ok: true,
    portal: { name: 'ITB', host: 'wifi.itb.ac.id', verified: true }
  });

  // Create duplikat -> 409
  const dupRes = await fetch(`${ctx.baseUrl}/portals`, {
    method: 'POST',
    headers: JSON_ADMIN,
    body: JSON.stringify({ name: 'ITB Duplikat', host: 'wifi.itb.ac.id' })
  });
  assert.equal(dupRes.status, 409);

  // Create tidak valid -> 400
  const badCreate = await fetch(`${ctx.baseUrl}/portals`, {
    method: 'POST',
    headers: JSON_ADMIN,
    body: JSON.stringify({ name: '', host: 'bad host' })
  });
  assert.equal(badCreate.status, 400);

  // Update portal -> 200
  const putRes = await fetch(`${ctx.baseUrl}/portals/wifi.itb.ac.id`, {
    method: 'PUT',
    headers: JSON_ADMIN,
    body: JSON.stringify({ name: 'ITB Ganesha', verified: false })
  });
  assert.equal(putRes.status, 200);
  assert.deepEqual(await putRes.json(), {
    ok: true,
    portal: { name: 'ITB Ganesha', host: 'wifi.itb.ac.id', verified: false }
  });

  // Update tidak ditemukan -> 404, host tidak valid -> 400, payload kosong -> 400
  assert.equal(
    (await fetch(`${ctx.baseUrl}/portals/tidak.ada`, {
      method: 'PUT', headers: JSON_ADMIN, body: JSON.stringify({ name: 'X' })
    })).status,
    404
  );
  assert.equal(
    (await fetch(`${ctx.baseUrl}/portals/bad%20host`, {
      method: 'PUT', headers: JSON_ADMIN, body: JSON.stringify({ name: 'X' })
    })).status,
    400
  );
  assert.equal(
    (await fetch(`${ctx.baseUrl}/portals/wifi.itb.ac.id`, {
      method: 'PUT', headers: JSON_ADMIN, body: JSON.stringify({})
    })).status,
    400
  );

  // Delete portal -> 200, lalu ulang -> 404, host buruk -> 400
  const delRes = await fetch(`${ctx.baseUrl}/portals/wifi.itb.ac.id`, {
    method: 'DELETE',
    headers: AUTH_HEADER
  });
  assert.equal(delRes.status, 200);
  assert.deepEqual(await delRes.json(), { ok: true, deleted: 'wifi.itb.ac.id' });

  assert.equal(
    (await fetch(`${ctx.baseUrl}/portals/wifi.itb.ac.id`, { method: 'DELETE', headers: AUTH_HEADER })).status,
    404
  );
  assert.equal(
    (await fetch(`${ctx.baseUrl}/portals/bad%20host`, { method: 'DELETE', headers: AUTH_HEADER })).status,
    400
  );
});

// ---------- CRUD /config ----------
test('GET /config dan PUT /config (parsial & lengkap) berjalan sesuai kontrak', async (t) => {
  const ctx = await startTestServer();
  t.after(() => ctx.close());

  const g1 = await fetch(`${ctx.baseUrl}/config`);
  assert.equal(g1.status, 200);
  assert.deepEqual(await g1.json(), {
    latestVersion: '1.0.14',
    latestCode: 14,
    downloadUrl: 'https://github.com/coneckin/coneckin/releases/latest/download/coneckin.apk',
    minSupportedCode: 10,
    message: 'Selamat datang'
  });

  // Update parsial pesan saja
  const p1 = await fetch(`${ctx.baseUrl}/config`, {
    method: 'PUT',
    headers: JSON_ADMIN,
    body: JSON.stringify({ message: 'Pemeliharaan malam ini' })
  });
  assert.equal(p1.status, 200);
  assert.deepEqual(await p1.json(), {
    ok: true,
    config: { minSupportedCode: 10, message: 'Pemeliharaan malam ini' }
  });

  // Update lengkap saat dokumen belum ada (upsert)
  ctx.fakeDb.state.app_config = [];
  const p2 = await fetch(`${ctx.baseUrl}/config`, {
    method: 'PUT',
    headers: JSON_ADMIN,
    body: JSON.stringify({ message: 'Versi wajib baru', minSupportedCode: 15 })
  });
  assert.equal(p2.status, 200);
  assert.deepEqual(await p2.json(), {
    ok: true,
    config: { minSupportedCode: 15, message: 'Versi wajib baru' }
  });

  // Update tidak valid -> 400
  assert.equal(
    (await fetch(`${ctx.baseUrl}/config`, {
      method: 'PUT', headers: JSON_ADMIN, body: JSON.stringify({ minSupportedCode: -5 })
    })).status,
    400
  );

  // Release null tetap ditangani aman
  ctx.setRelease(null);
  const g2 = await fetch(`${ctx.baseUrl}/config`);
  const g2Body = await g2.json();
  assert.equal(g2Body.latestVersion, null);
  assert.equal(g2Body.latestCode, null);
});

// ---------- CRUD /reports ----------
test('POST /reports publik, GET /reports rekap admin, dan DELETE /reports/:id', async (t) => {
  const ctx = await startTestServer();
  t.after(() => ctx.close());

  const payload = {
    host: 'ypbpi.hotspot',
    code: 'LOGIN_TIMEOUT',
    androidSdk: 34,
    appCode: 14,
    appVersion: '1.0.14',
    manufacturer: 'samsung'
  };

  // Create laporan valid & invalid
  const postOk = await fetch(`${ctx.baseUrl}/reports`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  assert.equal(postOk.status, 201);
  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ host: 'bad host' })
    })).status,
    400
  );

  // Read rekap laporan (admin) + filter
  const getRep = await fetch(`${ctx.baseUrl}/reports?host=ypbpi.hotspot&code=LOGIN_TIMEOUT&limit=10`, {
    headers: AUTH_HEADER
  });
  assert.equal(getRep.status, 200);
  const repBody = await getRep.json();
  assert.equal(repBody.total, 1);
  assert.equal(repBody.count, 1);
  assert.deepEqual(repBody.summary, {
    byCode: { LOGIN_TIMEOUT: 1 },
    byHost: { 'ypbpi.hotspot': 1 }
  });

  // Query tidak valid -> 400
  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports?code=SALAH`, { headers: AUTH_HEADER })).status,
    400
  );

  // Delete laporan spesifik -> 200, ulang -> 404, ID buruk -> 400
  const savedId = String(ctx.fakeDb.state.reports[0]._id);
  const delOk = await fetch(`${ctx.baseUrl}/reports/${savedId}`, {
    method: 'DELETE',
    headers: AUTH_HEADER
  });
  assert.equal(delOk.status, 200);
  assert.deepEqual(await delOk.json(), { ok: true, deleted: savedId });

  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports/${savedId}`, { method: 'DELETE', headers: AUTH_HEADER })).status,
    404
  );
  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports/bukan-objectid`, { method: 'DELETE', headers: AUTH_HEADER })).status,
    400
  );
});

// ---------- Penanganan DB belum siap (503), 404, JSON rusak (400), payload besar (413), 500 ----------
test('Penanganan database belum siap (503), penyimpanan penuh (503), dan error handler', async (t) => {
  const ctx = await startTestServer();
  t.after(() => ctx.close());

  // 404 rute tidak dikenal
  assert.equal((await fetch(`${ctx.baseUrl}/tidak-ada`)).status, 404);

  // 400 JSON tidak valid
  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{rusak'
    })).status,
    400
  );

  // 413 payload terlalu besar (> 4kb)
  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ padding: 'x'.repeat(5000) })
    })).status,
    413
  );

  // 503 saat penyimpanan laporan mencapai MAX_REPORTS
  ctx.fakeDb.state.reports = new Array(20000);
  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        host: 'ypbpi.hotspot', code: 'TIMEOUT', androidSdk: 34,
        appCode: 14, appVersion: '1.0.14', manufacturer: 'samsung'
      })
    })).status,
    503
  );
  ctx.fakeDb.state.reports = [];

  // 500 saat error tak terduga dari database
  ctx.fakeDb.state.forceError = new Error('gagal mongodb+srv://u:p@cluster/db');
  assert.equal((await fetch(`${ctx.baseUrl}/portals`)).status, 500);
  ctx.fakeDb.state.forceError = null;

  // 503 saat database belum siap di seluruh endpoint yang butuh DB
  ctx.setDb(null);
  assert.equal((await fetch(`${ctx.baseUrl}/portals`)).status, 503);
  assert.equal((await fetch(`${ctx.baseUrl}/portals?all=true`, { headers: AUTH_HEADER })).status, 503);
  assert.equal(
    (await fetch(`${ctx.baseUrl}/portals`, {
      method: 'POST', headers: JSON_ADMIN, body: JSON.stringify({ name: 'X', host: 'x.y' })
    })).status,
    503
  );
  assert.equal(
    (await fetch(`${ctx.baseUrl}/portals/x.y`, {
      method: 'PUT', headers: JSON_ADMIN, body: JSON.stringify({ name: 'Y' })
    })).status,
    503
  );
  assert.equal(
    (await fetch(`${ctx.baseUrl}/portals/x.y`, { method: 'DELETE', headers: AUTH_HEADER })).status,
    503
  );
  assert.equal(
    (await fetch(`${ctx.baseUrl}/config`, {
      method: 'PUT', headers: JSON_ADMIN, body: JSON.stringify({ message: 'Halo' })
    })).status,
    503
  );
  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        host: 'ypbpi.hotspot', code: 'TIMEOUT', androidSdk: 34,
        appCode: 14, appVersion: '1.0.14', manufacturer: 'samsung'
      })
    })).status,
    503
  );
  assert.equal((await fetch(`${ctx.baseUrl}/reports`, { headers: AUTH_HEADER })).status, 503);
  assert.equal(
    (await fetch(`${ctx.baseUrl}/reports/507f1f77bcf86cd799439011`, { method: 'DELETE', headers: AUTH_HEADER })).status,
    503
  );
});
