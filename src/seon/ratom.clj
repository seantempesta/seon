(ns seon.ratom
  "Reagent's reactive atoms on the server: reagent.ratom (reagent-project/reagent fa4dc05,
  src/reagent/ratom.cljs) ported, its names and behaviour kept: RAtom, Reaction, deref capture
  (*ratom-context*), run-in-reaction, flush!, dispose! and add-on-dispose!, with the store (`store`) a source a
  reaction derefs. Each deref inside a reaction registers what it read; a change re-runs only the reactions
  that read what changed, and a reaction whose new value is = to its old signals nothing on.

  One change, the owner's (\"I'd rather infer the cursor than require it\"; docs/decisions.md): the
  cursor is inferred. A render reads ctx through a tracking view (`view`), a map that delegates
  to the real value and records each path it looks up, as Vue 3's reactive proxy and MobX's
  observable map do. A run's reads are recorded once (`reads-of`): what it read through views, and
  what it read of the store (Datahike's recorder, as a reaction's key set). The walk kept as a
  value (seon.render/walk) keeps them, and a change reaches a run only when a changed path and a
  read path are prefixes of one another (a deep read; a shallow one only from above; an iterated
  node by a direct value added, removed or replaced), the changed paths from the tree's one diff
  (seon.ctx/changes), matched against the one index of the reads by path (`read-index`,
  `reached`). The printer and the page writer take the real value (`unwrap`, `realize`), each such
  read whole.

  Reagent's JavaScript-only parts, and what stands for them here: its scheduler
  (requestAnimationFrame, batching/schedule) is the commit: flush! runs at a non-reactive deref,
  Reagent's own rule, and after a commit (`commit!`); a page's render queue is the walk's, drained
  in mount order. docs/render.md#reactive-renders."
  (:refer-clojure :exclude [defn defn- atom run!])
  (:require [seon.contract :refer [defn defn-]] [datahike.reads :as reads] [datahike.index.interface] [clojure.core.async :as a] [seon.db :as pdb]))

(defprotocol IReaction
  "A reaction's own state, read and set by this namespace's functions (Reagent's JavaScript
  fields)."
  (-get [r k] "The field k.")
  (-set! [r k v] "Set field k.")
  (-handle-change [r sender old new] "Reagent's _handle-change: a source it watches moved.")
  (-update-watching [r derefed] "Reagent's _update-watching: watch what the last run derefed.")
  (-queued-run [r] "Reagent's _queued-run: run again when dirty and still watching.")
  (-run [r check] "Reagent's _run: run f capturing its derefs; check, keep a throw as its state.")
  (-set-opts [r opts] "Reagent's _set-opts: :auto-run :on-set :on-dispose :no-cache."))

(defprotocol IDisposable
  (dispose! [this] "Stop watching; a reaction with no watchers left disposes itself.")
  (add-on-dispose! [this f] "Call (f this) when it is disposed."))

(def ^:dynamic *ratom-context*
  "The reaction whose run is in progress: each deref inside it is recorded as one of its
  sources (Reagent's)."
  nil)

;; Reagent keeps its queue and running count process-wide, one JavaScript thread; here both are an owner's (the proc whose step
;; runs now), so no process-wide state is shared: preempted fibers lost 267,087 of 400,000 enqueues on the shared queue (flow-lab
;; e18), and a fork copied a non-empty one. docs/flow.md#the-five-rules.
(def ^:dynamic *owner*
  "The owner of reactions whose step runs now (`owner`), whose flush! alone runs them; nil: a dirtied reaction runs at its next
  deref. See docs/reactive.md#how-a-commit-reaches-its-readers-today."
  nil)

(defn owner
  "A fresh owner of reactions {:queue :running :port}, held in its proc's state and bound as *owner* around its steps; port, where
  another owner's step sends its dirtied reactions (nil: they wait for their next deref). See
  docs/reactive.md#how-a-commit-reaches-its-readers-today."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:maybe :seon.host/chan]] :map]]}
  ([] (owner nil))
  ([port] {:queue (volatile! nil) :running (volatile! 0) :port port}))

(defn running
  "How many watches the reactions of the owner bound now hold (Reagent's (running)): back where it was once every reaction it
  made is disposed; 0 with no owner."
  {:malli/schema [:=> [:cat] :int]}
  [] (if-let [o *owner*] @(:running o) 0))

(defn- check-watches
  "new, the watches map, counted against old into the running count of the owner bound now."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] [:maybe :map]]}
  [old new] (when-let [o *owner*] (vswap! (:running o) + (- (count new) (count old)))) new)

(defn- notify-w
  "Call each watch of this: (f k this old new)."
  {:malli/schema [:=> [:cat :any [:maybe :map] :any :any] :nil]}
  [this watches old new] (doseq [[k f] watches] (f k this old new)) nil)

(defn- notify-deref-watcher!
  "Record derefed as a source of the reaction in progress: a reactive object, or [ref path kind]
  for a read through a view."
  {:malli/schema [:=> [:cat :any] :nil]}
  [derefed]
  (when-some [r *ratom-context*]
    (-set! r :captured (conj (or (-get r :captured) []) derefed)))
  nil)

(defprotocol IStore
  "The store as a source a reaction derefs (docs/plan.md#the-root-concept-one-reaction-over-the-store)."
  (-moved [s] "The datoms of the commit that made its db, which its watchers are matched against.")
  (-owner [s] "The owner of reactions whose state the store is (the proc it was made in), or nil.")
  (-set-keys [s r ks] "Reaction r reads keys ks of the store (nil: none any more): its entry in the store's index, set in the store
  owner's step (`store-keys!`).")
  (commit! [s db datoms] "s at db, made by a commit of datoms: each reaction that read one of their keys runs again, in the caller (an
  auto-run one at once, the rest at the next flush!). Its caller is the readers' owner proc, taking the commit's datoms as work with a
  declared bound, never the writer's step; a reaction re-run inside that step never parks on another proc (the step-fn contract):
  one that needs another proc's answer asks and is re-run on the answer (docs/flow.md#flow-inside-an-agent)."))

(deftype Store [^:volatile-mutable db ^:volatile-mutable moved ^:volatile-mutable watches
                ^:volatile-mutable idx ^:volatile-mutable rkeys owner]
  clojure.lang.IDeref
  (deref [this] (notify-deref-watcher! this) db)
  clojure.lang.IRef
  (addWatch [this k fw] (set! watches (check-watches watches (assoc watches k fw))) this)
  (removeWatch [this k] (set! watches (check-watches watches (dissoc watches k))) this)
  (getWatches [_] watches)
  (getValidator [_] nil)
  (setValidator [_ _] (throw (ex-info "a store takes no validator" {})))
  IStore
  (-moved [_] moved)
  (-owner [_] owner)
  (-set-keys [_ r ks]
    (set! idx (cond-> (pdb/reads-drop idx r (get rkeys r #{})) (seq ks) (pdb/reads-add r ks)))
    (set! rkeys (if (seq ks) (assoc rkeys r ks) (dissoc rkeys r))))
  (commit! [this db' datoms]
    ;; the readers of what the datoms moved, from the index as it is now (a rerun below changes it): d x 5 lookups, whatever the
    ;; readers (seon.db/readers); a watch that is not a reaction is told of every commit, as before
    (let [old db rs (pdb/readers idx datoms)]
      (set! db db')
      (set! moved datoms)
      (notify-w this watches old db')
      (doseq [r rs] (-handle-change r this old db')))
    this))

(defn store
  "The store at db as a source: a deref inside a reaction answers db and makes the store one of the reaction's sources, and
  what the run read of it is recorded by Datahike's recorder (datahike.reads) as the reaction's key set; `commit!` re-runs a
  reaction only when the commit's datoms touch that set: the store keeps every reader's keys in one index (seon.db/reads-add), and a
  commit asks it for the readers of what it moved (seon.db/readers), d x 5 lookups whatever the readers. See
  docs/plan.md#the-root-concept-one-reaction-over-the-store."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.host/deref]}
  [db] (Store. db nil nil {} {} *owner*))

(defn- store-keys!
  "Reaction r reads keys ks of store s (nil: none any more): set in s's index now when s is the bound owner's (or has none), else sent
  to s's owner as {:seon.ratom/keys [s r ks]} on its port, never written from another owner's step (`take-keys!`). See
  docs/reactive.md#how-a-commit-reaches-its-readers-today."
  {:malli/schema [:=> [:cat :any :any [:maybe [:set :any]]] :nil]}
  [s r ks]
  (let [o (-owner s)]
    (cond (or (nil? o) (identical? o *owner*)) (-set-keys s r ks)
          (:port o) (a/put! (:port o) {:seon.ratom/keys [s r ks]})
          :else nil))
  nil)

(defn take-keys!
  "Message m, {:seon.ratom/keys [s r ks]} on the bound owner's port, taken in its step: r's keys set in store s's index. nil. See
  docs/reactive.md#how-a-commit-reaches-its-readers-today."
  {:malli/schema [:=> [:cat :map] :nil]}
  [m] (let [[s r ks] (:seon.ratom/keys m)] (-set-keys s r ks)) nil)

;; the recorder outside every reaction, while a run is in progress (`deref-capture`): nil when none was bound
(def ^:dynamic ^:private *outer-reads* nil)

(defn- deref-capture
  "(f) in r's context: its derefs become r's sources (watched when they differ from the last
  run's), and every read of a db it made, recorded by Datahike's recorder, r's key set (the
  recorder is bound before the run, since a read follows the deref that gave its db). Clears
  r's dirty flag. Reagent's, with the reads recorded."
  {:malli/schema [:=> [:cat :seon.host/fn :any] :any]}
  [f r]
  (-set! r :captured nil)
  (let [;; the recorder outside every reaction (a read of the render proc's pass): taken at the outermost run, where no reaction is
        ;; in progress, and handed down, so a nested run's reads reach it and never its parent run's (each run's reads are its own)
        outer (or *outer-reads* (when (nil? *ratom-context*) datahike.index.interface/*reads*))
        ;; the recorder with-reads composes into at its return is the outermost, never the parent run's: a parent depends on
        ;; its child's value, not on what the child read (Reagent's rule); at the outermost run that is the binding already in force
        [res rs] (binding [*outer-reads* outer datahike.index.interface/*reads* outer]
                   (reads/with-reads #(binding [*ratom-context* r] (f))))
        c (-get r :captured)]
    (-set! r :dirty? false)
    ;; every read the run made is a dependency, however it reached the db: the one recorder (docs/plan.md#the-root-concept-one-reaction-over-the-store)
    (-set! r :key-set (when (seq rs) (reads/reads-index rs)))
    (when-not (= c (-get r :last)) (-update-watching r c))
    ;; its keys in each store it read: the store's index, which a commit asks for the readers of what it moved (`store-keys!`)
    (doseq [w (keys (-get r :watching)) :when (instance? Store w)] (store-keys! w r (-get r :key-set)))
    res))

(defn reads-of
  "(f) run in no reaction, its reads recorded as a reaction's run records them (`deref-capture`, the one recorder), answering [its
  value, the reads it made through views of reference ref: #{[path kind]}, its key set: the datom keys of its reads of a db
  (datahike.reads/reads-index), nil when it read none]: what a walk kept as a value (seon.render/walk) keeps of a component's
  run, matched against the tree's next diff (`reached`) and the next datoms moved. See docs/render.md#a-page-is-a-kept-walk."
  {:malli/schema [:=> [:cat :any :seon.host/fn] [:tuple :any [:set [:tuple [:vector :any] :keyword]] [:maybe [:set :any]]]]}
  [ref f]
  (let [cap (volatile! nil) ks (volatile! nil)
        r (reify IReaction
            (-get [_ k] (when (= k :captured) @cap))
            (-set! [_ k v] (case k :captured (vreset! cap v) :key-set (vreset! ks v) nil))
            (-handle-change [_ _ _ _] nil)
            (-update-watching [_ _] nil)
            (-queued-run [_] nil)
            (-run [_ _] nil)
            (-set-opts [_ _] nil))
        v (deref-capture f r)]
    [v (into #{} (keep (fn [x] (when (and (vector? x) (identical? ref (:ref (first x)))) [(nth x 1) (nth x 2)]))) @cap) @ks]))

;;; --- which reads a change reaches ---------------------------------------------------------

(defn prefixes
  "Every prefix of path p, [] and p included."
  {:malli/schema [:=> [:cat [:vector :any]] [:sequential [:vector :any]]]}
  [p] (map #(subvec p 0 %) (range (inc (count p)))))

;;; --- Atom -------------------------------------------------------------------------------

(deftype RAtom [^:volatile-mutable state meta validator ^:volatile-mutable watches]
  clojure.lang.IDeref
  (deref [this] (notify-deref-watcher! this) state)
  clojure.lang.IAtom
  (reset [a new-value]
    (when-not (nil? validator) (assert (validator new-value) "Validator rejected reference state"))
    (let [old-value state]
      (set! state new-value)
      (when-not (nil? watches) (notify-w a watches old-value new-value))
      new-value))
  (swap [a f] (.reset a (f state)))
  (swap [a f x] (.reset a (f state x)))
  (swap [a f x y] (.reset a (f state x y)))
  (swap [a f x y more] (.reset a (apply f state x y more)))
  (compareAndSet [a old new] (if (identical? old state) (do (.reset a new) true) false))
  clojure.lang.IRef
  (addWatch [this k f] (set! watches (check-watches watches (assoc watches k f))) this)
  (removeWatch [this k] (set! watches (check-watches watches (dissoc watches k))) this)
  (getWatches [_] watches)
  (getValidator [_] validator)
  (setValidator [_ _] (throw (ex-info "an RAtom's validator is given when it is made" {})))
  clojure.lang.IMeta
  (meta [_] meta)
  clojure.lang.IObj
  (withMeta [_ m] (RAtom. state m validator watches)))

(defn atom
  "Like clojure.core/atom, except that it keeps track of derefs (Reagent's)."
  {:malli/schema [:function [:=> [:cat :any] :seon.host/deref] [:=> [:cat :any [:* :any]] :seon.host/deref]]}
  ([x] (RAtom. x nil nil nil))
  ([x & {:keys [meta validator]}] (RAtom. x meta validator nil)))

;;; --- the tracking view: an inferred cursor ---------------------------------------------------

(declare view sorted-view)

(defn view?
  "Is x a tracking view?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x] (or (instance? seon.ratom.View x) (instance? seon.ratom.SortedView x)))

(defn- wrap
  "Value v found at path p of source src: a view of it when it is a map (a :shallow read: its
  presence, the reads inside it its own), else v, read as kind."
  {:malli/schema [:=> [:cat :map :any [:vector :any] :keyword] :any]}
  [src v p kind]
  (if (map? v)
    (do (notify-deref-watcher! [src p :shallow]) (if (sorted? v) (sorted-view src v p) (view src v p)))
    (do (notify-deref-watcher! [src p kind]) v)))

(defn- entries
  "The entries es of map m at path, each map value a view (its reads its own), each other value as
  it is: what iterating a view answers. The caller reads the node's key set (:keys), which a
  direct value replaced reaches too (Vue 3's iteration), so iterating records one read, not one an
  entry."
  {:malli/schema [:=> [:cat :map :map [:vector :any] [:maybe [:sequential :any]]] [:sequential :any]]}
  [src m path es]
  (map (fn [e] (let [k (key e) v (val e)]
                 (if (map? v)
                   (clojure.lang.MapEntry. k (let [p (conj path k)] (if (sorted? v) (sorted-view src v p) (view src v p))))
                   e)))
       es))

(defn unwrap
  "The real value under view x, read whole (:deep); any other x as it is. What the printer and
  the page writer take."
  {:malli/schema [:=> [:cat :any] :any]}
  [x]
  (if (view? x) (let [src (.-src x)] (notify-deref-watcher! [src (.-path x) :deep]) (.-m x)) x))

(defn key-set
  "The keys of map x, recorded for a view as a read of its key set alone (`read-index`'s :key-set): reached when a key is added or
  removed, never when a value is replaced, as (keys x) is not (an iteration, which reads each direct value too). What a reader of which
  keys an entity holds asks. See docs/render.md#reactive-renders."
  {:malli/schema [:=> [:cat [:maybe :map]] [:set :any]]}
  [x] (if (view? x) (.keySet ^java.util.Map x) (set (keys x))))

(defn untracked
  "View x's value with no read recorded (any other x as it is): for a reader of what is written once, an entity's id, which a read per member would make a list's reading cost a read per member. See docs/render.md#reactive-renders."
  {:malli/schema [:=> [:cat :any] :any]}
  [x] (if (view? x) (.-m x) x))

(defn realize
  "x with every view in it (its own, its map values', its vector's members) its real value,
  each read whole."
  {:malli/schema [:=> [:cat :any] :any]}
  [x]
  (cond (view? x) (unwrap x)
        (map? x) (if (some view? (vals x)) (reduce-kv (fn [m k v] (assoc m k (realize v))) x x) x)
        (vector? x) (if (some view? x) (mapv realize x) x)
        :else x))

(defmacro ^:private defview
  "A tracking view type T: a map over m, the value at path of source src, recording each read.
  extra: the interfaces a sorted map adds."
  [T extra]
  `(deftype ~T [~'src ~'m ~'path]
     clojure.lang.ILookup
     (~'valAt [~'_ ~'k] (wrap ~'src (get ~'m ~'k) (conj ~'path ~'k) :deep))
     (~'valAt [~'_ ~'k ~'nf] (if (contains? ~'m ~'k)
                               (wrap ~'src (get ~'m ~'k) (conj ~'path ~'k) :deep)
                               (do (notify-deref-watcher! [~'src (conj ~'path ~'k) :shallow]) ~'nf)))
     clojure.lang.IPersistentMap
     (~'assoc [~'this ~'k ~'v] (assoc (unwrap ~'this) ~'k ~'v))
     (~'assocEx [~'this ~'k ~'v] (assoc (unwrap ~'this) ~'k ~'v))
     (~'without [~'this ~'k] (dissoc (unwrap ~'this) ~'k))
     clojure.lang.Associative
     (~'containsKey [~'_ ~'k] (notify-deref-watcher! [~'src (conj ~'path ~'k) :shallow]) (contains? ~'m ~'k))
     (~'entryAt [~'this ~'k] (when (contains? ~'m ~'k) (clojure.lang.MapEntry. ~'k (.valAt ~'this ~'k))))
     clojure.lang.IPersistentCollection
     (~'count [~'_] (notify-deref-watcher! [~'src ~'path :key-set]) (count ~'m))
     (~'cons [~'this ~'x] (conj (unwrap ~'this) ~'x))
     (~'empty [~'_] (empty ~'m))
     (~'equiv [~'this ~'o] (= (unwrap ~'this) (if (view? ~'o) (unwrap ~'o) ~'o)))
     clojure.lang.Seqable
     (~'seq [~'_] (notify-deref-watcher! [~'src ~'path :keys]) (seq (entries ~'src ~'m ~'path (seq ~'m))))
     clojure.lang.Counted
     clojure.lang.MapEquivalence
     ;; what a persistent map's = asks of the other side (APersistentMap.equiv), so (= m view)
     ;; holds as (= view m) does
     java.util.Map
     (~'size [~'_] (notify-deref-watcher! [~'src ~'path :key-set]) (count ~'m))
     (~'isEmpty [~'_] (notify-deref-watcher! [~'src ~'path :key-set]) (empty? ~'m))
     (~'get [~'this ~'k] (.valAt ~'this ~'k))
     (~'entrySet [~'this] (set (seq ~'this)))
     (~'keySet [~'_] (notify-deref-watcher! [~'src ~'path :key-set]) (set (keys ~'m)))
     (~'values [~'this] (vals ~'this))
     clojure.lang.IFn
     (~'invoke [~'this ~'k] (.valAt ~'this ~'k))
     (~'invoke [~'this ~'k ~'nf] (.valAt ~'this ~'k ~'nf))
     clojure.lang.IMeta
     (~'meta [~'_] (meta ~'m))
     Object
     (~'equals [~'this ~'o] (.equiv ~'this ~'o))
     (~'hashCode [~'this] (hash (unwrap ~'this)))
     (~'toString [~'this] (str (unwrap ~'this)))
     ~@extra))

(defview View [])
(defview SortedView
  [clojure.lang.Reversible
   (rseq [_] (notify-deref-watcher! [src path :keys]) (seq (entries src m path (rseq m))))
   clojure.lang.Sorted
   (comparator [_] (.comparator ^clojure.lang.Sorted m))
   (entryKey [_ e] (key e))
   (seq [_ asc] (notify-deref-watcher! [src path :keys]) (seq (entries src m path (.seq ^clojure.lang.Sorted m asc))))
   (seqFrom [_ k asc] (notify-deref-watcher! [src path :keys]) (seq (entries src m path (.seqFrom ^clojure.lang.Sorted m k asc))))])

(defn view
  "Map m, the value at path of source src ({:ref r :changes f}: the reference read, and the
  diff that names what a reset! of it changed, [[op path ...] ...]), as a tracking view; m nil (no
  value there) a view of nothing, every lookup nil."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:vector :any]] :map]}
  [src m path] (View. src m path))

(defn sorted-view
  "Sorted map m as a tracking view (view), sorted as m is."
  {:malli/schema [:=> [:cat :map :map [:vector :any]] :map]}
  [src m path] (SortedView. src m path))

(defn ref-view
  "The value of reference r as a tracking view at its root: its reads are r's, the changes of a
  reset! of r named by (changes old new) (seon.ctx/changes for ctx)."
  {:malli/schema [:=> [:cat :seon.host/deref :seon.host/fn] :map]}
  [r changes]
  ;; the value now, not a read: the view's lookups are the reads
  (let [v (binding [*ratom-context* nil] @r)] (view {:ref r :changes changes} v [])))

(defn fresh
  "x as of now in source src (nil: x's own): a view made again over the source's current value at its path (a stored view holds
  the value it was made with; an RCursor reads its ratom at each deref), else x. A walk kept as a value (seon.render/walk) holds
  the views of last time's source and draws again over this time's."
  {:malli/schema [:=> [:cat [:maybe :map] :any] :any]}
  [src x]
  (if (view? x)
    (let [src (or src (.-src x)) p (.-path x) v (get-in (binding [*ratom-context* nil] @(:ref src)) p)]
      (if (and (map? v) (sorted? v)) (sorted-view src v p) (view src v p)))
    x))

;;; --- queueing ------------------------------------------------------------------------------

(defn- rea-enqueue
  "Queue reaction r, dirtied, for its owner o's flush!: in o's own step (o bound as *owner*) onto o's queue; in another owner's
  step, a message {:seon.ratom/dirty r} on o's port, never a write to another owner's queue (seam 3: a recompute crosses a proc
  only as a message); with no owner, or no port, nothing: r stays dirty and runs at its next deref."
  {:malli/schema [:=> [:cat :any [:maybe :map]] :nil]}
  [r o]
  (cond (nil? o) nil
        (identical? o *owner*) (vswap! (:queue o) (fnil conj []) r)
        (:port o) (a/put! (:port o) {:seon.ratom/dirty r})
        :else nil)
  nil)

(defn take-dirty!
  "Message m, {:seon.ratom/dirty r} on the bound owner's port, taken in its step: r queued for this owner's flush!. nil. See
  docs/reactive.md#how-a-commit-reaches-its-readers-today."
  {:malli/schema [:=> [:cat :map] :nil]}
  [m] (when-let [o *owner*] (vswap! (:queue o) (fnil conj []) (:seon.ratom/dirty m))) nil)

(defn flush!
  "Run every reaction the bound owner queued, and any each of those queues, until none is left (Reagent's): the end of the owner's
  step, and a non-reactive deref. With no owner bound, nothing is queued, so nothing runs."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (when-let [o *owner*]
    (loop []
      (when-let [q @(:queue o)]
        (vreset! (:queue o) nil)
        (doseq [r q] (-queued-run r))
        (recur))))
  nil)

;;; --- which reads a tree's change reaches -------------------------------------------------------

(defn read-index
  "Read index ix ({:pre :deep :keys :key-set}, each {path #{reader}}; nil: none) with reader r's reads of one source ([[path kind] ...])
  added (f conj) or removed (f disj): a read at path indexed at each of its prefixes (:pre), a whole read at its path (:deep), an
  iteration at the node it iterated (:keys), a key set at its node (:key-set, `key-set`). The one index a change is matched against (`reached`): a walk kept as a value holds
  it (seon.render/walk's :tree-readers). See docs/render.md#reactive-renders."
  {:malli/schema [:=> [:cat [:maybe :map] :any [:maybe [:or [:set [:tuple [:vector :any] :keyword]] [:sequential [:tuple [:vector :any] :keyword]]]] :seon.host/fn] :map]}
  [ix r reads f]
  (let [add (fn [m k] (let [v (f (get m k #{}) r)] (if (seq v) (assoc m k v) (dissoc m k))))]
    (reduce (fn [ix [p kind]]
              (cond-> (reduce #(update %1 :pre add %2) ix (prefixes p))
                (= kind :deep) (update :deep add p)
                (= kind :keys) (update :keys add p)
                (= kind :key-set) (update :key-set add p)))
            (or ix {:pre {} :deep {} :keys {} :key-set {}}) reads)))

(defn move-reads
  "Read index ix with reader r's reads moved from was to now (each [[path kind] ...]): the reads it lost out, the reads it gained
  in, and r back at each prefix of a lost read that a read it still holds also sits under (an index keeps one set of readers a
  path, so a lost read's disj takes r off the prefixes its held reads share). Equal to the index built from now whole, in
  writes proportional to what moved. See docs/render.md#reactive-renders."
  {:malli/schema [:=> [:cat [:maybe :map] :any [:maybe [:set [:tuple [:vector :any] :keyword]]] [:maybe [:set [:tuple [:vector :any] :keyword]]]] :map]}
  [ix r was now]
  (let [was (or was #{}) now (or now #{})
        lost (remove now was)
        ix (read-index (read-index ix r lost disj) r (remove was now) conj)
        under? (fn [p q] (and (<= (count p) (count q)) (= p (subvec q 0 (count p)))))]
    (reduce (fn [ix p] (if (some (fn [[q _]] (under? p q)) now) (update-in ix [:pre p] (fnil conj #{}) r) ix))
            ix (distinct (mapcat (comp prefixes first) lost)))))

(defn reached
  "The readers of read index ix (`read-index`) a diff's changes ([[op path ...] ...]) reach: a read at or below a changed path
  (:pre), a whole read above it (:deep), a node iterated whose direct value it added, removed or replaced (:keys), a node's key set one
  of whose keys it added or removed (:key-set). See
  docs/render.md#reactive-renders."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential [:vector :any]]] [:set :any]]}
  [ix changes]
  (let [{:keys [pre deep keys key-set]} ix]
    (reduce (fn [acc [op p]]
              (cond-> (into (into acc (get pre p)) (mapcat #(get deep %)) (prefixes p))
                (seq p) (into (get keys (pop p)))
                ;; a key set is reached by a key added or gone, never by a value replaced
                (and (seq p) (not= :replace op)) (into (get key-set (pop p)))))
            #{} changes)))

;;; --- reaction --------------------------------------------------------------------------------

(defn- handle-reaction-change
  "The watch a reaction puts on each source: Reagent's handle-reaction-change."
  {:malli/schema [:=> [:cat :any :any :any :any] :any]}
  [this sender old new] (-handle-change this sender old new))

(deftype Reaction [^:volatile-mutable f ^:volatile-mutable state ^:volatile-mutable dirty? ^:volatile-mutable nocache?
                   ^:volatile-mutable watching ^:volatile-mutable watches ^:volatile-mutable auto-run
                   ^:volatile-mutable caught ^:volatile-mutable captured ^:volatile-mutable last-captured
                   ^:volatile-mutable on-set ^:volatile-mutable on-dispose
                   ^:volatile-mutable on-dispose-arr ^:volatile-mutable key-set owner]
  clojure.lang.IRef
  (addWatch [this k fw] (set! watches (check-watches watches (assoc watches k fw))) this)
  (removeWatch [this k]
    (let [was-empty (empty? watches)]
      (set! watches (check-watches watches (dissoc watches k)))
      (when (and (not was-empty) (empty? watches) (nil? auto-run))
        (dispose! this)))
    this)
  (getWatches [_] watches)
  (getValidator [_] nil)
  (setValidator [_ _] (throw (ex-info "a Reaction takes no validator" {})))
  clojure.lang.IAtom
  (reset [a newval]
    (assert (fn? on-set) "Reaction is read only; on-set is not allowed")
    (let [oldval state]
      (set! state newval)
      (on-set oldval newval)
      (notify-w a watches oldval newval)
      newval))
  (swap [a g] (.reset a (g (-get a :peek))))
  (swap [a g x] (.reset a (g (-get a :peek) x)))
  (swap [a g x y] (.reset a (g (-get a :peek) x y)))
  (swap [a g x y more] (.reset a (apply g (-get a :peek) x y more)))
  (compareAndSet [a old new] (if (identical? old state) (do (.reset a new) true) false))
  IReaction
  (-get [this k]
    (case k :captured captured :last last-captured :watching watching :dirty? dirty? :state state
            :auto-run auto-run :key-set key-set :peek (binding [*ratom-context* nil] (.deref this))))
  (-set! [_ k v]
    (case k :captured (set! captured v) :dirty? (set! dirty? v) :f (set! f v) :auto-run (set! auto-run v) :key-set (set! key-set v)))
  (-handle-change [this sender oldval newval]
    ;; the store reaches it only by a commit touching a key its run read; every other source by Reagent's own watch
    ;; a store reaches it only when its index names it for the commit's datoms (Store's commit!); every other source by Reagent's watch
    (when-not (or (identical? oldval newval) dirty?)
      ;; run at once only in its owner's own step (or with no owner): in another owner's step it is dirtied and reaches its owner as
      ;; a message, an auto-run one too, so no reaction runs under another owner's bindings. The dirty? guard above keeps at most one
      ;; message outstanding a reaction, so an owner's port holds at most as many as the reactions it owns
      (if (and (some? auto-run) (or (nil? owner) (identical? owner *owner*)))
        ;; through the catching run (Reagent's _try-capture, failure-points.md's edge for a reaction's run): a reader that throws is its
        ;; own error, and the commit that reached it goes on to every other reader (web's BUG 2)
        (if (true? auto-run) (-run this true) (auto-run this))
        (do (set! dirty? true) (rea-enqueue this owner)))))
  (-update-watching [this derefed]
    ;; a reactive object is watched (Reagent's); a read through a view is no source: a walk kept as a value keeps it (`reads-of`)
    (let [new (set (remove vector? derefed))
          old (set (keys watching))]
      (set! last-captured derefed)
      (set! watching (zipmap new (repeat true)))
      ;; a store is no watch: its index holds this reaction's keys (deref-capture sets them, store-keys!), dropped here when it is read no more
      (doseq [w new :when (and (not (contains? old w)) (not (instance? Store w)))] (add-watch w this handle-reaction-change))
      (doseq [w old :when (not (contains? new w))] (if (instance? Store w) (store-keys! w this nil) (remove-watch w this)))))
  (-queued-run [this]
    (when (and dirty? (some? watching))
      (cond (nil? auto-run) (-run this true)
            (true? auto-run) (do (set! dirty? false) (-run this true))
            :else (do (set! dirty? false) (auto-run this)))))
  (-run [this check]
    (let [oldstate state
          res (if check
                ;; Reagent's _try-capture: a run that throws keeps the throw as its state, and its
                ;; next deref throws it (docs/failure-points.md, a reaction's run)
                (try (set! caught nil) (deref-capture f this)
                     (catch Throwable e (set! state e) (set! caught e) (set! dirty? false)))
                (deref-capture f this))]
      (when-not nocache?
        (set! state res)
        ;; Use = to determine equality from reactions, since they are likely to produce new data structures.
        (when-not (or (nil? watches) (= oldstate res))
          (notify-w this watches oldstate res)))
      res))
  (-set-opts [this opts]
    (when (some? (:auto-run opts)) (set! auto-run (:auto-run opts)))
    (when (some? (:on-set opts)) (set! on-set (:on-set opts)))
    (when (some? (:on-dispose opts)) (set! on-dispose (:on-dispose opts)))
    (when (some? (:no-cache opts)) (set! nocache? (:no-cache opts))))
  clojure.lang.IDeref
  (deref [this]
    (when-some [e caught] (throw e))
    (let [non-reactive (nil? *ratom-context*)]
      (when non-reactive (flush!))
      (if (and non-reactive (nil? auto-run))
        (when dirty?
          (let [oldstate state]
            (set! state (f))
            (when-not (or (nil? watches) (= oldstate state))
              (notify-w this watches oldstate state))))
        (do (notify-deref-watcher! this)
            (when dirty? (-run this false)))))
    state)
  IDisposable
  (dispose! [this]
    (let [s state wg watching]
      (set! watching nil)
      (set! last-captured nil)
      (set! state nil)
      (set! auto-run nil)
      (set! dirty? true)
      (doseq [w (keys wg)] (if (instance? Store w) (store-keys! w this nil) (remove-watch w this)))
      (when (some? on-dispose) (on-dispose s))
      (doseq [g on-dispose-arr] (g this))))
  (add-on-dispose! [_ g] (set! on-dispose-arr (conj (or on-dispose-arr []) g))))

(defn make-reaction
  "A Reaction of f (no arguments), Reagent's: :auto-run, run now and on each change of what it
  derefs (true: run it; a fn: called with the reaction); :on-set, called (old new) by a reset!
  before its watches; :on-dispose, called with its last value when it is disposed."
  {:malli/schema [:=> [:cat [:maybe :seon.host/fn] [:* :any]] :seon.host/deref]}
  [f & {:keys [auto-run on-set on-dispose]}]
  (let [r (Reaction. f nil true false nil nil nil nil nil nil nil nil nil nil *owner*)]
    (-set-opts r {:auto-run auto-run :on-set on-set :on-dispose on-dispose})
    r))

(defmacro reaction
  "A Reaction of body (Reagent's)."
  [& body] `(make-reaction (fn [] ~@body)))

(defmacro run!
  "A Reaction of body, run now and again whenever what it derefs changes; dispose! stops it
  (Reagent's)."
  [& body] `(let [co# (make-reaction (fn [] ~@body) :auto-run true)] (deref co#) co#))

(defn run-in-reaction
  "(f), and when it derefs anything a new Reaction that watches what it derefed and calls (run
  obj) on a change, with opts set on it: [result reaction-or-nil] (Reagent's sets the reaction
  on obj; here its caller holds it). How a component renders."
  {:malli/schema [:=> [:cat :seon.host/fn :any :seon.host/fn :map] [:tuple :any :any]]}
  [f obj run opts]
  (let [r (make-reaction nil)
        res (deref-capture f r)]
    (if (nil? (-get r :watching))
      [res nil]
      (do (-set-opts r opts) (-set! r :f f) (-set! r :auto-run (fn [_] (run obj))) [res r]))))

;;; --- Track: reagent.ratom's (fa4dc05 ratom.cljs 182-244) --------------------------------------

;; Reagent keeps the cache on f itself (.-reagReactionCache), one per JavaScript thread; here the cache is its owner's, keyed by
;; [f args]: a walk (or any owner of reactions) binds *tracks* to the cache its value holds, so no process-wide state is shared
(def ^:dynamic *tracks*
  "The track cache of the owner whose reactions run now, {[f args] reaction} in a volatile its value holds; nil: none, and a
  track runs its f at each deref."
  nil)

(defn- cached-reaction
  "The value of f, the reaction cached under k in the owner's *tracks*: an existing one derefed; outside a reaction or with no
  owner's cache, (f); else a new reaction, cached while anything watches it. Reagent's."
  {:malli/schema [:=> [:cat :seon.host/fn :any] :any]}
  [f k]
  (let [cache *tracks*
        r (some-> cache deref (get k))]
    (cond
      (some? r) (deref r)
      (or (nil? *ratom-context*) (nil? cache)) (f)
      :else (let [r (make-reaction f :on-dispose (fn [_] (vswap! cache dissoc k)))
                  v (deref r)]
              (vswap! cache assoc k r)
              v))))

(deftype Track [f args]
  clojure.lang.IDeref
  (deref [_] (cached-reaction #(apply f args) [f args]))
  Object
  (equals [_ o] (and (instance? Track o) (= f (.-f ^Track o)) (= args (.-args ^Track o))))
  (hashCode [_] (hash [f args])))

(defn make-track
  "A Track of f and args (Reagent's)."
  {:malli/schema [:=> [:cat :seon.host/fn [:maybe [:sequential :any]]] :seon.host/deref]}
  [f args] (Track. f args))

(defn make-track!
  "A Track of f and args, derefed by a reaction run now and on each change (Reagent's)."
  {:malli/schema [:=> [:cat :seon.host/fn [:maybe [:sequential :any]]] :seon.host/deref]}
  [f args] (let [t (make-track f args) r (make-reaction #(deref t) :auto-run true)] (deref r) r))

(defn track
  "(f & args) as a reactive value, = by f and args: inside a reaction one cached reaction shared by every equal track, run
  again only when what it read moved (Reagent's)."
  {:malli/schema [:=> [:cat :seon.host/fn [:* :any]] :seon.host/deref]}
  [f & args] (make-track f args))

(defn track!
  "track, run eagerly: a reaction that derefs it now and on each change; dispose! stops it (Reagent's)."
  {:malli/schema [:=> [:cat :seon.host/fn [:* :any]] :seon.host/deref]}
  [f & args] (make-track! f args))
