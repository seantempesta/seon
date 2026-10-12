(ns seon.program-context-weight
  "Measure what a `:seon.sci.eval/program-context` entry retains: the bytes its
  eviction frees, which is what `seon.cache`'s budget means.

  Two methods:

  - `retained-bytes`: an identity walk of the value that skips everything an
    equal value already held reaches, and everything the JVM retains anyway
    (classes, class loaders, namespaces, Vars and their roots and metadata,
    keywords, threads, the SCI context store's current context). The held
    value is a second derivation of the same program, so what both reach is
    shared and what only the value reaches is its own. Strings count as the
    value's own: its rows' source text is the datoms' strings, shared only
    while Datahike's node cache keeps those nodes. A plain JOL `GraphLayout`
    of the value cannot answer this: through compiled Var roots, the
    projection and the context store it reaches most of the live heap.
  - `heap-retained`: used heap after collections while a fresh derivation is
    held, less after it is dropped, with the value its only holder, while the
    node cache still holds the source text. Needs a quiet JVM.

  `report` derives both for the loaded program context and for overlays of
  1, 5, 20 and 60 interpreted functions, defined on probe agents through the
  MCP entrance, beside the weight `seon.sci.eval` charges each. Run it at an
  isolated root's REPL (`bin/seon --root R nuke --force`), never on default:
  it defines agents and collects the heap."
  (:require [seon.db :as db]
            [seon.mcp]
            [seon.operator.runtime :as runtime]
            [seon.schema :as schema]
            [seon.sci.eval :as eval])
  (:import [java.lang.reflect Field Modifier]
           [java.util ArrayDeque HashMap IdentityHashMap]
           [org.openjdk.jol.vm VM VirtualMachine]))

(defn- jol-vm
  "JOL's view of this JVM, without self-attach: the serviceability agent's
  attach stops the process on macOS, and the walk needs only field offsets and
  object sizes."
  {:malli/schema [:=> [:cat] :seon.schema/value]}
  []
  (doseq [property ["jol.skipDynamicAttach" "jol.skipInstallAttach" "jol.skipHotspotSAAttach"]]
    (System/setProperty property "true"))
  (VM/current))

(defn- reference-offsets
  "The field offsets of `c`'s instance reference fields, its superclasses' included."
  {:malli/schema [:=> [:cat :seon.schema/value [:fn clojure.core/class?]] :seon.schema/value]}
  [^VirtualMachine vm ^Class c]
  (long-array
   (for [k (take-while some? (iterate #(.getSuperclass ^Class %) c))
         ^Field field (.getDeclaredFields ^Class k)
         :when (not (or (Modifier/isStatic (.getModifiers field)) (.isPrimitive (.getType field))))]
     (.fieldOffset vm field))))

(defn- jvm-retained
  "The identities the JVM retains whatever the cache holds: every Var's root
  and metadata, and the SCI context store's current context as each of
  `contexts` sees it, the context every SCI evaluation shares."
  {:malli/schema [:=> [:cat [:sequential :seon.sci.eval/program-context]] :seon.schema/value]}
  [contexts]
  (let [retained (IdentityHashMap.)]
    (doseq [n (all-ns) [_ v] (ns-map n) :when (var? v)]
      (when (bound? v) (.put retained @v true))
      (when-let [m (meta v)] (.put retained m true)))
    (doseq [context contexts]
      (when-let [store (get-in (:env context) [:namespaces 'sci.ctx-store '*ctx*])]
        (.put retained (.getRawRoot ^sci.lang.Var store) true)))
    retained))

(defn- walk!
  "Bytes of the objects reachable from `roots` that are not in `seen`, never
  entering a `shared` object; every reached object joins `seen`, strings only
  when `strings?`."
  {:malli/schema [:=> [:cat [:sequential :seon.schema/value] :seon.schema/value :seon.schema/value :boolean]
                  [:int {:min 0}]]}
  [roots ^IdentityHashMap shared ^IdentityHashMap seen strings?]
  (let [^VirtualMachine vm (jol-vm)
        offsets (HashMap.)
        queue (ArrayDeque.)
        ;; hidden classes and records have no field offsets; a weak or soft
        ;; referent is not retained. Local, so the per-object call is unarmed.
        opaque? (fn [^Class c] (or (.isHidden c) (.isRecord c) (.isAssignableFrom java.lang.ref.Reference c)))
        shared? #(or (instance? Class %) (instance? ClassLoader %) (instance? clojure.lang.Namespace %)
                     (instance? clojure.lang.Var %) (instance? clojure.lang.Keyword %) (instance? Thread %)
                     (.containsKey shared %))
        visit (fn [o]
                (when (and (some? o) (or strings? (not (instance? String o))) (not (.containsKey seen o)))
                  (.put seen o true)
                  (when-not (shared? o) (.add queue o))))]
    (doseq [root roots]
      (when-not (.containsKey seen root) (.put seen root true) (.add queue root)))
    ;; the queue holds Boolean/FALSE as an object: test for nil, never truthiness
    (loop [total 0]
      (let [o (.poll queue)]
        (if (nil? o)
          total
          (let [c (class o)]
            (cond
              (.isArray c) (when-not (.isPrimitive (.getComponentType c))
                             (let [^objects a o] (dotimes [i (alength a)] (visit (aget a i)))))
              (opaque? c) nil
              :else (let [^longs fields (or (.get offsets c)
                                            (let [found (reference-offsets vm c)] (.put offsets c found) found))]
                      (dotimes [i (alength fields)] (visit (.getObject vm o (aget fields i))))))
            ;; JOL sizes an instance from field offsets, which a hidden class or record lacks: count its header
            (recur (+ total (if (and (opaque? c) (not (.isAssignableFrom java.lang.ref.Reference c)))
                              (long (.objectHeaderSize vm))
                              (.sizeOf vm o))))))))))

(defn retained-bytes
  "The bytes `value` retains beyond `held` and the JVM, by identity walk.

  `held` are equal or parent program contexts; the answer is what dropping
  `value` frees while `held` stays. Strings are never taken as shared with
  `held`: a row's source text is the datom's string, which `held` shares only
  while Datahike's node cache still holds that node, so this is the bound once
  it no longer does."
  {:malli/schema [:=> [:cat :seon.sci.eval/program-context [:sequential :seon.sci.eval/program-context]] nat-int?]}
  [value held]
  (let [shared (jvm-retained (cons value held))
        seen (IdentityHashMap.)]
    (walk! held shared seen false)
    (walk! [value] shared seen true)))

(defn retained-parts
  "`retained-bytes` of `value` split by its keys and its kernel snapshot's and
  acquisition's keys, each part counting only what earlier parts did not reach."
  {:malli/schema [:=> [:cat :seon.sci.eval/program-context [:sequential :seon.sci.eval/program-context]]
                  [:map-of :seon.schema/value nat-int?]]}
  [value held]
  (let [shared (jvm-retained (cons value held))
        seen (IdentityHashMap.)
        nested #{:seon.sci.kernel/program-snapshot ::eval/acquisition :env}]
    (walk! held shared seen false)
    (.put seen value true)
    (into {}
          (comp (map (fn [[path part]] [path (walk! [part] shared seen true)]))
                (filter (comp pos? second)))
          (concat (for [[k v] value :when (and (some? v) (not (nested k)))] [[k] v])
                  (for [k nested [k2 v] (get value k) :when (some? v)] [[k k2] v])))))

(defn heap-retained
  "The bytes evicting `(build)`'s value frees, by used heap.

  Used heap while the value is held, less after it is dropped, each after
  collections settle."
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.sci.eval/program-context]] :int]}
  [build]
  (let [used (fn [] (dotimes [_ 4] (System/gc) (Thread/sleep 300))
               (let [runtime (Runtime/getRuntime)] (- (.totalMemory runtime) (.freeMemory runtime))))
        held (volatile! (build))
        holding (used)]
    (vreset! held nil)
    (- holding (used))))

(defn- overlay-row
  "Both measurements of `agent-id`'s program context beside its charge, after
  defining `n` functions in a fresh namespace on that agent's branch."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.sci.eval/program-context :string pos-int?] :map]}
  [connection base agent-id n]
  (#'seon.mcp/eval-clj {:seon.mcp/code (apply str "(ns probe." agent-id ") "
                                              (for [i (range n)] (str "(defn f" i " [x] (+ x " i "))")))
                        :seon.mcp/agent agent-id :seon.mcp/cluster "default" :seon.mcp/mode "sci"})
  (let [database (db/db connection)
        branch (:seon.agent/branch (db/pull database [:seon.agent/branch] [:seon.agent/id agent-id]))
        own (db/db (db/branch-connection database branch))
        held (eval/program-context own)
        loaded (#'eval/loaded-program own)
        projection (or (db/carried-projection own) (schema/projection-from-database own))
        plan (#'eval/program-changes own loaded projection)
        rebuild #(#'eval/derive-overlay-ctx base own loaded projection plan)]
    {:functions n
     :interpreted (get-in held [::eval/acquisition ::eval/interpreted-count])
     :walked (retained-bytes (rebuild) [base held])
     :heap (heap-retained rebuild)
     :charged (#'eval/overlay-weight held)}))

(defn report
  "Measured and charged bytes of this root's program contexts.

  The loaded program context of the default cluster and four overlays."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [connection (:seon.db/connection (:seon.turn.loop/cluster (get @runtime/running-instances "default")))
        database (db/db connection)
        loaded (#'eval/loaded-program database)
        base (eval/program-context database)
        rebuild #(#'eval/derive-base-ctx loaded)]
    {:base {:functions (count (:functions (:seon.sci.kernel/program-snapshot base)))
            :walked (retained-bytes (rebuild) [base])
            :heap (heap-retained rebuild)
            :charged (#'eval/context-weight base)}
     :overlays (mapv (fn [n] (overlay-row connection base (str "weight-" n "-" (subs (str (random-uuid)) 0 8)) n))
                     [1 5 20 60])}))
