(ns seon.web.data
  "Pure JVM projection for the first `/data` database browser slice."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.ui.html :as html])
  (:import [java.net URLDecoder]
           [java.nio.charset StandardCharsets]))

(defn- query-parameters
  [query-string]
  (into {}
        (keep
         (fn [part]
           (let [[name value] (str/split part #"=" 2)]
             (when-not (str/blank? name)
               [(URLDecoder/decode name StandardCharsets/UTF_8)
                (URLDecoder/decode (or value "") StandardCharsets/UTF_8)]))))
        (str/split (or query-string "") #"&")))

(defn attribute
  "Return the selected attribute from one Ring request."
  [request]
  (some-> (get (query-parameters (:query-string request)) "attr")
          not-empty
          keyword))

(defn view-id
  "Return the requested view identity from one Ring request."
  [request]
  (some-> (get (query-parameters (:query-string request)) "view")
          not-empty))

(defn- stable-entity-ids
  [page]
  (second
   (reduce
    (fn [[seen ids] datom]
      (let [entity-id (nth datom 0 nil)]
        (if (or (nil? entity-id) (contains? seen entity-id))
          [seen ids]
          [(conj seen entity-id) (conj ids entity-id)])))
    [#{} []]
    (:datahike.index-page/datoms page))))

(defn- error-value?
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- entity-panel
  [entity-id entity]
  [:section {:class "flex flex-col gap-1 border border-base-800 p-3"
             :data-entity-id (str entity-id)}
   [:h2 {:class "text-amber-500 font-mono"} (str "entity " entity-id)]
   [:pre {:class "whitespace-pre-wrap overflow-x-auto text-xs text-text-200"}
    (pr-str entity)]])

(defn render
  "Render one complete `#app-view` database browser at `database`."
  [{::keys [database selected-attribute page-size]}]
  (let [page
        (db/index-page
         (cond-> {::db/db database
                  ::db/index :aevt
                  ::db/direction :forward
                  ::db/limit page-size}
           selected-attribute (assoc ::db/components [selected-attribute])))
        entity-ids (when-not (error-value? page) (stable-entity-ids page))
        entities (when entity-ids
                   (mapv #(db/entity {::db/db database ::db/ref %})
                         entity-ids))
        failure (or (when (error-value? page) page)
                    (some #(when (error-value? %) %) entities))]
    (html/->string
     [:main {:id "app-view" :class "flex flex-col gap-2 p-3"}
      [:header {:class "flex items-baseline gap-3 border-b border-base-800 pb-2"}
       [:h1 {:class "text-signal font-mono"} "database"]
       [:span {:class "text-text-500"}
        (str "basis t=" (:t database))]
       [:a {:href "/" :class "text-amber-500 hover:text-amber-300"}
        "← agents"]]
      (if failure
        [:div {:class "text-error"} (:seon.error/message failure)]
        (into [:div {:class "flex flex-col gap-3"}]
              (map entity-panel entity-ids entities)))])))

(defn shell
  "Render the identity-encoded `/data` shim for `feed-url`."
  [{::keys [feed-url]}]
  (str
   "<!doctype html>"
   (html/->string
    [:html {:lang "en" :data-theme "phosphor"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport"
              :content "width=device-width, initial-scale=1.0"}]
      [:title "seon · data"]
      [:link {:rel "stylesheet" :href "/css/output.css"}]
      [:script {:type "module" :src "/js/datastar.js"}]]
     [:body {:class "min-h-screen bg-base-950 text-text-50 font-sans p-4"}
      [:main {:id "app-view" :class "text-xs font-mono text-text-500"}
       "loading database…"]
      [:div {:style "display:none"
             :data-init
             (str "@get('" feed-url
                  "', {retryMaxCount: Infinity, openWhenHidden: false})")}]]])))
