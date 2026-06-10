(ns seon.handlers.ns
  "Renderers for `:seon.ns` entities — namespaces the agent has created
   via `(ns …)` forms.

   The entity card shows what the NAMESPACE CONTAINS (fns + schemas),
   not the `(ns ...)` source line itself — the owning `:seon.eval`
   already shows that. The query runs at render time so newly-added
   fns/schemas appear as soon as the next render fires.

   Symbol names linkify (HTML pane) to anchor ids on the corresponding
   `:seon.fn` / `:seon.schema` cards. The card stamps the anchor id
   on its top-level div so a click jumps in-page."
  (:require
    [datahike.api :as d]
    [clojure.string :as str]))

(defn- ns-contents
  "Pull lists of {sym, doc, private?} for fns and {key, shape-source}
   for schemas that point at `:seon.ns/name <ns-kw>`.

   Returns `{:fns [...] :schemas [...]}`. Safe if `db` is nil — returns
   empty vectors (some callers might invoke without a db; the inspector
   always passes one)."
  [db ns-kw]
  (if (or (nil? db) (nil? ns-kw))
    {:fns [] :schemas []}
    (let [fns (try
                (d/q '[:find ?sym ?priv ?doc
                       :in $ ?nk
                       :where
                       [?n :seon.ns/name ?nk]
                       [?f :seon.fn/ns ?n]
                       [?f :seon.fn/sym ?sym]
                       [(get-else $ ?f :seon.fn/private? false) ?priv]
                       [(get-else $ ?f :seon.fn/doc "") ?doc]]
                     db ns-kw)
                (catch :default _ #{}))
          schemas (try
                    (d/q '[:find ?k
                           :in $ ?nk
                           :where
                           [?n :seon.ns/name ?nk]
                           [?s :seon.schema/ns ?n]
                           [?s :seon.schema/key ?k]]
                         db ns-kw)
                    (catch :default _ #{}))]
      {:fns     (->> fns
                     (map (fn [[sym priv doc]]
                            {:sym sym :private? priv :doc doc}))
                     (sort-by :sym)
                     vec)
       :schemas (->> schemas
                     (map first)
                     (sort-by (comp str pr-str))
                     vec)})))

(defn- short-name
  "For an FQ symbol string `\"my.agent.XAR-.../foo\"`, return `\"foo\"`.
   For schemas (keywords like `:seon.foo/bar`), return `\":bar\"`."
  [s]
  (let [s (str s)]
    (if-let [i (str/last-index-of s "/")]
      (subs s (inc i))
      s)))

(defn render-ai
  "Compact one-line summary with counts and short names:

     [ns my.agent.XAR-...]  fns: add, sub  schemas: :answer, :id"
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.db/keys [db] :seon.render/keys [entity]}]
  (let [n      (:seon.ns/name entity)
        {:keys [fns schemas]} (ns-contents db n)
        fn-list (->> fns (map (comp short-name :sym)) (str/join ", "))
        sc-list (->> schemas (map short-name) (str/join ", "))
        parts   (cond-> [(str "[ns " (name n) "]")]
                  (seq fns)
                  (conj (str "fns(" (count fns) "): " fn-list))
                  (seq schemas)
                  (conj (str "schemas(" (count schemas) "): " sc-list))
                  (and (empty? fns) (empty? schemas))
                  (conj "(empty)"))]
    {:seon.render/text (str/join "  " parts)}))

(defn- anchor-id
  "Stable in-page anchor id for a fn/schema entity card. Keep in
   sync with the corresponding renderer if cards adopt their own."
  [kind name-or-sym]
  (str "seon-" kind "-" (-> (str name-or-sym)
                            (str/replace #"[^A-Za-z0-9_-]" "_"))))

(defn render-html
  "Interactive card. Header line + collapsible `<details>` per group
   (fns / schemas). Each name is an in-page anchor link — clicking
   `add` jumps to the `:seon.fn` entity card further down the pane."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.db/keys [db] :seon.render/keys [entity]}]
  (let [n (:seon.ns/name entity)
        {:keys [fns schemas]} (ns-contents db n)]
    {:seon.render/hiccup
     [:div {:class "py-1"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold text-amber-400"} "ns"]
       [:span {:class "text-xs font-mono text-text-100"} (name n)]
       [:span {:class "text-xs text-text-500"}
        (str (count fns) " fns · " (count schemas) " schemas")]]
      (when (seq fns)
        [:details {:class "mt-1" :open true}
         [:summary {:class "text-xs font-mono text-amber-300/80 cursor-pointer"}
          (str "fns (" (count fns) ")")]
         (into [:ul {:class "mt-0.5 ml-2"}]
               (for [{:keys [sym private? doc]} fns]
                 [:li {:class "text-xs font-mono flex items-baseline gap-2"}
                  [:a {:href  (str "#" (anchor-id "fn" sym))
                       :class "text-text-100 hover:text-amber-300"}
                   (short-name sym)]
                  (when private?
                    [:span {:class "text-text-500"} "(private)"])
                  (when (and doc (not (str/blank? doc)))
                    [:span {:class "text-text-400 italic"}
                     (first (str/split-lines doc))])]))])
      (when (seq schemas)
        [:details {:class "mt-1" :open true}
         [:summary {:class "text-xs font-mono text-amber-300/80 cursor-pointer"}
          (str "schemas (" (count schemas) ")")]
         (into [:ul {:class "mt-0.5 ml-2"}]
               (for [k schemas]
                 [:li {:class "text-xs font-mono"}
                  [:a {:href  (str "#" (anchor-id "schema" k))
                       :class "text-text-100 hover:text-amber-300"}
                   (pr-str k)]]))])
      (when (and (empty? fns) (empty? schemas))
        [:div {:class "text-xs text-text-500 italic mt-0.5"} "(empty)"])]}))
