(ns seon.error.refusal
  "Diagnostic construction and cause-chain reading for error boundaries. An
  error holds references, never copies: every world in it is referenced here.")

(def ^:private machinery-namespace-prefixes
  ;; DERIVED FROM WHAT THESE FRAMES ARE, exactly as
  ;; `seon.instrument/caller-frame` derives its own: the host, the
  ;; language, the contract library, core.async's dispatch, the fault
  ;; machinery and the operator's prepl wire are what CAUGHT the failure. None of them is a place to go
  ;; and edit, and naming one routes the fault to the steward of the
  ;; checker instead of the steward of the code that broke.
  ["clojure." "java." "jdk." "sun." "malli." "seon.error" "seon.instrument"
   "seon.operator."])

(def ^:private first-party-namespace-prefixes
  ;; The program's own namespaces: the ones an agent can open and edit.
  ["seon." "my."])

(defn frame-function
  "The function a stack frame's class belongs to, as `ns/name`, or nil.

  Demunged Clojure frames read `ns/fn`, `ns/fn--1234` for a compiled
  arity and `ns/outer/fn` for a closure, so the function is the first two
  segments with the compiler's suffix dropped. A class that demunges to no
  `/` is a host class and is not a function at all; a frame in the
  language, host, contract or fault machinery is where a failure was
  caught, not where it happened, and names nothing."
  {:malli/schema [:=> [:cat :symbol] [:or :nil :qualified-symbol]]}
  [class-name]
  (let [demunged (clojure.lang.Compiler/demunge (str class-name))
        separator (.indexOf demunged "/")]
    (when (pos? separator)
      (let [frame-ns (subs demunged 0 separator)
            simple (subs demunged (inc separator))
            nested (.indexOf simple "/")
            simple (if (neg? nested) simple (subs simple 0 nested))
            suffix (.indexOf simple "--")
            simple (if (neg? suffix) simple (subs simple 0 suffix))]
        (when (and (seq simple)
                   (not (some #(.startsWith ^String frame-ns ^String %)
                              machinery-namespace-prefixes)))
          (symbol frame-ns simple))))))

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
  "Whether a frame belongs to a first-party function (`seon.*`/`my.*`,
  outside the fault machinery)."
  {:malli/schema [:=> [:cat :seon.error/frame] :boolean]}
  [[class-name]]
  (boolean
   (when-let [function (frame-function class-name)]
     (some #(.startsWith ^String (namespace function) ^String %)
           first-party-namespace-prefixes))))

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
  "The first complete stack frame of the ROOT cause, where the failure
  happened. The outermost throwable's frame is where it was wrapped: a
  wrapper's frame names the catch site, never the code that broke."
  {:malli/schema [:=> [:cat [:or :nil :seon.error/throwable]]
                  [:or :nil :seon.error/frame]]}
  [throwable]
  (when throwable
    (first (stack-frames (peek (cause-links throwable))))))

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
  "The projection handed to the operation running now, or nil."
  []
  ((:handed @schema-seam)))

(defn- identity-projector
  [projection]
  (if projection ((:projector @schema-seam) projection) (constantly nil)))

(defn- referenced
  "`value` with every world inside it replaced by its identity; a collection
  holding no world is returned identical."
  [project value]
  (if-some [declared (project value)]
    (referenced project (:seon.schema/identity-value declared))
    (cond
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

(defn references
  "`value` with every world in it — any value a schema declares
  identity-only in `projection` — replaced by that world's identity data:
  a database is its name and basis-t, a connection its id, a projection its
  fingerprint, an environment or SCI context the references it holds. Maps,
  vectors and sets are entered; records, sequences and other objects are
  not. Without a projection nothing is recognized and `value` is returned."
  {:malli/schema [:=> [:cat [:maybe :map]
                       [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Returns the supplied data with its worlds referenced.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [projection value]
  (referenced (identity-projector projection) value))

(defn- first-party-frames
  "`throwable`'s first-party frames: where a core fault happened and the
  call path that reached it."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :seon.error/frame]]}
  [throwable]
  (filterv first-party-frame? (stack-frames throwable)))

(defn called-frames
  "`throwable`'s first-party frames inside the host code an interpreted form
  called, innermost first: its stack up to the first frame of SCI's
  interpreter (`sci.impl.*`). Every frame past that one is the evaluator
  that ran the author's form, never the author's; the author's own location
  is SCI's stacktrace. A throw from the interpreted code itself calls no
  first-party host code and has none."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :seon.error/frame]]}
  [throwable]
  (into [] (comp (take-while (fn [[class-name]] (not (.startsWith (str class-name) "sci.impl."))))
                 (filter first-party-frame?))
        (stack-frames throwable)))

(defn- link
  [project frames-of ^Throwable throwable relation]
  (let [frames (frames-of throwable)
        message (ex-message throwable)
        data (ex-data throwable)]
    (cond-> (merge {:seon.error/throwable-class (.getName (class throwable))} relation)
      (seq message) (assoc :seon.error/message message)
      data (assoc :seon.error/data (referenced project data))
      (seq frames) (assoc :seon.error/frames frames))))

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
  `frames-of` selects each link's frames: by default every first-party frame
  (a core fault's evidence); an agent's own exception passes `called-frames`."
  {:malli/schema [:function
                  [:=> [:cat :seon.error/throwable] :seon.error/chain]
                  [:=> [:cat :seon.error/throwable [:maybe :map]] :seon.error/chain]
                  [:=> [:cat :seon.error/throwable [:maybe :map]
                        [:=> [:cat :seon.error/throwable] [:vector :seon.error/frame]]]
                   :seon.error/chain]]}
  ([throwable] (chain throwable (operation-projection)))
  ([throwable projection] (chain throwable projection first-party-frames))
  ([throwable projection frames-of]
   (let [project (identity-projector projection)]
     (loop [pending [[throwable nil]] seen #{} links []]
       (if-let [[head relation] (first pending)]
         (let [own (vec (take-while (complement seen) (cause-links head)))
               start (count links)
               links (into links
                           (map-indexed
                            (fn [i t]
                              (link project frames-of t (when relation
                                                (if (zero? i) relation
                                                    {:seon.error/cause-of (+ start i -1)})))))
                           own)
               suppressed (for [[i ^Throwable t] (map-indexed vector own)
                                s (.getSuppressed t)]
                            [s {:seon.error/suppressed-by (+ start i)}])]
           (recur (into (subvec pending 1) suppressed) (into seen own) links))
         links)))))

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
          frame (root-frame throwable)
          root-message (:seon.error/message
                        (nth links (dec (count (cause-links throwable)))))]
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
