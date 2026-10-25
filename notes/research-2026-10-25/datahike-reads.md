# Datahike read capture (research, read-only). Fork: reference-code/datahike/src/datahike

## 1. Inventory of reusable analysis
- Parser: datalog.parser via `memoized-parse-query` query.cljc:3189 (cache entry :3178); `query-source-bindings` :3193.
- `query-attribute-dependencies` query.cljc:3038 = `dependency-plan-attributes` over `query-dependency-plan` :2980. Pure, no db, no args needed.
  Keeps ATTRIBUTES ONLY: no entity ids, no values, no bound :in args (args are only used to tell which :in positions are sources). Returns :all for rules, pull in :find (extract-find-pull-source-deps :2918 passes no db), wildcard, unknown clause, any parse failure (catch Throwable -> :all :3030).
- `pull-spec-attribute-dependencies` pull_api.cljc:107 (db arity keeps explicit selectors concrete; component attrs and [*] -> :all). `pull-dependency-plan` pull_api.cljc:~165 adds lookup-ref attrs. Attribute-level only.
- query_stats.cljc: per-clause {:clause :t :rels [{:rows :bound}]} when q runs with stats (relational engine only; planner is bypassed when stats?). Row counts, bound vars and time. NO ranges.
- `explain` query.cljc:3753: returns a STRING (index per scan, merges, est. cards). The plan ops are data inside (`create-plan-via-ir` :3556, private; plan/lower.cljc) with scan index+bounds and merge attrs, but the executor varies at runtime (fused scans, group-direct, columnar, recursive rules).
- Planner/cost: query/plan.cljc, estimate.cljc, lower.cljc, execute.cljc. Index lookups the planner does are ~25 direct sites in execute.cljc on PersistentSortedSet interop (`di/-slice` :530,584,596,914-992,1073,1116,1950,3108; `.lookupGE` :88,1085,1276,1638,1659; `.seekGE`/`.forwardCursor` :214,1464,1857,2007-2055). No single choke point under the planner.
- Result caches: query result cache is OFF (`*query-result-cache?*` false, query.cljc:73); per-db search memo `memoize-for` db/search.cljc:19 is OFF when `:cache-size` is nil (it is nil on the live root: checked) / zero. If either is on, a hit skips the lookup and a recorder sees nothing. Parsed-query cache is harmless.

## 2. The index-level idea: evaluation
(a) Choke points. The IIndex protocol (index/interface.cljc:57) has ONE implementer: PersistentSortedSet, index/persistent_set.cljc:204-225: `-slice` :206, `-rslice` :208, `-lookup` :214, `-count-slice` :217, `-all` :222, `-seq`, `-count`. Above it, db.cljc contextual-search/-batch-search/-datoms/-seek-datoms/-rseek-datoms/-index-range :239-311, which db/search.cljc (:106, 279-308) and db/utils.cljc:270 turn into `di/-slice`. Who reaches them:
  - pull: pull_api.cljc:373,388-389,449,514 -> `dbi/datoms` -> contextual-datoms -> `-slice`. YES.
  - d/entity: impl/entity.cljc:35,179,208 -> `dbi/search` -> `-slice`. YES, but lazy (reads happen on later attr access, perhaps outside the recording scope).
  - d/datoms / index-range / seek: YES (`-slice`/`-rslice`).
  - q on the RELATIONAL engine: dbi/-search and -batch-search -> `-slice`; fast ground path query.cljc:2172/2176 uses `di/-lookup` for [e a ?v] (eavt) and unique [?e a v] (avet). YES.
  - q on the PLANNER (the default, query.cljc:4512): NO. It uses PSS cursors/lookupGE directly. A reify IIndex proxy on the live db fails with "No matching method lookupGE" (measured). But `datahike.query/*disable-planner*` (dynamic var, query.cljc:4512) routes everything through the choke points.
(b) A range is a pair of Datom bounds + index-type, nil = wildcard, plus e0/tx0 and emax/txmax sentinels. Measured on the live root (reify proxy over :eavt/:aevt/:avet, n<=684):
  - `[:slice :eavt [1270 nil nil] [1270 nil nil]]` (entity), `[1270 :seon.task/id nil]` (e,a), aevt `[0 :seon.task/id nil] .. [1152921504606846975 :seon.task/id nil]` (attribute: e0..emax = wildcard), `[:lookup :avet [0 :seon.task/id "v"]]` (a,v).
  Normalise per component: from==to -> bound; sentinel/nil -> wildcard; tx ignored.
(c) Recording = one `vswap!` on a volatile found in a dynamic var; measured noise-level (pull 0.0265 ms vs 0.0233 ms with an atom swap added).
(d) Ranges per call (live root, relational engine, proxy):
  - `d/pull [:seon.task/id {:seon.task/needs [:seon.task/id]}]` on one entity: 3 slices ([e], [e :id], [e :needs]); plain `(pull ... [:seon.task/id])` via q: 3 (one extra count).
  - d/entity then `:seon.task/id`: 1; whole entity touch: 1.
  - `[:find ?t :in $ ?id :where [?t :seon.task/id ?id]]`: 1 lookup (avet a,v).
  - `[:find ?id :where [?t :seon.task/id ?id]]` (684 rows): 1 slice (aevt attribute).
  - 3-clause join scan+needs+id, 392 result rows: 1077 ranges = 1 aevt scan + 1076 per-entity eavt probes [e :needs]. So ranges grow with ROWS, not clauses. Mitigation: coalesce in the collector (a join probe set per attribute collapses to the attribute once > k entries), or accept.
  Engine speed (ms/call, live, n=20..100): point query planned 0.070 vs relational 0.021 (relational faster); 2-hop with arg 0.110 vs 0.109; 392-row join 2.23 planned vs 5.75 relational (2.6x slower). Views are mostly point/pull shaped.
(e) Matching: normalise each range to a key: [:e e], [:ea e a], [:a a] (aevt attribute), [:av a v], [:av-range a start end], or ANY (no bound component). Build 4 hash sets + an ANY flag + a small vector for v-ranges. A tx datom [e a v] touches iff (or any (e-set e) (ea-set [e a]) (a-set a) (av-set [a v]) (some range-check)). O(1) per datom, O(|ranges|) once. Retractions (added false) match the same way.
(f) Pitfalls:
  1. Planner bypass (above): with-reads must bind `*disable-planner*` true (and `*query-result-cache?*` false). Never hook execute.cljc.
  2. Caches: memoize-for (off when :cache-size nil) and result cache skip lookups; with-reads must bypass them (1 line in memoize-for: `(if *reads* (f) ...)`).
  3. Laziness: record at slice CALL time (done: slice creation is eager, ranges are fixed), so lazy iteration is fine; but a lazy seq or d/entity realised AFTER the scope exits does its slice calls unrecorded. with-reads must return realised results (q and pull do; entity does not).
  4. Multi-db queries: PSS index carries no db identity; ranges from two sources merge. Fine for one-db views; record [db-position] later if needed.
  5. as-of: immutable, can never be touched by a later tx; history/since: temporal indexes get the same datoms, same keys match (over-invalidates, safe). Filtered db: its pred runs after the slice, ranges still recorded (correct).
  6. Schema: the db record (rschema, cardinality, ident maps) is read outside the indexes. A tx datom whose attribute is a :db/* schema attribute (or entity in :system-entities) should be treated as touching everything.
  7. Predicates/functions read nothing extra from db (nothing recorded, correct). not/not-join/or/rules in the relational engine go through -search, so recorded like any clause.
  8. Aggregates that count whole index use `-count`/`-all`/`-seq` (not `-slice`): record these as ANY.
  9. A FilteredDB pred (`d/filter`) sees only returned datoms, not absences: insufficient alone (a new datom in a scanned range would be missed). Ranges are required.
  10. Not verified: behaviour of dynamic binding across Jolt fibers/threads (Datahike already uses dynamic vars such as *disable-planner*); stratum/secondary indexes (`execute-filter-pattern-via-pss`, columnar) read outside PSS only when those indexes are configured (clj-only).

## 3. Clause-level analysis vs index ranges
- Clause-level: free, cached (0.006 ms cached, 0.019 fresh; pull-spec 0.18 ms), no db. Granularity = attribute. A view of ONE task re-renders on any change to that attribute in ANY task, and pull-in-find / [*] / rules give :all (always re-render). Requires gluing two analyses (q deps with pull replaced by vars, plus pull-spec deps) per the notes.
- Index-level: exact per entity and per value (entity pull -> [:e e] keys; unique lookup -> [:av a v]), uniform for q, pull, entity (eager parts), datoms, index-range; handles rules/not/or and pulls inside :find with no per-form code. Cost: an append per lookup; set-up O(ranges); join-shaped queries record O(rows) ranges (coalesce). Requires relational engine while recording (2.6x slower on wide joins, 3x FASTER on point queries).
- Both are conservative (never miss). Can be combined: use attribute-level as the cheap pre-filter (is any tx attribute in the view's attribute set?), then ranges for exactness.

## 4. Recommendation
Index-level capture with a dynamic collector, relational engine while recording. Minimal fork change (~25 lines, jolt/<topic> branch):
 1. index/persistent_set.cljc: `(def ^:dynamic *reads* nil)`; in `-slice`/`-rslice` (:206,:208) `(when-some [r *reads*] (vswap! r conj [index-type from to]))`; in `-lookup` (:214) record the key datom (+ cmp name) or change query.cljc:2172/2176 to record [:eavt e a] / [:avet a v]; `-all`/`-seq`/`-count` record ANY. (~10 lines)
 2. db/search.cljc:19 memoize-for: `(if (or *reads* ...) (f) ...)` (1 line).
 3. query.cljc: `with-reads` helper binding `*disable-planner*` true and `*query-result-cache?*` false (3 lines). Nothing in execute.cljc.
 4. `reads->index` and `touched?` (the normalisation + the 4 hash sets above): ~25 lines in a new small ns, datahike.reads, or in index/utils.cljc.
API (datahike.api, Datahike's words):
  (d/with-reads f)            ; f is a no-arg fn; returns [result reads], reads = vector of [index-type from-components to-components] (plain data)
  (d/reads-touched? reads tx-data) ; tx-data = (:tx-data tx-report); true iff any datom [e a v] (added or retracted) falls in a recorded range or is a schema datom
  (d/reads-index reads)       ; optional: precompute the hash index once per view, then (d/reads-touched? idx tx-data)
Fallback if the owner prefers zero fork change now: clause-level (query-attribute-dependencies + pull-spec-attribute-dependencies), accepting attribute granularity.
Cleanup: the probe defs I made in the live root's root.repl ns were unmapped; nothing transacted.
