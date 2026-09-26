(ns seon.render.value
  "One structural value renderer: profile-bounded AI and complete HTML."
  (:require [clojure.string :as str]
            [seon.id :as id]
            [seon.db :as db]
            [clojure.edn :as edn]
            [seon.print :as print]
            [seon.schema.edn :as schema.edn]
            [seon.schema :as schema]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.sci.admit :as admit]
            [seon.ai.tokens :as tokens]
            [sci.lang]))

(schema.edn/load! {})

;;; LOAD-CYCLE BOUNDARIES. `seon.render` and `seon.config` both require
;;; `seon.render.value` transitively, so this namespace cannot require them
;;; back. One resolution per var, realized at first use, instead of a
;;; `requiring-resolve` on every call (AGENTS §2.1).

(defonce ^:private render-agent-render-profile
  (delay (requiring-resolve 'seon.render/agent-render-profile)))
(defonce ^:private config-defaults
  (delay (requiring-resolve 'seon.config/defaults)))
(defonce ^:private render-declared-projection
  (delay (requiring-resolve 'seon.render/declared-projection)))


;;; THE PROJECTION-FREE TOTAL CORE: bounded text of any value and any Throwable,
;;; for when the projection, profile or admission below is what broke. Rules:
;;; (1) only clojure.core data prints (EDN scalars and Vars), `clojure.lang` collections walked to
;;; `:items` members and `:depth` levels (a lazy seq realizes a chunk at most);
;;; (2) every other object prints as `#object[Class 0xidentity-hash]`, never
;;; through its print-method, toString or deref; (3) a writer refuses output
;;; past the limit. Compiled caps, never read from the database.

(def ^:private text-limits
  ;; Measured 2026-09-23, armed on default: a three-link chain holding a
  ;; 1e6-char string, a 1e5 vector, (range), an atom and a throwing toString
  ;; renders 1,339 chars in 345 us.
  {:chars 400 :items 8 :depth 3 :entries 12 :links 8 :frames 6 :suppressed 3 :floor-chars 8192})

(def ^:private text-full
  (doto (Exception. "bounded text limit reached") (.setStackTrace (make-array StackTraceElement 0))))

(defn- datum
  "`x` as clojure.core data at most `level` collections deep (rules 1 and 2):
  `...` marks a cut collection or its omitted members."
  {:malli/schema [:=> [:cat :seon.schema/value :int] :seon.schema/value]}
  [x level]
  (let [items (:items text-limits)]
    (cond
      (or (nil? x) (boolean? x) (number? x) (string? x) (char? x) (ident? x) (var? x)
          (uuid? x) (instance? java.util.Date x)) x
      (not (and (coll? x) (.startsWith (.getName (class x)) "clojure.lang.")))
      (symbol (str "#object[" (.getName (class x)) " 0x" (Integer/toHexString (System/identityHashCode x)) "]"))
      (zero? level) '...
      :else (let [walk #(datum % (dec level))
                  members (cond-> (mapv (if (map? x) (fn [[k v]] [(walk k) (walk v)]) walk) (take items x))
                            (> (bounded-count (inc items) x) items) (conj (if (map? x) ['... '...] '...)))]
              (cond (map? x) (into {} members) (vector? x) members
                    (set? x) (set members) :else (apply list members))))))

(defn bounded-text
  "`value` as at most `limit` characters (rule 3 over `datum`); `…` marks the
  limit, `#unprintable[value-class failure-class]` a value whose printing throws.

  The projection-free core for when the projection, profile or admission is
  what broke; kept beside the selection by root ruling 2026-09-26, with
  `floor`, because diagnosis must survive the renderer failing."
  {:malli/schema [:function [:=> [:cat :seon.schema/value] :string]
                  [:=> [:cat :seon.schema/value [:int {:min 1}]] :string]]}
  ([value] (bounded-text value (:chars text-limits)))
  ([value limit]
   (let [out (StringBuilder.)
         put (fn [^String s]
               (let [room (- limit (.length out))]
                 (.append out s 0 (int (min room (.length s))))
                 (when (> (.length s) room) (throw text-full))))
         writer (proxy [java.io.Writer] []
                  (write ([x] (put (cond (string? x) x (int? x) (str (char x)) :else (String. ^chars x))))
                         ([x off len] (put (if (string? x) (subs x off (+ off len)) (String. ^chars x (int off) (int len))))))
                  (flush []) (close []))]
     (try
       (binding [*print-length* nil *print-level* nil *print-meta* false *print-readably* true
                 *print-dup* false *print-namespace-maps* false]
         (print-method (datum value (:depth text-limits)) writer))
       (str out)
       (catch Throwable failure
         (str out (if (identical? failure text-full) "…"
                    (str "#unprintable[" (.getName (class value)) " " (.getName (class failure)) "]"))))))))

(defn- causes
  "`throwable` and its causes, outermost first, each once, at most `:links`."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :seon.error/throwable]]}
  [throwable]
  (loop [link throwable seen []]
    (if (or (nil? link) (= (:links text-limits) (count seen)) (some #(identical? link %) seen))
      seen
      (recur (.getCause ^Throwable link) (conj seen link)))))

(defn- link-lines
  "One link's lines: class and message, ex-data entries, first-party frames and
  suppressed throwables; a link whose accessors throw prints its class and the failure's."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :string]]}
  [^Throwable link]
  (let [{:keys [entries frames suppressed]} text-limits
        frame (fn [^StackTraceElement f]
                [(symbol (.getClassName f)) (symbol (.getMethodName f)) (str (.getFileName f)) (long (.getLineNumber f))])]
    (try
      (into [(str (.getName (class link)) ": " (bounded-text (ex-message link)))]
            (concat
             (for [[k v] (take entries (ex-data link))] (str "  " (bounded-text k) " " (bounded-text v)))
             (when (> (bounded-count (inc entries) (ex-data link)) entries) ["  … further ex-data entries"])
             (for [[class-name _ file line] (take frames (filter refusal/first-party-frame? (map frame (.getStackTrace link))))]
               (str "  at " (clojure.lang.Compiler/demunge (str class-name)) " (" file ":" line ")"))
             (for [^Throwable other (take suppressed (.getSuppressed link))]
               (str "  suppressed " (.getName (class other)) ": " (bounded-text (ex-message other))))))
      (catch Throwable failure
        [(str (.getName (class link)) " #unprintable[" (.getName (class failure)) "]")]))))

(defn floor
  "An unhandled Throwable as bounded text from the Throwable alone, one block
  of `link-lines` per cause. The leaf an error's rendering degrades to, never a
  stored member.

  It keeps Seon's first-party frames: panics and test failures must keep the
  whole cause (AGENTS.md, root ruling 2026-09-26). The one-render-path rule
  that acquires no frames governs only the generic floor's selection."
  {:malli/schema [:=> [:cat :seon.error/throwable] :string]}
  [throwable]
  (let [chain (causes throwable)
        text (str/join "\n" (concat (mapcat (fn [position link]
                                              (cond-> (link-lines link) (pos? position) (update 0 #(str "caused by " %))))
                                            (range) chain)
                                    (when (and (= (:links text-limits) (count chain)) (ex-cause (peek chain)))
                                      ["… further causes"])))]
    (if (> (count text) (:floor-chars text-limits)) (str (subs text 0 (:floor-chars text-limits)) "…") text)))

(defn transacted
  "Restore a pulled entity to its transaction shape.

  With a database value, installed Datahike value type and cardinality are the
  authority: refs become entity ids, cardinality-many values become sets, and
  scalar EDN vectors remain vectors. The one-argument arity preserves the
  established shape-only behavior for callers without database custody.

  This data conversion returns the supplied entity's attributes. A stored
  error entity is ordinary data here; this reader does not produce an error."
  {:malli/schema
   [:function
    [:=> [:catn [::entity :map]]
     :map]
    [:=> [:catn [::entity :map]
                 [::database :seon.db/db]]
     :map]]}
  ([entity]
   (into {}
         (map (fn [[attribute value]]
                [attribute
                 (cond
                   (and (map? value) (find value :db/id)) (:db/id value)
                   (and (sequential? value)
                        (seq value)
                        (every? #(and (map? %) (find % :db/id)) value))
                   (into #{} (map :db/id) value)
                   (sequential? value) (set value)
                   :else value)]))
         (dissoc entity :db/id)))
  ([entity database]
   (into {}
         (map (fn [[attribute value]]
                (let [{:db/keys [valueType cardinality]}
                      (get-in database [:schema attribute])
                      ref-id #(if (and (map? %) (find % :db/id))
                                (:db/id %)
                                %)]
                  [attribute
                   (cond
                     (= :db.type/ref valueType)
                     (if (= :db.cardinality/many cardinality)
                       (into #{} (map ref-id) value)
                       (ref-id value))

                     (= :db.cardinality/many cardinality)
                     (set value)

                     :else value)])))
         (dissoc entity :db/id))))

(defn render-database-identity-ai
  "Readable identity face for an admitted immutable database value."
  ;; The input is the identity projection the registry declares this
  ;; producer on (`:seon.db/database-value-identity`, unqualified Datahike
  ;; keys), not a render unit: declared as a unit, an armed JVM refused it
  ;; on every call (issue: the-database-identity-face-cannot-satisfy-its-
  ;; own-declared-input, 2026-09-08).
  {:malli/schema [:=> [:cat :seon.db/database-value-identity] :string]}
  [unit]
  (str "database " (pr-str (:db-name unit))
       " at basis transaction " (:t unit)
       " commit " (:datahike/commit-id unit)))

(def ^:private print-option-keys
  #{:seon.print/length
    :seon.print/level
    :seon.print/width
    :seon.print/namespace-maps?})

(defn- identity-address
  "The value's OWN address, when the caller supplied none.

  A DERIVATION, not an invented id: the address is the value's installed
  `:db.unique/identity` attribute and its value, read from the database the
  request carries — the same authority `seon.render/target-profile`
  (`src/seon/render.clj:110`) already uses for requery identity. It closes a
  real production hole: `seon.render/producer-argument`
  (`src/seon/render.clj:196`) removes `:seon.render.call/id` before invoking a
  DECLARED producer, and the page's walk request
  (`src/seon/render/web.clj:2977`) supplies no `:seon.render.value/root`, so a
  declared producer that delegates its own value to this floor — `seon.ai/
  attempt-html`, whose contract promises `:seon.render/hiccup` — reached
  `node-id` with nothing and returned a refusal where its own contract
  required Hiccup.

  There is deliberately NO digest-of-the-value fallback. `surface-id`
  (`src/seon/render/block.clj:61`) states the requirement this obeys: the map
  from address to DOM id must be injective, and two distinct anonymous roots
  that happen to hold equal values would morph over each other under a content
  digest. A value with no identity has no address, and the refusal below
  remains the honest answer for it."
  [unit]
  (let [value (:seon.render/value unit)
        database (:seon.db/db unit)]
    (when (and database (map? value))
      (some (fn [attribute]
              (when-some [entry (find value attribute)]
                [attribute (val entry)]))
            (db/identity-attributes database)))))

(defn node-id
  "Stable element id for one root selector and `get-in` path."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.render.data/path]
                  [:or :string :seon.render.value/missing-root-identity-error]]}
  [unit path]
  (let [root-address
        (or (:seon.render.call/id unit)
            (:seon.render.value/root unit)
            (when-some [eid (:db/id unit)] [:db/id eid])
            (when-some [block-name (:seon.render.block/name unit)]
              [:seon.render.block/name block-name])
            (identity-address unit))]
    (if-not root-address
      ;; The offending value is the identity members the unit HAS, never the
      ;; unit: it carries the SCI ctx and the database, and admitting that
      ;; copy cost 4.6 s and 22.8 GB (render-graph research §0.1).
      (let [identity-members (select-keys unit [:seon.agent/id :seon.render.call/id
                                                :seon.render.value/root :db/id
                                                :seon.render.block/name])]
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.value/identity 'seon.render.value/node-id
       {:seon.error/message "A rendered value root requires a caller-supplied block id."
        :seon.error/fix "Supply :seon.render.call/id, :seon.render.value/root, or an entity identity."
        :seon.render.value/root-description (pr-str identity-members)
        :seon.error/member :seon.render.value/root
        :seon.error/expected "a caller-supplied block or entity identity"
        :seon.error/offending identity-members
        :seon.error/data {:seon.agent/id (:seon.agent/id unit) :seon.render.data/path path}}))
      (str "seon-value-"
           (id/digest 24 [(:seon.agent/id unit) root-address path])))))

(defn- encoded
  [value]
  (java.net.URLEncoder/encode (str value) "UTF-8"))

(defn- path-url
  [unit path offset]
  (when-let [base (:seon.render.value/route-base unit)]
    (str base (if (str/includes? base "?") "&" "?")
         "path=" (encoded (pr-str path)) "&offset=" offset)))

(defn- path-link
  [unit path offset label css-class]
  (if-some [url (path-url unit path offset)]
    [:a {:class css-class :href url} label]
    label))

(defn- print-options
  [unit]
  (merge (print/default-options)
         (select-keys (:seon.render.value/options unit) print-option-keys)
         (:seon.print/options unit)))

(defn- render-profile
  [unit]
  (let [profile (or (:seon.render/profile unit)
                    (@render-agent-render-profile
                     @@config-defaults))
        root (or (:seon.repl/handle unit) (:seon.render.value/root unit))
        root (when (and (qualified-symbol? root) (= "result" (namespace root))) root)]
    (cond
      root (assoc profile :seon.print/requery-id root)
      (or (:seon.print/requery-id profile)
          (:seon.print/requery-refusal profile)) profile
      :else (assoc profile :seon.print/requery-refusal
                   "the value has no result handle"))))

;;; THE FLOOR'S SELECTION (design one-render-path §3a–§3d). Every reached
;;; object is classified by its EXACT class into a row of permitted operations
;;; before anything else touches it; an unlisted class is opaque. Members are
;;; admitted in shell order (a member's shell is the largest of its depth and
;;; the ordinals on its path), so each branch of a large value shows a prefix
;;; before any branch shows more. The charge is the laid-out length itself:
;;; flat width plus the indentation of every group fipp will break (its own
;;; fits test, reference-code/fipp/src/fipp/engine.cljc:203-208), so the saved
;;; text is at most B. The queue and the charge are one value threaded through
;;; a loop; a refused admission keeps the previous value.

(def ^:private scalar-bound 32)
(def ^:private chunk-width 16)
(def ^:private nest-cap 6)
(def ^:private sorted-bound 64)

(defn- escaped-width
  "Characters `pr-str` spends on chars [from, to) of `s`: `.length`, `.charAt`."
  {:malli/schema [:=> [:cat :string :int :int] :int]}
  [^String s from to]
  (loop [i (long from) width 0]
    (if (< i to)
      (recur (inc i) (+ width (if-let [e (char-escape-string (.charAt s i))] (count e) 1)))
      width)))

(defn- integer-digits
  "An upper bound on a BigInteger's decimal spelling, from its bit length."
  {:malli/schema [:=> [:cat :seon.schema/value] :int]}
  [^java.math.BigInteger b]
  (+ 2 (long (Math/ceil (* (.bitLength b) (Math/log10 2))))))

(defn- throwable-safe?
  "True when a Throwable class runs only Throwable's own accessors."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [^Class c]
  (every? (fn [m] (= Throwable (.getDeclaringClass (.getMethod c ^String m (make-array Class 0)))))
          ["getMessage" "getCause" "getStackTrace" "getSuppressed"]))

(def ^:private throwable-safe (memoize throwable-safe?))

(defn- throwable-value
  "A Throwable as plain data: type, message and ex-data per link, cycles
  bounded by identity. Frames are not acquired (design §3g)."
  {:malli/schema [:=> [:cat :seon.error/throwable] :map]}
  [^Throwable t]
  (loop [^Throwable link t seen [] via []]
    (cond
      (or (nil? link) (some #(identical? % link) seen)) {:via via}
      (not (throwable-safe (class link))) {:via (conj via {:type (symbol (.getName (class link)))})}
      :else (recur (.getCause link) (conj seen link)
                   (conj via (cond-> {:type (symbol (.getName (class link)))}
                               (.getMessage link) (assoc :message (.getMessage link))
                               (identical? (class link) clojure.lang.ExceptionInfo)
                               (assoc :data (.getData ^clojure.lang.ExceptionInfo link))))))))

(defn- seq-cells
  "A seq's first `limit` members, stepped as Clojure's printer steps any
  ISeq (`first`, `next`). An unrealized pending cell (a LazySeq, an
  Iterate's next value) ends the prefix as pending, never realized here;
  cells past `limit` end it as more."
  {:malli/schema [:=> [:cat :seon.schema/value :int] :map]}
  [x limit]
  (loop [cell x out (transient [])]
    (cond
      (or (nil? cell) (identical? (class cell) clojure.lang.PersistentList$EmptyList))
      {:members (persistent! out)}
      (and (instance? clojure.lang.IPending cell) (not (realized? cell)))
      {:members (persistent! out) :pending? true}
      (identical? (class cell) clojure.lang.LazySeq) (recur (.seq ^clojure.lang.LazySeq cell) out)
      (>= (count out) limit) {:members (persistent! out) :more? true}
      (instance? clojure.lang.ISeq cell)
      (recur (.next ^clojure.lang.ISeq cell) (conj! out (.first ^clojure.lang.ISeq cell)))
      :else {:members (persistent! out) :pending? true})))

(defn- list-row
  "A sequential value as a list (Clojure's printer): its stepped members,
  with a total when every member was reached or the value is Counted."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value :int] :map]}
  [x cells limit]
  (let [{:keys [members pending? more?]} (seq-cells cells limit)]
    (cond-> {:kind :coll :face ::print/list :open "(" :close ")" :members (seq members)}
      (not (or pending? more?)) (assoc :total (count members))
      (and more? (counted? x)) (assoc :total (count x))
      (or pending? (and more? (not (counted? x)))) (assoc :pending? true))))

(defn- held-order
  "Members in held order; an exact hash map or set of at most 64 exact
  keywords is sorted, identity attributes first (design §3b)."
  {:malli/schema [:=> [:cat :seon.schema/value [:set :qualified-keyword] [:fn clojure.core/ifn?]] :seon.schema/value]}
  [x identity-attributes key-of]
  (let [members (seq x)]
    (if (and (#{clojure.lang.PersistentHashMap clojure.lang.PersistentHashSet} (class x))
             (<= (count x) sorted-bound)
             (every? #(identical? (class (key-of %)) clojure.lang.Keyword) members))
      (sort-by (fn [member] (let [k (key-of member)]
                              [(if (contains? identity-attributes k) 0 1) (namespace k) (name k)]))
               members)
      members)))

(defn- scalar
  [face value text]
  {:kind :scalar :node {::print/face face ::print/value value ::print/text text}})

(defn- classify
  "One row of the permitted-operation table: exact classes for scalars, maps
  and sets; seqs and other sequentials by interface, stepped to `limit`."
  {:malli/schema [:=> [:cat :seon.schema/value [:set :qualified-keyword] :int] :map]}
  [x identity-attributes limit]
  (let [c (class x)]
    (cond
      (nil? x) (scalar ::print/nil nil "nil")
      (identical? c Boolean) (scalar ::print/boolean x (str x))
      (#{Long Integer Short Byte} c) (scalar ::print/number x (str x))
      (#{Double Float} c)
      (scalar ::print/number x (let [d (double x)]
                                 (cond (Double/isNaN d) "##NaN"
                                       (= d Double/POSITIVE_INFINITY) "##Inf"
                                       (= d Double/NEGATIVE_INFINITY) "##-Inf"
                                       :else (str x))))
      (identical? c Character)
      (scalar ::print/char x (if-let [n (char-name-string x)] (str "\\" n) (str "\\" x)))
      (identical? c String) {:kind :string :value x :total (.length ^String x)}
      (identical? c clojure.lang.Keyword)
      (let [^clojure.lang.Symbol s (.sym ^clojure.lang.Keyword x)]
        {:kind :name :node {::print/face ::print/keyword ::print/value x}
         :text (str ":" (when (.getNamespace s) (str (.getNamespace s) "/")) (.getName s))})
      (identical? c clojure.lang.Symbol)
      ;; Metadata is never read: final fields only.
      {:kind :name :node {::print/face ::print/symbol ::print/value x}
       :text (str (when (.getNamespace ^clojure.lang.Symbol x) (str (.getNamespace ^clojure.lang.Symbol x) "/"))
                  (.getName ^clojure.lang.Symbol x))}
      (identical? c java.util.UUID) (scalar ::print/uuid x (str "#uuid \"" x "\""))
      (identical? c java.util.Date) (scalar ::print/inst x (str "#inst \"" (.toInstant ^java.util.Date x) "\""))
      (identical? c clojure.lang.BigInt)
      (let [b (.bipart ^clojure.lang.BigInt x)]
        (cond (nil? b) (scalar ::print/number x (str (.lpart ^clojure.lang.BigInt x) "N"))
              (and (identical? (class b) java.math.BigInteger) (<= (integer-digits b) scalar-bound))
              (scalar ::print/number x (str b "N"))
              :else (scalar ::print/number x (str "#bigint[≈" (integer-digits b) " digits]"))))
      (identical? c clojure.lang.Ratio)
      (let [n (.numerator ^clojure.lang.Ratio x) d (.denominator ^clojure.lang.Ratio x)]
        (if (and (identical? (class n) java.math.BigInteger) (identical? (class d) java.math.BigInteger)
                 (<= (+ (integer-digits n) (integer-digits d)) scalar-bound))
          (scalar ::print/number x (str n "/" d))
          (scalar ::print/number x (str "#ratio[≈" (+ (integer-digits n) (integer-digits d)) " digits]"))))
      (identical? c java.math.BigDecimal)
      (let [u (.unscaledValue ^java.math.BigDecimal x)]
        (if (and (identical? (class u) java.math.BigInteger)
                 (<= (+ (integer-digits u) (count (str (.scale ^java.math.BigDecimal x)))) scalar-bound))
          (scalar ::print/number x (str x "M"))
          (scalar ::print/number x (str "#bigdec[≈" (integer-digits u) " digits]"))))
      (#{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap clojure.lang.PersistentTreeMap} c)
      {:kind :map :face ::print/map :open "{" :close "}" :total (count x)
       :members (if (identical? c clojure.lang.PersistentHashMap) (held-order x identity-attributes key) (seq x))}
      (#{clojure.lang.PersistentVector clojure.lang.MapEntry} c)
      {:kind :coll :face ::print/vector :open "[" :close "]" :total (count x) :members (seq x)}
      (#{clojure.lang.PersistentHashSet clojure.lang.PersistentTreeSet} c)
      {:kind :coll :face ::print/set :open "#{" :close "}" :total (count x) :ordered? true
       :members (if (identical? c clojure.lang.PersistentHashSet) (held-order x identity-attributes identity) (seq x))}
      ;; Every seq and every other Sequential prints as a list, as Clojure's
      ;; printer prints it; a vector type prints as a vector.
      (instance? clojure.lang.ISeq x) (list-row x x limit)
      (instance? clojure.lang.IPersistentVector x)
      {:kind :coll :face ::print/vector :open "[" :close "]" :total (count x) :members (seq x)}
      (instance? clojure.lang.Sequential x) (list-row x (seq x) limit)
      (identical? c clojure.lang.Var)
      {:kind :scalar :node {::print/face ::print/var ::print/name (str (.name (.ns ^clojure.lang.Var x)) "/" (.sym ^clojure.lang.Var x))}}
      ;; An agent's Var: final fields `sym` and `ns` of final classes
      ;; (sci/lang.cljc Var, Namespace); its metadata is never read.
      (identical? c sci.lang.Var)
      (let [sym (.-sym ^sci.lang.Var x) space (.-ns ^sci.lang.Var x)]
        {:kind :scalar
         :node {::print/face ::print/var
                ::print/name (str (when (identical? (class space) sci.lang.Namespace)
                                    (str (.-name ^sci.lang.Namespace space) "/"))
                                  (when (identical? (class sym) clojure.lang.Symbol)
                                    (.getName ^clojure.lang.Symbol sym)))}})
      (identical? c Class) {:kind :scalar :node {::print/face ::print/class ::print/name (.getName ^Class x)}}
      (and (instance? Throwable x) (throwable-safe c))
      (assoc (classify (throwable-value x) identity-attributes limit) :prefix "#error " :face ::print/throwable)
      :else
      {:kind :scalar :node {::print/face ::print/object
                            ::print/class (clojure.lang.Compiler/demunge (.getName c))}})))

(defn- leaf-width
  "The flat width of a leaf print node, as the emitter prints it at `depth`:
  declared AI text below the root prints as a string literal."
  {:malli/schema [:=> [:cat :map :int] :int]}
  [node depth]
  (cond
    (::print/text node) (count (::print/text node))
    (= ::print/var (::print/face node)) (+ 2 (count (::print/name node)))
    (= ::print/class (::print/face node)) (count (::print/name node))
    (= ::print/object (::print/face node)) (+ 9 (count (::print/class node)))
    (and (pos? depth) (= ::print/projected (::print/face node))
         (= :seon.render/ai (:seon.render/output node)))
    (count (pr-str (::print/value node)))
    :else (count (print/emit-text node {::print/width 0 ::print/length nil ::print/level nil
                                        ::print/namespace-maps? false}))))

(defn keyword-entry
  "The value at keyword `k` in an exact persistent array or hash map of at
  most 64 entries, found by comparing keys with `identical?`; nil otherwise.
  Never calls `get`, a comparator or a host Map on an unclassified value."
  {:malli/schema [:=> [:cat :seon.schema/value :keyword] :seon.schema/value]}
  [value k]
  (when (and (#{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap} (class value))
             (<= (count value) sorted-bound))
    (some (fn [entry] (when (identical? (key entry) k) (val entry))) value)))

(defn- cut-text
  {:malli/schema [:=> [:cat :map] :string]}
  [node]
  (case (:kind node)
    (:string :name) (str "…+" (- (:total node) (:shown node)))
    (if (:pending? node) "…?" (str "…" (- (:total node) (:base node 0) (:n node))))))

(defn- more?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [node]
  (case (:kind node)
    (:string :name) (< (:shown node) (:total node))
    (:map :coll) (boolean (or (:pending? node) (seq (:pending node))))
    false))

(defn- flat-width
  "A node's flat width from its own fields and its children's widths."
  {:malli/schema [:=> [:cat :map] :int]}
  [node]
  (case (:kind node)
    :leaf (:width node)
    :string (+ 2 (:shown-width node) (if (more? node) (count (cut-text node)) 0))
    :name (+ (:shown-width node) (if (more? node) (count (cut-text node)) 0))
    (:map :coll) (let [lines (+ (:n node) (if (more? node) 1 0))]
                   (+ (count (str (:prefix node) (:open node) (:close node))) (:sum node)
                      (* (if (= :map (:kind node)) 2 1) (max 0 (dec lines)))
                      (if (more? node) (count (cut-text node)) 0)))
    :entry (+ (:sum node) 1)))

(defn- nest
  {:malli/schema [:=> [:cat :map] :int]}
  [node]
  (if (< (:depth node) nest-cap) (if (= ::print/set (:face node)) 2 1) 0))

(defn- extras
  "Width fipp's line breaks add to a broken node."
  {:malli/schema [:=> [:cat :map] :int]}
  [node]
  (if-not (:broken? node)
    0
    (case (:kind node)
      (:map :coll) (* (+ (:indent node) (nest node)) (max 0 (dec (+ (:n node) (if (more? node) 1 0)))))
      :entry (+ (:indent node) (if (< (:depth node) nest-cap) 2 0))
      0)))

(defn- start-column
  {:malli/schema [:=> [:cat :map :map] :int]}
  [nodes node]
  (let [parent (get nodes (:parent node))]
    (+ (count (:outside node ""))
       (if (and (= :entry (:kind parent)) (= (:id node) (peek (:kids parent))))
         (+ (:indent parent) (if (< (:depth parent) nest-cap) 2 0))
         (:indent node)))))

(defn- breaks?
  "fipp's fits test: a group breaks when its parent broke (or it is the root)
  and its flat width exceeds the width left at its start column."
  {:malli/schema [:=> [:cat :map :map :int] :boolean]}
  [nodes node width]
  (and (pos? width)
       (contains? #{:map :coll :entry} (:kind node))
       (let [parent (get nodes (:parent node))]
         (and (or (nil? parent) (boolean (:broken? parent)))
              (> (:flat node) (- width (start-column nodes node)))))))

(defn- chain
  {:malli/schema [:=> [:cat :map :int] [:vector :int]]}
  [nodes id]
  (loop [id id out []]
    (if (nil? id) out (recur (:parent (get nodes id)) (conj out id)))))

(defn- settle
  "Re-derive flat widths along `id`'s chain to the root (each parent's child
  sum moved by its child's delta), then broken flags top-down, a newly broken
  node cascading into its children. `before` is the state preceding the change."
  {:malli/schema [:=> [:cat :map :map :int :int] :map]}
  [before state id width]
  (let [path (chain (:nodes state) id)
        state (first
               (reduce (fn [[state delta] id]
                         (let [node (get-in state [:nodes id])
                               old (get-in before [:nodes id])
                               node (assoc node :sum (if old
                                                       (+ (:sum old) delta)
                                                       (transduce (map #(get-in state [:nodes % :flat])) + 0 (:kids node))))
                               node (assoc node :flat (flat-width node))]
                           [(-> state
                                (assoc-in [:nodes id] node)
                                (update :extras + (- (extras node) (if old (extras old) 0))))
                            (- (:flat node) (if old (:flat old) 0))]))
                       [state 0] path))
        ;; Top-down along the chain; a newly broken node pushes its children,
        ;; each node breaking at most once. An explicit stack, never the call stack.
        flip (fn [state id]
               (loop [state state pending (list id)]
                 (if-let [id (first pending)]
                   (let [node (get-in state [:nodes id])]
                     (if (and (not (:broken? node)) (breaks? (:nodes state) node width))
                       (let [broken (assoc node :broken? true)]
                         (recur (-> state (assoc-in [:nodes id] broken) (update :extras + (extras broken)))
                                (into (rest pending) (:kids broken))))
                       (recur state (rest pending))))
                   state)))]
    (reduce flip state (rseq path))))

(defn- settle-in
  "`settle` with the changed state last, for threading."
  {:malli/schema [:=> [:cat :map :int :int :map] :map]}
  [before id width state]
  (settle before state id width))

(defn- declared
  "What the render pairs say about a reached map (design §2): nil, the
  producer's print node `{::print/node n}`, or a value to select in its
  place `{:value v}` (its identity projection, or a producer's refusal)."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.schema/value :seon.render.data/path :seon.render/output] [:maybe :map]]}
  [unit value path output]
  (when (and (map? value) (:seon.sci.eval/ctx unit)
             (not (get-in unit [:seon.render.value/options :seon.render.value/structural?])))
    (let [projection (or (:seon.schema/projection unit)
                         (:seon.schema/projection (meta (:seon.db/db unit)))
                         (:seon.schema/projection (env/of (:seon.sci.eval/ctx unit))))
          identity (when projection (schema/identity-only-projection-in projection value))
          value (if identity (:seon.schema/identity-value identity) value)
          projector (when identity
                      (let [form (get (:seon.schema.projection/forms projection) (:seon.schema/key identity))]
                        (when (and (vector? form) (map? (second form)))
                          (:seon.schema/identity-projection (second form)))))
          answer (try
                   (@render-declared-projection
                    (cond-> (assoc unit :seon.render.data/path path)
                      projector (assoc :seon.render/value value :seon.render.call/function projector))
                    value output)
                   (catch Throwable failure
                     {::print/node {::print/face ::print/string ::print/value (floor failure)}}))]
      (cond
        (::print/node answer) answer
        (:seon.error/value answer) {:value (:seon.error/value answer)}
        identity {:value value}))))

(defn- make-node
  "A node for `value` reached at `path`, classified, with its flat width."
  {:malli/schema [:=> [:cat :map :seon.schema/value :int :int :seon.render.data/path [:maybe :int] :map] :map]}
  [context value id depth path parent position]
  (let [{:keys [unit output ceiling identity-attributes]} context
        answer (declared unit value path output)
        declared (::print/node answer)
        value (if (contains? answer :value) (:value answer) value)
        base {:id id :depth depth :path path :parent parent :indent (:indent position 0)
              :outside (:outside position) :kids [] :n 0 :sum 0}
        node (if declared
               (let [text (when (and (= ::print/projected (::print/face declared))
                                     (= :seon.render/ai (:seon.render/output declared)))
                            (::print/value declared))
                     declared (if (and text (> (count text) ceiling))
                                (print/elision {::print/omitted (- (count text) ceiling)
                                                ::print/elision-unit :characters
                                                ::print/prefix (subs text 0 ceiling)
                                                ::print/bound-by ::print/rendered-token-ceiling
                                                :seon.render.data/path path
                                                :seon.render.data/next-offset ceiling
                                                :seon.render.data/total (count text)
                                                ::print/requery-refusal "renderer text beyond its ceiling"})
                                declared)]
                 (assoc base :kind :leaf :node declared :rendered? true))
               (let [children (:max-children context)
                     ;; A root continuation pages from its absolute offset.
                     limit (if (= Long/MAX_VALUE children) children (+ (:offset context 0) children))
                     c (classify value identity-attributes limit)]
                 (case (:kind c)
                   :scalar (assoc base :kind :leaf :node (:node c))
                   :name (if (<= (count (:text c)) scalar-bound)
                           (assoc base :kind :leaf :node (assoc (:node c) ::print/text (:text c)))
                           (assoc base :kind :name :node (:node c) :text (:text c) :total (count (:text c))
                                  :shown chunk-width :shown-width chunk-width))
                   :string (let [shown (min chunk-width (:total c) (:max-string context))]
                             (assoc base :kind :string :value (:value c) :total (:total c)
                                    :shown shown :shown-width (escaped-width (:value c) 0 shown)))
                   (merge base (select-keys c [:kind :face :open :close :total :pending? :prefix :ordered?])
                          {:pending (:members c) :outside (:prefix c)}))))
        node (if (= :leaf (:kind node)) (assoc node :width (leaf-width (:node node) depth)) node)]
    (assoc node :flat (flat-width node))))

(defn- charge
  {:malli/schema [:=> [:cat :map] :int]}
  [state]
  (+ (:reserve state) (get-in state [:nodes 0 :flat]) (:extras state) (- (:rendered state))))

(defn- push-work
  {:malli/schema [:=> [:cat :map :int :int :int :map] :map]}
  [state shell depth ordinal work]
  (let [key [shell depth ordinal (:seq state)]]
    (-> state (update :seq inc) (update :queue assoc key work))))

(defn- schedule
  "Push a node's first refinement: its next chunk, or its first member."
  {:malli/schema [:=> [:cat :map :map :int :map] :map]}
  [state node shell context]
  (case (:kind node)
    (:string :name) (cond-> state (more? node)
                      (push-work (max shell 2) (:depth node) 2 {:op :chunk :id (:id node) :k 2 :shell shell}))
    (:map :coll) (cond-> state
                   (and (seq (:pending node)) (< (:depth node) (:max-depth context)))
                   (push-work (max shell (inc (:depth node))) (inc (:depth node)) 1
                              {:op :member :parent (:id node) :ord 1 :shell shell}))
    state))

(defn- admit-chunk
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [state work context]
  (let [node (get-in state [:nodes (:id work)])
        to (min (:total node) (+ (:shown node) chunk-width) (:max-string context))
        text (if (= :name (:kind node)) (:text node) (:value node))
        grown (-> node (assoc :shown to)
                  (update :shown-width + (if (= :name (:kind node)) (- to (:shown node))
                                             (escaped-width text (:shown node) to))))
        next (cond->> (assoc-in state [:nodes (:id node)] grown)
               (:charge? context) (settle-in state (:id node) (:width context)))]
    (when (and (> to (:shown node))
               (or (not (:charge? context)) (<= (charge next) (:budget context))))
      (cond-> next
        (< to (:total node))
        (push-work (max (:shell work) (inc (:k work))) (:depth node) (inc (:k work))
                   (update work :k inc))))))

(defn- admit-member
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [state work context]
  (when (<= (:ord work) (:max-children context))
  (let [parent (get-in state [:nodes (:parent work)])
        [member & more] (:pending parent)
        ordinal (:ord work)
        depth (inc (:depth parent))
        shell (max (:shell work) depth ordinal)
        indent (+ (:indent parent) (nest parent))
        next-id (:next-id state)
        index (+ (:base parent 0) (dec ordinal))
        [added kid]
        (if (= :map (:kind parent))
          (let [entry-id next-id
                k (make-node context (key member) (+ 1 next-id) depth (conj (:path parent) index) entry-id {:indent indent})
                v (make-node context (val member) (+ 2 next-id) depth (conj (:path parent) (key member)) entry-id
                             {:indent (+ indent (if (< (:depth parent) nest-cap) 2 0))})]
            [[{:kind :entry :id entry-id :depth (:depth parent) :indent indent :parent (:id parent)
               :kids [(:id k) (:id v)] :n 0 :sum 0} k v] entry-id])
          (let [c (make-node context member next-id depth
                             (conj (:path parent) (if (= ::print/set (:face parent)) member index))
                             (:id parent) {:indent indent})]
            [[c] next-id]))
        grown (-> (reduce #(assoc-in %1 [:nodes (:id %2)] %2) state added)
                  (assoc :next-id (+ next-id (count added)))
                  (update :rendered + (transduce (comp (filter :rendered?) (map :width)) + 0 added))
                  (update-in [:nodes (:id parent)] #(-> % (update :kids conj kid) (update :n inc) (assoc :pending more))))
        next (cond->> grown (:charge? context) (settle-in state kid (:width context)))]
    (when (or (not (:charge? context)) (<= (charge next) (:budget context)))
      (cond-> (reduce #(schedule %1 %2 shell context) next (remove #(= :entry (:kind %)) added))
        (and (seq more) (< ordinal (:max-children context)))
        (push-work (max (:shell work) depth (inc ordinal)) depth (inc ordinal) (update work :ord inc)))))))

(defn- select-nodes
  "Shell-order selection of `value` under the context's budget."
  {:malli/schema [:=> [:cat :seon.schema/value :map] :map]}
  [value context]
  (let [offset (:offset context 0)
        root (make-node context value 0 0 (:root-path context []) nil {:indent 0 :outside nil})
        ;; A continuation starts the root collection at its absolute offset
        ;; (design §3g); its members keep their absolute ordinals.
        root (cond-> root
               (and (pos? offset) (= :string (:kind root)))
               (as-> node (let [start (min offset (:total node))
                                shown (min chunk-width (- (:total node) start) (:max-string context))]
                            (assoc node :value (subs (:value node) start) :total (- (:total node) start)
                                   :shown shown :shown-width (escaped-width (subs (:value node) start) 0 shown)
                                   :base start)))
               (and (pos? offset) (#{:map :coll} (:kind root)))
               (-> (assoc :base offset) (update :pending #(seq (drop offset %))))
               true (as-> node (assoc node :flat (flat-width node))))
        state {:nodes {0 root} :queue (sorted-map) :seq 0 :next-id 1 :extras 0
               :rendered (if (:rendered? root) (:width root) 0) :reserve (:legend context)}
        state (settle {:nodes {}} state 0 (:width context))]
    (loop [state (schedule state root 0 context)]
      (if-let [[key work] (first (:queue state))]
        (let [state (update state :queue dissoc key)
              admitted (case (:op work)
                         :chunk (admit-chunk state work context)
                         :member (admit-member state work context))]
          (recur (or admitted state)))
        state))))

(defn- cut-node
  "The one tail cut of a node that shows less than it holds."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [node context]
  (let [bound (cond (and (= :string (:kind node)) (>= (:shown node) (:max-string context)))
                    :seon.render.profile/max-string-length
                    (#{:string :name} (:kind node)) :seon.render.profile/token-budget
                    (>= (:depth node) (:max-depth context)) :seon.render.profile/max-depth
                    (>= (:n node) (:max-children context)) :seon.render.profile/max-children
                    :else :seon.render.profile/token-budget)
        request (cond-> (merge (:requery context)
                               {:seon.render.profile/id (:seon.render.profile/id (:profile context))
                                :seon.render.data/path (:path node)})
                  (not (:pending? node))
                  (assoc ::print/bound-by (if (= bound :seon.render.profile/token-budget)
                                            (or (::print/bound-by (:profile context)) bound)
                                            bound)))]
    (print/elision
     (case (:kind node)
       (:string :name) (assoc request ::print/omitted (- (:total node) (:shown node))
                              ::print/elision-unit :characters
                              ::print/prefix (subs (if (= :name (:kind node)) (:text node) (:value node)) 0 (:shown node))
                              :seon.render.data/next-offset (+ (:base node 0) (:shown node))
                              :seon.render.data/total (+ (:base node 0) (:total node)))
       (if (:pending? node)
         (assoc request ::print/elision-unit :pending :seon.render.data/next-offset (+ (:base node 0) (:n node)))
         (assoc request ::print/omitted (- (:total node) (:base node 0) (:n node)) ::print/elision-unit :children
                :seon.render.data/next-offset (+ (:base node 0) (:n node)) :seon.render.data/total (:total node)))))))

(defn- tree
  "The selected nodes as one print-node tree, built with an explicit stack."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [nodes context]
  (loop [stack (list [:enter 0]) built {}]
    (if-let [[op id] (first stack)]
      (let [node (get nodes id)]
        (if (= op :enter)
          (recur (into (conj (rest stack) [:exit id]) (map (fn [kid] [:enter kid])) (:kids node)) built)
          (let [kids (mapv built (:kids node))
                cut (when (more? node) (cut-node node context))
                result
                (case (:kind node)
                  :leaf (:node node)
                  :entry kids
                  (:string :name) (if cut
                                    (if (= :name (:kind node)) (assoc (:node node) ::print/text (str (::print/prefix cut) "…+" (::print/omitted cut))) cut)
                                    (if (= :name (:kind node)) (assoc (:node node) ::print/text (:text node))
                                        {::print/face ::print/string ::print/value (:value node)}))
                  (:map :coll)
                  (let [children (cond-> kids cut (conj cut))
                        collection (cond-> {::print/face (if (= ::print/throwable (:face node)) ::print/map (:face node))}
                                     (= :map (:kind node)) (assoc ::print/entries children)
                                     (= :coll (:kind node)) (assoc ::print/items children)
                                     (:ordered? node) (assoc ::print/ordered? true)
                                     (:total node) (assoc :seon.render.data/total (:total node)))]
                    (if (= ::print/throwable (:face node))
                      {::print/face ::print/throwable ::print/value collection}
                      collection)))
                result (cond-> result (and (map? result) (not= :entry (:kind node)))
                         (assoc :seon.render.data/path (:path node)))]
            (recur (rest stack) (assoc built id result)))))
      (get built 0))))

(defn- breadcrumbs
  [unit path]
  (when (:seon.render.value/route-base unit)
    [:nav {:class "seon-data-crumbs"}
     (path-link unit [] 0 "root" "seon-data-crumb")
     (map (fn [index]
            (path-link unit (subvec path 0 (inc index)) 0
                       (pr-str (nth path index)) "seon-data-crumb"))
          (range (count path)))]))

(defn- legend-text
  "The one line that follows a cut text: what `…N` means and how to read on."
  {:malli/schema [:=> [:cat :map] :string]}
  [profile]
  (let [source (:seon.print/requery-id profile)
        form (when (some? source)
               (:seon.print/requery-form (print/elision {:seon.print/requery-id source
                                                         :seon.render.data/path []})))]
    (str ";; …N = N more, …+N = N more characters, …? = not yet realized. "
         (cond
           (qualified-symbol? source) (str "Read any part with (get-in " source " [...]).")
           form (str "Read any part with (seon.print/value-at " (pr-str (second form)) " [...]).")
           :else (str "Not retrievable: " (or (:seon.print/requery-refusal profile) "no source identity") ".")))))

(defn prepare
  "Select the value under its render profile (design one-render-path §3) and
  emit the one tree as laid-out AI text and structural HTML."
  {:malli/schema
   [:function
    [:=> [:cat :seon.render/unit]
     [:or :nil :seon.render.value/projection :seon.render.value/missing-root-identity-error]]
    [:=> [:cat :seon.render/unit :seon.render/output]
     [:or :nil :seon.render.value/projection :seon.render.value/missing-root-identity-error]]]}
  ([unit]
   (prepare unit :seon.render/ai))
  ([unit output]
   (let [path (vec (get-in unit [:seon.render.data/cursor
                                :seon.render.data/path] []))
         id (node-id unit path)]
     (if (:seon.render.value/root-description id)
       id
       (let [profile (render-profile unit)
             ai? (= output :seon.render/ai)
             options (cond-> (assoc (print-options unit)
                                    :seon.print/length nil
                                    :seon.print/level nil)
                       (= :single-line (:seon.render.profile/composition profile))
                       (assoc :seon.print/width 0))
             legend (legend-text profile)
             database (:seon.db/db unit)
             context {:unit unit :output output :profile profile
                      :requery (select-keys profile [:seon.print/requery-id :seon.print/requery-refusal])
                      :identity-attributes (if database (set (db/identity-attributes database)) #{})
                      :width (if ai? (:seon.print/width options) 0)
                      :charge? ai?
                      :budget (if ai? (tokens/estimate-chars (:seon.render.profile/token-budget profile)) Long/MAX_VALUE)
                      :legend (if ai? (inc (count legend)) 0)
                      :max-children (if ai? (:seon.render.profile/max-children profile) Long/MAX_VALUE)
                      :max-depth (if ai? (:seon.render.profile/max-depth profile) Long/MAX_VALUE)
                      :max-string (or (when ai? (:seon.render.profile/max-string-length profile)) Long/MAX_VALUE)
                      :offset (max 0 (long (get-in unit [:seon.render.data/cursor :seon.render.data/offset] 0)))
                      :root-path path
                      :ceiling (tokens/estimate-chars (or (:seon.print/rendered-token-ceiling profile)
                                                          (:seon.print/rendered-token-ceiling (print/default-options))))}
             state (select-nodes (:seon.render/value unit) context)
             cut? (boolean (some more? (vals (:nodes state))))
             selected (tree (:nodes state) context)
             emitted (print/emit-both selected options)
             text (cond-> (:seon.print/text emitted) (and ai? cut?) (str "\n" legend))
             limit (- (charge state) (if cut? 0 (:legend context)))]
         ;; The charge is the laid-out length (design §3d); a longer text is
         ;; the allocator's defect, never a second attempt. Declared renderer
         ;; text lays out on its own terms and is exempt.
         (when (and ai? (zero? (:rendered state)) (> (count text) limit))
           (let [refusal (refusal/diagnostic (java.util.Date.) :seon.render.value/select 'seon.render.value/prepare
                          {:seon.error/message "The floor's saved text exceeded its charge."
                           :seon.error/expected "text length at most the charge"
                           :seon.error/offending (count text)
                           :seon.error/data {:seon.print/charge limit :seon.render.data/path path}})]
             (throw (ex-info (:seon.error/message refusal) refusal))))
         (cond-> {:seon.render.value/tree selected
                  :seon.render.value/options options
                  :seon.render.value/truncated? (boolean (or cut? (pos? (:offset context))))
                  :seon.render.value/text text
                  :seon.render.value/html
                  [:div {:id id :class "seon-data-panel"}
                   (breadcrumbs unit path)
                   (:seon.print/hiccup emitted)]}
           (:seon.render.call/selected-producer selected)
           (assoc :seon.render.call/selected-producer
                  (:seon.render.call/selected-producer selected))))))))

(defn render-ai-data
  "Return the text sink result from one already prepared projection."
  {:malli/schema [:=> [:cat :seon.render.value/projection] :string]}
  [projection]
  (:seon.render.value/text projection))

(defn render-html-data
  "Return the hiccup sink result from one already prepared projection."
  {:malli/schema [:=> [:cat :seon.render.value/projection]
                  :seon.render/hiccup]}
  [projection]
  (:seon.render.value/html projection))

(defn artifact
  "Select the one durable value artifact from an admission result.

  The print node is the sole value source. Semantic data and printable EDN
  are derived when read and are never stored beside it.

  An admission that stored NOTHING still has to answer, and the answer is the
  reason: the missing marker is re-admitted as itself — a handful of bytes —
  so every reader downstream gets a real print node saying
  `#:seon.eval{:missing :over-bound, :size N}` instead of an empty artifact
  that would render as though the value were nothing."
  {:malli/schema [:=> [:cat :map] :seon.render.value/artifact]}
  [admitted]
  (if (:seon.sci.admit/reason admitted)
    (cond-> {:seon.sci.admit/print-node
             (:seon.sci.admit/print-node
              (admit/admit-value
               {:seon.sci.admit/value
                (select-keys admitted [:seon.sci.admit/reason :seon.sci.admit/bytes])
                :seon.sci.admit/interrupt-fn (fn [])
                :seon.sci.admit/caps {}
                :seon.sci.admit/unbounded? true
                :seon.config/on-core-error :record}))}
      (:seon.sci.admit/record admitted)
      (assoc :seon.sci.admit/record (:seon.sci.admit/record admitted)))
    (select-keys admitted
                 [:seon.sci.admit/print-node
                  :seon.sci.admit/record])))

(defn artifact-edn
  "Serialize one value artifact with canonical print bindings."
  {:malli/schema [:=> [:cat :seon.render.value/artifact] :string]}
  [stored]
  (admit/canonical-edn stored))

(defn read-artifact
  "Read one stored value artifact."
  {:malli/schema [:=> [:cat :string] :seon.render.value/artifact]}
  [content]
  (edn/read-string content))

(defn artifact-value
  "Derive semantic drill data from an artifact's sole print node."
  {:malli/schema [:=> [:cat :seon.render.value/artifact] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [stored]
  (admit/semantic-value (:seon.sci.admit/print-node stored)))

(defn- render-prepared
  [unit output]
  (let [projection (prepare unit output)]
    (if (:seon.render.value/root-description projection)
      projection
      (if (= output :seon.render/html)
        (render-html-data projection)
        (render-ai-data projection)))))

(defn render-ai
  "Render any floor unit through the admitted text sink."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :string :seon.render.value/missing-root-identity-error]]}
  [unit]
  (render-prepared unit :seon.render/ai))

(defn render-html
  "Render any floor unit through the admitted hiccup sink."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.render.value/missing-root-identity-error]]}
  [unit]
  (render-prepared unit :seon.render/html))
