---
type: reference
status: draft
tags: [reference, agent, database]
---

# Sidecar wire protocol (PoC)

Two Unix domain sockets between the **client** (Rust host / smoke driver) and the
**JVM writer** subprocess:

| Socket | Direction | Shape |
|---|---|---|
| `/tmp/seon-poc-req.sock`  | client → writer, length-framed req/resp | one request → one response |
| `/tmp/seon-poc-pub.sock`  | writer → all subscribers, length-framed pub | server pushes tx events |

Both sockets carry **CBOR-encoded** messages with a 4-byte big-endian length
prefix on the wire:

```
+----+----+----+----+----+----+----+----+
|  length (u32 BE)  |  CBOR payload ...  |
+----+----+----+----+----+----+----+----+
```

CBOR is chosen because:
- Native binary format (no base64 / string escaping cost).
- First-class support in JVM (jackson-cbor) and Rust (`ciborium`, `serde_cbor`).
- Already used by upstream `pydatahike`.

CBOR carries the same value space as EDN modulo:
- Datahike keywords are encoded as **tagged strings** `tag 39 "namespace/name"`.
  (CBOR tag 39 = "identifier" per RFC 8746 conventions used here.) The writer
  recovers `:kw` from any tagged string. For the PoC, the smoke client may also
  send plain strings prefixed with `:` and the writer normalizes.
- `#inst` instants are CBOR tag 0 strings (RFC 3339).

## Request messages (req socket)

All requests are CBOR maps with a string `"op"` key (using string keys instead
of keywords to keep client implementations trivial).

### `{"op": "ping"}`
Liveness check. Response: `{"ok": true, "pong": true}`.

### `{"op": "q", "query": <edn-string>, "args": [<value> ...]}`
Runs a Datalog query. `query` is an EDN string; `args` is an array of CBOR
values that become the inputs to `d/q` after `(d/db conn)` is prepended.
Response: `{"ok": true, "basis-t": <int>, "result": <cbor>}`.

### `{"op": "transact", "tx-data": <edn-string>, "tx-meta"?: <edn-string>, "request-id"?: <string>}`

Writes a transaction. `tx-data` is an EDN string of the tx-data vector.
`tx-meta`, when present, is an EDN string of a map merged into the datahike
tx-report's `:tx-meta`. `request-id`, when present, is a caller-supplied
opaque string echoed on both the response and the pub event — used by callers
that both transact AND subscribe to dedup their own commits.

Response:
```
{"ok": true,
 "basis-t": <int>,             ; max-tx of post-commit db
 "basis-t-before": <int>,      ; max-tx of db-before
 "tempids": {...},             ; tempid → eid (minus :db/current-tx)
 "tx-data": [[e a v t op] ...] ; full datom list, same shape as the pub event
 "tx-meta": {...},             ; datahike-issued meta merged with caller's;
                               ; includes "db/txInstant" + "db/commitId"
 "datoms-added": <int>,
 "datoms-retracted": <int>,
 "request-id"?: <string>}      ; only present if the request supplied one
```

The `tx-data` and `tx-meta` shape matches what a JVM-side `d/listen!`
callback receives, modulo CBOR-native encoding of keywords (string with
`namespace/name`). This is the shape gap #1 (per the kabel-vs-sidecar
research) closes — a callback can now reason about specific changes, not
just counts.

### `{"op": "transact-batch", "tx-data-list": [<edn-string> ...], "tx-meta-list"?: [<edn-string-or-null> ...], "request-ids"?: [<string-or-null> ...]}`

Apply N tx-data vectors in order, each as its own datahike commit. Emits one
pub event per individual tx — matching `d/listen` semantics exactly. The
batch is single-threaded inside the writer: entry `i+1` does not start until
entry `i` has committed and broadcast.

`tx-meta-list` and `request-ids`, when present, must have the same length as
`tx-data-list`; per-entry nils mean "no tx-meta" / "no request-id".

**Ordering guarantees:**
- The JVM commits the batch in array order.
- All subscribers (including any future host-side batcher's per-tx oneshot
  receivers) see the per-tx events in commit order.
- Caller-side ordering across concurrent guests is preserved by whatever
  enqueue mechanism feeds the batch (the Rust host's mpsc, the smoke
  client's serial loop, etc.) — the JVM does not re-order.

**Partial failure:** if entry `i` throws, entries `0..i-1` ARE applied and
returned in `reports`; entry `i` and beyond are NOT applied. The response
shape distinguishes complete success from partial:

Success (all applied):
```
{"ok": true,
 "applied": N,
 "total":   N,
 "reports": [
   {"index": 0, "basis-t": <int>, "basis-t-before": <int>,
    "tempids": {...}, "tx-data": [[e a v t op] ...], "tx-meta": {...},
    "datoms-added": <int>, "datoms-retracted": <int>,
    "request-id"?: <string>},
   ...
 ]}
```

Partial failure (entries 0..k-1 applied, entry k failed):
```
{"ok": true,                       ; the OP succeeded; some entries didn't
 "applied": k,
 "total":   N,
 "failed-at":  k,
 "error":      <string>,
 "error-kind": "datahike" | "internal",
 "reports": [<k entries>]}
```

Why `"ok": true` for partial failure: the operation itself succeeded — the
writer received the batch, applied as many entries as it could, and is
returning a structured report. Use `failed-at` to distinguish.

### WIT-side `transact-batch` (guest → host)

The same batch shape is exposed on the WIT contract as
`transact-batch(tx-data-list, tx-meta-list, request-ids) -> string`. The
guest's CLJS overlay calls this directly from `transact-batch!`. The Rust
host forwards to the JVM writer's `transact-batch` op without going through
the opportunistic `TransactBatcher` (the guest has already done its own
batching).

WIT length-list convention: the WIT signature uses `list<string>` (not
`option<list<string>>`) for `tx-meta-list` and `request-ids` because
wasm-rquickjs does not cleanly marshal nested options on the import side.
**Empty list means "omit entirely".** A non-empty list MUST have length
equal to `tx-data-list`. Per-entry "absent" is the empty string `""`. The
host returns a protocol-error variant if lengths mismatch.

The WIT return value is the EDN-printed reports map (not CBOR) — the guest
parses it with `edn/read-string`.

### `{"op": "pull", "selector": <edn-string>, "eid": <int-or-edn-string-lookup-ref>, "basis-t"?: <int>}`
Pull. If `basis-t` is provided, the writer uses `(d/as-of db basis-t)` for
the snapshot read; otherwise reads against the latest db. `eid` may be a
CBOR integer eid OR an EDN-string lookup-ref like `"[:person/name \"alice\"]"`.

Response: `{"ok": true, "basis-t": <int>, "result": <cbor>}`.

### `{"op": "entity-pull", "ref": <int-or-edn-string-lookup-ref>, "selector"?: <edn-string>, "depth"?: <int>, "basis-t"?: <int>}`

Eager entity replacement for `d/entity`. Default selector is `[*]`; default
`depth` is 1 (component-ref maps one level deep are realized). Missing
entities (`:entity-id/missing` from a lookup-ref that doesn't match any
indexed datom) return `result: nil` — NOT an error. This matches V0's
`d/entity` contract (nil on miss).

Response: `{"ok": true, "basis-t": <int>, "result": <cbor-map-or-nil>}`.

### `{"op": "pull-many", "selector": <edn-string>, "eids": [<int-or-edn-string-lookup-ref> ...], "basis-t"?: <int>}`

Batched pull. Returns a vector in input order.

Response: `{"ok": true, "basis-t": <int>, "result": [<cbor-map> ...]}`.

### `{"op": "schema"}` and `{"op": "reverse-schema"}`

Read the current db's `:schema` map (attr → attr-schema) or `:rschema`
(property → set-of-attrs) respectively.

Response: `{"ok": true, "basis-t": <int>, "result": <cbor-map>}`.

### `{"op": "db-filter", "pred-query": <edn-string>, "args"?: [<value> ...]}`

Build a filtered-db handle. **This is a deliberate departure from native
`d/filter`** — which takes a host fn `(fn [db datom] -> bool)` that the
sidecar can't transport across the wire. Instead, the wire shape is a
**predicate query**: an EDN Datalog query that returns rows of `[?e]` (the
eids to retain). The writer runs the query against the current db,
materializes a `d/filter` whose predicate is `(contains? keep-eid-set
(.-e datom))`, and registers it under a fresh integer handle.

Response: `{"ok": true, "basis-t": <int>, "handle": <u32>, "kept": <int>}`.

The handle is valid until `filter-release` is called or the writer
restarts. The basis-t the handle was created at is captured at registration
time; subsequent `q-filtered` calls report that basis-t.

### `{"op": "q-filtered", "handle": <u32>, "query": <edn-string>, "args"?: [<value> ...]}`

Run a Datalog query against a previously-built filtered db. Response shape
identical to `q`.

`{"ok": false, "error-kind": "not-found"}` if the handle was released or
never existed.

### `{"op": "filter-release", "handle": <u32>}`

Drop a filtered-db handle (idempotent).

Response: `{"ok": true, "released": true, "handle": <u32>}`.

### `{"op": "q", ..., "basis-t"?: <int>}`

The existing `q` op accepts an optional `basis-t`. When present, the writer
uses `(d/as-of db basis-t)` for the snapshot read. This is the building
block for the audit's "warnings composer" pattern — get a basis-t once
(from any preceding response), thread it to all subsequent reads, get
consistent results across N round-trips.

### `{"op": "schema-install", "tx-data": <edn-string>}` *(reserved)*

Was specified earlier as a convenience for schema vectors. Use `transact`
directly — schema-install just IS a normal transact whose tx-data installs
`:db/ident` etc. (See `test-schema-altering-tx-shape`.) Kept here as a
non-implemented note so callers don't expect a separate op.

### Errors

Any failure: `{"ok": false, "error": <string>, "error-kind": <string>}` with
HTTP-ish kinds: `"validate"`, `"datahike"`, `"protocol"`, `"internal"`.

## Pub messages (pub socket)

After every successful transact the writer pushes a tx event with the full
post-commit tx-report shape:

```
{"event": "tx",
 "basis-t": <int>,                ; max-tx of db-after
 "basis-t-before": <int>,         ; max-tx of db-before
 "db-name": "default",            ; reserved for future multi-DB routing
 "tx-data": [[e a v t op] ...],   ; every datom in this commit, 5-vector wire shape
 "datoms-added":  <int>,          ; (count of `op = true` for convenience)
 "datoms-retracted": <int>,       ; (count of `op = false`)
 "tx-meta": {"db/txInstant": <epoch-ms>,
             "db/commitId": <uuid-string>,
             ...},                ; merged with caller-supplied tx-meta
 "request-id"?: <string>}         ; only present if the originating transact supplied one
```

Worked example (writing `{:person/name "alice" :person/age 33}` against the
schema in `client.clj`):

```
{"event" "tx"
 "basis-t" 536870914
 "basis-t-before" 536870913
 "db-name" "default"
 "tx-data" [[536870914 "db/txInstant" 1779613402036 536870914 true]
            [3         "person/name" "alice"        536870914 true]
            [3         "person/age"  33             536870914 true]]
 "datoms-added" 3
 "datoms-retracted" 0
 "tx-meta" {"db/txInstant" 1779613402036
            "db/commitId"  "6a12beda-3da0-5ad4-bd49-c8789b4f22cc"}}
```

Datom shape on the wire is `[e a v t op]`:
- `e` — entity id (int)
- `a` — attribute, encoded as `"namespace/name"` string (CBOR text)
- `v` — value, CBOR-native: int, double, string, bool, java.util.Date (CBOR
  tag 0). Keyword values become `"namespace/name"` strings via the same
  walker used elsewhere.
- `t` — tx-id (int) for this commit
- `op` — boolean: `true` for `:db/add`, `false` for `:db/retract`

Subscribers connect, send no bytes, and just read frames as they arrive. The
writer fans out to every connected subscriber. No causal ordering across
reconnects — late subscribers see only events that arrive after they
connect (pub-sub-only semantics, matching kabel). See the kabel-vs-sidecar
research for the gap list (request-id dedup, tx-log catch-up).

## EDN-as-string vs full CBOR datoms

For the PoC, queries and tx-data are passed as **EDN strings** so the writer
parses them with `clojure.edn/read-string`. This avoids hand-rolling the
keyword/symbol/regex CBOR mappings on the client side. Results come back as
CBOR-native values (vectors, maps, ints, strings) — readable from any client
without an EDN parser.

This means the wire is asymmetric (string-in, structured-out) which is a
deliberate PoC simplification. A v1 protocol would either commit to a single
representation everywhere or split read / write APIs.
