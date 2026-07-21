---
type: research
status: draft
tags: [research, agent]
---

# Datahike query capabilities for reactive testing infrastructure

Source read: `~/src/datahike` (HEAD as of 2026-05-16, mirrored in `reference-code/datahike`). All citations use that tree.

## TL;DR

- **Recursive rules: YES.** Full Datomic-style recursive + mutually recursive rules with SCC handling. See `src/datahike/query.cljc:1000-1142`.
- **Pull recursion: YES.** Both `'...'` (unbounded) and integer-depth forms, delegated to `datalog.parser.pull` and consumed by `pull-recursion-frame` in `src/datahike/pull_api.cljc:47-118`.
- **No built-in `transitive` operator** — you express transitive closure with a recursive rule (or pull recursion when the relation is a single ref attr from the entity).
- **CLJS parity is near-100% for the query engine.** Every relevant file is `.cljc`. Only `.clj`-only files are codegen/CLI/pod/HTTP-server/JVM-pod glue.
- **tx-listener fires synchronously on the writer go-block** after commit, before the caller's promise is delivered. `tx-report` carries `:db-before`, `:db-after`, `:tx-data`. Calling the **async** `transact!` from inside is safe; calling the **sync** `transact` deadlocks (documented).
- **`missing?`, `or`, `or-join`, `not`, `not-join`, `and`, predicates** all present. `(and ...)` nested inside `(or ...)` is legal — `or` branches resolve via `resolve-clause`, which dispatches to the `[and *]` arm.
- **Schema flexibility `:write`** lets you transact NEW `:db/ident` attrs at any time. `update-schema` in `src/datahike/db/transaction.cljc:88-118` mutates the schema in-place on every tx that carries a schema datom.

---

## 1. Recursive queries

**Verdict: YES — full Datomic-style recursive rules. Plus pull recursion. No standalone `transitive` operator.**

### 1a. Recursive rules

`src/datahike/query.cljc:1000-1142` is the `;;; RULES` section.

- `parse-rules` (`query.cljc:601`) groups rule branches by name.
- `rule?` (`query.cljc:1002-1007`) detects a rule call by checking against `(:rules context)`.
- `expand-rule` (`query.cljc:1018-1038`) clones a branch with fresh `__auto__<seqid>` suffixes per call — this is what makes recursion terminate cleanly (each recursive invocation gets fresh vars).
- `solve-rule` (`query.cljc:1067-1142`) runs a worklist over a stack of `{:prefix-clauses :prefix-context :clauses :used-args :pending-guards :clause}` frames, expanding rule clauses lazily and pruning dead branches (empty rels → drop frame; `-differ?`-trivially-false guards → drop frame).
- The planner has a dedicated `:recursive-rule` op tag with `scc-rule-names` / `scc-rule-plans` for **mutually recursive rule sets** (`query.cljc:201-228`). So `(ancestor)` calling `(descendant)` calling `(ancestor)` is handled, not just self-reference.

The plan cache key (`query.cljc:2132`) includes `rules-keys`, so plans are memoised per rule set.

### Example: transitive `:seon.ns/requires` walk

Given schema (paraphrased from the seon style):

```clojure
(schema/register! :seon.ns/name      [:keyword {:seon.db/identity true}])
(schema/register! :seon.ns/requires  [:vector :seon.db/ref])

```

The transitive-reachability rule:

```clojure
;; Returns every ns ?dep that ?ns transitively requires.
(d/q '[:find  [?dep-name ...]
       :in    $ % ?ns-name
       :where [?ns :seon.ns/name ?ns-name]
              (depends-on ?ns ?dep)
              [?dep :seon.ns/name ?dep-name]]
     @conn
     '[[(depends-on ?n ?dep)
        [?n :seon.ns/requires ?dep]]
       [(depends-on ?n ?dep)
        [?n :seon.ns/requires ?mid]
        (depends-on ?mid ?dep)]]
     :seon.agent.loop)

```

And the **reverse** direction — "which namespaces depend on this one transitively" (the one you actually want for affected-tests):

```clojure
(d/q '[:find  [?dependent-name ...]
       :in    $ % ?target-name
       :where [?target :seon.ns/name ?target-name]
              (depends-on ?dependent ?target)
              [?dependent :seon.ns/name ?dependent-name]]
     @conn
     '[[(depends-on ?n ?dep)
        [?n :seon.ns/requires ?dep]]
       [(depends-on ?n ?dep)
        [?n :seon.ns/requires ?mid)
        (depends-on ?mid ?dep)]]
     :seon.db)

```

Notes & caveats:

- Datahike inherits the standard Datomic rule-direction caveat: rules walk fastest when the recursive arg is bound. For the "downstream dependents" query above, `?target` is bound first, but the rule recurses on the first arg (`?n`) which is unbound at entry — the engine will materialise all `[?n :seon.ns/requires ?dep]` rows then filter. For a 1k-ns graph this is fine; for 100k-edge graphs you'd want to either invert the rule (write `depended-on-by` recursing the other direction) or partially-evaluate from both ends.
- The `-differ?` guard machinery (`query.cljc:1046-1051`) prevents the engine from re-entering a rule with the same arg tuple, which kills infinite loops in cyclic graphs (require cycles between namespaces, if any). Don't rely on it as a correctness guarantee against pathological cycles, but it stops the obvious termination case.

### 1b. Pull recursion

`src/datahike/pull_api.cljc:47-118` implements bounded + unbounded recursion:

- Recursion bookkeeping in the frame (`:recursion {:depth {} :seen #{}}`) tracks both per-attr depth and the seen-set to break cycles.
- `pull-recursion-frame` (`pull_api.cljc:81-92`) walks a list of eids, building the child results.
- `recursive-frames` (`pull_api.cljc:97-118`) compares current `depth` against the pattern's `:recursion` value (an integer from `{... :recursion N}`, or `:datalog.parser.pull/...` / unbounded marker from the parser).
- Parsing comes from external lib `datalog.parser.pull` (referenced at `pull_api.cljc:5`). That parser is the same one Datascript uses and supports the standard pull-syntax recursive forms: `{:foo/children ...}` (unbounded), `{:foo/children 5}` (bounded depth 5).

This is **only useful for transitive walks along a single ref attr**, not joins. For `:seon.ns/requires` (a `:cardinality/many` ref) you can do:

```clojure
(d/pull @conn '[:seon.ns/name {:seon.ns/requires ...}] [:seon.ns/name :seon.agent.loop])
;; => {:seon.ns/name :seon.agent.loop
;;     :seon.ns/requires [{:seon.ns/name :seon.db
;;                         :seon.ns/requires [...]}
;;                        ...]}

```

For "which namespaces are reachable" you'd then flatten the result — but the rule version above is the cleaner shape if you just want a `[...]` set.

### 1c. Built-in transitive operator?

**No.** Greppable: `grep -rn "transitive\|closure" src/datahike/` returns no operator implementation. The only `recur` in the query engine is `loop`/`recur` Clojure constructs. The recursive-rule machinery IS the transitive-closure mechanism in this engine — same as Datomic.

---

## 2. CLJS support parity

**Verdict: YES — full parity for query/pull/rules/listeners.**

Survey of `src/datahike/` (`find … -name '*.clj' -not -name '*.cljc'`):

| File | JVM-only? | Used by query path? |
|------|-----------|---------------------|
| `cli.clj`, `codegen/*.clj` | yes | no — build-time only |
| `http/client.clj`, `http/writer.clj` | yes | only for HTTP remote |
| `js/api_macros.clj` | yes | no — macro at build time |
| `migrate.clj` | yes | one-shot migration tool |
| `norm/norm.clj` | yes | namespace normalization, not query |
| `pod.clj` | yes | bb pod glue |
| `query.cljc`, `query/*.cljc` | **no — full cljc** | yes |
| `pull_api.cljc` | **no — full cljc** | yes |
| `db.cljc`, `db/*.cljc` | **no — full cljc** | yes |
| `core.cljc`, `connector.cljc`, `writer.cljc`, `writing.cljc` | **no — full cljc** | yes |

Specific features confirmed cross-platform:

- **Recursive rules**: `query.cljc` reader-conditionals are `:clj`/`:cljs` only for `Number`/`Date` extension (`query.cljc:444,450`) and `js/Math.abs` (`query.cljc:455`). The rule machinery itself is uniform.
- **`missing?`**: `-missing?` at `query.cljc:424` is plain Clojure, no reader conditionals. Registered for both via the literal map at `query.cljc:548`.
- **`or` / `or-join` / `not` / `not-join` / `and`**: `query.cljc:1925-1999` — all plain Clojure.
- **Predicates in `:where`** (`[(pred ?a ?b)]`): `filter-by-pred` / `bind-by-fn` at `query.cljc:1907-1918` — no `:clj`-only branches.
- **Pull**: `pull_api.cljc` reader-conditional is only for the `:cljs` refer of `PullSpec` (line 5).
- **Listener**: `core.cljc:206-224` (listen!/unlisten!) — plain atoms in metadata, no reader conditionals.

The only practical CLJS caveat I can find is at `writer.cljc:74`/`writer.cljc:249` — error/result delivery uses `put!` in CLJS vs `deliver` in CLJ, but that's an implementation detail behind the public API.

**Caveat — bootstrapped CLJS (`cljs.js`):** The seon V0 pod uses `cljs.js` self-hosted. Anything that depends on macros expanded at compile time should still work because `.cljc` is processed as Clojure-style code at compile time, but a few things in `query.cljc` use Clojure-side reader conditionals that get expanded by the cljs compiler — fine in AOT, fine in self-hosted (cljs.js handles cljc). I did NOT find any `defmacro` in `query.cljc` other than `some-of` (`query.cljc:1011-1016`) which is wrapped in `#?(:clj ...)` — **this macro is JVM-only and might be a problem under self-hosted CLJS**. Quick scan of its usage (`(some-of ...)` is called inside `expand-rule` at `query.cljc:1033-1035`) suggests if the macro doesn't expand in CLJS the recursive-rule code breaks. **Needs a REPL probe under the V0 pod to confirm** — try a recursive rule and see if it explodes with "Unable to resolve symbol: some-of".

---

## 3. tx-listener semantics

**Verdict: works as the plan expects, with one important async/sync caveat.**

### Fn name + signature

`d/listen` per public API spec (`src/datahike/api/specification.cljc:643-657`), routed to `datahike.core/listen!` (`src/datahike/core.cljc:206-217`):

```clojure
(d/listen conn (fn [tx-report] ...))       ;; auto-generated key
(d/listen conn :my-key (fn [tx-report] ...)) ;; named key (idempotent re-register)
(d/unlisten conn :my-key)

```

Returns the registration key.

### Sync vs async

Listeners fire on the **writer go-block thread**, synchronously in a `doseq`, **after** the tx is committed but **before** the caller's promise/channel is delivered. See `src/datahike/writer.cljc:226-250`:

```clojure
(defn transact!
  [connection arg-map]
  (let [p (throwable-promise) ...]
    (go
      (let [tx-report (<! (dispatch! writer {:op 'transact! :args [arg-map]}))]
        (when (map? tx-report)
          ;; ... index-building dispatch ...
          (doseq [[_ callback] (some-> (:listeners (meta connection)) (deref))]
            (callback tx-report)))            ;; <-- synchronous, on writer go-block
        (#?(:clj deliver :cljs put!) p tx-report)))
    p))

```

Same pattern at `writer.cljc:262-277` for `merge-db!`. **Listener callbacks run sequentially, in registration order (whatever `(deref atom)` gives — undefined ordering, treat as set).** A slow callback delays the caller's promise resolution.

### `:db-before` / `:db-after`

Both present. See `src/datahike/writing.cljc:351-367` (`complete-db-update`) — the tx-report assembled by the writer carries `:db-before`, then `(assoc :db-after db)`. The `tx-report` handed to listeners is the same map.

`:tx-data` is the standard `[e a v tx added?]` datom vector (search engine in `src/datahike/db/transaction.cljc` and `writing.cljc` confirms this is the Datomic-shape).

### Can a listener call `transact!` on the same conn?

- **`d/transact!` (async, returns promise/channel) — YES, safe.** The spec doc explicitly says so (`api/specification.cljc:652`: "Inside the callback, use only async operations (transact!, merge-db!)").
- **`d/transact` (sync, blocks) — NO, deadlocks.** `api/specification.cljc:207`: "WARNING: Do not call from listener callbacks or transaction functions — use transact! instead to avoid deadlocks." This is because sync `transact` parks the calling thread waiting for the writer go-block, and the writer go-block IS what's currently executing your listener.

For the seon plan: the auto-run-tests-on-fn-change listener should use `d/transact!` to write `:seon.test/last-passed-at` / `:seon.test/last-failed-at` results back. Or, even better, do the test execution off-thread (kick a `(go ...)` from inside the listener) so the writer go-block isn't held by the test run itself.

---

## 4. `missing?`, `or`, `(and ...)` inside `(or ...)`

**Verdict: all three work. The plan's query is valid as written.**

### `missing?`

`src/datahike/query.cljc:424-426`:

```clojure
(defn- -missing?
  [db e a]
  (nil? (get (de/entity db e) a)))

```

Registered as `'missing?` in the predicate table at `query.cljc:548`. Available in both `:clj` and `:cljs` — pure Clojure code.

Usage: `[(missing? $ ?e :seon.test/last-passed-at)]`.

### `or` and `and-inside-or`

`src/datahike/query.cljc:1925-1969`:

```clojure
'[or *] ;; (or ...)
(let [[_ & branches] clause
      ...
      contexts (mapv #(resolve-clause context' %) branches)
      ...])

'[and *] ;; (and ...)
(let [[_ & clauses] clause]
  ...)

```

The `or` arm calls `resolve-clause` on each branch. Each branch is itself a clause, and `resolve-clause` dispatches via `condp looks-like?` — which has an arm for `'[and *]`. So a branch shaped like `(and [?e :attr ?p] [(< ?p ?f)])` resolves as an `and`-grouped sub-clause. **The plan's query as written should run.**

The only subtle thing: `or` requires **all branches to bind the same set of vars** (Datomic constraint, enforced via the `limit-context` calls in `or-join`). The plan's `or` is fine because both branches' only "output" is the truthiness for `?e`. If you ever need different vars per branch, switch to `or-join`.

Plan's warnings query, validated:

```clojure
[?e :seon.test/last-failed-at ?f]
(or [(missing? $ ?e :seon.test/last-passed-at)]
    (and [?e :seon.test/last-passed-at ?p]
         [(< ?p ?f)]))

```

Both branches "bind" nothing new (predicates), filter `?e`. Should work. If the engine ever complains about var balance, the `or-join` form is:

```clojure
(or-join [?e]
  [(missing? $ ?e :seon.test/last-passed-at)]
  (and [?e :seon.test/last-passed-at ?p]
       [(< ?p ?f)]))

```

---

## 5. Query performance signals

### Transitive `:seon.ns/requires` walk at 10k fns + 1k ns

Order-of-magnitude estimate from reading the engine:

- Each rule expansion creates one new context frame (`solve-rule`'s `recur`) and runs the underlying clauses through `resolve-clauses` → which goes through `lookup-pattern` → hash-joins against existing rels.
- For a 1k-ns dependency graph with ~5 edges/ns avg = 5k `:seon.ns/requires` datoms. A transitive walk from one root visits each edge at most once (deduplicated by the `-differ?` guards). So **~5k pattern lookups per call, each O(log n) on the AVET index ≈ ~5–50 ms per query** in-memory.
- The plan cache (`query.cljc:2132`, keyed on `[where-clauses bound-vars rules-keys schema-hash]`) means repeated calls with the same shape skip the planning step.
- **What kills perf:** if the listener fires the warnings query on EVERY tx, and `:seon.fn/source` changes 100×/sec, you're running this query 100×/sec. Either (a) debounce on the listener side, or (b) `dedupe`/`async/sliding-buffer` the tx-stream.

### Indexing concerns

Datahike does **not** index every attribute by default. From `src/datahike/db.cljc:282-284`:

```clojure
(when-not (dbu/indexing? db attr)
  (log/raise "Attribute" attr "should be marked as :db/index true" {}))

```

This is in `-index-range`. AVET is only populated for attributes with `:db/index true` OR `:db/unique` set (search `:db/index` in `db.cljc:867-869`, `922-923`). Practical guidance:

- `:seon.fn/sym` — should be `{:seon.db/identity true}` (identity implies unique implies AVET). Confirm via the seon bridge `seon-db-props->db-props`.
- `:seon.ns/name` — same, should be identity.
- `:seon.test/last-failed-at`, `:seon.test/last-passed-at` — if you ever do range/equality lookups by these (e.g., "tests that failed in the last hour"), mark `{:db/index true}`. Otherwise queries that filter by them will fall back to full-EAVT scan.
- `:seon.ns/requires` — ref attrs walked via patterns `[?n :seon.ns/requires ?dep]` go through AEVT (always populated). No extra index needed.

**Bottom line: identity attrs and any attr you range-query need `:db/index` or `:db.unique/*`. Refs walked by pattern do not.**

---

## 6. Schema flexibility for agent-defined tests

**Verdict: YES — `:write` mode lets agents transact new attrs at runtime. This is the design intent.**

`src/datahike/db/transaction.cljc:88-118` (`update-schema`):

- Triggered on every tx that contains a datom with attribute `:db/ident`.
- Adds a new attribute by `(assoc-in db [:schema v-ident] ...)` and updates `ident-ref-map`/`ref-ident-map`.
- Raises if you try to re-define an existing ident: `"Schema with attribute … already exists"`.

So the agent flow works exactly as the plan needs:

```clojure
;; At any point after create-database, in any tx:
(d/transact conn [{:db/ident       :seon.test/last-passed-at
                   :db/valueType   :db.type/instant
                   :db/cardinality :db.cardinality/one}])

;; Subsequent transacts can use the attr immediately:
(d/transact conn [[:db/add [:seon.fn/sym 'seon.foo/bar]
                   :seon.test/last-passed-at #inst "2026-05-25"]])

```

`schema-flexibility :write` (`db/transaction.cljc:42-49`) means "the schema is checked on every tx, and unknown attrs raise unless they're being declared in the same tx". The validation is in `validate-val`, which is called per-datom; `update-schema` runs first when `:db/ident` datoms appear, so schema-decl + first-use can even share a tx.

**Caveat:** `schema-flexibility :read` mode (the opposite) lets you transact anything without declaration but skips type validation. For an agent runtime where you want type safety on persisted state but also dynamic schema growth, **`:write` is the right mode** — and it does what the plan needs. The CLAUDE.md schema-load-ordering rule (register before reference in the same file) is consistent with this — the bridge needs the schema present when the entity attr is registered.

---

## Summary (for the parent agent)

**Recursion: yes — fully supported.** Datahike has Datomic-style recursive (and mutually recursive, with SCC handling) rules in `src/datahike/query.cljc:1000-1142`, plus pull recursion via `datalog.parser.pull` consumed by `src/datahike/pull_api.cljc:47-118`. There is no separate `transitive` operator; recursive rules ARE the transitive-closure mechanism.

**For transitive `:seon.ns/requires`, use a recursive rule.** Canonical shape:

```clojure
'[[(depends-on ?n ?dep) [?n :seon.ns/requires ?dep]]
  [(depends-on ?n ?dep) [?n :seon.ns/requires ?mid]
                        (depends-on ?mid ?dep)]]

```

Use pull recursion (`{:seon.ns/requires ...}`) when you want a nested tree result; use the rule when you want a flat set of dependents (the warnings/affected-tests case). Rule direction matters: write `depended-on-by` if you query "which dependents are affected by this change" hot-path. Mark `:seon.fn/sym` and `:seon.ns/name` as identity attrs (auto-indexed). One **open uncertainty**: the `some-of` macro in `query.cljc` is JVM-only (`#?(:clj defmacro)`) and is used by `expand-rule` — needs a REPL probe in the V0 self-hosted CLJS pod to confirm recursive rules don't blow up under bootstrapped compilation.
