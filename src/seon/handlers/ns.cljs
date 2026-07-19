(ns seon.handlers.ns
  "Render captured namespace facts for agent context.

   The entity card shows what the NAMESPACE CONTAINS (fns + schemas),
   not the `(ns ...)` source line itself — the owning `:seon.eval`
   already shows that. The database authority supplies the namespace and its
   members as ordinary pulled data before this formatter runs.

   Symbol names linkify (HTML pane) to anchor ids on the corresponding
   `:seon.fn` / `:seon.schema` cards. The card stamps the anchor id
   on its top-level div so a click jumps in-page."
  (:require [clojure.string :as str]))

(defn- ns-contents
  "Project already-pulled namespace members into this formatter's shape."
  [node]
  {:fns (->> (:seon.fn/_ns node)
             (map (fn [row]
                    {:sym (:seon.fn/sym row)
                     :private? (boolean (:seon.fn/private? row))
                     :doc (or (:seon.fn/doc row) "")}))
             (sort-by :sym)
             vec)
   :schemas (->> (:seon.schema/_ns node)
                 (keep :seon.schema/key)
                 (sort-by (comp str pr-str))
                 vec)})

(defn- short-name
  "For an FQ symbol string `\"my.agent.XAR-.../foo\"`, return `\"foo\"`.
   For schemas (keywords like `:seon.foo/bar`), return `\":bar\"`."
  [s]
  (let [s (str s)]
    (if-let [i (str/last-index-of s "/")]
      (subs s (inc i))
      s)))

(defn render-ai
  "Compact one-line summary with counts and short names.

   Example:

     [ns my.agent.XAR-...]  fns: add, sub  schemas: :answer, :id"
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :string]]}
  [{:seon.render/keys [node entity]}]
  (let [entity (or node entity)
        n      (:seon.ns/name entity)
        {:keys [fns schemas]} (ns-contents entity)
        fn-list (->> fns (map (comp short-name :sym)) (str/join ", "))
        sc-list (->> schemas (map short-name) (str/join ", "))
        parts   (cond-> [(str "[ns " (name n) "]")]
                  (seq fns)
                  (conj (str "fns(" (count fns) "): " fn-list))
                  (seq schemas)
                  (conj (str "schemas(" (count schemas) "): " sc-list))
                  (and (empty? fns) (empty? schemas))
                  (conj "(empty)"))]
    (str/join "  " parts)))

(defn- anchor-id
  "Stable in-page anchor id for a fn/schema entity card. Keep in
   sync with the corresponding renderer if cards adopt their own."
  [kind name-or-sym]
  (str "seon-" kind "-" (-> (str name-or-sym)
                            (str/replace #"[^A-Za-z0-9_-]" "_"))))

(defn render-html
  "Interactive card.

   Header line + collapsible `<details>` per group
   (fns / schemas). Each name is an in-page anchor link — clicking
   `add` jumps to the `:seon.fn` entity card further down the pane."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :seon.render.canvas/hiccup]]}
  [{:seon.render/keys [node entity]}]
  (let [entity (or node entity)
        n (:seon.ns/name entity)
        {:keys [fns schemas]} (ns-contents entity)]
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
       [:div {:class "text-xs text-text-500 italic mt-0.5"} "(empty)"])]))
