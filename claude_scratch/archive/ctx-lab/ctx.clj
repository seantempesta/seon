;; The ctx atom lab (notes/design-ctx-atom-2026-10-25.md): evaluated in a lab agent of the ctx-lab
;; worktree's root (port 8230), loaded with (load-file ".../notes/ctx-lab/ctx.clj"). A prototype to
;; measure, not code for src.
(require '[datahike.api :as d] '[seon.contract :as contract] '[seon.render :as render]
         '[seon.cost :as cost] '[seon.agent :as agent] '[malli.core :as m] '[clojure.string :as str])

;; ---- 1. db -> map: one pull pattern derived from the schemas' render properties ----------------

(defn fwd "pull's reverse ref :ns/_a read forward" [a]
  (if (str/starts-with? (name a) "_") (keyword (namespace a) (subs (name a) 1)) a))
(defn rev? [a] (not= a (fwd a)))

(defn unit-schema "the entity schema a unit attribute leads to" [a]
  (if (rev? a) (get render/entity-schemas (fwd a))
      ;; a forward ref: the schema naming the target's identity (by attribute name's ns)
      (get {:seon.run/task :seon.task/task-fact :seon.eval/agent :seon.db/agent-fact} a)))

(defn ident-of "an entity schema's identity attribute" [s]
  (first (filter #(get-in (d/schema (agent/db)) [% :db/unique]) (map first (drop 2 (get contract/forms s))))))

(def ^:dynamic *depth* 3)
(defn pattern
  "Entity schema s as one pull pattern: its relevant keys (:seon.render/keys, else *), and each unit
  (:seon.render/units, :seon.render/parts) a nested pattern, by the unit's own schema."
  ([s] (pattern s *depth*))
  ([s depth]
   (let [p (contract/properties s)
         units (distinct (concat (:seon.render/units p) (:seon.render/parts p)))]
     (into (if-let [ks (:seon.render/keys p)] (into [:db/id] ks) ['*])
           (for [u units :let [us (unit-schema u)]]
             (if (and us (pos? depth)) {u (pattern us (dec depth))} u))))))

;; the agent's own record also has its turns (via its runs) and messages; evals are :seon.eval/_agent.

;; ---- the shape: a reverse-ref collection is a map keyed by its members' identity ----------------

(defn keyed
  "A pulled tree with each reverse-ref collection made a map {identity-value member}, recursively:
  the members are addressable (get-in, dissoc) by the identity the schema declares."
  [s tree]
  (let [p (contract/properties s)
        units (distinct (concat (:seon.render/units p) (:seon.render/parts p)))]
    (reduce (fn [t u]
              (let [us (unit-schema u) v (get t u)]
                (cond
                  (nil? v) t
                  (and us (rev? u) (sequential? v))
                  (let [id (ident-of us)]
                    (assoc t u (into (sorted-map) (map (fn [x] [(get x id) (keyed us x)])) v)))
                  (and us (map? v)) (assoc t u (keyed us v))
                  :else t)))
            tree units)))

(defn tree [eid] (keyed :seon.db/agent-fact (d/pull (agent/db) (pattern :seon.db/agent-fact) eid)))

;; the identity is always in the pattern (the agent schema's :seon.render/keys leaves out its key)
(defn pattern+ [s] (let [p (pattern s)] (into [(ident-of s)] p)))
(defn tree [eid] (keyed :seon.db/agent-fact (d/pull (agent/db) (pattern+ :seon.db/agent-fact) eid)))

;; ---- a long session, production-shaped, on this agent's branch ---------------------------------

(defn me [] (:e (first (d/datoms (agent/db) :avet :seon.agent/key (:key @agent/state)))))
(defn my-key [] (:v (first (d/datoms (agent/db) :eavt (me) :seon.agent/key))))

(defn response [i]
  {:handle (symbol "result" (str "e" i)) :ms 1.5 :ticks (* 1000 i) :bytes (* 64 i)
   :value {:task "lab/ctx" :turn (quot i 3) :proofs (vec (for [j (range 6)] {:test (symbol "app.x-test" (str "t" j)) :status (if (odd? (+ i j)) :pass :fail)
                                                                         :assert (str "expected (= 3 (f " j ")) got " (+ i j))}))
           :changes {:vars (vec (for [j (range 5)] (symbol "app.x" (str "f" j)))) :lines (+ 10 i)}
           :out (apply str (repeat 8 "a line of output from the form, as printed\n"))}})

(defn seed!
  "n evals by the model of a long session (each a production-shaped response, ~1.6 KB), a task, a
  run, n/3 turns with prose, n/20 messages: written on this branch in batches of 100."
  [n]
  (let [k (my-key) run "lab/ctx#1"]
    (agent/transact! [{:seon.task/id "lab/ctx" :seon.task/ns 'app.x :seon.task/name "ctx lab" :seon.task/plan "Make app.x/f0 pass its tests."
                       :seon.task/execution :running :seon.task/branch :ctx-lab1 :seon.task/kind :work
                       :seon.task/proofs #{'app.x-test/t0 'app.x-test/t1}}
                      {:seon.run/id run :seon.run/task [:seon.task/id "lab/ctx"] :seon.run/status :running :seon.run/boot "lab"
                       :seon.run/start-ms 0 :seon.run/agent [:seon.agent/key k]}])
    (doseq [part (partition-all 100 (range 1 (inc n)))]
      (agent/transact!
       (vec (mapcat (fn [i]
                      (cond-> [{:seon.eval/id (str "s" i) :seon.eval/agent [:seon.agent/key k] :seon.eval/by "a1"
                                :seon.eval/record {:code (str "(app.x/f" (mod i 5) " " i ")") :response (response i)}}]
                        (zero? (mod i 3)) (conj {:seon.turn/id (str run "/" (quot i 3)) :seon.turn/run [:seon.run/id run] :seon.turn/n (quot i 3)
                                                 :seon.turn/reply (apply str "I will check f0 against its failing assertion, then edit it. " (repeat 6 "More prose. "))
                                                 :seon.turn/forms 3 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 900})
                        (zero? (mod i 20)) (conj {:seon.message/id (str "m" i) :seon.message/from "user" :seon.message/to [:seon.run/id run]
                                                  :seon.message/body "How is it going? Check t3 too."})))
                    part))))
    (agent/commit!)
    {:evals (count (d/datoms (agent/db) :aevt :seon.eval/id))}))

;; the identity of each entity schema, read once (it is the schema's, not the db's)
(def ident-of (memoize ident-of))
(defn keyed
  [s tree]
  (let [p (contract/properties s)
        units (distinct (concat (:seon.render/units p) (:seon.render/parts p)))]
    (reduce (fn [t u]
              (let [us (unit-schema u) v (get t u)]
                (cond
                  (nil? v) t
                  (and us (rev? u) (sequential? v))
                  (let [id (ident-of us)]
                    (assoc t u (persistent! (reduce (fn [m x] (assoc! m (get x id) (keyed us x))) (transient {}) v))))
                  (and us (map? v)) (assoc t u (keyed us v))
                  :else t)))
            tree units)))
(defn seed-more! "n more evals after the first from (same shapes)" [from n]
  (let [k (my-key) run "lab/ctx#1"]
    (doseq [part (partition-all 100 (range from (+ from n)))]
      (agent/transact!
       (vec (mapcat (fn [i]
                      (cond-> [{:seon.eval/id (str "s" i) :seon.eval/agent [:seon.agent/key k] :seon.eval/by "a1"
                                :seon.eval/record {:code (str "(app.x/f" (mod i 5) " " i ")") :response (response i)}}]
                        (zero? (mod i 3)) (conj {:seon.turn/id (str run "/" (quot i 3)) :seon.turn/run [:seon.run/id run] :seon.turn/n (quot i 3)
                                                 :seon.turn/reply (apply str "I will check f0 against its failing assertion, then edit it. " (repeat 6 "More prose. "))
                                                 :seon.turn/forms 3 :seon.turn/errors 0 :seon.turn/red 1 :seon.turn/ms 900})))
                    part))))
    (agent/commit!)
    {:evals (count (d/datoms (agent/db) :aevt :seon.eval/id))}))

;; ---- 2. keeping it current: a transaction's datoms as a patch of the map, O(change) ----------

(defn units-of "schema s's units: {:rev {fwd-attr unit} :fwd #{unit}}" [s]
  (let [p (contract/properties s) us (distinct (concat (:seon.render/units p) (:seon.render/parts p)))]
    {:rev (into {} (for [u us :when (rev? u)] [(fwd u) u]))
     :fwd (set (remove rev? us))
     :keys (when-let [ks (:seon.render/keys p)] (set (concat ks [(ident-of s)])))}))
(def units-of (memoize units-of))

(defn index-paths
  "{eid [path schema]} of every entity in keyed tree t of schema s at path p."
  ([s t] (index-paths s t [] {}))
  ([s t p ix]
   (let [ix (assoc ix (:db/id t) [p s])
         {:keys [rev fwd]} (units-of s)]
     (as-> ix ix
       (reduce (fn [ix u] (let [us (unit-schema u)]
                            (reduce-kv (fn [ix id m] (index-paths us m (conj p u id) ix)) ix (get t u))))
               ix (vals rev))
       (reduce (fn [ix u] (let [v (get t u)] (if (and (map? v) (:db/id v)) (index-paths (unit-schema u) v (conj p u) ix) ix)))
               ix fwd)))))

(defn unindex [ix t] (reduce (fn [ix [e]] (dissoc ix e)) ix (filter (fn [[_ [p]]] true) (select-keys ix (keep :db/id (tree-seq map? vals t))))))

(defn- one-attr [db a] (let [s (get (d/schema db) a)] {:many? (= :db.cardinality/many (:db/cardinality s)) :ref? (= :db.type/ref (:db/valueType s))}))

(defn patch
  "ctx {:tree :ix} with datoms (a transaction's :tx-data, or a datoms-diff's) applied on db (the db
  after them): each datom of a known entity sets or removes its attribute (a set member for
  cardinality many, {:db/id v} for a ref, a pulled subtree for a forward unit); an unknown entity
  whose datom refs a known one by a unit's attribute joins it, pulled whole by that unit's pattern;
  a member whose identity is retracted leaves. Work: per datom one map lookup and one assoc-in,
  plus the pull of each entity that joins."
  [{:keys [tree ix] :as ctx} db datoms]
  (reduce
   (fn [{:keys [tree ix] :as ctx} [e ds]]
     (if-let [[p s] (get ix e)]
       (let [{:keys [fwd keys]} (units-of s) id (ident-of s)
             step (fn [{:keys [tree ix] :as c} dt]
                    (let [a (:a dt) v (:v dt) add? (:added dt) path (conj p a)]
                      (cond
                        (and keys (not (keys a)) (not (fwd a))) c   ; an attribute the view leaves out
                        (and (fwd a) (:ref? (one-attr db a))) (if add?
                                  (let [us (unit-schema a) sub (keyed us (d/pull db (pattern+ us) v))]
                                    {:tree (assoc-in tree path sub) :ix (index-paths us sub path ix)})
                                  (assoc c :tree (update-in tree p dissoc a)))
                        (and (= a id) (not add?) (seq p))      ; its identity retracted: the member leaves
                        {:tree (update-in tree (pop p) dissoc (peek p)) :ix (unindex ix (get-in tree p))}
                        :else
                        (let [{:keys [many? ref?]} (one-attr db a) v (if ref? {:db/id v} v)]
                          (assoc c :tree
                                 (cond many? (if add? (update-in tree path (fnil conj #{}) v)
                                                 (let [r (disj (get-in tree path) v)] (if (seq r) (assoc-in tree path r) (update-in tree p dissoc a))))
                                       add? (assoc-in tree path v)
                                       (= v (get-in tree path)) (update-in tree p dissoc a)
                                       :else tree))))))]
         ;; retractions first: a card-one change is [retract old, add new] in one transaction
         (reduce step ctx (concat (remove :added ds) (filter :added ds))))
       ;; unknown: does one of its datoms make it a member of a known entity?
       (if-let [[pp ps u] (some (fn [dt] (when (:added dt)
                                           (when-let [[pp ps] (get ix (:v dt))]
                                             (when-let [u (get (:rev (units-of ps)) (:a dt))] [pp ps u]))))
                                ds)]
         (let [us (unit-schema u) m (keyed us (d/pull db (pattern+ us) e)) path (conj pp u (get m (ident-of us)))]
           {:tree (assoc-in tree path m) :ix (index-paths us m path ix)})
         ctx)))
   ctx
   (group-by :e datoms)))

(defn ctx0 [] (let [t (tree (me))] {:tree t :ix (index-paths :seon.db/agent-fact t)}))

(defn transact-diff!
  "tx written by agent/transact!, answered with the datoms it changed (the datoms-diff of the db
  before and after) and what each step cost"
  [tx]
  (let [before (agent/db) _ (agent/transact! tx) after (agent/db)
        [gone added] (d/datoms-diff before after :eavt [])]
    {:before before :after after :datoms (concat (map #(assoc % :added false) gone) added)}))
(defn dm [d added] {:e (:e d) :a (:a d) :v (:v d) :added added})
(defn transact-diff! [tx]
  (let [before (agent/db) _ (agent/transact! tx) after (agent/db)
        [gone added] (d/datoms-diff before after :eavt [])]
    {:before before :after after :datoms (vec (concat (map #(dm % false) gone) (map #(dm % true) added)))}))

(def n (atom 5000))
(defn new-eval-tx [] (let [i (swap! n inc)] [{:seon.eval/id (str "s" i) :seon.eval/agent [:seon.agent/key (my-key)] :seon.eval/by "a1"
                                               :seon.eval/record {:code (str "(app.x/f1 " i ")") :response (response i)}}]))

;; pull answers a cardinality-many attribute as a vector; the schema says [:set T]: the map holds a set
(def ^:dynamic *schema* nil)
(defn many-sets [m] (let [sch (or *schema* (d/schema (agent/db)))]
                      (reduce-kv (fn [m a v] (if (and (vector? v) (= :db.cardinality/many (get-in sch [a :db/cardinality])) (not= :db.type/ref (get-in sch [a :db/valueType])))
                                               (assoc m a (set v)) m)) m m)))
(defn keyed
  [s tree]
  (let [p (contract/properties s)
        units (distinct (concat (:seon.render/units p) (:seon.render/parts p)))]
    (reduce (fn [t u]
              (let [us (unit-schema u) v (get t u)]
                (cond
                  (nil? v) t
                  (and us (rev? u) (sequential? v))
                  (let [id (ident-of us)]
                    (assoc t u (persistent! (reduce (fn [m x] (assoc! m (get x id) (keyed us x))) (transient {}) v))))
                  (and us (map? v)) (assoc t u (keyed us v))
                  :else t)))
            (many-sets tree) units)))

;; the schema read once per build, not once per entity
(defn tree [eid] (binding [*schema* (d/schema (agent/db))] (keyed :seon.db/agent-fact (d/pull (agent/db) (pattern+ :seon.db/agent-fact) eid))))
(alter-var-root #'keyed (fn [k] (fn [s t] (if *schema* (k s t) (binding [*schema* (d/schema (agent/db))] (k s t))))))
