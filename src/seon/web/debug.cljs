(ns seon.web.debug
  "Render operator views pinned to immutable database values.

   Debug and data feeds receive ordinary values from the JVM writer. They do
   not retain Datahike database values or maintain a second render cache."
  (:require
   [clojure.string :as str]
   [seon.agent.debug :as agent-debug]
   [seon.db :as db]
   [seon.render.system :as system]
   [seon.schema :as schema]
   [seon.ui.header :as header]
   [seon.ui.html :as html]
   [seon.web.brand :as brand]
   [seon.web.datastar :as datastar]))

(schema/register! ::ring-request :map)
(schema/register! ::data-attribute [:or :nil :keyword])

(defn- response [status content-type body]
  (js/Response.
   body
   #js {:status status
        :headers #js {"Content-Type" content-type
                      "Cache-Control" "no-store, no-cache, must-revalidate"}}))

(defn- brand-style []
  (when-let [css (brand/css-text)]
    [:style (html/raw css)]))

(defn- page-html [title feed-url loading]
  (let [b (brand/info nil)]
    (str
     "<!doctype html>"
     (html/->string
      [:html {:lang "en" :data-theme (::brand/theme b)}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport"
                :content "width=device-width, initial-scale=1.0"}]
        [:title (brand/page-title b title)]
        [:link {:rel "stylesheet" :href "/css/output.css"}]
        (brand-style)
       [:script {:type "module" :src "/js/datastar.js"}]]
       [:body {:class "min-h-screen bg-base-950 text-text-50 font-sans p-4"}
        [:main {:id "app-view"
                :class "text-xs font-mono text-text-500"}
         loading]
        [:div {:style "display:none"
               :data-init (str "@get('" feed-url
                               "', {retryMaxCount: Infinity, openWhenHidden: false})")}]]]))))

(defn- ^:async agent-exists? [database agent-id]
  (let [result (await
                (db/query
                 {::db/db database
                  :seon.db/query '[:find ?e :in $ ?id
                                   :where [?e :seon.agent/id ?id]]
                  :seon.db/args [agent-id]}))]
    (if (:seon.error/message result)
      result
      (boolean (seq result)))))

(defn- database-unavailable [error]
  (response 503 "text/plain; charset=utf-8"
            (or (:seon.error/message error) "database unavailable")))

(defn- header-projection [agents]
  (if (:seon.error/message agents)
    header/default-projection
    {::header/brand-name "seon"
     ::header/agent-count (count agents)
     ::header/running-count
     (count (filter #(= :running (::system/state %)) agents))}))

(defn- debug-element [agent-id preview agents]
  [:main {:id "app-view" :class "flex flex-col gap-2 p-3"}
   (header/system-header (header-projection agents))
   header/header-spacer
   [:h1 {:class "text-signal font-mono"} (str "debug · " agent-id)]
   (if-let [message (:seon.error/message preview)]
     [:div {:class "text-error"} message]
     [:pre {:id "debug-exact-prompt"
            :class "whitespace-pre-wrap text-xs"}
      (:seon.render/text preview)])])

(defn- render-debug! [agent-id database]
  (-> (js/Promise.all
       #js [(agent-debug/ctx-preview
             {:seon.agent/id agent-id
              ::db/db database})
            (system/acquire-fleet-summary database)])
      (.then (fn [[preview agents]]
               (debug-element agent-id preview agents)))))

(defn- debug-feed-definition [agent-id view-id]
  {:seon.web.feed/key [:seon.web.feed/debug agent-id]
   :seon.web.feed/live? true
   ::datastar/view-id view-id
   :seon.web.feed/render
   (fn [database]
     (-> (render-debug! agent-id database)
         (.then (fn [element] {::datastar/element element}))))})

(defn ^:async debug-page!
  "Serve the lightweight debug shell after confirming the agent exists."
  [request]
  (let [agent-id (get-in request [:path-params :id])
        database (await (db/db))]
    (cond
      (:seon.error/message database) (database-unavailable database)
      (str/blank? agent-id) (response 404 "text/plain; charset=utf-8"
                                      "agent not found")
      :else
      (let [exists? (await (agent-exists? database agent-id))]
        (cond
          (:seon.error/message exists?) (database-unavailable exists?)
          exists?
          (let [view-id (datastar/new-view-id)]
            (response 200 "text/html; charset=utf-8"
                      (page-html (str "agent " agent-id " · debug")
                                 (str "/agent/" agent-id "/debug/feed?view=" view-id)
                                 "loading debug view…")))
          :else
          (response 404 "text/plain; charset=utf-8"
                    (str "agent " agent-id " not found")))))))

(defn ^:async debug-feed!
  "Open the database-value-pinned exact-prompt feed for one agent."
  [request]
  (let [agent-id (get-in request [:path-params :id])
        database (await (db/db))
        view-id (or (datastar/request-view-id request)
                    (datastar/new-view-id))]
    (cond
      (:seon.error/message database) (database-unavailable database)
      (str/blank? agent-id) (response 404 "text/plain; charset=utf-8"
                                      "agent not found")
      :else
      (let [exists? (await (agent-exists? database agent-id))]
        (cond
          (:seon.error/message exists?) (database-unavailable exists?)
          exists? (datastar/open-view-feed!
                   request (debug-feed-definition agent-id view-id))
          :else (response 404 "text/plain; charset=utf-8"
                          (str "agent " agent-id " not found")))))))

(defn- query-value [^js request name]
  (try
    (let [url (js/URL. (.-url request))]
      (.get (.-searchParams url) name))
    (catch :default _ nil)))

(defn- data-attribute [^js request]
  (some-> (query-value request "attr") not-empty keyword))

(defn- data-element [page agents]
  [:main {:id "app-view" :class "flex flex-col gap-2 p-3"}
   (header/system-header (header-projection agents))
   header/header-spacer
   [:div {:class "flex items-baseline gap-3"}
    [:h1 {:class "text-signal font-mono"} "database"]
    [:a {:href "/" :class "text-amber-500 hover:text-amber-300"}
     "← agents"]]
   (if-let [message (:seon.error/message page)]
     [:div {:class "text-error"} message]
     [:pre {:class "whitespace-pre-wrap text-xs"}
      (pr-str (:datahike.index-page/datoms page))])])

(defn- render-data! [database attribute]
  (-> (js/Promise.all
       #js [(db/index-page
             (cond-> {::db/db database
                      ::db/index :aevt
                      ::db/direction :forward
                      ::db/limit 50}
               attribute (assoc ::db/components [attribute])))
            (system/acquire-fleet-summary database)])
      (.then (fn [[page agents]] (data-element page agents)))))

(defn- data-feed-definition [attribute view-id]
  {:seon.web.feed/key [:seon.web.feed/data attribute]
   :seon.web.feed/live? true
   ::datastar/view-id view-id
   :seon.web.feed/render
   (fn [database]
     (-> (render-data! database attribute)
         (.then (fn [element] {::datastar/element element}))))})

(defn data-page!
  "Serve the lightweight remote database-browser shell."
  [request]
  (let [http-request (:seon.http/request request)
        view-id (datastar/new-view-id)
        attribute (data-attribute http-request)
        query (if attribute
                (str "&attr=" (js/encodeURIComponent (subs (str attribute) 1)))
                "")]
    (response 200 "text/html; charset=utf-8"
              (page-html "data" (str "/data/feed?view=" view-id query)
                         "loading database…"))))

(defn data-feed!
  "Open the remote bounded-index database-browser feed."
  [request]
  (let [http-request (:seon.http/request request)
        view-id (or (datastar/request-view-id request)
                    (datastar/new-view-id))]
    (datastar/open-view-feed!
     request (data-feed-definition (data-attribute http-request) view-id))))
