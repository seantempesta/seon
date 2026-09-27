(ns seon.error.refusal
  "Diagnostic construction and cause-chain reading for error boundaries. An
  error holds references, never copies: every world in it is referenced here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

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

(defn- cause-links
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
      (or (map? value) (vector? value))
      (reduce-kv (fn [held k v]
                   (let [r (referenced project v)]
                     (if (identical? r v) held (assoc held k r))))
                 value value)
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
  referenced and within the evidence bound (`bounded-cost`)."
  [project ^Throwable throwable relation]
  (let [message (ex-message throwable)
        data (ex-data throwable)]
    (cond-> (merge {:seon.error/throwable-class (.getName (class throwable))
                    ::frames (stack-frames throwable)}
                   relation)
      (seq message) (assoc :seon.error/message message)
      data (assoc :seon.error/data (first (bounded-cost (referenced project data)))))))

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
  error schema's render pair's selection."
  {:malli/schema [:function
                  [:=> [:cat :seon.error/throwable] :seon.error/chain]
                  [:=> [:cat :seon.error/throwable [:maybe :map]] :seon.error/chain]]}
  ([throwable] (chain throwable (operation-projection)))
  ([throwable projection]
   (let [project (identity-projector projection)]
     (loop [pending [[throwable nil]] seen #{} links []]
       (if-let [[head relation] (first pending)]
         (let [own (vec (take-while (complement seen) (cause-links head)))
               start (count links)
               links (into links
                           (map-indexed
                            (fn [i t]
                              (link project t (when relation
                                                (if (zero? i) relation
                                                    {:seon.error/cause-of (+ start i -1)})))))
                           own)
               suppressed (for [[i ^Throwable t] (map-indexed vector own)
                                s (.getSuppressed t)]
                            [s {:seon.error/suppressed-by (+ start i)}])]
           (recur (into (subvec pending 1) suppressed) (into seen own) links))
         (first-party-links links))))))

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

(defn diagnostic
  "Preserve the supplied observation, consuming only its optional Throwable,
  with every world in it referenced (`references`, under the operation's
  handed projection).
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
        observation (references projection (dissoc observation :seon.error/throwable))]
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
