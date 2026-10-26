(ns seon.ratom
  "Reagent's reactive atoms on the server: reagent.ratom (reagent-project/reagent fa4dc05,
  src/reagent/ratom.cljs) ported, its names and behaviour kept: RAtom, Reaction, deref capture
  (*ratom-context*), run-in-reaction, flush!, dispose! and add-on-dispose!. A render runs in a
  reaction; each deref inside it registers what it read; a change re-runs only the reactions
  that read what changed, and a reaction whose new value is = to its old signals nothing on.

  One change, the owner's (2026-10-26: \"I'd rather infer the cursor than require it\"): the
  cursor is inferred. A render reads ctx through a tracking view (`view`), a map that delegates
  to the real value and records each path it looks up, as Vue 3's reactive proxy and MobX's
  observable map do. A reset! of the atom behind it dirties a reaction only when a changed path
  and a read path are prefixes of one another (a deep read; a shallow one only from above; an
  iterated node by a direct value added, removed or replaced), the changed paths from the diff the view was made
  with (seon.ctx/changes for ctx: the one diff), computed once a change and matched against an
  index of the reads by path (`source-changed`). The printer and the page writer
  take the real value (`unwrap`, `realize`), each such read whole.

  Reagent's JavaScript-only parts, and what stands for them here: its scheduler
  (requestAnimationFrame, batching/schedule) is the commit: flush! runs at a non-reactive deref,
  Reagent's own rule, and once per transaction after its patch, the server's frame; its render
  queue is the walk's, drained in mount order. docs/render.md#reactive-renders."
  (:refer-clojure :exclude [defn defn- atom run!])
  (:require [seon.contract :refer [defn defn-]]))

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

;; the running count: Reagent's debug counter, which its tests read; the agent is one thread
(defonce ^:private -running (volatile! 0))

(defn running
  "How many watches reactions hold now (Reagent's (running)): back where it was once every
  reaction a test made is disposed."
  {:malli/schema [:=> [:cat] :int]}
  [] @-running)

(defn reactive?
  "Is a reaction's run in progress?"
  {:malli/schema [:=> [:cat] :boolean]}
  [] (some? *ratom-context*))

(defn- check-watches
  "new, the watches map, counted against old into the running count."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] [:maybe :map]]}
  [old new] (vswap! -running + (- (count new) (count old))) new)

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

(defn- deref-capture
  "(f) in r's context: its derefs become r's sources (watched when they differ from the last
  run's). Clears r's dirty flag. Reagent's."
  {:malli/schema [:=> [:cat :seon.host/fn :any] :any]}
  [f r]
  (-set! r :captured nil)
  (let [res (binding [*ratom-context* r] (f))
        c (-get r :captured)]
    (-set! r :dirty? false)
    (when-not (= c (-get r :last)) (-update-watching r c))
    res))

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
     (~'count [~'_] (notify-deref-watcher! [~'src ~'path :keys]) (count ~'m))
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
     (~'size [~'_] (notify-deref-watcher! [~'src ~'path :keys]) (count ~'m))
     (~'isEmpty [~'_] (notify-deref-watcher! [~'src ~'path :keys]) (empty? ~'m))
     (~'get [~'this ~'k] (.valAt ~'this ~'k))
     (~'entrySet [~'this] (set (seq ~'this)))
     (~'keySet [~'_] (notify-deref-watcher! [~'src ~'path :keys]) (set (keys ~'m)))
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
  diff that names what a reset! of it changed, [[op path ...] ...]), as a tracking view."
  {:malli/schema [:=> [:cat :map :map [:vector :any]] :map]}
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
  (let [v (binding [*ratom-context* nil] @r)] (view {:ref r :changes changes} (if (map? v) v {}) [])))

(defn view=
  "Are a and b equal as a render's args: two views of the same source at the same path (Reagent
  compares an RCursor by its ratom and path), else =."
  {:malli/schema [:=> [:cat :any :any] :boolean]}
  [a b]
  (if (and (view? a) (view? b))
    (and (identical? (:ref (.-src a)) (:ref (.-src b))) (= (.-path a) (.-path b)))
    (= a b)))

(defn fresh
  "x as of now: a view made again over its source's current value at its path (a stored view
  holds the value it was made with; an RCursor reads its ratom at each deref), else x."
  {:malli/schema [:=> [:cat :any] :any]}
  [x]
  (if (view? x)
    (let [src (.-src x) p (.-path x) v (get-in (binding [*ratom-context* nil] @(:ref src)) p)]
      (if (and (map? v) (sorted? v)) (sorted-view src v p) (view src (or v {}) p)))
    x))

;;; --- queueing ------------------------------------------------------------------------------

;; Reagent's rea-queue: the reactions a change dirtied, run by flush!
(defonce ^:private rea-queue (volatile! nil))

(defn- rea-enqueue
  "Queue reaction r for flush!."
  {:malli/schema [:=> [:cat :any] :nil]}
  [r] (vswap! rea-queue (fnil conj []) r) nil)

(defn flush!
  "Run every queued reaction, and any each of those queues, until none is left (Reagent's). The
  server's frame: called after a transaction's patch, and at a non-reactive deref."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (loop []
    (when-let [q @rea-queue]
      (vreset! rea-queue nil)
      (doseq [r q] (-queued-run r))
      (recur)))
  nil)

;;; --- reaction --------------------------------------------------------------------------------

;; each source read through views: its diff and its readers by path, {ref {:changes f :pre {path #{r}}
;; :deep {path #{r}} :keys {path #{r}}}}. One watch a source: a reset! computes its changed paths once
;; and reaches only the reactions that read one of them, by index, so a change costs
;; what it changed, never every reader of the source
(defonce ^:private sources (volatile! {}))

(declare source-changed)

(defn- index-reads!
  "Reaction r's reads of source src ([[path kind] ...]) added to its index (f conj) or removed
  (f disj); the source watched while any reaction reads it, and let go when none does."
  {:malli/schema [:=> [:cat :map :any [:set [:tuple [:vector :any] :keyword]] :seon.host/fn] :nil]}
  [src r reads f]
  (let [ref (:ref src)
        add (fn [m k] (let [v (f (get m k #{}) r)] (if (seq v) (assoc m k v) (dissoc m k))))
        had? (contains? @sources ref)]
    (vswap! sources update ref
            (fn [ix] (reduce (fn [ix [p kind]]
                               (cond-> (reduce #(update %1 :pre add %2) ix (prefixes p))
                                 (= kind :deep) (update :deep add p)
                                 (= kind :keys) (update :keys add p)))
                             (assoc (or ix {:pre {} :deep {} :keys {}}) :changes (:changes src)) reads)))
    (cond (and (not had?) (seq (get-in @sources [ref :pre]))) (add-watch ref ::paths source-changed)
          (empty? (get-in @sources [ref :pre])) (do (vswap! sources dissoc ref) (when had? (remove-watch ref ::paths)))))
  nil)

(defn- source-changed
  "The one watch on a source read through views: the paths its reset! changed (its diff, once),
  and each reaction a changed path reaches, looked up in its index: a read at or below
  the path (:pre), a deep read above it (:deep), a node iterated whose direct value it added,
  removed or replaced (:keys)."
  {:malli/schema [:=> [:cat :any :any :any :any] :nil]}
  [_ ref old new]
  (when-not (identical? old new)
    (let [{:keys [changes pre deep keys]} (get @sources ref)
          hit (reduce (fn [acc [op p]]
                        (cond-> (into (into acc (get pre p)) (mapcat #(get deep %)) (prefixes p))
                          (seq p) (into (get keys (pop p)))))
                      #{} (changes old new))]
      (doseq [r hit] (-handle-change r ::reached old new))))
  nil)

(defn- handle-reaction-change
  "The watch a reaction puts on each source: Reagent's handle-reaction-change."
  {:malli/schema [:=> [:cat :any :any :any :any] :any]}
  [this sender old new] (-handle-change this sender old new))

(defn- source-of
  "The source a capture entry names: a reactive object, or a view read's {:ref :changes}."
  {:malli/schema [:=> [:cat :any] :any]}
  [d] (if (vector? d) (first d) d))

(deftype Reaction [^:volatile-mutable f ^:volatile-mutable state ^:volatile-mutable dirty? ^:volatile-mutable nocache?
                   ^:volatile-mutable watching ^:volatile-mutable watches ^:volatile-mutable auto-run
                   ^:volatile-mutable caught ^:volatile-mutable captured ^:volatile-mutable last-captured
                   ^:volatile-mutable reads ^:volatile-mutable on-set ^:volatile-mutable on-dispose
                   ^:volatile-mutable on-dispose-arr]
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
            :auto-run auto-run :reads reads :peek (binding [*ratom-context* nil] (.deref this))))
  (-set! [_ k v]
    (case k :captured (set! captured v) :dirty? (set! dirty? v) :f (set! f v) :auto-run (set! auto-run v)))
  (-handle-change [this sender oldval newval]
    ;; a source read through views reaches this reaction only by a changed path it read (::reached:
    ;; source-changed's index); every other source by Reagent's own watch
    (when-not (or (identical? oldval newval) dirty?)
      (if (nil? auto-run)
        (do (set! dirty? true) (rea-enqueue this))
        (if (true? auto-run) (-run this false) (auto-run this)))))
  (-update-watching [this derefed]
    ;; a reactive object is watched (Reagent's); a view's reads are indexed at their source by path
    (let [by-src (reduce (fn [m d] (if (vector? d) (update m (first d) (fnil conj #{}) [(nth d 1) (nth d 2)]) m)) {} derefed)
          new (set (remove vector? derefed))
          old (set (remove map? (keys watching)))]
      (set! last-captured derefed)
      (doseq [[src rs] reads] (index-reads! src this rs disj))
      (doseq [[src rs] by-src] (index-reads! src this rs conj))
      (set! reads by-src)
      (set! watching (zipmap (concat new (keys by-src)) (repeat true)))
      (doseq [w new :when (not (contains? old w))] (add-watch w this handle-reaction-change))
      (doseq [w old :when (not (contains? new w))] (remove-watch w this))))
  (-queued-run [this] (when (and dirty? (some? watching)) (-run this true)))
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
      (doseq [[src rs] reads] (index-reads! src this rs disj))
      (set! watching nil)
      (set! reads nil)
      (set! last-captured nil)
      (set! state nil)
      (set! auto-run nil)
      (set! dirty? true)
      (doseq [w (keys wg) :when (not (map? w))] (remove-watch w this))
      (when (some? on-dispose) (on-dispose s))
      (doseq [g on-dispose-arr] (g this))))
  (add-on-dispose! [_ g] (set! on-dispose-arr (conj (or on-dispose-arr []) g))))

(defn make-reaction
  "A Reaction of f (no arguments), Reagent's: :auto-run, run now and on each change of what it
  derefs (true: run it; a fn: called with the reaction); :on-set, called (old new) by a reset!
  before its watches; :on-dispose, called with its last value when it is disposed."
  {:malli/schema [:=> [:cat [:maybe :seon.host/fn] [:* :any]] :seon.host/deref]}
  [f & {:keys [auto-run on-set on-dispose]}]
  (let [r (Reaction. f nil true false nil nil nil nil nil nil nil nil nil nil)]
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
