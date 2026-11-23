(ns seon.rule
  "Native Datahike `d/q` + `datahike.reads` incremental stratified rule engine over `:seon.rule/*` entities."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store]
            [datahike.index.interface :as di]))
(defn- dsch [db] (or (:schema db) (when db (d/schema db)) {}))

(defn- db-req-empty? [db-req dbs]
  (when (seq db-req)
    (let [eff-db (or (:branch dbs) (:code dbs) (:main dbs))]
      (some (fn [[src a v]]
              (let [db (case src
                         $ eff-db
                         $main (or (:main dbs) eff-db)
                         $branch (or (:branch dbs) (:main dbs) eff-db)
                         $code (or (:code dbs) (:branch dbs) (:main dbs))
                         $task (or (:task dbs) (:branch dbs) (:main dbs))
                         nil)]
                (when-let [aspec (and db (get (dsch db) a))]
                  (let [use-avet? (and (not= v ::any)
                                       (or (:db/index aspec) (:db/unique aspec)
                                           (= :db.type/ref (:db/valueType aspec))))
                        empty? (binding [di/*reads* nil]
                                 (nil? (if use-avet?
                                         (first (d/datoms db :avet a v))
                                         (first (d/datoms db :aevt a)))))]
                    (when empty?
                      (if use-avet?
                        (first (d/datoms db :avet a v))
                        (first (d/datoms db :aevt a)))
                      true)))))
            db-req))))

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
  (let [dh (dsch db)]
    (when (and db (contains? dh :seon.rule/id))
      (let [sig (fn [a]
                  (if (contains? dh a)
                  (reduce (fn [[n mx] dt] [(inc n) (max mx (long (:tx dt 0)))]) [0 0] (d/datoms db :aevt a))
                  [0 0]))]
      [(sig :seon.rule/id)
       (sig :seon.rule/query)
       (sig :seon.rule/when)
       (sig :seon.rule/assert)
       (sig :seon.rule/enabled?)
       (sig :seon.rule/stratum)
       (sig :seon.rule/target)]))))

(defn- scan-stored-rules [db]
  (let [dh (dsch db)
        ev-map (fn [a] (reduce #(assoc %1 (:e %2) (:v %2)) {} (d/datoms db :aevt a)))
        ids (ev-map :seon.rule/id)
        pull-a #(if (contains? dh %) (ev-map %) {})
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
                       stored (if (and db (contains? (dsch db) :seon.rule/id))
                                (scan-stored-rules db)
                                {})]
                   (->> (vals (if (seq stored) stored base))
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
  (if-not (and branch-db (contains? (dsch branch-db) :seon.code.form/hash))
    []
    (if (or (nil? main-db) (= main-db branch-db))
      (do (first (d/datoms branch-db :aevt :seon.code.form/hash)) [])
      (let [mh (if (contains? (dsch main-db) :seon.code.form/hash)
                 (reduce #(assoc %1 (:e %2) (:v %2)) {} (d/datoms main-db :aevt :seon.code.form/hash))
                 {})]
        (vec (keep #(when (not= (:v %) (get mh (:e %))) (:e %))
                   (d/datoms branch-db :aevt :seon.code.form/hash)))))))

(defn parse-query
  "Normalizes a vector or map Datahike Datalog query into `{:find :keys :in :where}`."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 50000}
  [q]
  (let [secs #{:find :keys :syms :strs :with :in :where}]
    (cond
      (map? q) (select-keys q [:find :keys :syms :strs :with :in :where])
      (sequential? q)
      (loop [xs q, cur nil, acc {}]
        (if (empty? xs)
          acc
          (let [x (first xs)]
            (if (contains? secs x)
              (recur (rest xs) x (assoc acc x []))
              (recur (rest xs) cur (if cur (update acc cur (fnil conj []) x) acc))))))
      :else {})))

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

(defn- resolve-query-inputs [ins wm dbs agent-id]
  (let [eff-db (or (:branch dbs) (:code dbs) (:main dbs))
        ag-eid (delay
                 (when agent-id
                   (some (fn [db]
                           (when (and db (contains? (dsch db) :seon.agent/id))
                             (:e (first (d/datoms db :avet :seon.agent/id agent-id)))))
                         [(:branch dbs) (:main dbs)])))
        resolve-one (fn [s]
                      (case s
                        $          eff-db
                        $main      (or (:main dbs) eff-db)
                        $branch    (or (:branch dbs) (:main dbs) eff-db)
                        $code      (or (:code dbs) (:branch dbs) (:main dbs))
                        $task      (or (:task dbs) (:branch dbs) (:main dbs))
                        $wm        (or (:facts wm) #{})
                        ?agent     agent-id
                        ?agent-eid @ag-eid
                        ?hierarchy (or (:hierarchy wm) default-hierarchy)
                        %          default-datalog-rules
                        ::unknown))
        vals (mapv resolve-one ins)]
    (when (every? #(and (some? %) (not= ::unknown %)) vals)
      vals)))

(defn- rows->envs [pq raw]
  (let [ks (:keys pq)
        finds (:find pq)
        var-sym (fn [form]
                  (cond
                    (lvar? form) form
                    (and (seq? form) (>= (count form) 2) (lvar? (second form)))
                    (symbol (str "?" (name (first form)) "-" (subs (name (second form)) 1)))
                    :else nil))]
    (cond
      (seq ks)
      (mapv (fn [m] (reduce-kv (fn [acc k v] (assoc acc (symbol (str "?" (name k))) v)) {} m)) raw)

      (and (= 2 (count finds)) (= '. (second finds)) (lvar? (first finds)))
      (if (some? raw) [{(first finds) raw}] [])

      (and (= 1 (count finds)) (vector? (first finds)) (= '... (second (first finds))) (lvar? (ffirst finds)))
      (let [v (ffirst finds)] (mapv (fn [x] {v x}) raw))

      :else
      (let [vars (mapv var-sym finds)]
        (mapv (fn [row] (zipmap vars row)) raw)))))

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

(def ^:dynamic *base-rules-override*
  "Optional vector of `:seon.rule/*` maps bound during branch test execution so ephemeral `with-test-store`
   fixtures inherit the branch's live `:seon.rule/*` entities before merge writes `resources/seon/rules.edn` to disk."
  nil)

(defn- branch-passing-run [db dh tx]
  (when (and (dh :seon.test.run/branch) (dh :seon.test.run/pass?))
    (let [br (or (get-in db [:config :branch]) (get-in (:origin-db db) [:config :branch]) :main)
          be (:e (first (d/datoms db :avet :seon.test.run/branch br)))
          re (or be (when (not= br :main) (:e (first (d/datoms db :avet :seon.test.run/branch :main)))))]
      (when (and re (true? (:v (first (d/datoms db :eavt re :seon.test.run/pass?)))))
        (let [rtx (if be Long/MAX_VALUE (reduce max 0 (map #(long (:tx % 0)) (d/datoms db :eavt re))))]
          (when (>= rtx tx)
            {:failed (if (dh :seon.test.run/failed-digests)
                       (into #{} (map :v) (d/datoms db :eavt re :seon.test.run/failed-digests))
                       #{})}))))))

(defn verdict-latest-for-var?
  "True when no passing verdict exists for `var-eid` at `(>= (:tx pdt) tx)` and no passing branch run clears it."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :int :int] :boolean]
                  [:=> [:cat [:maybe :seon.host/db] :int :int [:maybe :string]] :boolean]]
   :cost/ticks 300000}
  ([db var-eid tx] (verdict-latest-for-var? db var-eid tx nil))
  ([db var-eid tx digest]
   (let [dh (dsch db)]
     (if-not (and db (dh :seon.test/var-ref))
       true
       (let [txl (long tx)
             prun (branch-passing-run db dh txl)
             fset (:failed prun)]
         (cond
           (and prun (empty? fset)) false
           (and prun (string? digest) (not (contains? fset digest))) false
           :else
           (let [res (reduce
                      (fn [acc dt]
                        (if-let [pdt (first (d/datoms db :eavt (:e dt) :seon.test/pass?))]
                          (let [ptx (long (:tx pdt 0))]
                            (cond
                              (and (true? (:v pdt)) (>= ptx txl)) (reduced ::cleared)
                              (and prun (nil? digest) (false? (:v pdt)) (= ptx txl))
                              (if-let [dg (:v (first (d/datoms db :eavt (:e dt) :seon.test/digest)))]
                                (conj acc dg)
                                acc)
                              :else acc))
                          acc))
                      (if (string? digest) #{digest} #{})
                      (d/datoms db :avet :seon.test/var-ref var-eid))]
             (and (not= ::cleared res)
                  (or (nil? prun) (boolean (some fset res)))))))))))

(defonce ^:private ^:seon.flow/edge query-meta-cache (store/lru-cache 256))

(defn- query-meta [q]
  (if-not (or (vector? q) (map? q))
    {:pq {} :ins [] :wm-attrs #{} :wm-req #{} :wm-const-req [] :wm-not-req [] :db-req [] :db-keys #{} :uses-agent? false :eff-q nil :eff-ins []}
    (store/lru-get! query-meta-cache 256 q
      (fn []
        (let [pq (parse-query q)
              ws (:where pq)
              raw-ins (vec (or (seq (:in pq)) ['$]))
              ins-set (set raw-ins)
              const-val? (fn [v] (not (and (symbol? v) (or (= '_ v) (.startsWith (name v) "?")))))
              wm-attrs (if-not (contains? ins-set '$wm)
                         #{}
                         (reduce (fn [acc node]
                                   (cond
                                     (and (vector? node) (>= (count node) 3) (= '$wm (first node)))
                                     (conj acc (if (keyword? (nth node 2)) (nth node 2) :*))
                                     (and (seq? node) (some #(= '$wm %) node))
                                     (let [kws (filter keyword? node)] (if (seq kws) (into acc kws) (conj acc :*)))
                                     :else acc))
                                 #{} (tree-seq coll? seq ws)))
              wm-req (into #{} (keep #(when (and (vector? %) (>= (count %) 3) (= '$wm (first %)) (keyword? (nth % 2))) (nth % 2))) ws)
              wm-const-req (vec (keep #(when (and (vector? %) (>= (count %) 4) (= '$wm (first %)) (keyword? (nth % 2)) (const-val? (nth % 3)))
                                         [(nth % 2) (nth % 3)]) ws))
              pos-by-e (reduce (fn [m cl]
                                 (if (and (vector? cl) (>= (count cl) 3) (= '$wm (first cl)) (lvar? (nth cl 1)) (keyword? (nth cl 2)))
                                   (update m (nth cl 1) (fnil conj []) (nth cl 2))
                                   m)) {} ws)
              wm-not-req (vec (keep (fn [cl]
                                      (when (and (seq? cl) (= 'not (first cl)) (= 2 (count cl)))
                                        (let [inner (second cl)]
                                          (when (and (vector? inner) (>= (count inner) 3) (= '$wm (first inner))
                                                     (lvar? (nth inner 1)) (keyword? (nth inner 2)))
                                            (when-let [a1 (first (get pos-by-e (nth inner 1)))]
                                              (let [v (when (>= (count inner) 4) (nth inner 3))]
                                                [a1 (nth inner 2) (if (and (>= (count inner) 4) (const-val? v)) v ::any)]))))))
                                    ws))
              db-req (vec (keep #(when (and (vector? %) (>= (count %) 3)
                                            (contains? #{'$ '$main '$branch '$code '$task} (first %))
                                            (keyword? (nth % 2)))
                                   (let [v (when (>= (count %) 4) (nth % 3))]
                                     [(first %) (nth % 2) (if (and (>= (count %) 4) (const-val? v)) v ::any)])) ws))
              db-keys (reduce (fn [acc s]
                                (case s $main (conj acc :main) $branch (conj acc :branch) $code (conj acc :code)
                                        $task (conj acc :task) ($ %) (conj acc :branch) ?agent-eid (conj acc :main :branch) acc))
                              #{} raw-ins)
              need-$? (and (contains? ins-set '%) (not (contains? ins-set '$)))
              eff-q (if-not need-$?
                      q
                      (cond (map? q) (update q :in #(into ['$ ] %))
                            (sequential? q) (let [v (vec q) idx (.indexOf v :in)]
                                              (if (neg? idx) v (vec (concat (subvec v 0 (inc idx)) ['$ ] (subvec v (inc idx))))))
                            :else q))]
          {:pq pq :ins raw-ins :wm-attrs wm-attrs :wm-req wm-req :wm-const-req wm-const-req :wm-not-req wm-not-req
           :db-req db-req :db-keys db-keys
           :uses-agent? (boolean (or (contains? ins-set '?agent) (contains? ins-set '?agent-eid)))
           :eff-q eff-q :eff-ins (if need-$? (into ['$ ] raw-ins) raw-ins)})))))

(defn rule-wm-read-attrs
  "Extracts the set of `$wm` attributes read by `rule`'s `:seon.rule/query`."
  {:malli/schema [:=> [:cat :map] [:set :keyword]] :cost/ticks 50000}
  [rule]
  (:wm-attrs (query-meta (:seon.rule/query rule))))

(defn rule-uses-agent?
  "True when `rule`'s `:in` clause binds `?agent` or `?agent-eid`."
  {:malli/schema [:=> [:cat :map] :boolean] :cost/ticks 5000}
  [rule]
  (:uses-agent? (query-meta (:seon.rule/query rule))))

(defn rule-db-keys
  "Returns the set of DB keys (`:main`, `:branch`, `:code`, `:task`) read by `rule`'s `:in` clause."
  {:malli/schema [:=> [:cat :map] [:set :keyword]] :cost/ticks 10000}
  [rule]
  (:db-keys (query-meta (:seon.rule/query rule))))

(def ^:private empty-reads-idx (reads/reads-index #{}))

(defn- wm-req-miss? [wm qm]
  (let [aev (:aev wm)
        ave (:ave wm)
        eav (:eav wm)]
    (boolean
      (or (some #(not (contains? aev %)) (:wm-req qm))
          (and (map? ave) (some (fn [[a v]] (not (contains? (get ave a) v))) (:wm-const-req qm)))
          (and (map? eav)
               (some (fn [[a1 a2 v2]]
                       (let [es (keys (get aev a1))]
                         (every? (fn [e]
                                   (if (= v2 ::any)
                                     (contains? (get eav e) a2)
                                     (contains? (get-in eav [e a2]) v2)))
                                 es)))
                     (:wm-not-req qm)))))))

(defn eval-rule-query
  "Executes `rule`'s native Datahike `:seon.rule/query` via `d/q` and returns binding env maps."
  {:malli/schema [:=> [:cat :map :map [:maybe :keyword] :map] [:vector :map]] :cost/ticks 10000000}
  [wm dbs agent-id rule]
  (let [{:keys [pq db-req eff-q eff-ins] :as qm} (query-meta (:seon.rule/query rule))]
    (if (or (nil? eff-q) (wm-req-miss? wm qm) (db-req-empty? db-req dbs))
      []
      (if-let [args (resolve-query-inputs eff-ins wm dbs agent-id)]
        (rows->envs pq (apply d/q eff-q args))
        []))))

(defn- step-one-rule [state r stratum dbs agent-id prev-state tx-datoms dirty-attrs recomputed skipped]
  (let [rid (:seon.rule/id r)
        prev (get-in prev-state [:runs rid])
        {:keys [wm-attrs db-keys uses-agent?] :as qm} (query-meta (:seon.rule/query r))
        wm-clean? (and prev (= (:rule-def prev) r)
                       (or (not uses-agent?) (= agent-id (:agent-id prev-state)))
                       (not (contains? @dirty-attrs :*))
                       (not (contains? wm-attrs :*))
                       (not (some @dirty-attrs wm-attrs)))
        rel-dt (when wm-clean?
                 (cond (nil? tx-datoms) nil
                       (map? tx-datoms) (vec (mapcat #(get tx-datoms %) db-keys))
                       :else tx-datoms))
        db-clean? (and wm-clean?
                       (or (empty? db-keys)
                           (and (some? rel-dt) (not (reads/reads-touched? (:reads-idx prev) rel-dt)))))]
    (if db-clean?
      (do
        (vswap! skipped inc)
        (when-let [outer di/*reads*] (when (seq (:reads prev)) (swap! outer into (:reads prev))))
        {:wm (if (seq (:asserted-facts prev)) (add-facts (:wm state) (:asserted-facts prev) {:rule rid :stratum stratum}) (:wm state))
         :projections (if (seq (:projections prev)) (into (:projections state) (:projections prev)) (:projections state))
         :runs (assoc (:runs state) rid (assoc prev :skipped? true))})
      (let [wm (:wm state)
            old-facts (or (:asserted-facts prev) #{})
            miss? (wm-req-miss? wm qm)
            t0 (if miss? 0 (System/nanoTime))
            [envs rs] (if miss? [[] #{}] (reads/with-reads #(eval-rule-query wm dbs agent-id r)))
            {:keys [facts projections]} (if (seq envs) (instantiate-asserts envs (:seon.rule/assert r) rid stratum) {:facts #{} :projections []})
            ridx (if (seq rs) (reads/reads-index rs) empty-reads-idx)
            ms (if miss? 0.0 (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0))]
        (vswap! recomputed inc)
        (when (not= facts old-facts)
          (doseq [[_ a _] (set/union (set/difference facts old-facts) (set/difference old-facts facts))]
            (vswap! dirty-attrs conj a)))
        {:wm (if (seq facts) (add-facts wm facts {:rule rid :stratum stratum}) wm)
         :projections (if (seq projections) (into (:projections state) projections) (:projections state))
         :runs (assoc (:runs state) rid
                      {:rule-id rid :rule-def r :stratum stratum :category (:seon.rule/category r :present)
                       :fired (count envs) :asserted-count (count facts) :asserted-facts facts :projections projections
                       :reads rs :reads-idx ridx :reads-by-db (if miss? {} (into {} (map (fn [k] [k ridx])) db-keys))
                       :db-keys db-keys :uses-agent? uses-agent? :reads-count (count rs) :skipped? false :ms ms})}))))

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

(defonce ^:private ^:seon.flow/edge edited-forms-cache (store/lru-cache 32))

(defn- ^:seon.flow/edge changes-form-eids [main-db branch-db]
  (when (and (instance? datahike.db.DB main-db)
             (instance? datahike.db.DB branch-db)
             (= (get-in main-db [:config :store :id]) (get-in branch-db [:config :store :id])))
    (let [mc (d/commit-id main-db) bc (d/commit-id branch-db)]
      (when (and mc bc)
        (try
          (let [base (d/merge-base branch-db mc bc)]
            (when base
              (if (= base bc)
                []
                (into [] (comp (filter #(and (:added %) (= :seon.code.form/hash (:a %))))
                               (map :e) (distinct)
                               (filter #(some? (first (d/datoms branch-db :eavt % :seon.code.form/hash)))))
                      (d/changes branch-db base bc)))))
          (catch Throwable _ nil))))))