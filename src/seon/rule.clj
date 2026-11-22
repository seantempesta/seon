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

(defonce ^:private ^:seon.flow/edge rules-load-cache
  (store/lru-cache 16))

(defn rules-db-sig
  "Computes a signature over all mutable `:seon.rule/*` attributes for cache invalidation."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :any]
   :cost/ticks 500000}
  [db]
  (when (and db (contains? (:schema db) :seon.rule/id))
    (let [sig (fn [a]
                (if (contains? (:schema db) a)
                  (reduce (fn [[n mx] dt] [(inc n) (max mx (long (:tx dt 0)))]) [0 0] (d/datoms db :aevt a))
                  [0 0]))]
      [(sig :seon.rule/id)
       (sig :seon.rule/query)
       (sig :seon.rule/when)
       (sig :seon.rule/assert)
       (sig :seon.rule/enabled?)
       (sig :seon.rule/stratum)
       (sig :seon.rule/target)])))

(defn- scan-stored-rules [db]
  (let [ids (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.rule/id))
        pull-a (fn [a]
                 (if (contains? (:schema db) a)
                   (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt a))
                   {}))
        attrs [:seon.rule/doc :seon.rule/category :seon.rule/stratum :seon.rule/enabled?
               :seon.rule/target :seon.rule/query :seon.rule/when :seon.rule/assert]
        by-a (into {} (map (fn [a] [a (pull-a a)])) attrs)]
    (reduce-kv (fn [acc e rid]
                 (assoc acc rid
                        (reduce (fn [m a]
                                  (let [am (get by-a a)]
                                    (if (contains? am e) (assoc m a (get am e)) m)))
                                {:seon.rule/id rid}
                                attrs)))
               {}
               ids)))

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

(defn eval-rule-query
  "Executes `rule`'s native Datahike `:seon.rule/query` via `d/q` and returns binding env maps."
  {:malli/schema [:=> [:cat :map :map [:maybe :keyword] :map] [:vector :map]]
   :cost/ticks 10000000}
  [wm dbs agent-id rule]
  (let [q (:seon.rule/query rule)]
    (if-not (or (vector? q) (map? q))
      []
      (let [pq (parse-query q)
            raw-ins (or (seq (:in pq)) ['$])
            has-rules? (some #(= '% %) raw-ins)
            has-default? (some #(= '$ %) raw-ins)
            [eff-q eff-ins] (if (and has-rules? (not has-default?))
                              [(inject-default-db-into-query q) (into ['$ ] raw-ins)]
                              [q (vec raw-ins)])]
        (if-let [args (resolve-query-inputs eff-ins wm dbs agent-id)]
          (rows->envs pq (apply d/q eff-q args))
          [])))))

(defn- instantiate-asserts [envs asserts rid stratum]
  (let [facts (volatile! #{})
        projs (volatile! [])]
    (doseq [env envs
            tmpl asserts]
      (cond
        (vector? tmpl)
        (let [f (subst-template env tmpl)]
          (when (and (= 3 (count f))
                     (every? #(and (some? %) (not= :seon.rule/nil %)) f))
            (vswap! facts conj f)))

        (map? tmpl)
        (let [p (subst-template env tmpl)]
          (when (and (some? (:entity p)) (not= :seon.rule/nil (:entity p)))
            (vswap! projs conj (assoc p :rule rid :stratum stratum))))))
    {:facts @facts :projections @projs}))

(defn- normalize-step-args [a2 a3]
  (if (and (map? a2) (contains? a2 :facts))
    {:base-wm a2
     :dbs (if (map? a3) a3 {})
     :agent-id (or (wm-scalar a2 :viewer :ctx/viewer)
                   (first (for [[_ a v] (:facts a2) :when (= a :ctx/viewer)] v)))}
    {:base-wm (if (and (map? a3) (:base-wm a3)) (:base-wm a3) (empty-wm))
     :dbs (if (map? a2) a2 {})
     :agent-id (if (map? a3) (:agent-id a3) a3)}))

(defn- relevant-tx-datoms [tx-datoms db-keys]
  (cond
    (nil? tx-datoms) nil
    (map? tx-datoms) (vec (mapcat #(get tx-datoms %) db-keys))
    :else tx-datoms))

(defn- initial-dirty-attrs [prev-state base-wm active target]
  (if (or (nil? prev-state) (not= target (:target prev-state)))
    #{:*}
    (let [old-f (or (:base-facts prev-state) #{})
          new-f (or (:facts base-wm) #{})
          base-diff (set/union (set/difference new-f old-f) (set/difference old-f new-f))
          active-ids (into #{} (map :seon.rule/id) active)
          removed-facts (mapcat (fn [[rid run]]
                                  (when-not (contains? active-ids rid) (:asserted-facts run)))
                                (:runs prev-state))]
      (into #{} (map second) (concat base-diff removed-facts)))))

(defn- step-one-rule [state r stratum dbs agent-id prev-state tx-datoms dirty-attrs recomputed skipped]
  (let [rid (:seon.rule/id r)
        prev (get-in prev-state [:runs rid])
        wm-deps (rule-wm-read-attrs r)
        db-keys (rule-db-keys r)
        uses-ag? (rule-uses-agent? r)
        same-rule? (and prev (= (:rule-def prev) r))
        ag-clean? (or (not uses-ag?) (= agent-id (:agent-id prev-state)))
        wm-clean? (and same-rule? ag-clean?
                       (not (contains? @dirty-attrs :*))
                       (not (contains? wm-deps :*))
                       (not (some @dirty-attrs wm-deps)))
        rel-dt (when wm-clean? (relevant-tx-datoms tx-datoms db-keys))
        db-clean? (and wm-clean?
                       (or (empty? db-keys)
                           (and (some? rel-dt)
                                (not (reads/reads-touched? (:reads-idx prev) rel-dt)))))]
    (if db-clean?
      (do
        (vswap! skipped inc)
        (when-let [outer di/*reads*]
          (when (seq (:reads prev)) (swap! outer into (:reads prev))))
        {:wm (add-facts (:wm state) (:asserted-facts prev) {:rule rid :stratum stratum})
         :projections (into (:projections state) (:projections prev))
         :runs (assoc (:runs state) rid (assoc prev :skipped? true))})
      (let [t0 (System/nanoTime)
            [envs rs] (reads/with-reads #(eval-rule-query (:wm state) dbs agent-id r))
            {:keys [facts projections]} (instantiate-asserts envs (:seon.rule/assert r) rid stratum)
            old-facts (or (:asserted-facts prev) #{})
            changed (set/union (set/difference facts old-facts) (set/difference old-facts facts))
            ridx (reads/reads-index rs)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (vswap! recomputed inc)
        (doseq [[_ a _] changed] (vswap! dirty-attrs conj a))
        {:wm (add-facts (:wm state) facts {:rule rid :stratum stratum})
         :projections (into (:projections state) projections)
         :runs (assoc (:runs state) rid
                      {:rule-id rid
                       :rule-def r
                       :stratum stratum
                       :category (:seon.rule/category r :present)
                       :fired (count envs)
                       :asserted-count (count facts)
                       :asserted-facts facts
                       :projections projections
                       :reads rs
                       :reads-idx ridx
                       :reads-by-db (into {} (map (fn [k] [k ridx])) db-keys)
                       :db-keys db-keys
                       :uses-agent? uses-ag?
                       :reads-count (count rs)
                       :skipped? false
                       :ms (/ (Math/round (* ms 100.0)) 100.0)})}))))

(defn step-rules
  "Incrementally evaluates `rules` in stratum order via native Datahike `d/q` inside `datahike.reads/with-reads`.
   Accepts either `(prev-state base-wm dbs rules tx-datoms [target])` or `(prev-state dbs agent-id rules tx-datoms-by-db [target])`.
   Skips untouched rules in O(|Delta|) while forwarding reads from both executed and skipped rules into outer `di/*reads*`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :map] :map :any [:vector :map] [:maybe :any]] :map]
                  [:=> [:cat [:maybe :map] :map :any [:vector :map] [:maybe :any] [:maybe :keyword]] :map]]
   :cost/ticks 20000000}
  ([prev-state a2 a3 rules tx-datoms]
   (step-rules prev-state a2 a3 rules tx-datoms nil))
  ([prev-state a2 a3 rules tx-datoms target]
   (let [{:keys [base-wm dbs agent-id]} (normalize-step-args a2 a3)
         active (filterv #(and (:seon.rule/enabled? % true) (rule-applies-to-target? % target)) rules)
         by-stratum (group-by #(long (:seon.rule/stratum % 50)) active)
         dirty-attrs (volatile! (initial-dirty-attrs prev-state base-wm active target))
         recomputed (volatile! 0)
         skipped (volatile! 0)
         final (reduce (fn [st stratum]
                         (reduce (fn [st2 r]
                                   (step-one-rule st2 r stratum dbs agent-id prev-state tx-datoms dirty-attrs recomputed skipped))
                                 st
                                 (sort-by #(str (:seon.rule/id %)) (get by-stratum stratum))))
                       {:wm base-wm :projections [] :runs {}}
                       (sort (keys by-stratum)))]
     (assoc final
            :base-facts (:facts base-wm)
            :agent-id agent-id
            :target target
            :recomputed @recomputed
            :skipped @skipped))))

(defn fire-rules
  "Cold stratified rule evaluation convenience wrapper over `step-rules`."
  {:malli/schema [:function
                  [:=> [:cat :map :any [:vector :map]] :map]
                  [:=> [:cat :map :any [:vector :map] [:maybe :keyword]] :map]]
   :cost/ticks 20000000}
  ([a1 a2 rules] (step-rules nil a1 a2 rules nil nil))
  ([a1 a2 rules target] (step-rules nil a1 a2 rules nil target)))