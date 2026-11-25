(ns seon.ctx.project
  "Pure generic 2-read focal entity world discovery (`entity-world-datoms` / `agent-world-datoms`),
   monotonic pull-key-set union, and rule projection collapsing for `seon.ctx/project-entity`."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.ctx.diff :as diff]
            [seon.ctx.ground :as ground]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :refer [db-schema live-cid]]
            [seon.rule :as rules]
            [seon.schema.index :as six]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge ref-attrs-cache (store/lru-cache 32))

(defn same-db?
  "True when `a` and `b` represent the same immutable Datahike snapshot."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :boolean]
   :cost/ticks 50000}
  [a b]
  (boolean (or (= a b) (let [ca (live-cid a) cb (live-cid b)] (and ca cb (= ca cb))))))

(defn merge-pull-patterns
  "Delegates to `seon.ctx.ground/merge-pull-patterns`."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]]
   :cost/ticks 500000}
  [patterns]
  (ground/merge-pull-patterns patterns))

(defn- reverse-ref-attrs-for [db focus-schema]
  (let [compute (fn []
                  (let [dh (db-schema db)
                        all-refs (into #{} (keep (fn [[a sp]] (when (= :db.type/ref (:db/valueType sp)) a))) dh)
                        ix (:entity-index (fit/cached-registry-info db))
                        targeted (into #{} (keys (:targets ix)))
                        generic (set/difference all-refs targeted)
                        reaching (when (qualified-keyword? focus-schema)
                                   (let [se (when (dh :seon.schema/key) (:e (first (d/datoms db :avet :seon.schema/key focus-schema))))
                                         from-db (when (and se (dh :seon.schema/ref-targets))
                                                   (keep #(:v (first (d/datoms db :eavt (:e %) :seon.schema/key)))
                                                         (d/datoms db :avet :seon.schema/ref-targets se)))]
                                     (set (concat from-db (six/reaching ix focus-schema)))))
                        chosen (if (qualified-keyword? focus-schema)
                                 (set/intersection all-refs (set/union generic (or reaching #{})))
                                 all-refs)]
                    (->> chosen (remove #{:seon.agent/example-refs}) sort vec)))
        sig (diff/code-db-sig db)]
    (if sig (store/lru-get! ref-attrs-cache 32 [sig focus-schema] compute) (compute))))

(defn- merge-forward-datoms [main-db branch-db eid]
  (let [m-ds (when main-db (vec (d/datoms main-db :eavt eid)))
        b-ds (when (and branch-db (not (same-db? branch-db main-db))) (vec (d/datoms branch-db :eavt eid)))]
    (if (empty? b-ds)
      (or m-ds [])
      (let [m-by-a (group-by :a m-ds)
            b-by-a (group-by :a b-ds)
            all-a (sort (set/union (set (keys m-by-a)) (set (keys b-by-a))))
            max-tx (fn [ds] (reduce max 0 (map #(long (or (:tx %) 0)) ds)))]
        (vec (mapcat (fn [a]
                       (let [ms (get m-by-a a) bs (get b-by-a a)]
                         (cond (empty? ms) bs (empty? bs) ms (>= (max-tx bs) (max-tx ms)) bs :else ms)))
                     all-a))))))

(defn- infer-focus-schema [db data focus-kind]
  (or (when (and (map? data) (instance? clojure.lang.IObj data)) (:seon/schema (meta data)))
      (when (and (map? data) (seq (dissoc data :db/id)))
        (let [ix (:entity-index (fit/cached-registry-info db))
              ms (six/most-specific ix data)]
          (or (when (qualified-keyword? ms) ms)
              (:schema (first (six/facets-of ix data))))))
      (case focus-kind
        :agent :seon.agent/entity
        :system :seon.system/entity
        nil)))

(defn- normalize-focal-ref [focal-ref]
  (cond
    (integer? focal-ref) [:db/id (long focal-ref)]
    (and (vector? focal-ref) (= 2 (count focal-ref)) (qualified-keyword? (first focal-ref))) focal-ref
    (qualified-keyword? focal-ref) [:seon.agent/id focal-ref]
    :else [:seon.system/id :root]))

(defn resolve-focal
  "Resolves any `focal-ref` (agent, system, task, var, ns, schema, branch, signal, or eid) across `branch-db` and `main-db`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
   :cost/ticks 4000000}
  [main-db branch-db focal-ref]
  (let [bdb (or branch-db main-db)
        ref0 (normalize-focal-ref focal-ref)
        [ra rv] ref0
        eid (ground/resolve-lookup-eid main-db bdb ref0)
        b-ent (when eid (d/pull bdb '[*] eid))
        ent (cond
              (and (map? b-ent) (seq (dissoc b-ent :db/id))) b-ent
              (and eid (not (same-db? main-db bdb))) (let [m (d/pull main-db '[*] eid)]
                                                        (when (and (map? m) (seq (dissoc m :db/id))) m))
              (= ra :seon.system/id) {:seon.system/id rv :seon.system/title "Seon Runtime"}
              (= ra :seon.branch/name) {:seon.branch/name rv}
              (= ra :seon.signal/kind) {:seon.signal/kind rv}
              (= ra :seon.agent/id) {:seon.agent/id rv}
              :else {})
        kind (cond
               (or (= ra :seon.agent/id) (contains? ent :seon.agent/id)) :agent
               (or (= ra :seon.system/id) (contains? ent :seon.system/id)) :system
               (= ra :seon.branch/name) :branch
               (= ra :seon.signal/kind) :signal
               :else :entity)
        canon-ref (if (= ra :db/id) (or (rdata/entity-lookup-ref ent) ref0) ref0)
        sk (infer-focus-schema bdb ent kind)]
    {:focus-ref canon-ref
     :focus-eid eid
     :focus-kind kind
     :focus-data (or ent {})
     :focus-schema sk
     :agent-eid (when (= :agent kind) eid)}))

(defn entity-world-datoms
  "Generic 2-read focal discovery for ANY entity lookup ref:
   Read 1 (forward `:eavt` merged by latest `:tx`) + Read 2 (reverse `:avet` refs targeting `focus-eid`),
   plus optional bounded 2-hop `:hop2-eids` and `:neighbor-meta {eid {:hop n :via-attr a}}`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any :boolean] :map]]
   :cost/ticks 12000000}
  ([main-db branch-db focal-ref] (entity-world-datoms main-db branch-db focal-ref true))
  ([main-db branch-db focal-ref include-hop2?]
   (let [bdb (or branch-db main-db)
         {:keys [focus-ref focus-eid focus-kind focus-data focus-schema agent-eid]} (resolve-focal main-db bdb focal-ref)]
     (if-not focus-eid
       {:focus-ref focus-ref :focus-eid nil :focus-kind focus-kind :focus-data focus-data :focus-schema focus-schema
        :agent-eid nil :datoms-count 0 :hop1-eids #{} :hop2-eids #{} :neighbor-meta {}
        :forward [] :reverse [] :reverse-attrs [] :connected {} :pinned #{} :curated #{} :extra-nss #{}}
       (let [same? (same-db? bdb main-db)
             fwd (merge-forward-datoms main-db bdb focus-eid)
             rev-attrs (reverse-ref-attrs-for bdb focus-schema)
             mdh (db-schema main-db)
             bdh (db-schema bdb)
             dh-sch (if same? mdh (merge mdh bdh))
             rev-ds (vec (distinct
                          (mapcat (fn [a]
                                    (concat (when (contains? mdh a) (d/datoms main-db :avet a focus-eid))
                                            (when (and (not same?) (contains? bdh a)) (d/datoms bdb :avet a focus-eid))))
                                  rev-attrs)))
             pinned-eids (volatile! #{})
             curated-eids (volatile! #{})
             extra-ns-eids (volatile! #{})
             hop1 (volatile! #{})
             nb-meta (volatile! {})
             fwd-max-tx (reduce max 0 (map #(long (or (:tx %) 0)) fwd))
             conn-map (volatile! {focus-eid fwd-max-tx})
             bump! (fn [e tx] (when (integer? e) (vswap! conn-map update e #(max (long (or % 0)) (long (or tx 0))))))
             mark1! (fn [e a tx]
                      (when (and (integer? e) (not= e focus-eid))
                        (vswap! hop1 conj e)
                        (bump! e tx)
                        (when-not (contains? @nb-meta e)
                          (vswap! nb-meta assoc e {:hop 1 :via-attr a}))))]
         (doseq [dt fwd :let [a (:a dt) v (:v dt) tx (:tx dt)]]
           (cond
             (= :db.type/ref (get-in dh-sch [a :db/valueType]))
             (mark1! v a tx)

             (contains? #{:seon.agent/pinned :seon.agent/curated :seon.task/curated} a)
             (doseq [item (if (coll? v) v [v])]
               (when-let [e (ground/resolve-lookup-eid main-db bdb item)]
                 (mark1! e a tx)
                 (if (= :seon.agent/pinned a) (vswap! pinned-eids conj e) (vswap! curated-eids conj e))))

             (and (= :seon.agent/context-prefs a) (map? v))
             (doseq [nsym (:extra-namespaces v) :when (symbol? nsym)]
               (when-let [e (ground/resolve-lookup-eid main-db bdb [:seon.code.ns/name nsym])]
                 (mark1! e :seon.agent/context-prefs tx)
                 (vswap! extra-ns-eids conj e)))))
         (doseq [dt rev-ds :let [a (:a dt) e (:e dt) tx (:tx dt)]]
           (mark1! e a tx))
         (let [h1 @hop1
               hop2 (volatile! #{})]
           (when include-hop2?
             (doseq [e1 (sort h1)
                     dt (let [bds (d/datoms bdb :eavt e1)] (if (seq bds) bds (when-not same? (d/datoms main-db :eavt e1))))
                     :let [a (:a dt) v (:v dt)]
                     :when (and (= :db.type/ref (get-in dh-sch [a :db/valueType]))
                                (integer? v) (not= v focus-eid) (not (contains? h1 v)))]
               (vswap! hop2 conj v)
               (when-not (contains? @nb-meta v)
                 (vswap! nb-meta assoc v {:hop 2 :via-attr a}))))
           {:focus-ref focus-ref :focus-eid focus-eid :focus-kind focus-kind
            :focus-data focus-data :focus-schema focus-schema :agent-eid agent-eid
            :datoms-count (+ (count fwd) (count rev-ds))
            :hop1-eids h1 :hop2-eids @hop2 :neighbor-meta @nb-meta
            :forward fwd :reverse rev-ds :reverse-attrs rev-attrs
            :connected @conn-map :pinned @pinned-eids :curated @curated-eids :extra-nss @extra-ns-eids}))))))

(defn agent-world-datoms
  "Generic 2-read agent world discovery (delegates to `entity-world-datoms`)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map]
   :cost/ticks 12000000}
  [main-db branch-db agent-id]
  (entity-world-datoms main-db branch-db [:seon.agent/id agent-id]))

(defn collapse-rule-projections
  "Per-entity collapse of raw rule `:projections` preserving merged `:pull`, `:fn`, `:max-tokens`, `:budget`, `:stratum`, `:priority`, `:zone`, and `:params`."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]
   :cost/ticks 5000000}
  [raw-projections]
  (mapv (fn [[eid ps]]
          (let [ordered (sort-by (fn [p] [(long (or (:stratum p) 50)) (long (or (:priority p) 50)) (str (:rule p))]) ps)
                pulls (keep #(when (sequential? (:pull %)) (:pull %)) ordered)
                merged-pull (when (seq pulls) (ground/merge-pull-patterns pulls))
                win-fn (last (keep :fn ordered))
                max-tok (let [xs (keep #(when (number? (:max-tokens %)) (long (:max-tokens %))) ordered)]
                          (when (seq xs) (reduce min xs)))
                budget (let [xs (keep #(when (number? (:budget %)) (long (:budget %))) ordered)]
                         (when (seq xs) (reduce min xs)))
                zs (keep :zone ordered)]
            (cond-> {:entity eid
                     :params (reduce merge {} (keep :params ordered))
                     :priority (reduce max 50 (map #(long (or (:priority %) 50)) ordered))
                     :stratum (reduce min 50 (map #(long (or (:stratum %) 50)) ordered))
                     :parent (last (keep :parent ordered))
                     :zone (cond (some #{:agent-prefix} zs) :agent-prefix
                                 (some #{:static-prefix} zs) :static-prefix
                                 (seq zs) (last zs)
                                 :else :workspace)
                     :rules (vec (sort-by str (distinct (keep :rule ordered))))
                     :why (vec (distinct (keep :why ordered)))
                     :tags (into #{} (mapcat #(let [t (:tags %)] (if (coll? t) t (when t [t])))) ordered)
                     :tx (reduce max 0 (map #(long (or (:tx %) 0)) ordered))}
              (seq merged-pull) (assoc :pull merged-pull)
              win-fn (assoc :fn win-fn)
              max-tok (assoc :max-tokens max-tok)
              budget (assoc :budget budget))))
        (group-by :entity raw-projections)))

(defn seed-world-wm
  "Seeds rule working memory (`$wm`) from discovered `world`, `derived-touches`, and `extra-facts`."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:maybe [:sequential :any]]] :map]
   :cost/ticks 5000000}
  [world derived-touches extra-facts]
  (let [aeid (:agent-eid world)
        max-tx-m (or (:max-tx derived-touches) {})
        written-s (or (:written derived-touches) #{})
        te (:turn-events derived-touches)]
    (rules/add-facts
     (rules/empty-wm)
     (concat
      (mapcat (fn [[e tx]] (cond-> [[e :world/connected true]] (pos? (long tx)) (conj [e :world/tx (long tx)]))) (:connected world))
      (map #(vector % :world/pinned true) (:pinned world))
      (map #(vector % :world/extra-ns true) (:extra-nss world))
      (mapcat (fn [[e cnt]]
                (cond-> [[e :world/touched true] [e :world/touches (long cnt)] [e :world/touched-tx (long (get max-tx-m e 0))]]
                  (contains? written-s e) (conj [e :world/written? true])))
              (:counts derived-touches))
      (keep (fn [[e v]] (when (and (integer? e) (number? v) (pos? (double v))) [e :world/energy (double v)])) (:energy derived-touches))
      (keep (fn [[e v]] (when (and (integer? e) (number? v) (pos? (double v))) [e :world/frontier (double v)])) (:novel-frontier derived-touches))
      (keep (fn [[e v]] (when (and (integer? e) (number? v) (pos? (double v))) [e :world/continuation true])) (:continuation derived-touches))
      (keep #(when (integer? %) [% :turn/error-schema true]) (:error-schemas te))
      (when (and aeid (:has-error? te)) [[aeid :turn/has-error? true]])
      (when (and aeid (:large-result? te)) [[aeid :turn/large-result? true]])
      extra-facts)
     {:rule :seed/world :stratum 0})))

(defn build-unmatched-neighbors
  "Computes `:unmatched` graph neighbors discovered around `world` that no context rule emitted."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map [:set :any] :boolean] [:vector :map]]
   :cost/ticks 8000000}
  [main-db branch-db world matched-eids include-hop2?]
  (let [same? (same-db? main-db branch-db)
        ix (:entity-index (fit/cached-registry-info branch-db))
        nb-meta (:neighbor-meta world)
        feid (:focus-eid world)
        pool (cond-> (vec (sort (:hop1-eids world)))
               include-hop2? (into (sort (:hop2-eids world))))]
    (vec
     (keep (fn [e]
             (when (and (integer? e) (not= e feid) (not (contains? matched-eids e)))
               (let [bds (d/datoms branch-db :eavt e)
                   ds (if (seq bds) bds (when-not same? (d/datoms main-db :eavt e)))]
               (when (seq ds)
                 (let [ent (reduce (fn [m dt]
                                     (let [v (:v dt)]
                                       (assoc m (:a dt) (if (and (string? v) (> (count v) 120)) (subs v 0 120) v))))
                                   {:db/id e} ds)
                       ms (six/most-specific ix ent)
                       sk (or (when (qualified-keyword? ms) ms)
                              (:schema (first (six/facets-of ix ent))))
                       pol (or (when sk (six/schema-property ix sk :seon.schema/context-policy))
                               (if sk :default :unassigned))
                       m (get nb-meta e)]
                   {:entity e
                    :ref (or (rdata/entity-lookup-ref ent) [:db/id e])
                    :schema sk
                    :label (rdata/entity-short-label ent)
                    :hop (long (or (:hop m) 1))
                    :via-attr (:via-attr m)
                    :policy pol})))))
           pool))))

(defn build-rule-trace
  "Builds sorted per-rule execution trace entries for `project-entity`."
  {:malli/schema [:=> [:cat [:sequential :map] :map [:set :any]] [:vector :map]]
   :cost/ticks 1000000}
  [all-rs runs-map disabled]
  (mapv (fn [r]
          (let [rid (:seon.rule/id r)
                run (get runs-map rid)]
            {:rule rid
             :stratum (long (or (:seon.rule/stratum r) 50))
             :ms (double (or (:ms run) 0.0))
             :matched-count (long (or (:fired run) 0))
             :emitted-eids (vec (distinct (keep :entity (:projections run))))
             :disabled? (boolean (contains? disabled rid))}))
        (sort-by (juxt #(long (or (:seon.rule/stratum %) 50)) #(str (:seon.rule/id %))) all-rs)))

(defn fix-matches-scope?
  "Delegates to `seon.ctx.ground/fix-matches-scope?`."
  {:malli/schema [:=> [:cat :any :any :any :any [:? :any]] :boolean]
   :cost/ticks 100000}
  ([scope error-class change-summary regression-test]
   (ground/fix-matches-scope? scope error-class change-summary regression-test))
  ([scope error-class change-summary regression-test ctx-text]
   (ground/fix-matches-scope? scope error-class change-summary regression-test ctx-text)))
