(ns seon.render.value
  "Unit adapter from admitted print data to the two floor projections."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [seon.ai.tokens :as tokens]
            [seon.print :as print]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

(schema.edn/load! {})

(defn transacted
  "Compatibility call into the projection selector's transaction shape."
  {:malli/schema [:=> [:cat :map] :map]}
  [entity]
  ((requiring-resolve 'seon.render/transacted) entity))

(defn render-database-identity-ai
  "Readable identity face for an admitted immutable database value."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
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

(defn- render-profile
  [unit]
  (let [profile (or (:seon.render/profile unit)
                    ((requiring-resolve 'seon.render/agent-render-profile)
                     ((requiring-resolve 'seon.config/defaults))))]
    (cond-> profile
      (:seon.cluster.eval/result-blob unit)
      (assoc :seon.print/requery-id
             [:seon.blob/digest (:seon.cluster.eval/result-blob unit)])

      (and (nil? (:seon.cluster.eval/result-blob unit))
           (:seon.render.value/root unit))
      (assoc :seon.print/requery-id (:seon.render.value/root unit))

      (and (nil? (:seon.cluster.eval/result-blob unit))
           (nil? (:seon.render.value/root unit)))
      (assoc :seon.print/requery-refusal
             "the value has no durable blob or entity identity"))))

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
        :seon.error/message (or (ex-message failure) "realization failed")}
       :seon.render.value/steps []
       :seon.render.value/offset offset
       :seon.render.value/shown 0
       :seon.render.value/total nil
       :seon.render.value/beyond-end? false
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
        (admit/admit-value
         {:seon.sci.admit/value value
          :seon.sci.admit/caps caps
          :seon.sci.admit/interrupt-fn (fn [])
          :seon.config/on-core-error :record})]
    {:seon.render.value/tree
     (:seon.sci.admit/print-node admitted)
     :seon.render.value/semantic (:seon.sci.admit/value admitted)
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
  "Admit once, recursively dispatch declared producers, fit, then emit."
  {:malli/schema
   [:function
    [:=> [:cat :seon.render/unit]
     [:or :nil :seon.render.value/projection]]
    [:=> [:cat :seon.render/unit :seon.render/output]
     [:or :nil :seon.render.value/projection]]]}
  ([unit]
   (prepare unit :seon.render/ai))
  ([unit output]
   (when-let [caps (:seon.sci.admit/caps unit)]
    (let [display (display-value unit)
          admitted (if-let [result-edn (:seon.cluster.eval/result-edn unit)]
                     (let [tree (edn/read-string result-edn)]
                       {:seon.render.value/tree tree
                        :seon.render.value/semantic
                        (admit/semantic-value tree)
                        :seon.render.value/truncated? false})
                     (admitted-projection
                      (:seon.render.value/window display) caps))
          profile (cond-> (render-profile unit)
                    (or (:seon.render.data/total unit)
                        (:seon.render.value/total display))
                    (assoc :seon.render.data/total
                           (or (:seon.render.data/total unit)
                               (:seon.render.value/total display))))
          tree (-> ((requiring-resolve 'seon.render/project-node)
                    unit
                    (:seon.render.value/semantic admitted)
                    (:seon.render.value/tree admitted)
                    output)
                   (print/enrich-elisions profile)
                   (print/fit profile))
          options (assoc (print-options unit)
                         :seon.print/length nil
                         :seon.print/level nil)
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
  "Compatibility call into the one profile-owned structural fitter."
  {:malli/schema
   [:function
    [:=> [:cat :seon.print/node :int] :seon.print/node]
    [:=> [:cat :seon.print/node :int :int :int] :seon.print/node]]}
  ([node size]
   (print/fit node
              {:seon.render.profile/id :seon.render.profile/legacy-window
               :seon.render.profile/token-budget 1048576
               :seon.render.profile/max-depth 64
               :seon.render.profile/max-children (max 0 (dec size))
               :seon.render.profile/blob-threshold 4096
               :seon.render.profile/composition :multiline
               :seon.print/requery-refusal
               "the legacy caller supplied no stable identity"}))
  ([node size max-size level]
   (print/fit node
              {:seon.render.profile/id :seon.render.profile/legacy-window
               :seon.render.profile/token-budget
               (max 1 (tokens/estimate (apply str (repeat max-size "x"))))
               :seon.render.profile/max-depth (max 0 level)
               :seon.render.profile/max-children (max 0 (dec size))
               :seon.render.profile/blob-threshold max-size
               :seon.render.profile/composition :multiline
               :seon.print/requery-refusal
               "the legacy caller supplied no stable identity"})))

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
    (admit/print-node-edn
     (print/fit node
                (assoc (render-profile unit)
                       :seon.render.profile/max-children
                       (max 0 (dec (page-size unit))))))))

(defn- render-prepared
  [unit output]
  (if-let [projection (prepare unit output)]
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
