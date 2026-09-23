(ns seon.render.value
  "One structural value renderer: profile-bounded AI and complete HTML."
  (:require [clojure.string :as str]
            [seon.id :as id]
            [seon.db :as db]
            [clojure.edn :as edn]
            [seon.print :as print]
            [seon.cluster.wake :as wake]
            [seon.schema.edn :as schema.edn]
            [seon.schema :as schema]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.sci.admit :as admit]))

(schema.edn/load! {})

;;; LOAD-CYCLE BOUNDARIES. `seon.render` and `seon.config` both require
;;; `seon.render.value` transitively, so this namespace cannot require them
;;; back. One resolution per var, realized at first use, instead of a
;;; `requiring-resolve` on every call (AGENTS §2.1).

(defonce ^:private render-agent-render-profile
  (delay (requiring-resolve 'seon.render/agent-render-profile)))
(defonce ^:private config-defaults
  (delay (requiring-resolve 'seon.config/defaults)))
(defonce ^:private render-project-node
  (delay (requiring-resolve 'seon.render/project-node)))


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
  limit, `#unprintable[value-class failure-class]` a value whose printing throws."
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
  stored member."
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
    :seon.print/namespace-maps?
    :seon.print/table?})

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
      {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.render.value/identity
        :seon.error/operation 'seon.render.value/node-id
        :seon.error/message "A rendered value root requires a caller-supplied block id."
        :seon.error/fix "Supply :seon.render.call/id, :seon.render.value/root, or an entity identity."
        :seon.render.value/root-description (pr-str (select-keys unit [:seon.agent/id :seon.render.call/id
                                   :seon.render.value/root :db/id
                                   :seon.render.block/name]))
        :seon.error/member :seon.render.value/root
        :seon.error/expected "a caller-supplied block or entity identity"
        :seon.error/offending unit
        :seon.error/data {:seon.agent/id (:seon.agent/id unit) :seon.render.data/path path}}
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

(defn- stable-entries
  [value]
  (cond
    (map? value) (sort-by (comp pr-str first) (seq value))
    (set? value) (map (fn [entry] [entry entry]) (sort-by pr-str value))
    (vector? value) (map-indexed vector value)
    (sequential? value) (map-indexed vector value)
    :else nil))

(defn- counted-size
  [value]
  (when (counted? value)
    (try (count value) (catch Throwable _ nil))))

(defn window
  "Return one stable structural page from an ordinary bounded value."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}] :int :int] :map]}
  [value offset size]
  (try
    (if-let [entries (stable-entries value)]
      (let [available (max 0 size)
            total (counted-size value)
            head (into [] (comp (drop offset) (take (inc available))) entries)
            more? (> (count head) available)
            page (subvec head 0 (min available (count head)))
            page-value (cond
                         (map? value) (into {} page)
                         (set? value) (into #{} (map second) page)
                         :else (mapv second page))]
        {:seon.render.value/window page-value
         :seon.render.value/steps (mapv first page)
         :seon.render.value/offset offset
         :seon.render.value/shown (count page)
         :seon.render.value/total total
         :seon.render.value/beyond-end?
         (and (some? total) (> offset total))
         :seon.render.value/more? more?})
      {:seon.render.value/window value
       :seon.render.value/steps []
       :seon.render.value/offset 0
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/beyond-end? false
       :seon.render.value/more? false})
    (catch Throwable failure
      {:seon.render.value/window
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.render.value/window
         :seon.error/operation 'seon.render.value/window
         :seon.error/message (or (ex-message failure) "Window realization failed.")
         :seon.error/fix "Inspect the source value and request a realizable window."
         :seon.render.value/window-offset offset
         :seon.error/expected "a realizable value window"
         :seon.error/offending value
         :seon.error/data {:seon.render.value/offset offset}}
       :seon.render.value/steps []
       :seon.render.value/offset offset
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/beyond-end? false
       :seon.render.value/more? false})))

(defn- display-value
  [unit]
  (let [raw (:seon.render/value unit)
        total (counted-size raw)]
    {:seon.render.value/window raw
     :seon.render.value/steps []
     :seon.render.value/offset 0
     :seon.render.value/shown (or total 0)
     :seon.render.value/total total
     :seon.render.value/more? false}))

(defn- reference-identity
  [database value]
  (if (map? value)
    value
    (let [inert (wake/inert-attributes database)
          identity-attributes (into []
                                  (comp (filter (fn [[_ properties]]
                                                  (= :db.unique/identity (:db/unique properties))))
                                        (map first)
                                        (remove inert))
                                  (:schema database))
        entity (db/pull
                database (into [:db/id] identity-attributes) value)]
    (or (some (fn [attribute]
                (when-let [entry (find entity attribute)]
                  [attribute (val entry)]))
              (sort-by str identity-attributes))
        (when-let [eid (:db/id entity)] [:db/id eid])
        value))))

(defn- attribute-value
  [unit attribute value output]
  (let [database (:seon.db/db unit)
        properties (get-in database [:schema attribute])]
    (cond
      (and (= :seon.render/ai output) (= :my.plan.item/needs attribute)
           (counted? value) (coll? value) (every? #(and (map? %) (string? (:my.plan.item/id %))
                                      (every? #{:db/id :my.plan.item/id} (keys %))) value))
      (vec (sort (map :my.plan.item/id value)))

      (and (:db/isComponent properties)
           (= :db.cardinality/many (:db/cardinality properties))
           (counted? value) (coll? value) (seq value) (every? map? value))
      (let [position (some (fn [attribute-key]
                             (when (and (qualified-keyword? attribute-key)
                                        (= "position" (name attribute-key))
                                        (every? #(number? (get % attribute-key)) value))
                               attribute-key))
                           (sort-by str (keys (first value))))]
        (cond
          (nil? position) value
          (set? value)
          (into (sorted-set-by
                 (fn [left right]
                   (let [ordered (compare (get left position) (get right position))]
                     (if (zero? ordered) (compare (pr-str left) (pr-str right)) ordered))))
                value)
          :else (vec (sort-by position value))))

      (and (= :db.type/ref (:db/valueType properties))
             (not (:db/isComponent properties)))
      (if (= :db.cardinality/many (:db/cardinality properties))
        (into #{} (map #(reference-identity database %)) value)
        (reference-identity database value))

      :else value)))

(declare value-node)

(defn- value-node*
  "Visit only the children the AI profile can show; HTML visits the whole value."
  [value unit profile output depth path remaining]
  (let [projection (or (:seon.schema/projection (meta value))
                       (:seon.schema/projection unit)
                       (:seon.schema/projection (meta (:seon.db/db unit)))
                       (:seon.schema/projection (env/of (:seon.sci.eval/ctx unit))))
        identity (when projection
                   (schema/identity-only-projection-in projection value))
        value (if identity (:seon.schema/identity-value identity) value)
        ai? (= output :seon.render/ai)
        total (counted-size value)
        cut (fn [offset total measure bound prefix]
              (print/elision
               (cond-> (merge profile
                              {:seon.render.data/path path
                               :seon.render.data/next-offset offset
                               :seon.print/omitted (max 1 (- (or total (inc offset)) offset))
                               :seon.print/elision-unit measure
                               :seon.print/bound-by bound})
                 total (assoc :seon.render.data/total total)
                 prefix (assoc :seon.print/prefix prefix))))
        exhausted? (and ai? (not (pos? @remaining)))
        selected (when (and (map? value) (:seon.sci.eval/ctx unit)
                            (not (get-in unit [:seon.render.value/options
                                              :seon.render.value/structural?])))
                   (let [node {:seon.print/face :seon.print/map :seon.print/entries []}
                         projected (@render-project-node
                                    unit value node output)]
                     (when (not= node projected) projected)))]
    (when ai? (vswap! remaining dec))
    (cond
      ;; A declared pair shapes a reached value before structural limits apply.
      selected selected

      (and (coll? value) (not= 0 total) ai?
           (or exhausted? (>= depth (:seon.render.profile/max-depth profile))))
      (cut 0 total :subtree
           (if exhausted? :seon.render.profile/token-budget
               :seon.render.profile/max-depth)
           (str (cond (map? value) "map" (set? value) "set"
                      (vector? value) "vector" :else "list")
                " depth " depth " "))

      (string? value)
      {:seon.print/face :seon.print/string :seon.print/value value}

      (coll? value)
      (let [map-value? (map? value)
            set-value? (set? value)
            face (cond (record? value) :seon.print/record
                       map-value? :seon.print/map set-value? :seon.print/set
                       (vector? value) :seon.print/vector :else :seon.print/list)
            child-key (if map-value? :seon.print/entries :seon.print/items)
            limit (if ai? (:seon.render.profile/max-children profile) Long/MAX_VALUE)
            ;; Order whole members before choosing which children to retain.
            entries (cond
                      map-value? (map (fn [[k v]] [nil k v])
                                      (sort-by key print/compare-values value))
                      set-value? (let [members (map (fn [v] [nil v v]) value)]
                                     (if (sorted? value) members
                                       (sort-by second print/compare-values members)))
                      :else (map-indexed (fn [i v] [nil i v]) value))
            children
            (loop [entries (seq entries) result []]
              (if-not entries
                result
                (let [offset (count result)]
                  (if (and ai? (or (>= offset limit) (not (pos? @remaining))))
                    (conj result (cut offset total :children
                                      (if (>= offset limit)
                                        :seon.render.profile/max-children
                                        :seon.render.profile/token-budget) nil))
                    (let [[_ key child] (first entries)
                          key-node (when map-value?
                                     (value-node key
                                                 (assoc-in unit [:seon.render.value/options
                                                                 :seon.render.value/structural?] true)
                                                 profile :seon.render/html depth [] remaining))
                          child-path (conj path key)
                          node (value-node (if map-value? (attribute-value unit key child output) child)
                                           unit profile output (inc depth)
                                           child-path remaining)]
                      (recur (next entries)
                             (conj result (if map-value? [key-node node] node))))))))]
        (cond-> {:seon.print/face face child-key children
                 :seon.render.data/path path}
          (record? value) (assoc :seon.print/name (admit/record-name value))
          (and set-value? (sorted? value)) (assoc :seon.print/ordered? true)
          total (assoc :seon.render.data/total total)))

      :else
      (let [face (cond (nil? value) :seon.print/nil
                       (boolean? value) :seon.print/boolean
                       (number? value) :seon.print/number
                       (keyword? value) :seon.print/keyword
                       (symbol? value) :seon.print/symbol
                       (char? value) :seon.print/char
                       (uuid? value) :seon.print/uuid
                       (instance? java.util.Date value) :seon.print/inst)]
        (if face
          {:seon.print/face face :seon.print/value value}
          (or (:seon.sci.admit/print-node
               (admit/admit-value
                (merge (select-keys unit [:seon.db/db :seon.sci.eval/ctx
                                          :seon.schema/projection :seon.env/environment
                                          :seon.sci.eval/projection-state])
                       {:seon.sci.admit/value value
                 :seon.sci.admit/caps (:seon.sci.admit/caps unit {})
                 :seon.sci.admit/unbounded? true
                 :seon.sci.admit/interrupt-fn (fn [])
                 :seon.config/on-core-error :record})))
              {:seon.print/face :seon.print/object
               :seon.print/class (.getName (class value))}))))))

(defn- value-node
  [value unit profile output depth path remaining]
  (try
    (assoc (value-node* value unit profile output depth path remaining)
           :seon.render.data/path path)
    (catch Throwable failure
      ;; Fixed terminal nodes cannot invoke the producer that just failed.
      {:seon.print/face :seon.print/map
       :seon.render.data/path path
       :seon.print/entries
       [[{:seon.print/face :seon.print/keyword
          :seon.print/value :seon.error/layer}
         {:seon.print/face :seon.print/keyword
          :seon.print/value :seon.render.value/projection-failed}]
        [{:seon.print/face :seon.print/keyword
          :seon.print/value :seon.error/message}
         {:seon.print/face :seon.print/string
          :seon.print/value (floor failure)}]]})))

(defn- breadcrumbs
  [unit path]
  (when (:seon.render.value/route-base unit)
    [:nav {:class "seon-data-crumbs"}
     (path-link unit [] 0 "root" "seon-data-crumb")
     (map (fn [index]
            (path-link unit (subvec path 0 (inc index)) 0
                       (pr-str (nth path index)) "seon-data-crumb"))
          (range (count path)))]))

(defn- pager
  [unit path {:seon.render.value/keys [offset shown total more?]}]
  (when (:seon.render.value/route-base unit)
    [:div {:class "seon-data-pager"}
       (when (pos? offset)
         (path-link unit path 0
                    "← previous" "seon-data-page"))
       [:span {:class "seon-data-range"}
        (str "showing " (if (zero? shown) 0
                            (str (inc offset) "–" (+ offset shown)))
             (when total (str " of " total)))]
       (when more?
         (path-link unit path (+ offset shown) "next →" "seon-data-page"))]))

(defn prepare
  "Validate the root, project live values, and emit the shared grammar."
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
       (let [display (display-value unit)
             profile (render-profile unit)
             raw (:seon.render/value unit)
             initial-tree
             (value-node raw unit profile output 0 []
                         (volatile! (:seon.render.profile/token-budget profile)))
             tree (cond-> initial-tree
                    (= output :seon.render/ai) (print/fit profile))
             options (cond-> (assoc (print-options unit)
                                    :seon.print/length nil
                                    :seon.print/level nil
                                    :seon.print/table? false)
                       (or (= output :seon.render/ai)
                           (= :single-line
                              (:seon.render.profile/composition profile)))
                       (assoc :seon.print/width 0))
             emitted (print/emit-both tree options)
             truncated? (boolean
                         (or (:seon.render.value/more? display)
                             (pos? (:seon.render.value/offset display))))]
         (cond-> {:seon.render.value/tree tree
                  :seon.render.value/options options
                  :seon.render.value/truncated? truncated?
                  :seon.render.value/text (:seon.print/text emitted)
                  :seon.render.value/html
                  [:div {:id id :class "seon-data-panel"}
                   (breadcrumbs unit path)
                   (pager unit path display)
                   (:seon.print/hiccup emitted)]}
           (:seon.render.call/selected-producer initial-tree)
           (assoc :seon.render.call/selected-producer
                  (:seon.render.call/selected-producer initial-tree))))))))

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
