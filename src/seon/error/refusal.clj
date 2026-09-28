(ns seon.error.refusal
  "Diagnostic construction and cause-chain reading for error boundaries. An
  error holds references, never copies: every world in it is referenced here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [seon.id :as id]))

(defn- machinery-namespace?
  "Whether `frame-ns` is Seon's own fault machinery or the operator's prepl
  wire: they CATCH a failure, and naming one routes the fault to the steward
  of the checker instead of the steward of the code that broke. Named
  exactly, so `seon.error.refusal-test` is not machinery. Dependencies and
  the host are recognized from the classpath (`authored-namespace?`)."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String frame-ns]
  (or (contains? #{"seon.error" "seon.error.refusal" "seon.instrument"} frame-ns)
      (.startsWith frame-ns "seon.operator.")))

(def ^:private basis
  ;; The Clojure CLI's record of this process's classpath, read once: a
  ;; `:classpath` entry carrying `:lib-name` is a dependency's. Nil where
  ;; the process has no basis (the operator under babashka).
  (delay (when-let [path (System/getProperty "clojure.basis")]
           (try (edn/read-string (slurp path)) (catch Exception _ nil)))))

(def ^:private library-roots
  (delay (into []
               (keep (fn [[root {:keys [lib-name]}]]
                       (when lib-name (.getCanonicalPath (java.io.File. ^String root)))))
               (:classpath @basis))))

(defn- library-url?
  "Whether a classpath resource, by its URL text, is a dependency's: in a
  jar or the JDK's image, or a file under a `:lib-name` root of the basis."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String url]
  (or (not (.startsWith url "file:"))
      (let [file (.getCanonicalPath (java.io.File. (java.net.URI. url)))]
        (boolean (some #(.startsWith file (str % java.io.File/separator))
                       @library-roots)))))

(defonce ^:private cache-seam
  ;; `seon.cache` holds Caffeine, which the operator under babashka lacks: the
  ;; one cache is reached only in a process with a basis.
  (delay (when @basis (requiring-resolve 'seon.cache/lookup))))

(declare classify-namespace)

(defn- authored-namespace?
  "Whether frames of `frame-ns` are authored (`classify-namespace`), derived
  once per namespace in the central cache: the classpath does not change
  under a running process, and each classification reads it (tens of
  microseconds; a 177-frame stack cost 35 ms unshared)."
  {:malli/schema [:=> [:cat :string :symbol] :boolean]}
  [frame-ns class-name]
  (if-let [lookup @cache-seam]
    (lookup ::authored-namespace frame-ns (constantly 64)
            #(classify-namespace frame-ns class-name))
    (classify-namespace frame-ns class-name)))

(defn- classify-namespace
  "Whether frames of `frame-ns` (one of whose classes is `class-name`) are
  code an agent or Seon wrote, decided from the classpath, never a name:
  its source is a file outside every library root of the basis; or it has
  no source and is loaded — defined at a REPL or by `eval`, as `user` is; or
  it is neither loaded nor a class the classpath holds (the JDK's and a
  dependency's inner classes, `clojure.lang.Compiler$FnExpr`, are). Without
  a basis no sourceless namespace can be told from the host's own, so none
  is authored."
  {:malli/schema [:=> [:cat :string :symbol] :boolean]}
  [^String frame-ns class-name]
  (let [path (-> frame-ns (.replace \- \_) (.replace \. \/))]
    (if-let [source (or (io/resource (str path ".clj")) (io/resource (str path ".cljc")))]
      (not (library-url? (str source)))
      (boolean
       (and @basis
            (or (find-ns (symbol frame-ns))
                (not (io/resource (str (.replace (str class-name) \. \/) ".class")))))))))

(defn- frame-site
  "`[namespace function]` of a demunged Clojure frame class, or nil for a
  host class. Demunged frames read `ns/fn`, `ns/fn--1234` for a compiled
  arity and `ns/outer/fn` for a closure, so the function is the first two
  segments with the compiler's suffix dropped."
  {:malli/schema [:=> [:cat :symbol] [:or :nil [:tuple :string :string]]]}
  [class-name]
  (let [demunged (clojure.lang.Compiler/demunge (str class-name))
        separator (.indexOf demunged "/")]
    (when (pos? separator)
      (let [simple (subs demunged (inc separator))
            nested (.indexOf simple "/")
            simple (if (neg? nested) simple (subs simple 0 nested))
            suffix (.indexOf simple "--")]
        [(subs demunged 0 separator) (if (neg? suffix) simple (subs simple 0 suffix))]))))

(defn- authored-prefixes
  "The namespace parts of `frames`' classes (munged, before the first `$`)
  whose namespace is authored and outside the fault machinery. A stack
  repeats a few namespaces across a hundred frames, so each distinct part is
  demunged and classified once; a host class has no `$` part and none."
  {:malli/schema [:=> [:cat [:sequential [:cat :symbol [:* [:or :symbol :string :int]]]]]
                  [:set :string]]}
  [frames]
  (into #{}
        (keep (fn [[^String prefix class-name]]
                (let [frame-ns (clojure.lang.Compiler/demunge prefix)]
                  (when (and (not (machinery-namespace? frame-ns))
                             (authored-namespace? frame-ns class-name))
                    prefix))))
        ;; one representative class per prefix
        (into {} (keep (fn [[class-name]]
                         (let [text (str class-name) cut (.indexOf text "$")]
                           (when (pos? cut) [(subs text 0 cut) class-name]))))
              (rseq (vec frames)))))

(defn- prepared-call-prefix
  "The class prefix of the prepared-call closure `seon.call-preparation/preparing`
  installs around every contract that declares a supplied default: contract
  machinery whose frame sits between each such caller and the refusal
  (`call_preparation.clj:1033`), the rule `seon.instrument/caller-frame`
  applies. Derived from the Var, so a rename moves it; nil where the
  namespace is not loaded."
  {:malli/schema [:=> [:cat] [:or :nil :string]]}
  []
  (when-let [preparing (some-> (find-ns 'seon.call-preparation) (ns-resolve 'preparing))]
    (let [{namespace-object :ns var-name :name} (meta preparing)]
      (str (clojure.lang.Compiler/munge (str (ns-name namespace-object)))
           "$" (clojure.lang.Compiler/munge (str var-name)) "$"))))

(defn- first-party-in
  "The frames of `frames` whose class's namespace part is in `authored`,
  less the prepared-call closure (`prepared-call-prefix`)."
  {:malli/schema [:=> [:cat [:set :string] [:vector [:cat :symbol [:* [:or :symbol :string :int]]]]]
                  [:vector [:cat :symbol [:* [:or :symbol :string :int]]]]]}
  [authored frames]
  (let [^String wrapper (prepared-call-prefix)]
    (filterv (fn [[class-name]]
               (let [text (str class-name) cut (.indexOf text "$")]
                 (and (pos? cut) (contains? authored (subs text 0 cut))
                      (not (and wrapper (.startsWith text wrapper))))))
             frames)))

(defn frame-function
  "The function a stack frame's class belongs to, as `ns/name`, or nil.

  Only a frame in authored code names one (`authored-namespace?`): a frame
  in a dependency, the host or the fault machinery is where a failure was
  caught, not where it happened. The name must be a Var that exists, so a
  compiler-generated class of a loaded namespace (`user$eval479`, a
  `reify`) names nothing."
  {:malli/schema [:=> [:cat :symbol] [:or :nil :qualified-symbol]]}
  [class-name]
  (when-let [[frame-ns simple] (when (seq (first-party-in (authored-prefixes [[class-name]])
                                                                  [[class-name]]))
                                  (frame-site class-name))]
    (when (and (seq simple)
               (or (not (find-ns (symbol frame-ns)))
                   (find-var (symbol frame-ns simple))))
      (symbol frame-ns simple))))

(defn- stack-frames
  "`throwable`'s own complete stack frames as `[class method file line]`;
  a frame the JVM reports without a file is not complete and is omitted."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :seon.error/frame]]}
  [^Throwable throwable]
  (into []
        (keep (fn [^StackTraceElement frame]
                (when-let [file (.getFileName frame)]
                  [(symbol (.getClassName frame)) (symbol (.getMethodName frame))
                   file (long (.getLineNumber frame))])))
        (.getStackTrace throwable)))

(defn first-party-frame?
  "Whether a frame is in code an agent or Seon wrote (`authored-namespace?`),
  outside the fault machinery: an evaluated form's `user$eval…` frame is one,
  a dependency's or the host's is not."
  {:malli/schema [:=> [:cat :seon.error/frame] :boolean]}
  [frame]
  (boolean (seq (first-party-in (authored-prefixes [frame]) [frame]))))

(defn cause-links
  "`throwable` and its causes, outermost to root, each once: a cause cycle
  (`initCause` permits one) ends at its first repeat, as `printStackTrace`'s
  own guard does."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector {:min 1} :seon.error/throwable]]}
  [^Throwable throwable]
  (loop [^Throwable link throwable seen #{} found []]
    (if (and link (not (contains? seen link)))
      (recur (.getCause link) (conj seen link) (conj found link))
      found)))

(defn root-frame
  "The first frame an agent can act on: the first first-party frame of the
  ROOT cause, where the failure happened, else of each enclosing link in turn
  outward. A dependency's innermost frame (`clojure.string$replace` under an
  agent's call) is where it broke but nothing to edit; with no first-party
  frame anywhere (a form refused at compile) there is no frame, and the
  chain's own data (a compile error's source and line) is the location."
  {:malli/schema [:=> [:cat [:or :nil :seon.error/throwable]]
                  [:or :nil :seon.error/frame]]}
  [throwable]
  (when throwable
    (let [stacks (mapv stack-frames (rseq (cause-links throwable)))
          authored (authored-prefixes (into [] cat stacks))]
      (some #(first (first-party-in authored %)) stacks))))

;;; WORLDS ARE REFERENCED, NEVER COPIED (owner 2026-09-26). Which values are
;;; worlds is the registry's answer, not a class roster: every schema declared
;;; `:seon.schema/identity-only` (a database value, a connection, a schema
;;; projection, an environment, an SCI context) names the projection of its
;;; value to identity data — exactly what value admission stores in its place
;;; (`seon.sci.admit/open-node`, admit.clj:305-346). LOAD-CYCLE BOUNDARY:
;;; `seon.schema` requires this namespace, so its two seams are resolved once,
;;; at first use.
(defonce ^:private schema-seam
  (delay {:handed (requiring-resolve 'seon.schema/handed-projection)
          :projector (requiring-resolve 'seon.schema/identity-only-projector)}))

(defn- operation-projection
  "The projection handed to the operation running now, or nil.

  Only `seon.schema` hands one, so where it is not loaded (the operator
  under babashka, which has no Malli) no operation holds one: nil is the
  answer, and resolving the seam there threw instead of the error it was
  building."
  []
  (when (find-ns 'seon.schema)
    ((:handed @schema-seam))))

(defn- identity-projector
  [projection]
  (if projection ((:projector @schema-seam) projection) (constantly nil)))

(def ^:private evidence-characters
  ;; What one cause link's data holds, in printed characters. The failure it
  ;; prevents (2026-09-27): an instrument refusal's offending arguments held a
  ;; schema projection's 3,576 forms (663 KB printed); the eval_clj error
  ;; value's chain copied them and the bridge stored a 2.8 MB blob. The live
  ;; refusal keeps its actual arguments; the chain, the error as data, holds
  ;; each larger value by name. The tests' bound for "prints in kilobytes".
  16384)

(defn- object-name
  "How Clojure prints an object it does not enter, `#object[Class 0xhash]`,
  as a STRING a reader reads back; `size` names what a held value holds."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}] [:maybe :int]] :string]}
  [value size]
  (str "#object[" (.getName (class value)) " 0x"
       (Integer/toHexString (System/identityHashCode value))
       (when size (str " {:count " size "}")) "]"))

(defn- held-by-name
  "`value` held by its name and size, with the characters the name costs."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]] [:tuple :string :int]]}
  [value]
  (let [label (object-name value (when (or (counted? value) (string? value)) (count value)))]
    [label (count label)]))

(defn- held-members
  "`members` (each key, referenced value, printed cost and original value)
  with the costliest values held by name until they fit the evidence bound,
  or nil when even their names do not fit."
  {:malli/schema [:=> [:cat [:vector [:tuple [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}] :int [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]]]]
                  [:maybe [:vector [:tuple [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}] :int [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]]]]]}
  [members]
  (let [total (reduce + (count members) (map #(nth % 2) members))]
    (if (<= total evidence-characters)
      members
      ;; Costliest first, the running total carried: a member whose name
      ;; costs no less than it does cannot help, and neither can any after it.
      (loop [members (vec (sort-by #(- (long (nth % 2))) members)) i 0 total total]
        (if (<= total evidence-characters)
          members
          (when (< i (count members))
            (let [[k _ cost original] (nth members i)
                  [label label-cost] (held-by-name original)]
              (when (< label-cost cost)
                (recur (assoc members i [k label label-cost original]) (inc i)
                       (+ (- total cost) label-cost))))))))))

(defn- bounded-cost
  "`[kept cost]`: `value` with whatever would print past the evidence bound
  held by its name and size, and the printed characters it keeps. A
  collection changing nothing is returned identical. A collection's walk
  stops once its members cost four bounds, holding it whole: the work is
  bounded by what an error may keep, not by the value's size."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]] [:tuple [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}] :int]]}
  [value]
  (cond
    (string? value)
    (if (<= (count value) evidence-characters) [value (+ 2 (count value))] (held-by-name value))
    (or (keyword? value) (symbol? value))
    [value (+ 1 (count (name value)) (if-some [n (namespace value)] (inc (count n)) 0))]
    (record? value) [value 8]
    (or (map? value) (vector? value) (set? value))
    (let [members (loop [entries (cond (map? value) (seq value)
                                       (vector? value) (seq (map-indexed vector value))
                                       :else (seq (map vector value value)))
                         members (transient [])
                         spent 0]
                    (cond
                      (nil? entries) (persistent! members)
                      (> spent (* 4 evidence-characters)) nil
                      :else (let [[k v] (first entries)
                                  [out cost] (bounded-cost v)]
                              (recur (next entries) (conj! members [k out cost v]) (+ spent (long cost))))))
          kept (some-> members held-members)
          cost (when kept (reduce + (+ 2 (count kept)) (map #(nth % 2) kept)))
          changed (remove (fn [[_ out _ original]] (identical? out original)) kept)]
      (cond
        (nil? kept) (held-by-name value)
        (empty? changed) [value cost]
        (set? value) [(into (empty value) (map second) kept) cost]
        :else [(reduce (fn [out [k v]] (assoc out k v)) value changed) cost]))
    :else [value 8]))

(defn- referenced
  "`value` with every world inside it replaced by its identity; a collection
  holding no world is returned identical."
  [project value]
  (if-some [declared (project value)]
    (referenced project (:seon.schema/identity-value declared))
    (cond
      ;; A PLACE IS ITS NAME. An atom, delay or promise prints its CURRENT
      ;; STATE (`core_print.clj` deref-as-map), so an error holding the agent
      ;; roster printed every context's projection: 2 GB, default's OOM of
      ;; 2026-09-26. Named, never entered, as value admission names it
      ;; (`seon.sci.admit`, "reference types ... named, never entered").
      ;; The name is a STRING: as a symbol it printed `#object[...]`, a tag
      ;; no reader accepts, so a stored error holding a place never read back.
      (and (instance? clojure.lang.IDeref value) (not (var? value)))
      (object-name value nil)
      (record? value) value
      (map? value)
      (reduce-kv (fn [held k v]
                   (let [r (referenced project v)]
                     (if (identical? r v) held (assoc held k r))))
                 value value)
      ;; By index, not reduce-kv: a map entry is a vector that reduce-kv
      ;; walks as a map (Keyword -> Map$Entry), and an explained problem
      ;; carries entries (`find`), so the refusal of a data value against a
      ;; union output could not be built (render-call, 2026-09-28).
      (vector? value)
      (reduce (fn [held i]
                (let [v (nth value i)
                      r (referenced project v)]
                  (if (identical? r v) held (assoc held i r))))
              value (range (count value)))
      (set? value)
      (let [members (map (fn [v] [v (referenced project v)]) value)]
        (if (every? (fn [[v r]] (identical? v r)) members)
          value
          (into (empty value) (map second) members)))
      :else value)))

(defn within-evidence-bound?
  "True when `data` prints within the evidence bound, holding nothing
  `bounded-cost` would hold by name: what a cause link's data is by
  construction (`link`), declared on `:seon.error/data`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [data]
  (identical? data (first (bounded-cost data))))

(defn within-evidence-bound-chain?
  "True when `links` is a vector whose every link's data is
  `within-evidence-bound?`: what `chain` returns and `recorded-chain` need
  not."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary values and answers false when they are not a bounded chain.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [links]
  (and (vector? links)
       (every? #(within-evidence-bound? (:seon.error/data % {})) links)))

(defn references
  "`value` with every world in it — any value a schema declares
  identity-only in `projection` — replaced by that world's identity data:
  a database is its name and basis-t, a connection its id, a projection its
  fingerprint, an environment or SCI context the references it holds. Maps,
  vectors and sets are entered; records, sequences and other objects are
  not. A place (atom, delay, promise: any deref-able but a Var) becomes the
  string `#object[Class 0xhash]`, since printing it copies its state. Without
  a projection no world is recognized; places are still named."
  {:malli/schema [:=> [:cat [:maybe :map]
                       [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Returns the supplied data with its worlds referenced.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [projection value]
  (referenced (identity-projector projection) value))

(defn- link
  "One throwable as a flat link, its complete frames held under `::frames`
  until the chain keeps only the first-party ones, its ex-data with worlds
  referenced and then passed through `hold`: `bounded-data` for the live
  chain, `identity` for the recorder's (`recorded-chain`)."
  {:malli/schema [:=> [:cat :seon.instrument/callable :seon.instrument/callable :seon.error/throwable [:maybe :map]] :map]}
  [project hold ^Throwable throwable relation]
  (let [message (ex-message throwable)
        data (ex-data throwable)]
    (cond-> (merge {:seon.error/throwable-class (.getName (class throwable))
                    ::frames (stack-frames throwable)}
                   relation)
      (seq message) (assoc :seon.error/message message)
      data (assoc :seon.error/data (hold (referenced project data))))))

(defn- bounded-data
  "`data` within the evidence bound, each larger value held by name
  (`bounded-cost`)."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [data]
  (first (bounded-cost data)))

(defn- first-party-links
  "`links` with each link's frames narrowed to its first-party ones (a link
  with none keeps no frames), every namespace classified once for the chain."
  {:malli/schema [:=> [:cat [:vector :map]] :seon.error/chain]}
  [links]
  (let [authored (authored-prefixes (into [] (mapcat ::frames) links))]
    (mapv (fn [entry]
            (let [frames (first-party-in authored (::frames entry))]
              (cond-> (dissoc entry ::frames)
                (seq frames) (assoc :seon.error/frames frames))))
          links)))

(defn- walk-links
  "The flat links of `throwable`'s whole cause chain (`chain`), each link's
  ex-data passed through `hold`."
  {:malli/schema [:=> [:cat :seon.error/throwable [:maybe :map] :seon.instrument/callable] :seon.error/chain]}
  [throwable projection hold]
  (let [project (identity-projector projection)]
    (loop [pending [[throwable nil]] seen #{} links []]
      (if-let [[head relation] (first pending)]
        (let [own (vec (take-while (complement seen) (cause-links head)))
              start (count links)
              links (into links
                          (map-indexed
                           (fn [i t]
                             (link project hold t (when relation
                                                    (if (zero? i) relation
                                                        {:seon.error/cause-of (+ start i -1)})))))
                          own)
              suppressed (for [[i ^Throwable t] (map-indexed vector own)
                               s (.getSuppressed t)]
                           [s {:seon.error/suppressed-by (+ start i)}])]
          (recur (into (subvec pending 1) suppressed) (into seen own) links))
        (first-party-links links)))))

(defn chain
  "The whole cause chain as FLAT links: first the primary chain, outermost to
  root, as `Throwable->map` (`clojure/core_print.clj:473`) reads it — per link
  its class, message, `ex-data` with worlds referenced, and first-party frames
  — then every throwable a link suppressed (`.addSuppressed`: a cleanup
  failure no cause link reaches) and that throwable's own causes. A suppressed
  link names the index of the link that suppressed it
  (`:seon.error/suppressed-by`); each of its causes names the index of the
  link it caused (`:seon.error/cause-of`). A throwable seen once is not linked
  again, so a cause cycle ends at its first repeat. The projection
  recognizes worlds; the one-argument form uses the operation's handed one.
  Each link keeps every first-party frame: which of them an agent sees is its
  error schema's render pair's selection. Each link's data is within the
  evidence bound (`:seon.error/data`): a larger value is held by its class,
  identity and size, a name that is not retrievable; the recorder's
  `recorded-chain` keeps it whole in the occurrence's data blob."
  {:malli/schema [:function
                  [:=> [:cat :seon.error/throwable] :seon.error/bounded-chain]
                  [:=> [:cat :seon.error/throwable [:maybe :map]] :seon.error/bounded-chain]]}
  ([throwable] (chain throwable (operation-projection)))
  ([throwable projection] (walk-links throwable projection bounded-data)))

(defn recorded-chain
  "`chain` with each link's data whole (worlds and places still referenced):
  the chain the recorder admits as a fault's evidence, whose storage bound
  caps the inline text and whose occurrence `:seon.error/data-blob` keeps the
  whole value once by content (`seon.error/evidence-stage`). Only the
  recorder, which holds the connection and the root, reads this chain.

  ## Costs

  Unarmed, the walk only: no value is printed here."
  {:malli/schema [:=> [:cat :seon.error/throwable [:maybe :map]] :seon.error/chain]}
  [throwable projection]
  (walk-links throwable projection identity))

(defn without-links
  "`links` (a `chain`) without the links `drop?` selects, every remaining
  relation renumbered to the kept positions. A relation naming a dropped link
  names the nearest kept link after it (else before it), so a suppressed
  failure stays attached to the chain it rode."
  {:malli/schema [:=> [:cat :seon.error/chain [:=> [:cat :seon.error/link] :any]]
                  [:vector :seon.error/link]]}
  [links drop?]
  (let [kept (into [] (remove (comp drop? links)) (range (count links)))
        position (fn [old] (let [after (first (filter #(>= % old) kept))]
                             (.indexOf ^java.util.List kept (or after (peek kept)))))
        renumber (fn [entry k] (cond-> entry (find entry k) (update k position)))]
    (mapv #(-> (links %)
               (renumber :seon.error/suppressed-by)
               (renumber :seon.error/cause-of))
          kept)))

;;; WHO PRODUCED IT (health plan §3, owner ruling 2026-09-28: blame follows the
;;; producer). Read only when an error is built, so an ordinary call pays nothing.

(defn var-digest
  "The definition digest the armed Var of `function` runs: its profile cell's,
  the key a world's call sink counts it under. Nil when `function` is not a
  loaded, armed Var.

  ## Costs

  - A namespace lookup, a Var resolution and two map reads; failure path only."
  {:malli/schema [:=> [:cat :qualified-symbol] [:maybe :seon.program/definition-digest]]}
  [function]
  (when-let [namespace-object (find-ns (symbol (namespace function)))]
    (let [target (ns-resolve namespace-object (symbol (name function)))]
      (when (var? target)
        (some-> ^clojure.lang.Var target .getRawRoot meta :seon.instrument/cell :seon.profile/digest)))))

(defn current-frames
  "The calling thread's complete stack frames as `[class method file line]`,
  innermost first: what a refusal is built on, read once on the failure path."
  {:malli/schema [:=> [:cat] [:vector :seon.error/frame]]}
  []
  (into []
        (keep (fn [^StackTraceElement frame]
                (when-let [file (.getFileName frame)]
                  [(symbol (.getClassName frame)) (symbol (.getMethodName frame))
                   file (long (.getLineNumber frame))])))
        (.getStackTrace (Thread/currentThread))))

(defn calling-definition
  "The authored definition a compiled call came from: the first of `frames`
  (innermost first) in code an agent or Seon wrote, outside the fault and
  contract machinery ([[first-party-frame?]]), named as the Var it belongs to
  ([[frame-function]]). Nil when that frame names no Var (an evaluated form:
  `user$eval123`) or when SCI's interpreter is reached first: then the caller
  is interpreted and its SCI frames name it."
  {:malli/schema [:=> [:cat [:sequential :seon.error/frame]] [:maybe :qualified-symbol]]}
  [frames]
  (reduce (fn [_ [class-name :as frame]]
            (cond
              (.startsWith (str class-name) "sci.") (reduced nil)
              (first-party-frame? frame) (reduced (frame-function class-name))))
          nil frames))

(defn- contract-refusal
  "The armed wrapper's refusal `error` is or carries: itself, else the deepest
  in `throwable`'s cause chain, else in its recorded chain's link data."
  {:malli/schema [:=> [:cat :map [:maybe :seon.error/throwable]] [:maybe :map]]}
  [error throwable]
  (let [refusal? #(and (map? %) (qualified-symbol? (:seon.instrument/fn %)) (keyword? (:seon.instrument/arm %)))]
    (or (when (refusal? error) error)
        (when throwable (some #(let [data (ex-data %)] (when (refusal? data) data)) (rseq (cause-links throwable))))
        (some #(let [data (:seon.error/data %)] (when (refusal? data) data)) (rseq (vec (:seon.error/chain error)))))))

(defn- interpreted-frames
  "An interpreted failure's SCI frames, innermost first, as `[where]`: the
  error's `:seon.sci.eval/stacktrace`, else read once from `throwable`
  (`sci.core/stacktrace`); nil where SCI is not loaded."
  {:malli/schema [:=> [:cat :map [:maybe :seon.error/throwable]] [:maybe [:sequential [:cat :symbol [:* :any]]]]]}
  [error throwable]
  (or (:seon.sci.eval/stacktrace error)
      (when-let [stacktrace (and throwable (find-ns 'sci.core) (resolve 'sci.core/stacktrace))]
        (keep (fn [{frame-ns :ns frame-name :name}]
                (when frame-ns [(if frame-name (symbol (str frame-ns) (str frame-name)) (symbol (str frame-ns)))]))
              (try (stacktrace throwable) (catch Exception _ nil))))))

(defn- authored-function?
  "Whether `function` names code an agent or Seon wrote, outside the fault
  machinery (`authored-namespace?`): a dependency's or the host's operation
  (`clojure.core/eval`) produced nothing an agent can edit."
  {:malli/schema [:=> [:cat :qualified-symbol] :boolean]}
  [function]
  (let [function-ns (namespace function)]
    (and (not (machinery-namespace? function-ns))
         (authored-namespace? function-ns (symbol (str (clojure.lang.Compiler/munge function-ns) "$"
                                                       (clojure.lang.Compiler/munge (name function))))))))

(defn producer
  "The definition that produced a failure, by the blame rule: an input or
  arity violation's caller passed the bad value, an output or guard
  violation's function returned it, a throw's first authored frame threw it,
  a declared error value's operation returned it. Returns
  `:seon.error/producer` and, when known, `:seon.error/producer-digest` (the
  version that ran), plus the refused call's `:seon.error/caller`; empty when
  the agent's own evaluated form produced it, or nothing authored did.

  The armed wrapper names a compiled caller itself (`seon.instrument`); an
  interpreted caller is the first SCI frame that is not the refusing
  function, and its digest is the recording branch's, never the host Var's.

  ## Examples

  (producer (ex-data refusal) refusal)
  ;; => {:seon.error/producer seon.turn/system-turn :seon.error/caller seon.turn/system-turn}"
  {:malli/schema [:=> [:cat :map [:maybe :seon.error/throwable]]
                  [:map [:seon.error/producer {:optional true} :seon.error/producer]
                   [:seon.error/producer-digest {:optional true} :seon.error/producer-digest]
                   [:seon.error/caller {:optional true} :seon.error/caller]
                   [:seon.error/caller-digest {:optional true} :seon.error/caller-digest]]]}
  [error throwable]
  (let [refusal (contract-refusal error throwable)
        refuser (:seon.instrument/fn refusal)
        interpreted (delay (some (fn [[where]] (when (and (qualified-symbol? where) (not= where refuser)) where))
                                 (interpreted-frames error throwable)))
        named (fn [function digest]
                (if (and function (authored-function? function))
                  (cond-> {:seon.error/producer function} digest (assoc :seon.error/producer-digest digest))
                  {}))
        called (select-keys refusal [:seon.error/caller :seon.error/caller-digest])
        called (if (and refusal (empty? called) @interpreted) {:seon.error/caller @interpreted} called)]
    (merge called
           (cond
             (:seon.error/producer refusal) (select-keys refusal [:seon.error/producer :seon.error/producer-digest])
             (#{:output :guard} (:seon.instrument/arm refusal)) (named refuser (:seon.instrument/digest refusal))
             refusal (named @interpreted nil)
             throwable (let [function (some-> (root-frame throwable) first frame-function)]
                         (named function (some-> function var-digest)))
             (qualified-symbol? (:seon.error/operation error))
             (named (:seon.error/operation error) (var-digest (:seon.error/operation error)))
             :else {}))))

(defn world-reference
  "The world an evaluation runs in (`seon.batch/*world*`, bound by the one
  REPL path and by every Flow proc step), as the identities of its agent,
  branch, turn, evaluation and task: an error holds references, never the
  world. Nil outside any world."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :seon.error/world]]}
  [world]
  (let [{agent-id :seon.agent/id branch :seon.agent/branch turn :seon.turn/id ordinal :seon.batch/ordinal
         task :seon.task/id} world]
    (not-empty
     (cond-> {}
       agent-id (assoc :seon.agent/id agent-id)
       branch (assoc :seon.agent/branch branch)
       turn (assoc :seon.turn/id turn)
       (and turn (int? ordinal)) (assoc :seon.cluster.eval/id (id/evaluation turn ordinal))
       task (assoc :seon.task/id task)))))

(defn- current-world
  "The calling world, read where `seon.batch` is loaded (never in the operator)."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (some-> (find-ns 'seon.batch) (ns-resolve '*world*) deref))

(defn attributed
  "`error` with the common part every recorded failure carries supplied where
  it states none: the blame rule's [[producer]] and the calling world's
  reference ([[world-reference]]). What the producer stated wins."
  {:malli/schema [:=> [:cat :map [:maybe :seon.error/throwable]] :map]}
  [error throwable]
  (let [world (when-not (:seon.error/world error) (world-reference (current-world)))]
    (cond-> (merge (producer error throwable) error)
      world (assoc :seon.error/world world))))

(defn diagnostic
  "Preserve the supplied observation, consuming only its optional Throwable,
  with every world in it referenced (`references`, under the operation's
  handed projection), and the common part supplied ([[attributed]]).
  From the Throwable derive the outermost exception class, the whole cause
  `:seon.error/chain`, the ROOT cause's first complete stack frame, and —
  when the observation states none — the root cause's message."
  {:malli/schema
   [:function
    [:=> [:cat [:map
                [:seon.error/at :seon.error/at]
                [:seon.error/layer :seon.error/layer]
                [:seon.error/operation :seon.error/operation]
                [:seon.error/message {:optional true} :seon.error/message]
                [:seon.error/throwable {:optional true} :seon.error/throwable]]]
     :seon.error/base]
    [:=> [:cat :seon.error/at :seon.error/layer :seon.error/operation :map]
     :seon.error/base]]}
  ([{:seon.error/keys [throwable message] :as observation}]
  (let [projection (operation-projection)
        observation (attributed (references projection (dissoc observation :seon.error/throwable)) throwable)
        ;; a producer that is only the operation says nothing more here; the
        ;; recorder states it on the stored fault (seon.error/prepare)
        observation (cond-> observation
                      (= (:seon.error/producer observation) (:seon.error/operation observation))
                      (dissoc :seon.error/producer :seon.error/producer-digest))]
  (if throwable
    (let [links (chain throwable projection)
          primary (subvec links 0 (count (cause-links throwable)))
          ;; `root-frame`, read from the links' own first-party frames
          frame (some #(first (:seon.error/frames %)) (rseq primary))
          root-message (:seon.error/message (peek primary))]
      (cond-> (assoc observation
                     :seon.error/exception-class (symbol (.getName (class throwable)))
                     :seon.error/chain links)
        frame (assoc :seon.error/frame frame)
        (and (nil? message) root-message) (assoc :seon.error/message root-message)))
    observation)))
  ([at layer operation members]
   (diagnostic (merge {:seon.error/at at :seon.error/layer layer
                       :seon.error/operation operation}
                      members))))

(defn refusal
  "Deepest structural error in `ex-data`, retaining its exception message;
  else deepest non-empty data, or nil.

  This error-handling reader preserves arbitrary producers' errors under the
  bare base contract. Non-error exception data remains an ordinary map;
  reading exception data does not declare another producer's error union."
  {:malli/schema
   [:=> [:cat [:maybe :seon.error/throwable]]
    [:or :nil :map :seon.error/base]]}
  [throwable]
  (loop [candidate throwable
         deepest nil
         classified nil]
    (if (nil? candidate)
      (or classified deepest)
      (let [data (ex-data candidate)]
        (recur (ex-cause candidate)
               (if (seq data) data deepest)
               (if (and (:seon.error/at data)
                        (:seon.error/layer data)
                        (:seon.error/operation data))
                 (assoc data :seon.error/message
                        (or (:seon.error/message data)
                            (not-empty (ex-message candidate))
                            (str (:seon.error/operation data))))
                 classified))))))
