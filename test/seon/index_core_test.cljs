(ns seon.index-core-test
  "Guard tests for `seon.client/index-core!` — the runtime-introspection
   core indexer (Step 2 of coherent-bootstrap-indexing-2026-06-08).

   index-core! replaced the old curated `core-fn-curated` /
   `synthesize-fn-source` / `seed-core-fns!` table. It builds `:seon.fn` rows
   from REAL runtime introspection: spec + doc from var meta, and source +
   real arglists from artifact source at the var's `:file`/`:line` (paren-balance,
   the cljs.repl/source-fn mechanism). These tests pin the invariants that the
   old curated path got WRONG:

     - source is REAL `(defn …)` text from the admitted program artifact,
       never a `,,,` stub;
     - arglists come from that real source, not the mangled var meta;
     - `:seon.fn/spec` is the exact `m/form` string when the fn is specced,
       and ABSENT when it is not (honestly unspecced).

   The test process receives the same program-source artifact path and digest
   as a pod, so this remains a straight unit test — no live pod.

   Run interactively via MCP eval:
     (require 'seon.index-core-test :reload)
     (cljs.test/run-tests 'seon.index-core-test)"
  (:require
    [clojure.string :as str]
    [cljs.reader :as reader]
    [cljs.test :refer [deftest is]]
    [seon.client :as client]
    [seon.config :as config]
    [seon.db :as db]
    [seon.schema :as schema]))

;; index-core! / index-schemas are PURE, DETERMINISTIC builders that do full
;; runtime introspection (program-source lookup + paren-parse over the build
;; closure — ~50+ fns). Re-running them once per deftest was the single
;; biggest suite-time cost (~14s of identical re-indexing). Compute ONCE and
;; share the result across every pure-read test via a `delay` (both are sync —
;; index-core! returns the tx-data vector directly, NOT a promise — so no
;; await is needed; just deref).
(def ^:private configuration (config/resolve-config-singleton {}))
(def core-tx (delay (client/index-core! configuration)))
(def schemas-tx (delay (client/index-schemas)))

(schema/register! ::indexed-value [:string {:seon.db/index true}])

(defn- by-sym [tx sym]
  (first (filter #(= sym (:seon.fn/sym %)) tx)))

(defn- agent-facing-syms
  "Agent-facing function symbols indexed for namespace `ns-name`."
  [tx ns-name]
  (into #{}
        (comp (filter #(= true (:seon.fn/agent-facing? %)))
              (map :seon.fn/sym)
              (filter #(str/starts-with? % (str ns-name "/"))))
        tx))

(defn- arity-count
  "Number of arity arg-vectors recovered in a parsed arglists string, e.g.
   \"([req] [db eid])\" → 2. Counts opening `[` — robust to the exact arg
   names, which is the point: it asserts parser fidelity, not db/*'s signature."
  [arglists-str]
  (count (re-seq #"\[" (str arglists-str))))

(deftest single-arity-vector-bodies-stay-out-of-indexed-arglists
  (doseq [sym ["my.canvas/button" "my.canvas/input"
               "my.canvas/select" "my.canvas/toggle"]]
    (let [row    (by-sym @core-tx sym)
          parsed (reader/read-string (:seon.fn/arglists row))]
      (is (= 1 (count parsed))
          (str sym " has exactly one physical callable arglist: "
               (pr-str parsed)))
      (is (map? (ffirst parsed))
          (str sym " retains its real map destructuring input")))))

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
    ;; transact! has one schema for its real public call shapes: a namespaced
    ;; request map, transaction data using the current db, or an explicit
    ;; immutable database value followed by transaction data.
    (is (str/starts-with? (:seon.fn/spec t) "[:function ")
        "spec is the m/form of transact!'s multi-arity :function schema")
    (is (str/includes? (:seon.fn/spec t) ":seon.db/transact-request")
        "spec still carries the map-in request slot")
    (is (str/includes? (:seon.fn/spec t) ":seon.db/tx-data")
        "spec carries the current-db transaction-data slot")
    (is (str/includes? (:seon.fn/spec t) ":seon.db/db")
        "spec carries the explicit-database positional slot")
    (is (str/includes? (:seon.fn/arglists t) "call-args")
        "arglists parsed from the real variadic source")))

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
    ;; and it still gets real source (artifact lookup, not stub).
    (is (and (str/starts-with? (:seon.fn/source register) "(defn")
             (str/includes? (:seon.fn/source register) "register!"))
        "register! gets REAL source (artifact lookup, not stub)")
    (is (true? (:seon.fn/agent-facing? query))
        "query carries the positive colocated capability fact")
    (is (true? (:seon.fn/agent-facing? register))
        "register! carries the positive colocated capability fact")
    (is (not (contains? (by-sym tx "seon.db/listen!")
                        :seon.fn/agent-facing?))
        "a public implementation function remains indexed but unmarked")))

(deftest protected-tool-inventory-is-explicit
  (let [tx @core-tx]
    (is (= #{"seon.db/current-agent-id"
             "seon.db/db"
             "seon.db/cas-assert"
             "seon.db/transact!"
             "seon.db/query"
             "seon.db/query-with-evidence"
             "seon.db/installed-schema"
             "seon.db/pull"
             "seon.db/pull-many"
             "seon.db/entity"
             "seon.db/execute-many"
             "seon.db/index-page"
             "seon.db/history"
             "seon.db/as-of"
             "seon.db/since"}
           (agent-facing-syms tx "seon.db"))
        "seon.db exposes only the deliberate database toolkit")
    (is (= #{"seon.schema/identity-attr?"
             "seon.schema/enum-members"
             "seon.schema/register!"
             "seon.schema/registered-schemas"
             "seon.schema/registered?"
             "seon.schema/schema-definition"
             "seon.schema/schemas-in-namespace"}
           (agent-facing-syms tx "seon.schema"))
        "seon.schema excludes projection and eval-validation internals")
    (is (= #{"my.kb/remember" "my.kb/recall"}
           (agent-facing-syms tx "my.kb"))
        "my.kb advertises only its general knowledge operations")))

(deftest my-kb-capabilities-and-recipes-stay-indexed-and-inspectable
  (let [tx          @core-tx
        capabilities ["my.kb/remember" "my.kb/recall"]
        sample-syms ["my.kb/remember-sources!"
                     "my.kb/retitle-source!"
                     "my.kb/clear-rating!"
                     "my.kb/replace-topics!"
                     "my.kb/forget-source!"
                     "my.kb/titles"
                     "my.kb/title+rating"
                     "my.kb/titles-by-author"
                     "my.kb/source-stats"
                     "my.kb/source-detail"
                     "my.kb/source-entity"]
        ns-source   (:seon.ns/source
                      (first (filter #(= 'my.kb (:seon.ns/name %)) tx)))]
    (doseq [sym capabilities]
      (let [row    (by-sym tx sym)
            source (:seon.fn/source row)]
        (is (some? row) (str sym " remains in the program graph"))
        (is (true? (:seon.fn/agent-facing? row))
            (str sym " carries its colocated capability metadata"))
        (is (and (string? source) (str/includes? ns-source source))
            (str sym " remains real source in the full my.kb namespace"))))
    (doseq [sym sample-syms]
      (let [row    (by-sym tx sym)
            source (:seon.fn/source row)]
        (is (some? row) (str sym " remains in the program graph"))
        (is (not (contains? row :seon.fn/agent-facing?))
            (str sym " is not advertised as a standing tool"))
        (is (and (string? source) (str/includes? ns-source source))
            (str sym " remains in full my.kb source for deliberate inspection"))))
    (is (str/includes? ns-source ":my.kb.source/id")
        "the colocated sample schema remains in the full namespace source")))

(deftest real-arglists-not-mangled
  ;; The parser recovers arglists from the REAL source, not the
  ;; instrumentation-mangled `([arg])` var-meta. query is the pure-variadic
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

(deftest listen-arglists-preserve-current-positional-arities
  (let [tx      @core-tx
        listen  (by-sym tx "seon.db/listen!")
        al      (:seon.fn/arglists listen)]
    (is (= '([input-or-handler] [key handler] [database key handler])
           (reader/read-string al))
        "listen! retains its current one-, two-, and three-argument forms")
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
  ;; Each owning ns gets a :seon.ns row so its symbol lookup-ref resolves.
  ;; on :seon.fn/ns resolves. A seon.* FRAMEWORK BULK ns keeps the minimal
  ;; `(ns x)` stub — it is DROPPED from the :namespaces section (still
  ;; indexed via its member rows); my.* nses AND the curated full-source
  ;; whitelist carry full file text (see the dedicated stub/full tests below).
  (let [tx      @core-tx
        ns-rows (filter :seon.ns/name tx)
        names   (set (map :seon.ns/name ns-rows))]
    (is (contains? names 'seon.db) "seon.db ns row emitted")
    (is (contains? names 'seon.schema) "seon.schema ns row emitted")
    (is (contains? names 'seon.test.runner) "seon.test.runner ns row emitted")
    (is (= "(ns seon.warn)"
           (:seon.ns/source (first (filter #(= 'seon.warn (:seon.ns/name %)) ns-rows))))
        "a non-whitelisted framework-bulk ns source is the minimal (ns x) stub")))

(deftest core-ns-rows-stub-bulk-full-source-whitelist
  ;; Curated render (LEAN whitelist): the seon.* FRAMEWORK BULK keeps the
  ;; minimal `(ns x)` stub (it is DROPPED from render, never shown as a body),
  ;; while THE seon.* ns the config policy lists in :seon.config/always
  ;; (my.plan by default) AND
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
        warn    (:seon.ns/source (row-for 'seon.warn))
        eval-ns (:seon.ns/source (row-for 'seon.eval))]
    (is (= "(ns seon.warn)" warn)
        "seon.warn (framework bulk) source is the minimal (ns x) stub")
    (is (= "(ns seon.eval)" eval-ns)
        "seon.eval (framework bulk) source is the minimal (ns x) stub")
    ;; my.kb (the DB manual) is full-source via the my.* rule, and the
    ;; whitelisted tool carries its REAL full file source — neither a stub.
    (is (full? 'my.kb)
        "my.kb (the runnable DB manual, full via the my.* rule) source is its REAL full file text")
    (is (full? 'my.plan)
        "my.plan (whitelist) source is its REAL full file text")
    ;; seon.db itself is DE-whitelisted — the raw db source is no longer
    ;; dumped; it drops to the minimal (ns x) stub (still indexed via its
    ;; member rows). The worked-example layer (my.kb, full via the my.* rule)
    ;; replaces it.
    (is (= "(ns seon.db)" (:seon.ns/source (row-for 'seon.db)))
        "seon.db (de-whitelisted) source is the minimal (ns x) stub")
    ;; LEAN: search + fs are NO LONGER whitelisted — they drop to the minimal
    ;; (ns x) stub (still indexed via their member rows below).
    (is (= "(ns seon.agent.search)" (:seon.ns/source (row-for 'seon.agent.search)))
        "seon.agent.search (de-whitelisted) source is the minimal (ns x) stub")
    (is (= "(ns seon.agent.fs)" (:seon.ns/source (row-for 'seon.agent.fs)))
        "seon.agent.fs (de-whitelisted) source is the minimal (ns x) stub")
    ;; the members are still indexed (dropped nses via member rows; the
    ;; whitelist via its full source).
    (let [syms (set (map :seon.fn/sym (filter :seon.fn/sym tx)))]
      (is (contains? syms "seon.agent.search/grep")
          "search's grep is an indexed :seon.fn member")
      (is (contains? syms "my.plan/step!")
          "plan's step! is an indexed :seon.fn member"))))

(deftest pure-index-emits-valid-refs
  ;; index-core! is a PURE builder: every :seon.fn/ns it emits is a
  ;; [:seon.ns/name <symbol>] lookup-ref (a single :seon.db/ref), NEVER a bare
  ;; value — the malformed shape the Run-3 findings traced to the second boot.
  ;;
  ;; DERIVED expectations: the indexed vars now come from the compile-time
  ;; `seon.indexing/public-fn-vars` macro over EVERY public first-party fn
  ;; in the whole build closure — specced OR not (owner directive 'just
  ;; index everything'; the hand-curated inclusion list is gone). Never
  ;; assert a hardcoded count (the old `= 14` broke whenever the var set
  ;; change). Instead: the known core surface (incl. honestly-unspecced fns
  ;; like register!/read-file/grep) must be present, the set must be
  ;; substantially wider than the old curated 14, and every row must be
  ;; structurally valid + unique.
  (let [tx   @core-tx
        fns  (filter :seon.fn/sym tx)
        syms (map :seon.fn/sym fns)]
    (is (>= (count fns) 14)
        "the indexed var set is at least as big as the old curated set")
    (is (> (count fns) 50)
        "the whole-package surface is indexed, not a curated sliver")
    (is (= (count syms) (count (distinct syms)))
        "no duplicate :seon.fn/sym rows (curated + macro vars deduped)")
    (doseq [sym ["seon.db/transact!" "seon.db/query" "seon.db/pull"
                 "seon.db/entity" "seon.db/listen!" "seon.db/current-agent-id"
                 "seon.schema/register!" "seon.agent.fs/read-file" "seon.agent.fs/walk-dir"
                 "seon.agent.search/grep" "seon.test.runner/run!"]]
      (is (some #{sym} syms) (str sym " present in the indexed vars")))
    (is (every? #(let [r (:seon.fn/ns %)]
                   (and (vector? r) (= 2 (count r)) (= :seon.ns/name (first r))
                        (symbol? (second r))))
                fns)
        "every :seon.fn/ns is a valid symbol namespace lookup-ref")))

(deftest index-schemas-covers-the-whole-registry
  ;; Fix b: ALL registered schemas — attr-level included — become
  ;; :seon.schema rows. Derived expectation: one row per registered key.
  (let [rows @schemas-tx
        ks   (set (map :seon.schema/key rows))]
    (is (= (count rows) (count (schema/registered-schemas)))
        "one :seon.schema row per registered schema key")
    (is (contains? ks :seon.db/id) "attr-level shape (:seon.db/id) indexed")
    (is (contains? ks :seon.eval) "entity kind (:seon.eval) indexed")
    (is (= (pr-str (schema/schema-definition :seon.db/id))
           (:seon.schema/form (first (filter #(= :seon.db/id (:seon.schema/key %)) rows))))
        ":seon.schema/form is derived from the registered Malli form")
    (is (every? (fn [{k :seon.schema/key ns-ref :seon.schema/ns}]
                  (if (namespace k)
                    (= {:seon.ns/name (symbol (namespace k))} ns-ref)
                    (nil? ns-ref)))
                rows)
        "namespaced keys carry the owning-ns nested ref; bare kinds don't")))

(deftest agent-entity-declares-its-context-configuration-attributes
  (let [attributes
        (into #{}
              (map first)
              (drop 2 (schema/schema-definition :seon.agent)))]
    (is (every? attributes
                [:seon.agent.ctx/capabilities
                 :seon.agent.ctx/escape-clipping?
                 :seon.agent.ctx/cache-breakpoint])
        "agent-level context settings are native Datahike attributes")
    (is (not-any? attributes
                  [:seon.agent.ctx/render-namespaces
                   :seon.agent.ctx.namespaces/full-source
                   :seon.agent.ctx.namespaces/with-tests
                   :seon.agent.ctx.namespaces/current-full?
                   :seon.agent.ctx.namespaces/current-tests?])
        "namespace render settings live only on the namespaces block")))

(deftest namespaces-block-declares-its-stored-configuration-attributes
  (let [block-form (schema/schema-definition
                    :seon.agent.ctx.namespaces/block)
        properties (second block-form)
        attributes (into #{} (map first) (drop 2 block-form))
        installed-idents
        (into #{}
              (map :db/ident)
              (db/malli->datahike-schema attributes))]
    (is (true? (:seon.db/entity properties))
        "the specialized component block participates in cold schema publication")
    (is (= #{:seon.agent.ctx/name
             :seon.agent.ctx.namespaces/full-source
             :seon.agent.ctx.namespaces/with-tests
             :seon.agent.ctx.namespaces/current-full?
             :seon.agent.ctx.namespaces/current-tests?}
           attributes)
        "the one namespaces block owns every persisted render dial")
    (is (= attributes installed-idents)
        "every attribute queried from a namespaces block has a Datahike declaration")
    (doseq [attribute [:seon.agent.ctx.namespaces/full-source
                       :seon.agent.ctx.namespaces/with-tests]]
      (let [declaration (first (db/malli->datahike-schema [attribute]))]
        (is (= :db.cardinality/many (:db/cardinality declaration)))
        (is (= :db.type/symbol (:db/valueType declaration)))
        (is (not (contains? declaration :db/unique))
            "namespace selections are ordinary shared symbols, not identities")))))

(deftest namespace-entities-declare-structural-require-edges
  (let [namespace-attributes
        (into #{}
              (map first)
              (drop 2 (schema/schema-definition :seon.ns)))
        edge-form (schema/schema-definition :seon.analyzer-info/require-edge)]
    (is (contains? namespace-attributes :seon.ns/require-edges))
    (is (true? (:seon.db/entity (second edge-form)))
        "stored require-edge component attributes install before optional pulls")))

(deftest index-schemas-persists-generator-policy-as-data
  (let [rows      @schemas-tx
        by-key    (into {} (map (juxt :seon.schema/key identity)) rows)
        installed (first
                   (filter #(= :seon.db.id/generator (:db/ident %))
                           (db/malli->datahike-schema
                            client/agent-bootstrap-attrs)))]
    (is (= :seon.db.id.generator/human-readable
           (:seon.db.id/generator (get by-key :seon.agent/id)))
        "the agent identity row carries its registered generator policy")
    (is (= :seon.db.id.generator/compact
           (:seon.db.id/generator (get by-key :my.plan/id)))
        "a compact identity row carries its registered generator policy")
    (is (not (contains? (get by-key :seon.agent/parent)
                        :seon.db.id/generator))
        "an ordinary registered attr gains no generator fact")
    (is (= {:db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one}
           (select-keys installed [:db/valueType :db/cardinality]))
        "the persisted policy attr is installed by the pod bootstrap schema")))

(deftest bootstrap-schema-installs-first-prompt-render-attributes
  (let [installed-idents (into #{}
                               (map :db/ident)
                               (db/malli->datahike-schema
                                client/agent-bootstrap-attrs))]
    (is (contains? installed-idents :seon.render/full?)
        "the first transcript prompt can pull the render opt-out before lazy namespace loading")))

(deftest malli-bridge-preserves-explicit-avet-indexing
  (is (= {:db/ident ::indexed-value
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one
          :db/index true}
         (first (db/malli->datahike-schema [::indexed-value])))))
