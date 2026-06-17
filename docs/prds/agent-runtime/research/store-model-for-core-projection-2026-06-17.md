---
type: research
status: active
tags: [research, database, cljs]
---

# Store model for the core program-graph projection

## TL;DR

**Recommendation: KEEP persisting the core projection, but collapse the three
half-measures into ONE provenance-keyed `reconcile-core!` pass — do NOT go
ephemeral.** Ephemeral core is technically feasible (datahike supports
multi-source `:in $core $agent` queries and in-process `:memory` conns), but it
buys nothing the render consumers can use cheaply: every consumer
(`namespaces-section`, the inspector) ITERATES per-kind `db/query` results and
joins in CLJS maps — it does NOT issue one corpus-wide Datalog join — so an
ephemeral split forces every one of those call sites to query two db values and
merge by hand, while the LMDB churn that motivates ephemeral is bounded and
one-time-per-boot, not per-write. The single correct persist path is a reconcile
that makes the `:core-seed`-origin row set EXACTLY equal the freshly-built index
(identity-upsert built + `:db/retractEntity` absent), sequenced as a PRE-AGENT
boot step so its `:core-seed` writes don't trip the origin-forge guard.

---

## Q1 — Ephemeral core feasibility

### (a) Can one `d/q` span TWO db values (`:in $core $agent`)? YES.

datahike's query engine first-classes multiple sources. `resolve-in`
(`reference-code/datahike/src/datahike/query.cljc:648-661`) binds each `SrcVar`
(`$core`, `$agent`) into `context.sources`:

```clojure
(defn resolve-in [context [binding value]]
  (cond
    (and (instance? BindScalar binding)
         (instance? SrcVar (:variable binding)))
    (update context :sources assoc (get-in binding [:variable :symbol]) value)
    ...))
```

Source-prefixed clauses (`[$core ?e :attr ?v]`) are resolved against their own
db at plan time (`query.cljc:2377-2391`):

```clojure
;; Source-prefixed clauses ($source pattern...) — must be checked BEFORE
;; data patterns because [$1 ?e :attr ?v] is also a vector.
(and (sequential? clause) (symbol? (first clause))
     (let [s (name (first clause))] (= \$ (first s))))
(let [src-sym  (first clause)
      src-db   (if sources (get sources src-sym resolve-db) resolve-db)
      ...]
  (cons src-sym resolved-inner))
```

and the planner explicitly branches on `multi-source?` (`query.cljc:3145-3175`),
passing `(:sources context-in)` through so "each clause resolves against its
source db". So `(d/q '[:find ?sym :in $core $agent :where (or [$core ?e
:seon.fn/sym ?sym] [$agent ?e :seon.fn/sym ?sym])] core-db agent-db)` is
supported.

CAVEAT: this works for db VALUES on the same peer. The two values do NOT share an
eid space — a join `[$core ?e :seon.fn/sym ?s] [$agent ?e :seon.fn/source ?src]`
(same `?e` across sources) is meaningless; multi-source queries must join on
VALUES (`?sym`), not eids. For the core projection that is fine (the corpus is
keyed by `:seon.fn/sym` etc., not eid), but it forecloses any "overlay agent
override onto the same entity" trick.

### (b) Is there a `db-with`/overlay to layer ephemeral datoms onto the persisted db value? YES, but it materializes.

`db-with` (`reference-code/datahike/src/datahike/db.cljc:158-162`,
`api/impl.cljc:124`) is `(:db-after (with db tx-data))` — it returns a NEW
immutable db value with the core datoms transacted in:

```clojure
(defn db-with
  "Applies transaction to an immutable db value, returning new immutable db value.
   Same as `(:db-after (with db tx-data))`."
  [db tx-data] {:pre [(dbu/db? db)]} (:db-after (with db tx-data)))
```

This DOES give a single unified db value that consumers query with no API change
— the overlay is the cleanest "one db, query uniformly" option. BUT: `with`
(`db.cljc:126-147`) runs the FULL `transact-tx-data` pipeline (schema validation,
index inserts into eavt/aevt/avet, and — because the cluster store has
`:keep-history? true` — temporal-index inserts too, `transaction.cljc:256-262`).
So building the overlay each boot is the same cost as transacting the core rows;
the only thing it avoids is DURABILITY (the overlay db value is never flushed to
konserve — `db->stored` only flushes when `not-in-memory?` AND `flush?`,
`writing.cljc:64-65`). It is an in-memory layer on top of the wire-backed db
value, recomputed each `@conn` deref — which means it must be re-applied on EVERY
read (the wire reader re-reads the branch root and reconstitutes a fresh db value
per `@conn`, `wire.cljs:7-11`), not once per boot. That per-read re-overlay cost
makes (b) unattractive versus simply persisting the rows once.

### (c) Cheapest merge for the render consumers — they ITERATE, not join.

The consumers do NOT issue a corpus-wide join, so neither (a) nor (b) is needed.
`namespaces-section` (`src/seon/ctx.cljs:955-1006`) runs THREE separate
single-pattern `db/query` calls (seed-tx set, ns→source map, ns→tx rows) and
joins them in CLJS with `into {}` / `filter` / `sort-by` / `get`. The inspector
and other surfaces follow the same shape. For an ephemeral split the cheapest
merge would therefore be: each consumer runs its query against BOTH the core db
and the agent db and `concat`/`merge`s the two CLJS result collections — N call
sites each gaining a second query + a merge. That is more code at more sites than
the persist path, and it must be kept in lockstep forever (a new consumer that
forgets the core db silently loses core rows).

**Q1 verdict:** Ephemeral is feasible via (a) multi-source `:in $core $agent` or
(b) a non-durable `db-with` overlay. But (c) shows the consumers iterate-and-
merge in CLJS, so ephemeral pushes a two-source merge into every render site with
no offsetting query-engine win. The overlay (b) preserves the single-db API but
re-pays its cost on every `@conn` read. Neither beats persist+reconcile.

---

## Q2 — If we KEEP persisting: the ONE correct reconcile

### The single pass

Make the set of `:core-seed`-origin program-graph rows EXACTLY equal the
freshly-built index:

1. **Upsert every built row** by its identity attr (`:seon.fn/sym`,
   `:seon.ns/name`, `:seon.test/sym`, `:seon.schema/key`). datahike identity-attr
   upsert means a re-asserted row with a changed `:source` REPLACES the prior
   source datom in place (one entity, history retains the old value). This heals
   drift UNIFORMLY across all four kinds — closing the latent `:seon.fn`/
   `:seon.test` presence-only staleness hole (Finding 6, B5) where an edited core
   `defn` body kept its stale stored source forever.
2. **Retract every `:core-seed` row whose ident is NOT in the built set** with
   `:db/retractEntity` (subsumes `prune-core-ghosts!`).

`core-index-tx` (`client.cljs:1408`) and `prune-core-ghosts!` (`client.cljs:1467`)
already gate on the SAME `:seon.db/origin :core-seed` provenance and the same
`registration-call-source?` agent-corpus carve-out — they are one mechanism split
in two. Collapse them.

### Retraction primitive — `:db/retractEntity`, NOT `[:db/retract e a v]`

To drop a whole ghost row, `:db/retractEntity` is correct. `retract-entity`
(`reference-code/datahike/src/datahike/db/transaction.cljc:713-730`) searches ALL
datoms on the eid and retracts them, and also walks `:db.type/ref` component
references:

```clojure
(defn retract-entity [db report op-vec]
  (let [[_ e] op-vec]
    (if-let [e (dbu/entid db e)]
      (let [e-datoms (vec (dbi/search db [e]))
            v-datoms (into [] (mapcat ...) (dbi/-attrs-by db :db.type/ref))]
        [(transduce cat transact-retract-datom report [e-datoms v-datoms])
         (retract-components db e-datoms)])
      [report []])))
```

A bare `[:db/retract e a v]` (`transaction.cljc:775-784`) does something DIFFERENT
and is the wrong tool for a revert: it searches for the EXACT `[e a v]` datom and
emits a single `added=false` retraction at the new tx — it does **NOT** roll the
attr forward to any prior history value:

```clojure
:db/retract (if-some [e (dbu/entid db e)]
              (let [a (dbu/normalize-and-validate-attr a op-vec db)
                    pattern (if (nil? v) [e a] ... [e a v])
                    datoms  (vec (dbi/search db pattern))]
                [(reduce transact-retract-datom report datoms) []])
              [report []])
```

This CONFIRMS the sandbox's correction (`override-sandbox-verify` Claim 4): a
single-cardinality `[:db/retract e :seon.fn/source <v>]` leaves the entity with NO
current `:seon.fn/source` — it does not revert to the `:core-seed` value. The
revert path for an override is `:db/retractEntity` on the override row; the next
boot's reconcile then re-seeds the compiled row for free (it is now ABSENT in
`have-fns`).

### History/index churn cost — retract-all+re-add-every-boot vs presence-dedup

The cluster store runs `:keep-history? true` (`wire.cljs` config;
`store/wire.cljs:107`). Under history, EVERY asserted-then-superseded datom writes
to BOTH the current and the temporal indices. `transact-add`
(`transaction.cljc:256-263`) on an upsert that replaces an old value increments
`:op-count` by **2** and inserts the removing datom + the new datom into
`temporal-eavt`/`temporal-aevt` (and `temporal-avet` if indexed):

```clojure
keep-history? (update-in [:temporal-eavt] #(di/-temporal-insert % removing :eavt op-count))
keep-history? (update-in [:temporal-eavt] #(di/-temporal-insert % datom   :eavt (inc op-count)))
keep-history? (update-in [:temporal-aevt] #(di/-temporal-insert % removing :aevt op-count))
keep-history? (update-in [:temporal-aevt] #(di/-temporal-insert % datom   :aevt (inc op-count)))
...
true (update :op-count + (if (or keep-history? indexing?) 2 1))))
```

These inserts land in the persistent hitchhiker-tree, which `db->stored` flushes
to konserve/LMDB on commit (`writing.cljc:75-83`, the `temporal-eavt'` etc.
flushes guarded by `(:keep-history? config)`). So **retract-all + re-add the
ENTIRE core projection every boot would write ~2 temporal datoms per core attr per
boot, permanently** — with ~192 fns × several attrs + 408 schemas + 221 tests,
that is thousands of temporal-index datoms accreting in LMDB on every single boot,
forever, even when nothing changed. That is the churn the existing presence-dedup
(`core-index-tx` `have-fns`, `client.cljs:1427`/`1457`) was built to avoid.

**The reconcile must be PRESENCE/DRIFT-keyed, not blind re-assert.** Emit an
upsert ONLY for a built row whose stored `:source` DIFFERS from the build (the
ns/schema rule at `client.cljs:1428-1454` generalized to fn/test), and a
`:db/retractEntity` ONLY for a `:core-seed` row absent from the build. On a
steady-state boot (nothing changed) the reconcile emits ZERO datoms — same
near-zero churn as today's `core-index-tx`, but now also healing fn/test drift and
pruning in one pass. Blind retract-all+re-add is correct-but-wasteful; drift-keyed
reconcile is correct AND cheap.

---

## Q3 — Wire-server interaction with an ephemeral LOCAL core overlay

The pod can create a local in-process `:memory` datahike-cljs conn with no wire
involvement — `open-agent-conn!`/`fresh-conn` already do exactly this
(`client.cljs:238-280`, `(d/create-database {:store {:backend :memory ...}})` then
`(d/connect ...)`). So an ephemeral local core db is mechanically possible. The
correctness problems it creates:

1. **Cross-process / cross-peer queries break.** The wire model
   (`wire.cljs:1-23`) is: the durable store holds the corpus, every peer reads it
   locally, and writes route to the single JVM writer. If core lives ONLY in each
   pod's local `:memory` db, then any OTHER reader of the central store (the
   wire-server itself, a second pod, a JVM-track consumer, the inspector if it
   ever reads the store directly) sees agent rows but NO core rows. The
   "section function that doesn't filter by agent sees the whole core" cross-agent
   property (CLAUDE.md reactive-context) depends on core being IN the shared store.
   Ephemeral-local core silently amputates the core half for every non-originating
   reader.

2. **Bitemporal history of core changes moves OUT of datahike into git.** Today a
   core fn edit, on reconcile, lands as a `:seon.fn/source` upsert whose prior
   value is retained in the temporal index — the store IS the bitemporal record of
   how core evolved. If core is rebuilt-from-source-each-boot and never persisted,
   that history exists ONLY in git. That may be acceptable (git is the real source
   of truth for compiled core), but it is a deliberate forfeit, not free — any
   datahike `as-of`/`history` query over core (e.g. "what was this fn's source last
   week") stops working.

3. **Two-db merge at every read (Q1c) under the wire's per-read reconstitution.**
   The wire reader rebuilds the agent db value on every `@conn`. An overlay/merge
   would have to be re-applied per read, and a `:memory` core conn is a SEPARATE
   value with a SEPARATE eid space — so consumers must use value-joins only (Q1a
   caveat).

**Q3 verdict:** An ephemeral local core overlay does create real correctness
issues — it breaks cross-process/cross-peer visibility of core and forfeits
datahike-native core history — UNLESS every core consumer is guaranteed to be the
same pod process that built the overlay. Given the architecture explicitly wants
the central store to be the shared corpus that any peer can read, ephemeral-local
core works AGAINST the wire design. Persist+reconcile keeps core in the one shared
place every reader already looks.

---

## Q4 — B1 migration (`:substrate-seed → :core-seed` retag), if persist stays

The rename (`41fccf0`) changed the origin literal in code but shipped no store
migration; pre-rename stores have core rows tagged `:substrate-seed` (fn 189, ns
54, schema 340, test 168) that every `:core-seed`-keyed mechanism ignores.

**The bitemporal-clean retag is NOT a simple in-place attr edit.** `:seon.db/origin`
is a datom on the TX entity, not on the program-graph row. You cannot "retag" the
historical seed txs cleanly: asserting `[:db/add <old-tx-eid> :seon.db/origin
:core-seed]` would write a NEW datom at a NEW tx (the old tx keeps its
`:substrate-seed` datom in history; the tx entity now has TWO origin values unless
you also retract the old). And the reconcile keys on the CURRENT source datom's tx
origin (`get-else $ ?tx :seon.db/origin`), so the rows' source datoms still point
at their original `:substrate-seed` txs.

Two honest options:

- **Just reset (recommended).** `bin/seon cluster reset` wipes the store and
  re-seeds uniformly under `:core-seed` (the post-reset live numbers in the audit
  confirm: legacy `:substrate-seed` = 0). Agent-authored work is the only thing
  lost, and on a pre-rename store the core seed regenerates anyway. For dev stores
  this is the cheap correct answer.
- **One-shot retag (only if a store must be preserved):** query the distinct tx
  eids that carry `:seon.db/origin :substrate-seed`, and for each transact `[[:db/add
  ?tx :seon.db/origin :core-seed] [:db/retract ?tx :seon.db/origin :substrate-seed]]`.
  This is a one-time migration filter (same class as the #7 poisoned-store filter).
  Because origin lives on the tx entity and the source datoms reference those same
  txs, the retag makes the reconcile/render see them as core. Bitemporal history
  does NOT make this "clean and in place" — you are adding+retracting a datom, and
  the old `:substrate-seed` assertion remains queryable in history. Acceptable, but
  reset is simpler.

Given dev stores are disposable and the code is already rename-clean (a
`:substrate-seed` row can't even be written now, audit B1), **reset is the
recommended migration**; carry the one-shot retag only if a specific store holds
irreplaceable agent work.

---

## Q5 — B4 sequencing: run the core seed/index as a PRE-AGENT step

ROOT (audit B4): `start-agent!` mints an agent, and the core seed/index transacts
(claiming `:core-seed`) execute INSIDE that agent's `with-tx-context` scope, so the
origin-forge guard (built to stop agents claiming core provenance) fires on the
legitimate seed (5× warn lines per boot).

**The fix is boot ordering, not a guard relaxation.** The core seed/index/reconcile
is a CORE, once-per-store step; it must run BEFORE any agent's `with-tx-context` is
established, in a context that legitimately claims `:core-seed`. Concretely:

- Lift `core-index-tx` + the new `reconcile-core!` OUT of `start-agent!` into a
  dedicated pre-agent boot phase that opens its OWN core-tagged tx-context (origin
  `:core-seed`) and runs to completion before the first agent is minted. The
  origin-forge guard (`db/internal`) is scoped to AGENT contexts; a core boot phase
  that is NOT an agent scope never trips it.
- Because the durable store is shared, this phase is idempotent (the drift-keyed
  reconcile emits zero datoms on a steady-state boot) and should run ONCE per pod
  boot regardless of how many agents are subsequently minted — matching the
  "build/merge/index (core, once) vs start-agent (per-agent)" separation the audit
  calls for (B4, B6).
- On the JVM/Integrant track the same step is an Integrant key that depends on the
  DB component and is depended-on-by the agent component (`:seon.core/seed`
  ordered before `:seon.agent/*`), so lifecycle ordering enforces pre-agent. On the
  pod (active), it is a sequential `await` in the boot pipeline before
  `start-agent!`.

This dissolves B4 (no agent-scoped `:core-seed` claim → no forge warning) and is
the same provenance-keyed core phase the reconcile and the #8 override replay-skip
both build on (one mechanism, code-as-data).

---

## Recommended plan

1. **Keep persisting core. Do NOT go ephemeral.** (Q1/Q3 — consumers iterate-and-
   merge in CLJS; ephemeral breaks cross-peer visibility and forfeits core history,
   for no query-engine win.)
2. **Collapse `core-index-tx` + `prune-core-ghosts!` into one drift-keyed
   `reconcile-core!`** (Q2): upsert built rows whose stored source DIFFERS (heal
   drift uniformly across fn/ns/test/schema, fixing the fn/test staleness hole),
   `:db/retractEntity` `:core-seed` rows absent from the build. Drift-keyed, not
   blind re-assert — steady-state boot emits zero datoms (Q2 churn evidence).
3. **Sequence `reconcile-core!` as a PRE-AGENT core boot phase** with its own
   `:core-seed` tx-context (Q5) — dissolves B4. Integrant key on JVM / sequential
   await before `start-agent!` on the pod.
4. **Migrate B1 by reset** (Q4); carry the one-shot tx-origin retag
   (`:db/add`+`:db/retract` on the seed txs) ONLY for a store with irreplaceable
   agent work.
5. **Revert/override drop uses `:db/retractEntity`** on the row, never
   `[:db/retract e a v]` (Q2 + override-sandbox Claim 4). Reconcile then re-seeds
   the compiled row.

### Risks

- **Drift-keyed reconcile must emit a correct nested `:seon.fn/ns
  {:seon.ns/name <kw>}`** when it re-upserts a changed fn — the Run-3 malformed-ns
  bug the presence-only dedup was dodging (`core-index-tx` docstring 1419-1421).
  Fix that root cause; do not restore presence-only as a workaround.
- **`:db/retractEntity` on a component-bearing core row** cascades to its
  components (`retract-entity` walks `:db.type/ref` component refs,
  `transaction.cljc:728-729`/`546-549`). Confirm no core program-graph row has
  `:db.type/ref`+`:db/isComponent` children that should survive a prune.
- **History growth is bounded but non-zero.** Each genuine core edit writes 2
  temporal datoms per changed attr per boot. That is the correct, intended cost of
  keeping core history in datahike; just ensure the reconcile does NOT re-assert
  unchanged rows (else it pays this on every boot — Q2).

---

## Could not fully resolve from source

- **Exact LMDB on-disk byte cost per temporal datom.** The churn argument is
  grounded in the datahike-side op-count/temporal-insert logic (`transaction.cljc`)
  and the `db->stored` flush gating (`writing.cljc`), which proves blind re-assert
  writes ~2 temporal datoms/attr/boot into the persistent hitchhiker-tree. The
  `konserve-lmdb` submodule was empty in this checkout (`reference-code/konserve-lmdb`
  has no `src/konserve/lmdb.cljc`), so the precise LMDB page/value encoding cost of
  one flushed hitchhiker-tree node could not be quoted from source — the
  conclusion (drift-keyed >> blind re-assert under `:keep-history?`) holds from the
  datahike layer alone, but the absolute disk figure is unquantified here.

---

## Cross-references

- `docs/prds/agent-runtime/research/simplification-audit-2026-06-17.md` —
  B1/B4/B5/B6, Finding 6 (the reconcile-into-one recommendation this grounds).
- `docs/prds/agent-runtime/research/override-sandbox-verify-2026-06-17.md` —
  Claim 4 retract correction (this quotes the datahike source behind it).
- `docs/seon/concepts/code-as-data-runtime.md` — upsert+history rubric.
- datahike source: `query.cljc` (resolve-in 648, multi-source 2377/3145),
  `db.cljc`/`api/impl.cljc` (with/db-with 126/158/124), `db/transaction.cljc`
  (retract-entity 713, :db/retract 775, temporal-insert 256-263, retract-components
  546), `writing.cljc` (db->stored flush gating 47-83).
- seon source: `client.cljs` (query-program-graph-entries 628, core-index-tx 1408,
  prune-core-ghosts! 1467, :memory conn 238-280), `ctx.cljs` (namespaces-section
  955), `db.cljs` (origin enum 189), `store/wire.cljs` (read locality 1-23, config
  107).
