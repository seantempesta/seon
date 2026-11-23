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
            [seon.code.overlay :as overlay]))

(def ^:private ticks-fn (scm/eval-string "(lambda () (jolt-ticks))"))

(def ^:private alloc-fn (scm/eval-string "(foreign-procedure \"(cs)thread_bytes_allocated\" () uptr)"))

(defn alloc-bytes
  "Returns cumulative heap bytes allocated on the current thread."
  []
  (long (alloc-fn)))

(def ^:private mono-fn  (scm/eval-string "(lambda () (jolt-monotonic-ns))"))

(defn measure-call [thunk]
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

(defn ir-unit-refs [& args] (apply parse/ir-unit-refs args))

(defn clean-edn-for-hash [& args] (apply parse/clean-edn-for-hash args))

(def watched-runtime-syms parse/watched-runtime-syms)

(defn read-clj [& args] (apply parse/read-clj args))

(defn md-path? [& args] (apply parse/md-path? args))

(defn schema-file-path? [^String path] (files/schema-file-path? path))

(defn doc-sections [& args] (apply cio/doc-sections args))

(defn render-doc [& args] (apply cio/render-doc args))

(defn ns-forms [& args] (apply cio/ns-forms args))

(defn ns-vars [& args] (apply cio/ns-vars args))

(defn- retract-stale-many-ops [& args] (apply cio/retract-stale-many-ops args))

(defn file-import-tx [& args] (apply cio/file-import-tx args))

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

(defn persist-forms-by-ns
  "Persists newly defined or updated top-level Clojure form source strings grouped by namespace
   `{nsym [src ...]}` into Datahike tx-data with automatic topological form reordering."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] [:vector :any]]}
  [db ns-entries ir-refs-by-var]
  (vec
   (mapcat
    (fn [[target-ns srcs]]
      (let [existing (ns-forms db target-ns)
            existing-ns-src (some #(when (= (:seon.code.form/key %) (str target-ns "#ns")) (:seon.code.form/src %)) existing)
            bare-ns? (fn [s]
                       (when (str/starts-with? (str/triml s) "(ns ")
                         (let [v (try (read-string s) (catch clojure.lang.ExceptionInfo _ nil))]
                         (and (seq? v) (= 'ns (first v)) (<= (count v) 2)))))
            srcs (if existing-ns-src (vec (remove bare-ns? srcs)) srcs)
            has-ns-form? (some #(str/starts-with? (str/triml %) "(ns ") srcs)
            existing-local-defs (into #{} (map #(symbol (name (:seon.code.var/name %)))) (ns-vars db target-ns))
            header (cond
                     has-ns-form? ""
                     existing-ns-src (str existing-ns-src "\n")
                     :else (str "(ns " target-ns ")\n"))
            combined (str header (str/join "\n" srcs))
            reparsed (read-clj (ns->default-path target-ns) combined existing-local-defs)
            kept-forms (if has-ns-form?
                         (:forms reparsed)
                         (vec (rest (:forms reparsed))))
            ir-extra-refs ir-refs-by-var
            kept-vars (let [rvars (:vars reparsed)
                local-var-refs (into {}
                                     (map (fn [v]
                                            (let [vs (:seon.code.var/name v)]
                                              [vs (or (when (contains? (:schema db) :seon.code.var/name)
                                                        (:e (first (d/datoms db :avet :seon.code.var/name vs))))
                                                      (analyze/var-tempid vs))])))
                                     rvars)
                ns-eid (when (contains? (:schema db) :seon.code.ns/name)
                         (:e (first (d/datoms db :avet :seon.code.ns/name target-ns))))
                reg-info (when (some :seon.code.var/schema rvars)
                           (store/fast-registry-for-db db))
                bctx (cond-> {:var->ref local-var-refs
                              :assign-tempid? true
                              :var-lookup-cache (volatile! {})
                              :schema-lookup-cache (volatile! {})}
                       ns-eid (assoc :ns-eid ns-eid)
                       reg-info (assoc :registry (:registry reg-info)
                                       :known-schemas (set (keys (:forms reg-info)))))]
            (mapv (fn [v]
                    (let [v-extra (get ir-extra-refs (:seon.code.var/name v))
                          acct (if (map? v-extra) v-extra (when (seq v-extra) {:refs v-extra}))]
                      (analyze/enrich-var-with-account db v acct boot-root-keys bctx)))
                  rvars))
            by-key (into {} (map (juxt :seon.code.form/key identity)) existing)
            max-ord (reduce max -1 (map :seon.code.form/order existing))
            ns-chain (ns-parent-chain-tx target-ns)
            ns-ent (let [existing-ns (let [p (d/pull db '[:seon.code.ns/name :seon.code.ns/path :seon.code.ns/tail]
                                                     [:seon.code.ns/name target-ns])]
                                       (when (:seon.code.ns/name p) p))
                         base (if existing-ns
                                (merge (peek ns-chain) existing-ns)
                                (assoc (peek ns-chain)
                                       :seon.code.ns/path (ns->default-path target-ns)
                                       :seon.code.ns/tail "\n"))]
                     (cond-> base
                       (and (or has-ns-form? (nil? existing-ns)) (:doc reparsed))
                       (assoc :seon.code.ns/doc (:doc reparsed))
                       (and (or has-ns-form? (nil? existing-ns)) (seq (:requires reparsed)))
                       ((fn [m]
                       (let [rr (when (contains? (:schema db) :seon.code.ns/requires-ns)
                                  (analyze/resolve-ns-requires-refs db (:requires reparsed)))]
                         (cond-> (assoc m :seon.code.ns/requires (:requires reparsed))
                           (seq rr) (assoc :seon.code.ns/requires-ns rr)))))))
            stale-ns-reqs (when has-ns-form?
                            (retract-stale-many-ops db :seon.code.ns/name [:seon.code.ns/requires :seon.code.ns/requires-ns] [ns-ent]))
            [_ raw-form-txs]
            (reduce
             (fn [[next-ord acc] f]
               (let [is-ns-hdr? (str/ends-with? (:seon.code.form/key f "") "#ns")]
                 (if-let [prev (get by-key (:seon.code.form/key f))]
                   [next-ord (conj acc (assoc f
                                              :seon.code.form/order (:seon.code.form/order prev)
                                              :seon.code.form/lead (:seon.code.form/lead prev)))]
                   (if (and is-ns-hdr? (seq existing))
                     (let [min-ord (reduce min 0 (map :seon.code.form/order existing))]
                       [next-ord (conj acc (assoc f
                                                  :seon.code.form/order (dec min-ord)
                                                  :seon.code.form/lead ""))])
                     [(inc next-ord) (conj acc (assoc f
                                                      :seon.code.form/order next-ord
                                                      :seon.code.form/lead (if (zero? next-ord) "" "\n\n")))]))))
             [(inc max-ord) []]
             kept-forms)
            merged-all (vals (merge by-key (into {} (map (juxt :seon.code.form/key identity)) raw-form-txs)))
            new-keys (into #{} (remove #(contains? by-key %)) (map :seon.code.form/key raw-form-txs))
            need-reorder?
            (and (seq new-keys)
                 (let [ord-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/order)) merged-all)]
                   (some (fn [f]
                           (let [co (get ord-by-k (:seon.code.form/key f) 0)]
                             (some (fn [dep]
                                     (let [dk (str dep)]
                                       (and (contains? new-keys dk)
                                            (< co (get ord-by-k dk 0)))))
                                   (:seon.code.form/deps f))))
                         merged-all)))
            form-txs
            (if-not need-reorder?
              raw-form-txs
              (let [ns-hdr? #(str/ends-with? (:seon.code.form/key % "") "#ns")
                    existing-ord (vec (sort-by (fn [f] [(if (ns-hdr? f) 0 1) (:seon.code.form/order f 0) (:seon.code.form/key f "")])
                                               (remove #(contains? new-keys (:seon.code.form/key %)) merged-all)))
                    new-ord (vec (sort-by :seon.code.form/order (filter #(contains? new-keys (:seon.code.form/key %)) raw-form-txs)))
                    placed (reduce
                            (fn [acc nf]
                              (let [nk (:seon.code.form/key nf)
                                    nsym-dep (when (str/includes? nk "/") (symbol nk))
                                    idx (when nsym-dep
                                          (first (keep-indexed
                                                  (fn [i ef]
                                                    (when (contains? (set (:seon.code.form/deps ef)) nsym-dep) i))
                                                  acc)))]
                                (if idx
                                  (vec (concat (subvec acc 0 idx) [nf] (subvec acc idx)))
                                  (conj acc nf))))
                            existing-ord
                            (reverse new-ord))
                    tx-by-k (into {} (map (juxt :seon.code.form/key identity)) raw-form-txs)]
                (vec
                 (keep-indexed
                  (fn [i f]
                    (let [k (:seon.code.form/key f)]
                      (if-let [changed (get tx-by-k k)]
                        (assoc changed :seon.code.form/order (long i))
                        (when (not= (long i) (long (:seon.code.form/order f -1)))
                          {:seon.code.form/key k :seon.code.form/order (long i)}))))
                  placed))))
            stale-form-deps (retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps]
                                                    (filter :seon.code.form/src form-txs))
            stale-var-refs  (analyze/stale-var-edge-retracts db kept-vars)]
        (vec (concat stale-ns-reqs stale-form-deps stale-var-refs (pop ns-chain) [ns-ent] form-txs kept-vars))))
    ns-entries)))

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

(defn task-file-path? [^String path] (files/task-file-path? path))

(defn agent-file-path? [^String path] (files/agent-file-path? path))

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

(defn eval-forms
{:malli/schema [:=> [:cat :seon.host/db :map :string] :map]}
[db {:keys [agent-id turn call-index call-id handle seen-messages thought reasoning intent usage prefix-hash default-ns tick-budget byte-budget]} ^String code-str]
(let [t0 (mono-fn)
      k0 (ticks-fn)
      b0 (alloc-fn)
      start-ns (create-ns (or default-ns 'user))
      _ (binding [*ns* start-ns] (refer-clojure))
      ticks-cap (long (or tick-budget (store/config-val db agent-id :seon.config/eval-ticks) 100000000))
      bytes-cap (long (or byte-budget (store/config-val db agent-id :seon.config/eval-bytes) 268435456))
      out-sw (java.io.StringWriter.)
      ir-by-var (volatile! {})
      touched-all (volatile! [])
      parsed (try {:ok? true :parsed (binding [schema/*bypass-contracts* true] (read-clj "<repl>" code-str))}
                  (catch Throwable t {:ok? false :error (str "Reader syntax error: " (.getMessage t))}))
      outcome
      (if-not (:ok? parsed)
        {:ok? false :error (:error parsed) :final-ns (ns-name start-ns) :by-ns (array-map)}
        (binding [*ns* start-ns
                  *out* out-sw]
          (reduce
           (fn [acc f]
             (let [src (:seon.code.form/src f)
                   trimmed (str/triml src)
                   ns-before (ns-name *ns*) aborts0 (store/aborted-lmdb-write-txns)]
               (try
                 (let [form-val (read-string src)
                       form-head (when (and (seq? form-val) (symbol? (first form-val)))
                                   (symbol (name (first form-val))))
                       tap-acc (volatile! {:nodes [] :before {}})
                       v (let [[eff-ticks eff-bytes] (effective-eval-budgets form-val ticks-cap bytes-cap)
                               [val rset] (reads/with-reads
                                            (fn []
                                              (jolt.host/run-interruptible
                                               (jolt.host/make-interrupt)
                                               (fn []
                                                 (binding [jolt.analyzer/*unit-tap* (analyze/make-tap tap-acc)]
                                                   (eval form-val)))
                                               eff-ticks
                                               eff-bytes)))]
                           (when (< (count @touched-all) 512) (vswap! touched-all into (take (- 512 (count @touched-all)) rset)))
                           val)
                       ns-after (ns-name *ns*)
                       is-ns? (= 'ns form-head)
                       _ (when is-ns? (register-loaded-ns! ns-after))
                       target-ns (if is-ns? ns-after ns-before)
                       _ (when-not (contains? #{'user 'repl} target-ns)
                           (register-loaded-ns! target-ns)
                           (when (and (seq? form-val) (symbol? (second form-val))
                                      (contains? persistable-repl-heads form-head)
                                      (not= 'ns form-head))
                             (let [acct (analyze/merge-accounts (map analyze/unit-account (:nodes @tap-acc)))]
                             (vswap! ir-by-var assoc (symbol (name target-ns) (name (second form-val))) acct)))
                           (arm-defined-vars-in-ns! db target-ns))
                       scratch-def? (and (= 'def form-head)
                                         (some #(and (symbol? %) (= "user" (namespace %)))
                                               (tree-seq coll? seq form-val)))
                       recordable? (and (not (contains? #{'user 'repl} target-ns))
                                        (contains? persistable-repl-heads form-head)
                                        (not scratch-def?))]
                   {:ok? true
                    :value v
                    :final-ns ns-after
                    :by-ns (if recordable?
                             (update (:by-ns acc) target-ns (fnil conj []) src)
                             (:by-ns acc))})
                 (catch Throwable t
                 (let [base-err (format-eval-throwable t ticks-cap bytes-cap)
                       hidx (into {} (map (fn [[k v]]
                                            (let [m (meta v)]
                                              [(name k) {:arglists (some-> (:arglists m) pr-str)
                                                         :doc (:doc m)}])))
                                  (when-let [uns (find-ns 'user)] (ns-publics uns)))]
                   (reduced {:ok? false
                   :interrupted? (boolean (:jolt/interrupted (ex-data t)))
                   :lmdb-txn-aborted? (> (store/aborted-lmdb-write-txns) aborts0)
                   :poisoned-worker? (pos? (store/active-lmdb-write-txns))
                   :error (parse/enrich-eval-error code-str base-err hidx)
                   :final-ns (ns-name *ns*)
                   :by-ns (:by-ns acc)}))))))
           {:ok? true :value nil :final-ns (ns-name start-ns) :by-ns (array-map)}
           (get-in parsed [:parsed :forms]))))
      dk (max 0 (- (ticks-fn) k0))
      dbytes (max 0 (- (alloc-fn) b0))
      dms (/ (Math/round (/ (double (- (mono-fn) t0)) 1e4)) 100.0)
      {:keys [ok? value error final-ns by-ns]} outcome
      eid (random-uuid)
      res-handle (or handle (next-result-handle db agent-id))
      res-schema (when ok? (infer-result-schema db value))
      res-schemas (when ok? (infer-result-schemas db value))
      res-schema-eids (when (and (seq res-schemas)
                                 (contains? (:schema db) :seon.agent.eval/result-schemas)
                                 (contains? (:schema db) :seon.schema/key))
                        (into #{} (keep #(:e (first (d/datoms db :avet :seon.schema/key %)))) res-schemas))
      _ (when ok?
          (let [rns (create-ns 'result)
                rv (intern rns (symbol (name res-handle)) value)]
            (alter-meta! rv assoc
                         :seon.eval/id eid
                         :seon.eval/handle res-handle
                         :seon.eval/result-schema res-schema
                         :seon.eval/ms (double dms)
                         :seon.eval/ticks (long dk)
                         :seon.eval/out (str out-sw)
                         :seon.eval/code code-str)))
      persist-tx (when (seq by-ns)
                   (persist-forms-by-ns db by-ns @ir-by-var))
      eval-ent (cond-> {:seon.agent.eval/id eid
                        :seon.agent.eval/agent (or agent-id :repl)
                        :seon.agent.eval/turn (long (or turn 1))
                        :seon.agent.eval/handle res-handle
                        :seon.agent.eval/code code-str
                        :seon.agent.eval/out (str out-sw)
                        :seon.agent.eval/ticks (long dk)
                        :seon.agent.eval/bytes (long dbytes)
                        :seon.agent.eval/ms (double dms)}
                 call-index (assoc :seon.agent.eval/call-index (long call-index))
                 call-id    (assoc :seon.agent.eval/call-id (str call-id))
                 res-schema (assoc :seon.agent.eval/result-schema res-schema)
                 (seq res-schema-eids) (assoc :seon.agent.eval/result-schemas res-schema-eids)
                 (seq seen-messages) (assoc :seon.agent.eval/seen-messages (set seen-messages))
                 thought    (assoc :seon.agent.eval/thought thought)
                 reasoning  (assoc :seon.agent.eval/reasoning reasoning)
                 intent     (assoc :seon.agent.eval/intent intent)
                 prefix-hash (assoc :seon.agent.eval/prefix-hash (str prefix-hash))
                 (:prompt-tokens usage) (assoc :seon.agent.eval/prompt-tokens (long (:prompt-tokens usage)))
                 (:completion-tokens usage) (assoc :seon.agent.eval/completion-tokens (long (:completion-tokens usage)))
                 (:cache-hit-tokens usage) (assoc :seon.agent.eval/cache-hit-tokens (long (:cache-hit-tokens usage)))
                 (:cache-miss-tokens usage) (assoc :seon.agent.eval/cache-miss-tokens (long (:cache-miss-tokens usage)))
                 ok?        (assoc :seon.agent.eval/value (pr-str value))
                 (and (contains? (:schema db) :seon.agent.eval/touched) (seq (analyze/extract-domain-touched-eids db @touched-all persist-tx (when (and db agent-id (contains? (:schema db) :seon.agent/id)) (:e (first (d/datoms db :avet :seon.agent/id agent-id)))) 64)))
                 (assoc :seon.agent.eval/touched (analyze/extract-domain-touched-eids db @touched-all persist-tx (when (and db agent-id (contains? (:schema db) :seon.agent/id)) (:e (first (d/datoms db :avet :seon.agent/id agent-id)))) 64))
                 error      (assoc :seon.agent.eval/error error))]
  {:ok? ok?
  :eval-id eid
  :call-id call-id
  :handle res-handle
  :result-schema res-schema
  :result-schemas res-schemas
  :value value
  :value-str (when ok? (pr-str value))
  :out (str out-sw)
  :error error
  :ticks (long dk)
  :bytes (long dbytes)
  :ms (double dms)
  :interrupted? (boolean (:interrupted? outcome))
  :lmdb-txn-aborted? (boolean (:lmdb-txn-aborted? outcome))
  :poisoned-worker? (boolean (:poisoned-worker? outcome))
  :final-ns final-ns
  :tx-data (vec (concat persist-tx [eval-ent]))}))

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
                (reset! (:wrapped-atom conn) latest))))
          (binding [store/*tx-meta* (cond-> {:seon.tx/agent (or agent-id :repl)
                                             :seon.tx/branch (get-in @conn [:config :branch] :main)}
                                      (:eval-id res) (assoc :seon.tx/eval (:eval-id res)))]
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
                              :ms (:ms res)}))))))

(defn rebind-spliced-callers!
  {:seon.flow/edge true, :malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :int] [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol]] :int]]}
  ([db] (overlay/rebind-spliced-callers! db nil))
  ([db target-ns] (overlay/rebind-spliced-callers! db target-ns)))