---
type: research
status: active
tags: [research, flow, schema]
---

# Grounding: wire/serialization + code-as-data tooling + lifecycle

> Source-grounded "write it this way" guidance for the Core engine lane. Every
> claim is cited to vendored library source (`reference-code/`) AND our `.cljs`
> so the next agent reads the source, not training memory. Research only — no
> `src/` edits.

## TL;DR

1. **The pod's wire is Transit-JSON, NOT nippy.** The prompt (and a lot of
   training memory) conflates them. Nippy is a JVM dep used ONLY by `.clj`
   files (`src/seon/db.clj`, `relay.clj`, `flow/harness/*`) for konserve/datahike
   datom *persistence* on the wire-server. The pod↔wire-server *envelope* is a
   single length-framed **Transit-JSON map** (`seon.store.internal.wire-node`).
   If you reach for `taoensso.nippy` in a `.cljs`, you are in the wrong lane.

2. **One uniform Transit frame, native values, no custom handlers, no inner
   encode.** `4-byte BE length + Transit-JSON`. Keys are `:seon.store.wire/*`
   keywords; values are native (keyword attrs, symbols, uuids, vectors). Transit's
   default cljs writer already handles Keyword/Symbol/UUID/Set/Vector/List/WithMeta
   — so namespaced keywords and the route-handler **symbols** ride native with
   ZERO custom handlers. The ONE type transit can't carry is a datahike `Datom`
   record → the wire sends datoms as 5-vectors `[e a v t added]` and reconstructs.

3. **`transact!` is a synchronous RPC round-trip with read-your-own-writes, not
   fire-and-forget.** Every write is an `rpc` over the UDS to the sole JVM writer;
   the `SeonWireWriter` blocks the returned envelope until a local re-deref reaches
   the ack'd basis-t (`ryow-deref!`). Straight-line `transact!`-then-`query` just
   works *because* of this; do not "optimize" it away.

4. **rewrite-clj / orchard are NOT needed for the program graph. VERDICT: the
   analyzer path is genuinely sufficient and strictly better.** Our code captures
   defs, requires, arglists, docs and schema metadata from
   `cljs.analyzer/namespaces` (`seon.analyzer-info`), and reconstitutes loadable
   source by **pure string concatenation** of stored `:seon.fn/source` rows — no
   parse, no zipper. Pulling in rewrite-clj/orchard would re-derive (worse)
   information the analyzer already produced. Keep them out of the pod.

5. **Integrant is JVM-track only — there is NO integrant in the pod.** `grep
   integrant src/**/*.cljs` is empty. The pod's "lifecycle" is plain
   `boot-seed!` → `replay-program-graph!` → `start-agent!` plus `defonce` atoms
   and `seon.state/reconcile!`. The "`:seon.dev/instrumentation` integrant
   component" in CLAUDE.md is the paused JVM app; pod instrumentation lives in
   `seon.eval`. Don't model pod lifecycle as integrant keys.

6. **One smell, already fixed; a couple to verify.** The component-cascade
   retract bug flagged in `holistic-state-management` IS fixed in
   `seon.agent.ctx/upsert-ctx-tx` (now `:db.fn/retractAttribute`,
   `ctx.cljs:1702`) and `reconcile!` correctly uses `:db.fn/retractEntity`
   (`state.cljs:102`). Remaining low-confidence items below.

---

## Wire / serialization

### nippy — what the source does, and why it's not your concern in the pod

- **What it does:** `taoensso.nippy/freeze` → byte array, `thaw` → value, via a
  type-tagged binary protocol (`reference-code/nippy/src/taoensso/nippy.clj:591`
  `IFreezable`, `:955` `freeze-to-out!`, `:1281` `freeze`). It is the binary
  serializer konserve uses to persist datahike index nodes + the branch root.
- **Where it actually lives in seon:** ONLY `.clj`
  (`grep -rln nippy src` → `src/seon/db.clj`, `src/seon/db/relay.clj`,
  `src/seon/flow/harness/{channel,bridge}.clj`). All JVM track / wire-server side.
- **Training-memory mistake:** "the pod serializes datoms with nippy for the
  wire." FALSE. Nippy never runs in the pod (it's a JVM library; the pod is Node).
  The durable datom encoding (nippy-in-konserve) is the **JVM writer's** business;
  the pod only ever sees the Transit envelope.
- **Correct seon idiom:** in a `.cljs`, you never touch nippy. Durability is the
  wire-server's; the pod's serialization concern stops at Transit (below).

### transit — the actual pod↔wire-server codec

- **What the source does:** `cognitect.transit/writer` (cljs) installs default
  handlers for `Keyword`, `Symbol`, `List/Cons/LazySeq/Range`, `Set`, `Vector`,
  `UUID`, and `WithMeta` out of the box
  (`reference-code/transit-cljs/src/cognitect/transit.cljs:212-240`); `:json` is
  the compact ground type. The reader mirrors them (`:106`).
- **Our usage:** `seon.store.internal.wire-node` builds ONE memoized `(t/writer
  :json)` / `(t/reader :json)` (`wire_node.cljs:47-50`) and frames each message as
  `4-byte BE length + Transit-JSON UTF-8` (`enc-frame`, `wire_node.cljs:54-61`;
  decode `dec-payload` `:63-67`; length reassembly in `rpc`'s `"data"` handler
  `:111-123`). The frame is the docstring's "uniform frame: ONE Transit-JSON map,
  `:seon.store.wire/*` keyword keys and NATIVE values … one encode/decode, no inner
  Transit strings" (`wire_node.cljs:18-22`).
- **Training-memory mistakes:**
  - *"Register custom write handlers for keywords / namespaced keywords."* No —
    transit's default `KeywordHandler` already round-trips `:seon.foo/bar`
    losslessly (`transit.cljs:227`). `grep write-handler src/seon/store` is empty
    by design.
  - *"Double-encode the payload as a transit string inside the envelope."* No —
    the whole frame is ONE transit map with native values; there is no inner
    transit string (`wire_node.cljs:18-22`). a/v on datoms arrive native.
  - *"Send the datahike db value / a Datom over the wire."* A `Datom` record is
    NOT a transit default type and a lazy db value is unserializable. The wire
    sends tx-data as plain maps/vectors and returns datoms as 5-vectors `[e a v t
    added]`; `wire-datoms->datoms` reconstitutes real `dd/datom`s on the pod side
    (`wire.cljs:199-202`, `277`). Keep records OFF the wire.
  - *"Make a fresh writer per message (transit caches across messages — unsafe to
    reuse)."* The opposite is the verified idiom: transit-js clears its
    per-message cache at the end of every write/read, and the pod is
    single-threaded (`^:async`/await, no worker threads), so the memoized
    writer/reader are safe and cheaper (`wire_node.cljs:41-50`). Don't churn them.
- **Correct seon idiom:** to add a wire op, add a `:seon.store.wire/op` map in
  `wire-node` with native-value keys (`transact`/`q`/`pull`/`subscribe-tx` are the
  template, `wire_node.cljs:149-230`); never invent a parallel codec or hand-roll
  JSON. Symbols (route handlers) and uuids cross natively — that is why
  `:seon.route/handler` can be a `:db.type/symbol` and survive the round-trip.

### transact! semantics — RYOW, echo-suppression, sole writer

- **What the source does (our side):** `SeonWireWriter/-dispatch!` only supports
  `transact!`; it mints a per-write UUID `write-id`, `rpc`s the op, and on ack
  resolves the synthesized tx-report ONLY after `ryow-deref!` sees a local db whose
  `:max-tx >= ack basis-t` (`wire.cljs:243-299`, `ryow-deref!` `:210-219`).
  `-streaming?` returns `false`, which flips datahike's `deref-conn` into
  follow-the-store mode (`wire.cljs:300-301`, docstring `:8-12`).
- **Echo-suppression:** own writes are tracked in `!own-write-ids`
  (`wire.cljs:228-236`, added at `:258`); the tx-feed adapter skips a feed event
  whose write-id is ours, because own txs already fired the conn's native listeners
  via `datahike.writer/transact!` (`wire.cljs:378-383`). FOREIGN txs synthesize a
  raw report and fire the SAME listener atom (`handle-feed-event!`
  `:362-398`) — one listener bus, two tx origins.
- **Lossless wake:** the feed is a polled queue; the adapter tracks a
  `:last-applied-t` basis-t watermark and re-subscribes with `since-t` on reconnect
  so the wire-server replays the gap in commit order, deduped idempotently by the
  watermark (`wire.cljs:362-398`, `400-464`; `subscribe-tx` `:since-t`
  `wire_node.cljs:204-217`).
- **Training-memory mistakes:**
  - *"`transact!` is async fire-and-forget; read may not see the write."* False —
    RYOW is guaranteed by `ryow-deref!`; the public `db/transact!` is `^:async` and
    auto-awaited, so callers get the ENVELOPE and writing `await` is itself an error
    (`db.cljs:423-465`, docstring `:462-464`).
  - *"Writes are local."* Every write is a UDS RPC to the SOLE JVM writer; a pod
    that can't reach its writer must NOT boot (no local fallback) — the boot gate
    `ping!` fails LOUD after ~10s (`wire.cljs:132-190`).
  - *"Check `transact!` by try/catch."* `db/transact!` is errors-as-values: it
    never throws into your eval; check `:seon.db/ok?` (`db.cljs:435-456`,
    `509-515`). The boot seed does exactly this and re-throws on a bad envelope
    (`client.cljs:2089-2147`).
- **Correct seon idiom:** transact through `seon.db/transact!` only (never
  `datahike.api` outside `src/seon/db*`), check the envelope, and rely on
  straight-line read-back. To react to writes (own OR foreign), `seon.db/listen!`
  — one bus.

---

## Code-as-data tooling — the rewrite-clj-vs-analyzer verdict

### What our analyzer path does

- **Capture (detect-and-tee):** after a successful `eval-str`, `build-tee-entities`
  diffs the analyzer's `:defs` snapshot (`analyzer-info/defs-since` over
  `cljs.analyzer/namespaces`, `eval.cljs:1612-1675`) and the Malli registry
  (`schema/current-keys`) to mint `:seon.fn` / `:seon.schema` / `:seon.ns` rows.
  Var metadata — `sym`, `fn-var?`, `arglists`, `doc`, `private?`, `:malli/schema`
  spec — comes from `analyzer-info/var-projection`, i.e. the analyzer's own
  var-map (`eval.cljs:1655-1675`, `analyzer_info.cljs:95-137`).
- **Requires:** captured from the analyzer's `:requires`+`:uses`+`:require-macros`
  maps (`analyzer_info.cljs:204-208`), stored as `:seon.ns/requires`
  (`eval.cljs:1770` `ns-requires-tx`) — NOT re-parsed from source.
- **Reconstitute (resume):** `reconstitute-ns-source` is PURE STRING
  CONCATENATION — the verbatim stored `(ns … (:require …))` form + each current
  `:seon.fn/source` / `:seon.schema/source` / `:seon.test/source`, deduped and
  joined; "no parsing" is explicit (`eval.cljs:623-670`). `replay-program-graph!`
  topo-sorts over stored `:seon.ns/requires` and `eval`s each reconstituted ns
  string once, letting cljs.js's load-fn pull transitive deps
  (`client.cljs:787-848`, `topo-sort-nses` `:687-708`).

### What rewrite-clj / orchard offer (read to challenge the stance)

- **rewrite-clj** (`reference-code/rewrite-clj/src/rewrite_clj/{parser,zip,node}.cljc`)
  is a whitespace/comment-preserving parser + zipper for *editing* source text. Its
  value is round-trip-preserving edits to a file you don't control.
- **orchard** (`reference-code/orchard/src/orchard/{info,meta,namespace,xref}.clj`)
  is an nREPL-era introspection toolkit (var info, xref, apropos) over a **JVM
  runtime via reflection** — it inspects the live VM's vars/classes.

### VERDICT — analyzer path wins; keep both libs out of the pod

The CLAUDE.md stance ("don't re-parse source with rewrite-clj when the analyzer
already produced structured data") is **correct, and the evidence is concrete:**

- The analyzer produces *more* than a parser can: resolved fully-qualified
  `:name`, `fn-var?`, instrument-ready `:malli/schema`, and the resolved
  `:requires`/`:uses` alias map. A rewrite-clj parse of source would have to
  re-derive (and could mis-resolve) all of it — strictly lossy vs the analyzer.
- Reconstitution is concatenation of *stored* source strings; there is no editing
  task, so a zipper buys nothing. Same model as `cider`'s load-file (cited in
  `code-as-data-runtime.md:73`).
- orchard is JVM-reflection-based and `.clj`-only — it cannot run in the Node pod
  at all, and its job (inspect a live JVM) is already covered by querying the DB
  program graph + `analyzer-info` over the live compile-state.
- The ONE place a parser could be tempting is classifying a form's head
  (`defn-form?` / `deftest-def?` in tee, `eval.cljs:1654`) — but that is done with
  cheap `read-string`/seq inspection on the agent's single form, not a zipper over
  a file, and is the right grain.

So: **no rewrite-clj, no orchard in the pod.** If a future need arises to *edit*
an agent's stored ns text in place (vs re-tee a new def), revisit rewrite-clj
THEN — but the current redefine-by-upsert model never edits text, it replaces a
row. Adding a parser now would be a second source-of-truth for the program graph
(the exact "don't be a dumbass / one mechanism" trap).

---

## Lifecycle

### Integrant — JVM track only; do not model the pod with it

- **What the source does:** `integrant.core` builds a dependency graph from
  `ig/ref`s (`reference-code/integrant/src/integrant/core.cljc:182`
  `dependency-graph`, `430` `build`), then `init` walks it calling the `init-key`
  multimethod per key, `halt!` the `halt-key!` multimethod in reverse, with
  `resume-key`/`resolve-key` for warm restarts (`:457-660`). Refs are resolved by
  `resolve-key` so a component receives its started deps.
- **Reality in the pod:** `grep -rln integrant src/seon/**/*.cljs` → **empty**.
  The pod has no integrant system. Lifecycle is:
  `boot-seed!` (`client.cljs:2079`) → `replay-program-graph!`
  (`client.cljs:787`) → per-agent `start-agent!`; runtime singletons are
  `defonce` atoms (e.g. the wire adapter `!adapter`, `wire.cljs:321`). Pod
  instrumentation is applied in `seon.eval` (per the grep), not an integrant key.
- **Training-memory mistake:** "wire the new pod subsystem as a
  `:seon.x/component` with `init-key`/`halt-key!`." Wrong lane — that's the paused
  JVM app (`bin/run`, nREPL 7888). In the pod, a subsystem is a fn called in the
  boot sequence plus (if it has runtime state) a `defonce` atom with an idempotent
  start (`start-listen-adapter!` is the template: defonce-guarded, second call is a
  no-op, `wire.cljs:410-425`).
- **Correct seon idiom:** model pod lifecycle as **derive + reconcile**, not
  component graph. Declarative state is synced by `seon.state/reconcile!`; code
  state is replayed; runtime singletons are `defonce` + idempotent start.

### reconcile! — the one declarative-lifecycle primitive

- `seon.state/reconcile!` (`state.cljs:58-113`) makes the MANAGED datoms (by
  `:seon.db/origin` provenance, NOT a kind) match a desired entity-map set:
  upsert each by its own `:db.unique/identity` attr, enumerate the managed
  population via `db/managed-identities` (`db.cljs:1231-1262`), retract stale via
  `:db.fn/retractEntity` (which cascades component children). Seed / override /
  reset / restore are all this one op (`holistic-state-management-2026-06-28.md`).
- Boot routes the routes+skills desired set through it under origin `:config`
  (`client.cljs:2120-2147`); the append-only core introspection
  (`:entity-schemas`/`:core-seed`/`:core-index`) stays origin `:core-seed`, OUT of
  the managed scope, so reset never eats it.

### reitit routing — datoms in, derived router out

- **What the source does:** `reitit.core/router` compiles routes and AUTO-SELECTS
  a `linear-router` for conflicting paths + a `mixed-router` for non-conflicting,
  via `conflicting-paths` (`reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:286-296`);
  by default `:conflicts` THROWS on overlapping patterns (`:329`,
  `exception/fail! :path-conflicts`). `split-path` accepts both `{id}` and `:id`.
- **Reality in seon:** `seon.route` owns ONLY the `:seon.route/*` schema + the
  seeded core route set (`route.cljs:43-108`); the reitit build + Node↔Ring adapter
  + `db->routes` live in the UI lane (`seon.web.router`). Handlers are stored as
  `:db.type/symbol` and resolved LATE via `eval/lookup-value` at request time
  (`route.cljs:18-30`, `46-47`) — same late-binding as render `:seon.render/html`
  symbols, so a route can name a handler before it exists and a redefine takes
  effect with no re-transact.
- **Training-memory mistakes:** "store the handler as a string and `resolve` it" /
  "build the reitit router once at boot." No — handler is a native symbol
  (transit-native over the wire, §wire), and the router is a PURE DERIVED VALUE
  rebuilt on tx from the route datoms (`route.cljs:4-10`). Overlapping patterns
  will throw unless `:conflicts` is handled — keep core patterns non-conflicting
  (the seeded set is: `/`, `/agent/{id}`, `/agent/{id}/feed`, `/agent/{id}/call`).

---

## SMELLS I FOUND (in wire / reconcile / route code)

1. **`seon.store.wire` schemas use `:any` at three spots — justified, but audit
   the boundary.** `::conn :any` (`wire.cljs:58`), `cluster-config` returns `:map`
   (`:62`), `ping!`/`adapter` return `:any` (`:167`, `:333`). These are genuine
   third-party (datahike/JS) boundaries, which the standing rule permits. *Fix:*
   none needed; flagging only so a future reviewer doesn't "tighten" them and break
   the boundary. **Confidence: high that this is correct as-is.**

2. **`SeonWireWriter/-dispatch!` rejects non-`transact!` ops by `put!`-ing an
   `ex-info` onto the promise-chan rather than an errors-as-value map**
   (`wire.cljs:247-249`). This is the datahike writer protocol contract (the
   go-loop expects a throwable to propagate), so it's correct AT THIS LAYER — the
   public `db/transact!` converts it to an envelope. *Fix:* none; but note the
   asymmetry (internal writer = throwable, public API = value) so nobody "fixes"
   the writer to return a value and breaks the datahike contract.
   **Confidence: medium — verify the go-loop consumer treats the ex-info as a
   rejection, `reference-code/datahike` writer.cljc.**

3. **`ryow-deref!` busy-loops up to 10× with NO yield** (`wire.cljs:210-219`).
   Flush-before-ack makes attempt #1 succeed (documented), so the loop is a
   falsifier, not a spinwait — but if the invariant ever breaks, this spins the
   single Node thread 10 iterations with no `await`/microtask yield before
   throwing. *Fix:* if it ever fires attempt >1 in practice, interleave a
   `(await (sleep 0))`; today it's correct and the throw is the right loud failure.
   **Confidence: medium that it's fine; low-cost to harden if logs ever show
   attempt>1.**

4. **`reconstitute-ns-source` joins members in QUERY order, then `distinct`s —
   intra-ns def ORDER across the fns set is datalog-unordered**
   (`eval.cljs:662-670`). The docstring relies on "same-ns forward refs resolve in
   one `eval-str` pass (LIVE-PROVEN)", which is true for the analyzer's two-pass
   resolution — so order genuinely doesn't matter for *resolution*. BUT a stored
   `(def x (some-side-effect))` that depends on an earlier `def`'s VALUE at load
   time (not just its symbol) could observe a different order run-to-run. The tee
   gate already refuses to persist bare/effectful `def`s (only literal `(defn …)`
   is teed, `eval.cljs:1644-1654`), which subsumes this — so it's defended.
   *Fix:* none; flagging the coupling so the "only-defn-is-teed" gate is understood
   as load-bearing for reconstitution determinism, not just for re-eval safety.
   **Confidence: high that it's currently safe BECAUSE of the tee gate.**

5. **Component-cascade retract — RESOLVED, re-verify no stragglers.** The bug the
   holistic doc flagged (plain `:db/retract` on `:seon.agent/ctx` orphans block
   rows) is fixed: `upsert-ctx-tx` now emits `:db.fn/retractAttribute`
   (`ctx.cljs:1702`) and `reconcile!` uses `:db.fn/retractEntity`
   (`state.cljs:102`) — both cascade per datahike
   (`transaction.cljc:730-733`, cited in holistic doc). *Fix:* none here; but
   `ctx.cljs:1742`'s docstring still says "dropping it from `:seon.agent/ctx`
   cascade-retracts" for the `remove!`/single-block path — confirm that path also
   routes through a cascading op and not a plain `:db/retract`.
   **Confidence: medium — I verified `upsert-ctx-tx` (1702) but not every
   `remove!` branch line-by-line; worth a 2-minute grep before relying on it.**

6. **`route/core-routes-tx` `:malli/schema` returns `[:vector ::route]` where
   `::route` requires `::handler` be present, but middleware/owner optional —
   consistent.** No smell; the late-symbol-resolution means an INVALID handler
   symbol won't fail here, it 500s at request time. *Fix:* none, but the UI lane's
   `db->routes` should surface an unresolvable `:seon.route/handler` as a derived
   warning (reactive-context) rather than a runtime 500. **Confidence: low — this
   is a UI-lane concern, flagging for the cross-lane list.**

---

## Cross-references

- `docs/seon/concepts/code-as-data-runtime.md` — the five-mechanisms principle the
  analyzer-path verdict upholds.
- `docs/prds/agent-fsm/holistic-state-management-2026-06-28.md` — the reconcile!
  design + the datahike attribute/connection grounding (cited here for the cascade
  fact).
- Source touchpoints: `src/seon/store/internal/wire_node.cljs` (transit codec +
  framing), `src/seon/store/wire.cljs` (writer/RYOW/listen-adapter),
  `src/seon/state.cljs` (reconcile!), `src/seon/route.cljs` (route datoms),
  `src/seon/eval.cljs` (tee + reconstitute), `src/seon/analyzer_info.cljs`
  (analyzer snapshots), `src/seon/db.cljs` (transact! + managed-identities).
