;; Bounded reachable-size walker for one cache at a time (lane cache-central-design-opus).
;; Evaluated as the scratch namespace `cache-central-probe` in JVM mode; defines nothing
;; in any seon namespace. Estimates shallow sizes (12-byte header, 4-byte compressed refs,
;; 8-byte alignment); descends reflectively into classpath classes (unnamed module) and
;; through the public API of common java.base types; stops at shared owners (Class, Var,
;; Namespace, Thread, ClassLoader, Keyword, Datahike DB values and index sets/nodes,
;; connections) which it counts as boundaries, never as the cache's bytes.
(ns cache-central-probe
  (:import [java.lang.reflect Field Modifier Array]
           [java.util IdentityHashMap ArrayDeque Map Collection]))

(def ^:private field-cache (java.util.concurrent.ConcurrentHashMap.))

(defn- prim-size [^Class t]
  (condp = t Long/TYPE 8 Double/TYPE 8 Integer/TYPE 4 Float/TYPE 4
             Short/TYPE 2 Character/TYPE 2 Byte/TYPE 1 Boolean/TYPE 1 4))

(defn- align [n] (* 8 (quot (+ n 7) 8)))

(defn- fields-of
  "[shallow-size accessible-ref-fields-or-nil] for class c."
  [^Class c]
  (or (.get field-cache c)
      (let [fs (loop [k c acc []] (if (nil? k) acc (recur (.getSuperclass k) (into acc (.getDeclaredFields k)))))
            inst (remove #(Modifier/isStatic (.getModifiers ^Field %)) fs)
            size (align (+ 12 (reduce + (map #(prim-size (.getType ^Field %)) inst))))
            refs (filterv #(not (.isPrimitive (.getType ^Field %))) inst)
            ok (try (doseq [^Field f refs] (.setAccessible f true)) true (catch Throwable _ false))
            v [size (when ok refs)]]
        (.put field-cache c v) v)))

(def boundary-prefixes
  ["datahike.db.DB" "datahike.db.FilteredDB" "datahike.db.HistoricalDB" "datahike.db.AsOfDB" "datahike.db.SinceDB"
   "org.replikativ.persistent_sorted_set" "me.tonsky.persistent_sorted_set" "datahike.index."
   "datahike.connector" "datahike.writer" "konserve." "superv." "clojure.core.async.impl"
   "java.lang.Thread" "java.lang.ClassLoader" "clojure.lang.DynamicClassLoader"])

(defn- boundary? [o]
  (or (instance? Class o) (instance? clojure.lang.Var o) (instance? clojure.lang.Namespace o)
      (instance? clojure.lang.Keyword o) (instance? Thread o) (instance? ClassLoader o)
      (let [n (.getName (class o))] (some #(.startsWith n ^String %) boundary-prefixes))))

(defn walk
  "Estimated bytes reachable from `roots` not already in `seen` (an IdentityHashMap
  shared across calls for marginal sizes), bounded by `cap` objects."
  ([roots cap] (walk roots cap (IdentityHashMap.)))
  ([roots cap ^IdentityHashMap seen]
   (let [stack (ArrayDeque.) started (System/nanoTime)
         push (fn [o] (when (and (some? o) (not (.containsKey seen o))) (.put seen o true) (.push stack o)))]
     (doseq [r roots] (push r))
     (loop [n 0 bytes 0 boundaries 0 opaque 0]
       (if (or (.isEmpty stack) (>= n cap))
         {:objects n :bytes bytes :boundaries boundaries :opaque-jdk opaque :capped? (>= n cap)
          :ms (quot (- (System/nanoTime) started) 1000000)}
         (let [o (.pop stack) c (class o)]
           (cond
             (boundary? o) (recur (inc n) bytes (inc boundaries) opaque)
             (.isArray c)
             (let [len (Array/getLength o) ct (.getComponentType c)]
               (if (.isPrimitive ct)
                 (recur (inc n) (+ bytes (align (+ 16 (* len (prim-size ct))))) boundaries opaque)
                 (do (dotimes [i len] (push (aget ^objects o i)))
                     (recur (inc n) (+ bytes (align (+ 16 (* 4 len)))) boundaries opaque))))
             (instance? String o) (recur (inc n) (+ bytes 24 (align (+ 16 (.length ^String o)))) boundaries opaque)
             (instance? clojure.lang.Symbol o)
             (do (push (.getNamespace ^clojure.lang.Symbol o)) (push (.getName ^clojure.lang.Symbol o))
                 (recur (inc n) (+ bytes 32) boundaries opaque))
             :else
             (let [[size refs] (fields-of c)]
               (cond
                 refs (do (doseq [^Field f refs] (push (.get f o)))
                          (recur (inc n) (+ bytes size) boundaries opaque))
                 (instance? Map o) (do (doseq [^java.util.Map$Entry e (.entrySet ^Map o)] (push (.getKey e)) (push (.getValue e)))
                                       (recur (inc n) (+ bytes size (* 32 (.size ^Map o))) boundaries opaque))
                 (instance? Collection o) (do (doseq [x ^Collection o] (push x))
                                              (recur (inc n) (+ bytes size (* 16 (.size ^Collection o))) boundaries opaque))
                 (instance? java.util.concurrent.atomic.AtomicReference o)
                 (do (push (.get ^java.util.concurrent.atomic.AtomicReference o)) (recur (inc n) (+ bytes 16) boundaries opaque))
                 :else (recur (inc n) (+ bytes size) boundaries (inc opaque)))))))))))

(defn cache-map
  "The plain map inside a core.cache wrapped atom (or a plain atom/map)."
  [x]
  (let [v (if (instance? clojure.lang.IDeref x) @x x)]
    (if (instance? clojure.core.cache.CacheProtocol v) (into {} (seq v)) v)))

(defn per-entry
  "Marginal bytes of each entry of `m` in order, sharing one seen set."
  [m cap]
  (let [seen (IdentityHashMap.)]
    (mapv (fn [[k v]] {:key (walk [k] cap seen) :val (walk [v] cap seen)}) m)))
