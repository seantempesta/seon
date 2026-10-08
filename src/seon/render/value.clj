(ns seon.render.value
  "The value printer: the last stage every shown value passes through, render
  output included, as AI text and as HTML from one selection.

  ## Rules

  General rules only; a value they print badly needs a render function, never
  a rule here.

  1. One budget. The profile's token budget bounds the laid-out text, render
     output included; nothing else clips.
  2. Breadth before depth, earlier first. Every member of one level is
     admitted before any member of the next, so a map of at most 64 keys
     shows all its keys before a value expands; a sequence's, a set's or a
     larger map's members sit one level deeper per doubling of their
     position, so the first ones show whole and the tail is cut with its
     count rather than every member starved. The budget alone bounds how
     many members show: there is no member count cap.
  3. Collapsed, then expanded. A collection not yet expanded shows its
     brackets, its count and a dig form; a nested entity shows its identity
     first.
  4. Identity first, short before long. In a keyword-keyed map of at most 64
     keys, installed `:db.unique/identity` attributes lead, then scalars and
     references, then long strings, then collections.
  5. Long strings show their head: 32 characters, then doubling chunks, one
     per level; a cut shows the characters left and the `subs` form. A
     keyword or symbol is one name, whole up to 256 characters.
  6. Every cut says what it cut: the count or characters omitted, its path,
     the bound that cut it, and a dig form on the result handle
     (`(drop 32 (get-in result/e… [:k]))`) that reads the rest.
  7. Laziness within budget. A lazy or infinite seq is realized only as far
     as it prints; an unknown tail is `…?`.
  8. Host objects are class and identity (`#object[Class 0x…]`), never their
     contents: live, not data a later evaluation reads back.
  9. Cycles and depth are safe: host objects are opaque, the walk uses an
     explicit stack, and `:max-depth` bounds it.
  10. No re-dispatch. The root is offered to its render once; every value
      beneath prints as data. A quoted form prints as `'x`, as typed.

  ## Recommendations

  - `text` and `html` are the total entries for a unit; `core-text` and
    `core-html` print with no render, database or config, for the edges where
    those may be what broke."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [seon.id :as id]
            [seon.db :as db]
            [seon.print :as print]
            [seon.schema.edn :as schema.edn]
            [seon.schema :as schema]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.sci.admit :as admit]
            [seon.ai.tokens :as tokens]
            [seon.sci.reader :as reader]
            [sci.lang]
            [sci.impl.records]))

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
(defonce ^:private render-viewer
  (delay (requiring-resolve 'seon.render/viewer)))
(defonce ^:private walk-with-related
  (delay (requiring-resolve 'seon.render.walk/with-related)))


;;; THE CORE PRINTER (root ruling 2026-10-07, one printer): printing data
;;; under one fixed budget, with no render selection, database or config, so
;;; the edges where those may be what failed (an unrecorded fault, a page's
;;; emergency body, a printer that threw) still show the value. Its last
;;; resort is the projection-free text below: clojure.core data walked to a
;;; few members and levels, host objects as class and identity, a Throwable
;;; as its cause chain with each link's nearest non-host frames.

(def ^:private text-limits
  {:chars 400 :items 8 :depth 3 :entries 12 :links 8 :frames 10 :suppressed 3 :throwable-chars 8192})

(defn- datum
  "`x` as clojure.core data at most `level` collections deep (rules 1–3):
  `...` marks a cut collection or its omitted members; a string past `limit`
  keeps its head."
  {:malli/schema [:=> [:cat :seon.schema/value :int :int] :seon.schema/value]}
  [x level limit]
  (let [items (:items text-limits)]
    (cond
      (string? x) (if (> (count x) limit) (str (subs x 0 limit) "…") x)
      (or (nil? x) (boolean? x) (number? x) (char? x) (ident? x) (var? x)
          (uuid? x) (instance? java.util.Date x)) x
      (not (and (coll? x) (str/starts-with? (.getName (class x)) "clojure.lang.")))
      (symbol (str "#object[" (.getName (class x)) " 0x" (Integer/toHexString (System/identityHashCode x)) "]"))
      (zero? level) '...
      :else (let [walk #(datum % (dec level) limit)
                  members (cond-> (mapv (if (map? x) (fn [[k v]] [(walk k) (walk v)]) walk) (take items x))
                            (> (bounded-count (inc items) x) items) (conj (if (map? x) ['... '...] '...)))]
              (cond (map? x) (into {} members) (vector? x) members
                    (set? x) (set members) :else (apply list members))))))

(defn- bounded-text
  "`value` as at most `limit` characters (default 400).

  ## Edge cases

  - A 200-deep map prints as `{:k 199, :v {:k 198, :v {:k 197, :v ...}}}`.
  - A host object prints as `#object[Class 0xhash]`, never through its
    `toString`, print-method or `deref`."
  {:malli/schema [:function [:=> [:cat :seon.schema/value] :string]
                  [:=> [:cat :seon.schema/value [:int {:min 1}]] :string]]}
  ([value] (bounded-text value (:chars text-limits)))
  ([value limit]
   (try
     (let [text (binding [*print-length* nil *print-level* nil *print-meta* false *print-readably* true
                          *print-dup* false *print-namespace-maps* false]
                  (pr-str (datum value (:depth text-limits) limit)))]
       (if (> (count text) limit) (str (subs text 0 limit) "…") text))
     (catch Throwable failure
       (str "#unprintable[" (.getName (class value)) " " (.getName (class failure)) "]")))))

(defn- causes
  "`throwable` and its causes, outermost first, each once, at most `:links`."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :seon.error/throwable]]}
  [throwable]
  (loop [link throwable seen []]
    (if (or (nil? link) (= (:links text-limits) (count seen)) (some #(identical? link %) seen))
      seen
      ;; a `getCause` that throws ends the chain at that link
      (recur (try (.getCause ^Throwable link) (catch Throwable _ nil)) (conj seen link)))))

(defn- host-class?
  "Whether a frame's class is the JVM's or Clojure's own runtime (`java.*`, `jdk.*`,
  `clojure.lang.*`...): where a failure passed through, never code an agent
  wrote. Dependencies and Seon are not host frames: which of those matter is
  the error render's to say."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String class-name]
  (boolean (some #(.startsWith class-name ^String %) ["java." "javax." "jdk." "sun." "clojure.lang."])))

(defn- link-lines
  "One link's lines: class and message, ex-data entries, the nearest non-host
  frames of the throw and suppressed throwables, each list counting what it leaves out; a
  link whose accessors throw prints its class and the failure's."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :string]]}
  [^Throwable link]
  (let [{:keys [entries frames suppressed]} text-limits]
    (try
      (let [trace (into [] (remove #(host-class? (.getClassName ^StackTraceElement %))) (.getStackTrace link))]
        (into [(str (.getName (class link)) ": " (bounded-text (ex-message link)))]
              (concat
               (for [[k v] (take entries (ex-data link))] (str "  " (bounded-text k) " " (bounded-text v)))
               (when (> (bounded-count (inc entries) (ex-data link)) entries) ["  … further ex-data entries"])
               (for [^StackTraceElement f (take frames trace)]
                 (str "  at " (main/demunge (.getClassName f)) " (" (.getFileName f) ":" (.getLineNumber f) ")"))
               (when (> (count trace) frames)
                 [(str "  … " (- (count trace) frames) " further frames")])
               (for [^Throwable other (take suppressed (.getSuppressed link))]
                 (str "  suppressed " (.getName (class other)) ": " (bounded-text (ex-message other)))))))
      (catch Throwable failure
        [(str (.getName (class link)) " #unprintable[" (.getName (class failure)) "]")]))))

(defn- throwable-text
  "An unhandled Throwable as bounded text from the Throwable alone: one block
  of lines per cause, each link walked once by identity, so a cyclic chain
  terminates. Which frames matter is the error render's to say.

  ## Costs

  - A 13-link `ex-info` chain with a 50-item payload per link (714 characters
    out): 783 µs unarmed, 977 µs armed (default, 2026-09-26)."
  {:malli/schema [:=> [:cat :seon.error/throwable] :string]}
  [throwable]
  (let [chain (causes throwable)
        text (str/join "\n" (concat (mapcat (fn [position link]
                                              (cond-> (link-lines link) (pos? position) (update 0 #(str "caused by " %))))
                                            (range) chain)
                                    (when (and (= (:links text-limits) (count chain)) (ex-cause (peek chain)))
                                      ["… further causes"])))]
    (if (> (count text) (:throwable-chars text-limits))
      (str (subs text 0 (:throwable-chars text-limits)) "…")
      text)))

(def ^:private core-profile
  ;; The core's fixed budget: no config is read, so a broken configuration
  ;; cannot stop it. Its cuts name no handle: the value has none.
  {:seon.render.profile/id :seon.render.profile/core
   :seon.render.profile/token-budget 1000
   :seon.render.profile/max-depth 8
   :seon.render.profile/composition :multiline
   :seon.print/requery-refusal "printed by the core printer: the value has no result handle"})

(declare render-ai render-html project compact-failure)

(defn- opaque
  "How the core names a value nothing else could print: its class and
  identity, read without calling any of its methods."
  {:malli/schema [:=> [:cat :seon.schema/value] :string]}
  [value]
  (str "#object[" (.getName (class value)) " 0x" (Integer/toHexString (System/identityHashCode value)) "]"))

(defn- shown-chain
  "`throwable` as the core shows it: per link (at most `:links`, each once)
  its class, message, ex-data as held (never entered: the selection bounds
  what is shown) and nearest non-host frames (at most `:frames`, each
  `[class method file line]` as `seon.error.refusal` holds frames). A link
  whose accessors throw is its class and the accessor's failure."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :map]]}
  [throwable]
  (mapv (fn [^Throwable link]
          (try
            (let [message (ex-message link) data (ex-data link)
                  frames (into [] (comp (remove #(host-class? (.getClassName ^StackTraceElement %)))
                                        (take (:frames text-limits))
                                        (map (fn [^StackTraceElement f]
                                               [(symbol (.getClassName f)) (symbol (.getMethodName f))
                                                (.getFileName f) (.getLineNumber f)])))
                               (.getStackTrace link))]
              (cond-> {:seon.error/throwable-class (.getName (class link))}
                message (assoc :seon.error/message message)
                data (assoc :seon.error/data data)
                (seq frames) (assoc :seon.error/frames frames)))
            (catch Throwable failure
              {:seon.error/throwable-class (.getName (class link))
               :seon.error/message (str "#unprintable[" (.getName (class failure)) "]")})))
        (causes throwable)))

(defn core-text
  "`value` printed by the value printer's core: its structural selection under
  a fixed budget, less `reserve` characters another part of the same unit
  already spent, with no render, database or config. A Throwable is shown as
  its cause chain (`shown-chain`). Total: if the selection itself fails, the
  projection-free text of the value, and if that fails, its class and
  identity.

  ## Edge cases

  - The value's own `seon.print/full` opt-in is not honored: the core's
    budget is fixed.
  - A Throwable whose `getCause`, `getMessage` or `getStackTrace` throws
    prints its class and the failure, never the throw."
  {:malli/schema [:function [:=> [:cat :seon.schema/value] :string]
                  [:=> [:cat :seon.schema/value :int] :string]]}
  ([value] (core-text value 0))
  ([value reserve]
  (let [throwable? (instance? Throwable value)
        last-resort #(try (if throwable? (throwable-text value) (bounded-text value))
                          (catch Throwable _ (opaque value)))]
    (try
      (let [shown (cond throwable? (shown-chain value)
                        (and (instance? clojure.lang.IObj value) (::print/full (meta value)))
                        (vary-meta value dissoc ::print/full)
                        :else value)
            text (:seon.render.value/text
                  (project {:seon.render/value shown
                            :seon.render.value/root 'seon.render.value/core
                            :seon.render/profile core-profile
                            :seon.render.value/options {:seon.render.value/structural? true}}
                           :seon.render/ai reserve))]
        (if (string? text) text (last-resort)))
      (catch Throwable _ (last-resort))))))

(defn core-html
  "`value` printed by the core (`core-text`) as preformatted hiccup."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.render/hiccup]}
  [value]
  [:pre {:class "seon-data-panel seon-core-value"} (core-text value)])

(defn- diagnostics-shown
  "The core's text of `entries` (`:seon.render.value/diagnostic`s), shown
  beside the value they did not replace, and the same text as HTML in the
  placeholder `seon.render.lint` counts."
  {:malli/schema [:=> [:cat :seon.render.value/diagnostics] [:map [::text :string] [::html :seon.render/hiccup]]]}
  [entries]
  (let [shown (core-text {:seon.render.value/diagnostics entries})]
    {::text shown
     ::html [:div {:class "seon-render-unavailable"} [:pre {:class "seon-data-panel seon-core-value"} shown]]}))

(defn- unavailable
  "The diagnostic beside a value whose unit the printer refused or threw on:
  the refusal or failure at the unit's cursor path, through the core."
  {:malli/schema [:=> [:cat :map [:or :seon.error/throwable :seon.error/base]] [:map [::text :string] [::html :seon.render/hiccup]]]}
  [unit refused]
  ;; The cursor may be what failed: only a held vector is a path.
  (let [path (get-in unit [:seon.render.data/cursor :seon.render.data/path])]
    (diagnostics-shown [{:seon.render.data/path (if (vector? path) path [])
                         :seon.render.value/failure (compact-failure refused)}])))

(defn text
  "The value printer's TOTAL text entry: `unit` through `render-ai`, or, when
  that refuses or throws, the core's text of the value plus its diagnostic. Always a string (AGENTS.md: a failed render falls back to the value
  itself, printed, with one diagnostic)."
  {:malli/schema [:=> [:cat :map] :string]}
  [unit]
  (let [shown (try (render-ai unit) (catch Throwable failure failure))]
    (if (string? shown)
      shown
      ;; One budget: the diagnostic's text is charged before the value's.
      (let [diagnostic (::text (unavailable unit shown))]
        (str (core-text (:seon.render/value unit) (inc (count diagnostic))) "\n" diagnostic)))))

(defn html
  "The value printer's TOTAL HTML entry: `unit` through `render-html`, or,
  when that refuses or throws, the core's HTML of the value plus one
  diagnostic. Always hiccup."
  {:malli/schema [:=> [:cat :map] :seon.render/hiccup]}
  [unit]
  (let [shown (try (render-html unit) (catch Throwable failure failure))]
    (if (vector? shown)
      shown
      [:div (core-html (:seon.render/value unit)) (::html (unavailable unit shown))])))

(defn transacted
  "Restore a pulled entity to its transaction shape.

  With a database value, installed Datahike value type and cardinality are the
  authority: refs become entity ids, cardinality-many values become sets (a
  vector stored in cells stays an ordered vector), and scalar EDN vectors
  remain vectors. The one-argument arity preserves the
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
                       (db/stored-collection database attribute (map ref-id value))
                       (ref-id value))

                     (= :db.cardinality/many cardinality)
                     (db/stored-collection database attribute value)

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

(defn keyword-lookup?
  "May `value` be asked for a keyword key?

  Only where the lookup can neither run foreign code nor refuse the keyword: an exact array or hash map (keys
  compared by hash and identity), or a tree map ordered by Clojure's default
  comparator whose keys are keywords (`Keyword/compareTo`). This row of the
  permitted-operation table is the one gate for a value's render attachment,
  identity and entity shape.

  ## Edge cases

  - False for a `java.util.HashMap`, a record, a symbol-keyed `sorted-map` or
    a custom comparator: such a map prints as the data it is, with no render
    attachment, identity or entity renderer.
  - An empty default-comparator `sorted-map` is true.

  ## Costs

  - 45 ns unarmed, 0.22 µs armed on `{:a 1}` (default, 2026-09-26)."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (let [c (class value)]
    (or (identical? c clojure.lang.PersistentArrayMap)
        (identical? c clojure.lang.PersistentHashMap)
        (and (identical? c clojure.lang.PersistentTreeMap)
             (identical? (.comparator ^clojure.lang.PersistentTreeMap value) clojure.lang.RT/DEFAULT_COMPARATOR)
             (or (zero? (count value))
                 (identical? (class (key (first value))) clojure.lang.Keyword))))))

(defn value-identity
  "`value`'s own address `[attribute value]`: its first installed
  `:db.unique/identity` attribute in `database`, for a map `keyword-lookup?`
  permits; else nil."
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] :seon.schema/value]
                  [:maybe [:tuple :qualified-keyword :seon.schema/value]]]}
  [database value]
  (when (and database (keyword-lookup? value))
    (some (fn [attribute]
            (when-some [entry (find value attribute)]
              [attribute (val entry)]))
          (db/identity-attributes database))))

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
  declared producer that delegates its own value to this value printer — `seon.ai/
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
    (value-identity database value)))

(defn node-id
  "Stable element id for one root selector and `get-in` path."
  {:malli/schema [:=> [:cat :seon.render.value/unit :seon.render.data/path]
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
  "The unit's route at `path` and `offset`, carrying the basis the value was
  derived at; with `text?`, the whole string at `path` as text/plain. Nil
  without a route base."
  {:malli/schema [:function [:=> [:cat :map :seon.render.data/path :int] [:maybe :string]]
                  [:=> [:cat :map :seon.render.data/path :int :boolean] [:maybe :string]]]}
  ([unit path offset] (path-url unit path offset false))
  ([unit path offset text?]
   (when-let [base (:seon.render.value/route-base unit)]
     (cond-> (str base (if (str/includes? base "?") "&" "?")
                  "path=" (encoded (pr-str path)) "&offset=" offset)
       (:seon.render.value/basis unit) (str "&basis=" (:seon.render.value/basis unit))
       text? (str "&text=1")))))

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

(defn- display-budget
  "The token budget a value prints under: `override` (the value's own
  `:seon.print/budget` metadata, else the session's `seon.print/*budget*`),
  never past the profile's ceiling, else the profile's budget."
  {:malli/schema [:=> [:cat :map [:maybe :int]] :int]}
  [profile override]
  (let [ceiling (:seon.render.profile/token-ceiling profile)]
    (if (pos-int? override)
      (cond-> override (int? ceiling) (min ceiling))
      (:seon.render.profile/token-budget profile))))

(defn- budget-override
  "The value's own `:seon.print/budget` metadata, else the session's."
  {:malli/schema [:=> [:cat :map :seon.schema/value] [:maybe :int]]}
  [unit value]
  (some #(when (pos-int? %) %)
        [(when (instance? clojure.lang.IMeta value) (::print/budget (meta value)))
         (get-in unit [:seon.print/options ::print/budget])]))

;;; THE VALUE PRINTER'S SELECTION (design one-render-path §3a–§3d). Every
;;; reached object is classified by its EXACT class into a row of permitted
;;; operations before anything else touches it; an unlisted class is opaque.
;;; Members are admitted level by level, breadth before depth: every member of
;;; one level (each collapsed to its count and dig form) before any member of
;;; the next, so a map shows all its keys before any value expands, and a long
;;; string grows one doubling chunk per level. The charge is the laid-out length itself:
;;; flat width plus the indentation of every group fipp will break (its own
;;; fits test, reference-code/fipp/src/fipp/engine.cljc:203-208), so the saved
;;; text is at most B. The queue and the charge are one value threaded through
;;; a loop; a refused admission keeps the previous value.

(def ^:private scalar-bound 32)
(def ^:private chunk-width 32)
;; A keyword or symbol is one name: whole up to this length.
(def ^:private name-bound 256)
(def ^:private nest-cap 6)
(def ^:private sorted-bound 64)
(def ^:private tuple-bound 8)

(defn- escaped-width
  "Characters the string literal spends on chars [from, to) of `s`; a newline
  stays a line break (`seon.print/string-literal`)."
  {:malli/schema [:=> [:cat :string :int :int] :int]}
  [^String s from to]
  (loop [i (long from) width 0]
    (if (< i to)
      (let [c (.charAt s i)]
        (recur (inc i) (+ width (if-let [e (when-not (= \newline c) (char-escape-string c))] (count e) 1))))
      width)))

(defn- shown-width
  "Characters chars [from, to) of string node `node` spend when shown: render
  text (`:raw?`) as written, any other string as its escaped literal."
  {:malli/schema [:=> [:cat :map :int :int] :int]}
  [node from to]
  (if (:raw? node) (- to from) (escaped-width (:value node) from to)))

(defn- integer-digits
  "An upper bound on a BigInteger's decimal spelling, from its bit length."
  {:malli/schema [:=> [:cat :seon.schema/value] :int]}
  [^java.math.BigInteger b]
  (+ 2 (long (Math/ceil (* (.bitLength b) (Math/log10 2))))))

(defn- throwable-safe?
  "True when a Throwable class runs only Throwable's own accessors.

  Asked per printed Throwable link, never memoized: four `getMethod` lookups
  (about 1 µs) are cheaper than a process holder keyed by `Class`, which pinned
  every Throwable class and its loader (task 6e5637a4afc8)."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [^Class c]
  (every? (fn [m] (= Throwable (.getDeclaringClass (.getMethod c ^String m (make-array Class 0)))))
          ["getMessage" "getCause" "getStackTrace" "getSuppressed"]))


(defn- throwable-value
  "A Throwable as plain data: type, message and ex-data per link, cycles
  bounded by identity. Frames are not acquired (design §3g)."
  {:malli/schema [:=> [:cat :seon.error/throwable] :map]}
  [^Throwable t]
  (loop [^Throwable link t seen [] via []]
    (cond
      (or (nil? link) (some #(identical? % link) seen)) {:via via}
      (not (throwable-safe? (class link))) {:via (conj via {:type (symbol (.getName (class link)))})}
      :else (recur (.getCause link) (conj seen link)
                   (conj via (cond-> {:type (symbol (.getName (class link)))}
                               (.getMessage link) (assoc :message (.getMessage link))
                               (identical? (class link) clojure.lang.ExceptionInfo)
                               (assoc :data (.getData ^clojure.lang.ExceptionInfo link))))))))

(defn- compact-failure
  "A presentation failure as the few facts that name it: the error's type,
  operation and message (from the error map, or the Throwable's ex-data when
  it carries one), else the Throwable's class and message. Never the ex-data
  as a whole: Malli problems and the refused value stay in the stored fault."
  {:malli/schema [:=> [:cat :seon.render.value/failure] :seon.render.value/failure-summary]}
  [failure]
  (let [data (if (instance? Throwable failure) (ex-data failure) failure)]
    (if (:seon.error/type data)
      (select-keys data [:seon.error/type :seon.error/operation :seon.error/message])
      (cond-> {:seon.error/throwable-class (.getName (class failure))}
        (ex-message failure) (assoc :seon.error/message (ex-message failure))))))

(defn- seq-cells
  "A seq's first `limit` members, stepped as Clojure's printer steps any
  ISeq (`first`, `next`), realizing a lazy seq as far as it prints and no
  further; cells past `limit` end the prefix as more."
  {:malli/schema [:=> [:cat :seon.schema/value :int] :map]}
  [x limit]
  (loop [cell x out (transient [])]
    (cond
      (or (nil? cell) (identical? (class cell) clojure.lang.PersistentList$EmptyList))
      {:members (persistent! out)}
      (>= (count out) limit) {:members (persistent! out) :more? true}
      (identical? (class cell) clojure.lang.LazySeq) (recur (.seq ^clojure.lang.LazySeq cell) out)
      ;; `.more`, not `.next`: the rest stays unrealized until it prints.
      (instance? clojure.lang.ISeq cell)
      (recur (.more ^clojure.lang.ISeq cell) (conj! out (.first ^clojure.lang.ISeq cell)))
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

(defn- member-rank
  "Where one keyword-keyed member sorts: installed identity attributes first;
  then a scalar, a short string or a pair of scalars (a reference) before a
  long string before a collection."
  {:malli/schema [:=> [:cat :seon.schema/value [:set :qualified-keyword] :keyword] [:vector :int]]}
  [member identity-attributes k]
  (let [v (when (map-entry? member) (val member))]
    [(if (contains? identity-attributes k) 0 1)
     (cond (and (coll? v) (counted? v) (<= (count v) 2) (not-any? coll? (if (map? v) (vals v) v))) 0
           (coll? v) 2
           (and (string? v) (> (count v) scalar-bound)) 1
           :else 0)]))

(defn- sorts?
  "True when `held-order` shows `x`'s members in an order of its own rather
  than its `seq` order: an exact map or hash set of at most 64 exact keywords."
  {:malli/schema [:=> [:cat :seon.schema/value [:fn clojure.core/ifn?]] :boolean]}
  [x key-of]
  (boolean
   (and (#{clojure.lang.PersistentHashMap clojure.lang.PersistentHashSet clojure.lang.PersistentArrayMap} (class x))
        (<= (count x) sorted-bound)
        (every? #(identical? (class (key-of %)) clojure.lang.Keyword) x))))

(defn- held-order
  "Members in held order, each `[index member]` with `index` its place in
  `seq`. An exact map or hash set of at most 64 exact keywords shows identity
  and short members first (`member-rank`), a hash map or set then alphabetically, an
  array map then in its own order (design §3b). A typed path step names the
  index, so a sorted display still addresses the held value."
  {:malli/schema [:=> [:cat :seon.schema/value [:set :qualified-keyword] [:fn clojure.core/ifn?]] :seon.schema/value]}
  [x identity-attributes key-of]
  (let [members (map-indexed vector x)
        c (class x)]
    (if (sorts? x key-of)
      (sort-by (fn [[index member]]
                 (let [k (key-of member)]
                   (into (member-rank member identity-attributes k)
                         (if (identical? c clojure.lang.PersistentArrayMap)
                           [index]
                           [(namespace k) (name k)]))))
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
      ;; A keyword key names a path step only where `keyword-lookup?` permits.
      ;; An entity (it carries `:db/id` or an installed identity attribute) is
      ;; the one map whose attributes the database schema describes.
      (let [lookup? (keyword-lookup? x)]
        {:kind :map :face ::print/map :open "{" :close "}" :total (count x) :indexed? true
         :lookup? lookup?
         :entity? (boolean (and lookup? (or (find x :db/id) (some #(find x %) identity-attributes))))
         :sorted? (sorts? x key)
         :members (held-order x identity-attributes key)})
      (#{clojure.lang.PersistentVector clojure.lang.MapEntry} c)
      {:kind :coll :face ::print/vector :open "[" :close "]" :total (count x) :members (seq x)}
      ;; Datahike's relation result is an APersistentSet over an array: count
      ;; and seq read that array (datahike/java/QueryResult.java).
      (#{clojure.lang.PersistentHashSet clojure.lang.PersistentTreeSet datahike.java.QueryResult} c)
      {:kind :coll :face ::print/set :open "#{" :close "}" :total (count x) :ordered? true :indexed? true
       :sorted? (sorts? x identity)
       :members (held-order x identity-attributes identity)}
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
      ;; A namespace is its name, as clojure.main prints it (#namespace[user]):
      ;; the final `name` field of a Clojure or SCI namespace, both final classes.
      (or (identical? c clojure.lang.Namespace) (identical? c sci.lang.Namespace))
      (let [n (if (identical? c sci.lang.Namespace) (.-name ^sci.lang.Namespace x) (.-name ^clojure.lang.Namespace x))]
        (if (identical? (class n) clojure.lang.Symbol)
          (scalar ::print/symbol n (str "#namespace[" n "]"))
          {:kind :scalar :node {::print/face ::print/object ::print/class (.getName c)}}))
      ;; An agent's record prints as clojure.main prints it (#user.Card{...}):
      ;; SciRecord's final rec-name and ext-map fields (sci/impl/records.cljc),
      ;; the fields classified as the exact map they are; no method is called.
      (identical? c sci.impl.records.SciRecord)
      (let [record-name (.-rec-name ^sci.impl.records.SciRecord x)
            row (classify (.-ext-map ^sci.impl.records.SciRecord x) identity-attributes limit)]
        (if (and (identical? (class record-name) clojure.lang.Symbol) (= ::print/map (:face row)))
          (assoc row :face ::print/record :name (str record-name) :open (str "#" record-name "{"))
          {:kind :scalar :node {::print/face ::print/object ::print/class (.getName c)}}))
      ;; An agent's type prints as its name (user.Card), from SCI's own box.
      (identical? c sci.lang.Type)
      (let [type-name (:sci.impl/type-name (.getVal ^sci.lang.Type x))]
        {:kind :scalar :node (if (identical? (class type-name) clojure.lang.Symbol)
                               {::print/face ::print/class ::print/name (str type-name)}
                               {::print/face ::print/object ::print/class (.getName c)})})
      (identical? c Class) {:kind :scalar :node {::print/face ::print/class ::print/name (.getName ^Class x)}}
      (and (instance? Throwable x) (throwable-safe? c))
      (assoc (classify (throwable-value x) identity-attributes limit) :prefix "#error " :face ::print/throwable)
      ;; Any other object is its class and identity, never its contents: a
      ;; live object, not data a later evaluation could read back.
      :else
      {:kind :scalar :node {::print/face ::print/object
                            ::print/class (clojure.lang.Compiler/demunge (.getName c))
                            ::print/rep (str "0x" (Integer/toHexString (System/identityHashCode x)))}})))

(declare cut-text)

(defn- string-shown
  "Characters a string node shows when admitted: whole when its cut at the
  first chunk would cost at least the escaped literal it hides, else that chunk."
  {:malli/schema [:=> [:cat :map :string :int] :int]}
  [node text max-string]
  (let [chunk (min chunk-width (:total node) max-string)]
    (if (and (< chunk (:total node)) (<= (:total node) max-string)
             ;; The raw length bounds the escaped scan by the cut's cost.
             (let [cost (count (cut-text (assoc node :shown chunk)))]
               (and (<= (- (:total node) chunk) cost)
                    (<= (shown-width (assoc node :value text) chunk (:total node)) cost))))
      (:total node)
      chunk)))

(defn- tuple-node
  "A short sequential value whose every member is a scalar, a name or a string
  shown whole (`string-shown`), as one whole print node, else nil. Such a tuple
  (a frame, a lookup ref, a pair) is admitted whole or not at all."
  {:malli/schema [:=> [:cat :map [:set :qualified-keyword] :seon.render.data/path [:maybe :seon.print/requery-id] :int] [:maybe :map]]}
  [c identity-attributes path requery-id max-string]
  (when (and (= :coll (:kind c)) (#{::print/vector ::print/list} (:face c))
             (not (:pending? c)) (<= (:total c) tuple-bound)
             ;; every member reached: a seq stepped short of its count is no tuple
             (= (:total c) (count (:members c))))
    (let [items (map-indexed
                 (fn [i member]
                   ;; A collection is never a tuple member: it is not
                   ;; classified, so no lazy member is realized here.
                   (let [x (if (coll? member) {:kind :coll} (classify member identity-attributes 1))
                         node (case (:kind x)
                                :scalar (:node x)
                                :name (assoc (:node x) ::print/text (:text x))
                                :string (when (= (:total x) (string-shown {:kind :string :total (:total x) :path (conj path i)
                                                                            :requery-id requery-id}
                                                                           (:value x) max-string))
                                          {::print/face ::print/string ::print/value (:value x)})
                                nil)]
                     (some-> node (assoc :seon.render.data/path (conj path i)))))
                 (:members c))]
      (when (every? some? items)
        {::print/face (:face c) ::print/items (vec items) :seon.render.data/total (:total c)}))))

(defn- leaf-width
  "The flat width of a leaf print node, as the emitter prints it at `depth`:
  declared AI text below the root prints as a string literal."
  {:malli/schema [:=> [:cat :map :int] :int]}
  [node depth]
  (cond
    (::print/text node) (count (::print/text node))
    (= ::print/var (::print/face node)) (+ 2 (count (::print/name node)))
    (= ::print/class (::print/face node)) (count (::print/name node))
    (= ::print/object (::print/face node))
    (+ 9 (count (::print/class node)) (if-some [rep (::print/rep node)] (inc (count rep)) 0))
    (and (pos? depth) (= ::print/projected (::print/face node))
         (= :seon.render/ai (:seon.render/output node)))
    (count (print/string-literal (::print/value node)))
    :else (count (print/emit-text node {::print/width 0 ::print/length nil ::print/level nil
                                        ::print/namespace-maps? false}))))

(defn- node-continuation
  "The form a cut of `node` prints to read its rest (`seon.print/continuation`):
  from the next offset for a sequence, string, or a map or set shown in its
  `seq` order; the whole collection for a map or set shown sorted
  (`sorts?`), where `drop` would not name the rest; render text's
  `(seon.print/full result/…)`. Nil without a source, and for a long keyword
  or symbol."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.print/continuation]]}
  [node]
  (if (:raw? node)
    (:raw-continuation node)
  (when (and (:requery-id node) (not= :name (:kind node)))
    (print/continuation
     {::print/requery-id (:requery-id node)
      ::print/elision-unit (cond (= :string (:kind node)) :characters
                                 (:pending? node) :pending
                                 :else :children)
      :seon.render.data/path (:path node)
      :seon.render.data/next-offset (cond (:sorted? node) 0
                                          (= :string (:kind node)) (+ (:base node 0) (:shown node))
                                          :else (+ (:base node 0) (:n node)))}))))

(defn- cut-text
  "The cut's text after a node's shown part, as `seon.print/elided-text`
  prints it: the marker, then the continuation form that reads the rest when
  the value has a source (a long keyword or symbol has no plain continuation)."
  {:malli/schema [:=> [:cat :map] :string]}
  [node]
  (let [marker (case (:kind node)
                 (:string :name) (str "…+" (- (:total node) (:shown node)))
                 (if (:pending? node) "…?" (str "…" (- (:total node) (:base node 0) (:n node)))))
        form (node-continuation node)]
    (cond-> marker form (str " " (print/form-text form)))))

(defn- more?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [node]
  (case (:kind node)
    (:string :name) (< (:shown node) (:total node))
    ;; A counted seq is stepped only to its member limit, so members past it
    ;; were never pending: its total, not its queue, names the cut.
    (:map :coll) (boolean (or (:pending? node) (seq (:pending node))
                              (< (+ (:base node 0) (:n node)) (:total node))))
    false))

(defn- flat-width
  "A node's flat width from its own fields and its children's widths."
  {:malli/schema [:=> [:cat :map] :int]}
  [node]
  (case (:kind node)
    :leaf (:width node)
    ;; Render text has no quotes; its cut starts a line of its own.
    :string (+ (if (:raw? node) 0 2) (:shown-width node)
               (if (more? node) (+ (count (cut-text node)) (if (:raw? node) 1 0)) 0))
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

(defn- tuple?
  "True for a leaf admitted whole as a tuple (`tuple-node`): fipp still lays
  its members out as a group, so it breaks like a collection."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [node]
  (and (= :leaf (:kind node)) (some? (::print/items (:node node)))))

(defn- extras
  "Width fipp's line breaks add to a broken node. A broken tuple fills
  (`seon.print` lays an all-scalar sequence out member by member), so charging
  every separator as a break bounds what it adds."
  {:malli/schema [:=> [:cat :map] :int]}
  [node]
  (if-not (:broken? node)
    0
    (case (:kind node)
      (:map :coll) (* (+ (:indent node) (nest node)) (max 0 (dec (+ (:n node) (if (more? node) 1 0)))))
      :entry (+ (:indent node) (if (< (:depth node) nest-cap) 2 0))
      :leaf (* (+ (:indent node) (if (< (:depth node) nest-cap) 1 0))
               (max 0 (dec (count (::print/items (:node node))))))
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
       (or (contains? #{:map :coll :entry} (:kind node)) (tuple? node))
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

(defn- declared
  "What the render pairs say about a reached map (design §2): nil, the
  producer's print node `{::print/node n}`, its identity projection to select
  in its place `{:value v}`, or `{:diagnostic failure}` when selection or the
  producer failed. A failure never replaces the value: the node prints as the
  data it is and the failure is shown once beside it (AGENTS.md, the REPL
  path is total)."
  {:malli/schema [:=> [:cat :seon.render.value/unit :seon.schema/value :seon.render.data/path :seon.render/output] [:maybe :map]]}
  [unit value path output]
  (when (and (or (keyword-lookup? value)
                 ;; a defined Var selects the Var render pair (`seon.render/declared-producers`)
                 (or (instance? clojure.lang.Var value) (instance? sci.lang.Var value)))
             (:seon.sci.eval/ctx unit)
             (not (get-in unit [:seon.render.value/options :seon.render.value/structural?])))
    (try
      (let [projection (or (:seon.schema/projection unit)
                           (:seon.schema/projection (meta (:seon.db/db unit)))
                           (:seon.schema/projection (env/of (:seon.sci.eval/ctx unit))))
            identity (when projection (schema/identity-only-projection-in projection value))
            value (if identity (:seon.schema/identity-value identity) value)
            projector (when identity
                        (let [form (get (:seon.schema.projection/forms projection) (:seon.schema/key identity))]
                          (when (and (vector? form) (map? (second form)))
                            (:seon.schema/identity-projection (second form)))))
            answer (@render-declared-projection
                    (cond-> (assoc unit :seon.render.data/path path)
                      projector (assoc :seon.render/value value :seon.render.call/function projector))
                    value output)
            ;; An entity's one hop shows beside its render (`seon.related/data`).
            answer (if (and (empty? path) (::print/node answer))
                     (@walk-with-related unit value output answer)
                     answer)]
        (cond
          (::print/node answer) answer
          (:seon.error/value answer) {:diagnostic (:seon.error/value answer)}
          identity {:value value}))
      (catch Throwable failure {:diagnostic failure}))))

(defn- make-node
  "A node for `value` reached at `path`, classified, with its flat width."
  {:malli/schema [:=> [:cat :map :seon.schema/value :int :int :seon.render.data/path [:maybe :int] :map] :map]}
  [context value id depth path parent position]
  (let [{:keys [unit output identity-attributes]} context
        ;; RENDER OUTPUT GOES STRAIGHT TO THE VALUE PRINTER, WHICH NEVER
        ;; RE-DISPATCHES (AGENTS.md, renders return data): only the root is
        ;; offered to its render; every value beneath prints as data.
        answer (when (zero? id) (declared unit value path output))
        face (::print/node answer)
        ;; A RENDER RETURNS DATA (AGENTS.md): an AI face that is not text is
        ;; printed by these same rules in the value's place, and nothing in it
        ;; is offered to a render again.
        render-data? (and (= ::print/projected (::print/face face))
                          (= :seon.render/ai (:seon.render/output face))
                          (not (string? (::print/value face))))
        declared (when-not render-data? face)
        value (cond render-data? (::print/value face)
                    (contains? answer :value) (:value answer)
                    :else value)
        base (cond-> {:id id :depth depth :path path :parent parent :indent (:indent position 0)
                      :outside (:outside position) :kids [] :n 0 :sum 0}
               (:diagnostic answer) (assoc :diagnostic (:diagnostic answer))
               render-data? (assoc :render-data? true
                                   :producer (:seon.render.call/selected-producer face))
               ;; a render may widen its own data (`:seon.print/budget` metadata)
               (and render-data? (instance? clojure.lang.IMeta value)
                    (pos-int? (::print/budget (meta (::print/value face)))))
               (assoc :own-budget (::print/budget (meta (::print/value face))))
               ;; The source a cut's continuation form reads from.
               (and (not render-data?) (get-in context [:requery ::print/requery-id]))
               (assoc :requery-id (get-in context [:requery ::print/requery-id])))
        ;; A RENDER'S TEXT IS A STRING SHOWN AS WRITTEN (`:raw?`): selected,
        ;; filled to a line end and cut like any long string, under the one
        ;; budget; its cut reads the rest with `(seon.print/full result/…)`
        ;; when the value can carry that mark.
        raw-text (when (and (= ::print/projected (::print/face declared))
                            (= :seon.render/ai (:seon.render/output declared)))
                   (::print/value declared))
        node (cond
               raw-text
               (let [node (cond-> (assoc base :kind :string :raw? true :node declared :value raw-text
                                         :total (count raw-text))
                            (and (symbol? (:requery-id base)) (instance? clojure.lang.IObj value))
                            (assoc :raw-continuation (list 'seon.print/full (:requery-id base))))
                     shown (string-shown node raw-text (:max-string context))]
                 (assoc node :shown shown :shown-width (shown-width node 0 shown)))
               declared (assoc base :kind :leaf :node declared)
               :else
               (let [;; A root continuation pages from its absolute offset.
                     c (classify value identity-attributes (+ (:offset context 0) (:max-children context)))
                     ;; A root paged from an offset keeps its members addressable.
                     tuple (when (and (or (pos? id) (zero? (:offset context 0)))
                                      (or (nil? (:length context)) (<= (:total c 0) (:length context)))
                                      (or (nil? (:level context)) (< depth (:level context))))
                             (tuple-node c identity-attributes path (:requery-id base) (:max-string context)))]
                 (case (if tuple :tuple (:kind c))
                   :tuple (assoc base :kind :leaf :node tuple)
                   :scalar (assoc base :kind :leaf :node (:node c))
                   :name (if (<= (count (:text c)) name-bound)
                           (assoc base :kind :leaf :node (assoc (:node c) ::print/text (:text c)))
                           (assoc base :kind :name :node (:node c) :text (:text c) :total (count (:text c))
                                  :shown chunk-width :shown-width chunk-width))
                   :string (let [node (assoc base :kind :string :value (:value c) :total (:total c))
                                 shown (string-shown node (:value c) (:max-string context))]
                             (assoc node :shown shown :shown-width (escaped-width (:value c) 0 shown)))
                   (merge base (select-keys c [:kind :face :name :open :close :total :pending? :prefix :ordered? :indexed? :sorted? :lookup? :entity?])
                          {:pending (:members c) :outside (:prefix c)}))))
        node (if (= :leaf (:kind node)) (assoc node :width (leaf-width (:node node) depth)) node)]
    (assoc node :flat (flat-width node))))

(defn- charge
  {:malli/schema [:=> [:cat :map] :int]}
  [state]
  (+ (:reserve state) (get-in state [:nodes 0 :flat]) (:extras state)))

(defn- push-work
  "Queue `work` at `level`: every member at one level is admitted before any
  at the next, members before the growth of a string at that level (`rank`
  0, a chunk 1), then by `ordinal` within the parent."
  {:malli/schema [:=> [:cat :map :int :int :int :map] :map]}
  [state level rank ordinal work]
  (let [key [level rank ordinal (:seq state)]]
    (-> state (update :seq inc) (update :queue assoc key work))))

(defn- schedule
  "Push a node's first refinement: its next chunk, one level down, or its
  first member at the next level."
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [state node context]
  (case (:kind node)
    (:string :name) (cond-> state (more? node)
                      (push-work (inc (:level node)) 1 2 {:op :chunk :id (:id node) :k 2}))
    (:map :coll) (cond-> state
                   (and (seq (:pending node)) (< (:depth node) (:max-depth context)))
                   (push-work (inc (:level node)) 0 1 {:op :member :parent (:id node) :ord 1
                                                       :level (inc (:level node))}))
    state))

(defn- grown-to
  "`state` with string or name node `node` shown through character `to`."
  {:malli/schema [:=> [:cat :map :map :int :map] :map]}
  [state node to context]
  (let [grown (-> node (assoc :shown to)
                  (update :shown-width + (if (= :name (:kind node)) (- to (:shown node))
                                             (shown-width node (:shown node) to))))]
    (settle state (assoc-in state [:nodes (:id node)] grown) (:id node) (:width context))))

(defn- complete-chunk
  "The string or name shown whole when its cut would cost at least the
  characters it hides (AGENTS.md: an elision costs less than what it hides),
  else nil: the whole charge may not rise."
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [state work context]
  (let [node (get-in state [:nodes (:id work)])]
    (when (and (more? node) (<= (- (:total node) (:shown node)) (count (cut-text node))))
      (let [whole (grown-to state node (:total node) context)]
        (when (<= (charge whole) (charge state)) whole)))))

(defn- admit-chunk
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [state work context]
  (let [node (get-in state [:nodes (:id work)])
        ;; Each chunk doubles what the string shows, one level per chunk.
        to (min (:total node) (+ (:shown node) (max chunk-width (:shown node))) (:max-string context))
        next (grown-to state node to context)]
    (when (and (> to (:shown node))
               (<= (charge next) (:budget context)))
      (cond-> next
        (< to (:total node))
        (push-work (+ (:level node) (:k work)) 1 (inc (:k work)) (update work :k inc))))))

(defn- referent-identity
  "The referent of entity id `eid` named by its identity: `[attribute value]`
  for the first identity attribute it holds, else `[:db/id eid]`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :qualified-keyword] :int] [:vector :seon.schema/value]]}
  [database identity-attributes eid]
  (let [attributes (sort-by str identity-attributes)
        entity (db/pull database (into [:db/id] attributes) eid)]
    (or (some #(when-some [v (get entity %)] [% v]) attributes)
        [:db/id eid])))

(defn- reference-value
  "A non-component reference attribute's value as its referent's identity,
  never an entity id: a bare {:db/id n} (a pull's reference) becomes the
  referent's lookup ref, and so does an integer, but only inside an entity
  (`entity?`), whose attributes the database schema describes. Any other
  value, or a key that is not such an attribute, is itself.

  ## Edge cases

  - `(frequencies …)` keyed by a ref attribute, `{:seon.error/resolved-tx 9}`,
    is no entity: 9 prints as 9, never as the entity 9 names."
  {:malli/schema [:=> [:cat :map :boolean :seon.schema/value :seon.schema/value] :seon.schema/value]}
  [context entity? attribute value]
  (let [database (:seon.db/db (:unit context))
        properties (when (and database (identical? (class attribute) clojure.lang.Keyword))
                     (get (:schema (db/schema-database database)) attribute))
        one (fn [v]
              (let [eid (cond (and entity? (int? v)) v
                              (and (map? v) (= [:db/id] (keys v))) (:db/id v))]
                (if eid (referent-identity database (:identity-attributes context) eid) v)))]
    (cond
      (not (and (= :db.type/ref (:db/valueType properties)) (not (:db/isComponent properties)))) value
      (= :db.cardinality/many (:db/cardinality properties))
      (if (or (vector? value) (set? value)) (into (empty value) (map one) value) value)
      :else (one value))))

(defn- sibling-level
  "The level member `ordinal` of `parent` is admitted at: a map of at most
  64 members has them all one level below it, so every key shows; a larger
  map's, a sequence's or a set's one more level per doubling of the ordinal,
  so earlier members are shown more fully and the tail is cut with its count
  and elision form rather than every member shown collapsed."
  {:malli/schema [:=> [:cat :map :int] :int]}
  [parent ordinal]
  (+ (inc (:level parent))
     (if (and (= :map (:kind parent)) (<= (:total parent 0) sorted-bound))
       0
       (- 63 (Long/numberOfLeadingZeros (long ordinal))))))

(defn- admit-member
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [state work context]
  (when (and (or (:past-max-children? context) (<= (:ord work) (:max-children context)))
             (or (nil? (:length context)) (<= (:ord work) (:length context))))
  (let [parent (get-in state [:nodes (:parent work)])
        [held & more] (:pending parent)
        ordinal (:ord work)
        depth (inc (:depth parent))
        indent (+ (:indent parent) (nest parent))
        next-id (:next-id state)
        ;; The member's coordinate in the held value (`seon.print/path-step`):
        ;; a map or set member carries its iteration index; a sequence member
        ;; is its absolute position.
        [index member] (if (:indexed? parent) held [(+ (:base parent 0) (dec ordinal)) held])
        [added kid]
        (if (= :map (:kind parent))
          (let [entry-id next-id
                k (make-node context (key member) (+ 1 next-id) depth
                             (conj (:path parent) [:seon.render.data/entry index :seon.render.data/key])
                             entry-id {:indent indent})
                v (make-node context (reference-value context (boolean (:entity? parent)) (key member) (val member))
                             (+ 2 next-id) depth
                             (conj (:path parent) (if (and (:lookup? parent) (keyword? (key member)))
                                                    (key member)
                                                    [:seon.render.data/entry index :seon.render.data/val]))
                             entry-id {:indent (+ indent (if (< (:depth parent) nest-cap) 2 0))})]
            [[{:kind :entry :id entry-id :depth (:depth parent) :indent indent :parent (:id parent)
               :kids [(:id k) (:id v)] :n 0 :sum 0} k v] entry-id])
          (let [c (make-node context member next-id depth
                             ;; `get-in` reads an index of a vector only: a seq's
                             ;; member is a typed step, read with `nth`.
                             (conj (:path parent) (if (or (:indexed? parent) (not= ::print/vector (:face parent)))
                                                    [:seon.render.data/nth index]
                                                    index))
                             (:id parent) {:indent indent})]
            [[c] next-id]))
        added (mapv #(assoc % :level (:level work)) added)
        grown (-> (reduce #(assoc-in %1 [:nodes (:id %2)] %2) state added)
                  (assoc :next-id (+ next-id (count added)))
                  (update-in [:nodes (:id parent)] #(-> % (update :kids conj kid) (update :n inc) (assoc :pending more))))
        next (settle state grown kid (:width context))]
    (cond
      (<= (charge next) (:budget context))
      (cond-> (reduce #(schedule %1 %2 context) next (remove #(= :entry (:kind %)) added))
        (seq more)
        (push-work (sibling-level parent (inc ordinal)) 0 (inc ordinal)
                   (assoc work :ord (inc ordinal) :level (sibling-level parent (inc ordinal)))))
      ;; The root's first member is the least any page must show (§3c):
      ;; its charge is what an insufficient budget reports.
      (and (zero? (:id parent)) (zero? (:n parent))) (assoc state :first-refused (charge next))))))

(defn- complete-tail
  "Every remaining member of the work's parent admitted at once when the cut
  it would otherwise keep costs at least what they hold (AGENTS.md: an elision
  costs less than what it hides), else nil. Each member must print whole
  without further work, and the whole charge may not rise; the attempt stops
  as soon as the members shown exceed the cut they would replace."
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [state work context]
  (let [parent-id (:parent work)
        before (charge state)
        ;; Past the member bound too; classification keeps its own step limit.
        open (assoc context :budget Long/MAX_VALUE :past-max-children? true)
        whole? (fn [node] (or (#{:leaf :entry} (:kind node))
                              (and (#{:string :name} (:kind node)) (not (more? node)))))]
    (when-not (:pending? (get-in state [:nodes parent-id]))
      (loop [current state ordinal (:ord work)]
        (let [parent (get-in current [:nodes parent-id])]
          (if-not (seq (:pending parent))
            (when (<= (charge current) before) (assoc current :queue (:queue state)))
            (let [admitted (admit-member current (assoc work :ord ordinal) open)
                  grown (get-in admitted [:nodes parent-id])]
              (when (and admitted
                         (every? #(whole? (get-in admitted [:nodes %])) (range (:next-id current) (:next-id admitted)))
                         (<= (- (charge admitted) (if (more? grown) (count (cut-text grown)) 0)) before))
                (recur admitted (inc ordinal))))))))))

(defn- fill-end
  "Where a cut string grown into `room` more characters of literal (render
  text: of characters) ends: at most `room` wide, back to its last line end,
  inside the growth for a literal and anywhere in the shown prefix for render
  text, which may shrink to it."
  {:malli/schema [:=> [:cat :map :int :int] :int]}
  [node room max-string]
  (let [^String text (:value node)
        limit (min (:total node) max-string)
        to (loop [i (long (:shown node)) width 0]
             (if (< i limit)
               ;; `escaped-width`'s rule, one character at a time.
               (let [c (.charAt text i)
                     width (+ width (if-let [e (when-not (or (:raw? node) (= \newline c)) (char-escape-string c))] (count e) 1))]
                 (if (<= width room) (recur (inc i) width) i))
               i))
        line-end (inc (.lastIndexOf text "\n" (int (dec to))))]
    (if (and (< to limit) (> line-end (if (:raw? node) 0 (:shown node)))) line-end to)))

(defn- fill-string
  "`state` with cut string `node` grown into the budget left (`fill-end`),
  shrinking past what the regrown cut costs, or render text ended at its last
  line end; nil when nothing changes."
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [state node context]
  (loop [room (max 0 (- (:budget context) (charge state)))]
    (let [to (fill-end node room (:max-string context))]
      (when (not= to (:shown node))
        (let [grown (grown-to state node to context)
              over (- (charge grown) (:budget context))]
          (cond (not (pos? over)) grown
                (pos? room) (recur (max 0 (- room over)))))))))

(defn- fill-strings
  "`state` with the budget left after selection spent on its cut strings,
  shallowest and earliest first: a long source shows as many lines as fit."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [state context]
  (reduce (fn [state id] (or (fill-string state (get-in state [:nodes id]) context) state))
          state
          (map :id (sort-by (juxt :level :id) (filter #(and (= :string (:kind %)) (more? %)) (vals (:nodes state)))))))

(defn- insufficient-budget
  "The declared refusal when B cannot show the root, or a page's first member
  (design §3c): a cut that repeated its own offset would make no progress."
  {:malli/schema [:=> [:cat :map :int] :seon.render.value/insufficient-floor-budget-error]}
  [context needed]
  (assoc (refusal/diagnostic (java.util.Date.) :seon.render.value/select 'seon.render.value/prepare
          {:seon.error/message (str "The value printer's budget of " (:budget context) " characters cannot show this value's "
                                    "first part, which needs " needed ".")
           :seon.error/member :seon.render.profile/token-budget
           :seon.error/expected "a budget covering the root and its first member"
           :seon.error/offending (:budget context)
           :seon.error/data {:seon.render.data/path (:root-path context [])}
           :seon.error/fix "Raise :seon.render.profile/token-budget, or read a deeper path."})
         :seon.render.value/budget (:budget context)
         :seon.render.value/needed needed))

(defn- select-nodes
  "Shell-order selection of `value` under the context's budget, or the
  insufficient-budget refusal when the root or its first member cannot fit."
  {:malli/schema [:=> [:cat :seon.schema/value :map] [:or :map :seon.render.value/insufficient-floor-budget-error]]}
  [value context]
  (let [offset (:offset context 0)
        root (make-node context value 0 0 (:root-path context []) nil {:indent 0 :outside nil})
        ;; Render data is not the value its handle names: its cuts offer no
        ;; dig form on the handle, and say so.
        context (cond-> context
                  (:render-data? root)
                  (assoc :requery {::print/requery-refusal
                                   "render data; read the value itself through its result handle"})
                  (and (:own-budget root) (not= Long/MAX_VALUE (:budget context)))
                  (as-> c (let [own (display-budget (:profile c) (:own-budget root))]
                            (assoc c :budget-tokens own :budget (tokens/estimate-chars own)))))
        text-key (if (= :name (:kind root)) :text :value)
        ;; A continuation starts the root at its absolute offset (design
        ;; §3g): a string or name at its character, a collection at its
        ;; member, which keeps its absolute ordinal.
        root (cond-> root
               (and (pos? offset) (#{:string :name} (:kind root)))
               (as-> node (let [start (min offset (:total node))
                                text (subs (text-key node) start)
                                shown (min chunk-width (count text) (:max-string context))]
                            (assoc node text-key text :total (count text) :shown shown :base start
                                   :shown-width (if (= :name (:kind node)) shown (shown-width (assoc node :value text) 0 shown)))))
               (and (pos? offset) (#{:map :coll} (:kind root)))
               (-> (assoc :base offset) (update :pending #(seq (drop offset %))))
               true (as-> node (assoc node :flat (flat-width node))))
        ;; What the unit's other parts already spent of the one budget.
        state {:nodes {0 (assoc root :level 0)} :queue (sorted-map) :seq 0 :next-id 1 :extras 0
               :reserve (:reserve context 0)}
        state (settle {:nodes {}} state 0 (:width context))
        ;; The root is mandatory.
        root-charge (charge state)]
    (if (> root-charge (:budget context))
      (insufficient-budget context root-charge)
      (let [state (loop [state (schedule state (get-in state [:nodes 0]) context)]
                    (let [[key work] (first (:queue state))]
                      ;; Every queued refinement is tried in level order, so a
                      ;; budget refusal at one level leaves the rest to what fits.
                      (if work
                        (let [state (update state :queue dissoc key)
                              admitted (case (:op work)
                                         :chunk (or (admit-chunk state work context)
                                                    (complete-chunk state work context))
                                         :member (or (admit-member state work context)
                                                     (complete-tail state work context)))]
                          (recur (or admitted state)))
                        (fill-strings state context))))]
        (if (and (:first-refused state) (zero? (get-in state [:nodes 0 :n])))
          (insufficient-budget context (:first-refused state))
          (assoc state :requery (:requery context) :budget-tokens (:budget-tokens context)))))))

(defn- cut-node
  "The one tail cut of a node that shows less than it holds."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [node context]
  (let [bound (cond (and (= :string (:kind node)) (>= (:shown node) (:max-string context)))
                    :seon.render.profile/max-string-length
                    (#{:string :name} (:kind node)) :seon.render.profile/token-budget
                    (and (:level context) (>= (:depth node) (:level context))) :seon.print/level
                    (>= (:depth node) (:max-depth context)) :seon.render.profile/max-depth
                    (and (:length context) (>= (:n node) (:length context))) :seon.print/length
                    :else :seon.render.profile/token-budget)
        raw? (:raw? node)
        request (cond-> (merge (:requery context)
                               {:seon.render.profile/id (:seon.render.profile/id (:profile context))
                                :seon.render.data/path (:path node)})
                  raw? (assoc ::print/raw? true)
                  ;; Render text is not at a path of the value: without its
                  ;; `full` form a cut of it says why it has no dig form.
                  (and raw? (not (:raw-continuation node)))
                  (-> (dissoc ::print/requery-id)
                      (assoc ::print/requery-refusal
                             "render text: the value has no result handle or cannot carry seon.print/full's mark"))
                  (not (:pending? node))
                  (assoc ::print/bound-by (if (= bound :seon.render.profile/token-budget)
                                            (or (::print/bound-by (:profile context)) bound)
                                            bound))
                  ;; The one continuation the cut's text was measured with.
                  (node-continuation node)
                  (assoc ::print/continuation (node-continuation node)))
        cut (print/elision
     (case (:kind node)
       (:string :name) (assoc request ::print/omitted (- (:total node) (:shown node))
                              ::print/elision-unit :characters
                              ::print/prefix (subs (if (= :name (:kind node)) (:text node) (:value node)) 0 (:shown node))
                              :seon.render.data/next-offset (+ (:base node 0) (:shown node))
                              :seon.render.data/total (+ (:base node 0) (:total node)))
       (if (:pending? node)
         (assoc request ::print/elision-unit :pending :seon.render.data/next-offset (+ (:base node 0) (:n node)))
         (assoc request ::print/omitted (- (:total node) (:base node 0) (:n node)) ::print/elision-unit :children
                :seon.render.data/next-offset (+ (:base node 0) (:n node)) :seon.render.data/total (:total node)))))]
    ;; The HTML expanders are the same continuation, at the cut's
    ;; coordinates, and for a string the whole string at its path.
    (if-some [href (when-not raw? (path-url (:unit context) (:path node) (:seon.render.data/next-offset cut)))]
      (cond-> (assoc cut ::print/href href)
        (= :string (:kind node)) (assoc ::print/text-href (path-url (:unit context) (:path node) 0 true)))
      cut)))

(defn- fold-collapsed-tail
  "The selected nodes with each collection's trailing members that show
  nothing but their own cut (collapsed, not one member admitted; a map entry
  whose value is so) folded into the collection's tail cut, which already
  names their count and reads them: a row with no identity says less than the
  count. Only a collection shown in its `seq` order folds (a sorted one's cut
  is the whole value), and only when the tail cut costs no more than the
  members it replaces, so the charge still bounds the text."
  {:malli/schema [:=> [:cat [:map-of :int :map]] [:map-of :int :map]]}
  [nodes]
  (reduce-kv
   (fn [nodes id node]
     (let [kids (:kids node)
           collapsed? (fn [kid-id]
                        (let [kid (get nodes kid-id)
                              kid (if (= :entry (:kind kid)) (get nodes (second (:kids kid))) kid)]
                          (and (#{:map :coll} (:kind kid)) (zero? (:n kid)) (more? kid))))
           folded (when (and (#{:map :coll} (:kind node)) (not (:sorted? node)) (seq kids))
                    (take-while collapsed? (rseq kids)))
           kept (- (count kids) (count folded))
           shrunk (assoc node :kids (subvec kids 0 kept) :n kept)
           ;; Each member and the cut carry one separator; a map's two.
           gap (if (= :map (:kind node)) 2 1)
           before (+ (transduce (map #(+ gap (:flat (get nodes %)))) + 0 folded)
                     (if (more? node) (+ gap (count (cut-text node))) 0))]
       (if (and (seq folded) (pos? kept) (<= (+ gap (count (cut-text shrunk))) before))
         (-> (apply dissoc nodes (mapcat #(cons % (:kids (get nodes %))) folded)) (assoc id shrunk))
         nodes)))
   nodes nodes))

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
                  (:string :name) (cond
                                    (= :name (:kind node))
                                    (assoc (:node node) ::print/text (if cut (str (::print/prefix cut) "…+" (::print/omitted cut)) (:text node)))
                                    ;; Render text keeps its producer beside its cut; whole, it is the render's own node.
                                    (:raw? node) (if cut
                                                   (merge cut (select-keys (:node node) [:seon.render.call/selected-producer
                                                                                         :seon.render.call/source-output?]))
                                                   (:node node))
                                    cut cut
                                    :else {::print/face ::print/string ::print/value (:value node)})
                  (:map :coll)
                  (let [children (cond-> kids cut (conj cut))
                        collection (cond-> {::print/face (if (= ::print/throwable (:face node)) ::print/map (:face node))}
                                     (= :map (:kind node)) (assoc ::print/entries children)
                                     (:name node) (assoc ::print/name (:name node))
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

(defn- omitted-characters
  "An estimate of the characters a selection left out, from what the value printer
  already knows at each cut, never by printing the value: a string's
  unshown characters exactly, and a collection's omitted members at the mean
  width of the members it did show (the mean over every shown collection
  when it showed none). A pending tail's length is unknown and counts 0, so
  the estimate is a lower bound."
  {:malli/schema [:=> [:cat [:map-of :int :map]] :int]}
  [nodes]
  (let [shown (filter #(and (#{:map :coll} (:kind %)) (pos? (:n %))) (vals nodes))
        members (reduce + 0 (map :n shown))
        mean (if (pos? members) (/ (double (reduce + 0 (map :sum shown))) members) 1.0)]
    (long (reduce + 0.0
                  (keep (fn [node]
                          (when (more? node)
                            (case (:kind node)
                              (:string :name) (- (:total node) (:shown node))
                              (if (:pending? node)
                                0
                                (* (- (:total node) (:base node 0) (:n node))
                                   (if (pos? (:n node)) (/ (double (:sum node)) (:n node)) mean))))))
                        (vals nodes))))))

(defn- breadcrumbs
  [unit path]
  (when (:seon.render.value/route-base unit)
    [:nav {:class "seon-data-crumbs"}
     (path-link unit [] 0 "root" "seon-data-crumb")
     (map (fn [index]
            (path-link unit (subvec path 0 (inc index)) 0
                       (pr-str (nth path index)) "seon-data-crumb"))
          (range (count path)))]))

(defn- plain-projection
  "The value printer's last answer: `value` as plain bounded Clojure text, with
  the diagnostic naming the failure that stopped the structural selection,
  both projection-free (`bounded-text`) under the core's one budget, the
  diagnostic charged first: this terminal never re-enters selection."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.error/throwable :seon.render.data/path]
                  :seon.render.value/projection]}
  [value failure path]
  (let [budget (tokens/estimate-chars (:seon.render.profile/token-budget core-profile))
        diagnostic (bounded-text [{:seon.render.data/path path
                                   :seon.render.value/failure (compact-failure failure)}]
                                 budget)
        value-text (bounded-text value (max 1 (- budget (count diagnostic) 1)))
        text (str value-text "\n" diagnostic)]
    {:seon.render.value/tree {::print/face ::print/projected :seon.render/output :seon.render/ai ::print/value text}
     :seon.render.value/options (print/default-options)
     :seon.render.value/truncated? true
     :seon.render.value/text text
     :seon.render.value/value-text value-text
     :seon.render.value/html [:div {:class "seon-data-panel"} [:pre value-text]
                              [:div {:class "seon-render-unavailable"}
                               [:pre {:class "seon-data-panel seon-core-value"} diagnostic]]]
     :seon.render.value/diagnostics [{:seon.render.data/path path :seon.render.value/failure failure}]}))

(defn- beside-share
  "The characters of the one budget held back for the reply's other parts:
  what they already took (`:seon.render.value/spent`) plus, for parts printed
  later, at most half the budget of what they would take whole
  (`:seon.render.value/beside`), so two parts that both overflow split it
  and one that fits shows whole. A quarter of the budget always stays for the
  value's own root and cut."
  {:malli/schema [:=> [:cat :map :int] :int]}
  [unit budget]
  (let [spent (:seon.render.value/spent unit 0)
        beside (:seon.render.value/beside unit 0)]
    (min (+ (if (pos-int? spent) spent 0) (if (pos-int? beside) (min beside (quot budget 2)) 0))
         (- budget (quot budget 4)))))

(defn- project
  "The projection `prepare` answers, for a unit that may carry no database:
  the core prints with none, and a database present is read, never supplied.
  `reserve` is the characters of the one budget the unit's diagnostics take."
  {:malli/schema [:function
                  [:=> [:cat :seon.render.value/unit :seon.render/output]
                   [:or :nil :seon.render.value/projection :seon.render.value/missing-root-identity-error
                    :seon.render.value/insufficient-floor-budget-error]]
                  [:=> [:cat :seon.render.value/unit :seon.render/output :int]
                   [:or :nil :seon.render.value/projection :seon.render.value/missing-root-identity-error
                    :seon.render.value/insufficient-floor-budget-error]]]}
  ([unit output] (project unit output 0))
  ([unit output reserve]
   (let [path (vec (get-in unit [:seon.render.data/cursor
                                :seon.render.data/path] []))]
     (try
       (let [id (node-id unit path)]
     (if (:seon.render.value/root-description id)
       id
       (let [profile (render-profile unit)
             ;; ONE SELECTION FOR BOTH FACES (design §3): HTML is the same
             ;; tree under the same B; its cuts are expanders, never a
             ;; second, unbounded selection.
             options (cond-> (assoc (print-options unit)
                                    :seon.print/length nil
                                    :seon.print/level nil)
                       (= :single-line (:seon.render.profile/composition profile))
                       (assoc :seon.print/width 0))
             ;; The session's own `*print-length*` / `*print-level*` (its
             ;; `set!`, carried as print options) bound the selection as
             ;; Clojure's printer would; absent, the profile's bounds stand.
             {length :seon.print/length level :seon.print/level}
             (merge (select-keys (:seon.render.value/options unit) [:seon.print/length :seon.print/level])
                    (select-keys (:seon.print/options unit) [:seon.print/length :seon.print/level]))
             database (:seon.db/db unit)
             budget-tokens (display-budget profile (budget-override unit (:seon.render/value unit)))
             ;; Nested selection reads the one profile this value prints
             ;; under and the one viewer, derived here once per value.
             selecting? (and (:seon.sci.eval/ctx unit)
                             (not (get-in unit [:seon.render.value/options :seon.render.value/structural?])))
             context {:unit (cond-> (assoc unit :seon.render/profile profile)
                              selecting? (merge (@render-viewer unit)))
                      :output output :profile profile
                      :requery (select-keys profile [:seon.print/requery-id :seon.print/requery-refusal])
                      :identity-attributes (if database (set (db/identity-attributes database)) #{})
                      :width (:seon.print/width options)
                      :budget (tokens/estimate-chars budget-tokens)
                      :reserve (+ reserve (beside-share unit (tokens/estimate-chars budget-tokens)))
                      ;; THE BUDGET BOUNDS THE MEMBERS: no count cap of its own.
                      ;; A shown member costs at least one character and one
                      ;; separator, so a seq is stepped no further than half
                      ;; the budget's characters (rule 7).
                      :max-children (cond-> (inc (quot (tokens/estimate-chars budget-tokens) 2))
                                      (int? length) (min length))
                      :max-depth (cond-> (:seon.render.profile/max-depth profile)
                                   (int? level) (min level))
                      ;; A session bound is the agent's own, as hard as
                      ;; Clojure's: no whole admission passes it.
                      :length (when (int? length) length)
                      :level (when (int? level) level)
                      :max-string (or (:seon.render.profile/max-string-length profile) Long/MAX_VALUE)
                      :budget-tokens budget-tokens
                      :offset (max 0 (long (get-in unit [:seon.render.data/cursor :seon.render.data/offset] 0)))
                      :root-path path}
             ;; THE EXPLICIT OPT-IN (`seon.print/full`): this one value prints
             ;; whole, with no budget and no member, depth or string bound.
             full? (true? (::print/full (meta (:seon.render/value unit))))
             context (cond-> context
                       full? (assoc :budget Long/MAX_VALUE
                                    :max-children Long/MAX_VALUE :max-depth Long/MAX_VALUE
                                    :max-string Long/MAX_VALUE :length nil :level nil))
             state (select-nodes (:seon.render/value unit) context)]
         ;; The selection answers its node state or its budget refusal.
         (if-not (:nodes state)
           state
       (let [failed (sort-by :id (filter :diagnostic (vals (:nodes state))))
             ;; Shown once, beside the value, each failure with its path as
             ;; data, inside the value's one budget.
             diagnostics (mapv (fn [node] {:seon.render.data/path (:path node)
                                           :seon.render.value/failure (:diagnostic node)})
                               failed)
             shown-diagnostics (when (seq diagnostics) (diagnostics-shown diagnostics))
             needed (if shown-diagnostics (inc (count (::text shown-diagnostics))) 0)]
         (if (and (< reserve needed) (not full?))
           ;; Only the root is offered a render, so selecting again under
           ;; the smaller budget meets the same failures.
           (project unit output needed)
       (let [state (update state :nodes fold-collapsed-tail)
             cut? (boolean (some more? (vals (:nodes state))))
             ;; Render data's cuts name no handle (`select-nodes`).
             context (assoc context :requery (:requery state))
             selected (cond-> (tree (:nodes state) context)
                        (get-in state [:nodes 0 :producer])
                        (assoc :seon.render.call/selected-producer (get-in state [:nodes 0 :producer])))
             emitted (print/emit-both selected options)
             ;; NO LEGEND (owner 2026-09-27): each cut carries the form that
             ;; reads its rest, which says what `…N` means at every cut.
             text (:seon.print/text emitted)
             limit (- (charge state) (:reserve state))]
         ;; The charge is the laid-out length (design §3d), declared render
         ;; text included; a longer text is the allocator's defect, never a
         ;; second attempt.
         (when (> (count text) limit)
           (let [refusal (refusal/diagnostic (java.util.Date.) :seon.render.value/select 'seon.render.value/prepare
                          {:seon.error/message "The value printer's saved text exceeded its charge."
                           :seon.error/expected "text length at most the charge"
                           :seon.error/offending (count text)
                           :seon.error/data {:seon.print/charge limit :seon.render.data/path path}})]
             (throw (ex-info (:seon.error/message refusal) refusal))))
         (cond-> {:seon.render.value/tree selected
                  :seon.render.value/options options
                  :seon.render.value/truncated? (boolean (or cut? (pos? (:offset context))))
                  :seon.render.value/elisions (count (filter more? (vals (:nodes state))))
                  :seon.render.value/whole-characters
                  (+ (count (:seon.print/text emitted)) (omitted-characters (:nodes state)))
                  :seon.render.value/text (cond-> text shown-diagnostics (str "\n" (::text shown-diagnostics)))
                  :seon.render.value/html
                  (cond-> [:div {:id id :class "seon-data-panel"}
                           (breadcrumbs unit path)
                           (:seon.print/hiccup emitted)]
                    shown-diagnostics (conj (::html shown-diagnostics)))}
           full? (assoc :seon.render.value/full? true)
           ;; a budget past the profile's is reported with its token count
           (and (not full?) (> (:budget-tokens state 0) (:seon.render.profile/token-budget profile)))
           (assoc :seon.render.value/budget-override (:budget-tokens state))
           (seq failed)
           (assoc :seon.render.value/diagnostics diagnostics
                  :seon.render.value/value-text text)
           (:seon.render.call/selected-producer selected)
           (assoc :seon.render.call/selected-producer
                  (:seon.render.call/selected-producer selected))
           (:seon.render.call/source-output? selected)
           (assoc :seon.render.call/source-output? true)))))))))
       (catch Throwable failure
         (if (get-in unit [:seon.render.value/options :seon.render.value/structural?])
           (plain-projection (:seon.render/value unit) failure path)
           ;; A FAILED RENDER STILL PRINTS THROUGH THE VALUE PRINTER: the same
           ;; selection, without the render pairs, under the same budget, so
           ;; the value keeps its cuts and its whole-size estimate (LARGE
           ;; VALUE reads it). The failure is the one diagnostic beside it;
           ;; only a value printer that fails structurally too prints plain text.
           (let [entry {:seon.render.data/path path :seon.render.value/failure failure}
                 {shown-text ::text shown-html ::html} (diagnostics-shown [entry])
                 ;; One budget: the failure's text is charged before the value's.
                 projection (project (assoc-in unit [:seon.render.value/options
                                                     :seon.render.value/structural?] true)
                                     output (+ reserve (inc (count shown-text))))]
             ;; A structural projection carries diagnostics only when the
             ;; value printer itself failed (it runs no declared render): print plain
             ;; text once, with the value printer's own distinct failures recorded.
             (if (or (not (:seon.render.value/text projection))
                     (seq (:seon.render.value/diagnostics projection)))
               (update (plain-projection (:seon.render/value unit) failure path)
                       :seon.render.value/diagnostics into
                       (remove #(= (ex-message (:seon.render.value/failure %)) (ex-message failure)))
                       (:seon.render.value/diagnostics projection))
               (-> projection
                   (assoc :seon.render.value/value-text
                          (or (:seon.render.value/value-text projection)
                              (:seon.render.value/text projection)))
                   (update :seon.render.value/text str "\n" shown-text)
                   (update :seon.render.value/html conj shown-html)
                   (update :seon.render.value/diagnostics (fnil conj []) entry))))))))))

(defn prepare
  "Select the value under its render profile (design one-render-path §3) and
  emit the one tree as laid-out AI text and structural HTML.

  TOTAL (AGENTS.md, the REPL path is total): a renderer that fails leaves its
  node printed as data, a failure of the value printer itself prints the value as
  plain bounded Clojure text, and either failure is shown once beside the
  value and returned as `:seon.render.value/diagnostics` for the caller to
  record as a core fault."
  {:malli/schema
   [:function
    [:=> [:cat :seon.render/unit]
     [:or :nil :seon.render.value/projection :seon.render.value/missing-root-identity-error
      :seon.render.value/insufficient-floor-budget-error]]
    [:=> [:cat :seon.render/unit :seon.render/output]
     [:or :nil :seon.render.value/projection :seon.render.value/missing-root-identity-error
      :seon.render.value/insufficient-floor-budget-error]]]}
  ([unit] (project unit :seon.render/ai))
  ([unit output] (project unit output)))

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
                :seon.sci.admit/unbounded? true}))}
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
  "Read one stored value artifact with the one reader (`seon.sci.reader`), or
  refuse content that is not one by name: an unreadable text or a form that
  is not `{:seon.sci.admit/print-node node}` (another blob's digest)."
  {:malli/schema [:=> [:cat :string]
                  [:or :seon.render.value/artifact :seon.render.value/unreadable-artifact-error]]}
  [content]
  (let [events (reader/read {::reader/text content :seon.config.eval.result/max-source (count content)})
        failure (if (vector? events) (some ::reader/error events) events)
        form (when (and (vector? events) (= 1 (count events))) (::reader/form (first events)))
        reason (cond
                 ;; a recovered read answers events, one of them an error
                 ;; event: report the reader's failure, not the shape
                 failure (str "The stored content does not read: " (:seon.error/message failure))
                 (not= 1 (count events)) (str "The stored content holds " (count events) " forms, not one artifact.")
                 (not (and (map? form) (print/node? (:seon.sci.admit/print-node form))))
                 "The stored content is not a value artifact (no :seon.sci.admit/print-node); this digest names another kind of blob.")]
    (if reason
      (assoc (refusal/diagnostic (java.util.Date.) :seon.render.value/artifact 'seon.render.value/read-artifact
                                 {:seon.error/message reason
                                  :seon.error/expected :seon.render.value/artifact})
             :seon.render.value/unreadable-artifact reason)
      form)))

(defn artifact-value
  "Derive semantic drill data from an artifact's sole print node."
  {:malli/schema [:=> [:cat :seon.render.value/artifact] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [stored]
  (admit/semantic-value (:seon.sci.admit/print-node stored)))

(defn render-ai
  "Render any value-printer unit as its text, or the refusal its projection answered."
  {:malli/schema [:=> [:cat :seon.render.value/unit]
                  [:or :string :seon.render.value/missing-root-identity-error
                   :seon.render.value/insufficient-floor-budget-error]]}
  [unit]
  (let [projection (project unit :seon.render/ai)]
    ;; A projection holds its tree; the refusals do not.
    (if (:seon.render.value/tree projection) (:seon.render.value/text projection) projection)))

(defn render-html
  "Render any value-printer unit as its hiccup, or the refusal its projection answered."
  {:malli/schema [:=> [:cat :seon.render.value/unit]
                  [:or :seon.render/hiccup :seon.render.value/missing-root-identity-error
                   :seon.render.value/insufficient-floor-budget-error]]}
  [unit]
  (let [projection (project unit :seon.render/html)]
    (if (:seon.render.value/tree projection) (:seon.render.value/html projection) projection)))
