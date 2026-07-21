(ns seon.render.value
  "Structural value renderer — the render-twin applied to EVERY eval value.

   Where `seon.render` resolves a fn-per-surface for ENTITIES, this ns
   renders the RAW VALUE an eval returned, on the same two surfaces:

   - `render-ai`        → the agent-facing TEXT: a clipped but
                          structure-revealing skeleton the agent can
                          navigate with ordinary Clojure
                          (`(get-in result/<id> …)`, filter, count)
                          WITHOUT re-querying.
   - `render-html-data` → the DATA CONTRACT the interactive HTML panel
                          (UI session U's lane) consumes to build a
                          collapsible drill-down browser. This ns produces
                          PLAIN DATA only — no hiccup, no web classes.

   ## Why a sampler, not pr-str + char-clip

   The live eval path today (`seon.eval/render-result-edn`) projects opaque
   handles, then `pr-str`s, then CHAR-clips the string mid-token. A mid-token
   clip yields invalid EDN AND destroys the navigation metadata an agent
   needs to form a `get-in` path (research: Clojure REPL Data Sampling,
   §'Technical Constraints of Naive Truncation'). This ns instead builds a
   DEPTH- and BREADTH-bounded SKELETON of the value. Every retained vector
   node keeps its real index. Retained map keys keep their original value only
   when the closed drill codec admits them; display-only keys are named by
   output-local metadata and never masquerade as paths.

   ## What the skeleton preserves

   - navigation paths — admitted map keys + vector indices stay intact
     (`get-in`/`nth`); display-only map keys are marked non-drillable
   - per-node TYPE + COUNT — `{…12 keys}`, `[…96 items]`, `#{…5 items}`
   - elision markers — `… +129 more`, plus, for a HOMOGENEOUS collection of
     maps, the shared key-set: `… +129 more each {:a :b :c}` (the column
     set of a 137-row query result, without scrolling 137 rows)
   - lazy-safe HEAD sampling — never realizes more than `max-items`+1 of a
     lazy/infinite seq
   - opaque handles — a datahike DB/Datom/Entity, a record, or a raw JS
     object becomes a compact tagged token, never a multi-KB index blob

   ## Marker vocabulary (the html DATA CONTRACT)

   The skeleton is plain data; non-plain nodes become reserved-namespaced
   marker maps the emitter + the U panel both read:

     {:seon.render.value/kind :vector|:set|:seq
      :seon.render.value/shown   [...]            ; bounded sample
      :seon.render.value/elided  n | :more        ; tail count (or :more)
      :seon.render.value/shape   [:k …]}          ; shared keys, if homogeneous
     {:seon.render.value/map-entries [[<k> <v>] …] ; map w/ elided tail
      :seon.render.value/elided-keys n
      :seon.render.value/non-drillable-key-indexes [0 3]}
     {:seon.render.value/pruned :map|:vector|:set|:seq
      :seon.render.value/count  n}                 ; depth-limit prune
     {:seon.render.value/string-len n :seon.render.value/head \"…\"}
     {:seon.eval/opaque \"datahike/DB\" :seon.eval/summary \"max-tx=42\"}
     {:seon.eval/datom [e a v]}

   (The opaque/datom markers use the `:seon.eval/opaque|datom` reserved
   keys so the whole system speaks one vocabulary. The opaque-DETECTION +
   projection logic lives ONLY here; `seon.eval` requires this ns for both
   `render-ai` and `project-plain` — see ns-end note.)"
  (:require
    [clojure.string :as str]
    [malli.error :as me]
    [seon.ai.tokens :as tokens]
    #?(:cljs [seon.config :as config])
    [seon.schema :as schema]))

;; The eval loop can allocate a different id on retry. Preparation therefore
;; owns every touch of the raw value; formatting owns only the later id. These
;; are transient rendering contracts, not stored entities.
(schema/register! ::value :any)
#?(:cljs
   (do
     (schema/register! ::eval-id :string)
     (schema/register! ::body :string)
     (schema/register! ::top-type-size :string)
     (schema/register! ::string-token-estimate [:int {:min 0}])
     (schema/register! ::render-error-message :string)
     (schema/register! ::drill-hint
                       [:map {:closed true}
                        [::top-type-size {:optional true} ::top-type-size]])
     (schema/register! ::prepared-complete
                       [:map {:closed true} [::body ::body]])
     (schema/register! ::prepared-drill
                       [:map {:closed true}
                        [::body ::body] [::drill-hint ::drill-hint]])
     (schema/register! ::prepared-string-partial
                       [:map {:closed true}
                        [::body ::body]
                        [::string-token-estimate ::string-token-estimate]])
     (schema/register! ::prepared-error
                       [:map {:closed true}
                        [::body ::body]
                        [::render-error-message ::render-error-message]])
     (schema/register! ::prepared-ai
                       [:or ::prepared-drill ::prepared-string-partial
                        ::prepared-error ::prepared-complete])
     (schema/register! ::prepare-ai-request
                       [:map {:closed true}
                        [:seon.config/configuration :seon.config/singleton]
                        [::value ::value]])
     (schema/register! ::format-ai-request
                       [:map {:closed true}
                        [::eval-id ::eval-id]
                        [::prepared ::prepared-ai]])))

;; Value-drill wire contracts stay producer-neutral and pure EDN. Recursive
;; sampler invariants are checked by bounded public validators at the later
;; producer/transport boundaries; registered forms deliberately carry only a
;; shallow data shape, never an application predicate or executable object.
(schema/register! ::path-segment
                  [:or :nil :boolean :int :double :string :keyword :symbol])
(schema/register! ::path [:vector ::path-segment])
(schema/register! ::offset [:int {:min 0 :max 9007199254740991}])
(schema/register! ::page-size [:int {:min 1 :max 9007199254740991}])
(schema/register! ::bounded-vector 'vector?)
(schema/register! ::bounded-map 'map?)
(schema/register! ::bounded-data
                  [:or :nil :boolean :int :double :string :keyword :symbol
                   ::bounded-vector ::bounded-map])
(schema/register! ::operation-limits
                  [:map {:closed true}
                   [:seon.config.render/value-max-path-segments
                    {:optional true} :seon.config/cap]
                   [:seon.config.render/value-max-path-bytes
                    {:optional true} :seon.config/cap]
                   [:seon.config.render/value-max-realized-items
                    {:optional true} :seon.config/cap]
                   [:seon.config.render/value-max-depth
                    {:optional true} :seon.config/cap]
                   [:seon.config.render/value-max-string
                    {:optional true} :seon.config/cap]
                   [:seon.config.render/value-shape-sample
                    {:optional true} :seon.config/cap]
                   [::page-size {:optional true} ::page-size]])
(schema/register! ::effective-limits
                  [:map {:closed true}
                   [:seon.config.render/value-max-path-segments :seon.config/cap]
                   [:seon.config.render/value-max-path-bytes :seon.config/cap]
                   [:seon.config.render/value-max-realized-items :seon.config/cap]
                   [:seon.config.render/value-max-depth :seon.config/cap]
                   [:seon.config.render/value-max-string :seon.config/cap]
                   [:seon.config.render/value-shape-sample :seon.config/cap]
                   [::page-size ::page-size]])
(schema/register! ::limit-normalization-request
                  [:map {:closed true}
                   [:seon.config/configuration :seon.config/singleton]
                   [::operation-limits {:optional true} ::operation-limits]])
(schema/register! ::drill-request
                  [:map {:closed true}
                   [::path ::path]
                   [::offset ::offset]
                   [::effective-limits ::effective-limits]])
(schema/register! ::schema-status [:enum :valid :invalid :shape-only])
(schema/register! ::status ::schema-status)
(schema/register! ::schema-status-row
                  [:map {:closed true}
                   [:seon.schema/key :keyword]
                   [:seon.schema/entity? :boolean]
                   [::status ::schema-status]])
(schema/register! ::schema-statuses [:vector ::schema-status-row])
(schema/register! ::humanized ::bounded-data)
(schema/register! ::error-value ::bounded-data)
(schema/register! ::explanation
                  [:map {:closed true}
                   [::humanized ::humanized]
                   [::error-value ::error-value]])
(schema/register! ::summary [:string {:min 1}])
(schema/register! ::truncated? :boolean)
(schema/register! ::more? :boolean)
(schema/register! ::tree ::bounded-data)
(schema/register! ::schemas ::schema-statuses)
(schema/register! ::drilled-projection
                  [:map {:closed true}
                   [::path ::path]
                   [::offset ::offset]
                   [::page-size ::page-size]
                   [::summary ::summary]
                   [::truncated? ::truncated?]
                   [::more? ::more?]
                   [::tree ::tree]
                   [::schemas ::schemas]
                   [::explanation {:optional true} ::explanation]])
(schema/register! ::ok? :boolean)
(schema/register! ::availability [:enum :available :unavailable])
(schema/register! ::recompute? :boolean)
(schema/register! ::drill-error
                  [:map {:closed true}
                   [:seon.error/message [:string {:min 1}]]
                   [:seon.error/kind :keyword]
                   [:seon.error/data {:optional true} ::bounded-data]])
(schema/register! ::available-result
                  [:map {:closed true}
                   [::ok? [:= true]]
                   [::availability [:= :available]]
                   [::projection ::drilled-projection]])
(schema/register! ::unavailable-result
                  [:map {:closed true}
                   [::ok? [:= true]]
                   [::availability [:= :unavailable]]
                   [::projection ::drilled-projection]
                   [::recompute? [:= true]]])
(schema/register! ::failed-result
                  [:map {:closed true}
                   [::ok? [:= false]]
                   [:seon/error ::drill-error]])
(schema/register! ::drill-result
                  [:or ::available-result ::unavailable-result ::failed-result])

(defn safe-nonnegative-int?
  "True when `x` is a non-negative safe integer."
  {:malli/schema [:=> [:catn [::candidate ::value]] :boolean]}
  [x]
  (and (number? x)
       #?(:clj (and (integer? x)
                    (<= -9007199254740991 x 9007199254740991))
          :cljs (js/Number.isSafeInteger x))
       #?(:clj (not (and (number? x)
                         (zero? x)
                         (neg? (Double/doubleToRawLongBits (double x)))))
          :cljs (not (js/Object.is x (js/Number "-0"))))
       (<= 0 x)))

(defn safe-positive-int?
  "True when `x` is a positive safe integer."
  {:malli/schema [:=> [:catn [::candidate ::value]] :boolean]}
  [x]
  (and (safe-nonnegative-int? x) (pos? x)))

(defn drill-path-segment?
  "True when `x` is an identity-stable scalar drill-path segment."
  {:malli/schema [:=> [:catn [::candidate ::value]] :boolean]}
  [x]
  (or (nil? x)
      (boolean? x)
      (string? x)
      (keyword? x)
      (symbol? x)
      (and (number? x)
           #?(:clj (Double/isFinite (double x))
              :cljs (js/Number.isFinite x))
           #?(:clj (not (and (zero? x)
                             (neg? (Double/doubleToRawLongBits (double x)))))
              :cljs (not (js/Object.is x (js/Number "-0")))))))

;; ============================================================
;; Sampling bounds — every one overridable by env (the `SEON_RENDER_VALUE_*`
;; sub-family) for token economy, read through `seon.config`.
;; ============================================================

#?(:cljs
   (defn- render-options [configuration]
     (let [max-keys (config/value-max-keys configuration)]
       {:max-depth      (config/value-max-depth configuration)
        :max-keys       max-keys
        :max-map-visits (* 4 max-keys)
        :max-items      (config/value-max-items configuration)
        :max-string     (config/value-max-string configuration)
        :shape-sample   (config/value-shape-sample configuration)})))

(defn- verbatim-probe-options
  "Generous bounds used ONLY to test — LAZY-SAFELY — whether `value` is small
   and fully plain before `pr-str`'ing it whole. `sample` realizes at most
   max-items+1 of any seq, so an untruncated probe PROVES the value is finite,
   opaque-free, and bounded: `pr-str` then cannot hang on a lazy/infinite seq
   nor blow up. Bounds far exceed anything a verbatim-cap-sized value reaches,
   so the char count stays the real gate."
  [verbatim-cap]
  {:max-depth 64 :max-keys 256 :max-map-visits 1024 :max-items 256
   :max-string verbatim-cap :shape-sample 8})

;; ============================================================
;; Opaque detection — the ONE home for "this node is not plain data,
;; project it to a compact marker." `seon.eval` requires this ns rather
;; than carrying its own copy. Per-node, only on VISITED nodes, so a giant
;; value is never fully walked.
;; ============================================================

(defn- datahike-handle?
  [x]
  (or (and (record? x) (some? (:max-tx x)))
      (and (not (map? x)) (not (record? x)) (not (seqable? x))
           (some? (:db/id x)))))

(defn- datom-shape? [x]
  (and (not (coll? x)) (not (record? x))
       (number? (:e x)) (keyword? (:a x))))

(defn- bounded-record-label
  "Compiler-owned record constructor label without invoking record printing."
  [x]
  (let [label #?(:clj (.getName (class x))
                 :cljs (let [ctor (type x)]
                         (or (.-cljs$lang$ctorStr ctor) (.-name ctor) "record")))]
    (subs label 0 (min 80 (count label)))))

(defn opaque?
  "True when `x` is a runtime handle rather than ordinary immutable data.

   Rendering projects these values to compact markers. The eval result store
   also uses this predicate at admission time so an opaque database, entity,
   compiler object, function, or host object cannot keep an arbitrary object
   graph alive behind `result/<id>`."
  {:malli/schema [:=> [:catn [::value :any]] :boolean]}
  [x]
  (boolean
    (or (datahike-handle? x)
        (record? x)
        (datom-shape? x)
        #?(:clj (and (some? x)
                     (not (or (coll? x) (string? x) (number? x)
                              (keyword? x) (symbol? x) (boolean? x)
                              (char? x) (uuid? x) (inst? x))))
           :cljs (object? x))
        (fn? x))))

(def retained-value-cap
  "Maximum live eval-value slots retained by either serving runtime."
  200)

(def ^:private retained-node-cap 4096)
(def ^:private retained-weight-cap (* 256 1024))

(defn- retained-rejection [reason nodes weight summary]
  {:seon.eval/retained? false
   :seon.eval/retained-reason reason
   :seon.eval/retained-observed-nodes nodes
   :seon.eval/retained-observed-weight weight
   :seon.eval/retained-node-cap retained-node-cap
   :seon.eval/retained-weight-cap retained-weight-cap
   :seon.eval/retained-summary summary
   :seon.eval/retained-recovery
   "Use a narrower seon.db/query, seon.db/pull, or seon.db/index-datoms request; persist intentional large text incrementally with my.blob/put!."})

(defn- retained-byte-length [x]
  #?(:cljs
     (cond
       (instance? js/ArrayBuffer x) (.-byteLength x)
       (and (exists? js/ArrayBuffer) (.isView js/ArrayBuffer x))
       (.-byteLength x)
       :else nil)
     :clj
     (cond
       (= (class x) (class (byte-array 0))) (alength ^bytes x)
       (instance? java.nio.ByteBuffer x)
       (.remaining ^java.nio.ByteBuffer x)
       :else nil)))

(defn admit-retained-value
  "Return `v` unchanged only when bounded structural retention admits it."
  {:malli/schema [:=> [:catn [::value ::value]] ::value]}
  [v]
  (try
    (let [seen #?(:cljs (js/WeakSet.) :clj (java.util.IdentityHashMap.))
          seen? #?(:cljs (fn [x] (.has seen x))
                   :clj (fn [x] (.containsKey seen x)))
          mark! #?(:cljs (fn [x] (.add seen x))
                   :clj (fn [x] (.put seen x true)))]
      (loop [work [v] nodes 0 weight 0]
        (if (empty? work)
          v
          (let [x (peek work)
                work (pop work)
                bytes (retained-byte-length x)
                reference? (or (coll? x) (some? bytes)
                               #?(:cljs (object? x) :clj false))]
            (if (and reference? (seen? x))
              (recur work nodes weight)
              (let [_ (when reference? (mark! x))
                    nodes' (inc nodes)
                    weight' (+ weight (cond (string? x) (count x)
                                            (some? bytes) bytes
                                            :else 1))]
                (cond
                  (> nodes' retained-node-cap)
                  (retained-rejection
                   :seon.eval/node-cap-exceeded nodes' weight'
                   "result was not retained because its structure exceeds the live-result node budget")

                  (> weight' retained-weight-cap)
                  (retained-rejection
                   :seon.eval/weight-cap-exceeded nodes' weight'
                   "result was not retained because its shallow weight exceeds the live-result budget")

                  (some? bytes) (recur work nodes' weight')

                  (opaque? x)
                  (retained-rejection
                   :seon.eval/opaque-value nodes' weight'
                   "result was not retained because it contains an opaque runtime handle")

                  (and (coll? x) (not (counted? x)))
                  (retained-rejection
                   :seon.eval/unbounded-collection nodes' weight'
                   "result was not retained because it contains a lazy or unbounded collection")

                  (map? x)
                  (let [metadata (meta x)
                        child-count (+ (* 2 (count x)) (if (seq metadata) 1 0))]
                    (if (> (+ nodes' (count work) child-count) retained-node-cap)
                      (retained-rejection
                       :seon.eval/node-cap-exceeded (inc retained-node-cap) weight'
                       "result was not retained because its structure exceeds the live-result node budget")
                      (recur (cond-> (reduce-kv (fn [stack k child]
                                                 (conj stack k child))
                                               work x)
                               (seq metadata) (conj metadata))
                             nodes' weight')))

                  (coll? x)
                  (let [metadata (meta x)
                        child-count (+ (count x) (if (seq metadata) 1 0))]
                    (if (> (+ nodes' (count work) child-count) retained-node-cap)
                      (retained-rejection
                       :seon.eval/node-cap-exceeded (inc retained-node-cap) weight'
                       "result was not retained because its structure exceeds the live-result node budget")
                      (recur (cond-> (reduce conj work x)
                               (seq metadata) (conj metadata))
                             nodes' weight')))

                  :else (recur work nodes' weight'))))))))
    (catch #?(:clj Throwable :cljs :default) e
      (let [message #?(:clj (or (.getMessage ^Throwable e) (str e))
                       :cljs (or (some-> e .-message) (str e)))]
        (retained-rejection
         :seon.eval/inspection-failed 0 0
         (str "result was not retained because bounded structural inspection failed: "
              (subs message 0 (min 160 (count message)))))))))

(defn- opaque-marker
  "Reader-safe marker for one non-plain node. Never throws."
  [x project-child]
  (try
    (cond
      (datom-shape? x)
      {:seon.eval/datom [(:e x) (:a x) (project-child (:v x))]}

      (and (record? x) (some? (:max-tx x)))
      (cond-> {:seon.eval/opaque "datahike/DB"}
        (number? (:max-tx x))
        (assoc :seon.eval/summary
               (str "max-tx=" (:max-tx x)
                    (when (number? (:max-eid x))
                      (str " max-eid=" (:max-eid x))))))

      (and (not (map? x)) (not (record? x)) (some? (:db/id x)))
      (let [eid (:db/id x)]
        (cond-> {:seon.eval/opaque "datahike/Entity"}
          (or (number? eid) (keyword? eid) (string? eid))
          (assoc :seon.eval/summary
                 (str ":db/id=" (tokens/bounded-pr-str eid 20)))))

      (record? x)
      {:seon.eval/opaque (bounded-record-label x)}

      (fn? x)
      {:seon.eval/opaque "fn"}

      #?(:clj (and (some? x)
                   (not (or (coll? x) (string? x) (number? x)
                            (keyword? x) (symbol? x) (boolean? x)
                            (char? x) (uuid? x) (inst? x))))
         :cljs (object? x))
      {:seon.eval/opaque #?(:clj "jvm/Object" :cljs "js/Object")}

      :else
      {:seon.eval/opaque "unknown"})
    (catch #?(:clj Throwable :cljs :default) _
      {:seon.eval/opaque "unknown" :seon.eval/summary "<unprintable>"})))

(declare project-plain)

(defn- project-plain*
  [value]
  (cond
    ;; opaque handles FIRST — a datahike DB is also map?/coll?.
    (opaque? value) (opaque-marker value project-plain*)

    ;; plain map — recurse over keys AND values.
    (map? value)
    (reduce-kv (fn [m k vv] (assoc m (project-plain k) (project-plain vv))) {} value)

    ;; plain vector/set/list/seq — recurse element-wise (lazy/seq → vector
    ;; for a finite, reader-safe shape).
    (coll? value)
    (if (seq? value)
      (mapv project-plain value)
      (into (empty value) (map project-plain) value))

    :else value))

(defn project-plain
  "Recursively project VALUE into reader-safe PLAIN DATA.

   Every non-plain
   node (datahike DB/Datom/Entity, record, JS object, fn) becomes a compact
   marker map; plain scalars (incl. #inst/#uuid) and collections survive,
   walked element-wise. Unbounded — the full structure is preserved, only
   opaque nodes are summarized; pair it with `pr-str` for a round-trippable
   string. (`sample` is the BOUNDED variant for agent display.) Never
   throws — a walk failure degrades to the opaque marker for that node."
  {:malli/schema [:=> [:catn [:seon.render.value/value :any]] :any]}
  [value]
  (try (project-plain* value)
       (catch #?(:clj Throwable :cljs :default) _
         (opaque-marker value project-plain*))))

;; ============================================================
;; SAMPLE — depth + breadth bounded skeleton of plain data + markers.
;; ============================================================

(defn- counted-count [coll]
  (when (counted? coll) (count coll)))

(defn- shared-keys
  "Sorted UNION of the keys of the first `shape-sample` items WHEN they are
   all maps — the column set a homogeneous collection of rows shares. nil
   otherwise. Bounded: never realizes past `shape-sample` items."
  [items shape-sample]
  (let [sample (take shape-sample items)]
    (when (and (seq sample) (every? map? sample))
      (->> sample (map (comp set keys)) (reduce into #{}) (sort-by str) vec))))

(defn- clip-string [s max-string]
  (if (> (count s) max-string)
    {:seon.render.value/string-len (count s)
     :seon.render.value/head       (subs s 0 (max 0 (dec max-string)))}
    s))

(defn- drillable-map-key?
  "Whether a retained original key belongs to the closed drill scalar codec."
  [k max-string]
  (or (nil? k)
      (boolean? k)
    (and (number? k)
           #?(:clj (Double/isFinite (double k))
              :cljs (js/Number.isFinite k))
           #?(:clj (not (and (zero? k)
                             (neg? (Double/doubleToRawLongBits (double k)))))
              :cljs (not (and (zero? k) (= js/-Infinity (/ 1 k))))))
      (and (string? k) (<= (count k) max-string))
      (and (or (keyword? k) (symbol? k))
           (<= (+ (count (name k))
                  (if-some [ns (namespace k)] (inc (count ns)) 0))
               max-string))))

(defn- map-key-projection
  "Bounded display projection plus whether the original key is non-drillable."
  [k {:keys [max-string]}]
  (cond
    (opaque? k)
    [(opaque-marker k identity) true]

    (and (string? k) (> (count k) max-string))
    [{:seon.eval/opaque "map-key/string"
      :seon.eval/summary (tokens/bounded-pr-str k 20)} true]

    (or (keyword? k) (symbol? k))
    (let [n (+ (count (name k))
               (if-some [ns (namespace k)] (inc (count ns)) 0))]
      (if (> n max-string)
        [{:seon.eval/opaque (str "map-key/" (if (keyword? k) "keyword" "symbol"))
          :seon.eval/summary (tokens/bounded-pr-str k 20)} true]
        [k false]))

    (coll? k)
    [{:seon.eval/opaque "map-key/collection"} true]

    (drillable-map-key? k max-string)
    [k false]

    (number? k)
    [{:seon.eval/opaque "map-key/number"
      :seon.eval/summary (tokens/bounded-pr-str k 20)} true]

    :else
    [{:seon.eval/opaque "map-key/unsupported"} true]))

(defn- named-scalar-marker
  "Bounded marker for a huge keyword or symbol, otherwise nil."
  [x max-string]
  (when (or (keyword? x) (symbol? x))
    (let [n (+ (count (name x))
               (if-some [ns (namespace x)] (inc (count ns)) 0))]
      (when (> n max-string)
        {:seon.eval/opaque (if (keyword? x) "keyword" "symbol")
         :seon.eval/summary (tokens/bounded-pr-str x 20)}))))

;; ============================================================
;; Explicit-whitespace rendering — the CENTRAL capability for surgical edits
;; (transcript-render redesign). Display precision ⟂ match precision: the
;; glyphs are DISPLAY-only (the live value behind result/<id> is the real
;; bytes). Every knob DEFAULTS off, so the fast path returns `s` unchanged —
;; byte-identical to today. Reads `seon.config` once per call.
;; ============================================================

#?(:cljs
   (defn- whitespace-active?
  "True iff any explicit-whitespace knob is off its default — the ONLY case
   where [[visible-whitespace]] diverges from `s`. Lets a caller bypass the
   pr-str/quote path for a string value ONLY when the operator asked for it,
   so the default render is byte-identical to today."
  [configuration]
  (not (and (= (config/render-whitespace configuration) :raw)
            (= (config/render-tabs configuration) :literal)
            (= (config/render-trailing-ws configuration) :off)
            (not (config/render-line-numbers? configuration))))))

(defn- mark-trailing-ws
  "Glyph only the TRAILING whitespace run of one line (`·` per space, `→` per
   tab); interior bytes untouched. For `:trailing-ws :dot` (surface a trailing
   space a diff would otherwise hide) without recoloring every space."
  [line]
  (str/replace line #"[ \t]+$"
               (fn [m] (-> m (str/replace " " "·") (str/replace "\t" "→")))))

#?(:cljs
   (defn visible-whitespace
  "Render explicit-whitespace glyphs on string content `s` per the render
   config — the one central place tab/space/indent/trailing-ws become visible.

   `:whitespace :visible` → every space `·`, every tab `→`; `:tabs :arrow`
   arrows tabs alone; `:trailing-ws :dot` marks only trailing whitespace;
   `:line-numbers true` prepends a 1-based gutter. All knobs off (the default)
   short-circuits to `s` unchanged — byte-identical to today. DISPLAY only: the
   value behind `result/<id>` is the real bytes the agent matches against."
  {:malli/schema [:=> [:catn [:seon.config/configuration :seon.config/singleton]
                             [:seon.render.value/content :string]] :string]}
  [configuration s]
  (let [ws     (config/render-whitespace configuration)
        tabs   (config/render-tabs configuration)
        trail  (config/render-trailing-ws configuration)
        lines? (config/render-line-numbers? configuration)]
    (if (and (= ws :raw) (= tabs :literal) (= trail :off) (not lines?))
      s
      (->> (str/split s #"\n" -1)
           (map-indexed
             (fn [i line]
               (let [line* (cond-> line
                             (= ws :visible)                  (str/replace " " "·")
                             (or (= ws :visible)
                                 (= tabs :arrow))             (str/replace "\t" "→")
                             (and (not= ws :visible)
                                  (= trail :dot))             mark-trailing-ws)]
                 (if lines? (str (inc i) "  " line*) line*))))
           (str/join "\n"))))))

(declare sample*)

(defn- exception-message [e]
  #?(:clj (.getMessage ^Throwable e)
     :cljs (.-message e)))

(defn- sample-seqish
  "Breadth + lazy-safe element sampling of a vector/set/seq. Realizes at
   most `max-items`+1 elements to detect overflow without forcing a huge or
   infinite seq. Order preserved.

   Realization is GUARDED. Forcing the head of a LAZY seq can THROW — an
   agent trivially builds one, e.g. `(keys non-map)` returns a `KeySeq`
   whose `-first` calls `(key elem)` on a non-map-entry, or `(map #(throw …)
   xs)`. The eval that returns such a value records `ok? true` (lazy, never
   forced); only [[sample]] forcing it here throws. `sample` PROMISES it
   never throws, so a poisoned realization degrades to an opaque marker
   naming the cause, exactly like any other non-plain node. `render-ai` adds
   an outer totality net for an unforeseen realization site; its caller may
   run inside an allocator retry and therefore cannot report side effects."
  [coll {:keys [max-items shape-sample] :as opts} depth kind]
  (let [forced (try {::head+1 (vec (take (inc max-items) coll))}
                 (catch #?(:clj Throwable :cljs :default) e
                   {::realize-error e}))]
    (if-some [e (::realize-error forced)]
      {:seon.eval/opaque  (str (name kind) " realization threw")
       :seon.eval/summary (tokens/clip-str
                            (or (exception-message e) (str e)) 60)}
      (let [head+1 (::head+1 forced)
            over?  (> (count head+1) max-items)
            shown  (mapv #(sample* % opts (inc depth)) (take max-items head+1))
            total  (counted-count coll)
            elided (cond (not over?) 0
                         total       (- total max-items)
                         :else       :more)
            shape  (when over? (shared-keys coll shape-sample))]
        (cond-> {:seon.render.value/kind  kind
                 :seon.render.value/shown shown}
          (not= elided 0) (assoc :seon.render.value/elided elided)
          shape           (assoc :seon.render.value/shape shape))))))

(defn- sample*
  [x {:keys [max-depth max-keys max-map-visits max-string] :as opts} depth]
  (cond
    ;; opaque handles FIRST — a datahike DB is also map?/coll?.
    (opaque? x) (opaque-marker x #(sample* % opts (inc depth)))

    (string? x) (clip-string x max-string)

    (or (keyword? x) (symbol? x))
    (or (named-scalar-marker x max-string) x)

    ;; depth limit — prune NON-EMPTY nested colls to a typed+counted marker
    ;; so the agent still sees "a map of 12 keys lives here" and can drill
    ;; it. Empty colls are tiny, render verbatim (fall through).
    (and (>= depth max-depth) (coll? x) (seq x))
    {:seon.render.value/pruned (cond (map? x) :map (set? x) :set
                                     (vector? x) :vector :else :seq)
     :seon.render.value/count  (counted-count x)}

    (map? x)
    ;; Inspect a bounded candidate window plus one unsampled tail sentinel.
    ;; Ranking the entire map by rendered size made output small only AFTER
    ;; recursively visiting every value. The explicit visit budget preserves
    ;; the useful small-value preference within a bounded window. Retained
    ;; entries stay in the immutable map's iteration order, so repeated renders
    ;; are byte-stable. Only keys admitted by the closed scalar codec remain
    ;; drill paths; the rest become bounded display markers.
    (let [visit-limit (max max-keys (or max-map-visits max-keys))
          candidates+1 (into [] (take (inc visit-limit)) x)
          candidates (take visit-limit candidates+1)
          sampled  (mapv (fn [[k v]]
                           (let [[display-key projected?] (map-key-projection k opts)]
                             [display-key (sample* v opts (inc depth)) projected?]))
                         candidates)
          ranked   (sort-by (fn [[i [_ sv _]]]
                              [(count (tokens/bounded-pr-str sv 20)) i])
                            (map-indexed vector sampled))
          keep-idx (into #{} (map first) (take max-keys ranked))
          kept     (into []
                         (keep-indexed
                           (fn [i kv] (when (contains? keep-idx i) kv)))
                         sampled)
          [kept non-drillable-key-indexes]
          (reduce-kv
            (fn [[entries indexes] output-index [k v non-drillable?]]
              [(conj entries [k v])
               (cond-> indexes non-drillable? (conj output-index))])
            [[] []]
            kept)
          total    (counted-count x)
          over-window? (> (count candidates+1) visit-limit)
          elided   (cond
                     total (max 0 (- total (count kept)))
                     over-window? :more
                     :else (max 0 (- (count candidates) (count kept))))]
      (cond-> {:seon.render.value/map-entries (vec kept)}
        (not= 0 elided) (assoc :seon.render.value/elided-keys elided)
        (seq non-drillable-key-indexes)
        (assoc :seon.render.value/non-drillable-key-indexes
               non-drillable-key-indexes)))

    (vector? x) (sample-seqish x opts depth :vector)
    (set? x)    (sample-seqish x opts depth :set)
    (coll? x)   (sample-seqish x opts depth :seq)

    :else x))

#?(:cljs
   (defn sample
  "Depth + breadth bounded SKELETON of `x` (plain data + marker maps).
   `opts` overrides `default-opts`. Admitted map keys and vector indices are
   preserved as paths; display-only map keys carry output-local non-drillable
   metadata. Lazy-safe; never throws."
  {:malli/schema [:=> [:catn [:seon.config/configuration
                              :seon.config/singleton]
                             [:seon.render.value/x :any]
                             [:seon.render.value/opts :map]] :any]}
  [configuration x opts]
  (let [opts (merge (render-options configuration) opts)]
     (sample* x (update opts :max-map-visits
                        #(max (:max-keys opts) (or % (:max-keys opts)))) 0))))

;; ============================================================
;; EMIT — render the skeleton to structure-revealing comment text.
;; Markers become compact tokens; scalars via pr-str; collections print
;; INLINE when they fit a width budget, else break one child per line.
;; ============================================================

(defn- leaf-marker? [x]
  (and (map? x)
       (or (:seon.eval/opaque x)
           (:seon.eval/datom x)
           (:seon.render.value/string-len x)
           (:seon.render.value/pruned x))))

(defn- truncated?
  "Any marker present in the skeleton = a partial view (something elided,
   pruned, clipped, or opaque). Drives the trailing drill hint."
  [skel]
  (boolean
    (some #(and (map? %)
                (or (contains? % :seon.render.value/elided)
                    (and (contains? % :seon.render.value/map-entries)
                         (not= 0 (or (:seon.render.value/elided-keys %) 0)))
                    (seq (:seon.render.value/non-drillable-key-indexes %))
                    (contains? % :seon.render.value/pruned)
                    (contains? % :seon.eval/opaque)
                    (contains? % :seon.eval/datom)
                    (contains? % :seon.render.value/string-len)))
          (tree-seq coll? #(if (map? %) (vals %) (seq %)) skel))))

(defn- ind [depth] (apply str (repeat depth "  ")))

(declare emit-inline)

(defn datom-token
  "Format one sampled datom marker as its canonical leaf token."
  {:malli/schema [:=> [:cat :map] :string]}
  [x]
  (let [[e a v] (:seon.eval/datom x)]
    (str "#datom[" (tokens/bounded-pr-str e 20) " "
         (tokens/bounded-pr-str a 20) " " (emit-inline v) "]")))

(defn opaque-token
  "Format one sampled opaque marker as its canonical leaf token."
  {:malli/schema [:=> [:cat :map] :string]}
  [x]
  (str "#‹" (:seon.eval/opaque x)
       (when-some [s (:seon.eval/summary x)] (str " " s)) "›"))

(defn pruned-token
  "Format one sampled pruned marker as its canonical leaf token."
  {:malli/schema [:=> [:cat :map] :string]}
  [x]
  (let [k (:seon.render.value/pruned x)
        c (:seon.render.value/count x)
        [o cl] (case k :map ["{" "}"] :set ["#{" "}"]
                     :vector ["[" "]"] ["(" ")"])
        unit (if (= k :map) "keys" "items")]
    (str o "…" (when c (str c " " unit)) cl)))

(defn clipped-string-token
  "Format one sampled clipped string as its canonical leaf token."
  {:malli/schema [:=> [:cat :map] :string]}
  [x]
  (str (pr-str (str (:seon.render.value/head x) "…"))
       "⟨" (tokens/chars->tokens (:seon.render.value/string-len x)) " tokens⟩"))

(defn- emit-leaf [x]
  (cond
    (:seon.eval/datom x)                  (datom-token x)
    (:seon.eval/opaque x)                 (opaque-token x)
    (:seon.render.value/pruned x)         (pruned-token x)
    (:seon.render.value/string-len x)     (clipped-string-token x)))

(defn- map-parts [m]
  (let [wrapped? (contains? m :seon.render.value/map-entries)
        elided   (when wrapped? (:seon.render.value/elided-keys m))
        non-drillable (when wrapped?
                        (:seon.render.value/non-drillable-key-indexes m))
        entries  (if wrapped?
                   (:seon.render.value/map-entries m)
                   m)]
    [(map (fn [[k v]] [(tokens/bounded-pr-str k 20) v]) entries)
     (str/join " · "
               (remove nil?
                       [(when elided
                          (if (= :more elided)
                            "… +more keys"
                            (str "… +" elided " more keys")))
                        (when (seq non-drillable)
                          (str (count non-drillable) " non-drillable key"
                               (when (not= 1 (count non-drillable)) "s")
                               " shown safely"))]))]))

(defn- seqish-parts [m]
  (let [{:seon.render.value/keys [shown elided shape]} m]
    [shown
     (str/join " "
       (remove nil?
         [(cond (nil? elided) nil
                (= :more elided) "… +more"
                :else (str "… +" elided " more"))
          (when shape (str "each {" (str/join " " (map pr-str shape)) "}"))]))]))

(declare emit)

(defn- emit-inline [x]
  (cond
    (leaf-marker? x) (emit-leaf x)
    (:seon.render.value/kind x)
    (let [[open close] (case (:seon.render.value/kind x)
                         :vector ["[" "]"] :set ["#{" "}"] ["(" ")"])
          [elems tail] (seqish-parts x)]
      (str open (str/join " " (concat (map emit-inline elems)
                                      (when (seq tail) [tail]))) close))
    (map? x)
    (let [[pairs tail] (map-parts x)]
      (str "{" (str/join ", " (concat (map (fn [[k v]] (str k " " (emit-inline v))) pairs)
                                      (when tail [tail]))) "}"))
    :else (pr-str x)))

(defn- fits? [x depth width]
  (let [s (emit-inline x)]
    (and (not (str/includes? s "\n"))
         (<= (+ (count s) (* 2 depth)) width))))

(defn- emit
  "Render skeleton node `x` at `depth`; inline when it fits, else break one
   child per line."
  [x depth width]
  (cond
    (leaf-marker? x) (emit-leaf x)
    (fits? x depth width)  (emit-inline x)

    (:seon.render.value/kind x)
    (let [[open close] (case (:seon.render.value/kind x)
                         :vector ["[" "]"] :set ["#{" "}"] ["(" ")"])
          [elems tail] (seqish-parts x)
          sep (str "\n" (ind (inc depth)))]
      (str open
           (str/join sep (map #(emit % (inc depth) width) elems))
           (when (seq tail) (str sep tail))
           close))

    (map? x)
    (let [[pairs tail] (map-parts x)
          sep (str "\n" (ind (inc depth)))]
      (str "{"
           (str/join sep
                     (map (fn [[k v]]
                            (str k " " (emit v (inc depth) width)))
                          pairs))
           (when tail (str sep tail))
           "}"))

    :else (pr-str x)))

;; ============================================================
;; RENDER-AI — the agent-facing text. The bounded skeleton FIRST (so it
;; composes cleanly behind the transcript's `; ⟹` prefix — no `; ⟹ ;;`
;; double-comment), then, ONLY when the view is partial, ONE trailing `;`
;; hint folding the top-level type/count + a drill pointer at the live var.
;; ============================================================

(defn- top-type+size [x]
  (when (and (coll? x) (not (record? x)))
    (let [t (cond (map? x) "map" (vector? x) "vector" (set? x) "set"
                  (seq? x) "seq" :else "coll")
          ;; Counting a non-counted seq REALIZES up to 1001 elements — a
          ;; poisoned lazy seq (see [[sample-seqish]]) throws when forced, and
          ;; this runs OUTSIDE sample's guard. Guard it: an unknown size is a
          ;; `?`, never a propagated throw (the sample marker already carries
          ;; the realization error; this is only the drill-hint's size hint).
          n (if (counted? x)
              (count x)
              (try (str "≥" (count (take 1001 x)))
                (catch #?(:clj Throwable :cljs :default) _ "?")))]
      (str t " " n (if (map? x) " keys" " items")))))

(def ^:private dominant-string-fraction
  "A map value that occupies at least this fraction of the map's rendered
   size — and is a plain string longer than the inline `max-string` cap —
   is the map's PAYLOAD: it renders as a body block (bounded by the generous
   `verbatim-cap`, honest ⟨N tokens⟩) with the small keys as the header,
   instead of being elided to a 2-line stub. Shape-general — no function names."
  0.70)

(defn- dominant-string-entry
  "When VALUE is a small map (≤ `max-keys`, so every key already renders)
   whose rendered size is DOMINATED (≥ `dominant-string-fraction`) by ONE
   plain-string value longer than the inline `max-string` cap, return that
   `[k s]` entry; nil otherwise. The winner must be a STRING — a map
   dominated by a big collection falls to the ordinary skeleton — with
   something to reveal (longer than the inline stub). This is what makes a
   read function's own payload (`view`'s content, a fetched body) VISIBLE instead
   of a stub, without any per-function special-casing."
  [value options max-string]
  (when (and (map? value) (not (record? value)) (seq value)
             (<= (count value) (:max-keys options)))
    ;; `(pr-str v)` sizes each RAW map value — a value that is a poisoned lazy
    ;; seq (see [[sample-seqish]]) throws when pr-str forces it. This is an
    ;; OPTIMIZATION (show a map's dominant string payload verbatim); if any
    ;; value can't be measured, fall back to the guarded skeleton — never
    ;; propagate (the throw here would crash the pod via render-result-edn).
    (try
      (let [sized   (map (fn [[k v]]
                           (let [[display-key _] (map-key-projection k options)]
                             [k v (+ (count (tokens/bounded-pr-str display-key 20))
                                   (if (string? v)
                                     (count v)
                                     (count (tokens/bounded-pr-str v 20))))]))
                         value)
            total   (reduce + (map peek sized))
            [k v n] (apply max-key peek sized)]
        (when (and (string? v)
                   (> (count v) max-string)
                   (pos? total)
                   (>= (/ n total) dominant-string-fraction))
          [k v]))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

#?(:cljs
   (defn- prepare-bounded-view
  "Prepare the bounded body and ID-independent drill facts for a value too
   large, deep, or opaque to print whole."
  [configuration value]
  (let [options (render-options configuration)
        verbatim-cap (config/value-verbatim-cap configuration)
        width (config/value-width configuration)
        dom   (dominant-string-entry value options (:max-string options))
        skel  (if-some [[dk s] dom]
                ;; re-clip only the dominant key's value to the body cap —
                ;; every retained get-in path stays valid; the header keys
                ;; render verbatim (all kept, since the map is ≤ max-keys).
                (update (sample configuration value {})
                        :seon.render.value/map-entries
                        (fn [entries]
                          (mapv (fn [[k v]]
                                  [k (if (= k dk)
                                       (clip-string s verbatim-cap)
                                       v)])
                                entries)))
                (sample configuration value {}))
        clip? (truncated? skel)
        body  (emit skel 0 width)
        tsz   (top-type+size value)]
    (cond-> {::body body}
       clip? (assoc ::drill-hint
                    (cond-> {}
                      tsz (assoc ::top-type-size tsz)))))))

#?(:cljs
   (defn prepare-ai
  "Prepare one raw eval value for agent-facing text.

   This is the ONLY phase allowed to realize, sample, print, or otherwise
   touch the raw value. The returned map is immutable, fully namespaced data
   with no reference to the original value, so an allocator may safely format
   it under any later eval id without repeating lazy effects."
  {:malli/schema [:=> [:cat ::prepare-ai-request] ::prepared-ai]}
  [{::keys [value] configuration :seon.config/configuration}]
  (let [verbatim-cap (config/value-verbatim-cap configuration)
        width (config/value-width configuration)]
    (try
      (cond
      ;; EXPLICIT-CHARACTER view of a STRING value (file content the agent
      ;; edits) — ONLY when an operator turned a whitespace knob on. Renders the
      ;; RAW bytes with visible glyphs instead of the quoted/escaped pr-str form,
      ;; so tab-vs-space is visible for building an exact `replace!` find. Gated:
      ;; at defaults this branch is skipped and the pr-str path below is unchanged
      ;; (byte-identical to today).
      (and (string? value) (whitespace-active? configuration))
      (if (<= (count value) verbatim-cap)
        {::body (visible-whitespace configuration value)}
        {::body
         (visible-whitespace configuration
                             (subs value 0 (max 0 (dec verbatim-cap))))
         ::string-token-estimate (tokens/estimate value)})

      :else
      (let [probe (sample configuration value
                          (verbatim-probe-options verbatim-cap))]
        (if (truncated? probe)
          (prepare-bounded-view configuration value)
          ;; Probe untruncated means the structure is finite and ordinary. The
          ;; capped printer remains the character-work gate, including huge
          ;; keyword/symbol/map-key scalars that structural breadth alone does
          ;; not bound.
          (let [{printed ::tokens/text
                 print-truncated? ::tokens/character-truncated?}
                (tokens/bounded-pr-str-result
                  value (quot (+ verbatim-cap 3) tokens/chars-per-token))]
            (if (and (not print-truncated?)
                     (<= (count printed) verbatim-cap))
              {::body printed}
              (prepare-bounded-view configuration value))))))
      (catch :default e
        {::body (emit (sample configuration value {}) 0 width)
         ::render-error-message
         (or (some-> e .-message) (str e))})))))

(defn format-ai
  "Format immutable prepared render data under one allocated eval id.

   This phase never sees the raw eval value. Reusing the same `::prepared`
   map with several ids therefore performs no further realization."
  {:malli/schema [:=> [:cat ::format-ai-request] :string]}
  [{::keys [eval-id prepared]}]
  (let [body (::body prepared)]
    (cond
      (contains? prepared ::render-error-message)
      (str body
           "\n; ‹value threw on render: " (::render-error-message prepared)
           "› — the live value is result/" eval-id)

      (contains? prepared ::string-token-estimate)
      (str body
           "\n; ‹partial view of " (::string-token-estimate prepared)
           " tokens› — the COMPLETE value is result/" eval-id)

      (contains? prepared ::drill-hint)
      (let [tsz (get-in prepared [::drill-hint ::top-type-size])
            id? (not (str/blank? eval-id))]
        ;; The drill hint teaches BOTH recovery (navigate the live var) AND
        ;; durability (`keep:` promotes the whole value to a content-addressed
        ;; blob that survives turns/prune). The keep idiom only renders when
        ;; an eval id names a live var to promote.
        (str body
             "\n; ‹partial view"
             (when tsz (str " of " tsz)) "› — the COMPLETE value is "
             "result/" eval-id
             (when id? (str " · keep: (my.blob/put! result/" eval-id ")"))
             "  (get-in result/" eval-id
             " […]) · filter · count · take/drop"))

      :else body)))

#?(:cljs
   (defn render-ai
  "Agent-facing TEXT for an eval value.

   `eval-id` names the live var the
   agent drills. A small value renders VERBATIM (like a REPL — full nesting,
   so the agent navigates its own stored data correctly next turn); a large /
   deep / opaque value renders as a bounded structural skeleton + ONE trailing
   hint line (top-level type/count + a drill pointer at `result/<id>`). The
   whole output is valid Clojure comment prose — no backticks, no fences — so
   it round-trips through the eval-able context.

   Convenience composition of [[prepare-ai]] and [[format-ai]]. Callers that
   allocate an eval id inside a retryable transaction prepare once OUTSIDE the
   retry, then invoke [[format-ai]] with the candidate id inside it."
  {:malli/schema [:=> [:catn [:seon.config/configuration
                              :seon.config/singleton]
                             [:seon.render.value/eval-id :string]
                             [:seon.render.value/value :any]]
                  :string]}
  [configuration eval-id value]
  (format-ai {::eval-id eval-id
              ::prepared (prepare-ai
                           {:seon.config/configuration configuration
                            ::value value})})))

;; ============================================================
;; RENDER-HTML-DATA — the DATA CONTRACT for the interactive drill-down
;; panel (UI session U's lane). PLAIN DATA only — no hiccup, no web
;; classes. U turns this into a collapsible browser.
;;
;; The tree IS the `sample` skeleton: every vector node carries its real index,
;; and every admitted map node its original key. Output-local metadata marks
;; display-only map keys, so the panel reconstructs a `get-in` PATH only from
;; drillable positions.
;; and EXPANDS a pruned/elided node by re-sampling `(get-in result/<id>
;; path)` one level deeper (the live value is `result/<id>`; expansion is a
;; fresh server `/call` — see the U coordination ask in the PRD note).
;; ============================================================

(defn- schema-status-row [status row]
  {:seon.schema/key (:seon.schema/key row)
   :seon.schema/entity? (:seon.schema/entity? row)
   :seon.render.value/status status})

(defn- schema-projection-in [projection value incomplete?]
  (if incomplete?
    {:seon.render.value/schemas
     (mapv #(schema-status-row :shape-only %)
           (schema/candidate-shapes-in projection value))}
    (let [matches (schema/matching-shapes-in projection value)]
      (if (seq matches)
        {:seon.render.value/schemas
         (mapv #(schema-status-row :valid %) matches)}
        (if-let [candidate (first (schema/candidate-shapes-in projection value))]
          (let [explanation (schema/explain-shape-in
                              projection
                              (:seon.schema/key candidate) value)
                humanized (some-> explanation me/humanize)
                error-value (some-> explanation me/error-value)]
            (cond->
              {:seon.render.value/schemas
               [(schema-status-row :invalid candidate)]}
              (and (some? humanized) (some? error-value))
              (assoc :seon.render.value/explanation
                     {:seon.render.value/humanized humanized
                      :seon.render.value/error-value error-value})))
          {:seon.render.value/schemas []})))))

#?(:cljs
   (defn- schema-projection [value incomplete?]
     (schema-projection-in
       (or (schema/current-projection)
           (schema/build-projection (schema/snapshot)))
       value incomplete?)))

(defn- drill-failure
  [message]
  {::ok? false
   :seon/error {:seon.error/message message
                :seon.error/kind :user-input}})

(defn- exact-map-keys?
  [value expected]
  (when (map? value)
    (let [entries (into [] (take (inc (count expected))) value)]
      (and (= (count expected) (count entries))
           (= expected (into #{} (map first) entries))))))

(defn- admitted-effective-limits?
  [limits]
  (and (exact-map-keys?
         limits
         #{:seon.config.render/value-max-path-segments
           :seon.config.render/value-max-path-bytes
           :seon.config.render/value-max-realized-items
           :seon.config.render/value-max-depth
           :seon.config.render/value-max-string
           :seon.config.render/value-shape-sample
           ::page-size})
       (every? safe-positive-int?
               ((juxt :seon.config.render/value-max-path-segments
                      :seon.config.render/value-max-path-bytes
                      :seon.config.render/value-max-realized-items
                      :seon.config.render/value-max-depth
                      :seon.config.render/value-max-string
                      :seon.config.render/value-shape-sample
                      ::page-size)
                limits))))

(defn admitted-drill-request?
  "True when a decoded drill request is closed and within every work cap."
  {:malli/schema [:=> [:catn [::request ::value]] :boolean]}
  [request]
  (and (exact-map-keys? request #{::path ::offset ::effective-limits})
       (let [{::keys [path offset effective-limits]} request]
         (and (vector? path)
              (admitted-effective-limits? effective-limits)
              (safe-nonnegative-int? offset)
              (let [max-segments
                    (:seon.config.render/value-max-path-segments
                      effective-limits)]
                (and (<= (count path) max-segments)
                     (every? drill-path-segment? path)
                     (<= (reduce
                           (fn [n segment]
                             (+ n (cond
                                    (string? segment) (count segment)
                                    (or (keyword? segment) (symbol? segment))
                                    (+ (count (name segment))
                                       (if-some [ns (namespace segment)]
                                         (inc (count ns)) 0))
                                    :else 16)))
                           0
                           path)
                         (:seon.config.render/value-max-path-bytes
                           effective-limits))))
              (let [page-size (::page-size effective-limits)
                    realized-max
                    (:seon.config.render/value-max-realized-items
                      effective-limits)
                    total (+ offset page-size)]
                (and (safe-nonnegative-int? total)
                     (<= total realized-max)))))))

(defn effective-limits-within?
  "True when requested limits equal or narrow one trusted maximum policy."
  {:malli/schema [:=> [:catn [::requested ::value]
                             [::trusted ::value]] :boolean]}
  [requested trusted]
  (let [limit-keys
        [:seon.config.render/value-max-path-segments
         :seon.config.render/value-max-path-bytes
         :seon.config.render/value-max-realized-items
         :seon.config.render/value-max-depth
         :seon.config.render/value-max-string
         :seon.config.render/value-shape-sample
         ::page-size]]
    (and (map? requested) (map? trusted)
         (= (count limit-keys) (count requested) (count trusted))
         (every? #(and (contains? requested %)
                       (contains? trusted %)
                       (safe-positive-int? (get requested %))
                       (safe-positive-int? (get trusted %))
                       (<= (get requested %) (get trusted %)))
                 limit-keys))))

(defn- descend-one
  [value segment]
  (cond
    (map? value)
    (if (contains? value segment)
      {::found? true ::value (get value segment)}
      {::found? false})

    (vector? value)
    (if (and (safe-nonnegative-int? segment) (< segment (count value)))
      {::found? true ::value (nth value segment)}
      {::found? false})

    :else {::found? false}))

(defn- descend-path
  [value path]
  (reduce
    (fn [{::keys [found? value] :as state} segment]
      (if found?
        (descend-one value segment)
        (reduced state)))
    {::found? true ::value value}
    path))

(defn- collection-kind [value]
  (cond
    (vector? value) :vector
    (set? value) :set
    :else :seq))

(defn- paged-collection
  [value offset page-size]
  (let [head+1 (into [] (comp (drop offset) (take (inc page-size))) value)
        more? (> (count head+1) page-size)]
    {::page (if more? (pop head+1) head+1)
     ::more? more?}))

(defn- bounded-map-window
  [value page-size]
  (let [entries+1 (into [] (take (inc page-size)) value)
        more? (> (count entries+1) page-size)
        entries (if more? (pop entries+1) entries+1)]
    {::page (into {} entries)
     ::more? more?
     ::elided-keys (when more? :more)}))

(defn- drill-summary
  [value]
  (cond
    (map? value) "map"
    (vector? value) (str "vector " (count value) " items")
    (set? value) "set"
    (coll? value) "seq"
    (opaque? value) "opaque"
    :else "scalar"))

(defn- ascending-distinct-indexes?
  [indexes entry-count]
  (and (vector? indexes)
       (<= (count indexes) entry-count)
       (every? safe-nonnegative-int? indexes)
       (every? #(< % entry-count) indexes)
       (= indexes (vec (distinct (sort indexes))))))

(defn- allowed-map-keys?
  [value allowed]
  (when (map? value)
    (let [entries (into [] (take (inc (count allowed))) value)]
      (and (<= (count entries) (count allowed))
           (every? allowed (map first entries))))))

(defn- marker-map-valid?
  [value max-collection]
  (cond
    (contains? value :seon.render.value/map-entries)
    (let [entries (:seon.render.value/map-entries value)
          indexes (:seon.render.value/non-drillable-key-indexes value)]
      (and (allowed-map-keys?
             value
             #{:seon.render.value/map-entries
               :seon.render.value/elided-keys
               :seon.render.value/non-drillable-key-indexes})
           (vector? entries)
           (<= (count entries) max-collection)
           (every? #(and (vector? %) (= 2 (count %))) entries)
           (or (nil? indexes)
               (ascending-distinct-indexes? indexes (count entries)))
           (let [elided (:seon.render.value/elided-keys value)]
             (or (nil? elided) (= :more elided)
                 (safe-positive-int? elided)))))

    (contains? value :seon.render.value/kind)
    (and (allowed-map-keys?
           value
           #{:seon.render.value/kind
             :seon.render.value/shown
             :seon.render.value/elided
             :seon.render.value/shape})
         (contains? #{:vector :set :seq} (:seon.render.value/kind value))
         (vector? (:seon.render.value/shown value))
         (<= (count (:seon.render.value/shown value)) max-collection)
         (let [elided (:seon.render.value/elided value)]
           (or (nil? elided) (= :more elided) (safe-positive-int? elided)))
         (let [shape (:seon.render.value/shape value)]
           (or (nil? shape)
               (and (vector? shape) (<= (count shape) max-collection)))))

    (contains? value :seon.render.value/pruned)
    (and (allowed-map-keys?
           value #{:seon.render.value/pruned :seon.render.value/count})
         (contains? #{:map :vector :set :seq}
                    (:seon.render.value/pruned value))
         (let [n (:seon.render.value/count value)]
           (or (nil? n) (safe-nonnegative-int? n))))

    (contains? value :seon.render.value/string-len)
    (and (exact-map-keys?
           value #{:seon.render.value/string-len :seon.render.value/head})
         (safe-nonnegative-int? (:seon.render.value/string-len value))
         (string? (:seon.render.value/head value)))

    (contains? value :seon.eval/opaque)
    (and (allowed-map-keys? value #{:seon.eval/opaque :seon.eval/summary})
         (string? (:seon.eval/opaque value))
         (let [summary (:seon.eval/summary value)]
           (or (nil? summary) (string? summary))))

    (contains? value :seon.eval/datom)
    (and (exact-map-keys? value #{:seon.eval/datom})
         (vector? (:seon.eval/datom value))
         (= 3 (count (:seon.eval/datom value))))

    :else false))

(def ^:dynamic *bounded-tree-visit!*
  "Optional test hook called for each deep-validator node visit."
  nil)

(defn- bounded-tree-node
  [value remaining depth max-depth max-collection max-string]
  (when (and (pos? remaining) (<= depth max-depth))
    (when *bounded-tree-visit!* (*bounded-tree-visit!* value))
    (cond
      (string? value) (when (<= (count value) max-string) (dec remaining))
      (or (nil? value) (boolean? value) (number? value))
      (dec remaining)

      (or (keyword? value) (symbol? value))
      (when (<= (+ (count (name value))
                   (if-some [ns (namespace value)] (inc (count ns)) 0))
                max-string)
        (dec remaining))

      (vector? value)
      (when (<= (count value) max-collection)
        (reduce
          (fn [left child]
            (if-some [next-left
                      (bounded-tree-node child left (inc depth) max-depth
                                         max-collection max-string)]
              next-left
              (reduced nil)))
          (dec remaining)
          value))

      (map? value)
      (when (marker-map-valid? value max-collection)
        (reduce
          (fn [left [k child]]
            (if-some [after-key
                      (bounded-tree-node k left (inc depth) max-depth
                                         max-collection max-string)]
              (if-some [after-value
                        (bounded-tree-node child after-key (inc depth) max-depth
                                           max-collection max-string)]
                after-value
                (reduced nil))
              (reduced nil)))
          (dec remaining)
          value))

      :else nil)))

(defn bounded-sampled-tree?
  "True when a sampled tree obeys the effective deep work bounds."
  {:malli/schema [:=> [:catn [::candidate ::value]
                             [::effective-limits ::effective-limits]]
                  :boolean]}
  [value effective-limits]
  (try
    (let [max-realized
          (:seon.config.render/value-max-realized-items effective-limits)
          page-size (::page-size effective-limits)]
      (boolean
        (bounded-tree-node value
                           (* (inc max-realized) 8)
                           0
                           (inc (:seon.config.render/value-max-path-segments
                                  effective-limits))
                           (max 16 (* 4 page-size))
                           (:seon.config.render/value-max-path-bytes
                             effective-limits))))
    (catch #?(:clj Throwable :cljs :default) _ false)))

(defn- bounded-ordinary-node
  [value remaining depth max-depth max-collection max-string]
  (when (and (pos? remaining) (<= depth max-depth))
    (cond
      (string? value) (when (<= (count value) max-string) (dec remaining))
      (or (nil? value) (boolean? value) (number? value))
      (dec remaining)

      (or (keyword? value) (symbol? value))
      (when (<= (+ (count (name value))
                   (if-some [ns (namespace value)] (inc (count ns)) 0))
                max-string)
        (dec remaining))

      (or (vector? value) (set? value) (list? value))
      (let [bounded-value (if (vector? value)
                            value
                            (into [] (take (inc max-collection)) value))]
        (when (<= (count bounded-value) max-collection)
        (reduce
          (fn [left child]
            (if-some [next-left
                      (bounded-ordinary-node child left (inc depth) max-depth
                                             max-collection max-string)]
              next-left
              (reduced nil)))
          (dec remaining)
          bounded-value)))

      (map? value)
      (let [entries (into [] (take (inc max-collection)) value)]
        (when (<= (count entries) max-collection)
        (reduce
          (fn [left [k child]]
            (if-some [after-key
                      (bounded-ordinary-node k left (inc depth) max-depth
                                             max-collection max-string)]
              (if-some [after-value
                        (bounded-ordinary-node child after-key (inc depth)
                                               max-depth max-collection
                                               max-string)]
                after-value
                (reduced nil))
              (reduced nil)))
          (dec remaining)
          entries)))

      :else nil)))

(defn- bounded-ordinary-data?
  [value effective-limits]
  (boolean
    (bounded-ordinary-node
      value
      (* 8 (inc (:seon.config.render/value-max-realized-items effective-limits)))
      0
      (inc (:seon.config.render/value-max-path-segments effective-limits))
      (max 16 (* 4 (::page-size effective-limits)))
      (:seon.config.render/value-max-path-bytes effective-limits))))

(defn- schema-status-row-valid?
  [row max-string]
  (and (map? row)
       (exact-map-keys? row #{:seon.schema/key :seon.schema/entity? ::status})
       (keyword? (:seon.schema/key row))
       (<= (+ (count (name (:seon.schema/key row)))
              (if-some [ns (namespace (:seon.schema/key row))]
                (inc (count ns)) 0))
           max-string)
       (boolean? (:seon.schema/entity? row))
       (contains? #{:valid :invalid :shape-only} (::status row))))

(defn- projection-result-valid?
  [projection effective-limits]
  (and (map? projection)
       (allowed-map-keys?
         projection
         #{::path ::offset ::page-size ::summary ::truncated? ::more?
           ::tree ::schemas ::explanation})
       (every? #(contains? projection %)
               [::path ::offset ::page-size ::summary ::truncated? ::more?
                ::tree ::schemas])
       (vector? (::path projection))
       (<= (count (::path projection))
           (:seon.config.render/value-max-path-segments effective-limits))
       (every? drill-path-segment? (::path projection))
       (<= (reduce
             (fn [n segment]
               (+ n (cond
                      (string? segment) (count segment)
                      (or (keyword? segment) (symbol? segment))
                      (+ (count (name segment))
                         (if-some [ns (namespace segment)]
                           (inc (count ns)) 0))
                      :else 16)))
             0
             (::path projection))
           (:seon.config.render/value-max-path-bytes effective-limits))
       (safe-nonnegative-int? (::offset projection))
       (safe-positive-int? (::page-size projection))
       (= (::page-size effective-limits) (::page-size projection))
       (let [total (+ (::offset projection) (::page-size projection))]
         (and (safe-nonnegative-int? total)
              (<= total
                  (:seon.config.render/value-max-realized-items
                    effective-limits))))
       (string? (::summary projection))
       (<= (count (::summary projection))
           (:seon.config.render/value-max-path-bytes effective-limits))
       (boolean? (::truncated? projection))
       (boolean? (::more? projection))
       (vector? (::schemas projection))
       (<= (count (::schemas projection))
           schema/shape-candidate-limit)
       (every? #(schema-status-row-valid?
                  % (:seon.config.render/value-max-path-bytes effective-limits))
               (::schemas projection))
       (if-some [explanation (::explanation projection)]
         (and (map? explanation)
              (exact-map-keys? explanation #{::humanized ::error-value})
              (bounded-ordinary-data? (::humanized explanation) effective-limits)
              (bounded-ordinary-data? (::error-value explanation)
                                      effective-limits))
         true)
       (bounded-sampled-tree? (::tree projection) effective-limits)))

(defn bounded-drill-result?
  "True when a drill result is closed and deeply work-bounded."
  {:malli/schema [:=> [:catn [::candidate ::value]
                             [::effective-limits ::effective-limits]]
                  :boolean]}
  [result effective-limits]
  (try
    (and (map? result)
         (cond
         (= false (::ok? result))
         (and (exact-map-keys? result #{::ok? :seon/error})
              (let [error (:seon/error result)]
                (and (map? error)
                     (allowed-map-keys?
                       error
                       #{:seon.error/message :seon.error/kind :seon.error/data})
                     (string? (:seon.error/message error))
                     (<= (count (:seon.error/message error))
                         (:seon.config.render/value-max-path-bytes
                           effective-limits))
                     (keyword? (:seon.error/kind error))
                     (<= (+ (count (name (:seon.error/kind error)))
                            (if-some [ns (namespace (:seon.error/kind error))]
                              (inc (count ns)) 0))
                         (:seon.config.render/value-max-path-bytes
                           effective-limits))
                     (if-some [data (:seon.error/data error)]
                       (bounded-ordinary-data? data effective-limits)
                       true))))

         (= :available (::availability result))
         (and (exact-map-keys? result #{::ok? ::availability ::projection})
              (= true (::ok? result))
              (projection-result-valid? (::projection result) effective-limits))

         (= :unavailable (::availability result))
         (and (exact-map-keys?
                result #{::ok? ::availability ::projection ::recompute?})
              (= true (::ok? result))
              (= true (::recompute? result))
              (projection-result-valid? (::projection result) effective-limits))

           :else false))
    (catch #?(:clj Throwable :cljs :default) _ false)))

(def sampling-policy-refusal-message
  "The value sample exceeds its retained eval policy.")

(defn sampling-policy-refusal
  "Return the shared bounded refusal for an attempted policy widening."
  {:malli/schema [:=> [:cat] ::drill-result]}
  []
  {::ok? false
   :seon/error {:seon.error/message sampling-policy-refusal-message
                :seon.error/kind :agent}})

(def sampling-policy-unavailable-message
  "The retained eval sampling policy is unavailable.")

(defn drill-value
  "Project one admitted path and bounded page from a live value."
  {:malli/schema [:=> [:catn [::schema-projection :map]
                             [::value ::value]
                             [::request ::value]]
                  ::drill-result]}
  [projection-input value request]
   (try
    (if-not (admitted-drill-request? request)
      (drill-failure "Invalid or over-budget value drill request.")
      (let [{::keys [path offset effective-limits]} request
            {::keys [found? value]} (descend-path value path)]
        (if-not found?
          (drill-failure "Value drill path is unavailable.")
          (if (and (map? value) (pos? offset))
            (drill-failure "Maps do not support offset paging.")
            (let [page-size (::page-size effective-limits)
                  map-result (when (map? value)
                               (bounded-map-window value page-size))
                  pageable? (and (coll? value) (not (map? value)))
                  page-result (when pageable?
                                (paged-collection value offset page-size))
                  page (cond
                         (map? value) (::page map-result)
                         pageable? (::page page-result)
                         :else value)
                  more? (boolean (and pageable? (::more? page-result)))
                  opts {:max-depth
                        (:seon.config.render/value-max-depth effective-limits)
                        :max-keys page-size
                        :max-map-visits page-size
                        :max-items page-size
                        :max-string
                        (:seon.config.render/value-max-string effective-limits)
                        :shape-sample
                        (:seon.config.render/value-shape-sample effective-limits)}
                  sampled (sample* page opts 0)
                  sampled (if-some [elided (::elided-keys map-result)]
                            (assoc sampled
                                   :seon.render.value/elided-keys elided)
                            sampled)
                  sampled (if pageable?
                            (assoc sampled :seon.render.value/kind
                                   (collection-kind value))
                            sampled)
                  incomplete? (or (pos? offset) more? (truncated? sampled))
                  projection
                  (merge
                    {::path path
                     ::offset offset
                     ::page-size page-size
                     ::summary (drill-summary value)
                     ::truncated? incomplete?
                     ::more? more?
                     ::tree sampled}
                    (schema-projection-in projection-input page incomplete?))]
              (let [result {::ok? true
                            ::availability :available
                            ::projection projection}]
                (if (bounded-drill-result? result effective-limits)
                  result
                  (drill-failure
                    "Value drill projection exceeded its bounds."))))))))
    (catch #?(:clj Throwable :cljs :default) _
      (drill-failure "Value drill failed while reading the selected value."))))

#?(:cljs
   (defn render-html-data
  "DATA CONTRACT the interactive HTML value-browser consumes.

   Returns:

     {:seon.render.value/eval-id    <id-string>     ; live-var handle
      :seon.render.value/summary    <\"map 12 keys\">  ; one-line header
      :seon.render.value/truncated? <bool>          ; is this a partial view
      :seon.render.value/tree       <sample skeleton>
      :seon.render.value/schemas    <ordered status rows>
      :seon.render.value/explanation <invalid-only Malli projections>}

   The `:tree` is the same plain-data skeleton `render-ai` emits — the
   panel renders each marker as a collapsible affordance and requests
   deeper slices by path. Schema status derives only from the activated
   projection; incomplete evidence is shape-only and never validated or
   explained. No hiccup here: styling + interactivity are U's."
  {:malli/schema [:=> [:catn [:seon.config/configuration
                              :seon.config/singleton]
                             [:seon.render.value/eval-id :string]
                             [:seon.render.value/value :any]]
                  :map]}
  [configuration eval-id value]
  (let [skel (sample configuration value {})
        incomplete? (truncated? skel)]
    (merge
      {:seon.render.value/eval-id    eval-id
       :seon.render.value/summary    (or (top-type+size value)
                                         (some-> (:seon.eval/opaque skel))
                                         "scalar")
       :seon.render.value/truncated? incomplete?
       :seon.render.value/tree       skel}
      (schema-projection value incomplete?)))))

;; ============================================================
;; Live integration:
;;
;; `seon.eval/render-result-edn` (the producer of `:seon.eval/result-edn`,
;; the AI text) calls `(render-ai configuration eval-id value)` before its
;; final char-cap backstop.
;; `seon.eval/sanitize-result-edn` (the read-side net for legacy rows)
;; reuses `project-plain`. The opaque-DETECTION + projection logic lives
;; ONLY here; `seon.eval` requires this ns — a one-way edge (eval →
;; render.value), no cycle. The `result/<id>` handle on the `; ⟹` line is
;; still added downstream by `seon.agent.ctx/format-eval-row` (untouched).
;; ============================================================
