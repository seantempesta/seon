(ns seon.code.mine
  "Mechanical Datahike miners & knowledge-graph analytics (pure tx-data generators + read-only reports):
   - `mine-schema-examples-tx`: backfills `:seon.schema/examples` from real EDN-safe, Malli-valid attribute values
     or compact entity maps (<= 200 printed chars, skipping refs, byte arrays, and base64 blobs).
   - `mine-docstring-refs-tx`: lifts backticked `seon.*/*` vars and `:seon.*/*` schema keys in var docstrings
     into missing `:seon.code.var/refs`, `:seon.code.var/calls`, and `:seon.code.var/keywords` edges.
   - `graph-density-report`: single-pass AEVT coverage of contracts, docs, schema edges, and examples.
   - `turn0-recall-precision-report`: Turn-0 projected context vs each modern agent's trajectory touches."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [seon.ctx :as ctx]
            [seon.health.static :as static]
            [seon.render.fit :as fit]
            [seon.schema :as schema]))

(defn- blob-string? [^String s]
  (or (str/starts-with? s "data:")
      (and (> (count s) 80) (boolean (re-find #"[A-Za-z0-9+/=]{64,}" s)))))

(defn- edn-safe? [v]
  (cond
    (or (nil? v) (boolean? v) (number? v) (keyword? v) (symbol? v) (uuid? v) (inst? v)) true
    (string? v) (not (blob-string? v))
    (bytes? v) false
    (or (vector? v) (set? v) (list? v)) (every? edn-safe? v)
    (map? v) (every? (fn [[k val]] (and (edn-safe? k) (edn-safe? val))) v)
    :else false))

(defn- small-edn? [v]
  (and (edn-safe? v)
       (if (string? v)
         (<= (count v) 200)
         (<= (count (pr-str v)) 200))))

(defn- true-eids [db a]
  (if (contains? (:schema db) a)
    (into #{} (keep #(when (true? (:v %)) (:e %))) (d/datoms db :aevt a))
    #{}))

(defn- attr-eids [db a]
  (if (contains? (:schema db) a)
    (into #{} (map :e) (d/datoms db :aevt a))
    #{}))

(defn- entity-form? [form]
  (and (vector? form) (= :map (first form)) (map? (second form)) (true? (:seon.db/attributes (second form)))))

(defn- sample-attr-values [db k valid?]
  (let [vals (if (= :db.cardinality/many (get-in db [:schema k :db/cardinality]))
               (->> (d/datoms db :aevt k) (map :e) distinct (take 20)
                    (map (fn [e] (into #{} (map :v) (d/datoms db :eavt e k)))))
               (->> (d/datoms db :aevt k) (map :v) (take 60) distinct))]
    (into [] (comp (filter small-edn?) (filter valid?) (take 2)) vals)))

(defn- sample-entity-maps [db form valid?]
  (let [sch (:schema db)
        entries (keep (fn [e] (when (vector? e)
                                {:k (first e) :optional? (and (map? (second e)) (true? (:optional (second e))))}))
                      (drop 2 form))
        plain? #(and (contains? sch %) (not (contains? #{:db.type/ref :db.type/bytes} (get-in sch [% :db/valueType]))))
        attrs (filterv plain? (map :k entries))
        anchor (some #(when (and (not (:optional? %)) (plain? (:k %))) (:k %)) entries)]
    (if-not anchor
      []
      (->> (d/datoms db :aevt anchor) (map :e) distinct (take 20)
           (map #(dissoc (d/pull db attrs %) :db/id))
           (into [] (comp (filter small-edn?) (filter valid?) (take 2)))))))

(defn mine-schema-examples-tx
  "Pure tx-data backfilling `:seon.schema/examples` for every `:seon.schema/key` lacking it: up to 2 distinct real
   values of its Datahike attribute (cardinality-many as per-entity sets; refs/bytes/base64 blobs skipped) or, for
   `:seon.db/attributes` entity schemas, up to 2 compact pulled entity maps, validated against live Malli and
   <= 200 printed EDN chars."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]
   :cost/ticks 4000000000}
  [db]
  (if-not (contains? (:schema db) :seon.schema/key)
    []
    (let [reg (:registry (schema/registry-for-db db))
          has-ex (attr-eids db :seon.schema/examples)]
      (into []
            (keep (fn [dt]
                    (when-not (contains? has-ex (:e dt))
                      (let [k (:v dt)
                            form (:v (first (d/datoms db :eavt (:e dt) :seon.schema/form)))
                            attr (get (:schema db) k)
                            vfn (delay (try (m/validator k {:registry reg}) (catch Throwable _ (constantly false))))
                            valid? #(try (@vfn %) (catch Throwable _ false))
                            exs (cond
                                  (entity-form? form) (sample-entity-maps db form valid?)
                                  (and attr (not (contains? #{:db.type/ref :db.type/bytes} (:db/valueType attr))))
                                  (sample-attr-values db k valid?)
                                  :else [])]
                        (when (seq exs)
                          {:seon.schema/key k :seon.schema/examples exs})))))
            (d/datoms db :aevt :seon.schema/key)))))

(defn doc-mentions
  "Backticked `seon.*/*` var symbols, `seon.*` namespace symbols, and `:seon.*/*` keywords mentioned in `doc`."
  {:malli/schema [:=> [:cat :string]
                  [:map [:syms [:set :qualified-symbol]] [:nss [:set :symbol]] [:keywords [:set :qualified-keyword]]]]
   :cost/ticks 5000000}
  [doc]
  {:syms (into #{}
               (map (fn [[_ s]] (let [i (str/index-of s "/")] (symbol (subs s 0 i) (subs s (inc i))))))
               (re-seq #"`(seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`" doc))
   :nss (into #{}
              (map (fn [[_ s]] (symbol s)))
              (re-seq #"`(seon\.[a-z0-9_-]+(?:\.[a-z0-9_-]+)*)`" doc))
   :keywords (into #{}
                   (map (fn [[_ s]] (keyword (subs s 1))))
                   (re-seq #"`(:seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`" doc))})

(defn mine-docstring-refs-tx
  "Pure O(N) flat-AEVT tx-data lifting backticked var docstring mentions into graph edges: existing `seon.*/*`
   vars missing from `:seon.code.var/refs` or `:seon.code.var/calls` (true `:db.type/ref` target EIDs), plus
   existing `:seon.*/*` schema keys missing from `:seon.code.var/keywords` (self-references skipped; skips adding
   `^:seon.flow/edge` targets into `:refs` of pure generatable functions so purity contracts stay exact)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]
   :cost/ticks 2000000000}
  [db]
  (let [sch (:schema db)]
    (if-not (every? #(contains? sch %) [:seon.code.var/doc :seon.code.var/name])
      []
      (let [var->eid (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name))
            eid->var (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.var/name))
            sch-keys (if (contains? sch :seon.schema/key)
                       (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
                       #{})
            edge-eids (true-eids db :seon.code.var/flow-edge?)
            gen-eids (if (contains? sch :seon.code.var/schema)
                       (into #{} (keep #(when (schema/generatable-function-schema? (:v %)) (:e %)))
                             (d/datoms db :aevt :seon.code.var/schema))
                       #{})
            eav-set (fn [e a] (if (contains? sch a) (into #{} (map :v) (d/datoms db :eavt e a)) #{}))]
        (into []
              (keep (fn [dt]
                      (let [doc (str (:v dt))]
                        (when (or (str/includes? doc "`seon.") (str/includes? doc "`:seon."))
                          (let [e (:e dt)
                                self (get eid->var e)
                                pure-gen? (and (contains? gen-eids e) (not (contains? edge-eids e)))
                                {:keys [syms keywords]} (doc-mentions doc)
                                matched (keep (fn [sym]
                                                (when (not= sym self)
                                                  (when-let [te (get var->eid sym)] [sym te])))
                                              syms)
                                have-r (when (seq matched) (eav-set e :seon.code.var/refs))
                                have-c (when (seq matched) (eav-set e :seon.code.var/calls))
                                have-k (when (seq keywords) (eav-set e :seon.code.var/keywords))
                                new-r (into #{} (keep (fn [[sym te]]
                                                        (when (and (not (have-r sym))
                                                                   (not (and pure-gen? (contains? edge-eids te))))
                                                          sym)))
                                            matched)
                                new-c (into #{} (keep (fn [[_ te]] (when-not (have-c te) te))) matched)
                                new-k (into #{} (filter #(and (not (have-k %)) (contains? sch-keys %))) keywords)]
                            (when (and self (or (seq new-r) (seq new-c) (seq new-k)))
                              (cond-> {:seon.code.var/name self}
                                (seq new-r) (assoc :seon.code.var/refs new-r)
                                (seq new-c) (assoc :seon.code.var/calls new-c)
                                (seq new-k) (assoc :seon.code.var/keywords new-k))))))))
              (d/datoms db :aevt :seon.code.var/doc))))))

(defn- opaque-kinds [sch]
  (when (and (vector? sch) (= :=> (first sch)))
    (let [[_ cat-s ret] (if (map? (second sch)) (rest sch) sch)
          args (when (vector? cat-s) (remove map? (rest cat-s)))
          parts (cons ret args)]
      {:map? (boolean (some #(or (contains? #{:map 'map?} %) (and (vector? %) (= [:map] (filterv (complement map?) %)))) parts))
       :any? (boolean (some #(= :any %) parts))})))

(defn graph-density-report
  "Single-pass flat-AEVT knowledge-graph density over public non-test Seon vars (indexed forks excluded) and
   `:seon.schema/*` entities: contract/doc/edge/example coverage plus pure (non-edge) `:=>` contracts whose
   arguments or return are opaque `:map` / `:any`."
  {:malli/schema [:=> [:cat :seon.host/db] :map]
   :cost/ticks 1000000000}
  [db]
  (let [t0 (System/nanoTime)
        sch (:schema db)
        hidden (into (true-eids db :seon.code.var/private?) (true-eids db :seon.code.var/test?))
        edge (true-eids db :seon.code.var/flow-edge?)
        foreign (static/foreign-nss db)
        pub (if (contains? sch :seon.code.var/ns)
              (into #{} (keep (fn [dt]
                                (let [n (:v dt)]
                                  (when (and (symbol? n) (not (hidden (:e dt))) (not (foreign n))
                                             (not (str/ends-with? (name n) "-test")))
                                    (:e dt)))))
                    (d/datoms db :aevt :seon.code.var/ns))
              #{})
        cnt (fn [a] (count (filter pub (attr-eids db a))))
        schemas (if (contains? sch :seon.code.var/schema)
                  (into [] (keep #(when (pub (:e %)) [(:e %) (:v %)])) (d/datoms db :aevt :seon.code.var/schema))
                  [])
        pure-kinds (keep (fn [[e s]] (when-not (edge e) (opaque-kinds s))) schemas)
        sch-eids (attr-eids db :seon.schema/key)]
    {:public-vars (count pub)
     :with-schema (count schemas)
     :with-doc (cnt :seon.code.var/doc)
     :with-input-edges (cnt :seon.code.var/input)
     :with-output-edges (cnt :seon.code.var/output)
     :with-examples (count (filter (fn [[_ s]] (and (vector? s) (map? (second s)) (contains? (second s) :examples))) schemas))
     :schemas-total (count sch-eids)
     :schemas-with-doc (count (filter sch-eids (attr-eids db :seon.schema/doc)))
     :schemas-with-examples (count (filter sch-eids (attr-eids db :seon.schema/examples)))
     :pure-opaque-map (count (filter :map? pure-kinds))
     :pure-opaque-any (count (filter :any? pure-kinds))
     :ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)}))

(def ^:private id-attrs
  [:seon.code.var/name :seon.code.ns/name :seon.code.doc/id :seon.schema/key :seon.task/id])

(defn- entity-id [db e]
  (when (integer? e)
    (some #(:v (first (d/datoms db :eavt e %))) id-attrs)))

(defn- modern-agent? [db aid]
  (and (qualified-keyword? aid)
       (not= "repl" (namespace aid))
       (when-let [e (:e (first (d/datoms db :avet :seon.agent/id aid)))]
         (boolean (or (first (d/datoms db :eavt e :seon.agent/task))
                      (first (d/datoms db :eavt e :seon.agent/scope)))))))

(defn- recent-agents [db {:keys [agents limit min-evals]}]
  (or (seq agents)
      (->> (d/datoms db :aevt :seon.agent.eval/agent)
           (reduce (fn [m dt] (update m (:v dt) (fn [[c tx]] [(inc (or c 0)) (max (or tx 0) (:tx dt))]))) {})
           (filter (fn [[a [c _]]] (and (>= c (or min-evals 3)) (modern-agent? db a))))
           (sort-by (fn [[a [_ tx]]] [(- tx) (str a)]))
           (map key)
           (take (or limit 4)))))

(defn- eval-code-entities [db eids]
  (let [sch (:schema db)
        has? (fn [a v] (and (contains? sch a) (boolean (first (d/datoms db :avet a v)))))]
    (into #{}
          (mapcat (fn [e]
                    (let [code (str (:v (first (d/datoms db :eavt e :seon.agent.eval/code))))]
                      (concat
                       (keep (fn [[_ s]]
                               (let [i (str/index-of s "/")
                                     sym (symbol (subs s 0 i) (subs s (inc i)))]
                                 (when (has? :seon.code.var/name sym) sym)))
                             (re-seq #"(?<![a-zA-Z0-9._/-])(seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)" code))
                       (keep (fn [[_ s]]
                               (let [k (keyword (subs s 1))]
                                 (when (has? :seon.schema/key k) k)))
                             (re-seq #"(:seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)" code))))))
          eids)))

(defn- agent-turn0 [db aid budget as-of?]
  (let [eval-dts (vec (d/datoms db :avet :seon.agent.eval/agent aid))
        eids (mapv :e eval-dts)
        first-tx (when (seq eval-dts) (reduce min (map :tx eval-dts)))
        t0-db (if (and as-of? first-tx (not (instance? datahike.db.AsOfDB db)))
                (try (d/as-of db (dec (long first-tx))) (catch Throwable _ db))
                db)
        ag (or (d/pull t0-db '[:seon.agent/id :seon.agent/role :seon.agent/task :seon.agent/scope
                               :seon.agent/branch :seon.agent/pinned] [:seon.agent/id aid])
               {:seon.agent/id aid :seon.agent/role :worker})
        eval-rule? #(boolean (re-find #"eval|touch|error" (str %)))
        cands (->> (:candidates (ctx/gather-agent-candidates t0-db t0-db aid
                                  {:agent ag :target :wire :include-evals? false :include-ctx-snapshot? false
                                   :enrich? false :situational? false :semantic? false :ru-budget budget}))
                   (remove #(and (seq (:rules %)) (every? eval-rule? (:rules %))))
                   vec)
        shown (:selected (fit/upgrade-ladder (fit/rank-candidates cands nil)
                                             {:target :wire :strategy :breadth-first :token-budget budget}))
        s-ids (into #{} (keep #(or (entity-id db (:entity %)) (:sym %))) shown)
        stored-t (if (contains? (:schema db) :seon.agent.eval/touched)
                   (into #{} (comp (mapcat #(map :v (d/datoms db :eavt % :seon.agent.eval/touched)))
                                   (mapcat #(if (coll? %) % [%]))
                                   (keep #(entity-id db %)))
                         eids)
                   #{})
        t-ids (into stored-t (eval-code-entities db eids))
        touched-nss (into #{} (keep #(when (and (symbol? %) (namespace %)) (symbol (namespace %)))) t-ids)
        hit? #(or (contains? t-ids %) (contains? touched-nss %))
        covered? #(or (contains? s-ids %) (and (symbol? %) (namespace %) (contains? s-ids (symbol (namespace %)))))
        hits (count (filter hit? s-ids))
        cov (count (filter covered? t-ids))]
    {:agent aid :evals (count eids) :shown (count s-ids) :touched (count t-ids) :hits hits :covered cov
     :precision (if (pos? (count s-ids)) (/ (double hits) (count s-ids)) 0.0)
     :recall (if (pos? (count t-ids)) (/ (double cov) (count t-ids)) 0.0)
     :gaps (vec (remove covered? t-ids))}))

(defn turn0-recall-precision-report
  "Turn-0 predictive Precision & Recall across recent modern agents (`opts`: `:agents`, else up to `:limit` (4)
   recent non-`:repl/*` agents with task/scope and >= `:min-evals` (3) evals; `:token-budget` 16000; `:as-of?`
   false for fast live-db replay with eval-rules stripped, or true for `d/as-of` pre-eval replay). Compares
   Turn-0 projected entities against entities touched/referenced across the trajectory plus top `:recall-gap-items`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map]] :map]
   :cost/ticks 5000000000}
  [db opts]
  (if-not (contains? (:schema db) :seon.agent.eval/agent)
    {:agents [] :precision 0.0 :recall 0.0 :recall-gap-items []}
    (let [o (or opts {})
          budget (long (or (:token-budget o) 16000))
          as-of? (boolean (:as-of? o))
          per (mapv #(agent-turn0 db % budget as-of?) (recent-agents db o))
          sum (fn [k] (reduce + 0 (map k per)))
          s-tot (sum :shown)
          t-tot (sum :touched)]
      {:agents (mapv #(dissoc % :gaps) per)
       :precision (if (pos? s-tot) (/ (double (sum :hits)) s-tot) 0.0)
       :recall (if (pos? t-tot) (/ (double (sum :covered)) t-tot) 0.0)
       :recall-gap-items (->> (mapcat :gaps per) frequencies (sort-by (fn [[k n]] [(- n) (str k)])) (take 15) (mapv key))})))
