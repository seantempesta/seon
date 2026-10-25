(ns seon.ctx
  "The agent's record as one map, `@ctx` (docs/ctx.md): its
  agent entity whole, and the units its entity schemas declare (:seon.render/units), each a
  nested map: a reverse-ref unit a map of its members by their identity under the unit's short
  key (:seon.eval/_agent is :evals, :seon.run/_agent :runs, :seon.turn/_run :turns,
  :seon.message/_to :messages), a forward-ref unit its entity under the attribute's name
  (:seon.run/task is :task). Beside them its environment, :t :branch :commit.

  Built once from the branch's db when the agent's state first holds it (init, resume, a clone),
  then kept by a patch from each transaction's datoms: the work of the change, never of the
  session (seon.ctx-test/the-patch-costs-the-change-not-the-session). Nothing is built on a read:
  `@ctx` is a deref. The value carries {:seon.render/schema :seon.ctx/ctx} as metadata: the
  model reads it through the one printer as any value, and the page draws it as a block
  (:seon.ctx/ctx's declared html render). A swap! of it is written to the branch as one checked
  transaction, or refused with why (write!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.render :as render] [seon.agent :as agent]
            [seon.schema.datahike :as sd] [seon.db :as pdb] [datahike.api :as d] [datahike.db.interface :as dbi] [clojure.string :as str] [malli.core :as m] [malli.error :as me] [malli.registry :as mr]))

;; ---- the shape, derived once from the registry -----------------------------------------------

(defn- forward
  "Attribute a read forward: pull's reverse ref :ns/_name as :ns/name."
  {:malli/schema [:=> [:cat :qualified-keyword] :qualified-keyword]}
  [a] (if (str/starts-with? (name a) "_") (keyword (namespace a) (subs (name a) 1)) a))

(defn- short-key
  "A unit's key in the map: a reverse ref is the plural of the thing that holds it
  (:seon.eval/_agent, :evals), a forward one its attribute's name (:seon.run/task, :task)."
  {:malli/schema [:=> [:cat :qualified-keyword] :keyword]}
  [u] (if (= u (forward u)) (keyword (name u)) (keyword (str (last (str/split (namespace u) #"\.")) "s"))))

(def ^:private identities
  "{entity-schema identity}: the upsert key each entity schema names (its members' key in their
  unit's map)."
  (into {} (for [[a s] render/entity-schemas :when (:seon.db/identity (contract/properties a))] [s a])))

(def units
  "{entity-schema [unit]}: each ref unit its schema declares, as {:attr (forward) :key :rev?
  :schema (a reverse unit's members' schema) :id (their identity)}. A unit holding no ref
  (:seon.task/proofs) is the entity's own attribute and stays one."
  (into {} (for [[s _] contract/forms
                 :let [us (:seon.render/units (contract/properties s))]
                 :when (and (:seon.db/attributes (contract/properties s)) (seq us))]
             [s (vec (for [u us :let [f (forward u) rev? (not= u f)]
                           :when (or rev? (= :db.type/ref (:db/valueType (sd/declaration contract/registry f))))]
                       (cond-> {:attr f :key (short-key u) :rev? rev?}
                         rev? (assoc :schema (render/entity-schemas f) :id (identities (render/entity-schemas f))))))])))

;; ---- db -> map ---------------------------------------------------------------------------

(defn- own
  "Entity e on db, every attribute it holds: a cardinality-many one as a set (pull answers a
  vector), a ref as {:db/id}."
  {:malli/schema [:=> [:cat :seon.host/db pos-int?] :map]}
  [db e]
  (let [many (get (dbi/-rschema db) :db.cardinality/many #{}) m (d/pull db '[*] e)]
    (reduce-kv (fn [m a v] (if (and (contains? many a) (sequential? v)) (assoc m a (set v)) m)) m m)))

(defn- key-of
  "Member e's key in its unit's map: the value of its identity id on db."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :qualified-keyword] :any]}
  [db e id] (:v (first (d/datoms db :eavt e id))))

(defn- node
  "Entity e as ctx holds it, schema s (else the one its identity names), with its units nested;
  and ix, {eid #{[path schema]}} (one entity can sit at several paths: each run of a task holds
  the task), gaining every entity it holds under path p."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? [:maybe :qualified-keyword] [:vector :any] :map] [:tuple :map :map]]}
  [db e s p ix]
  (let [m (own db e)
        s (or s (render/entity-schema m nil))
        ix (update ix e (fnil conj #{}) [p s])]
    (reduce (fn [[m ix] {:keys [attr key rev? schema id]}]
              (if rev?
                (let [es (map :e (d/datoms db :avet attr e))]
                  (if (empty? es)
                    [m ix]
                    (reduce (fn [[m ix] me] (let [k (key-of db me id) [sub ix] (node db me schema (conj p key k) ix)]
                                              [(assoc-in m [key k] sub) ix]))
                            [m ix] es)))
                (if-let [v (:db/id (get m attr))]
                  (let [[sub ix] (node db v nil (conj p key) ix)] [(-> m (dissoc attr) (assoc key sub)) ix])
                  [m ix])))
            [m ix] (get units s))))

(defn- environment
  "The map's environment: the db's t, the branch and the commit the db is (none for a value as of
  an earlier t: its basis names it)."
  {:malli/schema [:=> [:cat :seon.host/db :any] :map]}
  [db branch] (let [c (d/commit-id db)] (cond-> {:t (:max-tx db) :branch branch} c (assoc :commit c))))

(defn build
  "The agent's record on db as ctx's value {:tree :ix}: agent key k's entity and its units, and
  its environment on branch. O(the record)."
  {:malli/schema [:=> [:cat :seon.host/db :any :any] [:map [:tree :map] [:ix :map]]]}
  [db k branch]
  (let [[tree ix] (if-let [e (and k (pdb/eid db :seon.agent/key k))] (node db e :seon.db/agent-fact [] {}) [{} {}])]
    {:tree (merge tree (environment db branch)) :ix ix}))

;; ---- datoms -> patch ---------------------------------------------------------------------

(defn- under?
  "Is path p at or below path q?"
  {:malli/schema [:=> [:cat [:vector :any] [:vector :any]] :boolean]}
  [p q] (and (<= (count q) (count p)) (= q (subvec p 0 (count q)))))

(defn- unindex
  "ix without the paths at or below q of the entities subtree t (at q) holds."
  {:malli/schema [:=> [:cat :map :any [:vector :any]] :map]}
  [ix t q]
  (reduce (fn [ix e] (let [r (into #{} (remove #(under? (first %) q)) (get ix e))] (if (seq r) (assoc ix e r) (dissoc ix e))))
          ix (distinct (keep :db/id (tree-seq map? vals t)))))

(defn- unit-of
  "The unit of schema s whose attribute is a, or nil."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword] :keyword] [:maybe :map]]}
  [s a] (some #(when (= a (:attr %)) %) (get units s)))

(defn- without
  "Tree t without key a (an attribute, a unit, a member's id) of the map at path p (the record's
  own entity at [])."
  {:malli/schema [:=> [:cat :map [:vector :any] :any] :map]}
  [t p a] (if (seq p) (update-in t p dissoc a) (dissoc t a)))

(defn- leave
  "c without the member (or forward unit) at path p; a unit it empties goes with it."
  {:malli/schema [:=> [:cat :map [:vector :any]] :map]}
  [{:keys [tree ix]} p]
  (let [unit (pop p) t (without tree unit (peek p))
        ;; a unit emptied is no key, as a build holds it
        t (if (and (seq unit) (empty? (get-in t unit))) (without t (pop unit) (peek unit)) t)]
    {:tree t :ix (unindex ix (get-in tree p) p)}))

(defn- join
  "c with entity e of schema s added at path p, whole as db holds it."
  {:malli/schema [:=> [:cat :map :seon.host/db pos-int? [:maybe :qualified-keyword] [:vector :any]] :map]}
  [{:keys [tree ix]} db e s p]
  (let [[sub ix] (node db e s p ix)] {:tree (assoc-in tree p sub) :ix ix}))

(defn- step
  "c with one datom dt of known entity e (at path p, schema s) applied: a ref unit's entity set
  or gone, a member gone with its identity, else the attribute set or retracted."
  {:malli/schema [:=> [:cat :map :seon.host/db [:vector :any] [:maybe :qualified-keyword] :any] :map]}
  [c db p s {:keys [a v added]}]
  (let [many (get (dbi/-rschema db) :db.cardinality/many #{})
        ref? (contains? (get (dbi/-rschema db) :db.type/ref #{}) a)
        u (unit-of s a)
        path (conj p a)]
    (cond
      (and u (not (:rev? u))) (if added
                                (let [q (conj p (:key u))] (join (update c :ix unindex (get-in (:tree c) q) q) db v nil q))
                                (if (= v (get-in (:tree c) (conj p (:key u) :db/id))) (leave c (conj p (:key u))) c))
      (and (seq p) (not added) (= a (identities s))) (leave c p)
      :else
      (let [v (if ref? {:db/id v} v) cur (get-in (:tree c) path)]
        (assoc c :tree
               (cond (contains? many a) (if added
                                          (assoc-in (:tree c) path (conj (or cur #{}) v))
                                          (let [r (disj (or cur #{}) v)] (if (seq r) (assoc-in (:tree c) path r) (without (:tree c) p a))))
                     added (assoc-in (:tree c) path v)
                     (= v cur) (without (:tree c) p a)
                     :else (:tree c)))))))

(defn patch
  "ctx's value c with datoms (a transaction's :tx-data, or a datoms-diff marked :added) applied on
  db, the db after them: each datom of an entity c holds sets or removes its attribute
  (retractions first: a change is a retraction and an assertion), and a datom whose ref links an
  entity to one c holds, by a reverse unit's attribute, joins it to that unit, read whole, or takes
  it out. Entities are taken in any order: one that joins is read from db, so a child read before
  its parent is read again, whole, when the parent joins. Work: a map lookup and an assoc-in per
  datom, and the read of each entity that joins. An identity never changes (an upsert key)."
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :map]] :seon.host/db [:sequential :any] :any] [:map [:tree :map] [:ix :map]]]}
  [c db datoms branch]
  (-> (reduce
       (fn [c [e ds]]
         (let [own (fn [at] (fn [c dt] (reduce (fn [c [p s]] (if (contains? (get (:ix c) e) [p s]) (step c db p s dt) c)) c at)))
               ;; a datom whose ref names a held entity by one of its reverse units' attribute links e
               ;; into that unit (added) or out of it (retracted), at each path the held one sits at
               link (fn [c {:keys [a v added]}]
                      (let [at (get (:ix c) v)]
                        (if-let [{:keys [key schema id]} (when (seq at) (some #(when (and (:rev? %) (= a (:attr %))) %) (get units (second (first at)))))]
                          (let [k (key-of db e id)]
                            (reduce (fn [c [pp _]] (let [q (conj pp key k)]
                                                     (cond (and added (not (contains? (get (:ix c) e) [q schema]))) (join c db e schema q)
                                                           (and (not added) (contains? (get (:ix c) e) [q schema])) (leave c q)
                                                           :else c)))
                                    c at))
                          c)))
               gone (remove :added ds) put (filter :added ds)]
           ;; retractions first (a change is a retraction and an assertion); where e joins it is read
           ;; whole from db, so its assertions set it only at the paths it held before
           (let [c (reduce link (reduce (own (get (:ix c) e)) c gone) gone)
                 held (get (:ix c) e)]
             (reduce (own held) (reduce link c put) put))))
       c (group-by :e datoms))
      (update :tree merge (environment db branch))))

;; the record and its index, beside the atom: the agent is one thread, and its state's watch runs
;; on it, so each patch follows the one before
(defonce ^:private held (volatile! nil))

;; ---- map -> db: a swap!'s change as one transaction -------------------------------------

(def ^:private environment-keys "The map's environment: the record's basis, never written." [:t :branch :commit])

(defn- registered?
  "Has attribute a a schema: the platform's, or one this branch registered (the REPL binds it)?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a] (boolean (and (qualified-keyword? a) (or (contains? contract/forms a) (some-> (mr/registry mr/*registry*) (mr/schema a))))))

(defn- why
  "Why value v cannot be attribute a, or nil when it can: a's schema's explanation, or that a has none."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [a v]
  (cond (not (registered? a)) (str a " has no schema: register it (my.schema/register!), a qualified keyword")
        (contract/valid? a v) nil
        :else (me/humanize (m/explain (contract/schema a) v))))

(defn- ref-of "A ref's value as written: {:db/id e} as e." {:malli/schema [:=> [:cat :any] :any]}
  [v] (if (and (map? v) (contains? v :db/id)) (:db/id v) v))

(defn- edits
  "acc with the change from old to new of the entity e (an id, or a new entity's tempid) at path p,
  schema s, as transaction data (:tx), what its schemas refuse (:invalid) and the paths it writes
  (:paths): each attribute set, retracted or, cardinality many, each member added or retracted;
  each unit's member new (its attributes and its own units, the same way from nothing, naming its
  parent by the unit's ref and keyed by its identity), gone (retracted whole) or changed (the same,
  one level down). A unit identical to its old self is skipped by one identical?; one that differs
  is walked member by member, each identical member skipped the same way (a changed eval of n:
  n identical? tests, and the work of the one that differs)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :qualified-keyword] :map :map [:or pos-int? :string] [:vector :any] :map] :map]}
  [db s old new e p acc]
  (if (identical? old new)
    acc
    (let [many (get (dbi/-rschema db) :db.cardinality/many #{})
          us (get units s)
          unit-keys (set (map :key us))
          skip (cond-> (conj unit-keys :db/id) (empty? p) (into environment-keys))
          bad (fn [acc path a v] (if-let [w (why a v)] (update acc :invalid conj {:path path :why w}) acc))
          acc (reduce
               (fn [acc a]
                 (let [o (get old a) n (get new a) path (conj p a)]
                   (cond
                     (= o n) acc
                     (contains? many a)
                     (let [o (set o) n (set n)]
                       (-> acc (bad path a n)
                           (update :tx into (concat (for [x (remove o n)] [:db/add e a (ref-of x)]) (for [x (remove n o)] [:db/retract e a (ref-of x)])))
                           (update :paths conj path)))
                     (nil? n) (-> acc (update :tx conj [:db/retract e a (ref-of o)]) (update :paths conj path))
                     :else (-> acc (bad path a n) (update :tx conj [:db/add e a (ref-of n)]) (update :paths conj path)))))
               acc (remove skip (distinct (concat (keys old) (keys new)))))]
      (reduce
       (fn [acc {:keys [attr key rev? schema id]}]
         (let [om (get old key) nm (get new key) path (conj p key)]
           (cond
             (identical? om nm) acc
             rev?
             (as-> acc acc
               (reduce-kv (fn [acc k m]
                            (let [o (get om k)]
                              (cond
                                (nil? o) (let [t (str "ctx" (pr-str (conj path k)))]
                                           ;; its identity is its key in the unit, written here: no path the
                                           ;; swap!'s map says anything at
                                           (-> (edits db schema {} (assoc m id k) t (conj path k) acc)
                                               (update :paths (fn [ps] (filterv #(not= (conj path k id) %) ps)))
                                               (update :tx conj [:db/add t attr e])))
                                :else (edits db schema o m (:db/id o) (conj path k) acc))))
                          acc (or nm {}))
               (reduce-kv (fn [acc k o] (if (contains? nm k) acc (-> acc (update :tx conj [:db/retractEntity (:db/id o)]) (update :paths conj (conj path k)))))
                          acc (or om {})))
             (and (map? om) (map? nm) (= (:db/id om) (:db/id nm))) (edits db (render/entity-schema nm nil) om nm (:db/id om) path acc)
             (nil? nm) (-> acc (update :tx conj [:db/retract e attr (:db/id om)]) (update :paths conj path))
             :else (update acc :invalid conj {:path path :why (str key " is " attr "'s entity: set it by " attr ", a ref")}))))
       acc us))))

(defn- strip "x without its :db/ids, at every depth." {:malli/schema [:=> [:cat :any] :any]}
  [x] (cond (map? x) (into {} (for [[k v] (dissoc x :db/id)] [k (strip v)])) (set? x) (into #{} (map strip) x) :else x))

(def ^:dynamic *installing* "True while the record installs its own value: no write." false)
(def ^:dynamic *writing* "True while a swap!'s transaction is applied: its patch waits for the swap! to end (pending)." false)
(defonce ^:private pending (volatile! nil))

(defn write!
  "A swap! of the record to nv, written: its change from the record (edits) as one transaction,
  every value checked by its schema, of the record's basis (nv's :t :branch :commit are the
  record's), and the db, given it, holding what nv says at every path it writes (one d/with); then
  applied (seon.agent/transact!), and the record as the db holds it installed when the swap! ends.
  Answers true, or throws the error that refuses it, nothing written: :seon.ctx/basis,
  :seon.ctx/invalid, :seon.ctx/not-held. Work: the change's paths, a d/with and a patch of it."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [nv]
  (let [c @held cur (:tree c) db (agent/db) branch (:branch cur)]
    (when-not (and c (map? nv) (= (select-keys nv environment-keys) (select-keys cur environment-keys)))
      (throw (ex-info "ctx: the map is not of the record's basis"
                      (contract/error :seon.ctx/basis #'write! "the map is not of the record's basis: the db moved while the swap!'s function ran (write with the swap!, not inside its function), or the function wrote :t, :branch or :commit, which are the environment's; nothing written"
                                      {:held (select-keys cur environment-keys) :swapped (if (map? nv) (select-keys nv environment-keys) {:value nv})}))))
    (let [{:keys [tx invalid paths]} (edits db :seon.db/agent-fact cur nv (:db/id cur) [] {:tx [] :invalid [] :paths []})]
      (cond
        ;; nothing to write: the record stays the db's (the swap!'s map gives way to it as it ends)
        (empty? tx) (if (seq invalid)
                      (throw (ex-info (str "ctx: refused, nothing written: " (pr-str invalid)) (contract/error :seon.ctx/invalid #'write! "refused by the schemas, nothing written: each path and why" {:invalid invalid})))
                      (do (vreset! pending c) true))
        (seq invalid) (throw (ex-info (str "ctx: refused, nothing written: " (pr-str invalid)) (contract/error :seon.ctx/invalid #'write! "refused by the schemas, nothing written: each path and why" {:invalid invalid})))
        :else
        (let [rep (d/with db tx)
              held' (:tree (patch c (:db-after rep) (:tx-data rep) branch))
              holds? (fn [w h] (cond (and (map? w) (map? h)) (= (strip w) (strip (select-keys h (keys w))))
                                     ;; cardinality many: held as a set, whatever collection the swap! wrote
                                     (set? h) (= (set (map strip w)) (strip h))
                                     :else (= (strip w) (strip h))))
              unheld (vec (remove #(holds? (get-in nv %) (get-in held' %)) paths))]
          (when (seq unheld)
            (throw (ex-info (str "ctx: the db would not hold what the swap! says at " (pr-str unheld) ", nothing written")
                            (contract/error :seon.ctx/not-held #'write! "the db would not hold what the swap! says at these paths (it would hold :held), nothing written" {:paths unheld :held (mapv #(get-in held' %) unheld)}))))
          (binding [*writing* true] (agent/transact! tx))
          true)))))

;; ---- the atom ----------------------------------------------------------------------------

(defonce ^{:doc "The agent's record as a map: (deref ctx), changed with swap! like any atom (write!: one checked transaction on the branch, or the error that refuses it)."} ctx (atom nil))


(defn- install!
  "c as the atom's value: the tree, marked as ctx for its renders and with its basis (the branch
  and t it is the record of: what an eval of it records, seon.repl/record-details); the index
  held beside it."
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :map]]] :nil]}
  [c] (vreset! held c) (binding [*installing* true] (reset! ctx (with-meta (:tree c) {:seon.render/schema :seon.ctx/ctx :seon.render/basis (select-keys (:tree c) [:t :branch])}))) nil)

(defn- moved
  "The datoms db moved by from a to b: the last transaction's when b is its db (agent/transact!
  records it), else what differs between the two values (a merge, a resume)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:maybe :map]] [:sequential :any]]}
  [a b tx]
  (if (identical? b (:db-after tx))
    (:tx-data tx)
    (let [[gone added] (d/datoms-diff a b :eavt [])]
      (concat (map #(assoc % :added false) gone) added))))

(defn- on-state
  "The watch on the agent's state: a new agent, key or branch builds ctx, as does a db moved while
  the record lacks its agent's entity; a db moved patches it;
  a commit of what was applied (one t, one branch) moves only its :commit."
  {:malli/schema [:=> [:cat :any :any :any :any] :any]}
  [_ _ o n]
  (let [a (:db o) b (:db n)]
    (cond
      (nil? b) nil
      (or (nil? @held) (nil? a) (not= (:key o) (:key n)) (not= (:branch o) (:branch n))) (install! (build b (:key n) (:branch n)))
      (identical? a b) nil
      ;; the agent's entity reaches its branch after the process starts (a merge of main): until
      ;; it is there the record is its environment alone, and each move looks it up (one seek)
      (nil? (get-in @held [:tree :db/id])) (install! (build b (:key n) (:branch n)))
      (= (:max-tx a) (:max-tx b)) (install! (update @held :tree merge (environment b (:branch n))))
      :else (let [c (patch @held b (moved a b (:tx n)) (:branch n))]
              ;; a swap!'s own transaction: installed when the swap! ends (the atom is mid-swap)
              (if *writing* (do (vreset! held c) (vreset! pending c)) (install! c))))))

(add-watch agent/state ::ctx on-state)
;; nil only while there is no record yet (as the validator is set, before an agent's db)
(set-validator! ctx (fn [nv] (or *installing* (and (nil? nv) (nil? @held)) (write! nv))))
;; a swap! written: the record as the db holds it (its new entities' ids) replaces the swap!'s map
(add-watch ctx ::written (fn [_ _ _ _] (when-let [c @pending] (vreset! pending nil) (install! c))))
