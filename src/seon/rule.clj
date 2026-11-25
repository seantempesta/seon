(ns seon.rule
  "Native Datahike `d/q` + `datahike.reads` incremental stratified rule engine over `:seon.rule/*` entities."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [datahike.index.interface :as di]
            [datahike.query :as dq]
            [datahike.reads :as reads]
            [seon.ctx.diff :as diff]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store :refer [live-cid]]
            [seon.rule.query :as rq]))

(defn- dsch [db] (or (:schema db) (when db (d/schema db)) {}))

(def default-hierarchy
  "Default `clojure.core/derive` hierarchy for polymorphic rule matching."
  (reduce (fn [h [c p]] (derive h c p)) (make-hierarchy)
          [[:rel/curated :rel/strong-signal] [:rel/mentions-ns :rel/strong-signal]
           [:rel/mentions-var :rel/strong-signal] [:rel/mentions-schema :rel/strong-signal]
           [:rel/tree-overlap :rel/strong-signal]
           [:ctx.match/embedding :ctx.match/semantic] [:ctx.match/search :ctx.match/semantic]]))

(defn ^{:seon.code/reexport 'seon.rule.query/lvar?} lvar?
  "Re-exports `seon.rule.query/lvar?`."
  [x] (rq/lvar? x))

(defn empty-wm
  "Creates an empty immutable EAV working memory with optional `clojure.core/make-hierarchy`."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :any] :map]] :cost/ticks 5000}
  ([] (empty-wm default-hierarchy))
  ([hierarchy]
   {:facts #{} :eav {} :aev {} :ave {} :provenance {} :hierarchy (or hierarchy default-hierarchy)}))

(defn add-fact
  "Adds `[e a v]` to working memory `wm` with provenance descriptor `prov` (`{:rule :stratum}`)."
  {:malli/schema [:=> [:cat :map [:vector :any] [:maybe :map]] :map] :cost/ticks 10000}
  [wm [e a v :as fact] prov]
  (if (or (not= 3 (count fact)) (nil? e) (nil? a) (nil? v)
          (= :seon.rule/nil e) (= :seon.rule/nil a) (= :seon.rule/nil v))
    wm
    (let [wm' (if (contains? (:facts wm) fact)
                wm
                (-> wm
                    (update :facts conj fact)
                    (update-in [:eav e a] (fnil conj #{}) v)
                    (update-in [:aev a e] (fnil conj #{}) v)
                    (update-in [:ave a v] (fnil conj #{}) e)))]
      (if prov (update-in wm' [:provenance fact] (fnil conj []) prov) wm'))))

(defn add-facts
  "Bulk-inserts a sequence or set of `[e a v]` triples into `wm` with provenance `prov`."
  {:malli/schema [:=> [:cat :map [:maybe [:or [:sequential :any] [:set :any]]] [:maybe :map]] :map]
   :cost/ticks 500000}
  [wm facts prov]
  (if (and (coll? facts) (not (map? facts)))
    (reduce (fn [w f]
              (if (and (vector? f) (= 3 (count f)) (some? (first f)) (some? (second f)) (some? (nth f 2)))
                (add-fact w f prov) w))
            wm facts)
    wm))

(defn wm-vals
  "Returns the set of values for `[e a]` in working memory `wm`."
  {:malli/schema [:=> [:cat :map :any :any] [:set :any]] :cost/ticks 1000}
  [wm e a]
  (get-in (:eav wm) [e a] #{}))

(defn wm-scalar
  "Resolves the winning scalar value for `[e a]` in `wm` by highest asserting rule `:stratum`."
  {:malli/schema [:=> [:cat :map :any :any] :any] :cost/ticks 5000}
  [wm e a]
  (let [vs (get-in (:eav wm) [e a])]
    (when (seq vs)
      (if (= 1 (count vs))
        (first vs)
        (let [prov (:provenance wm)
              score-v (fn [v]
                        (let [ps (get prov [e a v])]
                          (if (seq ps) (apply max (map #(long (or (:stratum %) 0)) ps)) 0)))]
          (first (sort-by (fn [v] [(- (score-v v)) (pr-str v)]) vs)))))))

(defn wm-entities-with
  "Returns all entity keys in `wm` that hold attribute `a`."
  {:malli/schema [:=> [:cat :map :any] [:vector :any]] :cost/ticks 5000}
  [wm a]
  (vec (keys (get-in wm [:aev a]))))

(defn- rule-applies-to-target? [rule target]
  (let [rt (:seon.rule/target rule :any)]
    (or (nil? target) (= rt :any) (= rt target))))

(defn diff-sessions
  "Compares two rule session results (`control-res` vs `test-res`) for A/B rule analysis."
  {:malli/schema [:=> [:cat :map :map] :map] :cost/ticks 2000000}
  [c-res t-res]
  (let [f1 (or (get-in c-res [:wm :facts]) #{}) f2 (or (get-in t-res [:wm :facts]) #{})
        added (vec (sort-by pr-str (set/difference f2 f1)))
        removed (vec (sort-by pr-str (set/difference f1 f2)))
        to-m #(cond (map? %) % (sequential? %) (into {} (map (juxt :rule-id identity)) %) :else {})
        r1 (to-m (:runs c-res)) r2 (to-m (:runs t-res))
        rids (sort (set/union (set (keys r1)) (set (keys r2))))
        rdiffs (vec (keep #(let [a (:fired (get r1 %) 0) b (:fired (get r2 %) 0)]
                             (when (not= a b) {:rule % :before a :after b})) rids))]
    {:added-count (count added) :removed-count (count removed)
     :added added :removed removed :rule-diffs rdiffs}))

(defonce ^:private ^:seon.flow/edge rules-load-cache (store/lru-cache 16))

(defn- scan-stored-rules [db]
  (let [dh (dsch db)
        ev-map (fn [a] (reduce #(assoc %1 (:e %2) (:v %2)) {} (d/datoms db :aevt a)))
        ids (ev-map :seon.rule/id)
        attrs [:seon.rule/doc :seon.rule/category :seon.rule/stratum :seon.rule/enabled?
               :seon.rule/target :seon.rule/query :seon.rule/when :seon.rule/assert]
        by-a (into {} (map (fn [a] [a (if (contains? dh a) (ev-map a) {})])) attrs)]
    (reduce-kv (fn [acc e rid]
                 (assoc acc rid
                        (reduce (fn [m a]
                                  (let [am (get by-a a)]
                                    (if (contains? am e) (assoc m a (get am e)) m)))
                                {:seon.rule/id rid} attrs)))
               {} ids)))

(def default-datalog-rules
  "Shared recursive Datahike Datalog rules passed as `%` to `:seon.rule/query`."
  '[[(calls* ?a ?b) [?a :seon.code.var/calls ?b]]
    [(calls* ?a ?c) [?a :seon.code.var/calls ?b] (calls* ?b ?c)]
    [(needs* ?t1 ?t2) [?t1 :seon.task/needs ?tid2] [?t2 :seon.task/id ?tid2]]
    [(needs* ?t1 ?t3) [?t1 :seon.task/needs ?tid2] [?t2 :seon.task/id ?tid2] (needs* ?t2 ?t3)]
    [(compatible-fn ?s ?f) [?f :seon.code.var/input ?s] (not [?f :seon.code.var/private? true]) (not [?f :seon.code.var/test? true])]
    [(compatible-fn ?s ?f) [?f :seon.code.var/output ?s] (not [?f :seon.code.var/private? true]) (not [?f :seon.code.var/test? true])]
    [(render-fn ?s ?f) [?f :seon.code.var/input ?s] [?f :seon.code.var/output ?u] [?u :seon.schema/key :seon.ui/hiccup]]
    [(schema-doc ?s ?d) [?d :seon.code.doc/references ?s]]
    [(var-doc ?v ?d) [?d :seon.code.doc/references ?v]]])

(defn entity-facets
  "Returns vector of Malli schema keywords satisfied by entity `e` (eid or map) on `db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:vector :qualified-keyword]] :cost/ticks 500000}
  [db e]
  (let [ent (cond (map? e) e
                  (and (integer? e) db) (reduce (fn [m dt] (assoc m (:a dt) true)) {} (d/datoms db :eavt e))
                  :else nil)]
    (if (and (map? ent) (seq ent))
      (mapv :schema (six/facets-of (schema/entity-index db) ent))
      [])))

(defn parse-query
  "Normalizes a vector or map Datahike Datalog query into `{:find :keys :in :where}`."
  {:malli/schema [:=> [:cat :any] :map] :cost/ticks 50000}
  [q]
  (rq/parse-query q))

(defn subst-template
  "Recursively substitutes `?lvar` bindings from `env` into a triple or projection map template."
  {:malli/schema [:=> [:cat :map :any] :any] :cost/ticks 50000}
  [env tmpl]
  (cond
    (lvar? tmpl) (get env tmpl)
    (vector? tmpl) (mapv #(subst-template env %) tmpl)
    (map? tmpl) (reduce-kv (fn [m k v]
                             (let [k' (subst-template env k) v' (subst-template env v)]
                               (if (or (nil? k') (nil? v') (= :seon.rule/nil v')) m (assoc m k' v'))))
                           {} tmpl)
    :else tmpl))

(defn- resolve-query-inputs [ins wm dbs aid fref feid]
  (let [eff (or (:branch dbs) (:code dbs) (:main dbs))
        ae (delay (when aid
                    (some #(when (and % ((dsch %) :seon.agent/id))
                             (:e (first (d/datoms % :avet :seon.agent/id aid))))
                          [(:branch dbs) (:main dbs)])))
        fr (or fref (when aid [:seon.agent/id aid]))
        fe (or feid @ae)
        vs (mapv (fn [s]
                   (case s
                     $ eff
                     $main (or (:main dbs) eff)
                     $branch (or (:branch dbs) (:main dbs) eff)
                     $code (or (:code dbs) (:branch dbs) (:main dbs))
                     $task (or (:task dbs) (:branch dbs) (:main dbs))
                     $wm (or (:facts wm) #{})
                     ?agent aid
                     ?agent-eid @ae
                     ?focus fr
                     ?focus-eid fe
                     ?hierarchy (or (:hierarchy wm) default-hierarchy)
                     % default-datalog-rules
                     ::unknown))
                 ins)]
    (when (every? #(and (some? %) (not= ::unknown %)) vs) vs)))

(defn- rows->envs [pq raw]
  (let [ks (:keys pq) finds (:find pq)
        var-sym (fn [form]
                  (cond
                    (lvar? form) form
                    (and (seq? form) (>= (count form) 2) (lvar? (second form)))
                    (symbol (str "?" (name (first form)) "-" (subs (name (second form)) 1)))
                    :else nil))]
    (cond
      (seq ks) (mapv (fn [m] (reduce-kv (fn [acc k v] (assoc acc (symbol (str "?" (name k))) v)) {} m)) raw)
      (and (= 2 (count finds)) (= '. (second finds)) (lvar? (first finds))) (if (some? raw) [{(first finds) raw}] [])
      (and (= 1 (count finds)) (vector? (first finds)) (= '... (second (first finds))) (lvar? (ffirst finds)))
      (let [v (ffirst finds)] (mapv (fn [x] {v x}) raw))
      :else (let [vars (mapv var-sym finds)] (mapv (fn [row] (zipmap vars row)) raw)))))

(defn- instantiate-asserts [envs asserts rid stratum]
  (let [facts (volatile! #{}) projs (volatile! [])]
    (doseq [env envs tmpl asserts]
      (cond
        (vector? tmpl)
        (let [f (subst-template env tmpl)]
          (when (and (= 3 (count f)) (every? #(and (some? %) (not= :seon.rule/nil %)) f))
            (vswap! facts conj f)))
        (map? tmpl)
        (let [p (subst-template env tmpl)]
          (when (and (some? (:entity p)) (not= :seon.rule/nil (:entity p)))
            (vswap! projs conj (assoc p :rule rid :stratum stratum))))))
    {:facts @facts :projections @projs}))

(defn- normalize-step-args [a2 a3]
  (if (and (map? a2) (contains? a2 :facts))
    {:base-wm a2 :dbs (if (map? a3) a3 {})
     :agent-id (or (wm-scalar a2 :viewer :ctx/viewer)
                   (first (for [[_ a v] (:facts a2) :when (= a :ctx/viewer)] v)))}
    {:base-wm (if (and (map? a3) (:base-wm a3)) (:base-wm a3) (empty-wm))
    :dbs (if (map? a2) a2 {}) :agent-id (if (map? a3) (:agent-id a3) a3)
    :focus-ref (when (map? a3) (:focus-ref a3)) :focus-eid (when (map? a3) (:focus-eid a3))}))

(defn- initial-dirty-attrs [prev-state base-wm active target]
  (if (or (nil? prev-state) (not= target (:target prev-state)))
    #{:*}
    (let [old-f (or (:base-facts prev-state) #{}) new-f (or (:facts base-wm) #{})
          base-diff (set/union (set/difference new-f old-f) (set/difference old-f new-f))
          active-ids (into #{} (map :seon.rule/id) active)
          removed-facts (mapcat (fn [[rid run]] (when-not (contains? active-ids rid) (:asserted-facts run)))
                                (:runs prev-state))]
      (into #{} (map second) (concat base-diff removed-facts)))))

(def ^:dynamic *base-rules-override*
  "Optional vector of `:seon.rule/*` maps bound during branch test execution."
  nil)

(defn rules-db-sig
  "O(1) commit-anchored signature over `:seon.rule/*` attributes (via `seon.ctx.diff/rules-db-sig`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :any] :cost/ticks 500000}
  [db]
  (when-let [s (diff/rules-db-sig db)]
    (if-let [ov *base-rules-override*] [s (System/identityHashCode ov)] s)))

(defn load-rules
  "Reads all `:seon.rule/*` entities from `db` (tracked by `datahike.reads`), merged over optional `fallback-rules`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector :map]]] [:vector :map]]]
   :cost/ticks 2000000}
  ([db] (load-rules db nil))
  ([db fallback-rules]
   (let [has-stored? (boolean (and db (contains? (dsch db) :seon.rule/id)
                                   (first (d/datoms db :aevt :seon.rule/id))))
         compute (fn []
                   (let [stored (if has-stored? (scan-stored-rules db) {})
                         base (if (seq stored) stored (into {} (map (juxt :seon.rule/id identity)) (or fallback-rules [])))]
                     (->> (vals base)
                          (sort-by (juxt #(long (or (:seon.rule/stratum %) 50)) #(str (:seon.rule/id %))))
                          vec)))
         rsig (when (and db (not (instance? datahike.db.AsOfDB db)) (not (instance? datahike.db.HistoricalDB db)))
                (when-let [s (rules-db-sig db)]
                  [s (if has-stored? :stored (hash fallback-rules))]))]
     (if rsig (store/lru-get! rules-load-cache 16 rsig compute) (compute)))))

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
       (let [txl (long tx) prun (branch-passing-run db dh txl) fset (:failed prun)]
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
                              (if-let [dg (:v (first (d/datoms db :eavt (:e dt) :seon.test/digest)))] (conj acc dg) acc)
                              :else acc))
                          acc))
                      (if (string? digest) #{digest} #{})
                      (d/datoms db :avet :seon.test/var-ref var-eid))]
             (and (not= ::cleared res) (or (nil? prun) (boolean (some fset res)))))))))))

(defn rule-wm-read-attrs
  "Extracts the set of `$wm` attributes read by `rule`'s `:seon.rule/query`."
  {:malli/schema [:=> [:cat :map] [:set :keyword]] :cost/ticks 50000}
  [rule] (:wm-attrs (rq/query-meta (:seon.rule/query rule))))

(defn rule-uses-agent?
  "True when `rule`'s `:in` clause binds `?agent` or `?agent-eid`."
  {:malli/schema [:=> [:cat :map] :boolean] :cost/ticks 5000}
  [rule] (:uses-agent? (rq/query-meta (:seon.rule/query rule))))

(defn rule-db-keys
  "Returns the set of DB keys (`:main`, `:branch`, `:code`, `:task`) read by `rule`'s `:in` clause."
  {:malli/schema [:=> [:cat :map] [:set :keyword]] :cost/ticks 10000}
  [rule] (:db-keys (rq/query-meta (:seon.rule/query rule))))

(def ^:private empty-reads-idx (reads/reads-index #{}))

(defn eval-rule-query
  "Executes rule's native Datahike `:seon.rule/query` via `d/q` and returns binding env maps."
  {:malli/schema [:function [:=> [:cat :map :map [:maybe :keyword] :map] [:vector :map]] [:=> [:cat :map :map [:maybe :keyword] :map :any [:maybe :int]] [:vector :map]]], :cost/ticks 10000000, :seon.flow/edge true}
  ([wm dbs agent-id rule] (eval-rule-query wm dbs agent-id rule nil nil))
  ([wm dbs agent-id rule focus-ref focus-eid]
   (let [{:keys [pq req-tree focus-eq fn-syms db-fn-call? direct-fn-call eff-q eff-ins] :as qm} (rq/query-meta (:seon.rule/query rule))
         eff-focus (or focus-ref (when agent-id [:seon.agent/id agent-id]))]
     (if (or (nil? eff-q)
             (and (some? focus-eq) (not= eff-focus focus-eq))
             (rq/wm-req-miss? wm qm)
             (rq/req-tree-empty? req-tree wm dbs agent-id)
             (and (seq fn-syms) (not-every? #(or (resolve %) (try (requiring-resolve %) (catch Throwable _ nil))) fn-syms)))
       []
       (if-let [args (resolve-query-inputs eff-ins wm dbs agent-id focus-ref focus-eid)]
         (if direct-fn-call
           (rq/eval-direct-fn-call direct-fn-call args)
           (rows->envs pq (if db-fn-call?
                            (binding [dq/*query-result-cache?* false] (apply d/q eff-q args))
                            (apply d/q eff-q args))))
         [])))))

(defonce ^:private ^:seon.flow/edge edited-forms-cache (store/lru-cache 64))

(defn ^:seon.flow/edge edited-form-eids
  "Returns `:seon.code.form/*` EIDs modified on `branch-db` since `diff/fast-merge-base` with `main-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] [:vector :int]]
   :cost/ticks 5000000}
  [main-db branch-db]
  (if-not (and branch-db (contains? (dsch branch-db) :seon.code.form/hash))
    []
    (let [mc (live-cid main-db) bc (live-cid branch-db)
          same? (or (nil? main-db) (identical? main-db branch-db)
                    (and mc bc (= mc bc) (= (:max-tx main-db) (:max-tx branch-db))))]
      (if same?
        (do (first (d/datoms branch-db :aevt :seon.code.form/hash)) [])
        (let [sid (store/store-id branch-db)
              base (when (and mc bc (= sid (store/store-id main-db))) (diff/fast-merge-base main-db branch-db))
              run (fn []
                    (first (d/datoms branch-db :aevt :seon.code.form/hash))
                    (if-let [chs (when base (diff/branch-changes branch-db base bc))]
                      (let [ha (into #{:seon.code.form/hash} (keep #(get-in branch-db [:ident-ref-map %])) [:seon.code.form/hash])]
                        (into [] (comp (filter #(and (:added %) (contains? ha (:a %))))
                                       (map :e) (distinct)
                                       (filter #(first (d/datoms branch-db :eavt % :seon.code.form/hash))))
                              chs))
                      (let [mh (if (contains? (dsch main-db) :seon.code.form/hash)
                                 (reduce #(assoc %1 (:e %2) [(:v %2) (long (:tx %2 0))]) {}
                                         (d/datoms main-db :aevt :seon.code.form/hash))
                                 {})]
                        (vec (keep (fn [dt]
                                     (let [[mv mtx] (get mh (:e dt))]
                                       (when (and (not= (:v dt) mv) (or (nil? mtx) (> (long (:tx dt 0)) (long mtx))))
                                         (:e dt))))
                                   (d/datoms branch-db :aevt :seon.code.form/hash))))))]
          (if-let [csig (and sid base (diff/code-db-sig branch-db))]
          (store/lru-get! edited-forms-cache 64 [sid base csig] run)
          (run)))))))

(defn agent-eval-turn-count
  "Returns the count of distinct surviving `:seon.agent.eval/turn` values for `agent-id` in `db` via indexed `:avet` lookup."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] :int] :cost/ticks 2000000}
  [db agent-id]
  (if-not (and db (contains? (dsch db) :seon.agent.eval/agent))
    0
    (->> (d/datoms db :avet :seon.agent.eval/agent agent-id)
         (keep #(:v (first (d/datoms db :eavt (:e %) :seon.agent.eval/turn))))
         distinct count)))

(defn agent-max-eval-turn
  "Returns the maximum `:seon.agent.eval/turn` (or 0) for `agent-id` on `branch-db` via indexed `:avet`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] :int] :cost/ticks 2000000}
  [branch-db agent-id]
  (if (and branch-db agent-id (contains? (dsch branch-db) :seon.agent.eval/agent))
    (reduce (fn [mx dt]
              (let [t (:v (first (d/datoms branch-db :eavt (:e dt) :seon.agent.eval/turn)))]
                (if (and (integer? t) (> (long t) (long mx))) (long t) mx)))
            0 (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))
    0))

(def ^:dynamic *track-reads?*
  "When false (and no outer `datahike.index.interface/*reads*` is active), `step-one-rule` evaluates `d/q` with Datahike's full query planner enabled."
  true)

(defn- step-one-rule [state r stratum dbs aid fref feid prev-state tx-datoms dirty-attrs recomputed skipped]
  (let [rid (:seon.rule/id r) prev (get-in prev-state [:runs rid])
        {:keys [wm-attrs db-keys uses-agent?] :as qm} (rq/query-meta (:seon.rule/query r))
        wm-clean? (and prev (= (:rule-def prev) r)
                       (or (not uses-agent?) (and (= aid (:agent-id prev-state)) (= fref (:focus-ref prev-state)) (= feid (:focus-eid prev-state))))
                       (not (@dirty-attrs :*)) (not (wm-attrs :*)) (not (some @dirty-attrs wm-attrs)))
        rel-dt (when wm-clean?
                 (cond (nil? tx-datoms) nil (map? tx-datoms) (vec (mapcat #(get tx-datoms %) db-keys)) :else tx-datoms))
        db-clean? (and wm-clean? (or (empty? db-keys) (and (some? rel-dt) (not (reads/reads-touched? (:reads-idx prev) rel-dt)))))]
    (if db-clean?
      (do (vswap! skipped inc)
          (when-let [outer di/*reads*] (when (seq (:reads prev)) (swap! outer into (:reads prev))))
          {:wm (if (seq (:asserted-facts prev)) (add-facts (:wm state) (:asserted-facts prev) {:rule rid :stratum stratum}) (:wm state))
           :projections (if (seq (:projections prev)) (into (:projections state) (:projections prev)) (:projections state))
           :runs (assoc (:runs state) rid (assoc prev :skipped? true))})
      (let [wm (:wm state) old-f (or (:asserted-facts prev) #{}) miss? (rq/wm-req-miss? wm qm)
            t0 (if miss? 0 (System/nanoTime))
            [envs rs] (cond miss? [[] #{}]
                              (or *track-reads?* di/*reads*) (reads/with-reads #(eval-rule-query wm dbs aid r fref feid))
                              :else [(binding [dq/*disable-planner* true] (eval-rule-query wm dbs aid r fref feid)) #{}])
            {:keys [facts projections]} (if (seq envs) (instantiate-asserts envs (:seon.rule/assert r) rid stratum) {:facts #{} :projections []})
            ridx (if (seq rs) (reads/reads-index rs) empty-reads-idx)
            ms (if miss? 0.0 (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0))]
        (vswap! recomputed inc)
        (when (not= facts old-f)
          (doseq [[_ a _] (set/union (set/difference facts old-f) (set/difference old-f facts))]
            (vswap! dirty-attrs conj a)))
        {:wm (if (seq facts) (add-facts wm facts {:rule rid :stratum stratum}) wm)
         :projections (if (seq projections) (into (:projections state) projections) (:projections state))
         :runs (assoc (:runs state) rid
                      {:rule-id rid :rule-def r :stratum stratum :category (:seon.rule/category r :present)
                       :fired (count envs) :asserted-count (count facts) :asserted-facts facts :projections projections
                       :reads rs :reads-idx ridx :reads-by-db (if miss? {} (into {} (map (fn [k] [k ridx])) db-keys))
                       :db-keys db-keys :uses-agent? uses-agent? :reads-count (count rs) :skipped? false :ms ms})}))))

(defn step-rules
  "Incrementally evaluates `rules` in stratum order via native Datahike `d/q` inside `datahike.reads/with-reads`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :map] :map :any [:vector :map] [:maybe :any]] :map]
                  [:=> [:cat [:maybe :map] :map :any [:vector :map] [:maybe :any] [:maybe :keyword]] :map]]
   :cost/ticks 20000000}
  ([prev-state a2 a3 rules tx-datoms] (step-rules prev-state a2 a3 rules tx-datoms nil))
  ([prev-state a2 a3 rules tx-datoms target]
   (let [{:keys [base-wm dbs agent-id focus-ref focus-eid]} (normalize-step-args a2 a3)
         active (filterv #(and (:seon.rule/enabled? % true) (rule-applies-to-target? % target)) rules)
         by-stratum (group-by #(long (:seon.rule/stratum % 50)) active)
         dirty-attrs (volatile! (initial-dirty-attrs prev-state base-wm active target))
         recomputed (volatile! 0) skipped (volatile! 0)
         final (reduce (fn [st stratum]
                         (reduce (fn [st2 r]
                                   (step-one-rule st2 r stratum dbs agent-id focus-ref focus-eid prev-state tx-datoms dirty-attrs recomputed skipped))
                                 st (sort-by #(str (:seon.rule/id %)) (get by-stratum stratum))))
                       {:wm base-wm :projections [] :runs {}}
                       (sort (keys by-stratum)))]
     (assoc final :base-facts (:facts base-wm) :agent-id agent-id :focus-ref focus-ref :focus-eid focus-eid
            :target target :recomputed @recomputed :skipped @skipped))))

(defn fire-rules
  "Cold stratified rule evaluation convenience wrapper over `step-rules`."
  {:malli/schema [:function
                  [:=> [:cat :map :any [:vector :map]] :map]
                  [:=> [:cat :map :any [:vector :map] [:maybe :keyword]] :map]]
   :cost/ticks 20000000}
  ([a1 a2 rules] (step-rules nil a1 a2 rules nil nil))
  ([a1 a2 rules target] (step-rules nil a1 a2 rules nil target)))