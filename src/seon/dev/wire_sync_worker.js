// Dumb byte-exchange worker for seon.dev.wire-sync (Track 2, unit 2.1).
//
// The MAIN thread owns ALL protocol logic (CBOR envelope encode/decode via
// seon.dev.cbor, Transit value strings via seon.client-runtime.wit). This
// worker only: connects to a Unix-domain socket, writes one pre-framed
// request (4-byte BE length + CBOR payload, already assembled by the main
// thread), reads one length-framed reply, and posts the reply PAYLOAD bytes
// back. No CBOR, no Transit, no envelope knowledge — so there is exactly one
// implementation of the wire protocol (the CLJS one), per the no-parallel-
// implementations rule.
//
// Sync bridge contract (synckit pattern):
//   workerData = { port: MessagePort, sab: SharedArrayBuffer(4) }
//   request  (on port): { sock: string, bytes: Uint8Array, timeoutMs: number }
//   response (on port): { ok: true, bytes: Uint8Array }      — reply payload
//                     | { ok: false, error: string }
//   After posting the response the worker does Atomics.store(i32,0,1) +
//   Atomics.notify so the main thread's Atomics.wait wakes and drains the
//   port with receiveMessageOnPort. The main thread is blocked for the whole
//   round-trip, so requests are strictly sequential — no id correlation
//   needed.

'use strict';

const { workerData } = require('node:worker_threads');
const net = require('node:net');

const port = workerData.port;
const i32 = new Int32Array(workerData.sab);

function rpcBytes(sockPath, bytes, timeoutMs) {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection(sockPath);
    let settled = false;
    let buf = Buffer.alloc(0);
    let need = null;

    const timer = setTimeout(() => {
      done(new Error('wire-sync worker: rpc timeout'), null);
    }, timeoutMs);

    function done(err, val) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      sock.destroy();
      if (err) reject(err); else resolve(val);
    }

    sock.on('error', (e) => done(e, null));
    sock.on('connect', () => sock.write(Buffer.from(bytes)));
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (need === null && buf.length >= 4) {
        need = buf.readUInt32BE(0);
        buf = buf.subarray(4);
      }
      if (need !== null && buf.length >= need) {
        done(null, buf.subarray(0, need));
      }
    });
    sock.on('end', () => done(new Error('wire-sync worker: socket closed before reply'), null));
  });
}

port.on('message', (msg) => {
  rpcBytes(msg.sock, msg.bytes, msg.timeoutMs || 10000)
    .then((payload) => port.postMessage({ ok: true, bytes: payload }))
    .catch((e) => port.postMessage({ ok: false, error: String((e && e.message) || e) }))
    .finally(() => {
      Atomics.store(i32, 0, 1);
      Atomics.notify(i32, 0);
    });
});
