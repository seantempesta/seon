(ns seon.ctx
  "The agent's record as one map, `@ctx` (docs/ctx.md): its agent entity pulled by a pattern,
  Datahike's pull syntax held as data (:seon.ctx/pattern: the agent's own edit, else its entity
  schema's), each join's members under the join's key (its :as: :evals, :runs, a run's :task,
  :turns and :messages), a collection a map of its members by their identity; a window newest
  first (:limit with :reverse); a symbol joined by lookup (:by); every entity held once. Beside
  them its environment, :t :branch :commit.

  Built once from the branch's db when the agent's state first holds it (init, resume, a clone),
  then kept by a patch from each transaction's datoms: the touched entities rebuilt, every other
  subtree the same object, the work of the change, never of the session
  (seon.ctx-test/the-patch-costs-the-change-not-the-session). Nothing is built on a read:
  `@ctx` is a deref. The value carries {:seon.render/schema :seon.ctx/ctx} as metadata: the
  model reads it through the one printer as any value, and the page draws it as a block
  (:seon.ctx/ctx's declared html render). A swap! of it is written to the branch as one checked
  transaction, or refused with why (write!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.render :as render] [seon.agent :as agent]
            [seon.config :as cfg] [seon.db :as pdb] [datahike.api :as d] [datahike.db.interface :as dbi] [clojure.string :as str] [malli.core :as m] [malli.error :as me] [malli.registry :as mr]))

;; ---- the pattern: what ctx holds (docs/ctx.md#the-pattern) ---------------------------------

(defn- forward
  "Attribute a read forward: pull's reverse ref :ns/_name as :ns/name."
  {:malli/schema [:=> [:cat :qualified-keyword] :qualified-keyword]}
  [a] (if (str/starts-with? (name a) "_") (keyword (namespace a) (subs (name a) 1)) a))

(def ^:private join-options
  "A join's options beside Datahike's pull (:as, :limit; :reverse, our fork's newest first): :by,
  the identity attribute a symbol value names (forward: each value read as [by v]; reverse: the
  entities whose attribute holds this entity's by)."
  #{:as :limit :reverse :by})

(defn spec
  "Pull pattern p (Datahike's syntax: '* for the entity's own attributes, {attr sub} a join, attr
  as [attr & options], sub a pattern or an entity schema's name, its declared one) as the walk
  takes it: {:star? :attrs :joins [{:attr :key :rev? :limit :reverse? :by :sub}]}: '* the
  entity's every attribute, else the attributes it names (Datahike's syntax), each join's attribute read forward, its key in the map (its :as, else the attribute),
  its limit a count or a config key's value; nested patterns the same. Throws
  :seon.ctx/invalid-pattern naming the clause it cannot read."
  {:malli/schema [:=> [:cat :any] :map]}
  [p]
  (let [bad (fn [why clause] (throw (ex-info (str "ctx: " why) (contract/error :seon.ctx/invalid-pattern #'spec why {:clause clause}))))]
    (when-not (vector? p) (bad "a pattern is a vector" p))
    {:star? (boolean (some #{'*} p))
     :attrs (into #{} (filter keyword?) p)
     :joins (vec (for [clause p :when (map? clause) [k sub] clause]
                   (let [[a & opts] (if (vector? k) k [k])
                         o (if (even? (count opts)) (apply hash-map opts) (bad "a join's options are pairs" k))
                         _ (when-let [x (first (remove join-options (keys o)))] (bad (str "no join option " x) k))
                         _ (when-not (qualified-keyword? a) (bad "a join names an attribute" k))
                         rev? (str/starts-with? (name a) "_")
                         lim (:limit o)]
                     {:attr (forward a) :key (or (:as o) a) :rev? rev? :by (:by o) :reverse? (boolean (:reverse o))
                      :limit (if (keyword? lim) (cfg/value lim) lim)
                      :sub (spec (cond (vector? sub) sub
                                       ;; an entity schema's name: the pattern it declares
                                       (qualified-keyword? sub) (or (some-> sub contract/properties :seon.ctx/pattern) (bad (str sub " declares no pattern") clause))
                                       :else (bad "a join's pattern is a vector, or an entity schema's name" clause)))})))}))

(def ^:private identities
  "{entity-schema identity}: the upsert key each entity schema names (a member's key in its
  collection's map)."
  (into {} (for [[a s] render/entity-schemas :when (:seon.db/identity (contract/properties a))] [s a])))

(defn pattern-of
  "The pattern ctx holds of entity m (pulled, on db): its own :seon.ctx/pattern (the agent's
  edit), else the one its entity schema declares (:seon.ctx/pattern), else its own attributes."
  {:malli/schema [:=> [:cat :map] :any]}
  [m] (or (:seon.ctx/pattern m) (some-> (render/entity-schema m nil) contract/properties :seon.ctx/pattern) '[*]))

;; ---- db -> map ---------------------------------------------------------------------------

(defn- own
  "Entity e on db, every attribute it holds (or, under pattern spec s that names its attributes,
  those and the ones its joins read: a forward join's attribute, a reverse lookup's name): a
  cardinality-many one as a set (pull answers a vector), a ref as {:db/id}; {:db/id e} alone when
  db holds none of it."
  {:malli/schema [:function [:=> [:cat :seon.host/db pos-int?] :map] [:=> [:cat :seon.host/db pos-int? [:maybe :map]] :map]]}
  ([db e] (own db e nil))
  ([db e s]
  (let [many (get (dbi/-rschema db) :db.cardinality/many #{})
        p (if (or (nil? s) (:star? s))
            '[*]
            (vec (into #{:db/id} (concat (:attrs s) (for [j (:joins s)] (if (:rev? j) (:by j) (:attr j)))))))
        m (or (d/pull db (vec (remove nil? p)) e) {:db/id e})]
    (reduce-kv (fn [m a v] (if (and (contains? many a) (sequential? v)) (assoc m a (set v)) m)) m m))))

(defn- many?
  "Does join j hold a collection (a reverse ref, or a cardinality-many attribute)?"
  {:malli/schema [:=> [:cat :seon.host/db :map] :boolean]}
  [db j] (boolean (or (:rev? j) (contains? (get (dbi/-rschema db) :db.cardinality/many #{}) (:attr j)))))

(defn- watched
  "The [attribute value] whose datoms change join j's members of entity e (m, its own
  attributes), each a key of the record's :watch: a reverse join's [attr e] (or [attr e's :by
  value]); a forward :by join's [by v] per symbol it holds (a var defined later joins). A forward
  join's own datoms are e's."
  {:malli/schema [:=> [:cat pos-int? :map :map] [:sequential :any]]}
  [e m j]
  (cond (and (:rev? j) (:by j)) (when-some [v (get m (:by j))] [[(:attr j) v]])
        (:rev? j) [[(:attr j) e]]
        (:by j) (let [v (get m (:attr j))] (for [x (if (coll? v) v [v]) :when (some? x)] [(:by j) x]))
        :else []))

(defn- in-time-order?
  "Do the ids of the entities reverse join j reaches grow with time: issued by the store's counter,
  not derived from their content (:seon.db/content-id on their identity)? Then their ids' order is
  the order they were written."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [j] (not (some-> (identities (render/entity-schemas (:attr j))) contract/properties :seon.db/content-id)))

(defn- members
  "The entities join j holds of entity e (m, its own attributes), in the order ctx holds them: a
  reverse join's by when each joined, newest first with :reverse, its :limit the newest n (ids
  issued in time order are read by rseek and stop there, docs/forks.md :reverse; content-derived
  ids are read whole and ordered by the transaction that wrote each ref); a forward one's by its
  values, a :by join's each symbol as [by v] (a symbol naming no entity joins nothing)."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :map :map] [:sequential pos-int?]]}
  [db e m j]
  (let [{:keys [attr by limit reverse?]} j
        cap (fn [xs] (if limit (take limit xs) xs))]
    (if (:rev? j)
      (when-some [v (if by (get m by) e)]
        (cap (cond (not reverse?) (map :e (d/datoms db :avet attr v))
                   (in-time-order? j) (map :e (take-while #(and (= attr (:a %)) (= v (:v %))) (d/rseek-datoms db :avet attr v)))
                   :else (map :e (sort-by (juxt :tx :e) #(compare %2 %1) (d/datoms db :avet attr v))))))
      (let [v (get m attr) vs (if (set? v) (sort-by str v) (if (some? v) [v] []))]
        (cap (keep (fn [x] (if by (pdb/eid db by x) (:db/id x))) vs))))))

(defn- key-of
  "Member e's key in its collection's map: its identity's value (its entity schema's), else its id."
  {:malli/schema [:=> [:cat :map] :any]}
  [m] (if-let [id (identities (render/entity-schema m nil))] (get m id (:db/id m)) (:db/id m)))

(defn- member-key
  "Member x's key in join j's map, by one seek of its identity when the join names it (a reverse
  join's members are its attribute's entity schema's; a :by join's are named by the attribute it
  looks up by), else read from the member (key-of)."
  {:malli/schema [:=> [:cat :seon.host/db :map pos-int?] :any]}
  [db j x]
  (if-let [id (if (:rev? j) (identities (render/entity-schemas (:attr j))) (:by j))]
    (or (:v (first (d/datoms db :eavt x id))) x)
    (key-of (own db x))))

(defn- ordered
  "Members pairs [[key node] …], in the order the join holds them (newest first in a window), as a
  map sorted by that order, whatever its keys' hashes; a key the order does not name (one a swap!
  adds) after them, by its printed name."
  {:malli/schema [:=> [:cat [:sequential :any]] :map]}
  [pairs]
  (let [ix (zipmap (map first pairs) (range))]
    (into (sorted-map-by (fn [a b] (let [i (ix a) j (ix b)]
                                     (cond (and i j) (compare i j) i -1 j 1 :else (compare (str a) (str b))))))
          pairs)))

(defn- stub?
  "Is x the {:db/id e} an entity held at another path is held as?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x] (and (map? x) (= [:db/id] (keys x))))

(defn- under?
  "Is path p at or below path q?"
  {:malli/schema [:=> [:cat [:vector :any] [:vector :any]] :boolean]}
  [p q] (and (<= (count q) (count p)) (= q (subvec p 0 (count q)))))

(defn- unwatch
  "r without join [e k]'s watch keys (:watching, the reverse of :watch)."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [r ek]
  (-> (reduce (fn [r w] (let [x (disj (get-in r [:watch w]) ek)] (if (seq x) (assoc-in r [:watch w] x) (update r :watch dissoc w))))
              r (get-in r [:watching ek]))
      (update :watching dissoc ek)))

(defn- watch
  "r with join [e k] watching keys ws (unwatched first: a join re-done watches what it reads now)."
  {:malli/schema [:=> [:cat :map :any [:sequential :any]] :map]}
  [r ek ws]
  (let [r (unwatch r ek)]
    (if (seq ws)
      (-> (reduce (fn [r w] (update-in r [:watch w] (fnil conj #{}) ek)) r ws) (assoc-in [:watching ek] (set ws)))
      r)))

(defn- forget
  "Record state r without subtree t at path q: each entity's paths at or below q, and its holding
  there. An entity still held as {:db/id} elsewhere moves its holding (:relocate: the patch builds
  the record anew, as that is a fresh build's placement)."
  {:malli/schema [:=> [:cat :map :any [:vector :any]] :map]}
  [r t q]
  (reduce (fn [r e]
            (let [at (into #{} (remove #(under? % q)) (get-in r [:at e]))
                  r (if (seq at) (assoc-in r [:at e] at) (update r :at dissoc e))]
              (if (some-> (get-in r [:held e]) (under? q))
                (cond-> (-> (reduce (fn [r j] (unwatch r [e (:key j)])) r (:joins (get-in r [:spec e])))
                            (update :held dissoc e) (update :spec dissoc e))
                  (seq at) (assoc :relocate true))
                r)))
          r (distinct (keep #(when (map? %) (:db/id %)) (tree-seq coll? #(if (map? %) (vals %) (seq %)) t)))))

(declare node)

(defn- join
  "Join j of entity e (m, its own attributes) at path p: [value r], value its members' nodes (a
  map by key-of, a collection; else the one node), r the record's state gaining their paths and
  the join's watch keys. old, the join's value before: a collection keeps every member still in
  it that nothing touched (the same object, by its key there) and changes only the members that
  joined, left or were touched; a member that left is forgotten (forget)."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :map :map [:vector :any] :any :map [:set :any]] [:tuple :any :map]]}
  [db e m j p old r touched]
  (let [r (watch r [e (:key j)] (watched e m j))
        es (members db e m j)]
    (if (many? db j)
      (let [old (if (map? old) old {})
            by-id (into {} (map (fn [[k x]] [(:db/id x) k])) old)
            now (set es)
            ;; members that left: their subtrees forgotten
            r (reduce (fn [r [x k]] (if (now x) r (forget r (get old k) (conj p k)))) r by-id)
            [pairs r] (reduce (fn [[ps r] x]
                                (let [k (by-id x)]
                                  (if (and k (not (touched x)))
                                    [(conj ps [k (get old k)]) r]
                                    (let [k (or k (member-key db j x))
                                          [n r] (node db x (:sub j) (conj p k) r (when (by-id x) (get old k)) touched)]
                                      [(conj ps [k n]) r]))))
                              [[] r] es)]
        ;; the same members, in the same order, each the same object: the map as it was
        [(if (and (= (map first pairs) (keys old)) (every? true? (map #(identical? (second %) (get old (first %))) pairs)))
           old
           (ordered pairs))
         r])
      (let [x (first es) o (when (and (map? old) (= x (:db/id old))) old)]
        (cond
          (nil? x) [nil (if (map? old) (forget r old p) r)]
          (and o (not (touched x))) [o r]
          :else (let [r (if (and (map? old) (not o)) (forget r old p) r)] (node db x (:sub j) p r o touched)))))))

(defn- node
  "Entity e as ctx holds it under pattern spec s at path p: its own attributes, and each join's
  members (join) under the join's key (a ref attribute's value, {:db/id}, gives way to them; a
  :by join's symbols stay, its members beside them under its :as); [m r], r the record's state
  ({:held {e path} :at {e #{path}} :spec {e s} :watch {[a v] #{[e key]}}}) gaining it. An
  entity is held once, at the first path the walk reaches it by (the pattern's order, a newest-
  first collection's newest member first); any other path holds {:db/id e}. old, e's value
  before, keeps each join no touched datom changed (identical?), its entries in r as they were."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :map [:vector :any] :map [:maybe :map] [:set :any]] [:tuple :map :map]]}
  [db e s p r old touched]
  (let [r (update-in r [:at e] (fnil conj #{}) p)
        h (get-in r [:held e])]
    (if (and h (not= h p))
      ;; a patch reaching a held entity by a new path: a fresh build places it (:relocate)
      [{:db/id e} (cond-> r (:patching r) (assoc :relocate true))]
      (let [old (when-not (stub? old) old)
            r (-> r (assoc-in [:held e] p) (assoc-in [:spec e] s))
            m (if (and old (not (touched e))) (apply dissoc old (map :key (:joins s))) (own db e s))
            refs (get (dbi/-rschema db) :db.type/ref #{})]
        (reduce (fn [[n r] j]
                  (let [k (:key j)
                        [v r] (if (and old (not (touched [e k])))
                                [(get old k) r]
                                (join db e m j (conj p k) (get old k) r touched))
                        n (if (or (= k (:attr j)) (contains? refs (:attr j))) (dissoc n (:attr j)) n)]
                    [(if (or (nil? v) (and (map? v) (empty? v) (many? db j))) (dissoc n k) (assoc n k v)) r]))
                [m r] (:joins s))))))

(def ^:private environment-keys "The map's environment: the record's basis, never written." [:t :branch :commit])

(defn- environment
  "The map's environment: the db's t, the branch and the commit the db is (none for a value as of
  an earlier t: its basis names it)."
  {:malli/schema [:=> [:cat :seon.host/db :any] :map]}
  [db branch] (let [c (d/commit-id db)] (cond-> {:t (:max-tx db) :branch branch} c (assoc :commit c))))

;; ---- provenance: a query on the tx-meta (docs/ctx.md#provenance) --------------------------

(defn build
  "The record of entity e on db as ctx's value {:tree :ix}: e pulled by its pattern
  (pattern-of: the agent's own edit, else its entity schema's), and its environment on branch,
  the db's data only (nothing derived from it is held here: a render derives what it needs);
  :ix the walk's state (node: where each entity is held, its pattern, what each join watches).
  Any entity an entity schema names: an
  agent's (its @ctx), root's, the user's, a task's. With no e (an agent whose entity its branch
  does not hold yet), its environment alone. O(the entities the pattern reaches)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe pos-int?] :any] [:map [:tree :map] [:ix :map]]]}
  [db e branch]
  (let [m (when e (own db e))
        [tree ix] (if (and m (< 1 (count m))) (node db e (spec (pattern-of m)) [] {} nil #{}) [{} {}])]
    {:tree (merge tree (environment db branch)) :ix ix}))

(defn made-by
  "Who made attribute a of entity e as db holds it: the tx-meta of the transaction of its newest
  datom, the branch it was written on (:seon.tx/branch), the eval whose code made it
  (:seon.tx/eval, when an eval did) and the branch a catch-up merged it from (:seon.tx/merged),
  with that transaction (:t); nil when e has no a. One seek of e's a and one of the transaction."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :qualified-keyword]
                  [:maybe [:map [:t :int] [:seon.tx/branch {:optional true} :seon.tx/branch]
                           [:seon.tx/eval {:optional true} :seon.tx/eval] [:seon.tx/merged {:optional true} :seon.tx/merged]]]]}
  [db e a]
  (when-let [tx (some->> (seq (d/datoms db :eavt e a)) (map :tx) (reduce max))]
    (assoc (dissoc (d/pull db [:seon.tx/branch :seon.tx/eval :seon.tx/merged] tx) :db/id) :t tx)))

;; ---- datoms -> patch ---------------------------------------------------------------------

(defn- touched-by
  "What datoms change of the record's state r: each held entity a datom is of (its own
  attributes), each [e key] whose join's members a datom adds, drops or renames (a forward join
  of e's attribute; a :watch key, [a v], a reverse join's or a :by join's)."
  {:malli/schema [:=> [:cat :map [:sequential :any]] [:set :any]]}
  [r datoms]
  (reduce (fn [t {:keys [e a v]}]
            (let [s (get-in r [:spec e])]
              (cond-> (into t (get-in r [:watch [a v]]))
                (and s (or (:star? s) (contains? (:attrs s) a) (some #(= a (:attr %)) (:joins s)))) (conj e)
                s (into (for [j (:joins s) :when (and (not (:rev? j)) (= a (:attr j)))] [e (:key j)])))))
          #{} datoms))

(defn- same-node?
  "Does rebuilt node n hold what o held, every key's value the same object (identical?)?"
  {:malli/schema [:=> [:cat [:maybe :map] :map] :boolean]}
  [o n] (and (map? o) (= (count o) (count n)) (every? (fn [[k v]] (identical? v (get o k ::none))) n)))

(defn- share
  "Map n with every subtree equal to old's at its path old's object (identical?): a fresh build
  shares what it did not change with the record it replaces, children first, so a node whose
  children all come back the same is old's node. Work: n's size, once."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [o n]
  (cond (identical? o n) o
        (and (map? o) (map? n))
        ;; built anew in n's order (into its empty, its comparator kept): an assoc over an equal
        ;; value may keep the value it holds
        (let [n (into (empty n) (map (fn [[k v]] [k (share (get o k) v)])) n)]
          (if (and (= (count o) (count n)) (every? (fn [[k v]] (identical? v (get o k ::none))) n)) o n))
        (= o n) o
        :else n))

(defn patch
  "ctx's value c with datoms (a transaction's :tx-data, or a datoms-diff marked :added) applied
  on db, the db after them: each held entity they touch (touched-by) rebuilt where it is held,
  shortest path first, as node builds it, keeping each join and member nothing touched
  (identical?), the path above it copied; a member that joins a window is read, the oldest
  leaves. Work: the touched entities and the members their changed joins read, never the record.
  Every path the change did not reach holds the same object it held before, so what changed is
  read off the two values (changes) in the work of what differs. A change of the pattern itself,
  or one moving where an entity is held (a new path reaching a held one, its holder leaving),
  builds the record anew, as a fresh build places it, sharing every subtree it did not change
  (share). The environment (:t :branch :commit) is the
  root map's, new each transaction."
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :map]] :seon.host/db [:sequential :any] :any] [:map [:tree :map] [:ix :map]]]}
  [c db datoms branch]
  (let [r (:ix c) root (get-in c [:tree :db/id])
        touched (touched-by r datoms)
        ;; a fresh build shares every subtree it did not change with the record before (share)
        fresh #(update (build db root branch) :tree (fn [t] (merge (share (apply dissoc (:tree c) environment-keys) (apply dissoc t environment-keys)) (select-keys t environment-keys))))]
    (cond
      (nil? root) (fresh)
      (some #(and (= root (:e %)) (= :seon.ctx/pattern (:a %))) datoms) (fresh)
      (empty? touched) (update c :tree #(merge (apply dissoc % environment-keys) (environment db branch)))
      :else
      (let [sites (sort-by (comp count first)
                           (distinct (for [x touched :let [e (if (vector? x) (first x) x) h (get-in r [:held e])] :when h] [h e])))
            [tree r] (reduce (fn [[tree r] [h e]]
                                  (let [o (if (empty? h) tree (get-in tree h))]
                                    (if (or (nil? o) (stub? o) (not= e (:db/id o)))
                                      [tree r]
                                      (let [[n r] (node db e (get-in r [:spec e]) h (assoc r :patching true) o touched)]
                                        ;; a rebuild that changed nothing keeps the node it rebuilt (the same object)
                                        [(if (same-node? o n) tree (if (empty? h) n (assoc-in tree h n))) r]))))
                                [(:tree c) r] sites)]
        (if (or (:relocate r) (not (seq (dissoc tree :db/id))) (empty? (d/datoms db :eavt root)))
          (fresh)
          {:tree (merge (apply dissoc tree environment-keys) (environment db branch))
           :ix (dissoc r :patching :relocate)})))))

;; the record and its index, beside the atom: the agent is one thread, and its state's watch runs
;; on it, so each patch follows the one before
(defonce ^:private held (volatile! nil))

(defn record
  "This agent's held record, {:tree :ix}: @ctx (its :tree) and its index (the walk's state); nil
  before it is built."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] @held)

;; ---- map -> db: a swap!'s change as one transaction -------------------------------------

(defn follow
  "c patched by one of the writer's commits of the branch it follows, {:t :ops :db} as main's
  readers receive them (seon.writer; the render proc's :main): ops, explicit-id tx-data
  (seon.db/report->tx), as the datoms patch takes. Root's, the user's and a task's record follow
  main this way, as an agent's follows its own transactions (on-state)."
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :map]] [:map [:t :int] [:ops [:sequential :any]] [:db :seon.host/db]] :any]
                  [:map [:tree :map] [:ix :map]]]}
  [c {:keys [t ops db]} branch]
  (patch c db (for [[op e a v] ops] {:e e :a a :v v :tx t :added (= op :db/add)}) branch))


(defn- registered?
  "Has attribute a a schema: the platform's, or one this branch registered (the REPL binds it)?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a] (boolean (and (qualified-keyword? a) (or (contains? contract/forms a) (some-> (mr/registry mr/*registry*) (mr/schema a))))))

(defn- why
  "Why value v cannot be attribute a, or nil when it can: a's schema's explanation, or that a has none."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [a v]
  (cond (not (registered? a)) "no schema: register it (my.schema/register!), a qualified keyword"
        (contract/valid? a v) nil
        :else (me/humanize (m/explain (contract/schema a) v))))

(defn- ref-of "A ref's value as written: {:db/id e} as e." {:malli/schema [:=> [:cat :any] :any]}
  [v] (if (and (map? v) (contains? v :db/id)) (:db/id v) v))

(defn- edits
  "acc with the change from old to new of the entity e (an id, or a new entity's tempid) at path p,
  pattern spec s, as transaction data (:tx), what its schemas refuse (:invalid) and the paths it
  writes (:paths): each attribute set, retracted or, cardinality many, each member added or
  retracted; each reverse join's member new (its attributes and its own joins, the same way from
  nothing, naming its parent by the join's ref and keyed by its identity), gone (retracted whole)
  or changed (the same, one level down); a forward join's entity changed in place. A join
  identical to its old self is skipped by one identical?; one that differs is walked member by
  member, each identical member skipped the same way. A member a lookup (:by) holds follows the
  attribute it reads, and one held at another path ({:db/id e}) is changed there: either is
  refused here."
  {:malli/schema [:=> [:cat :seon.host/db :map :map :map [:or pos-int? :string] [:vector :any] :map] :map]}
  [db s old new e p acc]
  (if (identical? old new)
    acc
    (let [many (get (dbi/-rschema db) :db.cardinality/many #{})
          js (:joins s)
          skip (cond-> (into #{:db/id} (map :key) js) (empty? p) (into environment-keys))
          bad (fn [acc path a v] (if-let [w (why a v)] (update acc :invalid conj {:path path :why w}) acc))
          refuse (fn [acc path w] (update acc :invalid conj {:path path :why w}))
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
       (fn [acc {:keys [attr key rev? by sub] :as j}]
         (let [om (get old key) nm (get new key) path (conj p key)]
           (cond
             (identical? om nm) acc
             by (refuse acc path (str "a lookup's members follow " attr ": change it"))
             (and rev? (many? db j))
             (let [schema (render/entity-schemas attr) id (identities schema)]
               (as-> acc acc
                 (reduce-kv (fn [acc k m]
                              (let [o (get om k)]
                                (cond
                                  (identical? o m) acc
                                  (or (stub? o) (stub? m)) (refuse acc (conj path k) "held at another path: change it there")
                                  (nil? o) (let [t (str "ctx" (count (:tx acc)))] ; unique: each new member writes its identity first
                                             ;; its identity is its key in the map, written here: no path the
                                             ;; swap!'s map says anything at
                                             (-> (edits db sub {} (cond-> m id (assoc id k)) t (conj path k) acc)
                                                 (update :paths (fn [ps] (filterv #(not= (conj path k id) %) ps)))
                                                 (update :tx conj [:db/add t attr e])))
                                  :else (edits db sub o m (:db/id o) (conj path k) acc))))
                            acc (or nm {}))
                 (reduce-kv (fn [acc k o] (if (contains? nm k) acc (-> acc (update :tx conj [:db/retractEntity (:db/id o)]) (update :paths conj (conj path k)))))
                            acc (or om {}))))
             (many? db j)
             (reduce (fn [acc k] (let [o (get om k) m (get nm k)]
                                   (cond (identical? o m) acc
                                         (and (map? o) (map? m) (not (stub? o)) (= (:db/id o) (:db/id m))) (edits db sub o m (:db/id o) (conj path k) acc)
                                         :else (refuse acc (conj path k) "a ref's entity: set it by the ref"))))
                     acc (distinct (concat (keys om) (keys nm))))
             (and (map? om) (map? nm) (not (stub? om)) (= (:db/id om) (:db/id nm))) (edits db sub om nm (:db/id om) path acc)
             (and (nil? nm) (map? om)) (-> acc (update :tx conj [:db/retract e attr (:db/id om)]) (update :paths conj path))
             :else (refuse acc path "a ref's entity: set it by the ref"))))
       acc js))))

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
    ;; a pattern the walk cannot read is refused before anything is written (spec throws why)
    (when (and (map? nv) (not= (:seon.ctx/pattern nv) (:seon.ctx/pattern cur)) (some? (:seon.ctx/pattern nv))) (spec (:seon.ctx/pattern nv)))
    (let [{:keys [tx invalid paths]} (edits db (get-in c [:ix :spec (:db/id cur)] {:joins []}) cur nv (:db/id cur) [] {:tx [] :invalid [] :paths []})]
      (cond
        ;; nothing to write: the record stays the db's (the swap!'s map gives way to it as it ends)
        (empty? tx) (if (seq invalid)
                      (throw (ex-info "ctx: refused by the schemas, nothing written" (contract/error :seon.ctx/invalid #'write! "refused by the schemas, nothing written: each path and why" {:invalid invalid})))
                      (do (vreset! pending c) true))
        (seq invalid) (throw (ex-info "ctx: refused by the schemas, nothing written" (contract/error :seon.ctx/invalid #'write! "refused by the schemas, nothing written: each path and why" {:invalid invalid})))
        :else
        (let [rep (d/with db tx)
              held' (:tree (patch c (:db-after rep) (:tx-data rep) branch))
              holds? (fn [w h] (cond (and (map? w) (map? h)) (= (strip w) (strip (select-keys h (keys w))))
                                     ;; cardinality many: held as a set, whatever collection the swap! wrote
                                     (set? h) (= (set (map strip w)) (strip h))
                                     :else (= (strip w) (strip h))))
              unheld (vec (remove #(holds? (get-in nv %) (get-in held' %)) paths))]
          (when (seq unheld)
            (throw (ex-info "ctx: the db would not hold what the swap! says, nothing written"
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

(defn advance
  "c0, the record as of db0, as of db1 (the same branch, later): patched by the datoms the two
  values differ by, the net change of every transaction in between (a turn's, from its start t to
  its end t). Read from the db alone, so a turn recorded is advanced again to the same record.
  Work: the patch's, of the net datoms."
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :map]] :seon.host/db :seon.host/db :any] [:map [:tree :map] [:ix :map]]]}
  [c0 db0 db1 branch] (patch c0 db1 (moved db0 db1 nil) branch))

(defn- tree-of "A record's map ({:tree t}), or the map itself." {:malli/schema [:=> [:cat :map] :map]}
  [c] (if (contains? c :tree) (:tree c) c))

(defn changes
  "What changed from record (or map) c0 to c1, as data in the record's own shape: [[op path value]
  …], op :add (value the new one), :remove (value nil) or :replace (value the new one), in the
  order of their paths. It walks the two maps together, one entity's two nodes (a map with its
  :db/id) and two collections (a join's map of members) into their keys, any other value (and
  another entity under the same key) compared whole, and
  skips a pair that is the same object (identical?) before comparing it (=): a patch keeps
  every subtree it did not reach the same object, so the walk costs what changed, never the
  record. The environment (:t :branch :commit) is the record's basis, not its data: left out.
  The one diff of ctx: a turn's (diff), and what a view or the model's request redraws."
  {:malli/schema [:=> [:cat :map :map] [:vector [:tuple [:enum :add :remove :replace] [:vector :any] :any]]]}
  [c0 c1]
  (let [entity? (fn [x] (and (map? x) (contains? x :db/id)))
        coll? (fn [x] (and (map? x) (not (entity? x)) (every? entity? (vals x))))
        walk (fn walk [acc p a b]
               (cond (identical? a b) acc
                     (nil? a) (conj acc [:add p b])
                     (nil? b) (conj acc [:remove p nil])
                     ;; one entity's two nodes, or two collections: into their keys; another entity
                     ;; under the same key (retracted and made again) is replaced whole
                     (or (and (entity? a) (entity? b) (= (:db/id a) (:db/id b))) (and (coll? a) (coll? b)))
                     (reduce (fn [acc k] (walk acc (conj p k) (get a k) (get b k)))
                             acc (remove (if (empty? p) (set environment-keys) #{}) (distinct (concat (keys a) (keys b)))))
                     (= a b) acc
                     :else (conj acc [:replace p b])))]
    (vec (sort-by (fn [[_ p]] (str/join "\u0001" (map str p))) (walk [] [] (tree-of c0) (tree-of c1))))))

(defn diff
  "What changed of the record from c0 to c1 (changes), as {path [old new]}: old nil for a member
  that joined, new nil for one that went; a change undone within a turn is no change. Pure in the
  two values. Work: what differs, never the record."
  {:malli/schema [:=> [:cat :map :map] [:map-of [:vector :any] [:tuple :any :any]]]}
  [c0 c1] (let [t0 (tree-of c0)] (into {} (for [[_ p v] (changes c0 c1)] [p [(get-in t0 p) v]]))))

(defn- agent-record
  "The record of the agent state st names (its key's entity on db, its branch), built."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db st] (build db (some->> (:key st) (pdb/eid db :seon.agent/key)) (:branch st)))

(defn- on-state
  "The watch on the agent's state: a new agent, key or branch builds ctx, as does a db moved while
  the record lacks its agent's entity; a db moved patches it;
  a commit of what was applied (one t, one branch) moves only its :commit."
  {:malli/schema [:=> [:cat :any :any :any :any] :any]}
  [_ _ o n]
  (let [a (:db o) b (:db n)]
    (cond
      (nil? b) nil
      (or (nil? @held) (nil? a) (not= (:key o) (:key n)) (not= (:branch o) (:branch n))) (install! (agent-record b n))
      (identical? a b) nil
      ;; the agent's entity reaches its branch after the process starts (a merge of main): until
      ;; it is there the record is its environment alone, and each move looks it up (one seek)
      (nil? (get-in @held [:tree :db/id])) (install! (agent-record b n))
      (= (:max-tx a) (:max-tx b)) (install! (update @held :tree merge (environment b (:branch n))))
      :else (let [c (patch @held b (moved a b (:tx n)) (:branch n))]
              ;; a swap!'s own transaction: installed when the swap! ends (the atom is mid-swap)
              (if *writing* (do (vreset! held c) (vreset! pending c)) (install! c))))))

(add-watch agent/state ::ctx on-state)
;; nil only while there is no record yet (as the validator is set, before an agent's db)
(set-validator! ctx (fn [nv] (or *installing* (and (nil? nv) (nil? @held)) (write! nv))))
;; a swap! written: the record as the db holds it (its new entities' ids) replaces the swap!'s map
(add-watch ctx ::written (fn [_ _ _ _] (when-let [c @pending] (vreset! pending nil) (install! c))))
