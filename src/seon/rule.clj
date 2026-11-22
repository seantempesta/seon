(ns seon.rule
  "Pure, datom-native stratified forward-chaining logic engine over Datahike:
   - Working memory is an immutable indexed EAV fact store (`:facts`, `:eav`, `:aev`, `:ave`, `:provenance`).
   - Rules are plain editable Datahike entities (`:seon.rule/*`, persisted to `resources/seon/rules.edn`).
   - `[:db :branch|:main [?e ?a ?v ?tx]]` probes Datahike indexes via cheapest-index dispatch inside
     `datahike.reads/with-reads`, capturing exact per-rule read-sets automatically.
   - `step-rules` skips untouched rules in O(|tx-datoms|) via `datahike.reads/reads-touched?` +
     working-memory attribute delta propagation; editing a `:seon.rule/*` entity re-fires only
     that rule and its downstream dependents.
   - Supports `clojure.core/derive` hierarchies (`[:isa ?v :ancestor]`), stratified negation (`[:not ...]`),
     optional joins (`[:optional ...]`), aggregation (`[:count ...]`, `[:max ...]`), safe pure
     expressions (`[:guard ...]`, `[:bind ...]`), per-fact provenance, and A/B `diff-sessions`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store.query :as sq]
            [seon.store :as store]
            [datahike.index.interface :as di]))

(def default-hierarchy
  "Default `clojure.core/derive` fact hierarchy for polymorphic rule matching."
  (reduce (fn [h [child parent]] (derive h child parent))
          (make-hierarchy)
          [[:rel/mentions-ns      :rel/ns-connection]
           [:rel/scope            :rel/ns-connection]
           [:rel/extra-ns         :rel/ns-connection]
           [:rel/tree-ns          :rel/ns-connection]
           [:rel/mentions-var     :rel/var-connection]
           [:rel/search-hit       :rel/var-connection]
           [:rel/tree-var         :rel/var-connection]
           [:rel/covering-test    :rel/var-connection]
           [:rel/mentions-schema  :rel/schema-connection]
           [:rel/tree-schema      :rel/schema-connection]
           [:rel/curated          :rel/strong-signal]
           [:rel/mentions-ns      :rel/strong-signal]
           [:rel/mentions-var     :rel/strong-signal]
           [:rel/mentions-schema  :rel/strong-signal]
           [:rel/tree-overlap     :rel/strong-signal]
           [:ctx.match/embedding  :ctx.match/semantic]
           [:ctx.match/search     :ctx.match/semantic]]))

(defn lvar?
  "True when `x` is a logic variable symbol starting with `?`."
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 500}
  [x]
  (and (symbol? x) (str/starts-with? (name x) "?")))

(defn wildcard?
  "True when `x` is the wildcard symbol `_`."
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 200}
  [x]
  (= x '_))

(defn resolve-term
  "Resolves logic variable `x` in binding map `env`, returning `::unbound` if unbound."
  {:malli/schema [:=> [:cat :map :any] :any]
   :cost/ticks 500}
  [env x]
  (cond
  (wildcard? x) ::unbound
  (lvar? x) (get env x ::unbound)
  :else x))

(defn unify-term
  "Unifies pattern term `pat` against ground value `val` in `env`, returning updated `env` or `nil`."
  {:malli/schema [:=> [:cat :map :any :any] [:maybe :map]]
   :cost/ticks 1000}
  [env pat val]
  (cond
    (wildcard? pat) env
    (lvar? pat) (let [cur (get env pat ::unbound)]
                  (cond
                    (= cur ::unbound) (assoc env pat val)
                    (= cur val) env
                    :else nil))
    (= pat val) env
    :else nil))

(defn eval-expr
  "Pure, deterministic, zero-`eval` expression evaluator for rule `:guard`, `:bind`, and `:assert` templates."
  {:malli/schema [:=> [:cat :map :any :any] :any]
   :cost/ticks 50000}
  [env h expr]
  (cond
    (lvar? expr) (get env expr)
    (not (seq? expr)) expr
    :else
    (let [[op & args] expr
          ev (fn [a] (eval-expr env h a))]
      (case op
        and (loop [xs args last-v true]
              (if (empty? xs) last-v
                  (let [v (ev (first xs))]
                    (if v (recur (rest xs) v) false))))
        or  (loop [xs args]
              (if (empty? xs) false
                  (let [v (ev (first xs))]
                    (if v v (recur (rest xs))))))
        not (not (ev (first args)))
        if  (if (ev (first args)) (ev (second args)) (when (nnext args) (ev (nth args 2))))
        =   (apply = (map ev args))
        not= (apply not= (map ev args))
        <   (let [vs (map ev args)] (and (every? number? vs) (apply < vs)))
        <=  (let [vs (map ev args)] (and (every? number? vs) (apply <= vs)))
        >   (let [vs (map ev args)] (and (every? number? vs) (apply > vs)))
        >=  (let [vs (map ev args)] (and (every? number? vs) (apply >= vs)))
        +   (apply + (map #(let [v (ev %)] (if (number? v) v 0)) args))
        -   (apply - (map #(let [v (ev %)] (if (number? v) v 0)) args))
        *   (apply * (map #(let [v (ev %)] (if (number? v) v 1)) args))
        /   (let [vs (map ev args)]
              (if (and (seq (rest vs)) (every? #(and (number? %) (not (zero? %))) (rest vs)))
                (apply / vs)
                0))
        max (let [vs (filter number? (map ev args))] (when (seq vs) (apply max vs)))
        min (let [vs (filter number? (map ev args))] (when (seq vs) (apply min vs)))
        count (let [x (ev (first args))] (if (or (coll? x) (string? x)) (count x) 0))
        nil? (nil? (ev (first args)))
        some? (some? (ev (first args)))
        blank? (str/blank? (str (ev (first args))))
        str (apply str (map ev args))
        name (let [x (ev (first args))] (when (or (keyword? x) (symbol? x) (string? x)) (name x)))
        namespace (let [x (ev (first args))] (when (or (keyword? x) (symbol? x)) (namespace x)))
        includes? (let [a (ev (first args)) b (ev (second args))]
                    (and (some? a) (some? b) (str/includes? (str a) (str b))))
        starts-with? (let [a (ev (first args)) b (ev (second args))]
                       (and (some? a) (some? b) (str/starts-with? (str a) (str b))))
        ends-with? (let [a (ev (first args)) b (ev (second args))]
                     (and (some? a) (some? b) (str/ends-with? (str a) (str b))))
        contains? (let [c (ev (first args)) k (ev (second args))] (boolean (and (coll? c) (contains? c k))))
        get (get (ev (first args)) (ev (second args)) (when (nnext args) (ev (nth args 2))))
        first (first (ev (first args)))
        second (second (ev (first args)))
        isa? (isa? (or h default-hierarchy) (ev (first args)) (ev (second args)))
        vector (mapv ev args)
        hash-map (apply hash-map (map ev args))
        nil))))

(defn empty-wm
  "Creates an empty immutable EAV working memory with optional `clojure.core/make-hierarchy`."
  {:malli/schema [:function
                  [:=> [:cat] :map]
                  [:=> [:cat :any] :map]]
   :cost/ticks 5000}
  ([] (empty-wm default-hierarchy))
  ([hierarchy]
   {:facts #{}
    :eav {}
    :aev {}
    :ave {}
    :provenance {}
    :hierarchy (or hierarchy default-hierarchy)}))

(defn add-fact
  "Adds `[e a v]` to working memory `wm` with provenance descriptor `prov` (`{:rule :stratum}`)."
  {:malli/schema [:=> [:cat :map [:vector :any] [:maybe :map]] :map]
   :cost/ticks 10000}
  [wm [e a v :as fact] prov]
  (if (or (not= 3 (count fact))
          (nil? e) (nil? a) (nil? v)
          (= :seon.rule/nil e) (= :seon.rule/nil a) (= :seon.rule/nil v))
    wm
    (let [already? (contains? (:facts wm) fact)
          wm' (if already?
                wm
                (-> wm
                    (update :facts conj fact)
                    (update-in [:eav e a] (fnil conj #{}) v)
                    (update-in [:aev a e] (fnil conj #{}) v)
                    (update-in [:ave a v] (fnil conj #{}) e)))]
      (if prov
        (update-in wm' [:provenance fact] (fnil conj []) prov)
        wm'))))

(defn add-facts
  "Bulk-inserts a sequence or set of `[e a v]` triples into `wm` with provenance `prov`."
  {:malli/schema [:=> [:cat :map [:maybe [:or [:sequential :any] [:set :any]]] [:maybe :map]] :map]
   :cost/ticks 500000}
  [wm facts prov]
  (if (and (coll? facts) (not (map? facts)))
    (reduce (fn [w f]
              (if (and (vector? f) (= 3 (count f)) (some? (first f)) (some? (second f)) (some? (nth f 2)))
                (add-fact w f prov)
                w))
            wm
            facts)
    wm))

(defn wm-vals
  "Returns the set of values for `[e a]` in working memory `wm`."
  {:malli/schema [:=> [:cat :map :any :any] [:set :any]]
   :cost/ticks 1000}
  [wm e a]
  (get-in (:eav wm) [e a] #{}))

(defn wm-scalar
  "Resolves the winning scalar value for `[e a]` in `wm` by highest asserting rule `:stratum`."
  {:malli/schema [:=> [:cat :map :any :any] :any]
   :cost/ticks 5000}
  [wm e a]
  (let [vs (get-in (:eav wm) [e a])]
    (when (seq vs)
      (if (= 1 (count vs))
        (first vs)
        (let [prov (:provenance wm)
              score-v (fn [v]
                        (let [ps (get prov [e a v])]
                          (if (seq ps)
                            (apply max (map #(long (or (:stratum %) 0)) ps))
                            0)))]
          (first (sort-by (fn [v] [(- (score-v v)) (pr-str v)]) vs)))))))

(defn wm-entities-with
  "Returns all entity keys in `wm` that hold attribute `a`."
  {:malli/schema [:=> [:cat :map :any] [:vector :any]]
   :cost/ticks 5000}
  [wm a]
  (vec (keys (get-in wm [:aev a]))))

(defn- match-wm-triple [wm env [pe pa pv]]
  (let [re (resolve-term env pe)
        ra (resolve-term env pa)
        rv (resolve-term env pv)
        eb? (not= re ::unbound)
        ab? (not= ra ::unbound)
        vb? (not= rv ::unbound)
        facts (:facts wm)
        eav (:eav wm)
        ave (:ave wm)
        aev (:aev wm)]
    (cond
      (and eb? ab? vb?)
      (if (and (set? facts) (contains? facts [re ra rv])) [env] [])

      (and eb? ab?)
      (for [v (when (map? eav) (let [m (get eav re)] (when (map? m) (get m ra))))
            :let [env' (unify-term env pv v)]
            :when env']
        env')

      (and ab? vb?)
      (for [e (when (map? ave) (let [m (get ave ra)] (when (map? m) (get m rv))))
            :let [env' (unify-term env pe e)]
            :when env']
        env')

      eb?
      (for [[a vs] (when (map? eav) (let [m (get eav re)] (when (map? m) m)))
            :let [env1 (unify-term env pa a)] :when env1
            v (when (coll? vs) vs)
            :let [env2 (unify-term env1 pv v)] :when env2]
        env2)

      ab?
      (for [[e vs] (when (map? aev) (let [m (get aev ra)] (when (map? m) m)))
            :let [env1 (unify-term env pe e)] :when env1
            v (when (coll? vs) vs)
            :let [env2 (unify-term env1 pv v)] :when env2]
        env2)

      :else
      (for [f (when (set? facts) facts)
            :when (and (vector? f) (= 3 (count f)))
            :let [[e a v] f
                  env1 (unify-term env pe e)] :when env1
            :let [env2 (unify-term env1 pa a)] :when env2
            :let [env3 (unify-term env2 pv v)] :when env3]
        env3))))

(defn- match-db-triple [dbs env src pat]
  (let [db (when (map? dbs) (get dbs src (:branch dbs)))
        [pe pa pv ptx] (if (vector? pat) pat [nil nil nil nil])
        re (resolve-term env pe)
        ra (resolve-term env pa)
        rv (resolve-term env pv)
        eb? (and (not= re ::unbound) (integer? re))
        ab? (and (not= ra ::unbound) (keyword? ra))
        vb? (not= rv ::unbound)
        valid-db? (instance? datahike.db.interface.ISearch db)
        schema (when valid-db? (or (:schema db) (try (d/schema db) (catch Throwable _ nil))))]
    (if (or (not valid-db?) (not (vector? pat)) (< (count pat) 3) (and ab? (not (contains? schema ra))))
      []
      (let [datoms (cond
                     (and eb? ab?) (d/datoms db :eavt re ra)
                     eb?           (d/datoms db :eavt re)
                     (and ab? vb?) (if (or (get-in schema [ra :db/index]) (get-in schema [ra :db/unique]))
                                     (d/datoms db :avet ra rv)
                                     (filter #(= rv (:v %)) (d/datoms db :aevt ra)))
                     ab?           (d/datoms db :aevt ra)
                     :else         [])]
        (for [dt datoms
              :let [env1 (unify-term env pe (:e dt))] :when env1
              :let [env2 (unify-term env1 pa (:a dt))] :when env2
              :let [env3 (unify-term env2 pv (:v dt))] :when env3
              :let [env4 (if ptx (unify-term env3 ptx (:tx dt)) env3)] :when env4]
          env4)))))

(defn instantiate-template
  "Instantiates an `:assert` template `[e a v]` using logic-variable bindings in `env`."
  {:malli/schema [:=> [:cat :map :any :any] :any]
   :cost/ticks 50000}
  [env h tmpl]
  (cond
    (lvar? tmpl) (get env tmpl)
    (seq? tmpl)  (eval-expr env h tmpl)
    (vector? tmpl) (mapv #(instantiate-template env h %) tmpl)
    (map? tmpl) (reduce-kv (fn [m k v] (assoc m (instantiate-template env h k) (instantiate-template env h v))) {} tmpl)
    :else tmpl))

(defn- rule-applies-to-target? [rule target]
  (let [rt (:seon.rule/target rule :any)]
    (or (nil? target) (= rt :any) (= rt target))))

(defn diff-sessions
  "Compares two rule session results (`control-res` vs `test-res`) for evidence-based A/B rule analysis:
   returns added/removed facts and changed rule firing counts (accepts `:runs` as map or vector)."
  {:malli/schema [:=> [:cat :map :map] :map]
   :cost/ticks 2000000}
  [control-res test-res]
  (let [f1 (or (get-in control-res [:wm :facts]) #{})
        f2 (or (get-in test-res [:wm :facts]) #{})
        added (vec (sort-by pr-str (set/difference f2 f1)))
        removed (vec (sort-by pr-str (set/difference f1 f2)))
        to-map (fn [rs] (cond (map? rs) rs
                              (sequential? rs) (into {} (map (juxt :rule-id identity)) rs)
                              :else {}))
        r1 (to-map (:runs control-res))
        r2 (to-map (:runs test-res))
        all-rids (sort (set/union (set (keys r1)) (set (keys r2))))
        rule-diffs (vec (keep (fn [rid]
                                (let [a (:fired (get r1 rid) 0)
                                      b (:fired (get r2 rid) 0)]
                                  (when (not= a b)
                                    {:rule rid :before a :after b})))
                              all-rids))]
    {:added-count (count added)
     :removed-count (count removed)
     :added added
     :removed removed
     :rule-diffs rule-diffs}))

(defn- match-db-count [dbs env src pat out-var]
  (let [db (when (map? dbs) (get dbs src (:branch dbs)))
        valid-db? (instance? datahike.db.interface.ISearch db)]
    (if (and valid-db? (vector? pat) (>= (count pat) 2))
      (let [[pe pa pv] pat
            re (resolve-term env pe)
            ra (resolve-term env pa)
            rv (resolve-term env pv)
            q [(when (integer? re) re)
               (when (keyword? ra) ra)
               (when (not= rv ::unbound) rv)]
            n (sq/count-range db q)]
        (if-let [env' (unify-term env out-var n)] [env'] []))
      [])))

(defn- match-db-slice [dbs env src pat opts]
  (let [db (when (map? dbs) (get dbs src (:branch dbs)))
        valid-db? (instance? datahike.db.interface.ISearch db)]
    (if (and valid-db? (vector? pat) (>= (count pat) 3))
      (let [[pe pa pv ptx] pat
            re (resolve-term env pe)
            ra (resolve-term env pa)
            rv (resolve-term env pv)
            q [(when (integer? re) re)
               (when (keyword? ra) ra)
               (when (not= rv ::unbound) rv)]
            ds (sq/slice-datoms db q (if (map? opts) opts {}))]
        (for [dt ds
              :let [env1 (unify-term env pe (:e dt))] :when env1
              :let [env2 (unify-term env1 pa (:a dt))] :when env2
              :let [env3 (unify-term env2 pv (:v dt))] :when env3
              :let [env4 (if ptx (unify-term env3 ptx (:tx dt)) env3)] :when env4]
          env4))
      [])))

(defn- match-facet [dbs env term sch-pat]
  (let [v (resolve-term env term)
        db (when (map? dbs) (or (:branch dbs) (:main dbs)))
        ent (cond
              (map? v) v
              (and (integer? v) (instance? datahike.db.interface.ISearch db))
              (try (dissoc (d/pull db (quote [*]) v) :db/id) (catch Throwable _ nil))
              :else nil)]
    (if (and (map? ent) (seq ent))
      (let [ix (schema/entity-index db)
            fs (six/facets-of ix ent)]
        (keep (fn [{:keys [schema]}] (unify-term env sch-pat schema)) fs))
      [])))

(defonce ^:private ^:seon.flow/edge rules-load-cache
  (store/lru-cache 16))

(defn rules-db-sig
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :any]}
  [db]
  (when (and db (contains? (:schema db) :seon.rule/id))
    (let [sig (fn [a] (if (contains? (:schema db) a)
                        (reduce (fn [[n mx] dt] [(inc n) (max mx (long (:tx dt 0)))]) [0 0] (d/datoms db :aevt a))
                        [0 0]))]
      [(sig :seon.rule/id) (sig :seon.rule/when) (sig :seon.rule/assert) (sig :seon.rule/enabled?)])))

(defn- scan-stored-rules [db]
  (let [ids (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.rule/id))
        pull-a (fn [a] (if (contains? (:schema db) a)
                         (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt a))
                         {}))
        docs (pull-a :seon.rule/doc)
        cats (pull-a :seon.rule/category)
        strs (pull-a :seon.rule/stratum)
        enss (pull-a :seon.rule/enabled?)
        tgts (pull-a :seon.rule/target)
        whns (pull-a :seon.rule/when)
        asts (pull-a :seon.rule/assert)]
    (reduce-kv (fn [acc e rid]
                 (assoc acc rid
                        (cond-> {:seon.rule/id rid}
                          (contains? docs e) (assoc :seon.rule/doc (get docs e))
                          (contains? cats e) (assoc :seon.rule/category (get cats e))
                          (contains? strs e) (assoc :seon.rule/stratum (get strs e))
                          (contains? enss e) (assoc :seon.rule/enabled? (get enss e))
                          (contains? tgts e) (assoc :seon.rule/target (get tgts e))
                          (contains? whns e) (assoc :seon.rule/when (get whns e))
                          (contains? asts e) (assoc :seon.rule/assert (get asts e)))))
               {} ids)))

(defn load-rules
"Reads all `:seon.rule/*` entities from `db` (tracked by `datahike.reads`), merged over optional `fallback-rules` by `:seon.rule/id`."
{:malli/schema [:function
                [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]
                [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector :map]]] [:vector :map]]]
 :cost/ticks 2000000}
([db] (load-rules db nil))
([db fallback-rules]
 (let [compute (fn []
                 (let [base (into {} (map (juxt :seon.rule/id identity)) (or fallback-rules []))
                       stored (if (and db (contains? (:schema db) :seon.rule/id))
                                (scan-stored-rules db)
                                {})]
                   (->> (vals (merge base stored))
                        (sort-by (juxt #(long (or (:seon.rule/stratum %) 50)) #(str (:seon.rule/id %))))
                        vec)))
       rsig (when (and db (not (instance? datahike.db.AsOfDB db)) (not (instance? datahike.db.HistoricalDB db)))
              [(get-in db [:config :store :id]) (get-in db [:config :branch] :main) (rules-db-sig db) (hash fallback-rules)])]
   (if rsig
     (store/lru-get! rules-load-cache 16 rsig compute)
     (compute)))))

(def default-datalog-rules
  "Shared recursive Datahike Datalog rules passed as `%` to native `:seon.rule/query` evaluations."
  '[[(calls* ?a ?b)        [?a :seon.code.var/calls ?b]]
    [(calls* ?a ?c)        [?a :seon.code.var/calls ?b] (calls* ?b ?c)]
    [(needs* ?t1 ?t2)      [?t1 :seon.task/needs ?tid2] [?t2 :seon.task/id ?tid2]]
    [(needs* ?t1 ?t2)      [?t1 :seon.task/needs ?t2] [(integer? ?t2)] [?t2 :seon.task/id _]]
    [(needs* ?t1 ?t3)      [?t1 :seon.task/needs ?tid2] [?t2 :seon.task/id ?tid2] (needs* ?t2 ?t3)]
    [(needs* ?t1 ?t3)      [?t1 :seon.task/needs ?t2] [(integer? ?t2)] [?t2 :seon.task/id _] (needs* ?t2 ?t3)]
    [(compatible-fn ?s ?f) [?f :seon.code.var/input ?s]
                           (not [?f :seon.code.var/private? true])
                           (not [?f :seon.code.var/test? true])]
    [(render-fn ?s ?f)     [?f :seon.code.var/input ?s]
                           [?f :seon.code.var/output ?u]
                           [?u :seon.schema/key :seon.ui/hiccup]]
    [(schema-doc ?s ?d)    [?d :seon.code.doc/references ?s]]
    [(var-doc ?v ?d)       [?d :seon.code.doc/references ?v]]])

(defn entity-facets
  "Returns vector of Malli schema keywords satisfied by entity `e` (eid or map) on `db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:vector :qualified-keyword]]
   :cost/ticks 500000}
  [db e]
  (let [ent (cond
              (map? e) e
              (and (integer? e) db) (dissoc (d/pull db '[*] e) :db/id)
              :else nil)]
    (if (and (map? ent) (seq ent))
      (mapv :schema (six/facets-of (schema/entity-index db) ent))
      [])))

(defn edited-form-eids
  "Returns vector of `:seon.code.form/*` EIDs whose `:seon.code.form/hash` differs on `branch-db` vs `main-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] [:vector :int]]
   :cost/ticks 5000000}
  [main-db branch-db]
  (if-not (and branch-db (contains? (:schema branch-db) :seon.code.form/hash))
    []
    (if (or (nil? main-db) (= main-db branch-db))
      (do (first (d/datoms branch-db :aevt :seon.code.form/hash)) [])
      (let [mh (if (contains? (:schema main-db) :seon.code.form/hash)
                 (reduce (fn [acc dt] (assoc acc (:e dt) (:v dt)))
                         {} (d/datoms main-db :aevt :seon.code.form/hash))
                 {})]
        (vec (keep (fn [dt] (when (not= (:v dt) (get mh (:e dt))) (:e dt)))
                   (d/datoms branch-db :aevt :seon.code.form/hash)))))))

(def ^:private query-section-keys #{:find :keys :syms :strs :with :in :where})

(defn parse-query
  "Normalizes a vector or map Datahike Datalog query into `{:find :keys :in :where}`."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 50000}
  [q]
  (cond
    (map? q) (select-keys q [:find :keys :syms :strs :with :in :where])
    (sequential? q)
    (loop [xs q, cur nil, acc {}]
      (if (empty? xs)
        acc
        (let [x (first xs)]
          (if (contains? query-section-keys x)
            (recur (rest xs) x (assoc acc x []))
            (recur (rest xs) cur (if cur (update acc cur (fnil conj []) x) acc))))))
    :else {}))

(defn rule-wm-read-attrs
  "Extracts the set of `$wm` attributes read by `rule`'s `:seon.rule/query` (for O(|Delta|) incremental skipping)."
  {:malli/schema [:=> [:cat :map] [:set :keyword]]
   :cost/ticks 50000}
  [rule]
  (let [pq (parse-query (:seon.rule/query rule))
        ins (set (:in pq))]
    (if-not (contains? ins '$wm)
      #{}
      (reduce (fn [acc node]
                (cond
                  (and (vector? node) (>= (count node) 3) (= '$wm (first node)))
                  (let [a (nth node 2)]
                    (conj acc (if (keyword? a) a :*)))

                  (and (seq? node) (some #(= '$wm %) node))
                  (let [kws (filter keyword? node)]
                    (if (seq kws) (into acc kws) (conj acc :*)))

                  :else acc))
              #{}
              (tree-seq coll? seq (:where pq))))))

(defn rule-db-keys
  "Returns the set of DB keys (`:main`, `:branch`, `:code`, `:task`) read by `rule`'s `:in` clause."
  {:malli/schema [:=> [:cat :map] [:set :keyword]]
   :cost/ticks 10000}
  [rule]
  (let [ins (or (:in (parse-query (:seon.rule/query rule))) ['$])]
    (reduce (fn [acc s]
              (case s
                $main   (conj acc :main)
                $branch (conj acc :branch)
                $code   (conj acc :code)
                $task   (conj acc :task)
                ($ %)   (conj acc :branch)
                ?agent-eid (conj acc :main :branch)
                acc))
            #{}
            ins)))

(defn rule-uses-agent?
  "True when `rule`'s `:in` clause binds `?agent` or `?agent-eid`."
  {:malli/schema [:=> [:cat :map] :boolean]
   :cost/ticks 5000}
  [rule]
  (let [ins (set (:in (parse-query (:seon.rule/query rule))))]
    (boolean (or (contains? ins '?agent) (contains? ins '?agent-eid)))))

(defn subst-template
  "Recursively substitutes `?lvar` bindings from `env` into a triple or projection map template."
  {:malli/schema [:=> [:cat :map :any] :any]
   :cost/ticks 50000}
  [env tmpl]
  (cond
    (lvar? tmpl) (get env tmpl)
    (vector? tmpl) (mapv #(subst-template env %) tmpl)
    (map? tmpl) (reduce-kv (fn [m k v]
                             (let [k' (subst-template env k)
                                   v' (subst-template env v)]
                               (if (or (nil? k') (nil? v') (= :seon.rule/nil v'))
                                 m
                                 (assoc m k' v'))))
                           {}
                           tmpl)
    :else tmpl))

(defn- resolve-agent-eid [dbs agent-id]
  (when agent-id
    (or (when-let [db (:branch dbs)]
          (when (contains? (:schema db) :seon.agent/id)
            (:e (first (d/datoms db :avet :seon.agent/id agent-id)))))
        (when-let [db (:main dbs)]
          (when (contains? (:schema db) :seon.agent/id)
            (:e (first (d/datoms db :avet :seon.agent/id agent-id))))))))

(defn- resolve-query-inputs [ins wm dbs agent-id]
  (let [eff-db (or (:branch dbs) (:code dbs) (:main dbs))
        resolve-one (fn [s]
                      (case s
                        $          eff-db
                        $main      (or (:main dbs) eff-db)
                        $branch    (or (:branch dbs) (:main dbs) eff-db)
                        $code      (or (:code dbs) (:branch dbs) (:main dbs))
                        $task      (or (:task dbs) (:branch dbs) (:main dbs))
                        $wm        (or (:facts wm) #{})
                        ?agent     agent-id
                        ?agent-eid (resolve-agent-eid dbs agent-id)
                        ?hierarchy (or (:hierarchy wm) default-hierarchy)
                        %          default-datalog-rules
                        ::unknown))
        vals (mapv resolve-one ins)]
    (when (every? #(and (some? %) (not= ::unknown %)) vals)
      vals)))

(defn- inject-default-db-into-query [q]
  (cond
    (map? q) (update q :in #(into ['$ ] %))
    (sequential? q)
    (let [v (vec q)
          idx (.indexOf v :in)]
      (if (neg? idx)
        v
        (vec (concat (subvec v 0 (inc idx)) ['$ ] (subvec v (inc idx))))))
    :else q))

(defn- find-var-symbol [form]
  (cond
    (lvar? form) form
    (and (seq? form) (>= (count form) 2) (lvar? (second form)))
    (symbol (str "?" (name (first form)) "-" (subs (name (second form)) 1)))
    :else nil))

(defn- rows->envs [pq raw]
  (let [ks (:keys pq)
        finds (:find pq)]
    (cond
      (seq ks)
      (mapv (fn [m]
              (reduce-kv (fn [acc k v] (assoc acc (symbol (str "?" (name k))) v)) {} m))
            raw)

      (and (= 2 (count finds)) (= '. (second finds)) (lvar? (first finds)))
      (if (some? raw) [{(first finds) raw}] [])

      (and (= 1 (count finds)) (vector? (first finds)) (= '... (second (first finds))) (lvar? (ffirst finds)))
      (let [v (ffirst finds)] (mapv (fn [x] {v x}) raw))

      :else
      (let [vars (mapv find-var-symbol finds)]
        (mapv (fn [row] (zipmap vars row)) raw)))))

(defn- eval-clause [wm dbs envs clause]
  (if-not (and (vector? clause) (seq clause))
    envs
    (let [hd (first clause)
          raw-h (:hierarchy wm)
          h (if (and (map? raw-h) (map? (:ancestors raw-h))) raw-h default-hierarchy)]
      (cond
        (= hd :db)
        (let [[src pat] (if (keyword? (second clause)) [(second clause) (nth clause 2 nil)] [:branch (second clause)])]
          (if (and (vector? pat) (>= (count pat) 3))
            (mapcat #(match-db-triple dbs % src pat) envs)
            []))

        (= hd :db-count)
        (let [[src pat out-var] (if (keyword? (second clause))
                                  [(second clause) (nth clause 2 nil) (nth clause 3 nil)]
                                  [:branch (second clause) (nth clause 2 nil)])]
          (if (and (vector? pat) (some? out-var))
            (mapcat #(match-db-count dbs % src pat out-var) envs)
            []))

        (= hd :db-slice)
        (let [[src pat opts] (if (keyword? (second clause))
                               [(second clause) (nth clause 2 nil) (nth clause 3 nil)]
                               [:branch (second clause) (nth clause 2 nil)])]
          (if (and (vector? pat) (>= (count pat) 3))
            (mapcat #(match-db-slice dbs % src pat opts) envs)
            []))

        (= hd :facet)
        (if (< (count clause) 3)
          []
          (let [[term sch-pat] (rest clause)]
            (mapcat #(match-facet dbs % term sch-pat) envs)))

        (= hd :guard)
        (let [expr (second clause)]
          (filter #(boolean (eval-expr % h expr)) envs))

        (= hd :bind)
        (if (< (count clause) 3)
          []
          (let [[var-sym expr] (rest clause)]
            (keep (fn [env]
                    (let [v (eval-expr env h expr)]
                      (unify-term env var-sym v)))
                  envs)))

        (= hd :not)
        (let [sub (vec (rest clause))]
          (filter #(empty? (eval-clauses wm dbs [%] sub)) envs))

        (= hd :optional)
        (let [sub (vec (rest clause))]
          (mapcat (fn [env]
                    (let [res (eval-clauses wm dbs [env] sub)]
                      (if (seq res) res [env])))
                  envs))

        (= hd :isa)
        (if (< (count clause) 3)
          []
          (let [[term anc] (rest clause)]
            (if (or (keyword? anc) (symbol? anc) (class? anc))
              (filter (fn [env]
                        (let [v (resolve-term env term)]
                          (and (not= v ::unbound)
                               (or (keyword? v) (symbol? v) (class? v))
                               (isa? h v anc))))
                      envs)
              [])))

        (= hd :count)
        (if (< (count clause) 3)
          []
          (let [[out-var & sub] (rest clause)]
            (keep (fn [env]
                    (let [n (count (eval-clauses wm dbs [env] (vec sub)))]
                      (unify-term env out-var n)))
                  envs)))

        (= hd :max)
        (if (< (count clause) 4)
          []
          (let [[out-var val-var & sub] (rest clause)]
            (keep (fn [env]
                    (let [ms (eval-clauses wm dbs [env] (vec sub))
                          vs (filter number? (map #(get % val-var) ms))]
                      (when (seq vs)
                        (unify-term env out-var (apply max vs)))))
                  envs)))

        (= 3 (count clause))
        (mapcat #(match-wm-triple wm % clause) envs)

        :else
        []))))

(defn eval-clauses
  "Evaluates a vector of rule `:when` clauses against working memory `wm` and `dbs` (`{:main :branch}`)."
  {:malli/schema [:=> [:cat :map :map [:vector :map] [:vector [:vector :any]]] [:vector :map]]
   :cost/ticks 5000000}
  [wm dbs envs clauses]
  (reduce (fn [acc cl]
            (if (empty? acc) (reduced []) (vec (eval-clause wm dbs acc cl))))
          envs
          clauses))

(defn step-rules
  "Incrementally evaluates `rules` in stratum order from `base-wm` against `dbs` (`{:main :branch}`),
   reusing prior per-rule results in `prev-state` whenever neither their Datahike `reads-idx` (against
   `tx-datoms`) nor their upstream working-memory read attributes moved.
   Returns `{:wm :runs :base-facts :recomputed :skipped :target}`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :map] :map :map [:vector :map] [:maybe :any]] :map]
                  [:=> [:cat [:maybe :map] :map :map [:vector :map] [:maybe :any] [:maybe :keyword]] :map]]
   :cost/ticks 15000000}
  ([prev-state base-wm dbs rules tx-datoms]
   (step-rules prev-state base-wm dbs rules tx-datoms nil))
  ([prev-state base-wm dbs rules tx-datoms target]
   (let [active (filter #(and (:seon.rule/enabled? % true) (rule-applies-to-target? % target)) rules)
         by-stratum (group-by #(long (:seon.rule/stratum % 50)) active)
         strata (sort (keys by-stratum))
         prev-runs (or (:runs prev-state) {})
         same-target? (= target (:target prev-state))
         dirty-attrs (volatile! (if (or (nil? prev-state)
                                        (not same-target?)
                                        (not= (:facts base-wm) (:base-facts prev-state)))
                                  #{:*}
                                  #{}))
         recomputed (volatile! 0)
         skipped (volatile! 0)
         final
         (reduce
          (fn [{:keys [wm runs]} stratum]
            (let [s-rules (sort-by #(str (:seon.rule/id %)) (get by-stratum stratum))]
              (reduce
               (fn [{:keys [wm runs]} r]
                 (let [rid (:seon.rule/id r)
                       prev (get prev-runs rid)
                       wm-deps (rule-wm-read-attrs r)
                       same-rule? (and prev (= (:rule-def prev) r))
                       wm-clean? (and same-rule?
                                      (not (contains? @dirty-attrs :*))
                                      (not (contains? wm-deps :*))
                                      (not (some @dirty-attrs wm-deps)))
                       db-clean? (and wm-clean?
                                      (some? tx-datoms)
                                      (not (reads/reads-touched? (:reads-idx prev) tx-datoms)))]
                   (if db-clean?
                     (do
                       (vswap! skipped inc)
                       {:wm (add-facts wm (:asserted-facts prev) {:rule rid :stratum stratum})
                        :runs (assoc runs rid prev)})
                     (do
                       (vswap! recomputed inc)
                       (let [t0 (System/nanoTime)
                             [envs rs] (reads/with-reads
                                        #(eval-clauses wm dbs [{}] (:seon.rule/when r)))
                             h (:hierarchy wm)
                             new-facts (into #{}
                                             (for [env envs
                                                   tmpl (:seon.rule/assert r)
                                                   :let [f (instantiate-template env h tmpl)]
                                                   :when (and (vector? f) (= 3 (count f))
                                                              (some? (first f)) (some? (second f)) (some? (nth f 2)))]
                                               f))
                             old-facts (or (:asserted-facts prev) #{})
                             changed (set/union (set/difference new-facts old-facts)
                                                (set/difference old-facts new-facts))
                             _ (doseq [[_ a _] changed] (vswap! dirty-attrs conj a))
                             ms (/ (- (System/nanoTime) t0) 1e6)]
                         {:wm (add-facts wm new-facts {:rule rid :stratum stratum})
                          :runs (assoc runs rid
                                       {:rule-id rid
                                        :rule-def r
                                        :stratum stratum
                                        :category (:seon.rule/category r :present)
                                        :fired (count envs)
                                        :asserted-count (count new-facts)
                                        :asserted-facts new-facts
                                        :reads-idx (reads/reads-index rs)
                                        :reads-count (count rs)
                                        :ms (/ (Math/round (* ms 100.0)) 100.0)})})))))
               {:wm wm :runs runs}
               s-rules)))
          {:wm base-wm :runs {}}
          strata)]
     (assoc final
            :base-facts (:facts base-wm)
            :target target
            :recomputed @recomputed
            :skipped @skipped))))

(defn fire-rules
  "Cold stratified rule evaluation convenience wrapper over `step-rules`."
  {:malli/schema [:function
                  [:=> [:cat :map :map [:vector :map]] :map]
                  [:=> [:cat :map :map [:vector :map] [:maybe :keyword]] :map]]
   :cost/ticks 15000000}
  ([base-wm dbs rules]
   (step-rules nil base-wm dbs rules nil nil))
  ([base-wm dbs rules target]
   (step-rules nil base-wm dbs rules nil target)))