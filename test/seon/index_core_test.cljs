(ns seon.index-core-test
  "Guard tests for `seon.client/index-core!` — the runtime-introspection
   core indexer (Step 2 of coherent-bootstrap-indexing-2026-06-08).

   index-core! replaced the old curated `core-fn-curated` /
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
     (require 'seon.index-core-test :reload)
     (cljs.test/run-tests 'seon.index-core-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :as t :refer [deftest is async]]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]
    ;; The exemplar TEST SIBLING — required so its deftest vars are
    ;; available as #'-literals for the test-sibling full-source guard.
    [seon.agent.todo-test]))

;; index-core! / index-schemas are PURE, DETERMINISTIC builders that do full
;; runtime introspection (file-read + paren-parse over the whole build
;; closure — ~50+ fns). Re-running them once per deftest was the single
;; biggest suite-time cost (~14s of identical re-indexing). Compute ONCE and
;; share the result across every pure-read test via a `delay` (both are sync —
;; index-core! returns the tx-data vector directly, NOT a promise — so no
;; await is needed; just deref). The three async/conn tests below build their
;; OWN conn-dependent index via core-index-tx and only borrow @core-tx /
;; @schemas-tx for the pure count comparisons.
(def core-tx (delay (client/index-core!)))
(def schemas-tx (delay (client/index-schemas)))
;; index-tests has no other delay; the three builders share ONE realized
;; snapshot per ns run via these delays (see freeze-builders! below).
(def tests-tx (delay (client/index-tests)))

;;; -------------------------------------------------------------------------
;;; HERMETICITY (issue #69 — same env-coupling class as the search_test fix
;;; b5c3a3a4).
;;;
;;; index-core! / index-schemas / index-tests are "pure" only relative to the
;;; LIVE program graph: they read var-meta + on-disk source lines, the global
;;; schema registry (schema/registered-schemas), and @!indexed-test-vars. The
;;; async tests below build `first-tx` from these builders at one instant (T1)
;;; and then RE-RUN the same builders inside core-index-tx / prune-core-ghosts!
;;; at a later instant (T2) and assert the two agree (idempotent no-op, exactly
;;; the seeded ghosts, only the drifted ns re-emits). When the overnight loop
;;; runs this suite concurrently with the gym scorecard (or another run), a
;;; mutation of those globals between T1 and T2 reclassifies a stored core-seed
;;; row as a ghost / re-emits a fn row, flaking the assertions — even though
;;; each conn here is a fresh per-test :memory store.
;;;
;;; Fix (test-only — the index source is Core's): pin the three builder vars to
;;; ONE realized snapshot (the file-level delays) for the test's duration, so
;;; T1 and T2 read identical sets regardless of any concurrent process. with-
;;; redefs can't be used — its `finally` restores before the Promise chain's
;;; awaits resolve — so we set! up front and restore in the terminal then/catch
;;; (the `done*` thunk every async test below funnels through).
;;; -------------------------------------------------------------------------
(defn- freeze-builders!
  "Snapshot index-core! / index-schemas / index-tests to the shared ns-level
   delays and pin each var to it. Returns a 0-arg `restore!` thunk. Realizes
   the delays via the ORIGINAL vars (the let bindings run before the set!s) so
   there is no re-entrant deref."
  []
  (let [oc      client/index-core!
        os      client/index-schemas
        ot      client/index-tests
        core    @core-tx
        schemas @schemas-tx
        tests   @tests-tx]
    ;; Explicit-arity fns — NOT (constantly …). CLJS statically dispatches the
    ;; same-ns call sites in core-index-tx / prune-core-ghosts! to
    ;; `.cljs$core$IFn$_invoke$arity$0()`; a variadic `(constantly …)` lacks
    ;; that method and the optimized call throws "arity$0 is not a function".
    ;; index-tests is multi-arity (0 + [vars]) — pin both so a 1-arity call
    ;; inside the freeze window can't break either.
    (set! client/index-core!   (fn [] core))
    (set! client/index-schemas (fn [] schemas))
    (set! client/index-tests   (fn ([] tests) ([_] tests)))
    (fn restore! []
      (set! client/index-core!   oc)
      (set! client/index-schemas os)
      (set! client/index-tests   ot))))

;;; -------------------------------------------------------------------------
;;; ORDER-INDEPENDENCE (issue #75). The three async tests below pin the builder
;;; vars to frozen `(fn [] snapshot)` thunks for their cross-await window and
;;; restore in their terminal then/catch. If that restore is skipped or runs
;;; late (a rejected-Promise path, a concurrent suite run), the frozen snapshot
;;; LEAKS into a later test — `client/index-tests` left pinned to the empty
;;; node-test `@tests-tx` makes a direct `(index-tests [#'var])` return [] (so
;;; index-tests-builds-rows-from-deftest-vars reads no source), and a leaked
;;; builder skews prune-core-ghosts's absentee set. Both then fail ONLY when run
;;; after a freezing test.
;;;
;;; Fix (test-only — the index source is Core's): a `:each` map fixture
;;; (map-form so it is async-safe — the wrapping `(fn [f] …)` form aborts on
;;; async tests) that resets the three vars to their REAL implementations both
;;; before AND after every test. The before-reset is the load-bearing half: it
;;; guarantees each test starts from the real builders regardless of any prior
;;; test's leaked freeze, AND it means each async test's own freeze captures the
;;; real fns (so its restore can't cascade a frozen state forward).
(def ^:private og-index-core!   client/index-core!)
(def ^:private og-index-schemas client/index-schemas)
(def ^:private og-index-tests   client/index-tests)

(defn- thaw-builders!
  "Reset the three builder vars to their real (unfrozen) implementations."
  []
  (set! client/index-core!   og-index-core!)
  (set! client/index-schemas og-index-schemas)
  (set! client/index-tests   og-index-tests))

(t/use-fixtures :each
  {:before (fn [] (thaw-builders!))
   :after  (fn [] (thaw-builders!))})

(defn- by-sym [tx sym]
  (first (filter #(= sym (:seon.fn/sym %)) tx)))

(defn- arity-count
  "Number of arity arg-vectors recovered in a parsed arglists string, e.g.
   \"([req] [db eid])\" → 2. Counts opening `[` — robust to the exact arg
   names, which is the point: it asserts parser fidelity, not db/*'s signature."
  [arglists-str]
  (count (re-seq #"\[" (str arglists-str))))

(deftest transact!-indexes-with-real-source-spec-arglists
  ;; The single most important criterion: transact! carries the real spec
  ;; form AND real source/arglists — not the old curated `([arg])`-mangled,
  ;; `,,,`-stubbed, `specced? false` lie.
  (let [tx @core-tx
        t  (by-sym tx "seon.db/transact!")]
    (is (some? t) "transact! is present in the indexed tx-data")
    (is (and (str/starts-with? (:seon.fn/source t) "(defn")
             (str/includes? (:seon.fn/source t) "transact!"))
        "source is the REAL (defn …) text read from the file (names transact!)")
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
  (let [tx       @core-tx
        query    (by-sym tx "seon.db/query")
        register (by-sym tx "seon.schema/register!")
        cai      (by-sym tx "seon.db/current-agent-id")]
    ;; query + current-agent-id ARE specced → :seon.fn/spec present.
    (is (some? (:seon.fn/spec query))
        "query is specced → :seon.fn/spec present")
    (is (some? (:seon.fn/spec cai))
        "current-agent-id is specced (-> [:maybe :string]) → :seon.fn/spec present")
    ;; register! is now specced too — the spec-everything sweep gave every public
    ;; fn a :malli/schema (register!'s `v` slot is :any: a Malli FORM, not a fixed
    ;; data shape). So :seon.fn/spec is PRESENT (it was the unspecced exemplar
    ;; before the sweep; "spec what we can, :any where opaque").
    (is (some? (:seon.fn/spec register))
        "register! is specced (spec-everything) → :seon.fn/spec present")
    ;; and it still gets real source (file-read, not stub).
    (is (str/starts-with? (:seon.fn/source register) "(defn register!")
        "register! gets REAL source (file-read, not stub)")))

(deftest real-arglists-not-mangled
  ;; The parser recovers arglists from the REAL source, not the
  ;; instrumentation-mangled `([arg])` var-meta. query is the T15 pure-variadic
  ;; `[& args]` form (see db.cljs docstring) — its arglist is `([& args])`,
  ;; recovered verbatim from source.
  (let [tx    @core-tx
        query (by-sym tx "seon.db/query")
        qa    (:seon.fn/arglists query)]
    ;; query is variadic (`[& args]`): the variadic marker survives, and it is
    ;; NOT the instrumentation-mangled `([arg])` var-meta nor an empty collapse.
    ;; Structural anchors, not the exact arg names — parser fidelity is the
    ;; point, not db/query's signature.
    (is (str/includes? qa "&")
        "query's variadic `&` arg is recovered from the real source")
    (is (not= "([arg])" qa)
        "query arglists are the real source form, not the mangled var-meta")
    (is (not= "()" qa)
        "query's arglist did not collapse to empty"))
  ;; MULTI-ARITY recovery (2026-06-09 fix): pull/entity define each arity as
  ;; `([args] body)` at paren-depth 2 — the old depth-1-only scan returned
  ;; "()" for them, which rendered the uncallable `(seon.db/pull ())` in
  ;; capabilities (context-audit §2). EVERY arity must now be recovered, so the
  ;; recovered arity COUNT is >1 (the real mechanism), and none collapses to
  ;; "()" or the mangled single `([arg])`.
  (let [tx     @core-tx
        pull   (by-sym tx "seon.db/pull")
        entity (by-sym tx "seon.db/entity")
        pa     (:seon.fn/arglists pull)
        ea     (:seon.fn/arglists entity)]
    (is (> (arity-count pa) 1)
        "pull's multiple real arities are recovered from multi-arity source")
    (is (> (arity-count ea) 1)
        "entity's multiple real arities are recovered from multi-arity source")
    (is (and (not= "()" pa) (not= "([arg])" pa))
        "multi-arity pull no longer collapses to empty/mangled arglists")
    (is (and (not= "()" ea) (not= "([arg])" ea))
        "multi-arity entity no longer collapses to empty/mangled arglists")))

(deftest arglists-expand-local-auto-kws
  ;; listen!'s real arg vector is `[{::keys [handler key conn] …}]` —
  ;; rendered verbatim, `::keys` would mis-resolve against the READER's
  ;; ns. The stored arglist must carry the explicit `:seon.db/keys`.
  (let [tx      @core-tx
        listen  (by-sym tx "seon.db/listen!")
        al      (:seon.fn/arglists listen)]
    (is (str/includes? al ":seon.db/keys")
        "listen!'s ::keys destructure is expanded to :seon.db/keys")
    (is (not (str/includes? al "::"))
        "no raw auto-resolved :: survives in listen!'s stored arglists")))

(deftest no-stub-source-anywhere
  ;; Permissive + honest: every indexed fn has REAL source (or is OMITTED),
  ;; never a `,,,` stub.
  (let [tx @core-tx]
    (is (every? #(str/starts-with? (:seon.fn/source %) "(defn")
                (filter :seon.fn/sym tx))
        "every indexed :seon.fn row has real (defn …) source")
    (is (not-any? #(and (:seon.fn/source %)
                        (str/includes? (:seon.fn/source %) ",,,"))
                  tx)
        "no row carries a `,,,` stub")))

(deftest emits-ns-rows-for-owning-nses
  ;; Each owning ns gets a :seon.ns row so the [:seon.ns/name kw] lookup-ref
  ;; on :seon.fn/ns resolves. A seon.* FRAMEWORK BULK ns keeps the minimal
  ;; `(ns x)` stub — it is DROPPED from the :namespaces section (still
  ;; indexed via its member rows); my.* nses AND the curated full-source
  ;; whitelist carry full file text (see the dedicated stub/full tests below).
  (let [tx      @core-tx
        ns-rows (filter :seon.ns/name tx)
        names   (set (map :seon.ns/name ns-rows))]
    (is (contains? names :seon.db) ":seon.db ns row emitted")
    (is (contains? names :seon.schema) ":seon.schema ns row emitted")
    (is (contains? names :seon.test.runner) ":seon.test.runner ns row emitted")
    (is (= "(ns seon.warn)"
           (:seon.ns/source (first (filter #(= :seon.warn (:seon.ns/name %)) ns-rows))))
        "a non-whitelisted framework-bulk ns source is the minimal (ns x) stub")))

(deftest core-ns-rows-stub-bulk-full-source-whitelist
  ;; Curated render (LEAN whitelist): the seon.* FRAMEWORK BULK keeps the
  ;; minimal `(ns x)` stub (it is DROPPED from render, never shown as a body),
  ;; while THE seon.* ns the config policy lists in :seon.config/always
  ;; (seon.agent.todo by default) AND
  ;; every my.* ns (my.kb, the runnable DB manual, full via the my.* rule)
  ;; force-store their REAL FULL FILE TEXT (they render FULL, so the boot
  ;; indexer reads the file — probing .cljs then .cljc — the same
  ;; seon.agent.ctx.namespaces/full-source-ns? rule the renderer uses, one writer no
  ;; drift). seon.warn / seon.eval / seon.agent.search / seon.agent.fs are all
  ;; framework bulk → stub (search/fs are NO LONGER whitelisted — lean set).
  (let [tx      @core-tx
        ns-rows (filter :seon.ns/name tx)
        row-for (fn [k] (first (filter #(= k (:seon.ns/name %)) ns-rows)))
        full?   (fn [k] (let [s (:seon.ns/source (row-for k))]
                          (and (not= (str "(ns " (name k) ")") s)
                               (str/starts-with? (str/triml s) (str "(ns " (name k)))
                               (str/includes? s "defn"))))
        warn    (:seon.ns/source (row-for :seon.warn))
        eval-ns (:seon.ns/source (row-for :seon.eval))]
    (is (= "(ns seon.warn)" warn)
        "seon.warn (framework bulk) source is the minimal (ns x) stub")
    (is (= "(ns seon.eval)" eval-ns)
        "seon.eval (framework bulk) source is the minimal (ns x) stub")
    ;; my.kb (the DB manual) is full-source via the my.* rule, and the
    ;; whitelisted tool carries its REAL full file source — neither a stub.
    (is (full? :my.kb)
        "my.kb (the runnable DB manual, full via the my.* rule) source is its REAL full file text")
    (is (full? :seon.agent.todo)
        "seon.agent.todo (whitelist) source is its REAL full file text")
    ;; seon.db itself is DE-whitelisted — the raw db source is no longer
    ;; dumped; it drops to the minimal (ns x) stub (still indexed via its
    ;; member rows). The worked-example layer (my.kb, full via the my.* rule)
    ;; replaces it.
    (is (= "(ns seon.db)" (:seon.ns/source (row-for :seon.db)))
        "seon.db (de-whitelisted) source is the minimal (ns x) stub")
    ;; LEAN: search + fs are NO LONGER whitelisted — they drop to the minimal
    ;; (ns x) stub (still indexed via their member rows below).
    (is (= "(ns seon.agent.search)" (:seon.ns/source (row-for :seon.agent.search)))
        "seon.agent.search (de-whitelisted) source is the minimal (ns x) stub")
    (is (= "(ns seon.agent.fs)" (:seon.ns/source (row-for :seon.agent.fs)))
        "seon.agent.fs (de-whitelisted) source is the minimal (ns x) stub")
    ;; the members are still indexed (dropped nses via member rows; the
    ;; whitelist via its full source).
    (let [syms (set (map :seon.fn/sym (filter :seon.fn/sym tx)))]
      (is (contains? syms "seon.agent.search/grep")
          "search's grep is an indexed :seon.fn member")
      (is (contains? syms "seon.agent.todo/add!")
          "todo's add! is an indexed :seon.fn member"))))

(deftest test-sibling-ns-rows-ride-their-base-full-source-rule
  ;; Test siblings ride the SAME full-source rule as their subject ns (the
  ;; `-test` suffix is stripped to the base — seon.agent.ctx.namespaces/full-source-ns?):
  ;;   (a) a `-test` sibling of a WHITELISTED base (seon.agent.todo-test →
  ;;       seon.agent.todo, a full-source-whitelist member) carries its REAL
  ;;       FULL FILE TEXT, so the deep render-namespace view has the real test
  ;;       bodies on hand;
  ;;   (b) a `-test` sibling of a NON-whitelisted, non-my.* base
  ;;       (seon.index-core-test → seon.index-core) gets the minimal `(ns x)`
  ;;       stub from index-tests — dropped from render, indexed only.
  ;; Either way the deftest member rows live on the :seon.test rows.
  (let [;; (a) whitelisted base → FULL source.
        srows (client/index-tests
                [#'seon.agent.todo-test/add-stores-a-fully-formed-open-todo])
        srow  (first (filter #(= :seon.agent.todo-test (:seon.ns/name %)) srows))
        ssrc  (:seon.ns/source srow)
        ;; (b) non-whitelisted base → minimal stub. Any deftest in THIS ns
        ;; works (its base seon.index-core is not whitelisted); use one
        ;; defined ABOVE so the `#'` var resolves at compile time.
        irows (client/index-tests [#'no-stub-source-anywhere])
        irow  (first (filter #(= :seon.index-core-test (:seon.ns/name %)) irows))
        isrc  (:seon.ns/source irow)]
    (is (some? srow) "an owning :seon.ns row is emitted for the whitelisted test ns")
    (is (and (not= "(ns seon.agent.todo-test)" ssrc)
             (str/starts-with? (str/triml ssrc) "(ns seon.agent.todo-test")
             (str/includes? ssrc "deftest"))
        "a whitelisted-base test sibling carries its REAL full file text")
    (is (some? irow) "an owning :seon.ns row is emitted for the non-whitelisted test ns")
    (is (= "(ns seon.index-core-test)" isrc)
        "a non-whitelisted-base test sibling source is the minimal (ns x) stub")))

(deftest pure-index-emits-valid-refs
  ;; index-core! is a PURE builder: every :seon.fn/ns it emits is a
  ;; [:seon.ns/name <kw>] lookup-ref (a single :seon.db/ref), NEVER a bare
  ;; keyword — the malformed value the Run-3 findings traced to the second boot.
  ;;
  ;; DERIVED expectations: the roster is now the compile-time
  ;; `seon.indexing/public-fn-vars` macro over EVERY public first-party fn
  ;; in the whole build closure — specced OR not (owner directive 'just
  ;; index everything'; the hand-curated inclusion list is gone). Never
  ;; assert a hardcoded count (the old `= 14` broke on every roster
  ;; change). Instead: the known core surface (incl. honestly-unspecced fns
  ;; like register!/read-file/grep) must be present, the set must be
  ;; substantially wider than the old curated 14, and every row must be
  ;; structurally valid + unique.
  (let [tx   @core-tx
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
                 "seon.schema/register!" "seon.agent.fs/read-file" "seon.agent.fs/walk-dir"
                 "seon.agent.search/grep" "seon.test.runner/run!"]]
      (is (some #{sym} syms) (str sym " present in the indexed roster")))
    (is (every? #(let [r (:seon.fn/ns %)]
                   (and (vector? r) (= 2 (count r)) (= :seon.ns/name (first r))
                        (keyword? (second r))))
                fns)
        "every :seon.fn/ns is a valid [:seon.ns/name kw] lookup-ref")))

(deftest index-schemas-covers-the-whole-registry
  ;; Fix b: ALL registered schemas — attr-level included — become
  ;; :seon.schema rows. Derived expectation: one row per registered key.
  (let [rows @schemas-tx
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
    (is (= "seon.index-core-test/pure-index-emits-valid-refs"
           (:seon.test/sym row)))
    (is (= [:seon.ns/name :seon.index-core-test] (:seon.test/ns row))
        "owning ns as a lookup-ref")
    (is (str/starts-with? (:seon.test/source row) "(deftest pure-index-emits-valid-refs")
        "source is the REAL (deftest …) text read from the test file")
    (is (some #(= :seon.index-core-test (:seon.ns/name %)) rows)
        "an owning :seon.ns row is emitted alongside")))

(deftest core-index-tx-idempotent-across-boots
  ;; The "fresh agent, same conn" guard: core-index-tx drops rows already
  ;; present on the conn, so a SECOND start-agent! on the shared conn re-seeds
  ;; nothing ([]). This is what makes a second agent boot clean instead of
  ;; aborting on a re-seed against the populated store.
  ;;
  ;; Uses a guaranteed-fresh `:memory` conn (per-test random store id) with the
  ;; same agent + tx-meta datahike schema the pod boots against, bound as
  ;; start-agent! binds it so transact lookup-refs resolve.
  (async done
    (let [restore! (freeze-builders!)
          done*    (fn [] (restore!) (done))]
     (-> (client/mem-db (into (db/malli->datahike-schema client/agent-bootstrap-attrs)
                              (db/tx-meta-datahike-schema)))
        (.then
          (fn [conn]
            ;; Pass :seon.db/conn explicitly — a `binding` of the dynamic
            ;; *conn* does NOT survive across Promise `.then` boundaries in
            ;; cljs (the binding frame pops when the sync callback returns).
            (-> (client/core-index-tx conn)
                (.then
                  (fn [first-tx]
                    ;; FIRST boot of the fresh conn: the full set — DERIVED
                    ;; from the pure builders, never a hardcoded count.
                    (is (= (count (filter :seon.fn/sym @core-tx))
                           (count (filter :seon.fn/sym first-tx)))
                        "first boot emits every core fn row")
                    (is (= (count @schemas-tx)
                           (count (filter :seon.schema/key first-tx)))
                        "first boot emits a :seon.schema row per registered schema")
                    (db/transact! {:seon.db/conn conn :seon.db/tx-data first-tx})))
                (.then (fn [_] (client/core-index-tx conn)))
                (.then
                  (fn [second-tx]
                    ;; SECOND boot of the now-populated conn: clean no-op.
                    (is (= [] second-tx)
                        "second boot of an already-indexed conn is a no-op ([])")
                    (db/transact!
                      {:seon.db/conn conn
                       :seon.db/tx-data
                       [[:db/retractEntity [:seon.fn/sym "seon.schema/register!"]]]})))
                (.then (fn [_] (client/core-index-tx conn)))
                (.then
                  (fn [gap-tx]
                    ;; After dropping one fn, the re-index emits ONLY the gap,
                    ;; with a valid lookup-ref (never a bare keyword).
                    (let [gap-fns (filter :seon.fn/sym gap-tx)]
                      (is (= ["seon.schema/register!"] (map :seon.fn/sym gap-fns))
                          "partial re-index emits only the missing fn")
                      (is (= [:seon.ns/name :seon.schema] (:seon.fn/ns (first gap-fns)))
                          "the re-emitted ref is a valid [:seon.ns/name kw] tuple"))
                    (done*))))))
        (.catch (fn [e]
                  (is false (str "idempotency test threw: " (or (.-message e) e)))
                  (done*)))))))

(deftest prune-core-ghosts-removes-only-core-claimed-absentees
  ;; Boot-index GC (open-issues 2026-06-11 row 5). Pins all four hard
  ;; constraints in one flow on one conn:
  ;;
  ;;   (a) discriminator — ONLY rows whose source tx carries
  ;;       `:seon.db/origin :core-seed` are candidates; an
  ;;       agent-authored my.* row with the IDENTICAL shape is never
  ;;       pruned, and an agent's `(register! …)` tee schema row is
  ;;       protected by replay's registration-call-source? rule even
  ;;       under a (forged) core-seed origin;
  ;;   (b) loudness is the log/info! inside the pruner (shape asserted
  ;;       via the returned pruned vector, the same data the message
  ;;       names);
  ;;   (c) idempotence — a second prune on the same conn prunes nothing;
  ;;   (d) rename semantics — the old ident (absent from the boot set)
  ;;       goes, idents present in the boot set stay.
  (async done
    (let [restore! (freeze-builders!)
          done*    (fn [] (restore!) (done))]
     (-> (client/mem-db (into (db/malli->datahike-schema client/agent-bootstrap-attrs)
                              (db/tx-meta-datahike-schema)))
        (.then
          (fn [conn]
            (-> (client/core-index-tx conn)
                (.then (fn [first-tx]
                         (db/transact! conn first-tx
                                       {:seon.db/origin :core-seed})))
                ;; GHOSTS: core-seeded rows whose ns/fn/schema no
                ;; longer exists in the booting code (deleted/renamed).
                (.then (fn [_]
                         (db/transact!
                           conn
                           [{:seon.ns/name   :seon.ghost.deleted
                             :seon.ns/source "(ns seon.ghost.deleted)"}
                            {:seon.fn/sym    "seon.ghost.deleted/gone"
                             :seon.fn/ns     {:seon.ns/name :seon.ghost.deleted}
                             :seon.fn/source "(defn gone [] 1)"}
                            ;; deleted registration → shape-literal source
                            {:seon.schema/key    :seon.ghost/bubble
                             :seon.schema/source "[:string]"}
                            ;; agent register! TEE row — protected by the
                            ;; `(…)`-call discriminator even though the
                            ;; origin here claims core-seed.
                            {:seon.schema/key    :my.agentish/teed
                             :seon.schema/source "(seon.schema/register! :my.agentish/teed :string)"}]
                           {:seon.db/origin :core-seed})))
                ;; AGENT-AUTHORED row with the IDENTICAL shape as the
                ;; ghost ns — different (non-core) provenance.
                (.then (fn [_]
                         (db/transact!
                           conn
                           [{:seon.ns/name   :my.todo-app
                             :seon.ns/source "(ns my.todo-app)"}]
                           {:seon.db/origin :agent})))
                (.then (fn [_] (client/prune-core-ghosts! conn)))
                (.then
                  (fn [{pruned :seon.client/pruned}]
                    (is (= [[:fn "seon.ghost.deleted/gone"]
                            [:ns :seon.ghost.deleted]
                            [:schema :seon.ghost/bubble]]
                           pruned)
                        "exactly the three core ghosts are pruned — not the agent ns row, not the (register! …) tee row")
                    (let [db' @conn
                          ns-names (into #{} (map first)
                                         (d/q '[:find ?nm :where [?e :seon.ns/name ?nm]] db'))
                          sch-keys (into #{} (map first)
                                         (d/q '[:find ?k :where [?e :seon.schema/key ?k]] db'))]
                      (is (not (contains? ns-names :seon.ghost.deleted))
                          "ghost ns row is GONE from the store")
                      (is (contains? ns-names :my.todo-app)
                          "agent-authored my.* ns row with identical shape SURVIVES")
                      (is (contains? ns-names :seon.db)
                          "live core rows (present in the boot set) survive — rename keeps the new")
                      (is (not (contains? sch-keys :seon.ghost/bubble))
                          "deleted-registration schema row is GONE")
                      (is (contains? sch-keys :my.agentish/teed)
                          "agent (register! …) tee row SURVIVES the prune"))))
                (.then (fn [_] (client/prune-core-ghosts! conn)))
                (.then
                  (fn [{pruned :seon.client/pruned}]
                    (is (= [] pruned)
                        "second boot's prune finds ZERO ghosts (idempotent)")
                    (done*))))))
        (.catch (fn [e]
                  (is false (str "prune test threw: " (or (.-message e) e)))
                  (done*)))))))

(deftest core-index-tx-reasserts-drifted-ns-source
  ;; ns rows dedup on name AND source. A store whose :seon.ns/source for a
  ;; full-source (my.*) ns differs from the freshly-built full file text
  ;; (e.g. a regressed `(ns x)` stub, or a stale build) gets exactly that
  ;; ns row re-emitted; everything else stays a no-op. (my.kb is a compiled
  ;; my.* root — its full file text is read at boot; its fn/schema rows are
  ;; already present, so only the drifted ns row re-emits.)
  (async done
    (let [restore! (freeze-builders!)
          done*    (fn [] (restore!) (done))]
     (-> (client/mem-db (into (db/malli->datahike-schema client/agent-bootstrap-attrs)
                              (db/tx-meta-datahike-schema)))
        (.then
          (fn [conn]
            (-> (client/core-index-tx conn)
                (.then (fn [first-tx]
                         (db/transact! {:seon.db/conn conn
                                        :seon.db/tx-data first-tx})))
                ;; Regress my.kb to a bare stub — the shape an existing
                ;; durable store carries before a re-boot with a fresher build.
                (.then (fn [_]
                         (db/transact!
                           {:seon.db/conn conn
                            :seon.db/tx-data
                            [{:seon.ns/name   :my.kb
                              :seon.ns/source "(ns my.kb)"}]})))
                (.then (fn [_] (client/core-index-tx conn)))
                (.then
                  (fn [tx]
                    (let [ns-rows (filter :seon.ns/name tx)
                          kb-row  (first (filter #(= :my.kb (:seon.ns/name %)) ns-rows))]
                      ;; The DRIFTED ns re-emits with its real full file text
                      ;; (not the stub). We don't pin the exact set of rows —
                      ;; any other genuinely-drifted ns may ride along; the
                      ;; behavior under test is "the drifted one is restored".
                      (is (some? kb-row) "the drifted my.kb ns row re-emits")
                      (is (str/starts-with? (:seon.ns/source kb-row) "(ns my.kb")
                          "re-emitted with the full file text, not the stub")
                      (is (> (count (:seon.ns/source kb-row)) (count "(ns my.kb)"))
                          "the re-emitted source is the real file, longer than the stub")
                      (is (empty? (remove :seon.ns/name tx))
                          "only ns rows re-emit — no fn/schema/test rows ride along"))
                    (done*))))))
        (.catch (fn [e]
                  (is false (str "drift test threw: " (or (.-message e) e)))
                  (done*)))))))
