(ns seon.ctx.ground
  "Pure generic entity hydration, lookup resolution, unread message filtering,
   scope fix matching, branch drift detection, and `seon.vector` cosine scoring for `seon.ctx`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx.diff :as diff]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks :refer [db-schema live-cid]]
            [seon.schema.index :as six]
            [seon.store :as store]
            [seon.vector :as vec]
            [seon.render.fit :as fit]))

(defn resolve-lookup-eid
  "Resolves an integer EID, `[:db/id eid]`, or `[attr val]` lookup ref against `branch-db` first, falling back to `main-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :any] [:maybe :int]]
   :cost/ticks 200000}
  [main-db branch-db ref]
  (cond
    (integer? ref) (long ref)
    (and (vector? ref) (= 2 (count ref)) (= :db/id (first ref)) (integer? (second ref)))
    (long (second ref))
    (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)))
    (let [[a v] ref]
      (or (when (and branch-db (contains? (db-schema branch-db) a))
            (:e (first (d/datoms branch-db :avet a v))))
          (when (and main-db (not= main-db branch-db) (contains? (db-schema main-db) a))
            (:e (first (d/datoms main-db :avet a v))))))))

(defn attach-semantic-scores
  "Enriches `candidates` with live `:semantic` cosine scores (< 1 ms, zero IPC)
   against `anchor` (focal lookup ref or agent entity map) when stored vectors exist."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :any [:vector :map]] [:vector :map]]
   :cost/ticks 15000000}
  [main-db _branch-db anchor candidates]
  (if (or (nil? main-db) (empty? candidates) (not ((db-schema main-db) :seon.vector/of)))
    candidates
    (let [ag (cond
               (map? anchor) anchor
               (and (vector? anchor) (= :seon.agent/id (first anchor)))
               (rdata/pull-agent-entity main-db main-db (second anchor))
               :else nil)
          qv (or (when (and (vector? anchor) (= 2 (count anchor)) (not= :seon.agent/id (first anchor)))
                   (vec/vector-of main-db anchor))
                 (when ag
                   (let [tid (first (rdata/agent-task-ids main-db ag))
                         sc (:seon.agent/scope ag)
                         p0 (first (:seon.agent/pinned ag))]
                     (or (when tid (vec/vector-of main-db [:seon.task/id tid]))
                         (when sc (vec/vector-of main-db [:seon.code.ns/name sc]))
                         (when (vector? p0) (vec/vector-of main-db p0))))))]
      (if (empty? qv)
        candidates
        (let [eids (vec (distinct (filter integer? (map :entity candidates))))
              smap (if (seq eids) (vec/semantic-scores-for main-db qv eids) {})]
          (if (empty? smap)
            candidates
            (mapv #(if-let [s (get smap (:entity %))] (assoc % :semantic (double s)) %) candidates)))))))

(def ^:private broad-stop-words
  #{"seon" "code" "user" "summary" "entity" "file" "form" "data" "test" "line" "name" "plan" "list" "view"
    "agent" "task" "branch" "main" "rule" "rules" "schema" "error" "errors" "render" "context" "live" "demo"
    "with" "from" "that" "this" "into" "over" "more" "when" "where" "what" "your" "only" "also" "just" "each"
    "make" "work" "system" "status" "health" "wire" "surface" "graph" "driven" "interactive" "perspective"})

(defn fix-matches-scope?
  "True when a `:seon.fix/*` record matches a narrower dotted namespace `scope`, or (on broad `'seon`/`nil` scope)
   matches an explicit error-class or >= 2 distinctive tokens in optional `ctx-text`."
  {:malli/schema [:=> [:cat :any :any :any :any [:? :any]] :boolean]
   :cost/ticks 500000}
  ([scope error-class change-summary regression-test]
   (fix-matches-scope? scope error-class change-summary regression-test nil))
  ([scope error-class change-summary regression-test ctx-text]
   (let [sn (when (symbol? scope) (name scope))
         hay (str error-class " " change-summary " " regression-test)]
     (if (and sn (not= sn "seon") (str/includes? sn "."))
       (str/includes? hay sn)
       (if (or (nil? ctx-text) (str/blank? (str ctx-text)))
         false
         (let [ctx-lc (str/lower-case (str ctx-text))
               hay-lc (str/lower-case hay)
               ec-hit? (when (keyword? error-class)
                         (or (str/includes? ctx-lc (str/lower-case (str error-class)))
                             (let [en (str/lower-case (name error-class))]
                               (and (>= (count en) 5) (str/includes? ctx-lc en)))))]
           (if ec-hit?
             true
             (let [c-toks (->> (re-seq #"[a-z][a-z0-9_-]{4,}" ctx-lc)
                               (remove broad-stop-words)
                               distinct)]
               (>= (count (filter #(str/includes? hay-lc %) c-toks)) 2)))))))))

(defn ^:seon.flow/edge branch-form-drift
  "Computes O(|branch-edits|) edited form keys, hashes, and true concurrent `:main` drift via `diff/fast-merge-base`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:sequential :int]] :map]
   :cost/ticks 5000000}
  [main-db branch-db edited-eids]
  (let [pairs (vec (keep (fn [e]
                           (when-let [fk (:v (first (d/datoms branch-db :eavt e :seon.code.form/key)))]
                             [fk (:v (first (d/datoms branch-db :eavt e :seon.code.form/hash)))]))
                         edited-eids))
        hashes (into {} pairs)
        edited (mapv first pairs)
        mc (store/live-cid main-db)
        bc (store/live-cid branch-db)
        fhash (fn [db fk]
                (when (and db ((db-schema db) :seon.code.form/key))
                  (when-let [e (:e (first (d/datoms db :avet :seon.code.form/key fk)))]
                    (:v (first (d/datoms db :eavt e :seon.code.form/hash))))))
        drifted (if (or (empty? pairs) (nil? main-db) (nil? mc) (nil? bc) (= mc bc))
                  []
                  (let [base-cid (diff/fast-merge-base main-db branch-db)]
                    (if (and base-cid (not= base-cid mc))
                      (if-let [base-db (try (d/commit-as-db branch-db base-cid) (catch Throwable _ nil))]
                        (vec (keep (fn [[fk th]]
                                     (let [bh (fhash base-db fk) mh (fhash main-db fk)]
                                       (when (and (not= mh bh) (not= mh th)) fk)))
                                   pairs))
                        [])
                      [])))]
    {:edited edited :hashes hashes :drifted drifted}))

(defn legacy-failing-digests
  "Fallback for minimal synthetic test stores with ZERO `:seon.test.run/branch` entities; O(1) empty on live stores."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] [:vector :string]]
   :cost/ticks 2000000}
  [main-db branch-db]
  (let [bdh (db-schema branch-db) mdh (db-schema main-db)]
    (if (or (when (and branch-db (bdh :seon.test.run/branch)) (first (d/datoms branch-db :aevt :seon.test.run/branch)))
            (when (and main-db (mdh :seon.test.run/branch)) (first (d/datoms main-db :aevt :seon.test.run/branch))))
      []
      (if (and branch-db (bdh :seon.test/pass?) (bdh :seon.test/var-ref) (bdh :seon.test/digest))
        (vec (distinct
              (keep (fn [dt]
                      (when (false? (:v dt))
                        (let [e (:e dt) tx (long (:tx dt 0))
                              vr (:v (first (d/datoms branch-db :eavt e :seon.test/var-ref)))
                              dg (:v (first (d/datoms branch-db :eavt e :seon.test/digest)))
                              cleared? (when vr
                                         (some (fn [vdt]
                                                 (when-let [pdt (first (d/datoms branch-db :eavt (:e vdt) :seon.test/pass?))]
                                                   (and (true? (:v pdt)) (>= (long (:tx pdt 0)) tx))))
                                               (d/datoms branch-db :avet :seon.test/var-ref vr)))]
                          (when (and vr dg (not cleared?)) dg))))
                    (d/datoms branch-db :aevt :seon.test/pass?))))
        []))))

(defn merge-pull-patterns
  "Pure, commutative, associative, deterministic union of Datahike `:pull` patterns."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]]
   :cost/ticks 500000}
  [patterns]
  (let [step (fn [{:keys [star? scalars nested other] :as acc} clause]
               (cond
                 (= '* clause) (assoc acc :star? true)
                 (keyword? clause) (update acc :scalars conj clause)
                 (map? clause)
                 (reduce-kv (fn [a k sub]
                              (if (sequential? sub) (update-in a [:nested k] (fnil conj []) (vec sub)) a))
                            acc clause)
                 (some? clause) (update acc :other conj clause)
                 :else acc))
        {:keys [star? scalars nested other]}
        (reduce (fn [acc pat] (reduce step acc pat))
                {:star? false :scalars #{} :nested {} :other #{}}
                patterns)
        merged-nested (into {} (map (fn [[k subs]] [k (merge-pull-patterns subs)])) nested)
        nested-vec (mapv (fn [[k sub]] {k sub}) (sort-by (comp str key) merged-nested))
        scalar-vec (if star? ['*] (vec (sort (remove #(contains? merged-nested %) scalars))))]
    (vec (concat scalar-vec nested-vec (sort-by pr-str other)))))

(defn message-unread?
  "Pure predicate: true when `msg-id` has not been recorded in `agent-id`'s seen-message set on `branch-db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword] :any] :boolean]
   :cost/ticks 2000000}
  [branch-db agent-id msg-id]
  (boolean
   (and (some? msg-id)
        (or (nil? branch-db) (nil? agent-id)
            (not (contains? (store/agent-seen-message-ids branch-db agent-id) msg-id))))))

(defn unread-message-eids
  "Returns `[eid tx]` pairs for unread `:seon.agent.message/*` addressed to `agent-id` in O(M + E)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:vector [:tuple :int :int]]]
   :cost/ticks 5000000}
  [main-db branch-db agent-id]
  (let [bdb (or branch-db main-db)]
    (if-not (and agent-id (or main-db bdb))
      []
      (let [seen (if bdb (store/agent-seen-message-ids bdb agent-id) #{})
            scan (fn [db]
                   (when (and db (contains? (db-schema db) :seon.agent.message/to))
                     (keep (fn [dt]
                             (let [e (:e dt)
                                   mid (:v (first (d/datoms db :eavt e :seon.agent.message/id)))]
                               (when (and mid (not (contains? seen mid)))
                                 [mid e (long (or (:tx dt) 0))])))
                           (d/datoms db :avet :seon.agent.message/to agent-id))))
            same? (or (= main-db bdb) (and (live-cid main-db) (= (live-cid main-db) (live-cid bdb))))
            raw (concat (scan main-db) (when-not same? (scan bdb)))
            dedup (reduce (fn [acc [mid e tx]]
                            (if (contains? acc mid) acc (assoc acc mid [e tx])))
                          {} raw)]
        (vec (sort-by second (vals dedup)))))))

(defn- merge-opt-num [a b]
  (cond (and (number? a) (number? b)) (min (long a) (long b))
        (number? a) (long a)
        (number? b) (long b)
        :else nil))

(defn merge-raw-candidates
  "Collapses duplicate candidate maps per `:entity`, merging `:pull` patterns, `:fn`, `:params`, `:rules`, `:tags`, and max priority/tx/signals."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]
   :cost/ticks 5000000}
  [all-raw]
  (reduce (fn [acc it]
            (let [e (:entity it)]
              (if-let [prev (get acc e)]
                (let [pulls (keep #(when (sequential? (:pull %)) (:pull %)) [prev it])
                      merged-pull (when (seq pulls) (merge-pull-patterns pulls))
                      p-pri (long (:priority prev 50))
                      i-pri (long (:priority it 50))
                      win-fn (cond (and (:fn it) (>= i-pri p-pri)) (:fn it)
                                   (:fn prev) (:fn prev)
                                   :else (:fn it))
                      mt (merge-opt-num (:max-tokens prev) (:max-tokens it))
                      bg (merge-opt-num (:budget prev) (:budget it))]
                  (assoc acc e
                         (-> prev
                             (assoc :priority (max p-pri i-pri)
                                    :stratum (min (long (or (:stratum prev) 50)) (long (or (:stratum it) 50)))
                                    :tx (max (long (:tx prev 0)) (long (:tx it 0)))
                                    :zone (cond (or (= :agent-prefix (:zone prev)) (= :agent-prefix (:zone it))) :agent-prefix
                                                (= :static-prefix (:zone prev)) :static-prefix
                                                (= :static-prefix (:zone it)) :static-prefix
                                                :else (or (:zone prev) (:zone it) :workspace))
                                    :pinned? (boolean (or (:pinned? prev) (:pinned? it)))
                                    :rules (vec (distinct (concat (:rules prev) (:rules it))))
                                    :tags (into (set (:tags prev)) (:tags it))
                                    :params (merge (:params prev) (:params it)))
                             (cond-> (seq merged-pull) (assoc :pull merged-pull)
                               win-fn (assoc :fn win-fn)
                               mt (assoc :max-tokens mt)
                               bg (assoc :budget bg)
                               (:ctx.match/score it) (assoc :ctx.match/score (:ctx.match/score it))
                               (or (:touches prev) (:touches it)) (assoc :touches (max (long (:touches prev 0)) (long (:touches it 0))))
                               (or (:touch-energy prev) (:touch-energy it))
                               (assoc :touch-energy (max (double (or (:touch-energy prev) 0.0)) (double (or (:touch-energy it) 0.0))))
                               (or (:frontier-score prev) (:frontier-score it))
                               (assoc :frontier-score (max (double (or (:frontier-score prev) 0.0)) (double (or (:frontier-score it) 0.0))))
                               (or (:written? prev) (:written? it)) (assoc :written? true)))))
                (assoc acc e it))))
          {} all-raw))

(defn- effective-pull-pattern [pull-pat]
  (if (and (sequential? pull-pat) (seq pull-pat))
    (if (some #{'* :db/id} pull-pat) (vec pull-pat) (into [:db/id] pull-pat))
    '[*]))

(defn hydrate-candidates
  "Pulls and lightly enriches merged candidates honoring rule `:pull` (else `[*]`), resolving `:ref` and `:schema`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :seon.host/db] :map [:maybe :map]] [:vector :map]]
   :cost/ticks 25000000}
  [main-db branch-db task-db merged opts]
  (let [o (if (map? opts) opts {})
        inc-evals? (if (contains? o :include-evals?) (boolean (:include-evals? o)) true)
        enrich? (if (contains? o :enrich?) (boolean (:enrich? o)) true)
        same? (fn [a b] (or (= a b) (let [ca (live-cid a) cb (live-cid b)] (and ca cb (= ca cb)))))
        same-mb? (same? main-db branch-db)
        same-tb? (or (nil? task-db) (same? task-db branch-db))
        ix (:entity-index (fit/cached-registry-info branch-db))
        infer-sk (fn [ent]
                   (when (and (map? ent) (seq (dissoc ent :db/id)))
                     (or (when (instance? clojure.lang.IObj ent) (:seon/schema (meta ent)))
                         (let [ms (six/most-specific ix ent)] (when (qualified-keyword? ms) ms))
                         (:schema (first (six/facets-of ix ent))))))]
    (vec
     (keep (fn [[eid item]]
             (cond
               (and (vector? eid) (= 2 (count eid)) (= :seon.agent/id (first eid)))
               (let [pat '[:db/id :seon.agent/id :seon.agent/branch :seon.agent/goal :seon.agent/label]
                     b (d/pull branch-db pat eid)
                     ent (if (:seon.agent/id b) b (when-not same-mb? (d/pull main-db pat eid)))]
                 (when (:seon.agent/id ent)
                   (assoc item :entity eid :ref eid :schema :seon.agent/entity :data ent)))

               (integer? eid)
               (let [pat (effective-pull-pattern (:pull item))
                     t-b (when-not same-tb? (d/pull task-db pat eid))
                     b (d/pull branch-db pat eid)
                     use-t? (boolean (and (map? t-b) (:seon.task/id t-b)))
                     from-b? (boolean (and (not use-t?) (seq (dissoc b :db/id))))
                     ent (cond use-t? t-b from-b? b same-mb? nil :else (d/pull main-db pat eid))
                     src-db (cond use-t? task-db from-b? branch-db :else main-db)]
                 (when (and (map? ent) (seq (dissoc ent :db/id)) (or inc-evals? (not (:seon.agent.eval/id ent))))
                   (let [ep (when enrich?
                              (rlinks/enrich-entity-params
                               src-db ent {:show-callers? (boolean (get-in item [:params :seon.ui/show-callers?]))}))
                         etx (max (long (:tx item 0))
                                  (reduce max 0 (map #(long (or (:tx %) 0)) (d/datoms src-db :eavt eid))))
                         ref (or (rdata/entity-lookup-ref ent) [:db/id eid])
                         sk (or (:schema item) (infer-sk ent))]
                     (cond-> (assoc item :entity eid :ref ref :data ent :tx etx)
                       sk (assoc :schema sk)
                       (seq ep) (update :params merge ep)))))))
           merged))))