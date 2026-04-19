---
type: reference
status: draft
tags: [prd, reference, database]
---
# Implementation Notes: Datahike Migration

This file captures lessons from validation work done **before** implementation. Scratch code referenced here lives at `reference-code/datahike-lmdb/scratch/multidb/` and `reference-code/datahike-lmdb/bench/datahike_lmdb/mp_*.clj`. Keep updating during implementation.

---

## Overview of Validation Work Done

Four focused tests, each a small Clojure script that exercised one concern:

1. **`multidb.clj`** — file backend + git time-travel. Proved two datahike DBs sharing a git-tracked directory correctly reconstitute past states on `git checkout`.
2. **`schema_bridge.clj`** — Malli → datahike schema translation. Proved a minimal bridge covers all our types (including `[:vector X]` → `:db.cardinality/many`), and confirmed datahike rejects wrong types at transact time (defence-in-depth for the Malli gate).
3. **`cross_db_refs.clj`** — tuple-based cross-DB refs (`:db.type/tuple [:db.type/keyword :db.type/uuid]`). Works, but query destructuring is awkward. Documented as escape hatch for polymorphic refs, not the default.
4. **`metadata.clj`** — entity namespace auto-stamp, tx-meta committer, `d/filter`-based security views. Proved pulled output is self-describing without schema, and `d/filter` gives true row-level-security semantics.

Separate multi-JVM architecture test (not on v1 roadmap but validated for future reference):

5. **`bench/datahike_lmdb/mp_http_*.clj`** — main JVM with `:writer :self` + `datahike.http.server` + two agent JVMs using `:writer {:backend :datahike-server}`. 30-second concurrent run: 256 writes, 0 errors, reader freshness min 65ms / mean 81ms / max 503ms via `@conn` re-reading storage because `DatahikeServerWriter/-streaming?` returns false (connector.cljc:74-79).

---

## Key Learnings

### Learning 1: Reader freshness is controlled by the writer's `streaming?` flag

**What we discovered:** Datahike's `deref-conn` short-circuits to the in-memory `@wrapped-atom` when the local writer reports `streaming? true`. Two JVMs with `:writer :self` on the same storage produce stale reads because each reader's local writer says "I'm streaming, no need to reread."

**Why it matters:** If we ever let multiple JVMs share a store, only the **writer-owning** JVM can use `:writer :self`. Others must use a non-streaming writer (`:datahike-server` or a custom `:flow` backend). Otherwise reads freeze silently.

**Example:**

```clojure
;; WRONG for multi-JVM setup:
;;   Both JVMs see only their own local atom, miss each other's writes
{:store  {:backend :file :path "shared/data"}
 :writer {:backend :self}}

;; RIGHT when non-writer JVM needs reads:
;;   Forces deref-conn to reread konserve root on every @conn
{:store  {:backend :file :path "shared/data"}
 :writer {:backend :datahike-server :url "..."}}
```

Source: `reference-code/datahike/src/datahike/connector.cljc:69-79` and `src/datahike/http/writer.clj:30`.

### Learning 2: Two `:self` writers silently corrupt data

**What we discovered:** Two independent JVMs with `:writer :self` on the same LMDB dir: no lock error, no crash, but each writer reads a stale branch pointer, transacts against it, and writes back — clobbering the other's commits. Test showed 339 entities stored when ~536 were committed.

**Why it matters:** Single-writer enforcement is **non-negotiable**. The phase-1 design uses Integrant singletons + an in-JVM conn registry + a startup check that refuses to open a second `:writer :self` on a store path already in the registry.

**Where the damage happens:** LMDB serializes raw write txns correctly at the byte level, but datahike's *logical* tx layer builds a new index tree from its local `max-tx` view, then atomically swaps the root. Two JVMs with stale views both swap; the loser's commit is orphaned.

### Learning 3: File backend + git = literal time-travel

**What we discovered:** Each datahike file-backend fragment is an immutable file with a content-addressed name. Git's object store is similar, so tracking `data/` in git works naturally. `git checkout <sha>` + `d/connect` reconstitutes the exact state at that commit.

**Why it matters:** Debugging AI-agent workflows becomes *checkout what the agent saw when it made that decision*. Combined with `keep-history?` for intra-commit time-travel, you get two orthogonal axes of time.

**Example** (from `scratch/multidb/src/scratch/multidb.clj`):

```clojure
;; Write, commit, write more, commit again
(d/transact conn [...alice...])      ;; snapshot A
(sh! "git" "add" "-A") (sh! "git" "commit" "-m" "snapshot A")
(d/transact conn [...bob...])        ;; snapshot B
(sh! "git" "commit" "-am" "snapshot B")

;; Checkout earlier snapshot → reconnect → earlier state visible
(sh! "git" "checkout" sha-a)
(d/release conn)
(let [conn (d/connect cfg)]
  (d/q '[:find ?n :where [?e :name ?n]] @conn))
;; => #{[Alice]}   ; Bob is gone
```

Caveat: high-churn DBs accumulate many small files, putting pressure on filesystem inode tables and git object counts. Run `gc-storage!` periodically.

### Learning 4: Pulled entities are self-describing when `:seon.db/namespace` is stamped

**What we discovered:** Auto-stamping `:seon.db/namespace <db-name>` on every entity makes `pull-deep` output readable without schema knowledge. Nested refs inherit the property — each nested entity carries its own namespace tag.

**Why it matters:** Agent-to-agent data exchange gets unambiguous typing for free. An LLM reading a pulled result can parse the structure without consulting Malli. Serialization over flow or any wire format is a straight `pr-str`.

**Example output** (from `scratch/multidb/src/scratch/metadata.clj`):

```clojure
{:seon.db/namespace :seon.weather                 ;; root type
 :seon.weather/temp 12
 :seon.weather/observer
 {:seon.db/namespace :seon.user                   ;; nested type
  :seon.user/name "Alice"}
 :seon.weather/at-city
 {:seon.db/namespace :seon.city                   ;; nested type
  :seon.city/name "NYC"}}
```

### Learning 5: `d/filter` gives normal-DB-looking security views

**What we discovered:** `datahike.core/filter db pred` returns a `FilteredDB` that composes with all datahike read operations (`d/q`, `d/pull`, `d/entity`). Hidden datoms are literally invisible — not null, not error, just absent. Filters compose (AND) via nested `d/filter` calls.

**Why it matters:** The future security layer is a *predicate* plus a *grants query*, not a new API. Agents keep calling `seon.db/q` and `seon.db/pull` unchanged; the filter is applied inside `seon.db` before the datahike call.

**Example:**

```clojure
(def public-db
  (dc/filter @conn
             (fn [db [e _a _v tx _op]]
               (not= :seon.user (:seon.db/namespace (d/entity db e))))))

(d/q '[:find ?e :where [?e :seon.db/namespace _]] public-db)
;; => set without :seon.user entities — they're just not there
```

Source: `reference-code/datahike/src/datahike/core.cljc:114-122`.

### Learning 6: Flow state replaces app-level atoms for DB bookkeeping

**What we discovered:** An early draft used a plain atom `:seon.db/registry` to map db-name → conn, plus a `core.async/mult` for tx-report fan-out. Both were parallel state mechanisms sitting next to the flow system that already carries every cross-namespace message in Seon.

**Why it matters:** "Flow as backbone" means application state lives in flow process state, not in ad-hoc atoms. One state model, one inspection/audit surface. Every DB op passes through a flow process on the way in and out, which is exactly the natural insertion point for future security filters, rate limits, and policy gates.

**How it maps:**
- Connections held as flow state inside per-namespace `conn-process` processes
- Subscriber list held as flow state inside a `tx-bus` process
- `seon.db` public API = compose request map → `topology/request!` → deref reply
- Integrant owns one thing: the flow itself

Details in Decision 9 (`decisions.md`).

### Learning 7: HTTP-writer architecture works if we ever need it

**What we discovered:** Multi-JVM DB access via `datahike.http.server` + `:writer {:backend :datahike-server}` on clients produced zero data loss, ~80ms mean read freshness, and passed all expected semantics. Readers re-read storage on every `@conn` because the remote writer reports `streaming? false`. Works over real HTTP, compatible with konserve + datahike out of the box.

**Why it matters:** Phase 1 is single-JVM, but we should not lock ourselves out of future multi-JVM deployment. The API uses db-name keywords; call sites don't know whether writes route locally or over HTTP. Migration path exists if we ever need it.

---

## Gotchas

### Gotcha 1: Datahike's `transact` vs. `transact!`

**The problem:** Datahike has both. `d/transact` is synchronous (blocks until committed). `d/transact!` is async (returns a future). This is **opposite** of Datalevin, where `transact!` is sync and there's no `transact`.

**How to avoid:** In `seon.db/transact!` (our sync public API), internally call `@(d/transact ...)` or `(d/transact ...)` — not `d/transact!`. Rename carefully; the symbol lives in domain code.

**Why this happens:** Different library authors made different choices about which is the "default" name.

### Gotcha 2: `:writer` config must actually construct a writer

**The problem:** If you pass `:writer {:backend :self}` to `d/create-database`, Datahike **stores that writer config with the database**. Later connections inherit it. If you later want to configure a different writer (e.g., `:datahike-server`), you must pass it in `d/connect` config — it overrides the stored one, but the override semantics are subtle.

**How to avoid:** Always pass the full writer config in every `d/connect`. Don't rely on what's stored in the database. The writer is a runtime concern, not a persistent one.

**Why this happens:** Konserve stores the config alongside the data for consistency-check reasons; writer happens to live inside that blob.

### Gotcha 3: Schema migration on disk vs. in memory

**The problem:** If the Malli registry adds a new attr, `ensure-schema!` on the next `transact!` installs it in that DB. Fine. But if the Malli schema *changes* an existing attr's type, datahike will reject the tx. Silently not what was intended.

**How to avoid:** `ensure-schema!` should detect type drift and throw with a clear migration message, not just try to install the new schema. Phase-1 tests must cover this case.

**Why this happens:** Datahike schema is append-only at the `:db/valueType` level. Type changes require explicit migration.

### Gotcha 4: `file-seq` ordering when cleaning test data

**The problem:** `(file-seq dir)` returns parent before children. `(doseq [f (file-seq dir)] (.delete f))` tries to delete parent dirs before their contents, silently fails.

**How to avoid:** Use `(reverse (file-seq dir))` — children first, parents last.

**Why this happens:** Java's File.delete only succeeds on empty dirs.

### Gotcha 5: datahike-lmdb requires Java 22+ (Panama FFI)

**The problem:** If you ever try the LMDB backend, Seon needs to be on Java 22+ (currently Java 21). Switching is straightforward but non-trivial — affects CI, deployment scripts, local dev setup.

**How to avoid:** Don't enable LMDB until the Java bump is planned. File backend works on Java 21+, so phase 1 and most of phase 2 are unaffected.

**Why this happens:** `konserve-lmdb` uses `org.suskalo/coffi` which requires Panama FFI, stabilized in Java 22.

### Gotcha 6: `org.replikativ/datahike` not `io.replikativ/datahike`

**The problem:** Clojars coord is `org.replikativ/datahike`. Several docs (and our first attempt) wrote `io.replikativ` — central repo returns 404.

**How to avoid:** Copy from the validated `reference-code/datahike-lmdb/deps.edn`.

### Gotcha 7: Empty temp dir is not the same as "no DB"

**The problem:** `lmdb-store-exists?` in `datahike-lmdb/core.clj` checks for `data.mdb`, not just the dir. If you create an empty dir for a path, `d/connect` raises "Database does not exist"; `d/create-database` then sees the empty dir and raises something else. Test cleanup must remove the whole dir.

**How to avoid:** `(io/delete-file dir)` recursively, don't just clear contents.

---

## Testing Notes

### REPL smoke test (dev)

```clojure
(require '[seon.db :as d])

;; Transact (auto-stamps namespace, auto-installs schema on first use)
(d/transact! :seon.weather
             [{:seon.weather/id (random-uuid)
               :seon.weather/temp 12
               :seon.weather/observer alice-uuid}])

;; Query
(d/q :seon.weather '[:find ?t :where [?e :seon.weather/temp ?t]])

;; Deep pull (follows cross-DB refs via seon.schema/ref-registry)
(d/pull-deep :seon.weather '[*] [:seon.weather/id some-id])

;; Listen (subscribes to tx-bus filtered by db-name)
(d/listen! :seon.weather ::repl-watch
           (fn [tx-report] (prn :weather-changed (:tx-data tx-report))))
```

### Integrant lifecycle tests (integration)

```clojure
(deftest lifecycle-matrix
  (testing "fresh dir → transact → halt → reinit → data intact"
    (with-tmp-data-root
      (start! {:profile :test-file})
      (d/transact! :seon.weather [{:seon.weather/id id :seon.weather/temp 12}])
      (halt!)
      (start! {:profile :test-file})
      (is (= #{[12]}
             (d/q :seon.weather '[:find ?t :where [?e :seon.weather/temp ?t]])))))

  (testing "double-writer guard fails fast"
    (with-tmp-data-root
      (start! {:profile :test-file})
      (is (thrown-with-msg? Exception #"writer already owned"
                            (second-system-at-same-path)))))

  (testing "crash recovery"
    ;; SIGTERM mid-tx, restart, verify last-committed intact
    ;; (separate process test, uses java/exec)
    ))
```

### Crash-recovery manual test

```bash
# Terminal 1
./bin/run  # main Seon
# In REPL: start a loop that writes every 100ms
# Note count before kill

# Terminal 2
pkill -TERM -f seon.runner

# Terminal 1 again
./bin/run
# In REPL: count entities — should equal last committed count, not lose data
```

---

## Future Improvements (ideas, not commitments)

### Compile-time db-name expansion

Currently `(d/transact! :seon.weather [...])` does an atom deref + keyword lookup per call. For hot paths, a macro `(defdb ^{:ns :seon.weather} tx! q pull)` could compile the lookup out.

**Why not now:** premature optimization; not a proven hot path.

### `d/listen!` filtering by attr / entity

Current tx-bus is whole-tx-report. Subscribers can filter inside their callback. For many-listener setups, server-side filtering would help.

**Why not now:** unclear there's a real need; callbacks are cheap in-JVM.

### Auto-derived `:seon.security` grant filters

Once a security model is chosen, make `seon.db` auto-resolve `*caller*` and apply grant-based `d/filter` transparently.

**Why not now:** phase 4.

### Cross-DB query helper (`q-multi`)

Takes `{:foo :seon.weather :bar :seon.user}` + query → resolves db-names to conns, wires `:in`. Small helper.

**Why not now:** only useful once cross-DB queries are frequent; explicit `:in` is fine for now.

### Backup coordinator

Pauses all writers, snapshots each store, resumes. Useful for consistent multi-DB backups.

**Why not now:** no production data yet; backup strategy depends on backend chosen in phase 3.

### Automatic schema migration detection

If Malli registry's type for an attr differs from the stored datahike schema, detect on start and either migrate (if safe) or refuse with a clear message. Phase-1 covers detection; automatic migration is follow-up work.

### `:attribute-refs? true` adoption

Switches datahike to store attribute idents as integer refs (Datomic-compat, faster lookups, required for schema-on-write). Requires migration.

**Why not now:** phase 3, after backend perf tuning.
