(ns seon.index-substrate-test
  "Guard tests for `seon.client/index-substrate!` — the runtime-introspection
   substrate indexer (Step 2 of coherent-bootstrap-indexing-2026-06-08).

   index-substrate! replaced the old curated `core-fn-curated` /
   `synthesize-fn-source` / `seed-core-fns!` table. It builds `:seon.fn` rows
   from REAL runtime introspection: spec + doc from var meta, and source +
   real arglists from a file-read at the var's `:file`/`:line` (paren-balance,
   the cljs.repl/source-fn mechanism). These tests pin the invariants that the
   old curated path got WRONG:

     - source is REAL `(defn …)` text, never a `,,,` stub;
     - arglists come from that real source, not the mangled var meta;
     - `:seon.fn/spec` is the exact `m/form` string when the fn is specced,
       and ABSENT when it is not (honestly unspecced).

   File-read needs Node `fs` + cwd = repo root; the `:node-test` build runs
   under Node with cwd = repo root (bin/test-cljs cds there), so this is a
   straight unit test — no live pod.

   Run interactively via MCP eval:
     (require 'seon.index-substrate-test :reload)
     (cljs.test/run-tests 'seon.index-substrate-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :as t :refer [deftest is async]]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]))

(defn- by-sym [tx sym]
  (first (filter #(= sym (:seon.fn/sym %)) tx)))

(deftest transact!-indexes-with-real-source-spec-arglists
  ;; The single most important criterion: transact! carries the real spec
  ;; form AND real source/arglists — not the old curated `([arg])`-mangled,
  ;; `,,,`-stubbed, `specced? false` lie.
  (let [tx (client/index-substrate!)
        t  (by-sym tx "seon.db/transact!")]
    (is (some? t) "transact! is present in the indexed tx-data")
    (is (str/starts-with? (:seon.fn/source t) "(defn ^:async transact!")
        "source is the REAL (defn …) text read from the file")
    (is (not (str/includes? (:seon.fn/source t) ",,,"))
        "NO `,,,` placeholder stub in the source")
    ;; transact! became a multi-arity `:function` schema in T15 (map-in +
    ;; two datahike-positional arities). The spec is the m/form of that
    ;; :function schema, carrying the map-in `:=>` head and the positional
    ;; conn/tx-data slots.
    (is (str/starts-with? (:seon.fn/spec t) "[:function ")
        "spec is the m/form of transact!'s multi-arity :function schema")
    (is (str/includes? (:seon.fn/spec t) ":seon.db/transact-request")
        "spec still carries the map-in request slot")
    (is (str/includes? (:seon.fn/arglists t) "call-args")
        "arglists parsed from real source (T15 transact! is [& call-args])")))

(deftest specced-vs-unspecced-matches-reality
  (let [tx       (client/index-substrate!)
        query    (by-sym tx "seon.db/query")
        register (by-sym tx "seon.schema/register!")
        cai      (by-sym tx "seon.db/current-agent-id")]
    ;; query IS specced → spec present, exact form.
    (is (some? (:seon.fn/spec query))
        "query is specced → :seon.fn/spec present")
    ;; register! and current-agent-id have NO :malli/schema → no spec key.
    (is (not (contains? register :seon.fn/spec))
        "register! is honestly unspecced → :seon.fn/spec ABSENT")
    (is (not (contains? cai :seon.fn/spec))
        "current-agent-id is honestly unspecced → :seon.fn/spec ABSENT")
    ;; but both still get real source (file-read, not stub).
    (is (str/starts-with? (:seon.fn/source register) "(defn register!")
        "register! still gets REAL source despite being unspecced")))

(deftest real-arglists-not-mangled
  ;; The parser recovers arglists from the REAL source, not the
  ;; instrumentation-mangled `([arg])` var-meta. query is the T15 pure-variadic
  ;; `[& args]` form (see db.cljs docstring) — its arglist is `([& args])`,
  ;; recovered verbatim from source.
  (let [tx    (client/index-substrate!)
        query (by-sym tx "seon.db/query")]
    (is (= "([& args])" (:seon.fn/arglists query))
        "query's real [& args] arglist is recovered from source")
    (is (not= "([arg])" (:seon.fn/arglists query))
        "query arglists are the real source form, not the mangled var-meta"))
  ;; MULTI-ARITY recovery (2026-06-09 fix): pull/entity define each arity as
  ;; `([args] body)` at paren-depth 2 — the old depth-1-only scan returned
  ;; "()" for them, which rendered the uncallable `(seon.db/pull ())` in
  ;; capabilities (context-audit §2). Both arities must now be recovered.
  (let [tx     (client/index-substrate!)
        pull   (by-sym tx "seon.db/pull")
        entity (by-sym tx "seon.db/entity")]
    (is (= "([req] [db selector eid])" (:seon.fn/arglists pull))
        "pull's two real arities recovered from multi-arity source")
    (is (= "([req] [db eid])" (:seon.fn/arglists entity))
        "entity's two real arities recovered from multi-arity source")
    (is (not= "()" (:seon.fn/arglists pull))
        "multi-arity fns no longer collapse to empty arglists")))

(deftest no-stub-source-anywhere
  ;; Permissive + honest: every indexed fn has REAL source (or is OMITTED),
  ;; never a `,,,` stub.
  (let [tx (client/index-substrate!)]
    (is (every? #(str/starts-with? (:seon.fn/source %) "(defn")
                (filter :seon.fn/sym tx))
        "every indexed :seon.fn row has real (defn …) source")
    (is (not-any? #(and (:seon.fn/source %)
                        (str/includes? (:seon.fn/source %) ",,,"))
                  tx)
        "no row carries a `,,,` stub")))

(deftest emits-ns-rows-for-owning-nses
  ;; Each owning ns gets a :seon.ns row so the [:seon.ns/name kw] lookup-ref
  ;; on :seon.fn/ns resolves. ns source is the minimal `(ns x)` stub today
  ;; (replay-safe — see index-substrate! docstring).
  (let [tx      (client/index-substrate!)
        ns-rows (filter :seon.ns/name tx)
        names   (set (map :seon.ns/name ns-rows))]
    (is (contains? names :seon.db) ":seon.db ns row emitted")
    (is (contains? names :seon.schema) ":seon.schema ns row emitted")
    (is (contains? names :seon.test.runner) ":seon.test.runner ns row emitted")
    (is (= "(ns seon.db)"
           (:seon.ns/source (first (filter #(= :seon.db (:seon.ns/name %)) ns-rows))))
        "ns source is the minimal (ns x) stub (replay-safe)")))

(deftest pure-index-emits-valid-refs
  ;; index-substrate! is a PURE builder: every :seon.fn/ns it emits is a
  ;; [:seon.ns/name <kw>] lookup-ref (a single :seon.db/ref), NEVER a bare
  ;; keyword — the malformed value the Run-3 findings traced to the second boot.
  ;;
  ;; DERIVED expectations (unit #23): the roster is now the curated base +
  ;; the compile-time `seon.indexing/specced-fn-vars` macro over the WHOLE
  ;; build closure — never assert a hardcoded count (the old `= 14` broke
  ;; on every roster change). Instead: the curated core surface must be
  ;; present, the set must be substantially wider than the old curated 14,
  ;; and every row must be structurally valid + unique.
  (let [tx   (client/index-substrate!)
        fns  (filter :seon.fn/sym tx)
        syms (map :seon.fn/sym fns)]
    (is (>= (count fns) 14)
        "the widened roster is at least as big as the old curated set")
    (is (> (count fns) 50)
        "the whole-package surface is indexed, not a curated sliver")
    (is (= (count syms) (count (distinct syms)))
        "no duplicate :seon.fn/sym rows (curated + macro roster deduped)")
    (doseq [sym ["seon.db/transact!" "seon.db/query" "seon.db/pull"
                 "seon.db/entity" "seon.db/listen!" "seon.db/current-agent-id"
                 "seon.schema/register!" "seon.fs/read-file" "seon.fs/walk-dir"
                 "seon.search/grep" "seon.test.runner/run!"]]
      (is (some #{sym} syms) (str sym " present in the indexed roster")))
    (is (every? #(let [r (:seon.fn/ns %)]
                   (and (vector? r) (= 2 (count r)) (= :seon.ns/name (first r))
                        (keyword? (second r))))
                fns)
        "every :seon.fn/ns is a valid [:seon.ns/name kw] lookup-ref")))

(deftest index-schemas-covers-the-whole-registry
  ;; Fix b: ALL registered schemas — attr-level included — become
  ;; :seon.schema rows. Derived expectation: one row per registered key.
  (let [rows (client/index-schemas)
        ks   (set (map :seon.schema/key rows))]
    (is (= (count rows) (count (schema/registered-schemas)))
        "one :seon.schema row per registered schema key")
    (is (contains? ks :seon.db/id) "attr-level shape (:seon.db/id) indexed")
    (is (contains? ks :seon.eval) "entity kind (:seon.eval) indexed")
    (is (= "[:string {:min 14, :max 14}]"
           (:seon.schema/source (first (filter #(= :seon.db/id (:seon.schema/key %)) rows))))
        ":seon.schema/source is the registered Malli form (pr-str)")
    (is (every? (fn [{k :seon.schema/key ns-ref :seon.schema/ns}]
                  (if (namespace k)
                    (= {:seon.ns/name (keyword (namespace k))} ns-ref)
                    (nil? ns-ref)))
                rows)
        "namespaced keys carry the owning-ns nested ref; bare kinds don't")))

(deftest index-tests-builds-rows-from-deftest-vars
  ;; Fix b: deftest vars → :seon.test rows via the same file-read
  ;; introspection. Driven here with an explicit var (the preload-populated
  ;; default roster is empty in the :node-test build).
  (let [rows (client/index-tests [#'pure-index-emits-valid-refs])
        row  (first (filter :seon.test/sym rows))]
    (is (= "seon.index-substrate-test/pure-index-emits-valid-refs"
           (:seon.test/sym row)))
    (is (= [:seon.ns/name :seon.index-substrate-test] (:seon.test/ns row))
        "owning ns as a lookup-ref")
    (is (str/starts-with? (:seon.test/source row) "(deftest pure-index-emits-valid-refs")
        "source is the REAL (deftest …) text read from the test file")
    (is (some #(= :seon.index-substrate-test (:seon.ns/name %)) rows)
        "an owning :seon.ns row is emitted alongside")))

(deftest substrate-index-tx-idempotent-across-boots
  ;; The "fresh agent, same conn" guard: substrate-index-tx drops rows already
  ;; present on the conn, so a SECOND start-agent! on the shared conn re-seeds
  ;; nothing ([]). This is what makes a second agent boot clean instead of
  ;; aborting on a re-seed against the populated store.
  ;;
  ;; Uses a guaranteed-fresh `:memory` conn (per-test random store id) with the
  ;; same agent + tx-meta datahike schema the pod boots against, bound as
  ;; start-agent! binds it so transact lookup-refs resolve.
  (async done
    (-> (client/mem-db (into (db/malli->datahike-schema client/agent-bootstrap-attrs)
                             (db/tx-meta-datahike-schema)))
        (.then
          (fn [conn]
            ;; Pass :seon.db/conn explicitly — a `binding` of the dynamic
            ;; *conn* does NOT survive across Promise `.then` boundaries in
            ;; cljs (the binding frame pops when the sync callback returns).
            (-> (client/substrate-index-tx conn)
                (.then
                  (fn [first-tx]
                    ;; FIRST boot of the fresh conn: the full set — DERIVED
                    ;; from the pure builders, never a hardcoded count.
                    (is (= (count (filter :seon.fn/sym (client/index-substrate!)))
                           (count (filter :seon.fn/sym first-tx)))
                        "first boot emits every substrate fn row")
                    (is (= (count (client/index-schemas))
                           (count (filter :seon.schema/key first-tx)))
                        "first boot emits a :seon.schema row per registered schema")
                    (db/transact! {:seon.db/conn conn :seon.db/tx-data first-tx})))
                (.then (fn [_] (client/substrate-index-tx conn)))
                (.then
                  (fn [second-tx]
                    ;; SECOND boot of the now-populated conn: clean no-op.
                    (is (= [] second-tx)
                        "second boot of an already-indexed conn is a no-op ([])")
                    (db/transact!
                      {:seon.db/conn conn
                       :seon.db/tx-data
                       [[:db/retractEntity [:seon.fn/sym "seon.schema/register!"]]]})))
                (.then (fn [_] (client/substrate-index-tx conn)))
                (.then
                  (fn [gap-tx]
                    ;; After dropping one fn, the re-index emits ONLY the gap,
                    ;; with a valid lookup-ref (never a bare keyword).
                    (let [gap-fns (filter :seon.fn/sym gap-tx)]
                      (is (= ["seon.schema/register!"] (map :seon.fn/sym gap-fns))
                          "partial re-index emits only the missing fn")
                      (is (= [:seon.ns/name :seon.schema] (:seon.fn/ns (first gap-fns)))
                          "the re-emitted ref is a valid [:seon.ns/name kw] tuple"))
                    (done))))))
        (.catch (fn [e]
                  (is false (str "idempotency test threw: " (or (.-message e) e)))
                  (done))))))
