---
type: research
status: active
tags: [research, agent, database]
---

# Wire serialization research — Transit usage, msgpack-on-CLJS reality, efficient CLJ↔CLJS, UDS→cloud

Read-only analysis pass. Backed by the VENDORED Transit source
(`reference-code/transit-clj`, `reference-code/transit-cljs`,
`reference-code/transit-js`) + live wire code (`src/seon/server/{transit,codec}.clj`,
`src/seon/store/wire.cljs`, `src/seon/store/internal/{wire_node,cbor}.cljs`) + web
citations. NO src/test edits, no submodule changes, no process restarts (per scope).

## TL;DR — recommendation

- **Encoding: stay on Transit-JSON for values; adopt full Transit-JSON for the
  envelope too (the migration plan's Option A).** Do NOT chase a binary format for
  the cross-language hop.
- **`:msgpack` on the CLJS/JS side DOES NOT EXIST. It is OFF THE TABLE — proven from
  source.** `transit-js` throws `Error("Type must be \"json\"")` for any writer type
  other than `json`/`json-verbose` (transit.js:122-135), and `transit-cljs`'s own
  `reader`/`writer` docstrings say "type may be either :json or :json-verbose"
  (transit.cljs:107,213). Only `transit-clj` (JVM) supports `:msgpack`
  (transit-clj/src/cognitect/transit.clj:157,166,306,317). A `:msgpack` writer on the
  JVM would emit bytes the pod **cannot decode** → breaks CLJ↔CLJS compat, which is
  the hard requirement. So the "more efficient library / msgpack" idea the owner
  recalls is a JVM-only capability that was never reachable across this seam.
- **Is current Transit usage correct? Mostly YES, with two real smells** (writer
  memoization on the CLJS side is *safe per the cache-clear contract* but masks a
  thread-safety assumption; and the `transit-then-EDN` `read-T` fallback + the dead
  `"payload"` round-trip in `wire_node`'s `transact` are vestigial). Details in §1.
- **The realistic "more efficient" win that ACTUALLY works on both sides is already
  what we use: Transit-JSON (compact mode), not `json-verbose`.** Transit-JSON's
  caching + compact tags are dramatically smaller than verbose. We are NOT on
  verbose anywhere — good. Quantified in §2.
- **A genuinely-binary alternative that works both sides exists — Fressian via
  `fress` (CLJS) + `clojure.data.fressian` (JVM)** — and notably **datahike's own
  konserve store already uses fressian/fress** on disk. But for this RPC seam the
  maintenance + complexity cost is not worth it now (Transit's docs themselves steer
  binary-persistence→Fressian, transport→Transit). Recommendation + tradeoffs in §3.
- **UDS-now verdict: KEEP UDS.** The codec is already transport-agnostic
  (length-prefixed bytes over a generic stream socket). UDS→TCP/cloud is a localized
  change (connection target + listener address), NOT a protocol change. The Transit
  migration is forward-compatible to a cloud writer. What to keep clean now in §4.

---

## 1. Are we using Transit CORRECTLY today?

### What the code does

**JVM (`src/seon/server/transit.clj`):** per-call writer/reader, each over a fresh
`ByteArrayOutputStream`/`ByteArrayInputStream`, `:json`:

```clojure
(defn write-str [v]
  (let [out (ByteArrayOutputStream. 256)
        w (t/writer out :json)]
    (t/write w v)
    (.toString out "UTF-8")))
```

**CLJS (`src/seon/store/internal/wire_node.cljs:48-69`):** ONE memoized writer + ONE
memoized reader, reused for every call:

```clojure
(defonce ^:private !writer (atom nil))
(defonce ^:private !reader (atom nil))
(defn- writer [] (or @!writer (reset! !writer (t/writer :json))))
(defn- reader [] (or @!reader (reset! !reader (t/reader :json))))
(defn T [v] (t/write (writer) v))
(defn readT [s] (when (and s (not= "" s) (not= "null" s)) (t/read (reader) s)))
```

### Verdict per concern

**(a) Writer/reader reuse vs per-call — CORRECT on both sides, for different reasons,
and the reuse is the *intended* pattern.** The transit-js Writer/Reader clear their
per-message cache at the end of every operation:

- `transit-js/src/com/cognitect/transit/impl/writer.js:497-512` —
  `Writer.prototype.write` ends with `if (this.cache != null) { this.cache.clear(); }`.
- `transit-js/src/com/cognitect/transit/impl/reader.js:58-62` —
  `Reader.prototype.read` ends with `this.cache.clear();`.

So a single CLJS writer/reader instance is **safe to reuse across many independent
RPCs** — no key-cache state leaks between messages. This is exactly what `wire_node`
does, and it's *more* correct (less allocation) than re-constructing each call. The
JVM `transit-clj` writer is bound to an `OutputStream` at construction, so it CANNOT
be trivially reused for a different stream; per-call construction there is the normal
JVM idiom. **No misuse.** One latent assumption to note: the memoized CLJS reader/writer
is single-instance, which is fine because Node is single-threaded and the pod is
`^:async`/await (CLAUDE.md: "the pod is core.async-free … native CLJS ^:async/await").
If a future build ever ran Transit off worker threads, the shared instance would need
revisiting — worth a one-line comment, not a change now.

**(b) Handler / caching config — CORRECT, defaults.** Neither side installs custom
handlers (`{:handlers …}` absent), so keywords/symbols/sets/uuids/instants/ratios use
transit's built-in handlers. The default CLJS handler set is comprehensive
(transit-cljs/src/cognitect/transit.cljs:223-269 registers keyword, symbol, all the
list/seq types, the three map types, both set types, vector/subvec, uuid, with-meta).
Caching is left ON (default) — correct; turning it off would *bloat* output.

**(c) Keyword / instant / ratio handling — CORRECT for VALUES, but note the envelope
caveat.** Inside the value payload, Transit handles keywords (`":"` tag), symbols
(`"$"`), instants, ratios, BigInts faithfully on both sides — this is precisely why
`transit.clj`'s docstring chose Transit over `pr-str`/EDN. HOWEVER, the **control
envelope keys are plain strings** (`codec.clj`'s `->java` flattens `:ns/name`→
`"ns/name"`, `wire_node`'s `enc-map` does `(name k)`), so envelope keys can't be
keywords by construction. This is the CBOR-flattening the migration plan's Option A
removes. Not a *misuse* of Transit (the envelope isn't Transit today, it's CBOR), but
it IS the stringly-typed seam the owner wants gone.

**(d) The Transit-then-EDN fallback in `read-T` — SMELL, vestigial.** NOTE: the
fallback the plan references lives in `seon.server.wire` (JVM `read-T`), not in
`server/transit.clj` (which is the clean two-fn codec). `server/transit.clj`'s
`read-str` is correct: returns nil for nil/empty (the "omitted" convention),
straight Transit otherwise. The EDN fallback only exists so tests can pass raw EDN
strings for `"tx-data"`; it is dead in production and the migration plan already
schedules its removal. Flag, not fix (out of this task's scope).

**(e) Two more smells worth flagging (REPORT, not fix):**

- **`wire_node.cljs:149` `transact` reads `(readT (get resp "payload"))`, but the
  actual pod writer `store/wire.cljs` (the `SeonWireWriter`, lines 257-278) reads
  structured fields (`"basis-t"`, `"tx-data"`, `"tempids"`, `"tx-meta"`) and never
  touches `"payload"`.** So `wire_node`'s `transact` decode path is a *different,
  older* contract than the live pod path. `wire_node` is documented as the prototype
  ("the prototype the pod's db path will later route through"), so the divergence is
  expected — but it means `wire_node`'s `transact`/`q`/`pull` readers are NOT the
  production decoders and shouldn't be treated as the spec. The migration plan's "drop
  `payload`" item should reconcile these so there is ONE decode contract.
- **`knn-search` sends `"query"` as a PLAIN CBOR string while `q` sends `"query"` as
  Transit** (`wire_node.cljs:157` `q` does `(T query)`; `wire_node.cljs:182`
  `knn-search` sends `"query" query` raw). Same envelope key name, two encodings.
  Under full-Transit Option A both become native — verify the JVM knn handler reads it
  as a string, not via `read-T`. (The migration plan already lists this as risk #5.)

**Bottom line: Transit itself is used correctly. The smells are around the *envelope*
(CBOR string keys, EDN fallback, the prototype/production decode divergence), which is
exactly what the Transit-envelope migration is for.**

---

## 2. `:msgpack` vs `:json` — the CLJS support reality (the crux)

**`:msgpack` is NOT implemented on the JS/CLJS side. Proven from vendored source.**

`transit-js` — the engine under `transit-cljs` — only knows JSON. Both constructors
hard-reject anything else:

`reference-code/transit-js/src/com/cognitect/transit.js:89-97` (reader):

```javascript
transit.reader = function(type, opts) {
    if(type === "json" || type === "json-verbose" || type == null) {
        type = "json";
        var unmarshaller = new reader.JSONUnmarshaller(opts);
        return new reader.Reader(unmarshaller, opts);
    } else {
        throw new Error("Cannot create reader of type " + type);
    }
};
```

`reference-code/transit-js/src/com/cognitect/transit.js:122-135` (writer):

```javascript
transit.writer = function(type, opts) {
    if(type === "json" || type === "json-verbose" || type == null) {
        if(type === "json-verbose") { ... opts["verbose"] = true; }
        var marshaller = new writer.JSONMarshaller(opts);
        return new writer.Writer(marshaller, opts);
    } else {
        var err = new Error("Type must be \"json\"");
        err.data = {type: type};
        throw err;
    }
};
```

The README states it outright,
`reference-code/transit-js/src/com/cognitect/transit.js`'s sibling README.md:10-12:

> "transit-js does **not currently support encoding to MessagePack**. Unlike the Java
> and Clojure implementations it relies on the non-streaming JSON parsing mechanism of
> the host JavaScript environment."

There is exactly ONE hit for "msgpack" across all of `transit-js/src` — that README
sentence (no `JSONMarshaller`-equivalent `MsgpackMarshaller`, no msgpack
reader/writer registry). `grep -rin msgpack transit-js/src` → empty.

`transit-cljs` confirms it at the API layer — its own docstrings only offer JSON:

`reference-code/transit-cljs/src/cognitect/transit.cljs:106-107`:

> "Return a transit reader. type may be either :json or :json-verbose."

`reference-code/transit-cljs/src/cognitect/transit.cljs:212-213`:

> "Return a transit writer. type maybe either :json or :json-verbose."

By contrast, `transit-clj` (JVM) **does** support msgpack — which is the trap:

`reference-code/transit-clj/src/cognitect/transit.clj:157,166` (writer):

```clojure
(if (#{:json :json-verbose :msgpack} type) ...
  (throw (ex-info "Type must be :json, :json-verbose or :msgpack" {:type type})))
```

(reader is symmetric at :306,:317). So if anyone "optimized" the JVM writer to
`:msgpack`, the wire-server would emit msgpack frames the pod's transit-cljs reader
would reject with `Cannot create reader of type msgpack` (or, since we'd never even
get to construct one, the pod just can't parse). **`transit-msgpack` BREAKS CLJ↔CLJS
compatibility and is off the table.** This matches the official Cognitect position
(alexmiller, Clojure Q&A): *"json was the priority for js/cljs in Transit because
there are very high performance native json parsers available in the browser."*
msgpack-cljs was a deliberate non-goal, not an oversight.

### The realistic efficient option that works on BOTH sides: Transit-JSON compact (what we already use)

Transit's own efficiency lever on the JS/CLJS side is **compact JSON vs
`json-verbose`**, not a binary format. Compact mode (`:json`) uses:

- short cache codes for repeated map keys / keywords (the WriteCache,
  `caching.MIN_SIZE_CACHEABLE = 3` — keys ≥3 chars get cached and replaced by a 1-2
  char back-reference on repeat, `transit-js/.../caching.js`),
- the array-as-map representation (`["^ ", k, v, …]`,
  transit-cljs:272-277 `:objectBuilder`) instead of verbose `{"k": v}`.

`json-verbose` disables the cache and uses plain JSON objects — strictly larger,
especially for repeated namespaced keyword keys (e.g. our `:seon.store.wire/write-id`
appearing in every datom/tx). For a payload of N maps sharing the same K namespaced
keys, verbose pays the full key string N×K times; compact pays it once then a
~1-char code. **We are already on `:json` (compact) on both sides — good, no change
needed; just do NOT regress to `json-verbose`.** That single choice is the realistic
"more efficient" answer for this seam.

---

## 3. Is there a known BETTER way for efficient CLJ↔CLJS wire serialization?

Survey of the actually-cross-platform options, with tradeoffs:

| Option | CLJ side | CLJS side | Efficiency | Compat | Maintenance | Verdict |
|---|---|---|---|---|---|---|
| **Transit-JSON (compact)** | transit-clj (dep) | transit-cljs (dep) | Good (cached keys, compact tags); text not binary | Bulletproof, Cognitect-blessed, both already deps | Stable for a decade | **RECOMMEND — use for envelope + values** |
| Transit-msgpack | transit-clj ✅ | **NONE** ❌ | Binary, smaller | **BROKEN — no JS reader** | n/a | **OFF THE TABLE** |
| **Fressian** | `clojure.data.fressian` | `fress` (`com.github.pkpkpk/fress`) | Binary, UTF-8 string compression, smallest | Works both sides; `fress` "wraps clojure.data.fressian, drop-in replacement" | `fress` actively published, but smaller community; **no BigDecimal/Ratio in CLJS yet**, symbol munging in advanced builds needs manual record maps | Viable but heavier; **defer** |
| `fressian-cljs` (kawasima) | data.fressian | fressian-cljs | Binary | Older, less active than `fress` | Low activity | Not recommended (use `fress` if Fressian at all) |
| Raw msgpack-cljs + custom Clojure-type layer | custom | a JS msgpack lib | Binary | You'd reinvent Transit's tag system (keywords/sets/instants/ratios) by hand | High DIY burden | Anti-pattern (rebuilds Transit badly) |

**Key facts:**

- transit-clj + transit-cljs are **already deps on both sides** (zero new dependency
  to standardize on Transit-JSON). Web confirms the default split: *"The default is
  json for ClojureScript and msgpack for Clojure"* — i.e. our JVM side *could* do
  msgpack but the CLJS side fundamentally can't receive it.
- **Fressian is the only credible binary CLJ↔CLJS option, and it's literally already
  in the stack underneath us:** datahike's konserve store uses fressian (JVM) / fress
  (CLJS) for on-disk persistence (per the konserve/fress ecosystem; web: *"The
  file-system store using Konserve currently uses fressian in Clojure and fress in
  ClojureScript … both implementations using the same on-disk format"*). So a future
  binary RPC is not exotic — the dependency competence exists in-tree.
- BUT Transit's own guidance is the deciding heuristic: **Transit for transport,
  Fressian for persistence.** Our seam is transport (RPC), so Transit is the
  on-design choice. `fress` also carries real CLJS gaps today (no BigDecimal/Ratio,
  buffer management, advanced-build symbol munging) that Transit-cljs already handles
  cleanly.

**Recommendation:** Standardize the whole wire (envelope + values) on **Transit-JSON
compact** — Option A of the migration plan. It is the right way to do serialization
here: one decode yields the whole map with real keywords/instants, no double-encoding,
no EDN fallback, deletes the hand-rolled `cbor.cljs`. Revisit Fressian ONLY if a
measured payload-size/throughput problem appears on the all-CLJS cloud path later
(§4); at that point `fress`↔`data.fressian` is the upgrade, and it reuses the same
konserve-proven competence. Do not hand-roll msgpack+types.

---

## 4. Transport: UDS now → IP/cloud later, all-CLJS cluster

### (a) Is keeping UDS now correct? YES.

UDS is the right local-default: lower latency / no TCP stack, filesystem-permission
access control for free, and it's what `bin/seon` already wires
(`SEON_REQ_SOCK`/`--req-sock`, default `tmp/seon-cluster-default-req.sock`;
`wire_node.cljs:53-62`, `store/wire.cljs:58-63`). The cluster-isolation story (acme on
its own socket) rides on the socket *path*, which is already env-parameterized. No
reason to move to TCP before there's a remote writer.

### (b) Is the codec/framing already transport-agnostic? YES — that's the key result.

The wire format is **4-byte big-endian length prefix + payload bytes over a stream
socket**, identical on both sides:

- JVM: `server/codec.clj:62-80` `write-frame!`/`read-frame` use a generic
  `OutputStream`/`InputStream` (`DataOutputStream.writeInt` + payload; read mirrors).
  Nothing in the codec knows it's a Unix socket vs a TCP socket — it's just a stream.
- CLJS: `wire_node.cljs:73-118` `rpc` opens `(.createConnection net sock-path)` and
  does manual length-prefix reassembly (`readUInt32BE` of the first 4 bytes, then
  accumulate to `@!need`); `cbor.cljs:158-163` `frame` writes the 4-byte BE header.
  `node:net.createConnection` takes EITHER a path (UDS) OR `{host, port}` (TCP) with
  the **same Socket API** — so switching to TCP is a one-line change to the connection
  argument; the framing/reassembly code is untouched.

**Therefore UDS→TCP/cloud is a *localized* change: the connection target (client) +
the listener bind address (server). It is NOT a protocol change.** The length-framed
stream design is already transport-neutral, and swapping CBOR→Transit-JSON inside the
frame doesn't touch the framing at all (length prefix + opaque payload bytes either
way). The Transit migration is fully forward-compatible to a cloud writer.

**What to keep clean NOW (cheap, high-leverage):**

1. **Keep the connection target a single parameter** (it already is: `sock-path` /
   `SEON_REQ_SOCK` on CLJS, `--req-sock` on the JVM). When TCP arrives, this becomes
   a `host:port` (or a URI) — do NOT scatter socket paths through call sites; route
   everything through `default-req-sock` / `default-sock-path` as today.
2. **Keep framing in ONE place per side** (`codec.clj` JVM, `cbor.cljs`→its Transit
   replacement on CLJS). The migration plan already centralizes this.
3. **Do NOT bake UDS assumptions into the protocol** (e.g. no reliance on
   filesystem-permission auth as the *only* auth — that won't exist over TCP). It
   currently doesn't, which is good.

### (c) All-CLJS cluster with the WRITER in the CLOUD — what the choice should anticipate

Pragmatic, not over-engineered. The serialization choice (Transit-JSON) is already
fine for cloud; the framing/transport is what needs forethought:

- **Auth / TLS.** Over UDS, the socket path + FS perms are the boundary. Over the
  public IP path you need TLS (terminate at the listener or a proxy — Caddy already
  fronts HTTPS in this repo) and an auth token on the envelope. Reserve a namespaced
  envelope key for it now conceptually (e.g. an auth field on the request map) so it's
  additive, not a reshape. Transit-JSON carries it natively.
- **Max message size / backpressure.** `read-frame` already caps frames at 16 MiB
  (`codec.clj:76` `(> len (* 16 1024 1024))` throws "Frame length out of bounds"); the
  CLJS `rpc` reassembles unboundedly — over WAN, add a matching cap on the CLJS read
  side and treat the boot core-index transact (the documented multi-thousand-row tx,
  `store/wire.cljs:224-227`) as the sizing driver. Backpressure today is implicit
  (one-frame-in/one-frame-out, request/reply). A cloud writer with many pods may need
  windowing, but NOT before it's a measured problem — the request/reply shape is fine
  to start.
- **Reconnection / heartbeat.** The `subscribe-tx`/`next-tx-event` feed already has a
  resilient re-subscribe loop (`store/wire.cljs:372-397`: on pump failure, log loud,
  wait 2s, re-subscribe). Over WAN that pattern generalizes well; add a heartbeat
  ping on the long-lived feed connection (the `ping` op exists,
  `wire_node.cljs:129-131`) to detect dead TCP connections faster than a stalled read.
- **At-most-once vs idempotency.** The echo-suppression `write-id` (a per-write UUID,
  `store/wire.cljs:214-244`) is already an idempotency token. Over a flaky WAN where a
  request may be retried, that token lets the writer dedupe — a property worth
  preserving explicitly in the Transit migration (the plan flags it as the 8-site
  symmetry trap; over WAN it gains a second purpose beyond echo-suppression).

**None of this forces a binary format or a protocol redesign now.** Transit-JSON over
length-framed streams scales from UDS to TLS-over-TCP with additive envelope fields
(auth) and additive operational glue (heartbeat, CLJS-side frame cap). Revisit
Fressian only if measured payload size on the cloud path becomes the bottleneck.

---

## Code smells flagged for follow-up (NOT fixed — out of task scope)

1. **`wire_node.cljs:149` `transact` decodes `"payload"`; the live pod writer
   `store/wire.cljs` decodes structured fields and never uses `"payload"`.** Two decode
   contracts for the same op; `wire_node` is the prototype, the pod path is production.
   Reconcile to ONE during the Transit migration (plan already lists "drop payload").
2. **`knn-search` sends `"query"` as a raw string; `q` sends `"query"` as Transit
   under the SAME key name** (`wire_node.cljs:157` vs :182). Verify the JVM handler
   doesn't `read-T` the knn query. (Plan risk #5.)
3. **Transit-then-EDN fallback in JVM `seon.server.wire/read-T`** is production-dead,
   kept only for EDN-string test inputs. Remove with the migration. (Plan step 2.)
4. **Stale "the Rust host forwards Transit-JSON as opaque blobs" docstring in
   `server/transit.clj:14-16`** — the Rust host is dead (per the migration plan's
   verdict). Correct when the file is touched.

## Sources

- Vendored source (quoted inline): `reference-code/transit-js/src/com/cognitect/transit.js`,
  `.../impl/writer.js`, `.../impl/reader.js`, `.../caching.js`, README.md;
  `reference-code/transit-cljs/src/cognitect/transit.cljs`;
  `reference-code/transit-clj/src/cognitect/transit.clj`.
- [Will Transit support encoding using msgpack in ClojureScript? — Clojure Q&A (alexmiller)](https://ask.clojure.org/index.php/8310/will-transit-support-encoding-using-msgpack-clojurescript)
- [cognitect/transit-cljs README](https://github.com/cognitect/transit-cljs)
- [pkpkpk/fress — Fressian for ClojureScript/WASM](https://github.com/pkpkpk/fress)
- [kawasima/fressian-cljs](https://github.com/kawasima/fressian-cljs)
- [Clojure Serialization With Fressian](https://www.metasimple.org/2018/02/19/clj-fressian-ext.html)
- [cljs-ajax Advanced Formats (transit defaults: json for cljs)](https://cljdoc.org/d/cljs-ajax/cljs-ajax/0.8.3/doc/advanced-formats)
</content>
</invoke>
