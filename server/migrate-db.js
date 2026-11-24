// Salin database panlink_db ke coneckin_db (dokumen + index).
// Jalankan SEKALI dari folder server/, lalu hapus file ini. Jangan di-commit.
// Butuh variabel lingkungan MONGODB_URI (berisi password, jangan dibagikan).
const { MongoClient } = require('mongodb');

const SRC = 'panlink_db';
const DST = 'coneckin_db';

(async () => {
  if (!process.env.MONGODB_URI) throw new Error('MONGODB_URI belum diisi');
  const client = new MongoClient(process.env.MONGODB_URI);
  await client.connect();
  const src = client.db(SRC);
  const dst = client.db(DST);

  const existing = await dst.listCollections().toArray();
  if (existing.length) {
    throw new Error(`${DST} sudah berisi koleksi (${existing.map(c => c.name).join(', ')}). Dibatalkan agar data tidak dobel.`);
  }

  const collections = await src.listCollections({ type: 'collection' }).toArray();
  for (const { name } of collections) {
    const docs = await src.collection(name).find().toArray();
    if (docs.length) await dst.collection(name).insertMany(docs);

    const indexes = (await src.collection(name).indexes()).filter(i => i.name !== '_id_');
    for (const { v, ns, key, ...options } of indexes) {
      await dst.collection(name).createIndex(key, options);
    }
    const copied = await dst.collection(name).countDocuments();
    console.log(`${name}: sumber ${docs.length} dokumen, tujuan ${copied} dokumen, ${indexes.length} index disalin`);
  }

  await client.close();
  console.log('Selesai. Periksa hasilnya sebelum menghapus', SRC);
})().catch(e => { console.error(e.message); process.exit(1); });
