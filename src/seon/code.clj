(ns seon.code
  "Pure Clojure, Co-Located Schema & Markdown-as-data engine:
   - Structured multi-segment entities in Datahike: `:seon.code.ns/*`, `:seon.code.form/*`,
     `:seon.code.var/*` (with native `:db.type/data` `:arglists` & `:schema`), `:seon.schema/*`,
     `:seon.code.doc/*`, and `:seon.code.file/*`.
   - Hierarchical namespace & schema subtree scoping (`in-scope?`, `scope-view`) so distributed
     agents tree out cleanly along namespace prefixes (`seon.code`, `seon.test`, `seon.app.billing`).
   - Lossless parse & byte-exact reconstruction (`0`-byte diff round-trip for `.clj` and `.md`).
   - Tick- & byte-bounded `eval-forms` / `eval-tool-calls` via `jolt.host/run-interruptible`
     + automatic Malli `arm-var!` instrumentation + `O(|Delta| + |callers|)` Merkle reach graph."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [jolt.analyzer]
            [jolt.host]
            [jolt.scheme :as scm]
            [seon.code.files :as files]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.code.reach :as reach]
            [seon.code.parse :as parse]
            [seon.code.io :as cio]
            [seon.schema.index :as six]
            [datahike.reads :as reads]
            [seon.code.analyze :as analyze]
            [seon.code.overlay :as overlay]
            [seon.ctx.touches :as ctouches]
            [seon.error :as serr]
            [seon.code.persist :as persist]
            [seon.store.config :as store-cfg]))

(def ^:private ticks-fn (scm/eval-string "(lambda () (jolt-ticks))"))

(def ^:private alloc-fn (scm/eval-string "(foreign-procedure \"(cs)thread_bytes_allocated\" () uptr)"))

(defn alloc-bytes
  "Returns cumulative heap bytes allocated on the current thread."
  {:malli/schema [:=> [:cat] :int]}
  []
  (long (alloc-fn)))

(def ^:private mono-fn  (scm/eval-string "(lambda () (jolt-monotonic-ns))"))

(defn measure-call
  {:malli/schema [:=> [:cat :seon.host/fn] :map]}
  [thunk]
  (let [t0 (mono-fn)
        k0 (ticks-fn)
        b0 (alloc-fn)
        v  (thunk)
        dk (max 0 (- (ticks-fn) k0))
        db (max 0 (- (alloc-fn) b0))
        ms (/ (Math/round (/ (double (- (mono-fn) t0)) 1e4)) 100.0)]
    {:val v :ticks (long dk) :bytes (long db) :ms (double ms)}))

(defn parent-ns-sym {:malli/schema [:=> [:cat :symbol] [:maybe :symbol]], :cost/ticks 50000, :cost/bytes 65536} [nsym] (parse/parent-ns-sym nsym))

(defn ns-parent-chain-tx [& args] (apply parse/ns-parent-chain-tx args))

(def boot-root-keys parse/boot-root-keys)

(defn boot-file-path? [& args] (apply reach/boot-file-path? args))

(def ^:private persistable-repl-heads parse/persistable-repl-heads)

(defn ir-unit-refs
  {:malli/schema [:=> [:cat :map] [:map [:refs [:set :symbol]] [:keywords [:set :qualified-keyword]]]]}
  [& args] (apply parse/ir-unit-refs args))

(defn clean-edn-for-hash [& args] (apply parse/clean-edn-for-hash args))

(def watched-runtime-syms parse/watched-runtime-syms)

(defn read-clj [& args] (apply parse/read-clj args))

(defn md-path? [& args] (apply parse/md-path? args))

(defn schema-file-path?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String path] (files/schema-file-path? path))

(defn doc-sections [& args] (apply cio/doc-sections args))

(defn render-doc [& args] (apply cio/render-doc args))

(defn ns-forms [& args] (apply cio/ns-forms args))

(defn ns-vars [& args] (apply cio/ns-vars args))

(defn- retract-stale-many-ops [& args] (apply cio/retract-stale-many-ops args))

(defn file-import-tx
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :string :string :boolean] [:vector :any]] [:=> [:cat [:maybe :seon.host/db] :string :string :boolean [:maybe :map]] [:vector :any]]]}
  [& args] (apply cio/file-import-tx args))

(defn render-path [& args] (apply cio/render-path args))

(defn register-loaded-ns! [& args] (apply overlay/register-loaded-ns! args))

(defn sort-forms-topologically [& args] (apply reach/sort-forms-topologically args))

(defn- arm-defined-vars-in-ns! [& args] (apply overlay/arm-defined-vars-in-ns! args))

(defn apply-overlay! [& args] (apply overlay/apply-overlay! args))

(defn changed-form-ops [& args] (apply reach/changed-form-ops args))

(defn load-delta! [& args] (apply overlay/load-delta! args))

(defn schema-kw->node-key [& args] (apply reach/schema-kw->node-key args))

(defn dep-sym->key [& args] (apply reach/dep-sym->key args))

(defn build-reach-graph [& args] (apply reach/build-reach-graph args))

(defn update-reach-graph [& args] (apply reach/update-reach-graph args))

(defn ns->default-path [& args] (apply parse/ns->default-path args))

(defn- format-eval-throwable [^Throwable t tick-budget byte-budget]
  (let [d (ex-data t)]
    (if (:jolt/interrupted d)
      (if (:jolt/ticks-spent d)
        (str "Eval exceeded tick budget (" (:jolt/ticks-spent d) " > " tick-budget
             " ticks). Break up the computation, fix the loop, or set `:seon.config/eval-ticks` on your agent entity.")
        (str "Eval exceeded allocation budget (limit " byte-budget " bytes)."))
      (or (.getMessage t) (str t)))))

(defn infer-result-schema
  "Infers the entity/value schema key of an eval result `v` from explicit `:seon/schema` metadata,
   `seon.schema.index/most-specific` fingerprinting, or lookup-ref vectors `[id-attr val]`."
  {:malli/schema [:function [:=> [:cat :any] [:maybe :qualified-keyword]] [:=> [:cat [:maybe :seon.host/db] :any] [:maybe :qualified-keyword]]]
   :cost/ticks 200000}
  ([v] (infer-result-schema nil v))
  ([db v]
   (let [ix (schema/entity-index db)]
     (or (when (instance? clojure.lang.IObj v) (:seon/schema (meta v)))
         (when (map? v)
           (let [ms (six/most-specific ix v)]
             (when (qualified-keyword? ms) ms)))
         (when (and (vector? v) (= 2 (count v)) (qualified-keyword? (first v)))
           (get-in ix [:identity-schemas (first v)]))))))

(defn reaching-tests [& args] (apply reach/reaching-tests args))

(defn ns-linked-docs [& args] (apply reach/ns-linked-docs args))

(defn- next-result-handle [db agent-id]
  (let [n (inc (:eval-count (store/agent-eval-history-stats db (or agent-id :repl))))]
    (symbol "result" (str "e" n))))

(defn- effective-eval-budgets [form-val base-ticks base-bytes]
  (reduce
   (fn [[t b] x]
     (if-let [ov (when (symbol? x)
                   (when-let [v (try (resolve x) (catch Throwable _ nil))]
                     (:seon.eval/budget-override (meta v))))]
       [(max t (long (or (:ticks ov) t)))
        (max b (long (or (:bytes ov) b)))]
       [t b]))
   [(long base-ticks) (long base-bytes)]
   (tree-seq coll? seq form-val)))

(defn task-file-path?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String path] (files/task-file-path? path))

(defn agent-file-path?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String path] (files/agent-file-path? path))

(defn rule-file-path?
  "Delegates to `seon.code.files/rule-file-path?`."
  {:malli/schema [:=> [:cat :string] :boolean]
   :cost/ticks 5000}
  [^String path]
  (files/rule-file-path? path))

(defn expand-overlay-rebinds [& args] (apply overlay/expand-overlay-rebinds args))

(defn infer-result-schemas
  "Returns the set of all matched schema keys for eval result `v` (single entity/value maps,
   lookup refs, and collections of maps/lookup refs up to 64 items)."
  {:malli/schema [:function
                  [:=> [:cat :any] [:set :qualified-keyword]]
                  [:=> [:cat [:maybe :seon.host/db] :any] [:set :qualified-keyword]]]
   :cost/ticks 500000}
  ([v] (infer-result-schemas nil v))
  ([db v]
   (let [ix (schema/entity-index db)
         one (fn [x]
               (let [explicit (when (instance? clojure.lang.IObj x)
                                (let [s (:seon/schema (meta x))]
                                  (when (qualified-keyword? s) s)))]
                 (cond-> #{}
                   explicit (conj explicit)
                   (map? x) (into (map :schema) (six/facets-of ix x))
                   (and (vector? x) (= 2 (count x)) (qualified-keyword? (first x)))
                   (into (when-let [s (get-in ix [:identity-schemas (first x)])] [s])))))]
     (cond
       (or (map? v)
           (and (vector? v) (= 2 (count v)) (qualified-keyword? (first v))))
       (one v)

       (and (coll? v) (seq v))
       (reduce (fn [acc item] (into acc (one item))) #{} (take 64 v))

       :else (one v)))))

(defn rebind-spliced-callers!
  {:seon.flow/edge true, :malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :int] [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol]] :int]]}
  ([db] (overlay/rebind-spliced-callers! db nil))
  ([db target-ns] (overlay/rebind-spliced-callers! db target-ns)))

(defn ^{:seon.code/reexport 'seon.code.persist/persist-forms-by-ns} persist-forms-by-ns [& args] (apply persist/persist-forms-by-ns args))

(defn- eval-single-form
  {:seon.flow/edge true}
  [db code-str f ir-by-var touched-all called-all ticks-cap bytes-cap acc]
  (let [src (:seon.code.form/src f) ns-before (ns-name *ns*)
        aborts0 (store/aborted-lmdb-write-txns) eff-caps (volatile! [ticks-cap bytes-cap])
        tap-acc (volatile! {:nodes [] :before {}})]
    (try
      (let [form-val (read-string src)
            form-head (when (and (seq? form-val) (symbol? (first form-val))) (symbol (name (first form-val))))
            unmap-target (when (= 'ns-unmap form-head)
                           (let [n-arg (second form-val) s-arg (nth form-val 2 nil)
                                 ns-v (cond (and (seq? n-arg) (= 'quote (first n-arg))) (second n-arg) (= '*ns* n-arg) ns-before :else nil)
                                 sv (when (and (seq? s-arg) (= 'quote (first s-arg))) (second s-arg))]
                             (when (and (symbol? ns-v) (symbol? sv) (not (contains? '#{user repl} ns-v)))
                               (symbol (name ns-v) (name sv)))))
            v (let [[eff-ticks eff-bytes] (vreset! eff-caps (effective-eval-budgets form-val ticks-cap bytes-cap))
                    [val rset] (reads/with-reads
                                 #(jolt.host/run-interruptible
                                   (jolt.host/make-interrupt)
                                   (fn [] (binding [jolt.analyzer/*unit-tap* (analyze/make-tap tap-acc)
                                                    store-cfg/*capturing-agent-eval-reads?* true]
                                            (eval form-val)))
                                   eff-ticks eff-bytes))]
                (let [seen-e (volatile! #{})
                      deduped (persistent!
                               (reduce (fn [a pair]
                                         (let [lo (when (vector? pair) (nth pair 0 nil)) hi (when (vector? pair) (nth pair 1 nil))
                                               e1 (when lo (:e lo)) e2 (when hi (:e hi))]
                                           (if (and (integer? e1) (pos? (long e1)) (= e1 e2))
                                             (if (contains? @seen-e e1) a (do (vswap! seen-e conj e1) (conj! a pair)))
                                             (if (or (when lo (:a lo)) (when hi (:a hi))) (conj! a pair) a))))
                                       (transient []) rset))]
                  (vswap! touched-all into deduped))
                val)
            nodes (:nodes @tap-acc)
            _ (when (seq nodes) (vswap! called-all into (analyze/called-seon-vars nodes)))
            ns-after (ns-name *ns*) is-ns? (= 'ns form-head)
            _ (when (= 'in-ns form-head)
                (when (empty? (ns-refers *ns*)) (clojure.core/refer-clojure))
                (when (and (str/ends-with? (name ns-after) "-test") (nil? (ns-resolve *ns* 'deftest)))
                  (try (clojure.core/require '[clojure.test :refer [deftest is testing]]) (catch Throwable _ nil))))
            _ (when is-ns? (register-loaded-ns! ns-after))
            target-ns (if is-ns? ns-after ns-before)
            _ (when-not (contains? '#{user repl} target-ns)
                (register-loaded-ns! target-ns)
                (when (and (seq nodes) (seq? form-val) (symbol? (second form-val))
                           (contains? persistable-repl-heads form-head) (not= 'ns form-head))
                  (vswap! ir-by-var assoc (symbol (name target-ns) (name (second form-val)))
                          (analyze/merge-accounts (map analyze/unit-account nodes))))
                (arm-defined-vars-in-ns! db target-ns))
            scratch-def? (and (= 'def form-head) (some #(and (symbol? %) (= "user" (namespace %))) (tree-seq coll? seq form-val)))
            recordable? (and (not (contains? '#{user repl} target-ns)) (contains? persistable-repl-heads form-head) (not scratch-def?))]
        (cond-> {:ok? true :value v :final-ns ns-after
                 :by-ns (if recordable? (update (:by-ns acc) target-ns (fnil conj []) src) (:by-ns acc))
                 :retracts (:retracts acc)}
          unmap-target (update :retracts (fnil conj []) unmap-target)))
      (catch Throwable t
        (let [[et eb] @eff-caps base-err (format-eval-throwable t et eb)
              _ (when (seq (:nodes @tap-acc)) (vswap! called-all into (analyze/called-seon-vars (:nodes @tap-acc))))
              blame-info (serr/classify-eval-blame {:ex-data (ex-data t) :called-vars (vec @called-all)} code-str)
              hidx (into {} (map (fn [[k v]] (let [m (meta v)] [(name k) {:arglists (some-> (:arglists m) pr-str) :doc (:doc m)}])))
                         (when-let [uns (find-ns 'user)] (ns-publics uns)))]
          (reduced {:ok? false :interrupted? (boolean (:jolt/interrupted (ex-data t)))
                    :lmdb-txn-aborted? (> (store/aborted-lmdb-write-txns) aborts0)
                    :poisoned-worker? (pos? (store/active-lmdb-write-txns))
                    :error (parse/enrich-eval-error code-str base-err hidx)
                    :blame-info blame-info :final-ns (ns-name *ns*) :by-ns (array-map) :retracts []}))))))

(defn- register-result-var!
  "Interns the eval result on the `result` namespace as `result/<handle>` with metadata."
  [res-handle value eid res-schema dms dk out-sw code-str]
  (let [rns (create-ns (quote result))
        rv (intern rns (symbol (name res-handle)) value)]
    (alter-meta! rv assoc
                 :seon.eval/id eid
                 :seon.eval/handle res-handle
                 :seon.eval/result-schema res-schema
                 :seon.eval/ms (double dms)
                 :seon.eval/ticks (long dk)
                 :seon.eval/out (str out-sw)
                 :seon.eval/code code-str)))

(defn- build-eval-entity
  [db ctx out-str eid res-handle res-schema res-schema-eids called-var-eids _touched-eids code-str dk dbytes dms ok? value error]
  (let [{:keys [agent-id turn call-index call-id seen-messages thought reasoning intent usage prefix-hash]} ctx
        aid (or agent-id :repl)
        ag-eid (when (and (contains? (:schema db) :seon.agent.eval/agent-ref) (contains? (:schema db) :seon.agent/id))
                 (:e (first (d/datoms db :avet :seon.agent/id aid))))]
    (cond-> {:seon.agent.eval/id eid :seon.agent.eval/agent aid :seon.agent.eval/turn (long (or turn 1))
             :seon.agent.eval/handle res-handle :seon.agent.eval/code code-str :seon.agent.eval/out out-str
             :seon.agent.eval/ticks (long dk) :seon.agent.eval/bytes (long dbytes) :seon.agent.eval/ms (double dms)}
      ag-eid (assoc :seon.agent.eval/agent-ref ag-eid)
      call-index (assoc :seon.agent.eval/call-index (long call-index))
      call-id (assoc :seon.agent.eval/call-id (str call-id))
      res-schema (assoc :seon.agent.eval/result-schema res-schema)
      (seq res-schema-eids) (assoc :seon.agent.eval/result-schemas res-schema-eids)
      (seq called-var-eids) (assoc :seon.agent.eval/called-vars called-var-eids)
      (seq seen-messages) (assoc :seon.agent.eval/seen-messages (set seen-messages))
      thought (assoc :seon.agent.eval/thought thought)
      reasoning (assoc :seon.agent.eval/reasoning reasoning)
      intent (assoc :seon.agent.eval/intent intent)
      prefix-hash (assoc :seon.agent.eval/prefix-hash (str prefix-hash))
      (:prompt-tokens usage) (assoc :seon.agent.eval/prompt-tokens (long (:prompt-tokens usage)))
      (:completion-tokens usage) (assoc :seon.agent.eval/completion-tokens (long (:completion-tokens usage)))
      (:cache-hit-tokens usage) (assoc :seon.agent.eval/cache-hit-tokens (long (:cache-hit-tokens usage)))
      (:cache-miss-tokens usage) (assoc :seon.agent.eval/cache-miss-tokens (long (:cache-miss-tokens usage)))
      ok? (assoc :seon.agent.eval/value (pr-str value))
      error (assoc :seon.agent.eval/error error))))

(defn eval-forms
  {:malli/schema [:=> [:cat :seon.host/db :map :string] :map]}
  [db {:keys [agent-id turn call-index call-id handle seen-messages thought reasoning intent usage prefix-hash default-ns tick-budget byte-budget]} ^String code-str]
  (let [t0 (mono-fn)
        k0 (ticks-fn)
        b0 (alloc-fn)
        start-ns (create-ns (or default-ns (quote user)))
        _ (binding [*ns* start-ns] (refer-clojure))
        ticks-cap (long (or tick-budget (store/config-val db agent-id :seon.config/eval-ticks) 100000000))
        bytes-cap (long (or byte-budget (store/config-val db agent-id :seon.config/eval-bytes) 268435456))
        out-sw (java.io.StringWriter.)
        ir-by-var (volatile! {})
        touched-all (volatile! [])
        called-all (volatile! #{})
        parsed (try {:ok? true :parsed (binding [schema/*bypass-contracts* true] (read-clj "<repl>" code-str))}
                    (catch Throwable t {:ok? false :error (str "Reader syntax error: " (.getMessage t))}))
        outcome
        (if-not (:ok? parsed)
          {:ok? false :error (:error parsed) :final-ns (ns-name start-ns) :by-ns (array-map)}
          (binding [*ns* start-ns
                    *out* out-sw]
            (reduce
             (fn [acc f] (eval-single-form db code-str f ir-by-var touched-all called-all ticks-cap bytes-cap acc))
             {:ok? true :value nil :final-ns (ns-name start-ns) :by-ns (array-map)}
             (get-in parsed [:parsed :forms]))))
        dk (max 0 (- (ticks-fn) k0))
        dbytes (max 0 (- (alloc-fn) b0))
        dms (/ (Math/round (/ (double (- (mono-fn) t0)) 1e4)) 100.0)
        {:keys [ok? value error final-ns by-ns retracts blame-info]} outcome
        eid (random-uuid)
        res-handle (or handle (next-result-handle db agent-id))
        res-schema (when ok? (infer-result-schema db value))
        res-schemas (when ok? (infer-result-schemas db value))
        res-schema-eids (when (and (seq res-schemas)
                                   (contains? (:schema db) :seon.agent.eval/result-schemas)
                                   (contains? (:schema db) :seon.schema/key))
                          (into #{} (keep #(:e (first (d/datoms db :avet :seon.schema/key %)))) res-schemas))
        called-syms (vec (sort-by str @called-all))
        called-var-eids (when (and (seq called-syms)
                                   (contains? (:schema db) :seon.agent.eval/called-vars)
                                   (contains? (:schema db) :seon.code.var/name))
                          (into #{} (keep #(:e (first (d/datoms db :avet :seon.code.var/name %)))) called-syms))
        _ (when ok?
            (register-result-var! res-handle value eid res-schema dms dk out-sw code-str))
        retract-tx (when (and ok? (seq retracts))
                     (vec (mapcat (fn [vsym]
                                    (let [ve (:e (first (d/datoms db :avet :seon.code.var/name vsym)))
                                          fe (:e (first (d/datoms db :avet :seon.code.form/key (str vsym))))]
                                      (cond-> [] ve (conj [:db/retractEntity ve]) fe (conj [:db/retractEntity fe]))))
                                  (distinct retracts))))
        persist-tx (vec (concat retract-tx (when (and ok? (seq by-ns)) (persist-forms-by-ns db by-ns @ir-by-var))))
        touched-eids (let [ag-eid (when (and db agent-id (contains? (:schema db) :seon.agent/id))
                                    (:e (first (d/datoms db :avet :seon.agent/id agent-id))))
                           eids (analyze/extract-domain-touched-eids db @touched-all persist-tx ag-eid nil)]
                       (when (seq eids)
                         (ctouches/record-live-reads! db (or agent-id :repl) (long (or turn 1)) eids {:code code-str}))
                       eids)
        eval-ent (build-eval-entity db {:agent-id agent-id :turn turn :call-index call-index
                                        :call-id call-id :seen-messages seen-messages
                                        :thought thought :reasoning reasoning :intent intent
                                        :usage usage :prefix-hash prefix-hash}
                                    (str out-sw) eid res-handle res-schema res-schema-eids called-var-eids touched-eids
                                    code-str dk dbytes dms ok? value error)]
    (cond-> {:ok? ok?
             :eval-id eid
             :call-id call-id
             :handle res-handle
             :result-schema res-schema
             :result-schemas res-schemas
             :called-vars called-syms
             :value value
             :value-str (:seon.agent.eval/value eval-ent)
             :out (str out-sw)
             :error error
             :ticks (long dk)
             :bytes (long dbytes)
             :ms (double dms)
             :interrupted? (boolean (:interrupted? outcome))
             :lmdb-txn-aborted? (boolean (:lmdb-txn-aborted? outcome))
             :poisoned-worker? (boolean (:poisoned-worker? outcome))
             :final-ns final-ns
             :touched-eids touched-eids
             :tx-data (vec (concat persist-tx [eval-ent]))}
      blame-info (assoc :blame-info blame-info))))

(defn eval-tool-calls
  {:malli/schema [:=> [:cat :seon.host/conn :map [:sequential :map]] :map]}
  [conn {:keys [agent-id turn seen-messages thought reasoning usage prefix-hash start-ns]} tool-calls]
  (loop [idx 0
         cur-ns (or start-ns 'user)
         remaining tool-calls
         results []]
    (if (empty? remaining)
      {:ok? (every? :ok? results)
       :final-ns cur-ns
       :results results}
      (let [{:keys [id code intent]} (first remaining)
            res (eval-forms @conn
                            {:agent-id agent-id
                             :turn turn
                             :call-index idx
                             :call-id (or id (str "call-" idx))
                             :seen-messages (when (zero? idx) seen-messages)
                             :thought (when (zero? idx) thought)
                             :reasoning (when (zero? idx) reasoning)
                             :usage (when (zero? idx) usage)
                             :prefix-hash (when (zero? idx) prefix-hash)
                             :intent intent
                             :default-ns cur-ns}
                            (or code ":noop"))]
        (if (:poisoned-worker? res)
        nil
        (do
          (when (:interrupted? res)
            (let [br (get-in @conn [:config :branch] :main)
                  latest (store/head conn br)]
              (when (and latest (not= (d/commit-id @conn) (d/commit-id latest)))
                (store/adopt-head! conn latest))))
          (binding [store/*tx-meta* {:seon.tx/agent (or agent-id :repl)
                                    :seon.tx/branch (get-in @conn [:config :branch] :main)}]
            (d/transact conn {:tx-data (:tx-data res)}))))
        (recur (inc idx)
               (:final-ns res cur-ns)
               (rest remaining)
               (conj results {:id (or id (str "call-" idx))
               :handle (:handle res)
               :result-schema (:result-schema res)
               :ok? (:ok? res)
               :value (:value-str res)
               :out (:out res)
               :error (:error res)
               :ticks (:ticks res)
               :ms (:ms res)
               :touched-eids (:touched-eids res)}))))))