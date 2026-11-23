(ns seon.code.mine
  "Mechanical Datahike miners & knowledge-graph analytics (pure tx-data generators + read-only reports):
   - `mine-schema-examples-tx`: backfills `:seon.schema/examples` from real Malli-valid attribute values or
     compact entity maps (<= 200 printed chars, refs and blobs skipped).
   - `mine-docstring-refs-tx`: lifts backticked `seon.*/*` vars and `:seon.*/*` schema keys in var docstrings
     into missing `:seon.code.var/refs`, `:seon.code.var/calls`, and `:seon.code.var/keywords` edges.
   - `graph-density-report`: single-pass AEVT coverage of contracts, docs, schema edges, and examples.
   - `turn0-recall-precision-report`: Turn-0 projected context vs each agent's actual trajectory touches."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [seon.health.static :as static]
            [seon.render.fit :as fit]
            [seon.schema :as schema]
            [seon.signals.touches :as touches]))

(defn- small? [v]
  (if (string? v)
    (<= (count v) 200)
    (<= (count (pr-str v)) 200)))

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
    (into [] (comp (filter valid?) (filter small?) (take 2)) vals)))

(defn- sample-entity-maps [db form valid?]
  (let [sch (:schema db)
        entries (keep (fn [e] (when (vector? e)
                                {:k (first e) :optional? (and (map? (second e)) (true? (:optional (second e))))}))
                      (drop 2 form))
        plain? #(and (contains? sch %) (not= :db.type/ref (get-in sch [% :db/valueType])))
        attrs (filterv plain? (map :k entries))
        anchor (some #(when (and (not (:optional? %)) (plain? (:k %))) (:k %)) entries)]
    (if-not anchor
      []
      (->> (d/datoms db :aevt anchor) (map :e) distinct (take 20)
           (map #(dissoc (d/pull db attrs %) :db/id))
           (into [] (comp (filter valid?) (filter small?) (take 2)))))))

(defn mine-schema-examples-tx
  "Pure tx-data backfilling `:seon.schema/examples` for every `:seon.schema/key` lacking it: up to 2 distinct real
   values of its Datahike attribute (cardinality-many as per-entity sets; `:db.type/ref` skipped) or, for
   `:seon.db/attributes` entity schemas, up to 2 compact pulled entity maps (ref attributes dropped), each
   validated against the live Malli schema and <= 200 printed chars."
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
                                  (and attr (not= :db.type/ref (:db/valueType attr))) (sample-attr-values db k valid?)
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

(defn- flat-aevt-set-map [db a]
  (if-not (contains? (:schema db) a)
    {}
    (reduce (fn [m dt] (update m (:e dt) (fnil conj #{}) (:v dt))) {} (d/datoms db :aevt a))))

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
            refs-by-e (flat-aevt-set-map db :seon.code.var/refs)
            calls-by-e (flat-aevt-set-map db :seon.code.var/calls)
            kws-by-e (flat-aevt-set-map db :seon.code.var/keywords)]
        (into []
              (keep (fn [dt]
                      (let [doc (str (:v dt))]
                        (when (str/includes? doc "`")
                          (let [e (:e dt)
                                self (get eid->var e)
                                pure-gen? (and (contains? gen-eids e) (not (contains? edge-eids e)))
                                {:keys [syms keywords]} (doc-mentions doc)
                                have-r (get refs-by-e e #{})
                                have-c (get calls-by-e e #{})
                                have-k (get kws-by-e e #{})
                                matched (keep (fn [sym]
                                                (when (not= sym self)
                                                  (when-let [te (get var->eid sym)] [sym te])))
                                              syms)
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

(defn- entity-id [db e]
  (some #(:v (first (d/datoms db :eavt e %))) [:seon.code.var/name :seon.code.ns/name :seon.schema/key]))

(defn- recent-agents [db {:keys [agents limit min-evals]}]
  (or (seq agents)
      (->> (d/datoms db :aevt :seon.agent.eval/agent)
           (reduce (fn [m dt] (update m (:v dt) (fn [[c tx]] [(inc (or c 0)) (max (or tx 0) (:tx dt))]))) {})
           (filter (fn [[_ [c _]]] (>= c (or min-evals 3))))
           (sort-by (fn [[a [_ tx]]] [(- tx) (str a)]))
           (map key)
           (take (or limit 4)))))

(defn- agent-turn0 [db aid budget]
  (let [lt (touches/extract-eval-touches db aid {:after-turn 0 :include-reasoning? false})
        pool (->> (touches/build-candidate-pool db db aid {:include-reasoning? false :eval-db db :upto-turn 0})
                  (remove #(every? (fn [r] (re-find #"eval|touch" (str r))) (:rules %))))
        shown (:selected (fit/upgrade-ladder (fit/rank-candidates (vec pool) nil)
                                             {:target :wire :strategy :breadth-first :token-budget budget}))
        s-ids (into #{} (keep #(or (when (integer? (:entity %)) (entity-id db (:entity %))) (:sym %))) shown)
        t-ids (into (into (set (:touched-syms lt)) (:touched-schemas lt))
                    (keep #(entity-id db %)) (:touched-eids lt))
        hits (count (filter t-ids s-ids))]
    {:agent aid :evals (:eval-count lt) :shown (count s-ids) :touched (count t-ids) :hits hits
     :precision (if (pos? (count s-ids)) (/ (double hits) (count s-ids)) 0.0)
     :recall (if (pos? (count t-ids)) (/ (double hits) (count t-ids)) 0.0)
     :gaps (vec (remove s-ids t-ids))}))

(defn turn0-recall-precision-report
  "Turn-0 predictive Precision & Recall across recent agents (`opts`: `:agents`, else the `:limit` (4) most recent
   with >= `:min-evals` (3) evals; `:token-budget` 16000): entities (vars/nss/schemas) projected by the context
   pipeline before any eval (eval-derived rules removed) vs everything touched across the agent's trajectory,
   with micro-averaged totals and the most frequent `:recall-gap-items`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map]] :map]
   :cost/ticks 5000000000}
  [db opts]
  (if-not (contains? (:schema db) :seon.agent.eval/agent)
    {:agents [] :precision 0.0 :recall 0.0 :recall-gap-items []}
    (let [per (mapv #(agent-turn0 db % (long (or (:token-budget opts) 16000))) (recent-agents db (or opts {})))
          sum (fn [k] (reduce + 0 (map k per)))
          hits (sum :hits)]
      {:agents (mapv #(dissoc % :gaps) per)
       :precision (if (pos? (sum :shown)) (/ (double hits) (sum :shown)) 0.0)
       :recall (if (pos? (sum :touched)) (/ (double hits) (sum :touched)) 0.0)
       :recall-gap-items (->> (mapcat :gaps per) frequencies (sort-by (fn [[k n]] [(- n) (str k)])) (take 15) (mapv key))})))
