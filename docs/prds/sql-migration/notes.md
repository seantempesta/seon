# Query Architecture - Working Notes

Learnings, gotchas, and insights discovered during research and implementation.

---

## Key Insight: System vs Domain Split

**System code** (infrastructure):
- `seon.db.node` - Query infrastructure
- `seon.db.queries` - Reusable query builders
- `seon.web.stats` - Dashboard queries
- `seon.trading.ingestion_state` - Progress tracking

→ Use whatever is fastest (XTQL or SQL)

**Domain code** (business logic):
- `seon.trading.signals` - DSL primitives
- `seon.trading.analysis` - Agent analysis

→ Use SQL for LLM accessibility

---

## Current Query Infrastructure

`seon.db.node` already has both:
- `xtql-query` - Uses `xtp/open-xtql-query` directly
- `sql-query` - Uses `xtp/open-sql-query` directly
- `query` - Routes based on input type (seq = XTQL, string = SQL)

Both support temporal options: `:current-time`, `:snapshot-time`

---

## Frozen-Time Pattern Ideas

### Option 1: Wrapper Record

```clojure
(defrecord FrozenDb [node as-of-time]
  ;; Implement protocols to intercept queries
  ;; Inject :current-time into all query opts
  )
```

### Option 2: Closure over query function

```clojure
(defn make-query-fn [node as-of-time]
  (fn [q & [opts]]
    (node/query node q (assoc opts :current-time as-of-time))))
```

### Option 3: Dynamic binding (not recommended)

```clojure
(def ^:dynamic *session-time* nil)
```

---

## Things to Research in XTDB Source

1. **xtp/open-xtql-query vs xtp/open-sql-query**
   - Do they share execution path?
   - Where does SQL parsing happen?

2. **Temporal filtering**
   - Is `:current-time` a filter or does it change what's indexed?
   - Performance implications?

3. **Snapshot tokens**
   - Can they represent past times?
   - Or only "current state as of now"?

4. **ATTACH DATABASE**
   - How does cross-DB query routing work?
   - Consistency across attached DBs?

---

## Performance Testing Commands

```clojure
;; Quick benchmark
(defn bench [label n f]
  (let [results (doall (repeatedly n #(let [start (System/nanoTime)
                                             _ (f)
                                             end (System/nanoTime)]
                                         (/ (- end start) 1e6))))]
    (println (format "%s: min=%.2f avg=%.2f max=%.2f ms (n=%d)"
                     label
                     (apply min results)
                     (/ (reduce + results) n)
                     (apply max results)
                     n))))

;; Compare
(bench "XTQL" 10 #(node/query db '(from :option-greeks [xt/id] (limit 100))))
(bench "SQL" 10 #(node/sql-query db "SELECT _id FROM option_greeks LIMIT 100"))
```

---

## XTDB Source Structure

Key directories in `reference-code/xtdb/`:

```
src/main/clojure/xtdb/
├── api.clj              # Public API
├── node.clj             # Node implementation
├── protocols.clj        # Core protocols
├── query.clj            # Query coordinator
├── sql/                 # SQL → plan
├── xtql/                # XTQL → plan
├── operator/            # Query operators
├── temporal.clj         # Temporal logic
└── indexer/             # Storage layer
```

---

## Gotchas Discovered

*(Fill in during research)*

---

## Useful Links

- [XTDB SQL Reference](https://docs.xtdb.com/reference/main/sql/queries.html)
- [XTDB ATTACH DATABASE Blog](https://xtdb.com/blog/attach-database)
- [XTDB Temporal Queries](https://docs.xtdb.com/reference/main/sql/temporal.html)
