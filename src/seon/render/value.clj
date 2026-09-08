(ns seon.render.value
  "One structural value renderer: profile-bounded AI and complete HTML."
  (:require [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.id :as id]
            [clojure.edn :as edn]
            [seon.print :as print]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

(schema.edn/load! {})

(defn transacted
  "Restore a pulled entity to its transaction shape.

  With a database value, installed Datahike value type and cardinality are the
  authority: refs become entity ids, cardinality-many values become sets, and
  scalar EDN vectors remain vectors. The one-argument arity preserves the
  established shape-only behavior for callers without database custody."
  {:malli/schema
   [:function
    [:=> [:catn [::entity :map]] :map]
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

(defn node-id
  "Stable element id for one root selector and `get-in` path."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.render.data/path]
                  [:or :string :seon.error/value]]}
  [unit path]
  (let [root-address
        (or (:seon.render.call/id unit)
            (:seon.render.value/root unit)
            (when-some [eid (:db/id unit)] [:db/id eid])
            (when-some [block-name (:seon.render.block/name unit)]
              [:seon.render.block/name block-name]))]
    (if-not root-address
      {:seon.error/kind ::missing-root-identity
       :seon.error/message
       "A rendered value root requires a caller-supplied block id."
       :seon.error/data
       {:seon.cluster.agent/id (:seon.cluster.agent/id unit)
        :seon.render.data/path path} :seon.render.value/missing-root-identity true}
      (str "seon-value-"
           (id/digest 24 [(:seon.cluster.agent/id unit) root-address path])))))

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
                    ((requiring-resolve 'seon.render/agent-render-profile)
                     ((requiring-resolve 'seon.config/defaults))))
        root (or (:seon.repl/handle unit)
                 (:seon.render.value/root unit)
                 (when-let [evaluation-id (:seon.eval/id unit)]
                   (id/symbol-in "result" \e evaluation-id))
                 (when-let [eid (or (:db/id unit)
                                    (:db/id (:seon.render/value unit)))]
                   [:db/id eid]))]
    (if root
      (assoc profile :seon.print/requery-id root)
      (assoc profile :seon.print/requery-refusal
             "the value has no result handle or entity identity"))))

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
  {:malli/schema [:=> [:cat :any :int :int] :map]}
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
       {:seon.error/kind :seon.render.value/window-failed
        :seon.render.value/window-realization-failed true
        :seon.error/message (or (ex-message failure) "realization failed")}
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
  (let [identity-attributes (into []
                                  (comp (filter (fn [[_ properties]]
                                                  (= :db.unique/identity (:db/unique properties))))
                                        (map first))
                                  (:schema database))
        entity (if (map? value) value
                   ((requiring-resolve 'seon.db/pull)
                    database (into [:db/id] identity-attributes) value))]
    (or (some (fn [attribute]
                (when-let [entry (find entity attribute)]
                  [attribute (val entry)]))
              (sort-by str identity-attributes))
        (when-let [eid (:db/id entity)] [:db/id eid])
        value)))

(defn- attribute-value
  [unit attribute value]
  (let [database (:seon.db/db unit)
        properties (get-in database [:schema attribute])]
    (if (and (= :db.type/ref (:db/valueType properties))
             (not (:db/isComponent properties)))
      (if (= :db.cardinality/many (:db/cardinality properties))
        (into #{} (map #(reference-identity database %)) value)
        (reference-identity database value))
      value)))

(declare value-node)

(defn- value-node*
  "Visit only the children the AI profile can show; HTML visits the whole value."
  [value unit profile output depth path remaining]
  (let [ai? (= output :seon.render/ai)
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
        exhausted? (and ai? (not (pos? @remaining)))]
    (when ai? (vswap! remaining dec))
    (cond
      (and (coll? value) (not= 0 total) ai?
           (or exhausted? (>= depth (:seon.render.profile/max-depth profile))))
      (cut 0 total :subtree
           (if exhausted? :seon.render.profile/token-budget
               :seon.render.profile/max-depth)
           (str (cond (map? value) "map" (set? value) "set"
                      (vector? value) "vector" :else "list")
                " depth " depth " "))

      (string? value)
      (let [limit (if ai? (max 1 (tokens/estimate-chars
                                 (:seon.render.profile/token-budget profile)))
                      (count value))]
        (if (< limit (count value))
          {:seon.print/face :seon.print/truncated-string
           :seon.print/value (subs value 0 limit)
           :seon.print/length (count value)
           :seon.print/bound-by :seon.render.profile/token-budget
           :seon.render.data/path path}
          {:seon.print/face :seon.print/string :seon.print/value value}))

      (coll? value)
      (let [selected (when (and (map? value) (:seon.sci.eval/ctx unit)
                                (not (get-in unit [:seon.render.value/options
                                                   :seon.render.value/structural?])))
                       (let [node {:seon.print/face :seon.print/map :seon.print/entries []}
                             projected ((requiring-resolve 'seon.render/project-node)
                                        unit value node output)]
                         (when (not= node projected) projected)))
            map-value? (map? value)
            set-value? (set? value)
            face (cond map-value? :seon.print/map set-value? :seon.print/set
                       (vector? value) :seon.print/vector :else :seon.print/list)
            child-key (if map-value? :seon.print/entries :seon.print/items)
            limit (if ai? (:seon.render.profile/max-children profile) Long/MAX_VALUE)
            ;; Complete print keys determine order. Omitted map values are
            ;; never visited, even to choose the retained prefix.
            key-node #(value-node % unit profile :seon.render/html depth [] remaining)
            key-text #(print/emit-text % {:seon.print/length nil :seon.print/level nil
                                         :seon.print/width 0 :seon.print/table? false})
            entries (cond
                      map-value? (sort-by (comp key-text first)
                                         (map (fn [[k v]] [(key-node k) k v]) value))
                      set-value? (sort-by (comp key-text first)
                                         (map (fn [v] [(key-node v) v v]) value))
                      :else (map-indexed (fn [i v] [nil i v]) value))
            children
            (when-not selected
             (loop [entries (seq entries) result []]
              (if-not entries
                result
                (let [offset (count result)]
                  (if (and ai? (or (>= offset limit) (not (pos? @remaining))))
                    (conj result (cut offset total :children
                                      (if (>= offset limit)
                                        :seon.render.profile/max-children
                                        :seon.render.profile/token-budget) nil))
                    (let [[key-node key child] (first entries)
                          ;; Lists are not associative: name their parent.
                          child-path (if (or map-value? set-value? (vector? value))
                                       (conj path key) path)
                          node (value-node (if map-value? (attribute-value unit key child) child)
                                           unit profile output (inc depth)
                                           child-path remaining)]
                      (recur (next entries)
                             (conj result (if map-value? [key-node node] node)))))))))]
        (or selected
         (cond-> {:seon.print/face face child-key children
                 :seon.render.data/path path}
          total (assoc :seon.render.data/total total))))

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
                {:seon.sci.admit/value value
                 :seon.sci.admit/caps (:seon.sci.admit/caps unit {})
                 :seon.sci.admit/unbounded? true
                 :seon.sci.admit/interrupt-fn (fn [])
                 :seon.config/on-core-error :record}))
              {:seon.print/face :seon.print/object
               :seon.print/class (.getName (class value))}))))))

(defn- value-node
  [value unit profile output depth path remaining]
  (try
    (assoc (value-node* value unit profile output depth path remaining)
           :seon.render.data/path path)
    (catch Throwable failure
      {:seon.print/face :seon.print/failed
       :seon.print/class (.getName (class failure))
       :seon.print/message (or (ex-message failure) "value realization failed")})))

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
  "Project live values within the AI profile, then emit through the shared grammar."
  {:malli/schema
   [:function
    [:=> [:cat :seon.render/unit]
     [:or :nil :seon.render.value/projection :seon.error/value]]
    [:=> [:cat :seon.render/unit :seon.render/output]
     [:or :nil :seon.render.value/projection :seon.error/value]]]}
  ([unit]
   (prepare unit :seon.render/ai))
  ([unit output]
   (let [display (display-value unit)
          profile (render-profile unit)
          raw (:seon.render/value unit)
          initial-tree (if-let [serialized (:seon.cluster.eval/result-edn unit)]
                         (edn/read-string serialized)
                         (value-node raw unit profile output 0 []
                                     (volatile! (:seon.render.profile/token-budget profile))))
          tree (cond-> initial-tree
                 (= output :seon.render/ai) (print/fit profile))
          options (cond-> (assoc (print-options unit)
                                 :seon.print/length nil
                                 :seon.print/level nil)
                    (= :single-line
                       (:seon.render.profile/composition profile))
                    (assoc :seon.print/width 0 :seon.print/table? false)

                    (= :tabular
                       (:seon.render.profile/composition profile))
                    (assoc :seon.print/table? true))
          emitted (print/emit-both tree options)
          truncated? (boolean
                      (or (:seon.render.value/more? display)
                          (pos? (:seon.render.value/offset display))))
          path (vec (get-in unit [:seon.render.data/cursor
                                  :seon.render.data/path] []))
          id (node-id unit path)]
      (if (:seon.error/kind id)
        id
        {:seon.render.value/tree tree
         :seon.render.value/options options
         :seon.render.value/truncated? truncated?
         :seon.render.value/text (:seon.print/text emitted)
         :seon.render.value/html
         [:div {:id id :class "seon-data-panel"}
          (breadcrumbs unit path)
          (pager unit path display)
          (:seon.print/hiccup emitted)
          (when truncated?
            [:p {:class "seon-data-capped"}
             "elided — this value is larger than the configured window"]) ]}))))

(defn render-ai-data
  "Return the text sink result from one already prepared projection."
  {:malli/schema [:=> [:cat :seon.render.value/projection] :string]}
  [projection]
  (str (:seon.render.value/text projection)
       (when (:seon.render.value/truncated? projection)
         " ; elided — this value is larger than the configured window")))

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
  (if (:seon.eval/missing admitted)
    (cond-> {:seon.sci.admit/print-node
             (:seon.sci.admit/print-node
              (admit/admit-value
               {:seon.sci.admit/value
                (select-keys admitted [:seon.eval/missing :seon.eval/size])
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
  {:malli/schema [:=> [:cat :seon.render.value/artifact] :any]}
  [stored]
  (admit/semantic-value (:seon.sci.admit/print-node stored)))

(defn artifact-result-edn
  "Derive receipt EDN from an artifact's sole print node."
  {:malli/schema [:=> [:cat :seon.render.value/artifact]
                  :seon.cluster.eval/result-edn]}
  [stored]
  (admit/print-node-edn (:seon.sci.admit/print-node stored)))

(defn- render-prepared
  [unit output]
  (let [projection (prepare unit output)]
    (if (:seon.error/kind projection)
      projection
      (if (= output :seon.render/html)
        (render-html-data projection)
        (render-ai-data projection)))))

(defn render-ai
  "Render any floor unit through the admitted text sink."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :string :seon.error/value]]}
  [unit]
  (render-prepared unit :seon.render/ai))

(defn render-html
  "Render any floor unit through the admitted hiccup sink."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.error/value]]}
  [unit]
  (render-prepared unit :seon.render/html))
