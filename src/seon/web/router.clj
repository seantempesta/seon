(ns seon.web.router
  "Data-driven Reitit routing over `:seon.route/*` entities in Datahike
   (forward match, `:show-user` op decoding, infra matching, and reverse `path-for`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [reitit.core :as r]
            [reitit.trie]
            [seon.render.data :as rdata]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge router-cache (store/lru-cache 32))

(def ^:private infra-route-ids
  {:seon.route/datastar-js :datastar-js
   :seon.route/op          :op
   :seon.route/mcp         :mcp
   :seon.route/call        :call
   :seon.route/preview     :preview})

(defn- db-schema [db]
  (if (instance? datahike.db.interface.ISearch db)
    (or (:schema db) (d/schema db) {})
    {}))

(defn- db-cache-key [db]
  (when (instance? datahike.db.DB db)
    [(get-in db [:config :store :id])
     (get-in db [:config :branch] :main)
     (d/commit-id db)
     (:max-tx db)
     (System/identityHashCode (:eavt db))]))

(defn core-routes-tx
  "Returns idempotent `:seon.route/*` entity maps covering all bookmarkable UI views and HTTP endpoints."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000}
  []
  [{:seon.route/name :seon.route/home          :seon.route/pattern "/"                     :seon.route/method :get  :seon.route/handler 'seon.web/navigate-url!       :seon.route/doc "Home view (System root / active session)."}
   {:seon.route/name :seon.route/system        :seon.route/pattern "/system"               :seon.route/method :get  :seon.route/handler 'seon.web/set-view-mode!      :seon.route/doc "Seon Runtime system entity view."}
   {:seon.route/name :seon.route/session       :seon.route/pattern "/session"              :seon.route/method :get  :seon.route/handler 'seon.web/set-view-mode!      :seon.route/doc "Active agent session view."}
   {:seon.route/name :seon.route/context       :seon.route/pattern "/context"              :seon.route/method :get  :seon.route/handler 'seon.web/set-view-mode!      :seon.route/doc "Context & Cache explain view."}
   {:seon.route/name :seon.route/root-debug    :seon.route/pattern "/root/debug"           :seon.route/method :get  :seon.route/handler 'seon.web/set-view-mode!      :seon.route/doc "Root agent Context & Debug view."}
   {:seon.route/name :seon.route/agent-session :seon.route/pattern "/agent/{slug}"         :seon.route/method :get  :seon.route/handler 'seon.web/view-agent!         :seon.route/doc "Agent session view for {slug}."}
   {:seon.route/name :seon.route/agent-explain :seon.route/pattern "/agent/{slug}/explain" :seon.route/method :get  :seon.route/handler 'seon.web/set-explain-lens!   :seon.route/doc "Agent Explain / Context Lab view for {slug}."}
   {:seon.route/name :seon.route/agent-context :seon.route/pattern "/agent/{slug}/context" :seon.route/method :get  :seon.route/handler 'seon.web/view-agent!         :seon.route/doc "Agent Context view for {slug}."}
   {:seon.route/name :seon.route/agent-debug   :seon.route/pattern "/agent/{slug}/debug"   :seon.route/method :get  :seon.route/handler 'seon.web/view-agent!         :seon.route/doc "Agent Debug view for {slug}."}
   {:seon.route/name :seon.route/agent-system  :seon.route/pattern "/agent/{slug}/system"  :seon.route/method :get  :seon.route/handler 'seon.web/view-agent!         :seon.route/doc "Agent System view for {slug}."}
   {:seon.route/name :seon.route/agent-tx      :seon.route/pattern "/agent/{slug}/tx/{tx}" :seon.route/method :get  :seon.route/handler 'seon.web/jump-canvas-tx!     :seon.route/doc "Time-travel d/as-of view for agent {slug} at tx {tx}."}
   {:seon.route/name :seon.route/var           :seon.route/pattern "/var/{ns}/{name}"      :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Inspect code var [:seon.code.var/name ns/name]."}
   {:seon.route/name :seon.route/ns            :seon.route/pattern "/ns/{name}"            :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Inspect code namespace [:seon.code.ns/name name]."}
   {:seon.route/name :seon.route/task          :seon.route/pattern "/task/{ns}/{name}"     :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Inspect task [:seon.task/id :ns/name]."}
   {:seon.route/name :seon.route/rule          :seon.route/pattern "/rule/{ns}/{name}"     :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Inspect rule [:seon.rule/id :ns/name]."}
   {:seon.route/name :seon.route/schema        :seon.route/pattern "/schema/{ns}/{name}"   :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Inspect Malli schema [:seon.schema/key :ns/name]."}
   {:seon.route/name :seon.route/fix           :seon.route/pattern "/fix/{id}"             :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Inspect class-fix record [:seon.fix/id uuid]."}
   {:seon.route/name :seon.route/error         :seon.route/pattern "/error/{id}"           :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Inspect typed error [:seon.error/id uuid]."}
   {:seon.route/name :seon.route/doc           :seon.route/pattern "/doc/{*id}"            :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Inspect documentation section [:seon.code.doc/id id]."}
   {:seon.route/name :seon.route/entity        :seon.route/pattern "/entity/{attr}/{*val}" :seon.route/method :get  :seon.route/handler 'seon.web/inspect-entity!     :seon.route/doc "Generic entity inspector for arbitrary lookup ref."}
   {:seon.route/name :seon.route/events        :seon.route/pattern "/events"               :seon.route/method :get  :seon.route/handler 'seon.web/start-http-listener! :seon.route/doc "Datastar SSE live event stream."}
   {:seon.route/name :seon.route/preview       :seon.route/pattern "/preview"              :seon.route/method :get  :seon.route/handler 'seon.web/handle-http-request! :seon.route/doc "Entity hovercard preview HTML fragment."}
   {:seon.route/name :seon.route/call          :seon.route/pattern "/call"                 :seon.route/method :post :seon.route/handler 'seon.web/invoke-call!         :seon.route/doc "Callable Reagent/Datastar action endpoint."}
   {:seon.route/name :seon.route/op            :seon.route/pattern "/op"                   :seon.route/method :post :seon.route/handler 'seon.web/call-server-edge!    :seon.route/doc "EDN server op boundary endpoint."}
   {:seon.route/name :seon.route/mcp           :seon.route/pattern "/mcp"                  :seon.route/method :post :seon.route/handler 'seon.web/handle-mcp-jsonrpc!  :seon.route/doc "MCP JSON-RPC 2.0 endpoint."}
   {:seon.route/name :seon.route/datastar-js   :seon.route/pattern "/datastar.js"          :seon.route/method :get  :seon.route/handler 'seon.web/handle-http-request! :seon.route/doc "Datastar browser runtime script."}])

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

(def ^:private default-router
  (delay (compile-router (entities->reitit-vec (core-routes-tx)))))

(defn db->routes
  "Projects `:seon.route/*` entities from `db` into a compiled Reitit router (cached by commit-id),
   falling back to `core-routes-tx` on nil or unseeded test DBs."
  {:malli/schema [:=> [:cat :any] :any]
   :cost/ticks 10000000}
  [db]
  (if-not (and db (instance? datahike.db.interface.ISearch db)
               (contains? (db-schema db) :seon.route/name))
    @default-router
    (let [compute (fn []
                    (let [ents (->> (d/datoms db :aevt :seon.route/name)
                                    (map #(d/pull db '[:seon.route/name :seon.route/pattern
                                                       :seon.route/method :seon.route/handler
                                                       :seon.route/doc] (:e %)))
                                    (filter #(and (:seon.route/name %) (:seon.route/pattern %)))
                                    vec)]
                      (if (seq ents)
                        (compile-router (entities->reitit-vec ents))
                        @default-router)))]
      (if-let [ck (db-cache-key db)]
        (store/lru-get! router-cache 32 ck compute)
        (compute)))))

(defn- split-uri [uri]
  (let [raw (str/trim (str (or uri "/")))
        raw (if (str/blank? raw) "/" raw)
        qidx (str/index-of raw "?")
        p0 (if qidx (subs raw 0 qidx) raw)
        qs (when qidx (subs raw (inc qidx)))
        p1 (if (and (> (count p0) 1) (str/ends-with? p0 "/")) (subs p0 0 (dec (count p0))) p0)]
    [(if (str/blank? p1) "/" p1) qs]))

(defn- explain-query? [qs]
  (boolean
   (when-not (str/blank? qs)
     (some (fn [part]
             (let [[k v] (str/split part #"=" 2)]
               (and (= "explain" k) (contains? #{"1" "true" "yes"} (str/lower-case (str v))))))
           (str/split qs #"&")))))

(defn- parse-uuid-safe [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (java.util.UUID/fromString (str/trim s)) (catch Throwable _ nil))))

(defn- parse-long-safe [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (Long/parseLong (str/trim s)) (catch Throwable _ nil))))

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
      :else
      (let [parsed (try (binding [*read-eval* false] (edn/read-string s)) (catch Throwable _ ::unparsed))]
        (cond
          (not= parsed ::unparsed)
          (cond
            (and (= vt :db.type/uuid) (string? parsed)) (or (parse-uuid-safe parsed) parsed)
            (and (= vt :db.type/uuid) (symbol? parsed)) (or (parse-uuid-safe s) s)
            (and (= vt :db.type/keyword) (symbol? parsed)) (keyword (namespace parsed) (name parsed))
            (and (= vt :db.type/string) (symbol? parsed)) s
            :else parsed)
          (= vt :db.type/uuid) (parse-uuid-safe s)
          (= vt :db.type/keyword) (keyword s)
          :else s)))))

(defn match-path
  "Matches URL string `uri` (optional `?query`) against the compiled Reitit router for `db`."
  {:malli/schema [:=> [:cat :any [:maybe :string]] [:maybe :map]]
   :cost/ticks 1000000}
  [db uri]
  (let [[path qs] (split-uri uri)
        rt (db->routes db)]
    (when-let [m (r/match-by-path rt path)]
      (let [d (:data m)]
        {:route/name (:name d)
         :route/pattern (:template m)
         :route/method (:method d)
         :route/handler (:handler d)
         :route/doc (:doc d)
         :route/id (:route/id d)
         :path-params (or (:path-params m) {})
         :query-string qs
         :explain? (explain-query? qs)
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

(defn- inspect-op [ref explain?]
  (when ref
    {:op :show-user :inspect-ref ref :mode (if explain? :context :inspect)
     :keep-inspect? true :as-of-tx :live}))

(defn path->show-user-op
  "Decodes any bookmarkable URL (agent views, time-travel tx, entity routes, optional `?explain=1`) into a `{:op :show-user ...}` map."
  {:malli/schema [:=> [:cat :any [:maybe :string]] [:maybe :map]]
   :cost/ticks 1500000}
  [db uri]
  (when-let [{:keys [path-params explain?] rname :route/name} (match-path db uri)]
    (case rname
      (:seon.route/home :seon.route/system)
      (if explain?
        (inspect-op [:seon.system/id :root] true)
        {:op :show-user :mode :system :clear-inspect true :as-of-tx :live})

      :seon.route/session
      {:op :show-user :mode (if explain? :context :session) :clear-inspect true :as-of-tx :live}

      :seon.route/context
      {:op :show-user :mode :context :clear-inspect true :as-of-tx :live}

      :seon.route/root-debug
      {:op :show-user :viewing (or (when db (rdata/entry-agent-id db)) :agent/root)
       :mode :context :clear-inspect true :as-of-tx :live}

      :seon.route/agent-session
      (when-let [aid (rdata/slug->agent-id (:slug path-params))]
        {:op :show-user :viewing aid :mode (if explain? :context :session) :clear-inspect true :as-of-tx :live})

      (:seon.route/agent-explain :seon.route/agent-context :seon.route/agent-debug)
      (when-let [aid (rdata/slug->agent-id (:slug path-params))]
        {:op :show-user :viewing aid :mode :context :clear-inspect true :as-of-tx :live})

      :seon.route/agent-system
      (when-let [aid (rdata/slug->agent-id (:slug path-params))]
        {:op :show-user :viewing aid :mode :system :clear-inspect true :as-of-tx :live})

      :seon.route/agent-tx
      (when-let [aid (rdata/slug->agent-id (:slug path-params))]
        (when-let [tx (parse-long-safe (:tx path-params))]
          {:op :show-user :viewing aid :mode (if explain? :context :session) :as-of-tx tx :clear-inspect true}))

      :seon.route/var
      (when (and (seq (:ns path-params)) (seq (:name path-params)))
        (inspect-op [:seon.code.var/name (symbol (:ns path-params) (:name path-params))] explain?))

      :seon.route/ns
      (when (seq (:name path-params))
        (inspect-op [:seon.code.ns/name (symbol (:name path-params))] explain?))

      :seon.route/task
      (when (and (seq (:ns path-params)) (seq (:name path-params)))
        (inspect-op [:seon.task/id (keyword (:ns path-params) (:name path-params))] explain?))

      :seon.route/rule
      (when (and (seq (:ns path-params)) (seq (:name path-params)))
        (inspect-op [:seon.rule/id (keyword (:ns path-params) (:name path-params))] explain?))

      :seon.route/schema
      (when (and (seq (:ns path-params)) (seq (:name path-params)))
        (inspect-op [:seon.schema/key (keyword (:ns path-params) (:name path-params))] explain?))

      :seon.route/fix
      (inspect-op (when-let [u (parse-uuid-safe (:id path-params))] [:seon.fix/id u]) explain?)

      :seon.route/error
      (inspect-op (when-let [u (parse-uuid-safe (:id path-params))] [:seon.error/id u]) explain?)

      :seon.route/doc
      (when (seq (:id path-params))
        (inspect-op [:seon.code.doc/id (:id path-params)] explain?))

      :seon.route/entity
      (when-let [a (attr-slug->kw (:attr path-params))]
        (let [v (decode-entity-val db a (:val path-params))]
          (when (some? v) (inspect-op [a v] explain?))))

      nil)))

(defn path-for
  "Reverse-routes `route-name` and `params` to a canonical URL path via `reitit.core/match-by-name`."
  {:malli/schema [:function
                  [:=> [:cat :any :qualified-keyword] [:maybe :string]]
                  [:=> [:cat :any :qualified-keyword [:maybe :map]] [:maybe :string]]]
   :cost/ticks 1000000}
  ([db route-name] (path-for db route-name {}))
  ([db route-name params]
   (let [m (r/match-by-name (db->routes db) route-name (or params {}))]
     (when (and m (not (r/partial-match? m)))
       (:path m)))))

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
           (path-for db :seon.route/agent-session {:slug slug})))

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
         (path-for db :seon.route/doc {:id v}))

       nil)
     (path-for db :seon.route/entity {:attr (kw->attr-slug a) :val (pr-str v)})
     "/")
    "/"))

(defn user-state->path
  "Derives the canonical bookmarkable URL path (plus `?explain=1` when explaining) for user view state `vs`."
  {:malli/schema [:=> [:cat :any [:maybe :map]] :string]
   :cost/ticks 1500000}
  [db vs]
  (let [{:keys [viewing-id view-mode as-of-tx inspect-ref]} (if (map? vs) vs {})
        canon (rdata/canonical-view-mode view-mode)
        explain? (= canon :context)
        nref (rdata/normalize-inspect-ref inspect-ref)]
    (cond
      nref
      (if (= :seon.agent/id (first nref))
        (let [slug (when (keyword? (second nref)) (rdata/agent-id->slug (second nref)))]
          (if slug
            (or (path-for db (if explain? :seon.route/agent-explain :seon.route/agent-session) {:slug slug}) "/")
            (let [p (entity-ref->path db nref)] (if explain? (str p "?explain=1") p))))
        (let [p (entity-ref->path db nref)]
          (if explain? (str p "?explain=1") p)))

      (= canon :system)
      (or (path-for db :seon.route/system {}) "/system")

      viewing-id
      (let [slug (rdata/agent-id->slug viewing-id)]
        (cond
          (not slug) (if explain? "/context" "/")
          (integer? as-of-tx)
          (let [base (or (path-for db :seon.route/agent-tx {:slug slug :tx (str as-of-tx)}) "/")]
            (if explain? (str base "?explain=1") base))
          explain? (or (path-for db :seon.route/agent-explain {:slug slug}) "/")
          :else (or (path-for db :seon.route/agent-session {:slug slug}) "/")))

      :else (if explain? "/context" "/"))))
