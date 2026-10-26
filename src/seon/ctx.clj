(ns seon.ctx
  "The agent's record as one map, `@ctx` (docs/ctx.md): its agent entity pulled by a pattern,
  Datahike's pull syntax held as data (:seon.ctx/pattern: the agent's own edit, else its entity
  schema's), each join's members under the join's key (its :as: :evals, :runs, a run's :task,
  :turns and :messages), a collection a map of its members by their ids, newest first; a window newest
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

(def ^:private identities
  "{entity-schema identity}: the upsert key each entity schema names: whether its ids are issued in
  time order (in-time-order?), and the attribute a new member's value names it by (edits)."
  (into {} (for [[a s] render/entity-schemas :when (:seon.db/identity (contract/properties a))] [s a])))

(defn- pattern-of
  "The pattern ctx holds of entity m (pulled, on db): its own :seon.ctx/pattern (the agent's
  edit), else the one its entity schema declares (:seon.ctx/pattern), else its own attributes."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
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
            (vec (into #{:db/id} (concat (:attrs s) (for [j (:joins s) :when (not (:rev? j))] (:attr j))))))
        m (or (d/pull db p e) {:db/id e})]
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
      (let [v (get m attr) vs (if (set? v) (sort-by #(if (map? %) (:db/id %) %) v) (if (some? v) [v] []))]
        (cap (keep (fn [x] (if by (pdb/eid db by x) (:db/id x))) vs))))))

(defn- members-map
  "Pairs [[id node] …] as a collection: a map sorted by its members' ids, greatest (newest, for ids
  issued in time order) first; a key that is no id (a new member a swap! names) after them, by its
  printed name. Fixed, and a function of the keys alone, so a member joins or leaves by one assoc or
  dissoc."
  {:malli/schema [:=> [:cat [:sequential [:tuple :any :map]]] :map]}
  [pairs]
  (into (sorted-map-by (fn [a b] (cond (and (int? a) (int? b)) (compare b a) (int? a) -1 (int? b) 1 :else (compare (str a) (str b)))))
        pairs))

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
  {:malli/schema [:=> [:cat :seon.ctx/ix :any] :seon.ctx/ix]}
  [r ek]
  (let [drop-key (fn [r k x] (let [m (dissoc (get r k) x)] (if (seq m) (assoc r k m) (dissoc r k))))]
    (if-let [ws (get-in r [:watching ek])]
      (-> (reduce (fn [r w] (let [x (disj (get-in r [:watch w]) ek)] (if (seq x) (assoc-in r [:watch w] x) (drop-key r :watch w))))
                  r ws)
          (drop-key :watching ek))
      r)))

(defn- watch
  "r with join [e k] watching keys ws (unwatched first: a join re-done watches what it reads now)."
  {:malli/schema [:=> [:cat :seon.ctx/ix :any [:sequential :any]] :seon.ctx/ix]}
  [r ek ws]
  (let [r (unwatch r ek)]
    (if (seq ws)
      (-> (reduce (fn [r w] (update-in r [:watch w] (fnil conj #{}) ek)) r ws) (assoc-in [:watching ek] (set ws)))
      r)))

(defn- forget
  "Record state r without subtree t at path q: each entity's paths at or below q, and its holding
  there. An entity still held as {:db/id} elsewhere moves its holding (:relocate: the patch builds
  the record anew, as that is a fresh build's placement)."
  {:malli/schema [:=> [:cat :seon.ctx/ix :any [:vector :any]] :seon.ctx/ix]}
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

(defn- window-moves
  "Join j's new members (r: the record's state) from moves, the datoms that touched it, when j is a
  window of ids issued in time order (rseek's newest first) and old holds its members: [v r
  entered], each member a retraction names and no assertion brings back dissoc'd (forgotten), each
  an assertion names that it does not hold assoc'd (built), the oldest past its
  limit dropped, a window left short by one that left filled from below its oldest by rseek, and
  the ids it built; nil when moves are not the join's own ref datoms (its full read answers then).
  Work: the moves, and a pull per member they bring, never the window."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :map [:vector :any] :map :seon.ctx/ix [:sequential :any] [:map-of :any [:vector :any]]]
                  [:maybe [:tuple :map :seon.ctx/ix [:set :int]]]]}
  [db e j p old r moves touched]
  (when (and (:rev? j) (not (:by j)) (:reverse? j) (in-time-order? j) (seq old)
             (every? #(and (= (:attr j) (:a %)) (= e (:v %))) moves))
    (let [full? (and (:limit j) (= (:limit j) (count old)))
          asserted (into #{} (comp (filter :added) (map :e)) moves)
          ;; a member a retraction names and no assertion brings back (one that leaves and comes back
          ;; in one transaction stays, the same object)
          gone (into #{} (comp (remove :added) (map :e) (filter #(contains? old %)) (remove asserted)) moves)
          leave (fn [[v r] x] [(dissoc v x) (forget r (get v x) (conj p x))])
          enter (fn [[v r] x] (let [[n r] (node db x (:sub j) (conj p x) r nil touched)] [(assoc v x n) r]))
          [v r] (reduce leave [old r] gone)
          ;; every member an assertion names that the window does not hold
          come (sort > (remove #(contains? v %) asserted))
          come (if (and full? (seq v)) (filter #(> % (key (first (rseq v)))) come) come)
          [v r] (reduce enter [v r] come)
          ;; past the limit: the oldest leave
          [v r] (if-let [n (:limit j)] (reduce leave [v r] (map key (take (max 0 (- (count v) n)) (rseq v)))) [v r])
          ;; short of it by one that left, and older ones were never held: filled from below the oldest
          need (if (and full? (:limit j)) (- (:limit j) (count v)) 0)
          older (when (pos? need)
                  (let [below (if (seq v) (dec (key (first (rseq v)))) Long/MAX_VALUE)]
                    (->> (d/rseek-datoms db :avet (:attr j) e below)
                         (take-while #(and (= (:attr j) (:a %)) (= e (:v %))))
                         (map :e) (remove #(contains? v %)) (take need))))
          [v r] (reduce enter [v r] older)]
      [v r (into (set come) older)])))

(defn- join
  "Join j of entity e (m, its own attributes) at path p: [value r], value its members' nodes (a
  collection: a map by their ids, newest first; else the one node), r the record's state gaining
  their paths and the join's watch keys. old, the join's value before: a window of ids issued in
  time order changes by the datoms that touched it (window-moves); any other collection is read
  again, keeping every member still in it that nothing touched (the same object) and changing only
  the members that joined, left or were touched; a member that left is forgotten (forget)."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :map :map [:vector :any] :any :seon.ctx/ix [:map-of :any [:vector :any]]] [:tuple :any :seon.ctx/ix]]}
  [db e m j p old r touched]
  (let [r (watch r [e (:key j)] (watched e m j))
        old-map (if (map? old) old {})
        moves (when (map? touched) (get touched [e (:key j)]))]
    (if (many? db j)
      (if-let [[v r entered] (when (seq moves) (window-moves db e j p old-map r moves touched))]
        ;; members a datom touched as entities (their own attributes), not just built, built again in place
        (reduce (fn [[v r] x] (if (and (contains? v x) (not (entered x)) (touched x))
                                (let [[n r] (node db x (:sub j) (conj p x) r (get v x) touched)] [(assoc v x n) r])
                                [v r]))
                [v r] (filter integer? (keys touched)))
        (let [es (members db e m j)
              now (set es)
              ;; members that left: their subtrees forgotten
              r (reduce (fn [r [x o]] (if (now x) r (forget r o (conj p x)))) r old-map)
              [pairs r] (reduce (fn [[ps r] x]
                                  (let [o (get old-map x)]
                                    (if (and o (not (touched x)))
                                      [(conj ps [x o]) r]
                                      (let [[n r] (node db x (:sub j) (conj p x) r o touched)] [(conj ps [x n]) r]))))
                                [[] r] es)]
          ;; the same members, each the same object: the map as it was
          [(if (and (= (count pairs) (count old-map)) (every? (fn [[x n]] (identical? n (get old-map x))) pairs))
             old-map
             (members-map pairs))
           r]))
      (let [es (members db e m j) x (first es) o (when (and (map? old) (= x (:db/id old))) old)]
        (cond
          (nil? x) [nil (if (map? old) (forget r old p) r)]
          (and o (not (touched x))) [o r]
          :else (let [r (if (and (map? old) (not o)) (forget r old p) r)] (node db x (:sub j) p r o touched)))))))

(defn- same-node?
  "Does rebuilt node n hold what o held, every key's value the same object (identical?)?"
  {:malli/schema [:=> [:cat [:maybe :map] :map] :boolean]}
  [o n] (and (map? o) (= (count o) (count n)) (every? (fn [[k v]] (identical? v (get o k ::none))) n)))

(defn- node
  "Entity e as ctx holds it under pattern spec s at path p: its own attributes, and each join's
  members (join) under the join's key (a ref attribute's value, {:db/id}, gives way to them; a
  :by join's symbols stay, its members beside them under its :as); [m r], r the record's state
  (:seon.ctx/ix: :held {e path}, :at {e #{path}}, :spec {e s}, :watch {[a v] #{[e key]}} and its
  reverse :watching; during a patch :patching, and :relocate when the patch must build anew)
  gaining it. An entity is held once, at the first path the walk reaches it by (the pattern's order, a newest-
  first collection's newest member first); any other path holds {:db/id e}. old, e's value
  before, keeps each join no touched datom changed (identical?), its entries in r as they were,
  and each attribute equal to before; a node equal to old in every value is old."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? :seon.ctx/spec [:vector :any] :seon.ctx/ix [:maybe :map] [:map-of :any [:vector :any]]] [:tuple :map :seon.ctx/ix]]}
  [db e s p r old touched]
  (let [r (update-in r [:at e] (fnil conj #{}) p)
        h (get-in r [:held e])]
    (if (and h (not= h p))
      ;; a patch reaching a held entity by a new path: a fresh build places it (:relocate)
      [{:db/id e} (cond-> r (:patching r) (assoc :relocate true))]
      (let [old (when-not (stub? old) old)
            r (-> r (assoc-in [:held e] p) (assoc-in [:spec e] s))
            m (cond (and old (not (touched e))) (apply dissoc old (map :key (:joins s)))
                    ;; pulled again: an attribute equal to before keeps the object it was
                    old (let [pulled (own db e s)]
                          (reduce-kv (fn [m k v] (let [o (get old k ::none)] (if (and (not (identical? o v)) (= o v)) (assoc m k o) m)))
                                     pulled pulled))
                    :else (own db e s))
            refs (get (dbi/-rschema db) :db.type/ref #{})
            [n r] (reduce (fn [[n r] j]
                            (let [k (:key j)
                                  [v r] (if (and old (not (touched [e k])))
                                          [(get old k) r]
                                          (join db e m j (conj p k) (get old k) r touched))
                                  n (if (or (= k (:attr j)) (contains? refs (:attr j))) (dissoc n (:attr j)) n)]
                              [(if (or (nil? v) (and (map? v) (empty? v) (many? db j))) (dissoc n k) (assoc n k v)) r]))
                          [m r] (:joins s))]
        ;; a node holding what it held, every value the same object, is the node it was
        [(if (same-node? old n) old n) r]))))

(def ^:private environment-keys "The map's environment: the record's basis, never written." [:t :branch :commit])

(defn- environment
  "The map's environment: the db's t, the branch and the commit the db is (none for a value as of
  an earlier t: its basis names it)."
  {:malli/schema [:=> [:cat :seon.host/db :any] :map]}
  [db branch] (let [c (d/commit-id db)] (cond-> {:t (:max-tx db) :branch branch} c (assoc :commit c))))

;; ---- provenance: a query on the tx-meta (docs/ctx.md#provenance) --------------------------

(defn- with-environment
  "Map t with db's environment on branch in place of the one it held (environment-keys)."
  {:malli/schema [:=> [:cat :map :seon.host/db :any] :map]}
  [t db branch] (merge (apply dissoc t environment-keys) (environment db branch)))

(defn build
  "The record of entity e on db as ctx's value {:tree :ix}: e pulled by its pattern
  (pattern-of: the agent's own edit, else its entity schema's), and its environment on branch,
  the db's data only (nothing derived from it is held here: a render derives what it needs);
  :ix the walk's state (node: where each entity is held, its pattern, what each join watches).
  Any entity an entity schema names: an agent's (its @ctx), root's, the user's, a task's. With no
  e (an agent whose entity its branch does not hold yet), its environment alone. O(the entities
  the pattern reaches)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe pos-int?] :any] [:map [:tree :map] [:ix :seon.ctx/ix]]]}
  [db e branch]
  (let [m (when e (own db e))
        [tree ix] (if (and m (< 1 (count m))) (node db e (render/pattern (pattern-of m)) [] {} nil {}) [{} {}])]
    {:tree (with-environment tree db branch) :ix ix}))

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
  "What datoms change of the record's state r, each to the datoms that touch it: each held entity
  a datom is of (its own attributes), each [e key] whose join's members a datom adds, drops or
  renames (a forward join of e's attribute; a :watch key, [a v], a reverse join's or a :by
  join's). A map: (touched x) answers whether x was touched, and with what."
  {:malli/schema [:=> [:cat :seon.ctx/ix [:sequential :any]] [:map-of :any [:vector :any]]]}
  [r datoms]
  (let [add (fn [t x d] (update t x (fnil conj []) d))]
    (reduce (fn [t {:keys [e a v] :as d}]
              (let [s (get-in r [:spec e])
                    t (reduce #(add %1 %2 d) t (get-in r [:watch [a v]]))
                    t (if (and s (or (:star? s) (contains? (:attrs s) a) (some #(= a (:attr %)) (:joins s)))) (add t e d) t)]
                (if s (reduce #(add %1 %2 d) t (for [j (:joins s) :when (and (not (:rev? j)) (= a (:attr j)))] [e (:key j)])) t)))
            {} datoms)))

(defn- share
  "Map n with every subtree equal to old's at its path old's object (identical?): a fresh build
  shares what it did not change with the record it replaces, children first, so a node whose
  children all come back the same is old's node. Work: n's size, once."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [o n]
  (cond (identical? o n) o
        (and (map? o) (map? n))
        (let [n (reduce-kv (fn [m k v] (let [x (share (get o k) v)] (if (identical? x v) m (assoc m k x)))) n n)]
          (if (and (= (count o) (count n)) (every? (fn [[k v]] (identical? v (get o k ::none))) n)) o n))
        (= o n) o
        :else n))

(defn patch
  "ctx's value c with datoms (a transaction's :tx-data, or a datoms-diff marked :added) applied
  on db, the db after them: each held entity they touch (touched-by) rebuilt where it is held,
  shortest path first, as node builds it, keeping each join and member nothing touched
  (identical?), the path above it copied; a window of ids issued in time order moves by its own
  datoms (window-moves), any other collection is read again. Work: the touched entities and the members their changed joins read, never the record.
  Every path the change did not reach holds the same object it held before, so what changed is
  read off the two values (changes) in the work of what differs. A change of the pattern itself,
  or one moving where an entity is held (a new path reaching a held one, its holder leaving),
  builds the record anew, as a fresh build places it, sharing every subtree it did not change
  (share). The environment (:t :branch :commit) is the
  root map's, new each transaction."
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :seon.ctx/ix]] :seon.host/db [:sequential :any] :any] [:map [:tree :map] [:ix :seon.ctx/ix]]]}
  [c db datoms branch]
  (let [r (:ix c) root (get-in c [:tree :db/id])
        touched (touched-by r datoms)
        ;; a fresh build shares every subtree it did not change with the record before (share)
        fresh #(update (build db root branch) :tree (fn [t] (merge (share (apply dissoc (:tree c) environment-keys) (apply dissoc t environment-keys)) (select-keys t environment-keys))))]
    (cond
      (nil? root) (fresh)
      (some #(and (= root (:e %)) (= :seon.ctx/pattern (:a %))) datoms) (fresh)
      (empty? touched) (update c :tree with-environment db branch)
      :else
      (let [sites (sort-by (comp count first)
                           (distinct (for [x (keys touched) :let [e (if (vector? x) (first x) x) h (get-in r [:held e])] :when h] [h e])))
            [tree r] (reduce (fn [[tree r] [h e]]
                                  (let [o (if (empty? h) tree (get-in tree h))]
                                    (if (or (nil? o) (stub? o) (not= e (:db/id o)))
                                      [tree r]
                                      (let [[n r] (node db e (get-in r [:spec e]) h (assoc r :patching true) o touched)]
                                        ;; a rebuild that changed nothing is the node it was (node): the tree as it was
                                        [(if (identical? o n) tree (if (empty? h) n (assoc-in tree h n))) r]))))
                                [(:tree c) r] sites)]
        (if (or (:relocate r) (not (seq (dissoc tree :db/id))) (empty? (d/datoms db :eavt root)))
          (fresh)
          {:tree (with-environment tree db branch)
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
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :seon.ctx/ix]] [:map [:t :int] [:ops [:sequential :any]] [:db :seon.host/db]] :any]
                  [:map [:tree :map] [:ix :seon.ctx/ix]]]}
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
  nothing, naming its parent by the join's ref, its identity in its value), gone (retracted whole)
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
             (as-> acc acc
                 (reduce-kv (fn [acc k m]
                              (let [o (get om k)]
                                (cond
                                  (identical? o m) acc
                                  (or (stub? o) (stub? m)) (refuse acc (conj path k) "held at another path: change it there")
                                  ;; a new member, under any key a swap! names it by: written from its value,
                                                  ;; which holds its own identity, under a tempid unique in the transaction
                                  (and (nil? o) (some-> (identities (render/entity-schemas attr)) (#(nil? (get m %)))))
                                  (refuse acc (conj path k) (str "a new member names its identity: " (identities (render/entity-schemas attr))))
                                  (nil? o) (let [t (str "ctx" (count (:tx acc)))]
                                             (-> (edits db sub {} m t (conj path k) acc)
                                                 (update :tx conj [:db/add t attr e])
                                                 ;; its path names it by the swap!'s key: the record holds it by its id
                                                 (update :named assoc (conj path k) t)))
                                  :else (edits db sub o m (:db/id o) (conj path k) acc))))
                            acc (or nm {}))
                 (reduce-kv (fn [acc k o] (if (contains? nm k) acc (-> acc (update :tx conj [:db/retractEntity (:db/id o)]) (update :paths conj (conj path k)))))
                            acc (or om {})))
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
    ;; a pattern the walk cannot read is refused before anything is written (seon.render/pattern throws why)
    (when (and (map? nv) (not= (:seon.ctx/pattern nv) (:seon.ctx/pattern cur)) (some? (:seon.ctx/pattern nv))) (render/pattern (:seon.ctx/pattern nv)))
    (let [{:keys [tx invalid paths named]} (edits db (get-in c [:ix :spec (:db/id cur)] {:joins []}) cur nv (:db/id cur) [] {:tx [] :invalid [] :paths [] :named {}})]
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
              ;; a path through a member the swap! named: read in the record at the id the transaction gave it
              by-id (fn [p] (reduce (fn [p' [q t]] (if (and (<= (count q) (count p)) (= q (subvec p 0 (count q))))
                                                     (assoc p' (dec (count q)) (get (:tempids rep) t))
                                                     p'))
                                    p named))
              unheld (vec (remove #(holds? (get-in nv %) (get-in held' (by-id %))) paths))]
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
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :seon.ctx/ix]]] :nil]}
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
  {:malli/schema [:=> [:cat [:map [:tree :map] [:ix :seon.ctx/ix]] :seon.host/db :seon.host/db :any] [:map [:tree :map] [:ix :seon.ctx/ix]]]}
  [c0 db0 db1 branch] (patch c0 db1 (moved db0 db1 nil) branch))

(defn- tree-of "A record's map ({:tree t}), or the map itself." {:malli/schema [:=> [:cat :map] :map]}
  [c] (if (contains? c :tree) (:tree c) c))

(defn- walk-of
  "The steps changes takes from a to b, sketched as its walk goes (its bound's terms): :keys, the keys
  of both sides of each entity or collection pair that is not the same object (the root's
  environment left out, as changes leaves it); :eq, the elements compared by = where neither is
  walked into; :changes, what it answers. It restates changes' walk on purpose: a bound is the
  algorithm sketched beside it, and the walk's predicates stay local to it (a contracted helper
  would add an armed call to every key it visits)."
  {:malli/schema [:=> [:cat :any :any] [:map [:keys nat-int?] [:eq nat-int?] [:changes nat-int?]]]}
  [a b]
  (let [entity? (fn [x] (and (map? x) (contains? x :db/id)))
        members? (fn [x] (and (map? x) (not (entity? x)) (every? entity? (vals x))))
        size (fn size [x] (cond (map? x) (reduce-kv (fn [n k v] (+ n 1 (size k) (size v))) 0 x)
                                (coll? x) (reduce (fn [n v] (+ n 1 (size v))) 0 x)
                                :else 1))
        go (fn go [acc root? a b]
             (cond (identical? a b) acc
                   (or (nil? a) (nil? b)) (update acc :changes inc)
                   (or (and (entity? a) (entity? b) (= (:db/id a) (:db/id b))) (and (members? a) (members? b)))
                   (let [ks (cond->> (distinct (concat (keys a) (keys b))) root? (remove (set environment-keys)))]
                     (reduce (fn [acc k] (go acc false (get a k) (get b k))) (update acc :keys + (count a) (count b)) ks))
                   :else (cond-> (update acc :eq + (min (size a) (size b))) (not= a b) (update :changes inc))))]
    (go {:keys 0 :eq 0 :changes 0} true a b)))

(def change-steps
  "What each step of changes' walk costs, measured once with the contracts on, on a case that
  isolates it (seon.ctx-test/changes-step-costs-are-current measures them again): the call itself,
  a key visited (sorted gets through the members' comparator, the members? scan, distinct over
  concat; at collections of 100 to 1,000 members), an element compared by =, and a change (its
  tuple, and its path printed for the sort, at 100 to 1,000 changes)."
  {:fixed {:ticks 148.0 :bytes 1008.0} :key {:ticks 927.0 :bytes 2980.0} :eq {:ticks 1.0 :bytes 0.0} :change {:ticks 3788.0 :bytes 30810.0}})

(defn- changes-bound
  "What one call of changes may cost on c0 and c1: its fixed part and each step of its walk
  (walk-of), each at its measured cost (change-steps). :out, how many changes it answers."
  {:malli/schema [:=> [:cat :map :map] [:map [:ticks number?] [:bytes number?] [:out [:map [:changes nat-int?]]]]]}
  [c0 c1]
  (let [w (walk-of (tree-of c0) (tree-of c1))
        {:keys [fixed]} change-steps]
    (-> (into {} (for [d [:ticks :bytes]]
                   [d (+ (get fixed d) (* (:keys w) (get-in change-steps [:key d])) (* (:eq w) (get-in change-steps [:eq d]))
                         (* (:changes w) (get-in change-steps [:change d])))]))
        (assoc :out {:changes (:changes w)}))))

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
  {:malli/schema [:=> [:cat :map :map] [:vector [:tuple [:enum :add :remove :replace] [:vector :any] :any]]]
   :seon.cost/bound (fn [c0 c1] (changes-bound c0 c1))}
  [c0 c1]
  (let [entity? (fn [x] (and (map? x) (contains? x :db/id)))
        members? (fn [x] (and (map? x) (not (entity? x)) (every? entity? (vals x))))
        walk (fn walk [acc p a b]
               (cond (identical? a b) acc
                     (nil? a) (conj acc [:add p b])
                     (nil? b) (conj acc [:remove p nil])
                     ;; one entity's two nodes, or two collections: into their keys; another entity
                     ;; under the same key (retracted and made again) is replaced whole
                     (or (and (entity? a) (entity? b) (= (:db/id a) (:db/id b))) (and (members? a) (members? b)))
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
      (= (:max-tx a) (:max-tx b)) (install! (update @held :tree with-environment b (:branch n)))
      :else (let [c (patch @held b (moved a b (:tx n)) (:branch n))]
              ;; a swap!'s own transaction: installed when the swap! ends (the atom is mid-swap)
              (if *writing* (do (vreset! held c) (vreset! pending c)) (install! c))))))

(add-watch agent/state ::ctx on-state)
;; nil only while there is no record yet (as the validator is set, before an agent's db)
(set-validator! ctx (fn [nv] (or *installing* (and (nil? nv) (nil? @held)) (write! nv))))
;; a swap! written: the record as the db holds it (its new entities' ids) replaces the swap!'s map
(add-watch ctx ::written (fn [_ _ _ _] (when-let [c @pending] (vreset! pending nil) (install! c))))
