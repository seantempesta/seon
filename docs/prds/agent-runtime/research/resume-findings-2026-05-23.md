---
type: research
status: active
tags: [research, pod, cljs, agent, datahike]
---

# Resume phase — research findings (2026-05-23)

## TL;DR

The v1.md §7.4 "walk program-graph entities in tx-id order and re-eval `:source`" sketch is **structurally sound** for our config but has three load-bearing gaps the sketch glosses over: (a) the query must run against `(d/history db)` and filter to currently-asserted source values (because `:keep-history? true` makes retracted/superseded `:source` strings reappear in a naive 5-tuple history walk); (b) replay needs `(in-ns 'foo)` semantics — re-evaling a top-level `(defn x …)` from agent code without first re-establishing the owning ns leaves the def in the wrong analyzer slot and the wrong globalThis path; (c) the analyzer-cache for the bootstrap-CLJS substrate must be primed BEFORE replay so cross-namespace references to substrate fns (`seon.db/transact!`, `seon.schema/register!`) don't get reported as undeclared and surface as replay failures.

Three concrete recommendations:

1. **Ship as `seon.client/replay-program-graph!`** living in `client.cljs` (Platform lane). Query the current db (not history) for entities by their identity attr, then look up creation tx-id via a separate history query keyed by `[?e <identity-attr> ?id]`. Replay in `(in-ns 'foo)` + form pairs, wrapped in `with-tx-context {:seon.db/origin :replay :seon.db/replay? true}`. Re-use the existing `!compile-state` — do NOT rebuild it. Failure isolation: every per-entity replay is a `try/catch` returning data; failures land as `:seon.log` entries (NOT new `:seon.eval` entries — there's no turn to attach to and the schema doesn't make turn optional).
2. **Verify on the live pod TODAY using a same-pod-session test pattern** before touching the persistent backend: in one pod session, write some `:seon.fn`/`:seon.ns`/`:seon.schema` entities via the agent, clear the `:cljs.analyzer/namespaces` slot for the agent's home ns + delete the globalThis path for one of the defns, then invoke `replay-program-graph!` and confirm the var is restored. No `:memory`→SQLite flip required for this test loop.
3. **Defer SQLite flip behind a tiny refactor of `open-agent-conn!`** that accepts a backend config map (and uses a versioned per-agent directory layout — proposed in §4 below). The replay implementation works against `:memory` first; SQLite is the orthogonal "make resume actually testable across process restarts" lever.

## Findings

### Q1. Datahike replay semantics under our config

**Tx-id monotonicity is genuinely guaranteed.** `datahike/db/transaction.cljc:51-52` — `current-tx` is `(inc (get-in report [:db-before :max-tx]))`. Every successful tx commits with `max-tx + 1`. `constants.cljc:4` defines `tx0` = `0x20000000` (536870912); tx-ids increase from there. There is no per-attribute or per-backend variability. This holds equally for `:memory` and any disk-backed konserve backend — the counter lives in the DB record, not the store.

**Upsert keeps the entity-id constant; produces a NEW tx-id per upsert.** `db/transaction.cljc:511-522` (`transact-add`) calls `next-eid db` ONLY when the entity is novel; an upsert (`upsert-eid` returns non-nil) reuses the existing eid (line 664-669 `entity-map->op-vec`). But each upsert is its own tx (`current-tx` returns `max-tx+1`). Net effect: for `[:seon.fn/sym "alice/foo"]`, the entity-id stays stable across N redefines; queries against `@conn` return the LATEST `:source` value (single-cardinality scalar). The PRIOR `:source` values are in `(d/history db)` only — they are retracted from the current db on each upsert.

**`d/q` against `@conn` does NOT see retracted datoms.** Confirmed by inspecting `api/impl.cljc:130-148` — `since`/`as-of`/`history` are explicit constructors over a temporal-indexed db; the plain `@conn` view is filtered to current assertions. This is the key fact the v1.md sketch elides. The sketch's query:

```clojure
'[:find ?e ?source ?tx
  :where (or [?e :seon.ns/source ?source ?tx] ...)]
```

bound against `@conn` (not `(d/history db)`) returns ONE row per current entity (with the CURRENT `:source` value and the tx-id of the MOST RECENT upsert). That's almost what we want — except the tx-id is then "tx of the latest redefine", not "tx of original creation". For dependency ordering, the latest tx-id is actually MORE correct: if `alice/foo` was redefined to reference a newer `bob/bar`, replaying in latest-tx order is what reflects the current code shape. So the sketch's query is fine; just must run against `@conn`, not `(d/history db)`. Document this clearly in code comments.

**`forget!` (v2 verb) — not in scope.** v1 has no retraction verb. If the agent retracts an entity via raw datahike, the current-db query won't see it, so it won't be replayed. Correct behavior.

**Transactor-internal txes:** the boot transact for `agent-bootstrap-attrs` (`client.cljs:283-303`) lands as tx0+1 or tx0+2 (the schema attrs). These tx-ids are LOWER than any agent eval — so a query filtered to `:seon.fn/source` / `:seon.ns/source` / `:seon.schema/source` will not pick them up. No filtering needed.

### Q2. CLJS bootstrap analyzer + runtime state on re-eval

**Re-evaling `(defn foo [] ...)`:** `cljs.js/eval-str` (in `eval-str*` at `clojurescript/src/main/cljs/cljs/js.cljs:1038-1136`) is fully idempotent under re-eval IF the analyzer's `:cljs.analyzer/namespaces` already has the owning ns recorded. The analyze pass (line 1077-1078) calls `ana/analyze aenv form`; for a redef the analyzer updates the existing `:defs <sym>` map in place. The compiler then emits a single JS statement assigning to the munged path (`cljs.user.foo = function...`). globalThis path gets clobbered cleanly — there's no accumulator. This is good: idempotent re-eval.

**Re-evaling `(ns foo (:require seon.bar))`:** the `:ns`/`:ns*` branch (`js.cljs:1091-1105`) re-runs `ns-side-effects` — which re-emits `goog.provide` + re-walks the require chain. For requires of namespaces already in the analyzer cache, the `load-fn` (we pass `(partial boot/load compile-state)`) short-circuits because the ns is already known. **This means re-evaling an `(ns …)` form is safe AND cheap** — it re-establishes the alias map and refer table without re-loading dependencies. Critical for replay: replay can emit `(ns foo (:require seon.bar))` even if `seon.bar` hasn't been replayed yet, because the analyzer cache already has `seon.bar` from substrate boot.

**Re-evaling `(deftype …)` / `(defrecord …)`:** produces a new JS constructor at the same munged path. EXISTING instances of the old type are orphaned — their prototype chain still points at the old constructor. For v1 agent code this is almost certainly fine (no long-lived agent-defined type instances cross pod boots); flag in Open Questions.

**Re-evaling `(defprotocol P …)`:** protocol method tables live on the protocol var and on extended types' prototypes. Re-eval rebuilds the protocol but DOES NOT re-extend types that previously called `(extend-protocol P …)` in separate forms. Replay order matters here — the `extend-protocol` form must run AFTER the `defprotocol`, AND the order of `extend-protocol` forms relative to each other doesn't matter. Tx-id order naturally provides this if the agent wrote them in source order.

**Re-evaling `(defmacro m …)`:** bootstrap CLJS routes macros through a `$macros` ns (the analyzer rewrites them). Re-eval into the same compile-state replaces the macro fn. Tx-id ordering picks up correctly: macros defined first, callers later.

**The "bare value-def reads don't resolve across eval-str calls" gotcha** (documented in `eval.cljs:28-33`): does NOT apply to fn defs. So the resume walker can rely on `(defn foo …)` source replays restoring callable fns. Atoms defined via `(def !x (atom 42))` will be replayed too — the atom gets fresh state (its prior runtime state is lost), but the var binding is restored. This is the correct semantic: persisted source is the source-of-truth; runtime atom state is volatile per the three-tier storage rule.

### Q3. Cross-namespace dependency ordering

**Tx-id order IS topological for v1 — with one important nuance.** The v1.md argument is correct for the SOURCE-CREATION direction: a fn entity can't carry `:seon.fn/ns [:seon.ns/name :seon.bar]` unless `:seon.bar` ns entity exists at write time (lookup-ref resolution at tx commit, `db/transaction.cljc:652-655` `entid-strict`). So namespace entities precede their fn entities in tx-id order.

**BUT the dependency at REPLAY time is about CALLEE resolution, not entity refs.** If `alice/foo` calls `bob/bar` in its body, the entity graph captures `alice/foo`'s `:seon.fn/ns` ref to `alice` only — there is NO datom recording the `foo→bar` callee link in v1 (`:seon.fn/refs` is deferred to v2 — `v1.md:313-316`). So tx-id ordering says nothing about callee dependencies.

**Why this doesn't matter in practice for v1:** CLJS top-level `(defn foo [] (bob/bar))` does NOT evaluate the body at def time — only at call time. So the analyzer only needs `bob` to be a KNOWN ns (alias resolution) and `bar` to be a globalThis-resolvable munged path WHEN `foo` is called. Replaying `(defn foo [] (bob/bar))` BEFORE `bob/bar` exists succeeds (compiles to JS that does the lookup at call time). The first call to `foo` after replay completes will resolve correctly because by then `bob/bar` has been replayed.

**The genuinely problematic case** is top-level non-fn forms that EAGERLY resolve: `(def x (alice/foo))` or `(def y (+ alice/foo 1))`. If `alice/foo` isn't yet replayed, this errors. v1 agent code is supposed to be mostly `defn`/`schema/register!`/`ns` — but the agent CAN write `(def !cache (atom (load-stuff)))` and that errors on replay if `load-stuff` isn't yet defined.

**Decision:** tx-id order is good enough for v1. Failures on eager-resolve forms land as `:ok? false` replay-eval entries (Q6 design) and the agent self-corrects on next turn. Don't build a DAG. Document the limitation.

**The `(ns foo (:require bar))` analyzer false-positive risk:** because the substrate analyzer cache is fully loaded at boot (`eval.cljs:143-162` `load-all-analysis-caches!`), and the substrate is the only thing every agent ns can require from, the require chain at replay always resolves. Agent-defined nses requiring OTHER agent-defined nses (e.g. `(ns alice (:require bob))` where bob is also agent-defined) — bob's `:seon.ns/source` must precede alice's. Tx-id order delivers this IF the agent wrote bob first (which they must have, because alice's require lookup-ref demands it at write time — but the require is by symbol, NOT by lookup-ref, so this is NOT enforced. **Edge case: agent could write `(ns alice (:require bob))` before bob exists, and the analyzer-deps-off bootstrap accepts it.** At replay, alice's ns analysis fails to find bob's analyzer cache entry. The require fails but the ns still gets created — bob's symbols just aren't aliased into alice. Calls in alice's later defns that go `bob/x` resolve at call time via globalThis, so might still work.

This is messy enough that I recommend Open-Question-flagging it: live-probe whether `(ns alice (:require bob))` replay against an empty `:cljs.analyzer/namespaces 'bob` slot succeeds, fails silently, or fails loudly.

### Q4. Which compiler-state to replay against?

**Reuse the existing `@!compile-state`.** Reasons:

- It's already populated with the substrate analyzer cache (`load-all-analysis-caches!` ran during `init-bootstrap!`). A fresh state would have to re-load all those caches — wasted work.
- Replay is conceptually "restore the state the agent left off in" — the substrate state IS the floor of that. Wiping it and starting fresh would mean re-eval'ing every cljs.core form too, which we don't have source for.
- The version-stamp guard (`repl.cljs:99-105`) handles staleness — if `seon.eval` was hot-reloaded, `ensure-bootstrap!` rebuilds before resume sees it.

**`setup-agent-ns!` interaction:** `start-agent!` calls `(seval/setup-agent-ns! compile-state agent/default-ns agent/default-id)` AFTER `ensure-bootstrap!` (client.cljs:362-369). The setup primes `!session-id` / `!current-ns` / `!results` atoms in the agent's home ns. Resume should run BETWEEN `ensure-bootstrap!` and `setup-agent-ns!` — that way the home ns exists with current-session atoms (volatile, correct), and the replay can freely re-eval `(ns seon.agent.<id>)` forms without clobbering the atoms (re-eval of `(ns …)` doesn't clear existing defs in that ns; it just re-establishes the namespace).

Actually re-reading the boot sequence: `setup-agent-ns!` itself emits `(def !session-id (atom …))` — those are wiped if the agent's source contains a different `(def !session-id …)` for the same ns. The agent SHOULD NOT redefine these (they're substrate-managed), but a misbehaving agent could. Defensive option: run `setup-agent-ns!` AFTER resume, so substrate atoms always win the last-write race. This is the safer ordering.

**Recommended boot sequence (revised):**

```
1. ensure-bootstrap!          ; substrate compile-state ready
2. open-agent-conn!           ; conn ready
3. assert-preconditions!      ; :keep-history? + tx-meta attrs
4. resume-program-graph!      ; re-eval agent's persisted :source
5. setup-agent-ns!            ; substrate atoms last (defensive)
6. agent/boot!                ; run-turn-once! + kick listener
```

### Q5. Tx-meta on replay txes

The v1.md sketch wraps replay in `(with-tx-context {:seon.db/origin :replay :seon.db/replay? true} …)`. This is correct AS A FLOOR but doesn't fully address what to do when a replayed form has side-effect transacts.

**Most replays don't transact.** `(defn foo [] …)` doesn't write to the DB at eval time. `(schema/register! ::ticker :string)` mutates the in-memory Malli registry, no datahike tx. `(ns alice)` analyzes, doesn't transact.

**The exception is detect-and-tee.** If `eval-batch!` is the path replay goes through, then replaying `(defn foo …)` would re-fire detect-and-tee, transacting another `:seon.fn` entity. Net result: a no-op upsert (same `:seon.fn/sym`, same `:source`), but with NEW tx-id stamped `:seon.db/replay? true`. This:
- Pollutes the history with replay-no-op datoms.
- Re-anchors the tx-id of THIS entity, so next replay walks it in the LATEST tx-id slot (potentially changing replay order across boots).

**Recommendation:** replay must NOT go through `eval-batch!`. It calls `seval/eval` (or `raw-eval`) directly, bypassing both the per-form `with-tx-context` scope that eval-batch opens AND the detect-and-tee that record-eval would trigger. The replay-level `with-tx-context` provides the bundle; no eval entity gets written, no program-graph entity gets re-tee'd.

This also dodges Q6 — no need for synthetic replay turns, no need to make `:seon.eval` parent optional.

### Q6. Failure isolation

Given Q5's recommendation (replay doesn't go through eval-batch!, doesn't write `:seon.eval` entries), the failure-recording question simplifies: **replay failures land as `:seon.log` entries**.

The log table is already in `agent-bootstrap-attrs` (client.cljs:268-274) and supports level + source + message + stack. Replay can write:

```clojure
{:seon.log/at      (js/Date.)
 :seon.log/level   :warn
 :seon.log/source  "seon.client/replay-program-graph!"
 :seon.log/agent   [:seon.agent/id agent-id]
 :seon.log/message (str "replay of " entity-kind " " entity-key " failed: " err-msg)
 :seon.log/stack   err-stack}
```

The agent's next turn renders the warnings tile and sees the failure. No schema changes needed.

### Q7. Interaction with persistent backend

See §4 (SQLite recommendation) — this is the largest section because Sean's direction shifted from "separate conversation" to "SQLite is the backend, design it now".

### Q8. seon.schema registrations

**`schema/register!` is in-memory atom mutation.** It's not a datahike tx. The `:seon.schema` entity captures the SOURCE STRING (`(schema/register! ::ticker :string)`), and replay re-evals that source, which calls `schema/register!`, which repopulates the registry. Clean.

**Order matters for instrumented defns.** If a `:seon.fn/source` form has Malli `:malli/schema` metadata referencing `::ticker`, and the `:seon.schema/source` for `::ticker` hasn't been replayed yet, the defn's instrumentation init will throw on registration lookup.

Tx-id order naturally handles this: schema MUST be registered before a defn can be transacted with valid Malli metadata. The agent writes them in source order. Replay walks in tx-id order, schemas come first. (Same nuance as Q3 — edge case where the agent wrote them out of order — covered by failure isolation: instrumentation throw on a replayed defn becomes a `:seon.log` entry, agent self-corrects next turn.)

## Recommended implementation sketch

```clojure
(ns seon.client
  ...)

(defn ^:async ^:private query-program-graph-entries
  "Returns a vector of {:kind <:ns|:fn|:schema>, :ident <id-value>,
   :source <string>, :tx <long>} sorted by tx-id ascending. Reads
   against the CURRENT db (not history) so only currently-asserted
   sources land in the replay set."
  [conn]
  (let [db    @conn
        rows  (d/q '[:find ?ident ?source ?tx ?kind
                     :where
                     (or-join [?e ?ident ?source ?tx ?kind]
                       (and [?e :seon.ns/name   ?ident ?tx]
                            [?e :seon.ns/source ?source]
                            [(ground :ns) ?kind])
                       (and [?e :seon.fn/sym    ?ident ?tx]
                            [?e :seon.fn/source ?source]
                            [(ground :fn) ?kind])
                       (and [?e :seon.schema/key    ?ident ?tx]
                            [?e :seon.schema/source ?source]
                            [(ground :schema) ?kind]))]
                   db)]
    ;; NOTE: the ?tx bound here is the tx of the LATEST upsert of the
    ;; identity attr (single-card, last-write-wins). For ns/fn/schema
    ;; that's the same tx that wrote :source, because tee transacts
    ;; both attrs in the same tx. Verify on a live pod — if datahike's
    ;; query planner separates them, fall back to a pull keyed on the
    ;; identity attr + a separate history lookup for creation-tx.
    (->> rows
         (map (fn [[ident source tx kind]]
                {:kind kind :ident ident :source source :tx tx}))
         (sort-by :tx)
         vec)))

(defn ^:async ^:private replay-one!
  "Replay one entry. Wraps eval in try/catch. Returns
   {:ok? true} or {:ok? false :error <msg> :stack <str>}.

   For :ns kind, source already contains the (ns …) form — eval it as
   the active ns 'cljs.user (which switches us out). For :fn / :schema,
   we need to be IN the entity's owning ns before eval; the :source
   for these is a bare (defn …) / (schema/register! …) form."
  [compile-state agent-id {:keys [kind ident source]}]
  (try
    (let [ns-sym (case kind
                   :ns     'cljs.user                    ; (ns …) form switches ns itself
                   :fn     (-> ident (str/split #"/") first symbol)
                   :schema (-> ident namespace symbol))
          r      (await (seval/eval compile-state source
                                    {:ns ns-sym
                                     :analyze-deps? false}))]
      (if (:ok r)
        {:ok? true}
        {:ok? false
         :error (-> r :error :seon.error/message)
         :stack (-> r :error :seon.error/stack)}))
    (catch :default e
      {:ok? false :error (.-message e) :stack (.-stack e)})))

(defn ^:async ^:private log-replay-failure!
  [agent-id {:keys [kind ident]} {:keys [error stack]}]
  (db/transact!
    {:seon.db/tx-data
     [{:seon.log/at      (js/Date.)
       :seon.log/level   :warn
       :seon.log/source  "seon.client/replay-program-graph!"
       :seon.log/agent   [:seon.agent/id agent-id]
       :seon.log/message (str "replay of " (name kind) " "
                              (pr-str ident) " failed: " error)
       :seon.log/stack   (or stack "")}]}))

(defn ^:async replay-program-graph!
  "Re-eval every :seon.ns / :seon.fn / :seon.schema entity's :source
   in tx-id order. Failures land as :seon.log :warn entries and do
   NOT abort replay — every entity gets its own try.

   Bypasses eval-batch! so:
     - no per-form :seon.eval entity is written (replay is not eval'd
       in a turn; there's no turn yet)
     - detect-and-tee doesn't fire, so no replay-no-op upsert pollutes
       history or re-anchors entity tx-ids

   The :seon.db/origin :replay + :seon.db/replay? true tx-meta tags
   the (single) log-write tx per failure, so observer can filter
   replay-failure logs from agent-action logs.

   Returns:
     {:seon.client/replay-n-total <int>
      :seon.client/replay-n-ok    <int>
      :seon.client/replay-n-fail  <int>}"
  [{:keys [conn compile-state agent-id]}]
  (db/with-tx-context
    {:seon.db/origin   :replay
     :seon.db/replay?  true
     :seon.db/agent-id agent-id}
    (fn ^:async run-replay! []
      (let [entries (await (query-program-graph-entries conn))
            !n-ok   (volatile! 0)
            !n-fail (volatile! 0)]
        (doseq [entry entries]
          (let [r (await (replay-one! compile-state agent-id entry))]
            (if (:ok? r)
              (vswap! !n-ok inc)
              (do
                (vswap! !n-fail inc)
                ;; Best-effort log; ignore log-write failure (would be
                ;; double-fault). Don't await the result.
                (await (log-replay-failure! agent-id entry r))))))
        {:seon.client/replay-n-total (count entries)
         :seon.client/replay-n-ok    @!n-ok
         :seon.client/replay-n-fail  @!n-fail}))))

;; In start-agent! after assert-preconditions!, before setup-agent-ns!:
;;   (await (replay-program-graph! {:conn conn
;;                                  :compile-state compile-state
;;                                  :agent-id agent/default-id}))
```

**Same-pod-session test pattern** (no SQLite needed):

```clojure
;; In a REPL or test, after start-agent! has run:
(.then
  (seon.client/replay-program-graph!
    {:conn @seon.client/!agent-conn
     :compile-state @seon.repl/!compile-state
     :agent-id "seon"})
  (fn [r] (js/console.log "replay result:" (pr-str r))))

;; Verify: write a fn via agent, clobber its analyzer cache slot +
;; globalThis path, replay, confirm restored:
;;
;;   1. (seon.agent/chat "seon" "(defn alice/foo [] :ok)")
;;   2. (swap! @seon.repl/!compile-state update-in
;;        [:cljs.analyzer/namespaces 'alice :defs] dissoc 'foo)
;;   3. (js-delete (gobj/get js/globalThis "alice") "foo")
;;   4. (replay-program-graph! ...)
;;   5. (seon.eval/lookup-value 'alice/foo) => should be the fn, not nil
```

## SQLite backend recommendation

### Config flip — minimal

The CLJS-side adapter (`src/konserve_sqlite_cljs/core.cljs`) registers `:sqlite` against `konserve.store/-connect-store` / `-create-store` / `-store-exists?` / `-delete-store`. Datahike's connector dispatches via `(get-in config [:store :backend])` and treats konserve uniformly — no datahike-specific changes needed. Confirmed at `reference-code/datahike/src/datahike/connector.cljc:10-11` (it pulls in `konserve.core` and `konserve.store`).

The flip is one config in `open-agent-conn!`:

```clojure
(defn ^:async open-agent-conn! [{:keys [agent-id schema-version]
                                 :or {agent-id "seon"
                                      schema-version 1}}]
  (let [path (agent-db-path agent-id schema-version)
        cfg  {:store              {:backend :sqlite
                                   :path    path}
              :schema-flexibility :write
              :keep-history?      true}]
    ...))
```

### Versioned directory layout

```
~/.seon/
  agents/
    <agent-id>/
      v1/
        program.db          ; the single SQLite file (WAL + SHM siblings)
        program.db-wal
        program.db-shm
      v2/                   ; created when we bump the schema-version
        program.db
      current -> v1         ; symlink (or a small marker file) for "which
                            ; version this agent is currently using"
```

Helper: `(defn agent-db-path [agent-id schema-version] (str (os-home) "/.seon/agents/" agent-id "/v" schema-version "/program.db"))`. Ensure parent dirs exist before passing to `Database. path` (the konserve adapter doesn't mkdir — it'll throw `SQLITE_CANTOPEN`).

**Schema-version bumps:** when we change `agent-bootstrap-attrs` in a breaking way, bump `schema-version`. The new directory starts empty → bootstrap-phase runs → resume-phase replays nothing (empty DB). Old `v1/` directory is preserved untouched, can be manually migrated or referenced as archaeology. This is the "iterate without breaking existing agents" requirement.

**Pod-version vs schema-version:** I recommend KEEPING THESE DECOUPLED. The pod can ship a new version that's wire-compatible with `v1` data — we'd only bump `schema-version` when datoms-on-disk become invalid. A pod-version env var is fine for debugging but should not split DBs by default.

### Single-writer per file — fine

The konserve-sqlite-cljs adapter caches conns per `path` in a process-level atom (`core.cljs:91`), so multi-conn-same-process is collapsed safely. Multi-process is genuinely single-writer (SQLite WAL allows concurrent readers + one writer; the pod is the only writer). Our pod-host model is "one pod process per agent" anyway in v1, so this fits.

### Path to multi-agent-per-db

The v1 layout (`<agent-id>/v<n>/program.db`) is per-agent. To converge to multi-agent-per-DB later:

1. Schema is already namespaced — `:seon.agent/id` is the partition key. A multi-agent DB would have N `:seon.agent` root entities, each with their own component subtree.
2. The layout could shift to `~/.seon/pod/v<n>/program.db` (per-pod, multi-agent). Migration: write a one-shot tool that opens each per-agent DB, dumps its entities, transacts them into the unified DB.
3. `start-agent!` would shift from "open MY conn" to "share THE conn, scope queries to my agent-id".

Today's per-agent layout doesn't paint into a corner — it's the simpler single-writer case, and the schema's `:seon.agent/id` partitioning is preserved.

### `:keep-history? true` on SQLite — gotchas

LMDB stores history as additional datoms in the temporal indexes; SQLite-via-konserve does the same (the konserve layer is the abstraction — datahike doesn't know what storage it's on). **No SQL-level history schema** — it's all datoms-as-blobs. The on-disk size grows by ~2x per redefine (the original asserted datom + the retraction datom). For v1 agent volumes (hundreds of fns, low-thousands of redefines per agent lifetime) this is fine. Watch for it if an agent enters a hot-redef loop.

WAL mode is enabled by the adapter (`core.cljs:77`), so concurrent reads don't block the writer. Synchronous=NORMAL (line 78) trades a hair of crash-safety for write throughput — appropriate for a development pod, would re-evaluate for a long-running production deployment.

### Connect-time behavior on existing data

`d/create-database` is idempotent (it's `create-if-not-exists`). After config flip, the first boot creates the DB; subsequent boots `connect` to the existing one. The bootstrap-attrs schema transact (`client.cljs:300-302`) — what happens on a re-boot against an existing DB? Datahike treats `:db/ident` upserts on schema attrs as no-ops if the existing schema matches. If we CHANGE a schema attr (e.g. flip a `:db.cardinality/one` → `:many`), datahike will throw at boot — bump `schema-version` instead.

### Recommendation

Ship the SQLite flip behind a small refactor of `open-agent-conn!`:

```clojure
(defn ^:async open-agent-conn!
  ([] (open-agent-conn! {}))
  ([{:keys [agent-id schema-version backend]
     :or {agent-id "seon"
          schema-version 1
          backend (or (some-> js/process .-env .-SEON_DB_BACKEND keyword)
                      :sqlite)}}]
   (let [store-cfg (case backend
                     :memory {:backend :memory :id (random-uuid)}
                     :sqlite (let [p (agent-db-path agent-id schema-version)]
                               (ensure-parent-dir! p)
                               {:backend :sqlite :path p}))
         cfg       {:store store-cfg
                    :schema-flexibility :write
                    :keep-history? true}]
     ...)))
```

Tests + smoke-test stay `:memory`. Default for the pod is `:sqlite`. `SEON_DB_BACKEND=memory` for cases where you want ephemeral. This is testable as a same-pod-session before the SQLite flip, and as a real cross-restart test after.

## Risks + sequencing

| What | Risk | Mitigation | When to ship |
|---|---|---|---|
| Replay walker against `:memory` | Low — no cross-restart needed; same-pod test pattern proves the walker | Land first; ship test that wipes analyzer slot + globalThis + replays | Now (no deps) |
| `open-agent-conn!` refactor for backend choice | Low — additive arg | Default backend kept as `:memory` until SQLite probe lands | After walker |
| SQLite live probe | Medium — first time wiring datahike+konserve-sqlite-cljs end-to-end. Schema attrs may surface bugs in the adapter | Run smoke-test against `:sqlite` backend first; expand if it passes | After refactor |
| SQLite default flip | Low | Behind env var; can revert with `SEON_DB_BACKEND=memory` | After probe |
| `(ns alice (:require bob))` edge case (Q3) | Medium — silent require failures could leave alice in a broken state at replay time | Open Q for live probe; if confirmed problematic, add a two-pass replay (all `:ns` entities first, then all `:fn`/`:schema`) | Investigate during walker impl |

Per-session-only test for the walker (sketch in §3 above) means we can prove the analyzer/globalThis restoration works WITHOUT needing the SQLite flip first. Sequencing:

1. **Land walker** (this week). Verified via same-pod-session test.
2. **Land backend refactor** (additive). Defaults stay `:memory`.
3. **Probe SQLite** end-to-end (smoke test + walker against fresh DB + walker after pod restart).
4. **Flip default to SQLite** once probe is green.

## Open questions

These the source code didn't fully answer; live-probe before committing.

### Resume correctness probes

1. **Does `(ns alice (:require bob))` replay succeed when `bob` exists as a `:seon.ns` entity but hasn't yet been replayed in this session?** Q3 edge case. Probe: in a fresh agent session, transact `alice` AFTER `bob` chronologically in the DB but DON'T replay yet; replay walker hits alice first (by manual reordering for the test), observe whether the `(ns)` form errors loudly, errors silently, or no-ops the bad require.

2. **Does the v1 sketch's query — `[?e :seon.fn/sym ?ident ?tx]` against `@conn` — return the LATEST tx for a redefined entity, or the ORIGINAL tx?** I believe latest (last-write-wins on single-card identity attr), but verify with `mcp__seon_cljs__eval`: write `(defn foo [] :a)`, then `(defn foo [] :b)`, query bound `?tx` for `[:seon.fn/sym "alice/foo"]`. Confirm tx-id is the second write's, not the first's. If first's, the query needs a different shape (probably a `(max ?tx)` aggregate).

3. **Does re-evaling `(defrecord Foo …)` clobber existing instances' prototype chain in any visible way?** Low priority for v1 but worth knowing. Probe: create instance, redefine type, check instance behavior.

4. **What does cljs.js's `:undeclared-var` analyzer warning do when the `^:require`d ns IS in the analyzer cache but the specific var was never `def`'d there?** Relevant for replay-then-call: `bob` ns gets replayed (its `(ns bob)` form), then we call `bob/x` from alice's later-replayed defn body. If alice's compile time was earlier (with bob/x undeclared at THAT moment), the JS emitted by alice's defn does a runtime lookup that should find `bob/x` once it's later replayed — but verify.

### eval-batch concerns surfaced during resume research

These came up while deep-reading `eval.cljs` for the analyzer-state understanding needed to design resume. They are NOT blockers for the resume implementation itself, but they should land in MVP's queue.

5. **`eval-batch!` outcome paths and analyzer-state consistency.** `eval-batch!` (`eval.cljs:603-722`) has at least 6 distinct outcome paths per entry: read-failure (parse), eval-throw (compile or runtime), Promise-reject, Promise-timeout, ns-update-failure, record-eval-failure. The analyzer state lives in `@compile-state`. CLJS `eval-str` either fully analyzes-and-emits a form (state mutates) or errors before analysis (state unchanged). The Promise-reject and Promise-timeout paths happen AFTER `eval-str` returned successfully — so analyzer state is consistent. Eval-throw inside emit-then-eval (`*eval-fn*` in `js.cljs:1129`) happens AFTER analysis — state IS mutated, but the form's var is defined (the throw is in body execution, not def). This is probably fine, but no test currently asserts it. Worth a property test: for each outcome path, assert `@compile-state` post-state matches expected (vars defined / not defined as appropriate).

6. **Sequence-dependent state mutations.** `eval-batch!` is sequential (`doseq` over `parsed`, each entry `await`ed). But the inner `read-current-ns` / `update-current-ns!` go via `(eval compile-state src {:ns agent-ns-sym})` — separate calls. If entry N's eval mutated the ns, entry N+1's `read-current-ns` reads the mutated value (correct). But entry N's `update-current-ns!` runs AFTER `record-eval!` — if record-eval throws, update-current-ns has already run. Order is fine, but the `update-current-ns!` failure handler (`eval.cljs:524-526`) just logs and continues — the agent's `!current-ns` atom is now in an inconsistent state relative to the eval entity (the eval entity says it ended in ns X; the atom never got updated to X). Next entry's `read-current-ns` returns the OLD ns. Net: silent ns-drift on update failures. Probably very rare in practice (the update is a simple `reset!` form), but worth flagging.

7. **`with-tx-context` interaction with `await` inside `raw-eval`.** This is the critical one. `with-tx-context` uses Node's `AsyncLocalStorage` (`db.cljs:391-437`), which the comments correctly note IS fiber-local across awaits. `raw-eval` (`eval.cljs:296-354`) sets `ana/*cljs-warning-handlers*` via `set!` (NOT `binding`) — a global mutation. If two `eval-batch!` runs interleave (concurrent agents — v1 supports this), the second one's `set!` clobbers the first's handlers between the first's `set!` and its restore. Result: the first agent's warning handler captures warnings from BOTH agents' analyses. This is a real bug for v1's concurrent-agents goal — not a resume blocker (replay is single-threaded), but should be fixed before multi-agent ships. The fix is `binding` instead of `set!`, but `set!` was chosen for a reason (per the docstring at `eval.cljs:301-305` — the binding doesn't replace the analyzer's handler chain). Worth a deeper look.

8. **`init-version` rotation and an in-flight `eval-batch!`.** If `seon.eval` hot-reloads while an `eval-batch!` is in progress, `init-version` rotates. The in-flight batch holds a closure over the OLD `compile-state`; the next call to `ensure-bootstrap!` builds a NEW state, and subsequent batches use it. The in-flight batch finishes against the stale state; its results land in the DB. Subsequent batches don't see the in-flight batch's vars (they're on the old globalThis path, but the new compile-state's analyzer cache doesn't know about them). Resume on next boot fixes this (the DB has the source; replay re-defs into the new state) — but the live session is inconsistent. Documented behavior should explicitly say "hot-reload of seon.eval mid-batch loses in-flight defs until next pod restart".
