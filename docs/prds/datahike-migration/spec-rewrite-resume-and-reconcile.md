---
type: research
status: active
tags: [research, agent, database]
---

# Spec-rewrite cluster: Resume + offline-first

Companion design draft for spec-02 §E.4 (pod resume) and §B.3 (offline-first reconcile).
Scope: replay-safety classifier; `:seon.fn/depends-on` schema + topo sort; reconcile
strategy for V1. Read-only research; cite existing seon code where it already encodes
the entity shapes.

## Findings

### Q1. Replay-safe form classification

The "head-walk" default in spec-02 §E.4 is the right floor, but five edge cases break
the pure `def*` rule:

1. **`defonce` with an effectful initializer** — `(defonce conn (start-mcp! ...))`.
   `defonce` itself is idempotent, but the initializer runs the first time and on
   replay it *would* run again into a fresh namespace. Treat as **effectful**: the
   form head is replay-safe, but the body contains a non-pure call. The conservative
   default is to classify `defonce` as effectful unless the rhs is a literal.
2. **`def` of a value computed by a call** — `(def schema (make-schema {...}))`.
   Without per-callee purity, we can't prove safety. Treat as **effectful by default**;
   let the agent opt-in via `^:seon.eval/replay` metadata.
3. **`defn` with a top-level `when` / `assert`** — body-side, not form-head; never
   runs at definition time. Safe.
4. **`(load-file ...)`, `(require ... :reload)`, `(in-ns ...)` outside of an
   `ns` form** — `require`/`use`/`import` are safe; `load-file` and `:reload`
   flags are not (they re-execute user code).
5. **`defmethod` against an unloaded multi** — replay-safe only if the multi's
   `defmulti` was also captured. The topo sort (Q2) handles this.

Override metadata: `^:seon.eval/replay true` / `^:seon.eval/effect true` on the
top-level form. Wins over the default classifier in both directions.

```clojure
(def ^:private replay-safe-heads
  '#{def defn defn- defmacro defmulti defmethod defprotocol defrecord
     deftype in-ns require use import ns})

(defn classify-form
  "Return :replay or :effect for a top-level form. Reads the form head and
   honors override metadata. defonce/def with non-literal rhs default to :effect.
   (unverified: this matches the spec-02 §E.4 default plus the five edge cases
   above; needs an empirical pass once eval-interception is wired.)"
  [form]
  (let [m (when (seq? form) (meta form))]
    (cond
      (:seon.eval/replay m) :replay
      (:seon.eval/effect m) :effect
      (not (seq? form)) :effect
      :else
      (let [head (first form)
            rhs  (nth form 2 nil)]
        (cond
          (= head 'defonce) (if (or (nil? rhs)
                                    (and (not (seq? rhs))
                                         (not (symbol? rhs))))
                              :replay :effect)
          (= head 'def)     (if (or (nil? rhs)
                                    (and (not (seq? rhs))
                                         (not (symbol? rhs))))
                              :replay :effect)
          (contains? replay-safe-heads head) :replay
          :else :effect)))))
```

The classifier writes `:seon.fn/replay-safe?` at ingest time. Effectful forms still
get a `:seon.fn` entity (so the agent's first-turn context can list them); they just
don't auto-eval on resume.

### Q2. `:seon.fn/depends-on` schema + topo-sort

`seon.graph.ingest` (lines 71-74) already stores `:seon.call/from-fn` and
`:seon.call/to-fn` as ref-edges between `:seon.fn/qualified-name` entities. That edge
*can* derive a depends-on set, but it has two limits for resume:

1. **Call edges include runtime calls inside fn bodies**, not just definition-time
   dependencies. A `defn foo` that calls `bar` only requires `bar` to *exist as a
   var* at def time (Clojure's late binding via `#'bar`) — except for macros,
   protocols, and record/type references, which must be resolved at definition time.
2. **Edge entities lack identity attrs** (line 142-149) — they're retract-then-insert
   per scan. Resume needs a stable per-fn dependency snapshot, not a join through
   call-edges.

Recommend an additional, explicit attribute:

```clojure
(seon.schema/register! :seon.fn/depends-on [:vector :seon.db/ref])
;; cardinality-many ref to other :seon.fn entities that must be eval'd first
```

Populated at eval-interception time from CLJS analyzer output. The analyzer's per-form
analysis surfaces `:uses` (vars referenced) and `:requires` (namespaces required);
filtering `:uses` to the user's own namespace gives the def-time dependency set.
(unverified: the exact CLJS analyzer key is `:uses` / `:imports` in `cljs.analyzer`'s
namespace-info map; the pod-side libdatahike-cljs work has not yet exposed it. Read
`cljs.analyzer/parse` and `cljs.analyzer.api/analyze` in
`~/src/workspace.ai/reference/` when wiring this.)

Topo sort with cycle detection (Kahn's algorithm; cycles get one of the participating
fns flagged and the cycle eval'd as a batch — Clojure handles mutual recursion via
late binding, so the order within a cycle is irrelevant as long as the vars are all
def'd before any of them is *called*):

```clojure
(defn topo-sort-fns
  "Given a seq of :seon.fn entities (each with :seon.fn/qualified-name and
   :seon.fn/depends-on as a set of qualified-names), return a vector of
   fn entities in eval order. Cycles are emitted as a single batch at the
   point all their incoming non-cycle deps are satisfied."
  [fns]
  (let [by-name (into {} (map (juxt :seon.fn/qualified-name identity)) fns)
        deps    (into {} (map (fn [f]
                                [(:seon.fn/qualified-name f)
                                 (set (:seon.fn/depends-on f))]))
                      fns)
        ;; Tarjan SCC condensation handles cycles
        sccs    (strongly-connected-components deps)]
    (->> sccs
         (mapcat (fn [scc]
                   (if (= 1 (count scc))
                     [(by-name (first scc))]
                     (mapv by-name scc))))   ; cycle: emit batch in scc order
         vec)))
```

`strongly-connected-components` is one Tarjan or Kosaraju pass on the deps map; not
inlined here for length. (unverified: the implementation; `loom.alg/scc` is the
standard Clojure choice but pulls a dep — for V1 a 40-line hand-rolled Tarjan is
fine.)

### Q3. Offline-first reconcile strategy for V1

Datahike does not natively support CRDTs or OT. Replikativ has `replikativ` (the
sibling library to konserve/datahike) that builds CRDTs on top of konserve, but
binding that to a live datahike conn is *not* an out-of-the-box flow.
(unverified: replikativ's docs at <https://github.com/replikativ/replikativ> describe
CDVCS — a CRDT version-control model — that could in principle layer over datahike,
but neither datahike nor kabel exposes the integration. Treat as a deferred research
question, not a V1 dependency.)

Of the four V1 options:

- **(a) LWW at attribute level** — simplest. Loses agent intent silently. Acceptable
  for read-mostly state (`:seon.user/working-on`); dangerous for accumulators
  (`:seon.user.user-X/notes`).
- **(b) CRDTs** — not in the box. Defer.
- **(c) Per-attribute strategy declared in schema** — Malli already extensible.
  `[:string {:seon.merge/strategy :lww}]`. Composes with (a). This is the V1 pick.
- **(d) Convergent through tx-bus replay** — apply offline txs in author order,
  surface attribute-level conflicts as needing-attention entities; agent decides
  on next tick. This is the V1 *mechanism*; (c) is the per-attribute policy it
  consults.

**V1 recommendation: (c) + (d) combined.**

```clojure
;; Schema-level declaration (extends the existing :seon.merge namespace; net-new):
(seon.schema/register! :seon.user.user-123/working-on
                       [:string {:seon.merge/strategy :lww}])
(seon.schema/register! :seon.user.user-123/notes
                       [:vector {:seon.merge/strategy :append}
                        :seon.db/ref])
(seon.schema/register! :seon.user.user-123/active-session
                       [:keyword {:seon.merge/strategy :error-on-conflict}])

;; Strategies for V1:
;;   :lww                — last :seon.tx/at wins (default for scalars)
;;   :append             — both sides' adds union; no removes lost
;;   :prepend            — like :append; agent decides ordering
;;   :error-on-conflict  — surface to needing-attention; agent resolves on next tick
;;   :owned-by-user      — pod's value wins (e.g. local-only working state)
;;   :owned-by-server    — server's value wins (e.g. billing, identity)
```

The reconcile flow on reconnect:

1. Pod sends accumulated offline txs (each carrying `:seon.tx/from-user-id`,
   `:seon.tx/at`, `:seon.tx/origin :pod`) in order.
2. Server's relay-writer runs each through `seon.db/transact!`. For every `[e a v]`
   datom, it consults the attr's `:seon.merge/strategy`:
   - `:lww` → keep whichever side has the later `:seon.tx/at`.
   - `:append` / `:prepend` → both sides' additions land; retracts on a value the
     other side added become a conflict.
   - `:error-on-conflict` / `:owned-by-*` → as named.
3. Unresolved conflicts transact a `:seon.conflict/*` entity referencing the
   `[e a]` pair and both candidate values. The agent sees these as part of its
   first-turn ctx after reconnect and resolves explicitly.
4. After resolution, the pod's replica fast-forwards to the server's post-merge
   head.

**Honest assessment:** this is "good enough for one user, one pod, one server"
(spec-02 §B.3 V1 target). It does not handle:

- **Two pods for the same user, both offline** simultaneously. The merge is
  pairwise-linearized through the server, so simultaneous offline writers on the
  *same attribute* fall to LWW even where the attribute declares `:append` (the
  retraction-vs-add case). Acceptable for the demo; flagged as a known limit.
- **Schema drift** — agent A on the pod registers a new attr while offline; agent
  B on the server registers a different attr at the same key. Schema reg is
  serialized through the JVM master, so this is a *namespace-registration* race
  rather than a tx race. Defer behind §A.1 single-writer guarantee.
- **Retraction races** — pod retracts `[e a v]`, server adds `[e a v']` with a
  later timestamp. Under `:append`, the retract is a *partial* retract; the add
  survives. Document the rule, don't try to be clever.

(unverified: replikativ + kabel together provide a "consistent-mergeable" CRDT
layer that *could* replace this for V2 — see <https://replikativ.io/>. The
research note in `docs/research/datahike-as-sidecar` from 2026-05-10 may already
cite which parts of this are wired; cross-read before V2.)

## Draft spec section — "Resume + offline-first"

> Insert as new §E.6 ("Resume") and rewrite §B.3 ("Offline-first reconcile").
> Both replace the deferred-by-handwave framing in the current spec-02.

### §E.6 Resume — what happens on pod boot

When a pod connects with `{:op :hello :user-id "user-123" :resume? true}`, the
server walks the user's namespace and replies with a **resume bundle**: the set
of `:seon.fn` entities with `:seon.fn/updated-by` = this user, partitioned by
`:seon.fn/replay-safe?` and topo-sorted by `:seon.fn/depends-on`.

The pod's eval pass:

1. Eval each replay-safe form's `:seon.fn/source` string in topo order. Cycles
   are emitted as a single batch — Clojure's late binding via `#'var` handles
   intra-cycle calls as long as all vars are def'd before any is called.
2. Effectful entities (`:seon.fn/replay-safe?` = `false`) are **not eval'd**.
   They land in the agent's first-turn ctx as a `:seon.agent.ctx/unrestored-fns`
   list — qualified-name plus source plus the classifier's reason — so the
   agent can choose whether to re-run.
3. Schemas attached to fns (`:malli/schema` metadata picked up at eval) are
   re-registered as a side effect of the eval, so contract instrumentation
   survives resume without an explicit schema replay pass.

Form classification runs **at eval time**, not at resume time. The pod's
eval-interception (per Q1's `classify-form`) writes `:seon.fn/replay-safe?`
when the entity is transacted. The agent can override per-form with
`^:seon.eval/replay` / `^:seon.eval/effect` metadata.

Dependency capture **also runs at eval time**. The pod's CLJS analyzer surfaces
the form's `:uses` set; mapping each `:uses` symbol to a `:seon.fn/qualified-name`
gives the `:seon.fn/depends-on` cardinality-many ref. Cross-namespace deps
(into seon core, into other users' namespaces) are filtered out — only deps
within the user's own namespace are persisted. The cross-namespace deps are
already implied by `(require ...)` forms, which themselves are replay-safe.

The first-turn message after a successful resume is:

```text
Resumed. Eval'd 47 of 51 captured fns (4 effectful, listed in ctx).
Last activity: 14 hours ago. 2 attribute conflicts pending (see
:seon.agent.ctx/conflicts).
```

### §B.3 (rewritten) — Offline-first reconcile

A pod that loses its server connection keeps writing locally. Each transaction
carries provenance:

```clojure
{:tx-data [...]
 :tx-meta {:seon.tx/from-user-id "user-123"
           :seon.tx/origin       :pod
           :seon.tx/at           #inst "2026-05-15T..."
           :seon.tx/local-ord    47}}
```

`:seon.tx/local-ord` is a pod-monotonic counter that survives pod restart (stored
on the local replica). It is **not** a vector clock — pairwise causality is
inferred from `:seon.tx/at` on reconcile, with `:seon.tx/local-ord` only as a
tiebreaker for same-instant offline writes.

On reconnect, the pod sends its offline txs in `:seon.tx/local-ord` order. The
server's relay-writer runs each through `seon.db/transact!`, but with one extra
pass per `[e a v]` datom: consult the attr's `:seon.merge/strategy`, compare to
any conflicting datom on the same `[e a]` pair written by another writer during
the offline window, and:

- `:lww` → keep the later `:seon.tx/at`.
- `:append` / `:prepend` → both adds land; the retract-vs-add case is a conflict.
- `:error-on-conflict` / `:owned-by-user` / `:owned-by-server` → as named; the
  loser becomes a `:seon.conflict/*` entity that the agent's first turn after
  reconnect must address.

Strategies are declared at schema-registration time:

```clojure
(seon.schema/register! :seon.user.user-123/working-on
                       [:string {:seon.merge/strategy :lww}])
(seon.schema/register! :seon.user.user-123/notes
                       [:vector {:seon.merge/strategy :append}
                        :seon.db/ref])
```

V1 does **not** handle two pods for the same user being offline simultaneously
(see Q3 honest-assessment list). It does handle the common case: one user, one
pod, one server, hours-to-days offline window, dozens-to-hundreds of accumulated
txs. That is the §B.3 demo target.

Reconcile is implemented as a thin layer in `seon.db.datahike.flow` (relay-writer
side), reading `:seon.merge/strategy` from Malli's properties via
`seon.schema/properties`. **No new flow process**; reconcile reuses the existing
single-writer route on the master. (unverified: `seon.schema/properties` already
exposes Malli properties verbatim in M-3; if not, add it as a one-liner.)
