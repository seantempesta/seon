(ns seon.web.debug
  "Database-value-pinned operator views backed by the remote database protocol.

   Debug and data feeds receive ordinary values from the JVM writer. They do
   not retain Datahike database values or maintain a second render cache."
  (:require
   [clojure.string :as str]
   [seon.agent.debug :as agent-debug]
   [seon.db :as db]
   [seon.schema :as schema]
   [seon.ui.header :as header]
   [seon.ui.html :as html]
   [seon.web.brand :as brand]
   [seon.web.datastar :as datastar]))

(schema/register! ::ring-request :map)
(schema/register! ::data-attribute [:or :nil :keyword])

(defn- write-status! [^js res status content-type body]
  (.writeHead res status
              #js {"Content-Type" content-type
                   "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res body))

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
        (header/system-header header/default-projection)
        header/header-spacer
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

(defn- database-unavailable! [res error]
  (write-status! res 503 "text/plain; charset=utf-8"
                 (or (:seon.error/message error) "database unavailable")))

(defn- debug-element [agent-id preview]
  [:main {:id "app-view" :class "flex flex-col gap-2 p-3"}
   (header/system-header header/default-projection)
   header/header-spacer
   [:h1 {:class "text-signal font-mono"} (str "debug · " agent-id)]
   (if-let [message (:seon.error/message preview)]
     [:div {:class "text-error"} message]
     [:pre {:id "debug-exact-prompt"
            :class "whitespace-pre-wrap text-xs"}
      (:seon.render/text preview)])])

(defn- render-debug! [agent-id database]
  (-> (agent-debug/ctx-preview
       {:seon.agent/id agent-id
        ::db/db database})
      (.then #(debug-element agent-id %))))

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
  (let [^js res (:seon.http/node-res request)
        agent-id (get-in request [:path-params :id])
        database (await (db/db))]
    (cond
      (:seon.error/message database) (database-unavailable! res database)
      (str/blank? agent-id) (write-status! res 404 "text/plain; charset=utf-8"
                                           "agent not found")
      :else
      (let [exists? (await (agent-exists? database agent-id))]
        (cond
          (:seon.error/message exists?) (database-unavailable! res exists?)
          exists?
          (let [view-id (datastar/new-view-id)]
            (write-status!
             res 200 "text/html; charset=utf-8"
             (page-html (str "agent " agent-id " · debug")
                        (str "/agent/" agent-id "/debug/feed?view=" view-id)
                        "loading debug view…")))
          :else
          (write-status! res 404 "text/plain; charset=utf-8"
                         (str "agent " agent-id " not found")))))))

(defn ^:async debug-feed!
  "Open the database-value-pinned exact-prompt feed for one agent."
  [request]
  (let [^js res (:seon.http/node-res request)
        agent-id (get-in request [:path-params :id])
        database (await (db/db))
        view-id (or (datastar/request-view-id request)
                    (datastar/new-view-id))]
    (cond
      (:seon.error/message database) (database-unavailable! res database)
      (str/blank? agent-id) (write-status! res 404 "text/plain; charset=utf-8"
                                           "agent not found")
      :else
      (let [exists? (await (agent-exists? database agent-id))]
        (cond
          (:seon.error/message exists?) (database-unavailable! res exists?)
          exists? (datastar/open-view-feed!
                   request (debug-feed-definition agent-id view-id))
          :else (write-status! res 404 "text/plain; charset=utf-8"
                               (str "agent " agent-id " not found")))))))

(defn- query-value [^js request name]
  (try
    (let [url (js/URL. (str "http://seon" (.-url request)))]
      (.get (.-searchParams url) name))
    (catch :default _ nil)))

(defn- data-attribute [^js request]
  (some-> (query-value request "attr") not-empty keyword))

(defn- data-element [page]
  [:main {:id "app-view" :class "flex flex-col gap-2 p-3"}
   (header/system-header header/default-projection)
   header/header-spacer
   [:div {:class "flex items-baseline gap-3"}
    [:h1 {:class "text-signal font-mono"} "database"]
    [:a {:href "/" :class "text-amber-500 hover:text-amber-300"}
     "← agents"]]
   (if-let [message (:seon.error/message page)]
     [:div {:class "text-error"} message]
     [:pre {:class "whitespace-pre-wrap text-xs"}
      (pr-str (::db/datoms page))])])

(defn- render-data! [database attribute]
  (-> (db/index-page
       (cond-> {::db/db database
                ::db/index :aevt
                ::db/direction :forward
                ::db/limit 50}
         attribute (assoc ::db/components [attribute])))
      (.then data-element)))

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
  [^js request ^js response]
  (let [view-id (datastar/new-view-id)
        attribute (data-attribute request)
        query (if attribute
                (str "&attr=" (js/encodeURIComponent (subs (str attribute) 1)))
                "")]
    (write-status!
     response 200 "text/html; charset=utf-8"
     (page-html "data" (str "/data/feed?view=" view-id query)
                "loading database…"))))

(defn data-feed!
  "Open the remote bounded-index database-browser feed."
  [request]
  (let [node-request (:seon.http/node-req request)
        view-id (or (datastar/request-view-id request)
                    (datastar/new-view-id))]
    (datastar/open-view-feed!
     request (data-feed-definition (data-attribute node-request) view-id))))
