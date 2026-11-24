(ns seon.web.router
  "Data-driven Reitit routing over `:seon.route/*` entities in Datahike
   (forward match, `:show-user` op decoding, infra matching, and reverse `path-for`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [reitit.core :as r]
            [reitit.trie]
            [seon.render.data :as rdata]
            [seon.store :as store :refer [db-schema]]))

(defonce ^:private ^:seon.flow/edge router-cache (store/lru-cache 32))

(def ^:private infra-route-ids
  {:seon.route/datastar-js :datastar-js
   :seon.route/op          :op
   :seon.route/mcp         :mcp
   :seon.route/call        :call
   :seon.route/preview     :preview})

(defn- db-cache-key [db]
  (when (instance? datahike.db.DB db)
    [(get-in db [:config :store :id])
     (get-in db [:config :branch] :main)
     (d/commit-id db)
     (:max-tx db)
     (System/identityHashCode (:eavt db))]))

(defn compile-router
  "Compiles a Reitit route vector into a live router (detecting path and name conflicts at build time)."
  {:malli/schema [:=> [:cat [:vector {:gen/elements [[["/" {:name :seon.route/home}]]
                                                     [["/a" {:name :a}] ["/b/{id}" {:name :b}]]]} :any]] :any]
   :cost/ticks 5000000}
  [routes]
  (r/router routes))

(defn- entities->reitit-vec [ents]
  (->> ents
       (sort-by (fn [e] [(str (:seon.route/pattern e)) (str (:seon.route/name e))]))
       (mapv (fn [{:seon.route/keys [name pattern method handler doc]}]
               [pattern (cond-> {:name name :method method :handler handler}
                          doc (assoc :doc doc)
                          (get infra-route-ids name) (assoc :route/id (get infra-route-ids name)))]))))

(defn- split-uri [uri]
  (let [raw (str/trim (str (or uri "/")))
        raw (if (str/blank? raw) "/" raw)
        qidx (str/index-of raw "?")
        p0 (if qidx (subs raw 0 qidx) raw)
        qs (when qidx (subs raw (inc qidx)))
        p1 (if (and (> (count p0) 1) (str/ends-with? p0 "/")) (subs p0 0 (dec (count p0))) p0)]
    [(if (str/blank? p1) "/" p1) qs]))

(defn- explain-query? [qmap]
  (contains? #{"1" "true" "yes"} (str/lower-case (str (get qmap "explain" "")))))

(defn- parse-uuid-safe [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (java.util.UUID/fromString (str/trim s)) (catch IllegalArgumentException _ nil))))

(defn- parse-long-safe [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (Long/parseLong (str/trim s)) (catch NumberFormatException _ nil))))

(defn- kw->attr-slug [k]
  (if (namespace k) (str (namespace k) "~" (name k)) (name k)))

(defn- attr-slug->kw [s]
  (let [t (str/trim (str s))]
    (when-not (str/blank? t)
      (cond
        (str/includes? t "~") (let [[ns-p nm] (str/split t #"~" 2)]
                                 (when (and (seq ns-p) (seq nm)) (keyword ns-p nm)))
        (str/includes? t "/") (let [[ns-p nm] (str/split t #"/" 2)]
                                 (when (and (seq ns-p) (seq nm)) (keyword ns-p nm)))
        :else nil))))

(defn- decode-entity-val [db attr raw-val]
  (let [s (str raw-val)
        vt (get-in (db-schema db) [attr :db/valueType])]
    (cond
      (str/blank? s) nil
      (= attr :db/id) (parse-long-safe s)
      (= vt :db.type/string)
      (if (and (>= (count s) 2) (str/starts-with? s "\"") (str/ends-with? s "\""))
        (try (let [p (binding [*read-eval* false] (edn/read-string s))] (if (string? p) p s))
             (catch RuntimeException _ s))
        s)
      (parse-uuid-safe s) (parse-uuid-safe s)
      :else
      (let [parsed (try (binding [*read-eval* false] (edn/read-string s))
                        (catch RuntimeException _ ::unparsed))]
        (cond
          (not= parsed ::unparsed)
          (cond
            (and (= vt :db.type/uuid) (string? parsed)) (or (parse-uuid-safe parsed) parsed)
            (and (= vt :db.type/uuid) (symbol? parsed)) (or (parse-uuid-safe s) s)
            (and (= vt :db.type/keyword) (symbol? parsed)) (keyword (namespace parsed) (name parsed))
            :else parsed)
          (= vt :db.type/uuid) (parse-uuid-safe s)
          (= vt :db.type/keyword) (keyword s)
          :else s)))))

(defn- inspect-op
  ([ref explain?] (inspect-op ref explain? nil))
  ([ref explain? tx]
   (when ref
     {:op :show-user :inspect-ref ref :mode (if explain? :context :inspect)
      :keep-inspect? true :as-of-tx (or tx :live)})))

(defn- live-db [db]
  (when (instance? datahike.db.interface.ISearch db) db))

(defn- parse-query-map [qs]
  (if (str/blank? qs)
    {}
    (into {}
          (keep (fn [part]
                  (when (seq part)
                    (let [[k v] (str/split part #"=" 2)
                          dec (fn [s] (try (java.net.URLDecoder/decode (or s "") "UTF-8")
                                           (catch IllegalArgumentException _ (or s ""))
                                           (catch IndexOutOfBoundsException _ (or s ""))))]
                      (when (seq k) [(dec k) (dec (or v ""))])))))
          (str/split qs #"&"))))

(defn- append-query [url kvs]
  (let [pairs (keep (fn [[k v]] (when (some? v) (str k "=" v))) kvs)]
    (if (seq pairs)
      (str url (if (str/includes? url "?") "&" "?") (str/join "&" pairs))
      url)))

(defn- branch-kw->slug [k]
  (cond
    (= k :main) "main"
    (= "branch" (namespace k)) (name k)
    (namespace k) (str (namespace k) "~" (name k))
    :else (str "~" (name k))))

(defn- slug->branch-kw [s]
  (let [t (str/trim (str (or s "")))]
    (cond
      (str/blank? t) nil
      (= t "main") :main
      (str/starts-with? t "~") (when (> (count t) 1) (keyword (subs t 1)))
      (str/includes? t "~") (let [[ns-p nm] (str/split t #"~" 2)] (when (and (seq ns-p) (seq nm)) (keyword ns-p nm)))
      (str/includes? t "/") (let [[ns-p nm] (str/split t #"/" 2)] (when (and (seq ns-p) (seq nm)) (keyword ns-p nm)))
      :else (keyword "branch" t))))

(defn- root-agent-id [db]
  (or (when (live-db db) (rdata/entry-agent-id db)) :agent/root))

(defn- first-doc-section [ldb doc-path]
  (let [dh (db-schema ldb)]
    (when (and ldb (contains? dh :seon.code.doc/path) (contains? dh :seon.code.doc/id))
      (->> (d/datoms ldb :avet :seon.code.doc/path doc-path)
           (map #(d/pull ldb (quote [:seon.code.doc/id :seon.code.doc/order]) (:e %)))
           (filter :seon.code.doc/id)
           (sort-by #(long (or (:seon.code.doc/order %) 0)))
           first :seon.code.doc/id))))

(defn- resolve-doc-id [db raw-path section]
  (let [p (str/replace (str raw-path) #"^/+" "")
        ldb (live-db db)
        dh (db-schema ldb)
        doc-path (cond
                   (str/starts-with? p "docs/") p
                   (= p "AGENTS.md") p
                   (and ldb (contains? dh :seon.code.doc/path))
                   (let [cand (str "docs/" p)]
                     (cond
                       (first (d/datoms ldb :avet :seon.code.doc/path cand)) cand
                       (first (d/datoms ldb :avet :seon.code.doc/path p)) p
                       :else cand))
                   :else (str "docs/" p))]
    (if (not (str/blank? section))
      (str doc-path "#" section)
      (or (first-doc-section ldb doc-path) doc-path))))

(defn- route-ent [nm pat meth h doc]
  {:seon.route/name nm :seon.route/pattern pat :seon.route/method meth
   :seon.route/handler h :seon.route/doc doc})

(defn core-routes-tx
  "Returns idempotent `:seon.route/*` entity maps covering all bookmarkable UI views and HTTP endpoints."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000}
  []
  (mapv (fn [[nm pat meth h doc]] (route-ent nm pat meth h doc))
        [[:seon.route/home             "/"                     :get  (quote seon.web/navigate-url!)        "Home view (Root agent Canvas)."]
         [:seon.route/system           "/system"               :get  (quote seon.web/set-view-mode!)       "Seon Runtime system entity view."]
         [:seon.route/canvas           "/canvas"               :get  (quote seon.web/set-view-mode!)       "Root Canvas view (alias)."]
         [:seon.route/chat             "/chat"                 :get  (quote seon.web/set-view-mode!)       "Root agent Chat view."]
         [:seon.route/session          "/session"              :get  (quote seon.web/set-view-mode!)       "Root agent Chat view (alias)."]
         [:seon.route/explain          "/explain"              :get  (quote seon.web/set-explain-lens!)    "Root agent Explain / Context Lab view."]
         [:seon.route/context          "/context"              :get  (quote seon.web/set-view-mode!)       "Context & Cache explain view (alias)."]
         [:seon.route/root-debug       "/root/debug"           :get  (quote seon.web/set-view-mode!)       "Root agent Context & Debug view (alias)."]
         [:seon.route/agent-session    "/agent/{slug}"         :get  (quote seon.web/view-agent!)          "Agent Canvas view for {slug}."]
         [:seon.route/agent-canvas     "/agent/{slug}/canvas"  :get  (quote seon.web/view-agent!)          "Agent Canvas view for {slug} (alias)."]
         [:seon.route/agent-chat       "/agent/{slug}/chat"    :get  (quote seon.web/view-agent!)          "Agent Chat view for {slug}."]
         [:seon.route/agent-chat-alias "/agent/{slug}/session" :get  (quote seon.web/view-agent!)          "Agent Chat view for {slug} (alias)."]
         [:seon.route/agent-inspect    "/agent/{slug}/inspect" :get  (quote seon.web/inspect-entity!)      "Inspect agent entity [:seon.agent/id aid]."]
         [:seon.route/agent-explain    "/agent/{slug}/explain" :get  (quote seon.web/set-explain-lens!)    "Agent Explain / Context Lab view for {slug}."]
         [:seon.route/agent-context    "/agent/{slug}/context" :get  (quote seon.web/view-agent!)          "Agent Context view for {slug} (alias)."]
         [:seon.route/agent-debug      "/agent/{slug}/debug"   :get  (quote seon.web/view-agent!)          "Agent Debug view for {slug} (alias)."]
         [:seon.route/agent-system     "/agent/{slug}/system"  :get  (quote seon.web/view-agent!)          "Agent System view for {slug} (alias)."]
         [:seon.route/agent-tx         "/agent/{slug}/tx/{tx}" :get  (quote seon.web/jump-canvas-tx!)      "Time-travel d/as-of view for agent {slug} at tx {tx} (alias)."]
         [:seon.route/signal           "/signals/{kind}"       :get  (quote seon.web/inspect-entity!)      "Inspect ambient signal [:seon.signal/kind :kind]."]
         [:seon.route/branch           "/branches/{*name}"     :get  (quote seon.web/inspect-entity!)      "Inspect Datahike branch [:seon.branch/name br]."]
         [:seon.route/docs             "/docs/{*path}"         :get  (quote seon.web/inspect-entity!)      "Inspect documentation path with optional ?section={slug}."]
         [:seon.route/var              "/var/{ns}/{name}"      :get  (quote seon.web/inspect-entity!)      "Inspect code var [:seon.code.var/name ns/name]."]
         [:seon.route/ns               "/ns/{name}"            :get  (quote seon.web/inspect-entity!)      "Inspect code namespace [:seon.code.ns/name name]."]
         [:seon.route/task             "/task/{ns}/{name}"     :get  (quote seon.web/inspect-entity!)      "Inspect task [:seon.task/id :ns/name]."]
         [:seon.route/rule             "/rule/{ns}/{name}"     :get  (quote seon.web/inspect-entity!)      "Inspect rule [:seon.rule/id :ns/name]."]
         [:seon.route/schema           "/schema/{ns}/{name}"   :get  (quote seon.web/inspect-entity!)      "Inspect Malli schema [:seon.schema/key :ns/name]."]
         [:seon.route/fix              "/fix/{id}"             :get  (quote seon.web/inspect-entity!)      "Inspect class-fix record [:seon.fix/id uuid]."]
         [:seon.route/error            "/error/{id}"           :get  (quote seon.web/inspect-entity!)      "Inspect typed error [:seon.error/id uuid]."]
         [:seon.route/doc              "/doc/{*id}"            :get  (quote seon.web/inspect-entity!)      "Inspect documentation section [:seon.code.doc/id id] (alias)."]
         [:seon.route/entity           "/entity/{attr}/{*val}" :get  (quote seon.web/inspect-entity!)      "Generic entity inspector for arbitrary lookup ref."]
         [:seon.route/events           "/events"               :get  (quote seon.web/start-http-listener!) "Datastar SSE live event stream."]
         [:seon.route/preview          "/preview"              :get  (quote seon.web/handle-http-request!)  "Entity hovercard preview HTML fragment."]
         [:seon.route/call             "/call"                 :post (quote seon.web/invoke-call!)         "Callable Reagent/Datastar action endpoint."]
         [:seon.route/op               "/op"                   :post (quote seon.web/call-server-edge!)    "EDN server op boundary endpoint."]
         [:seon.route/mcp              "/mcp"                  :post (quote seon.web/handle-mcp-jsonrpc!)  "MCP JSON-RPC 2.0 endpoint."]
         [:seon.route/datastar-js      "/datastar.js"          :get  (quote seon.web/handle-http-request!)  "Datastar browser runtime script."]]))

(defn missing-core-routes
  "Returns `:seon.route/*` maps from `core-routes-tx` not yet seeded in `db`."
  {:malli/schema [:=> [:cat :any] [:vector :map]]
   :cost/ticks 2000000}
  [db]
  (let [dh (db-schema db)]
    (if-not (and db (contains? dh :seon.route/name))
      []
      (let [existing (into #{} (map :v) (d/datoms db :aevt :seon.route/name))]
        (filterv #(not (contains? existing (:seon.route/name %))) (core-routes-tx))))))

(def ^:private default-router
  (compile-router (entities->reitit-vec (core-routes-tx))))

(defn db->routes
  "Projects `:seon.route/*` entities from `db` into a compiled Reitit router (cached by commit-id),
   falling back to `core-routes-tx` on nil or unseeded test DBs."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :any]
   :cost/ticks 10000000}
  [db]
  (if-not (and db (instance? datahike.db.interface.ISearch db)
               (contains? (db-schema db) :seon.route/name))
    default-router
    (let [compute (fn []
                    (let [ents (->> (d/datoms db :aevt :seon.route/name)
                                    (map #(d/pull db '[:seon.route/name :seon.route/pattern
                                                       :seon.route/method :seon.route/handler
                                                       :seon.route/doc] (:e %)))
                                    (filter #(and (:seon.route/name %) (:seon.route/pattern %)))
                                    vec)]
                      (if (seq ents)
                        (compile-router (entities->reitit-vec ents))
                        default-router)))]
      (if-let [ck (db-cache-key db)]
        (store/lru-get! router-cache 32 ck compute)
        (compute)))))

(defn path-for
  "Reverse-routes `route-name` and `params` to a canonical URL path via `reitit.core/match-by-name`."
  {:malli/schema [:function
                  [:=> [:cat :any :qualified-keyword] [:maybe :string]]
                  [:=> [:cat :any :qualified-keyword [:maybe :map]] [:maybe :string]]]
   :cost/ticks 1000000}
  ([db route-name] (path-for db route-name {}))
  ([db route-name params]
   (let [m (r/match-by-name (db->routes (live-db db)) route-name (or params {}))]
     (when (and m (not (r/partial-match? m)))
       (:path m)))))

(defn match-path
  "Matches URL string `uri` (optional `?query`) against the compiled Reitit router for `db`."
  {:malli/schema [:=> [:cat :any [:maybe :string]] [:maybe :map]]
   :cost/ticks 1000000}
  [db uri]
  (let [[path qs] (split-uri uri)
        safe-path (str/replace path "+" "%2B")
        rt (db->routes (live-db db))]
    (when-let [m (try (r/match-by-path rt safe-path)
                      (catch IllegalArgumentException _ nil)
                      (catch IndexOutOfBoundsException _ nil))]
      (let [d (:data m)
            qmap (parse-query-map qs)]
        {:route/name (:name d)
         :route/pattern (:template m)
         :route/method (:method d)
         :route/handler (:handler d)
         :route/doc (:doc d)
         :route/id (:route/id d)
         :path-params (or (:path-params m) {})
         :query-string qs
         :query-params qmap
         :explain? (explain-query? qmap)
         :tx (parse-long-safe (get qmap "tx"))
         :section (not-empty (get qmap "section"))
         :path path}))))

(defn match-http-route
  "Matches Ring request `req` against infrastructure HTTP routes in `db` (or optional custom `routes` vector)."
  {:malli/schema [:function
                  [:=> [:cat :any [:map [:request-method [:enum :get :post :put :delete]] [:uri :string]]] [:maybe :map]]
                  [:=> [:cat :any [:map [:request-method [:enum :get :post :put :delete]] [:uri :string]]
                        [:vector {:gen/elements [[{:route/id :mcp :route/method :post :route/path "/mcp"}]]} :map]]
                   [:maybe :map]]]
   :cost/ticks 2000000}
  ([db req]
   (when-let [m (match-path db (:uri req))]
     (when (and (:route/id m) (= (:request-method req) (:route/method m)))
       {:route/id (:route/id m) :route/method (:route/method m) :route/path (:route/pattern m)})))
  ([_db req routes]
   (let [m (:request-method req)
         [path _] (split-uri (:uri req))
         valid (filter #(and (string? (:route/path %)) (str/starts-with? (:route/path %) "/")) routes)
         by-path (group-by :route/path valid)]
     (when (seq by-path)
       (let [rt (compile-router (mapv (fn [[p rs]] [p {:entries rs}]) by-path))]
         (when-let [hit (r/match-by-path rt path)]
           (some #(when (= m (:route/method %)) %) (get-in hit [:data :entries]))))))))

(defn- doc-id->path [db doc-id]
  (let [[raw-p sec] (str/split doc-id #"#" 2)
        url-p (if (str/starts-with? raw-p "docs/") (subs raw-p 5) raw-p)
        base (when-let [p (path-for db :seon.route/docs {:path url-p})]
               (str/replace p "%2F" "/"))]
    (when base
      (append-query base (when (not (str/blank? sec)) [["section" sec]])))))

(defn entity-ref->path
  "Reverse-routes any Datahike lookup ref `[attr val]` into its clean bookmarkable URL via Reitit."
  {:malli/schema [:=> [:cat :any :any] :string]
   :cost/ticks 1500000}
  [db ref]
  (if-let [[a v] (rdata/normalize-inspect-ref ref)]
    (or
     (case a
       :seon.system/id
       (if (= v :root)
         (path-for db :seon.route/system {})
         (path-for db :seon.route/entity {:attr (kw->attr-slug a) :val (pr-str v)}))
       :seon.agent/id
       (when (keyword? v)
         (when-let [slug (rdata/agent-id->slug v)]
           (path-for db :seon.route/agent-inspect {:slug slug})))
       :seon.signal/kind
       (when (keyword? v)
         (path-for db :seon.route/signal {:kind (name v)}))
       :seon.branch/name
       (when (keyword? v)
         (when-let [p (path-for db :seon.route/branch {:name (branch-kw->slug v)})]
           (str/replace p "%2F" "/")))
       :seon.code.var/name
       (when (qualified-symbol? v)
         (path-for db :seon.route/var {:ns (namespace v) :name (name v)}))
       :seon.code.ns/name
       (when (symbol? v)
         (path-for db :seon.route/ns {:name (str v)}))
       :seon.task/id
       (when (qualified-keyword? v)
         (path-for db :seon.route/task {:ns (namespace v) :name (name v)}))
       :seon.rule/id
       (when (qualified-keyword? v)
         (path-for db :seon.route/rule {:ns (namespace v) :name (name v)}))
       :seon.schema/key
       (when (qualified-keyword? v)
         (path-for db :seon.route/schema {:ns (namespace v) :name (name v)}))
       :seon.fix/id
       (path-for db :seon.route/fix {:id (str v)})
       :seon.error/id
       (path-for db :seon.route/error {:id (str v)})
       :seon.code.doc/id
       (when (and (string? v) (not (str/blank? v)))
         (doc-id->path db v))
       nil)
     (path-for db :seon.route/entity {:attr (kw->attr-slug a) :val (if (uuid? v) (str v) (pr-str v))})
     "/")
    "/"))

(defn user-state->path
  "Derives the canonical bookmarkable URL path (plus orthogonal `?explain=1` / `?tx=<n>`) for user view state `vs`."
  {:malli/schema [:=> [:cat :any [:maybe :map]] :string]
   :cost/ticks 1500000}
  [db vs]
  (let [{:keys [viewing-id view-mode as-of-tx inspect-ref]} (if (map? vs) vs {})
        canon (rdata/canonical-view-mode view-mode)
        explain? (= canon :context)
        nref (rdata/normalize-inspect-ref inspect-ref)
        tx (when (integer? as-of-tx) (long as-of-tx))
        rid (root-agent-id db)
        base (cond
               nref (entity-ref->path db nref)
               (= canon :system) (or (path-for db :seon.route/system {}) "/system")
               (or (nil? viewing-id) (= viewing-id rid))
               (cond explain? (or (path-for db :seon.route/explain {}) "/explain")
                     (= canon :session) (or (path-for db :seon.route/chat {}) "/chat")
                     :else (or (path-for db :seon.route/home {}) "/"))
               :else
               (if-let [slug (rdata/agent-id->slug viewing-id)]
                 (cond explain? (or (path-for db :seon.route/agent-explain {:slug slug}) "/")
                       (= canon :session) (or (path-for db :seon.route/agent-chat {:slug slug}) "/")
                       :else (or (path-for db :seon.route/agent-session {:slug slug}) "/"))
                 (if explain? "/explain" "/")))]
    (append-query base (cond-> []
                         (and nref explain?) (conj ["explain" "1"])
                         tx (conj ["tx" tx])))))

(defn path->show-user-op
  "Decodes any bookmarkable URL (views, entity routes, optional `?explain=1`, `?tx=<n>`, `?section=<slug>`) into `{:op :show-user ...}`."
  {:malli/schema [:=> [:cat :any [:maybe :string]] [:maybe :map]]
   :cost/ticks 1500000}
  [db uri]
  (when-let [{:keys [path-params explain? tx section] rname :route/name} (match-path db uri)]
    (let [eff-tx (or tx :live)]
      (case rname
        (:seon.route/home :seon.route/canvas)
        {:op :show-user :viewing (root-agent-id db) :mode (if explain? :context :canvas)
         :clear-inspect true :as-of-tx eff-tx}
        :seon.route/system
        (if explain?
          (inspect-op [:seon.system/id :root] true tx)
          {:op :show-user :mode :system :clear-inspect true :as-of-tx eff-tx})
        (:seon.route/chat :seon.route/session)
        {:op :show-user :viewing (root-agent-id db) :mode (if explain? :context :session)
         :clear-inspect true :as-of-tx eff-tx}
        (:seon.route/explain :seon.route/context :seon.route/root-debug)
        {:op :show-user :viewing (root-agent-id db) :mode :context
         :clear-inspect true :as-of-tx eff-tx}
        (:seon.route/agent-session :seon.route/agent-canvas)
        (when-let [aid (rdata/slug->agent-id (:slug path-params))]
          {:op :show-user :viewing aid :mode (if explain? :context :canvas) :clear-inspect true :as-of-tx eff-tx})
        (:seon.route/agent-chat :seon.route/agent-chat-alias)
        (when-let [aid (rdata/slug->agent-id (:slug path-params))]
          {:op :show-user :viewing aid :mode (if explain? :context :session) :clear-inspect true :as-of-tx eff-tx})
        :seon.route/agent-inspect
        (when-let [aid (rdata/slug->agent-id (:slug path-params))]
          (inspect-op [:seon.agent/id aid] explain? tx))
        (:seon.route/agent-explain :seon.route/agent-context :seon.route/agent-debug)
        (when-let [aid (rdata/slug->agent-id (:slug path-params))]
          {:op :show-user :viewing aid :mode :context :clear-inspect true :as-of-tx eff-tx})
        :seon.route/agent-system
        (when-let [aid (rdata/slug->agent-id (:slug path-params))]
          {:op :show-user :viewing aid :mode :system :clear-inspect true :as-of-tx eff-tx})
        :seon.route/agent-tx
        (when-let [aid (rdata/slug->agent-id (:slug path-params))]
          (when-let [ptx (parse-long-safe (:tx path-params))]
            {:op :show-user :viewing aid :mode (if explain? :context :canvas) :as-of-tx ptx :clear-inspect true}))
        :seon.route/signal
        (when-let [k (not-empty (:kind path-params))]
          (inspect-op [:seon.signal/kind (keyword k)] explain? tx))
        :seon.route/branch
        (when-let [bk (slug->branch-kw (:name path-params))]
          (inspect-op [:seon.branch/name bk] explain? tx))
        :seon.route/docs
        (when-let [raw-p (not-empty (:path path-params))]
          (inspect-op [:seon.code.doc/id (resolve-doc-id db raw-p section)] explain? tx))
        :seon.route/var
        (when (and (seq (:ns path-params)) (seq (:name path-params)))
          (inspect-op [:seon.code.var/name (symbol (:ns path-params) (:name path-params))] explain? tx))
        :seon.route/ns
        (when (seq (:name path-params))
          (inspect-op [:seon.code.ns/name (symbol (:name path-params))] explain? tx))
        :seon.route/task
        (when (and (seq (:ns path-params)) (seq (:name path-params)))
          (inspect-op [:seon.task/id (keyword (:ns path-params) (:name path-params))] explain? tx))
        :seon.route/rule
        (when (and (seq (:ns path-params)) (seq (:name path-params)))
          (inspect-op [:seon.rule/id (keyword (:ns path-params) (:name path-params))] explain? tx))
        :seon.route/schema
        (when (and (seq (:ns path-params)) (seq (:name path-params)))
          (inspect-op [:seon.schema/key (keyword (:ns path-params) (:name path-params))] explain? tx))
        :seon.route/fix
        (inspect-op (when-let [u (parse-uuid-safe (:id path-params))] [:seon.fix/id u]) explain? tx)
        :seon.route/error
        (inspect-op (when-let [u (parse-uuid-safe (:id path-params))] [:seon.error/id u]) explain? tx)
        :seon.route/doc
        (when-let [raw-id (not-empty (:id path-params))]
          (let [ldb (live-db db)
                dh (db-schema ldb)
                resolved (if (and ldb (contains? dh :seon.code.doc/id)
                                  (not (str/includes? raw-id "#"))
                                  (nil? (first (d/datoms ldb :avet :seon.code.doc/id raw-id))))
                           (or (first-doc-section ldb raw-id) raw-id)
                           raw-id)]
            (inspect-op [:seon.code.doc/id resolved] explain? tx)))
        :seon.route/entity
        (when-let [a (attr-slug->kw (:attr path-params))]
          (let [v (decode-entity-val db a (:val path-params))]
            (when (some? v) (inspect-op [a v] explain? tx))))
        nil))))