(ns ^{:seon/babashka true} seon.error.refusal
  "Diagnostic construction and cause-chain reading for error boundaries. An
  error holds references, never copies: every world in it is referenced here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            ;; `sci.lang.Var`, which `referenced` names, is defined by SCI: a
            ;; fresh JVM that loads this namespace first must load it too
            [sci.core]
            [seon.id :as id]
            [seon.schema.handed :as handed])
  (:import [java.util IdentityHashMap]))

(defn- machinery-namespace?
  "Whether `frame-ns` is Seon's own fault machinery or the operator's prepl
  wire: they CATCH a failure, and naming one routes the fault to the steward
  of the checker instead of the steward of the code that broke. Named
  exactly, so `seon.error.refusal-test` is not machinery. Dependencies and
  the host are recognized from the classpath (`authored-namespace?`)."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String frame-ns]
  (or (contains? #{"seon.error" "seon.error.refusal" "seon.instrument"
                   ;; the entrant boundary: the evaluator, turn and flow plumbing
                   ;; beneath every call, where a failure is carried, not made
                   "seon.sci.eval" "seon.sci.kernel" "seon.sci.observe" "seon.sci.batch"
                   "seon.turn" "seon.turn.world" "seon.batch" "seon.flow" "seon.cluster.agent"}
                 frame-ns)
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

(declare classify-namespace)

(defn- authored-namespace?
  "Whether frames of `frame-ns` are authored (`classify-namespace`), derived
  once per namespace in the central cache: the classpath does not change
  under a running process, and each classification reads it (tens of
  microseconds; a 177-frame stack cost 35 ms unshared)."
  {:malli/schema [:=> [:cat :string :symbol] :boolean]}
  [frame-ns class-name]
  ;; the packaged population hands the cache lookup; under babashka none is
  ;; bound (no Caffeine), and the classification is read directly
  (if-let [lookup (::handed/lookup handed/packaged)]
    (lookup ::authored-namespace frame-ns (constantly 64)
            #(classify-namespace frame-ns class-name))
    (classify-namespace frame-ns class-name)))

(defn- classify-namespace
  "Whether frames of `frame-ns` (one of whose classes is `class-name`) are
  code an agent or Seon wrote, decided from the classpath, never a name:
  its source is a file outside every library root of the basis; or it has
  no source and its class is not one the classpath holds — defined at a REPL
  or by `eval`, as `user` is (the JDK's and a dependency's classes,
  `clojure.lang.Compiler$FnExpr`, are held). Without a basis no sourceless
  namespace can be told from the host's own, so none is authored. Pure over
  the classpath: load state is never read."
  {:malli/schema [:=> [:cat :string :symbol] :boolean]}
  [^String frame-ns class-name]
  (let [path (-> frame-ns (.replace \- \_) (.replace \. \/))]
    (if-let [source (or (io/resource (str path ".clj")) (io/resource (str path ".cljc")))]
      (not (library-url? (str source)))
      (boolean
       (and @basis
            (not (io/resource (str (.replace (str class-name) \. \/) ".class"))))))))

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

(def ^:private prepared-call-prefix
  ;; The class prefix of the prepared-call closure `seon.call-preparation/preparing`
  ;; installs around every contract that declares a supplied default: contract
  ;; machinery whose frame sits between each such caller and the refusal, the
  ;; rule `seon.instrument/caller-frame` applies. A literal, never a lookup:
  ;; `seon.error.refusal-test` asserts it is that function's munged name.
  "seon.call_preparation$preparing$")

(defn- first-party-in
  "The frames of `frames` whose class's namespace part is in `authored`,
  less the prepared-call closure (`prepared-call-prefix`)."
  {:malli/schema [:=> [:cat [:set :string] [:vector [:cat :symbol [:* [:or :symbol :string :int]]]]]
                  [:vector [:cat :symbol [:* [:or :symbol :string :int]]]]]}
  [authored frames]
  (let [^String wrapper prepared-call-prefix]
    (filterv (fn [[class-name]]
               (let [text (str class-name) cut (.indexOf text "$")]
                 (and (pos? cut) (contains? authored (subs text 0 cut))
                      (not (.startsWith text wrapper)))))
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
  a frame the JVM reports without a file is not complete and is omitted.
  With `enclosing` (the throwable it caused or that suppressed it), only the
  frames it does not share with `enclosing`'s trace: the suffix in common is
  `enclosing`'s, as `Throwable.printStackTrace` prints it (`... n more`), so a
  chain's frames cost its distinct frames, never links x depth."
  {:malli/schema [:function
                  [:=> [:cat :seon.error/throwable] [:vector :seon.error/frame]]
                  [:=> [:cat :seon.error/throwable [:maybe :seon.error/throwable]] [:vector :seon.error/frame]]]}
  ([throwable] (stack-frames throwable nil))
  ([^Throwable throwable ^Throwable enclosing]
   (let [^"[Ljava.lang.StackTraceElement;" trace (.getStackTrace throwable)
         ^"[Ljava.lang.StackTraceElement;" outer (if enclosing (.getStackTrace enclosing) (make-array StackTraceElement 0))
         own (loop [m (dec (alength trace)) n (dec (alength outer))]
               (if (and (>= m 0) (>= n 0) (.equals (aget trace m) (aget outer n)))
                 (recur (dec m) (dec n))
                 (inc m)))]
     (into []
           (keep (fn [^StackTraceElement frame]
                   (when-let [file (.getFileName frame)]
                     [(symbol (.getClassName frame)) (symbol (.getMethodName frame))
                      file (long (.getLineNumber frame))])))
           (java.util.Arrays/copyOfRange trace 0 (int own))))))

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
    ;; the root's whole trace, then each enclosing link's own frames: a
    ;; link's frames shared with its enclosing link are that link's
    (let [links (cause-links throwable)
          stacks (into [(stack-frames (peek links))]
                       (map (fn [i] (stack-frames (links i) (when (pos? i) (links (dec i))))))
                       (range (- (count links) 2) -1 -1))
          authored (authored-prefixes (into [] cat stacks))]
      (some #(first (first-party-in authored %)) stacks))))

;;; WORLDS ARE REFERENCED, NEVER COPIED (owner 2026-09-26). Which values are
;;; worlds is the registry's answer, not a class list: every schema declared
;;; `:seon.schema/identity-only` (a database value, a connection, a schema
;;; projection, an environment, an SCI context) names the projection of its
;;; value to identity data — exactly what value admission stores in its place
;;; (`seon.sci.admit/open-node`). The projection is handed
;;; (`seon.schema.handed`), never found by name, and carries the operations
;;; asked of it: `:seon.schema.projection/identity-projector` and
;;; `:seon.schema.projection/explain`.

(defn- checking-projection
  "The projection an error is checked against: the one handed to the operation
  running now, else the packaged declaration projection
  (`seon.schema.handed/packaged`, bound when `seon.schema.edn` loads), so an
  error is buildable at boot, at namespace load and on a thread no operation
  handed a projection. Nil where `seon.schema.edn` is not loaded."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (or (handed/projection)
      (some-> (::handed/projection handed/packaged) deref)))

(defn- identity-projector
  [projection]
  (if-let [projector (:seon.schema.projection/identity-projector projection)]
    (projector projection)
    (constantly nil)))

(defn- object-name
  "How Clojure prints an object it does not enter, `#object[Class 0xhash]`,
  as a STRING a reader reads back."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]] :string]}
  [value]
  (str "#object[" (.getName (class value)) " 0x" (Integer/toHexString (System/identityHashCode value)) "]"))

(defn- entries
  "A map's, vector's or set's members as `[key member]`: a vector's key is
  its index, a set member is its own key."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]] [:maybe [:sequential [:tuple [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]]]]]}
  [value]
  (cond (map? value) (seq value)
        (vector? value) (seq (map-indexed vector value))
        :else (seq (map vector value value))))

(defn- referenced
  "`value` with every world inside it replaced by its identity; a collection
  holding no world is returned identical. Walked over an explicit stack, so
  depth costs heap, never JVM stack. One walk is one snapshot: each world is
  projected once and a collection met again by identity reuses its result."
  {:malli/schema [:=> [:cat :seon.instrument/callable [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [project value]
  ;; Identity tables local to this one walk: error data repeats one value
  ;; under every problem naming it. A result built inside a hash set serves
  ;; anywhere; one built outside serves only outside.
  (let [^IdentityHashMap projected (IdentityHashMap.)
        ^IdentityHashMap in-set (IdentityHashMap.)
        ^IdentityHashMap outside (IdentityHashMap.)
        project (fn [v] (if (.containsKey projected v)
                          (.get projected v)
                          (let [declared (project v)] (when (some? declared) (.put projected v declared)) declared)))
        ;; a leaf's `{::result r}`, else the node to walk; `hashed?`: a hash set encloses it
        opened (fn opened [value hashed?]
                 (if-some [declared (project value)]
                   (opened (:seon.schema/identity-value declared) hashed?)
                   (cond
                     ;; A PLACE IS ITS NAME: an atom, delay or promise prints its
                     ;; current state (`core_print.clj` deref-as-map), so it is
                     ;; named, never entered; a string, so a stored error reads back.
                     (and (instance? clojure.lang.IDeref value)
                          (not (instance? clojure.lang.Var value)) (not (instance? sci.lang.Var value))) {::result (object-name value)}
                     (record? value) {::result value}
                     ;; a vector by index, never reduce-kv, which walks a map entry as a map
                     (or (map? value) (vector? value) (set? value))
                     {::value value ::held value ::entries (entries value) ::hashed? hashed?}
                     :else {::result value})))
        open (fn [child hashed?]
               (cond (.containsKey in-set child) {::result (.get in-set child)}
                     (and (not hashed?) (.containsKey outside child)) {::result (.get outside child)}
                     :else (opened child hashed?)))
        ;; Only a rebuilt node inside a hash set is hashed, as it closes, its
        ;; children's hashes cached: the set's insert hashes one level, and no
        ;; leaf (a lazy seq, a cyclic Java collection) is hashed or realized.
        closed (fn [{::keys [value held hashed?]}]
                 (cond-> held (and hashed? (not (identical? held value))) (doto hash)))
        folded-in (fn [{::keys [value] :as node} k result child]
                    (cond (identical? result child) node
                          (set? value) (update node ::held #(conj (disj % child) result))
                          :else (update node ::held assoc k result)))
        root (opened value false)]
    (if-not (contains? root ::entries)
      (::result root)
      (loop [stack (list root)]
        (let [{pending ::entries ::keys [value hashed?] :as top} (peek stack)]
          (if (nil? pending)
            (let [result (closed top)
                  stack (pop stack)]
              (when-let [[_ child] (find top ::child)] (.put (if hashed? in-set outside) child result))
              (if-let [parent (peek stack)]
                (recur (conj (pop stack) (folded-in parent (::key top) result (::child top))))
                result))
            (let [[k child] (first pending)
                  top (assoc top ::entries (next pending))
                  node (open child (or hashed? (and (set? value) (not (sorted? value)))))]
              (if (contains? node ::entries)
                (recur (conj (pop stack) top (assoc node ::key k ::child child)))
                (recur (conj (pop stack) (folded-in top k (::result node) child)))))))))))

(defn references
  "`value` with every world in it — any value a schema declares
  identity-only in `projection` — replaced by that world's identity data:
  a database is its name and basis-t, a connection its id, a projection its
  fingerprint, an environment or SCI context the references it holds. Maps,
  vectors and sets are entered; records, sequences and other objects are
  not, and are never hashed or realized. A place (atom, delay, promise: any deref-able but a Var) becomes the
  string `#object[Class 0xhash]`, since printing it copies its state. Without
  a projection no world is recognized; places are still named."
  {:malli/schema [:=> [:cat [:maybe :map]
                       [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data; only registry-declared worlds inside it are replaced.", :gen/elements [nil false 0 "" :k [] {}]}]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Returns the supplied data with its worlds referenced.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [projection value]
  (referenced (identity-projector projection) value))

(defn value-reference
  "`value` as an error holds it in its one offending slot: a world is its
  identity and any other non-collection is itself; a collection is its class,
  its count and one digest of its data. The digest is one walk that projects
  nothing: a record or an object `seon.id` has no encoding for (a database, a
  connection, a context, a function) is hashed as its name, class and
  identity. A sequence that is not counted is its class alone, never realized."
  {:malli/schema [:=> [:cat [:maybe :map]
                       [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Error evidence is arbitrary producer data.", :gen/elements [nil false 0 "" :k [] {}]}]]
                  [:or :seon.error/reference
                   [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A value that is not a collection is held as itself.", :gen/elements [nil false 0 "" :k]}]]]}
  [projection value]
  (let [project (identity-projector projection)]
    (if (or (not (coll? value)) (some? (project value)))
      (referenced project value)
      (cond-> {:seon.error.reference/class (.getName (class value))}
        (counted? value)
        (assoc :seon.error.reference/count (count value)
               :seon.error.reference/digest
               (id/digest 64 [(referenced
                               ;; class tests only, run at every node: `inst?` is a
                               ;; protocol check whose miss allocates
                               (fn [v]
                                 (when (or (record? v)
                                           (not (or (coll? v) (nil? v) (string? v) (keyword? v) (symbol? v)
                                                    (number? v) (boolean? v) (char? v) (uuid? v)
                                                    (instance? java.util.Date v) (instance? java.time.Instant v)
                                                    (instance? java.util.regex.Pattern v) (tagged-literal? v))))
                                   {:seon.schema/identity-value (object-name v)}))
                               value)]))))))

(defn- link
  "One throwable as a flat link, the frames it does not share with
  `enclosing` (`stack-frames`) held under `::frames` until the chain keeps
  only the first-party ones, its ex-data whole with worlds referenced."
  {:malli/schema [:=> [:cat :seon.instrument/callable :seon.error/throwable
                       [:maybe :seon.error/throwable] [:maybe :map]] :map]}
  [project ^Throwable throwable enclosing relation]
  (let [message (ex-message throwable)
        data (ex-data throwable)]
    (cond-> (merge {:seon.error/throwable-class (.getName (class throwable))
                    ::frames (stack-frames throwable enclosing)}
                   relation)
      (seq message) (assoc :seon.error/message message)
      data (assoc :seon.error/data (referenced project data)))))

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
  Each link keeps the first-party frames it does not share with the link
  enclosing it (`stack-frames`, printStackTrace's `... n more`), so the
  outermost link holds its whole first-party trace and every distinct frame
  appears once: the walk costs the chain's distinct frames, not links x
  depth. Which of them an agent sees is its error schema's render pair's
  selection. Each link's data is the ex-data itself, whole (worlds and
  places referenced): the value printer bounds what is shown, and the
  recorder keeps it once by content in the occurrence's data blob.

  ## Costs

  The walk only: no value is printed or measured here."
  {:malli/schema [:function
                  [:=> [:cat :seon.error/throwable] :seon.error/chain]
                  [:=> [:cat :seon.error/throwable [:maybe :map]] :seon.error/chain]]}
  ([throwable] (chain throwable (handed/projection)))
  ([throwable projection]
   (let [project (identity-projector projection)]
     ;; each pending head carries the throwable enclosing it: nil for the
     ;; outermost, else the link that suppressed it
     (loop [pending [[throwable nil nil]] seen #{} links []]
       (if-let [[head relation suppressor] (first pending)]
         (let [own (vec (take-while (complement seen) (cause-links head)))
               start (count links)
               links (into links
                           (map-indexed
                            (fn [i t]
                              (link project t (if (zero? i) suppressor (own (dec i)))
                                    (when relation
                                      (if (zero? i) relation
                                          {:seon.error/cause-of (+ start i -1)})))))
                           own)
               suppressed (for [[i ^Throwable t] (map-indexed vector own)
                                s (.getSuppressed t)]
                            [s {:seon.error/suppressed-by (+ start i)} t])]
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

(defn first-frame-answer
  "The first non-nil `(pick frame)` over the calling thread's frames,
  innermost first, each `[class method file line]` built only as the walk
  reaches it; a `false` answer ends the walk with nil. A caller lookup
  touches the frames up to its answer, never converts the whole stack.
  (`StackWalker` would not capture the array either, but babashka, which
  loads this namespace for the operator, has no StackWalker.)"
  {:malli/schema [:=> [:cat :seon.instrument/callable] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The answer is whatever the caller's pick returns for a frame.", :gen/elements [nil "x"]}]]}
  [pick]
  (let [^"[Ljava.lang.StackTraceElement;" trace (.getStackTrace (Thread/currentThread))
        ;; the walk begins past this function's own frames
        own? (fn [i] (.startsWith (.getClassName ^StackTraceElement (aget trace i)) "seon.error.refusal$first_frame_answer"))
        start (or (some #(when (own? %) (loop [j %] (if (and (< (inc j) (alength trace)) (own? (inc j))) (recur (inc j)) (inc j))))
                        (range (min 4 (alength trace))))
                  0)]
    (loop [i start]
      (when (< i (alength trace))
        (let [^StackTraceElement frame (aget trace i)
              answer (pick [(symbol (.getClassName frame)) (symbol (.getMethodName frame))
                            (.getFileName frame) (long (.getLineNumber frame))])]
          (cond (false? answer) nil
                (some? answer) answer
                :else (recur (inc i))))))))

(defn calling-definition
  "The authored definition a compiled call came from: the first frame of the
  calling thread (innermost first) in code an agent or Seon wrote, outside
  the fault and contract machinery ([[first-party-frame?]]), named as the Var
  it belongs to ([[frame-function]]). Nil when that frame names no Var (an
  evaluated form: `user$eval123`) or when SCI's interpreter is reached first:
  then the caller is interpreted and its SCI frames name it."
  {:malli/schema [:=> [:cat] [:maybe :qualified-symbol]]}
  []
  (first-frame-answer
   (fn [[class-name _ file :as frame]]
     (cond
       (nil? file) nil
       (.startsWith (str class-name) "sci.") false
       (first-party-frame? frame) (or (frame-function class-name) false)))))

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

  ```clojure
  (producer (ex-data refusal) refusal)
  ;; => {:seon.error/producer seon.turn/open-call :seon.error/caller seon.turn/open-call}
  ```"
  {:malli/schema [:=> [:cat :map [:maybe :seon.error/throwable]]
                  [:map [:seon.error/producer {:optional true} :seon.error/producer]
                   [:seon.error/producer-digest {:optional true} :seon.error/producer-digest]
                   [:seon.error/caller {:optional true} :seon.error/caller]
                   [:seon.error/caller-digest {:optional true} :seon.error/caller-digest]]]}
  [error throwable]
  (let [refusal (contract-refusal error throwable)
        refuser (:seon.instrument/fn refusal)
        ;; the first frame past the refuser: an agent's own closure or form (`user`) is
        ;; the blamed caller, never the compiled or interpreted function that called it
        interpreted (delay (let [where (ffirst (remove (fn [[frame]] (= frame refuser)) (interpreted-frames error throwable)))]
                             (when (qualified-symbol? where) where)))
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
  reference ([[world-reference]]). What the producer stated wins, its
  `:seon.error/type` included."
  {:malli/schema [:=> [:cat :map [:maybe :seon.error/throwable]] :map]}
  [error throwable]
  (let [world (when-not (:seon.error/world error) (world-reference (current-world)))]
    (cond-> (reduce-kv (fn [held k v] (if (contains? held k) held (assoc held k v)))
                       error (producer error throwable))
      world (assoc :seon.error/world world))))

(defn diagnostic
  "Preserve the supplied observation, consuming only its optional Throwable,
  with every world in it referenced (`references`, under the operation's
  handed projection). The world and producer are not supplied here: only a
  boundary that records an occurrence attributes ([[attributed]]).
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
  (let [projection (handed/projection)
        observation (references projection (dissoc observation :seon.error/throwable))]
  (if throwable
    (let [links (chain throwable projection)
          primary (subvec links 0 (count (cause-links throwable)))
          ;; the root's WHOLE first-party trace, not its link's own frames: a
          ;; link keeps only frames it does not share with its enclosing link,
          ;; so its first own frame is the helper that threw, not the call site
          frame (root-frame throwable)
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

(defn error?
  "Whether `value` is an error value: a map carrying the base's three required
  members. The one check; every hand-written variant is this."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (and (map? value) (every? #(some? (get value %)) [:seon.error/at :seon.error/layer :seon.error/operation]) true))

(defn- construction-refusal
  "The `:seon.error/base` refusal for a malformed `error` construction: it
  names the `alternative`, carries the Malli `problems` (humanized, or nil
  where none could be computed) and holds the `attempted` value in its
  `:seon.error/chain` (a link's data, worlds referenced), so the
  malformed producer is loud and local. The blame rule's first authored frame
  is the caller of `error`."
  {:malli/schema [:=> [:cat :qualified-keyword :map :string :any] :seon.error/base]}
  [alternative attempted reason problems]
  (diagnostic
   {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.error.refusal/construction
    :seon.error/operation 'seon.error.refusal/error
    :seon.error/member alternative
    :seon.error/message (str "Error construction refused for " alternative ": " reason)
    :seon.error/fix (str "Supply every member " alternative " requires, of the shapes it declares.")
    :seon.error/throwable (ex-info reason (cond-> {:seon.error/attempted attempted}
                                            problems (assoc :seon.error/problems problems)))}))

(defn error
  "The error value of the declared error alternative `alternative` (a
  registered schema, e.g. `:my.fs/error` or an extension of
  `:seon.error/base`): THE one constructor, returned, never thrown.

  `members` is the producer's flat map under full keys, with
  `:seon.error/operation` required and optional `:seon.error/message`,
  `:seon.error/fix`, `:seon.error/member`, `:seon.error/expected-key`,
  `:seon.error/offending-projection` and its own. A `:seon.error/throwable`
  supplies the cause chain, frame and (absent a message) message
  ([[diagnostic]]). Absent, `:seon.error/at` is now and `:seon.error/layer`
  is `alternative`. The value holds no world or producer: the recording
  boundary attributes the occurrence.

  The built value is checked against `alternative` (`seon.schema/
  explain-candidate-value`, Malli `m/explainer`, compiled once per schema
  object). Valid: the error, carrying `alternative` as its
  `:seon.error/type` (which selects its render). Invalid (a
  missing operation included), or when `alternative` is not declared in the
  handed projection, else the packaged declaration projection (so an error is
  buildable at boot, at load and on any thread): a `:seon.error/base` refusal naming
  `alternative`, the humanized problems and the attempted value in
  `:seon.error/chain`.

  ## Examples

  ```clojure
  (error :my.fs/error {:seon.error/operation 'my.fs/read :seon.error/message \"No such file.\"})
  ```
  ## Costs

  One [[diagnostic]] plus one cached-explainer call; the refusal path adds a
  humanize and a chain walk, on the error path only."
  {:malli/schema [:=> [:cat :qualified-keyword :map] :seon.error/base]}
  [alternative members]
  (let [members (merge {:seon.error/at (java.util.Date.) :seon.error/layer alternative} members)
        projection (checking-projection)
        explain (:seon.schema.projection/explain projection)]
    (if-not (qualified-symbol? (:seon.error/operation members))
      (construction-refusal alternative members "`:seon.error/operation` (a qualified symbol) is required" nil)
      (let [built (diagnostic members)
            refused (fn [reason problems] (construction-refusal alternative built reason problems))]
        (if-not explain
          (refused "no schema registry is loaded, so the alternative cannot be checked" nil)
          (try
            (if-let [problems (explain projection alternative built)]
              (refused "the built error does not satisfy it" problems)
              (assoc built :seon.error/type alternative))
            (catch clojure.lang.ExceptionInfo e
              (refused (str "it cannot be checked: " (ex-message e)) nil))))))))

(defmacro let-ok
  "Like `let`, but a bound value that is an [[error?]] is returned at once and
  the rest is not evaluated, so callers stop writing `(if (error? x) x ...)`
  ladders. Expands to plain `let` and `if`: contracts stay on the functions."
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)
    (let [[target expr & more] bindings]
      `(let [value# ~expr]
         (if (error? value#) value# (let [~target value#] (let-ok ~(vec more) ~@body)))))))

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
