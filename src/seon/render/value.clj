(ns seon.render.value
  "Unit adapter from admitted print data to the two floor projections."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [seon.print :as print]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

(schema.edn/load! {})

(defn transacted
  "Restore a pulled entity to the value shape its schema validates.

  Pull expands refs to maps and cardinality-many values to vectors. Renderer
  selection validates the transaction shape, while renderer functions still
  receive the original pulled value."
  {:malli/schema [:=> [:cat :map] :map]}
  [entity]
  (into {}
        (map (fn [[attribute value]]
               [attribute
                (cond
                  (and (map? value) (contains? value :db/id))
                  (:db/id value)

                  (and (sequential? value)
                       (seq value)
                       (every? (fn [element]
                                 (and (map? element)
                                      (contains? element :db/id)))
                               value))
                  (into #{} (map :db/id) value)

                  (sequential? value) (set value)
                  :else value)]))
        (dissoc entity :db/id)))

(def ^:private print-option-keys
  #{:seon.print/length
    :seon.print/level
    :seon.print/width
    :seon.print/namespace-maps?
    :seon.print/table?})

(defn node-id
  "Stable element id for one root selector and `get-in` path."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.render.data/path]
                  :string]}
  [unit path]
  (let [root-address
        (or (:seon.render.value/root unit)
            (when-some [eid (:db/id unit)] [:db/id eid])
            (when-some [block-name (:seon.render.block/name unit)]
              [:seon.render.block/name block-name])
            :seon.render.value/anonymous)
        address [(:seon.cluster.agent/id unit) root-address path]
        digest (schema/sha-256
                [(.getBytes ^String (pr-str address) "UTF-8")])]
    (str "seon-value-" (subs digest 0 24))))

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

(defn- page-size
  [unit]
  (long
   (min (or (:seon.render.value/max-collection unit)
            (:seon.render.value/max-collection
             (:seon.render.value/options unit))
            (:seon.config.eval.result/max-collection
             (:seon.sci.admit/caps unit)))
        (:seon.config.eval.result/max-collection
         (:seon.sci.admit/caps unit)))))

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
         :seon.render.value/total (counted-size value)
         :seon.render.value/more? more?})
      {:seon.render.value/window value
       :seon.render.value/steps []
       :seon.render.value/offset 0
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/more? false})
    (catch Throwable failure
      {:seon.render.value/window
       {:seon.error/kind :seon.render.value/window-failed
        :seon.error/message (or (ex-message failure) "realization failed")}
       :seon.render.value/steps []
       :seon.render.value/offset offset
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/more? false})))

(defn- display-value
  [unit]
  (if (and (:seon.render.value/route-base unit)
           (:seon.render.data/cursor unit))
    (window
     (:seon.render/value unit)
     (long (get-in unit [:seon.render.data/cursor
                         :seon.render.data/offset] 0))
     (page-size unit))
    (let [raw (:seon.render/value unit)
          total (counted-size raw)]
      {:seon.render.value/window raw
       :seon.render.value/steps []
       :seon.render.value/offset 0
       :seon.render.value/shown (or total 0)
       :seon.render.value/total total
       :seon.render.value/more? false})))

(defn- admitted-projection
  [value caps]
  (let [admitted
        (admit/admit
         {:seon.sci.admit/value value
          :seon.sci.admit/caps caps
          :seon.sci.admit/interrupt-fn (fn [])
          :seon.config/on-core-error :record})]
    {:seon.render.value/tree
     (edn/read-string (:seon.cluster.eval/result-edn admitted))
     :seon.render.value/truncated? (:seon.sci.admit/capped? admitted)}))

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
    (let [size (page-size unit)]
      [:div {:class "seon-data-pager"}
       (when (pos? offset)
         (path-link unit path (max 0 (- offset size))
                    "← previous" "seon-data-page"))
       [:span {:class "seon-data-range"}
        (str "showing " (if (zero? shown) 0
                            (str (inc offset) "–" (+ offset shown)))
             (when total (str " of " total)))]
       (when more?
         (path-link unit path (+ offset shown) "next →" "seon-data-page"))])))

(defn prepare
  "Admit one floor unit once and tee the finite print node to both sinks."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :nil :seon.render.value/projection]]}
  [unit]
  (when-let [caps (:seon.sci.admit/caps unit)]
    (let [display (display-value unit)
          admitted (if-let [result-edn (:seon.cluster.eval/result-edn unit)]
                     {:seon.render.value/tree (edn/read-string result-edn)
                      :seon.render.value/truncated? false}
                     (admitted-projection
                      (:seon.render.value/window display) caps))
          tree (:seon.render.value/tree admitted)
          options (print-options unit)
          emitted (print/emit-both tree options)
          truncated? (boolean
                      (or (:seon.render.value/truncated? admitted)
                          (:seon.render.value/more? display)
                          (pos? (:seon.render.value/offset display))))
          path (vec (get-in unit [:seon.render.data/cursor
                                  :seon.render.data/path] []))]
      {:seon.render.value/tree tree
       :seon.render.value/options options
       :seon.render.value/truncated? truncated?
       :seon.render.value/text (:seon.print/text emitted)
       :seon.render.value/html
       [:div {:id (node-id unit path) :class "seon-data-panel"}
        (breadcrumbs unit path)
        (pager unit path display)
        (:seon.print/hiccup emitted)
        (when truncated?
          [:p {:class "seon-data-capped"}
           "elided — this value is larger than the configured window"]) ]})))

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
  are derived when read and are never stored beside it."
  {:malli/schema [:=> [:cat :map] :seon.render.value/artifact]}
  [admitted]
  (select-keys admitted
               [:seon.sci.admit/print-node
                :seon.sci.admit/capped?
                :seon.sci.admit/record]))

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

(defn print-node-window
  "Return the first structural window of one admitted print node."
  {:malli/schema [:=> [:cat :seon.print/node :int] :seon.print/node]}
  [node size]
  (let [face (:seon.print/face node)
        limit (max 0 (dec size))]
    (case face
      (:seon.print/vector :seon.print/list :seon.print/set)
      (let [items (:seon.print/items node)
            cut? (> (count items) limit)]
        (assoc node :seon.print/items
               (cond-> (subvec (vec items) 0 (min limit (count items)))
                 cut? (conj {:seon.print/face :seon.print/elided}))))

      (:seon.print/map :seon.print/record)
      (let [entries (:seon.print/entries node)
            cut? (> (count entries) limit)]
        (assoc node :seon.print/entries
               (cond-> (subvec (vec entries) 0 (min limit (count entries)))
                 cut? (conj {:seon.print/face :seon.print/elided}))))

      :seon.print/string
      {:seon.print/face :seon.print/truncated-string
       :seon.print/value ""
       :seon.print/length (count (:seon.print/value node))}

      node)))

(defn result-window-edn
  "Store a small tagged data window beside an oversized result blob."
  {:malli/schema
   [:=> [:cat :seon.render/unit :seon.cluster.eval/result-edn]
    :seon.cluster.eval/result-edn]}
  [unit result-edn]
  (let [parsed (edn/read-string result-edn)
        node (if (and (map? parsed) (contains? parsed :seon.print/face))
               parsed
               (:seon.render.value/tree
                (admitted-projection parsed (:seon.sci.admit/caps unit))))]
    (admit/print-node-edn (print-node-window node (page-size unit)))))

(defn- render-prepared
  [unit output]
  (if-let [projection (prepare unit)]
    (if (= output :seon.render/html)
      (render-html-data projection)
      (render-ai-data projection))
    (if (= output :seon.render/html)
      [:div {:class "seon-error-card"}
       [:span {:class "seon-error-card-message"}
        (str "This panel needs :seon.sci.admit/caps on the unit; without "
             "them nothing bounds what it would print.")]]
      (str "This projection needs :seon.sci.admit/caps on the unit; without "
           "them nothing bounds what it would say."))))

(defn render-ai
  "Render any floor unit through the admitted text sink."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (render-prepared unit :seon.render/ai))

(defn render-html
  "Render any floor unit through the admitted hiccup sink."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (render-prepared unit :seon.render/html))
