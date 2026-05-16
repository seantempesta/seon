// Direct storage shootout: SQLite (mem + file + WAL), file-per-key, JS Map.
// Mirrors konserve's KV access pattern: SET id -> bytes, GET id -> bytes.
const {Database} = require('node-sqlite3-wasm');
const fs = require('fs');
const path = require('path');
const os = require('os');

const SIZES = [1000, 10000];
const VAL_BYTES = 256;  // konserve datahike index pages are typically ~100-500 bytes

function makeVal(i) { return Buffer.alloc(VAL_BYTES).fill(i & 0xff); }
function time(fn) { const t0 = Date.now(); fn(); return Date.now() - t0; }

function bench(label, setup, put, get, teardown) {
  const row = {label};
  for (const N of SIZES) {
    process.stdout.write(`  ${label.padEnd(22)} N=${N}... `);
    const {ctx, val} = setup(N);
    const keys = Array.from({length: N}, (_, i) => `k${i}`);
    row[`put_${N}`] = time(() => { for (let i = 0; i < N; i++) put(ctx, keys[i], val(i)); });
    process.stdout.write(`put=${String(row[`put_${N}`]).padStart(6)}ms  `);
    row[`get_${N}`] = time(() => { for (let i = 0; i < N; i++) { const r = get(ctx, keys[i]); if (!r) throw new Error('miss'); }});
    process.stdout.write(`get=${String(row[`get_${N}`]).padStart(6)}ms\n`);
    teardown(ctx);
  }
  return row;
}

const results = [];

// 1. SQLite :memory: — no WAL needed
results.push(bench('sqlite-mem',
  N => {
    const db = new Database(':memory:');
    db.run('CREATE TABLE t (k TEXT PRIMARY KEY, v BLOB)');
    const ins = db.prepare('INSERT INTO t VALUES (?, ?)');
    const sel = db.prepare('SELECT v FROM t WHERE k = ?');
    return {ctx: {db, ins, sel}, val: makeVal};
  },
  (ctx, k, v) => ctx.ins.run([k, v]),
  (ctx, k) => ctx.sel.get([k]),
  ctx => ctx.db.close()
));

// 2. SQLite file + WAL — single big BEGIN/COMMIT for batched writes
results.push(bench('sqlite-file-wal',
  N => {
    const p = path.join(os.tmpdir(), `shootout-${N}-${Date.now()}.db`);
    try { fs.unlinkSync(p); fs.unlinkSync(p + '-wal'); fs.unlinkSync(p + '-shm'); } catch {}
    const db = new Database(p);
    db.run('PRAGMA journal_mode=WAL');
    db.run('PRAGMA synchronous=NORMAL');
    db.run('CREATE TABLE t (k TEXT PRIMARY KEY, v BLOB)');
    const ins = db.prepare('INSERT INTO t VALUES (?, ?)');
    const sel = db.prepare('SELECT v FROM t WHERE k = ?');
    return {ctx: {db, ins, sel, p}, val: makeVal};
  },
  (ctx, k, v) => ctx.ins.run([k, v]),  // each insert = own txn (worst case)
  (ctx, k) => ctx.sel.get([k]),
  ctx => { ctx.db.close(); for (const ext of ['', '-wal', '-shm']) { try { fs.unlinkSync(ctx.p + ext); } catch {} }}
));

// 3. SQLite file + WAL + one BIG txn (batched writes)
results.push(bench('sqlite-file-batched',
  N => {
    const p = path.join(os.tmpdir(), `shootout-batched-${N}-${Date.now()}.db`);
    try { fs.unlinkSync(p); fs.unlinkSync(p + '-wal'); fs.unlinkSync(p + '-shm'); } catch {}
    const db = new Database(p);
    db.run('PRAGMA journal_mode=WAL');
    db.run('PRAGMA synchronous=NORMAL');
    db.run('CREATE TABLE t (k TEXT PRIMARY KEY, v BLOB)');
    db.run('BEGIN');
    const ins = db.prepare('INSERT INTO t VALUES (?, ?)');
    const sel = db.prepare('SELECT v FROM t WHERE k = ?');
    return {ctx: {db, ins, sel, p, committed: false}, val: makeVal};
  },
  (ctx, k, v) => ctx.ins.run([k, v]),
  (ctx, k) => {
    if (!ctx.committed) { ctx.db.run('COMMIT'); ctx.committed = true; }
    return ctx.sel.get([k]);
  },
  ctx => { ctx.db.close(); for (const ext of ['', '-wal', '-shm']) { try { fs.unlinkSync(ctx.p + ext); } catch {} }}
));

// 4. File-per-key (konserve.node-filestore shape)
results.push(bench('file-per-key',
  N => {
    const d = path.join(os.tmpdir(), `shootout-fpk-${N}-${Date.now()}`);
    fs.mkdirSync(d, {recursive: true});
    return {ctx: {d}, val: makeVal};
  },
  (ctx, k, v) => fs.writeFileSync(path.join(ctx.d, k), v),
  (ctx, k) => fs.readFileSync(path.join(ctx.d, k)),
  ctx => fs.rmSync(ctx.d, {recursive: true, force: true})
));

// 5. JS Map (in-memory baseline — what konserve.memory does under the hood)
results.push(bench('js-map',
  _N => ({ctx: new Map(), val: makeVal}),
  (ctx, k, v) => ctx.set(k, v),
  (ctx, k) => ctx.get(k),
  _ctx => {}
));

// print
console.log('\nstorage shootout — value size:', VAL_BYTES, 'bytes per key');
console.log('all numbers in milliseconds, lower is better\n');
const cols = ['label'].concat(SIZES.flatMap(n => [`put_${n}`, `get_${n}`]));
console.log(cols.map(c => c.padEnd(18)).join(''));
console.log('-'.repeat(18 * cols.length));
for (const r of results) {
  console.log(cols.map(c => String(r[c] ?? '').padEnd(18)).join(''));
}
