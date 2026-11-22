(ns seon.web.actions
  "Stateless, `^:seon/callable` UI actions dispatched from Datastar `?form=` calls (sidebar/view-mode/branch/canvas-history/chat forms), plus the `/call` & `/mcp` dispatch helpers (`invoke-call!`, `handle-mcp-jsonrpc!`, `mcp-tools`, `call-server-edge!`)."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.ui :as ui]
            [seon.store :as store]
            [seon.model.wire :as mwire]
            [hasch.core :as hasch]
            [seon.instruments :as inst]))

(defn parse-kw-input [^String s default-kw]
  (let [t (str/trim (str s))]
    (cond
      (str/blank? t) default-kw
      (str/starts-with? t ":") (edn/read-string t)
      :else (keyword t))))

(defn async-action [st req slot format-fn]
  {:st st :async {:req req :slot slot :format-fn format-fn}})

(defn ^:seon/callable toggle-sidebar! [st]
  (async-action st {:op :show-user :toggle-sidebar? true} nil nil))

(defn ^:seon/callable toggle-theme! [st]
  (async-action st {:op :show-user :toggle-theme? true} nil nil))

(defn ^:seon/callable toggle-expand! [st agent-id]
  (async-action st {:op :show-user :toggle-expand agent-id} nil nil))

(defn ^:seon/callable view-agent! [st agent-id mode]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :viewing agent-id :mode (or mode :workspace)}
                nil nil))

(defn ^:seon/callable set-view-mode! [st mode]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :mode mode}
                nil nil))

(defn ^:seon/callable select-branch! [st branch]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :selected-branch branch}
                nil nil))

(defn ^:seon/callable switch-tab! [st tab]
  (set-view-mode! st (case tab (:schemas :health :branches) :debug :agents :workspace :canvas)))

(defn ^:seon/callable power-loss-reboot! [st]
  (when-let [pl (:on-power-loss st)]
    (a/thread (pl)))
  {:st st})

(defn ^:seon/callable run-tests! [st branch]
  (async-action
   (assoc st :branch-feedback {:ok? true :text (str "Running reach tests on " branch "...")})
   {:op :test :branch branch}
   :branch-feedback
   (fn [ans]
     {:ok? (:ok? ans)
      :text (str "Tests on " branch ": " (if (:ok? ans) "PASS" "FAIL")
                 " (" (:ran ans 0) " ran, " (:cached ans 0) " cached"
                 (when (:short-circuited? ans) (str ", fail-fast skipped " (:skipped-slow ans) " slow")) ")")})))

(defn ^:seon/callable merge-branch! [st branch]
  (let [srv (:server-in-ch st)
        ev-ch (:event-ch st)]
    (when (and srv ev-ch)
      (a/go
        (let [t-ans (let [rc (a/chan 1)]
                      (a/>! srv {:req {:op :test :branch branch} :reply-ch rc})
                      (let [[v _] (a/alts! [rc (a/timeout 30000)])]
                        (or v {:ok? false :error :timeout})))]
          (if-not (:ok? t-ans)
            (a/>! ev-ch {:ev :action-result
                         :field :branch-feedback
                         :val {:ok? false
                               :text (str "Merge blocked on " branch ": tests failed ("
                                          (or (:error t-ans)
                                              (str (:ran t-ans 0) " ran, "
                                                   (count (remove :seon.test/pass? (:results t-ans))) " failed")) ")")}})
            (let [m-ans (let [rc (a/chan 1)]
                          (a/>! srv {:req {:op :merge :branch branch} :reply-ch rc})
                          (let [[v _] (a/alts! [rc (a/timeout 30000)])]
                            (or v {:ok? false :error :timeout})))]
              (a/>! ev-ch {:ev :action-result
                           :field :branch-feedback
                           :val {:ok? (:ok? m-ans)
                                 :text (if (:ok? m-ans)
                                         (str "Tested & merged " branch " -> :main @ tx " (:tx m-ans)
                                              " (" (count (:touched-paths m-ans)) " files)")
                                         (str "Blocked / Conflict: " (pr-str (or (:conflicts m-ans) (:error m-ans)))))}}))))))
    (assoc st :branch-feedback {:ok? true :text (str "Running reach tests before merging " branch " -> :main...")})))

(defn ^:seon/callable assign-health-fix! [st cls subject message]
  (let [tid (keyword "seon.health" (str "fix-" (mod (System/currentTimeMillis) 100000)))
        scope-ns (try
                   (let [s (str subject)]
                     (if (str/includes? s "/") (symbol (first (str/split s #"/" 2))) 'seon))
                   (catch Throwable _ 'seon))]
    (async-action
     (assoc st :branch-feedback {:ok? true :text (str "Spawned worker for health issue " cls " on " subject)})
     {:op :create-task :id tid :scope scope-ns :title (str "Fix " (name cls) " on " subject)
      :plan (str message) :spawn-agent? true :role :worker}
     nil nil)))

(defn ^:seon/callable spawn-agent-form!
  ([st form] (spawn-agent-form! st (ui/entry-agent-id @(:conn st)) form))
  ([st parent-id form]
   (let [aid (parse-kw-input (:agent_id form) (keyword "agent" (str "w" (mod (System/currentTimeMillis) 10000))))
         branch (keyword "branch" (str (or (namespace aid) "agent") "." (name aid)))
         scope (when-not (str/blank? (:scope form)) (symbol (str/trim (:scope form))))
         goal (str/trim (str (:goal form "")))]
     (async-action
      (assoc st :branch-feedback {:ok? true :text (str "Spawned " aid " under " parent-id " on " branch)})
      (cond-> {:op :spawn-agent :id aid :parent parent-id :branch branch :role :worker :goal goal}
        scope (assoc :scope scope))
      nil nil))))

(defn ^:seon/callable register-schema-form! [st form]
  (let [k (parse-kw-input (:key form) :seon.custom/item)]
    (async-action
     (assoc st :branch-feedback {:ok? true :text (str "Validating & deriving " k "...")})
     (cond-> {:op :register-schema :key k :form (:form form "")}
       (not (str/blank? (:doc form))) (assoc :doc (:doc form)))
     :branch-feedback
     (fn [ans]
       {:ok? (:ok? ans)
        :text (if (:ok? ans)
                (str "Registered " (:key ans)
                     (when (seq (:derived-attributes ans))
                       (str " + derived Datahike attr " (:derived-attributes ans)))
                     " — seed=42 sample: " (:sample ans))
                (str "Rejected: " (:error ans)))}))))

(defn ^:seon/callable step-canvas-history! [st delta]
  (let [db @(:conn st)
        {:keys [viewing-id as-of-tx]} (ui/user-view-state db)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        bdb (or (try (store/head (:conn st) branch) (catch Throwable _ nil)) db)
        txs (ui/canvas-history-txs bdb db viewing-id)
        n (count txs)]
    (if (< n 2)
      {:st st}
      (let [cur-idx (if as-of-tx
                      (or (first (keep-indexed (fn [i tx] (when (= tx as-of-tx) i)) txs)) (dec n))
                      (dec n))
            nxt-idx (max 0 (min (dec n) (+ cur-idx (long delta))))
            target (if (= nxt-idx (dec n)) :live (nth txs nxt-idx))]
        (async-action st {:op :show-user :as-of-tx target} nil nil)))))

(defn ^:seon/callable jump-canvas-tx! [st tx]
  (let [db @(:conn st)
        {:keys [viewing-id]} (ui/user-view-state db)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        bdb (or (try (store/head (:conn st) branch) (catch Throwable _ nil)) db)
        txs (ui/canvas-history-txs bdb db viewing-id)
        target (if (or (nil? tx) (= tx (last txs))) :live (long tx))]
    (async-action st {:op :show-user :as-of-tx target} nil nil)))

(defn ^:seon/callable jump-canvas-live! [st]
  (async-action st {:op :show-user :as-of-tx :live} nil nil))

(defn ^:seon/callable dismiss-feedback! [st]
  {:st (assoc st :branch-feedback nil)})

(defn ^:seon/callable chat-agent-form!
  ([st form] (chat-agent-form! st (ui/entry-agent-id @(:conn st)) form))
  ([st agent-id form]
   (let [db @(:conn st)
         txt (str/trim (str (:text form "")))
         aid (or agent-id (ui/entry-agent-id db))
         br (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id aid]))
                :branch/repl)]
     (cond
       (str/blank? txt) {:st st}

       (or (str/starts-with? txt "(") (str/starts-with? txt "["))
       (async-action
        (assoc st :branch-feedback nil)
        {:op :eval :branch br :agent-id aid :code txt :keep-warm? true}
        :branch-feedback
        (fn [ans]
          (when-not (:ok? ans)
            {:ok? false :text (str "Eval error: " (:error ans))})))

       :else
       (do
         (when (:as-of-tx (ui/user-view-state db))
           (a/put! (:server-in-ch st) {:req {:op :show-user :as-of-tx :live}}))
         (async-action
          (assoc st :branch-feedback nil)
          {:op :send-message :from :user :to aid :type :chat :body txt}
          nil nil))))))

(defn ^:seon/callable chat-root-form!
  "Back-compat alias to `chat-agent-form!`."
  ([st form] (chat-agent-form! st form))
  ([st agent-id form] (chat-agent-form! st agent-id form)))

(defn ^:seon/callable eval-agent-form! [st agent-id branch form]
  (let [code-str (str/trim (str (:code form "")))]
    (cond
      (str/blank? code-str)
      {:st st}

      (or (boolean (re-find #"^[\(\[\{'#@]" code-str))
          (and (str/starts-with? code-str ":") (not (str/includes? code-str " "))))
      (async-action
       (assoc st :branch-feedback nil)
       {:op :eval :branch branch :agent-id agent-id :code code-str :keep-warm? true}
       :branch-feedback
       (fn [ans]
         (when-not (:ok? ans)
           {:ok? false :text (str "Error: " (:error ans))})))

      :else
      (async-action
       (assoc st :branch-feedback nil)
       {:op :send-message :from :user :to agent-id :type :chat :body code-str}
       nil nil))))

(defn ^:seon/callable unpresent-stage! [st agent-id stage-key]
  (let [branch (or (:seon.agent/branch (d/pull @(:conn st) '[:seon.agent/branch] [:seon.agent/id agent-id])) :main)]
    (async-action
     st
     {:op :eval :branch branch :agent-id agent-id :code (str "(user/unpresent! " (pr-str stage-key) ")") :keep-warm? true}
     nil nil)))

(defn ^:seon/callable pin-ref-form! [st agent-id form]
  (let [raw (str/trim (str (:ref form "")))
        parsed (try (edn/read-string raw) (catch Throwable _ nil))]
    (if-not (and (vector? parsed) (= 2 (count parsed)) (qualified-keyword? (first parsed)))
      {:st (assoc st :branch-feedback {:ok? false :text "Pin expects a lookup ref vector like [:seon.code.ns/name 'seon.web]"})}
      (let [cur (vec (or (:seon.agent/pinned (d/pull @(:conn st) '[:seon.agent/pinned] [:seon.agent/id agent-id])) []))
            nxt (if (some #(= % parsed) cur) cur (conj cur parsed))]
        (async-action
         (assoc st :branch-feedback {:ok? true :text (str "Pinned " (pr-str parsed) " to " agent-id "'s walk.")})
         {:op :transact :tx-data [{:seon.agent/id agent-id :seon.agent/pinned nxt}]}
         nil nil)))))

(defn ^:seon/callable unpin-tile! [st agent-id ref-val]
  (let [cur (vec (or (:seon.agent/pinned (d/pull @(:conn st) '[:seon.agent/pinned] [:seon.agent/id agent-id])) []))
        nxt (vec (remove #(= % ref-val) cur))]
    (async-action
     st
     {:op :transact :tx-data [{:seon.agent/id agent-id :seon.agent/pinned nxt}]}
     nil nil)))

(defn invoke-call!
  "Dispatches either a registered Reagent closure (`?h=...`) OR a stateless `^:seon/callable`
   Clojure call form (`?form=(ns/fn ...)`). Fault-isolated: never throws out of `web-proc`!"
  [st {:keys [hkey call-form form-fields]}]
  (try
    (cond
      hkey
      (if-let [hfn (get-in st [:walk :handlers hkey])]
        (let [res (if (seq form-fields) (hfn st form-fields) (hfn st))]
          (if (and (map? res) (contains? res :st))
            (assoc res :ok? true)
            {:ok? true :st (if (and (map? res) (contains? res :conn)) res st)}))
        {:ok? false :error :seon.web/stale-handler :handler hkey :st st})

      (and (seq? call-form) (qualified-symbol? (first call-form)))
      (let [sym (first call-form)
            v (resolve sym)]
        (if-not (and v (:seon/callable (meta v)))
          {:ok? false :error :seon.web/not-callable :message (str sym " is not marked ^:seon/callable") :fn sym :st st}
          (let [args (cond-> (vec (rest call-form)) (seq form-fields) (conj form-fields))
                res (apply @v st args)]
            (if (and (map? res) (contains? res :st))
              (assoc res :ok? true)
              {:ok? true :st (if (and (map? res) (contains? res :conn)) res st)}))))

      :else
      {:ok? false :error :seon.web/malformed-call :form call-form :st st})
    (catch Throwable ex
      {:ok? false :error :seon.web/handler-error :message (or (.getMessage ex) (str ex)) :st st})))

(def mcp-tools
  [{:name "seon_repl"
    :description "Evaluate Clojure forms inside an isolated COW forked Seon agent REPL on a Datahike branch. Auto-attaches the same `<walk-context>` tiles + `<ephemeral-tail>` internal Seon agents receive on Turn 1 (or when `task`, `scope`, or `context: true` is passed) and delivers unread incoming messages on subsequent turns. Returns live runtime handle `result/e<n>`, `@seon.ctx/ctx`, `(user/context ...)`, `(user/chat! ...)`, `(user/monitor ...)`, and structural CST editors."
    :inputSchema {:type "object"
                  :properties {:code    {:type "string"  :description "Clojure form(s) to evaluate."}
                               :branch  {:type "string"  :description "Datahike branch keyword, e.g. \":branch/work\" (default \":branch/mcp\")."}
                               :agent   {:type "string"  :description "Agent keyword id, e.g. \":agent/mcp\"."}
                               :task    {:type "string"  :description "Optional task keyword id (e.g. \":seon.ns/task-id\") to focus `<walk-context>` on."}
                               :scope   {:type "string"  :description "Optional namespace symbol (e.g. \"seon.render.canvas\") to focus `<walk-context>` on."}
                               :context {:type "boolean" :description "Explicitly include (`true`) or suppress (`false`) `<walk-context>` + `<ephemeral-tail>`. Defaults to auto on Turn 1 or when `task`/`scope` is passed."}}
                  :required ["code"]}}
   {:name "seon_health"
    :description "Read system health (derived structural violations, blocking Flow procs, unschemed attributes, stored-data schema checks, global failing tests across branches, open proc faults)."
    :inputSchema {:type "object" :properties {}}}
   {:name "seon_test"
    :description "Run minimal reach-selected, fail-fast clojure.test + seed=42 Malli generative checks on a branch (default :main) inside Seon."
    :inputSchema {:type "object"
                  :properties {:branch {:type "string" :description "Branch keyword (default \":main\")."}
                               :force  {:type "boolean" :description "Bypass green reach cache."}}}}
   {:name "seon_merge"
    :description "Perform Malli-driven 3-way merge of a branch into Datahike :main (enforcing stored-data schema & health gates) and sync local Git."
    :inputSchema {:type "object"
                  :properties {:branch  {:type "string" :description "Branch keyword to merge into :main."}
                               :message {:type "string" :description "Optional commit message."}}
                  :required ["branch"]}}
   {:name "seon_status"
    :description "Read system status snapshot (main tx, git commit, branches, tasks, agents, config, derived Datahike attributes)."
    :inputSchema {:type "object" :properties {}}}])

(defn ^:seon.flow/edge call-server-edge!
  [server-in-ch req timeout-ms]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (let [[ans _] (a/alts!! [rch (a/timeout timeout-ms)])]
      (or ans {:ok? false :error :timeout}))))

(defn ^:seon.flow/edge handle-mcp-jsonrpc!
{:malli/schema [:function [:=> [:cat :seon.host/chan [:or :string :map]] :map] [:=> [:cat :seon.host/chan [:or :string :map] [:maybe :seon.host/db]] :map]]}
([server-in-ch ^String body-str] (handle-mcp-jsonrpc! server-in-ch body-str nil))
([server-in-ch ^String body-str db]
(let [msg (try (json/read-str body-str :key-fn keyword) (catch Throwable _ nil))
      id (:id msg)
      method (:method msg)]
  (cond
    (nil? msg)
    {:status 400 :headers {"Content-Type" "application/json"}
     :body (json/write-str {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Invalid JSON"}})}

    (= method "initialize")
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (json/write-str
            {:jsonrpc "2.0" :id id
             :result {:protocolVersion "2024-11-05"
                      :capabilities {:tools {}}
                      :serverInfo {:name "seon-next" :version "2.0.0"}}})}

    (= method "notifications/initialized")
    {:status 200 :headers {"Content-Type" "application/json"} :body ""}

    (= method "tools/list")
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (json/write-str {:jsonrpc "2.0" :id id :result {:tools mcp-tools}})}

    (= method "tools/call")
    (let [tname (get-in msg [:params :name])
          args  (get-in msg [:params :arguments] {})
          br    (parse-kw-input (or (:branch args) ":branch/mcp") :branch/mcp)
          aid   (when (:agent args) (parse-kw-input (:agent args) :agent/mcp))
          req   (case tname
                  "seon_repl"   {:op :eval
                                 :branch br
                                 :agent-id aid
                                 :code (str (:code args ""))
                                 :keep-warm? true}
                  "seon_health" {:op :health}
                  "seon_test"   {:op :test
                                 :branch (parse-kw-input (or (:branch args) ":main") :main)
                                 :force? (boolean (:force args))}
                  "seon_merge"  {:op :merge
                                 :branch (parse-kw-input (:branch args) :branch/mcp)
                                 :message (:message args)}
                  "seon_status" {:op :status}
                  nil)]
      (if-not req
        {:status 200 :headers {"Content-Type" "application/json"}
         :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Unknown tool: " tname)}})}
        (let [ans (if (= tname "seon_merge")
                    (let [mbr (:branch req)
                          t-ans (call-server-edge! server-in-ch {:op :test :branch mbr} 30000)]
                      (if-not (:ok? t-ans)
                        {:ok? false :branch mbr :error "Reach tests failed; merge blocked" :test-result t-ans}
                        (call-server-edge! server-in-ch req 30000)))
                    (call-server-edge! server-in-ch req 30000))
              text (if (= tname "seon_repl")
                     (let [task-kw (when (and (:task args) (not (str/blank? (str (:task args)))))
                                     (parse-kw-input (:task args) nil))
                           scope-sym (when (and (string? (:scope args)) (not (str/blank? (:scope args))))
                                       (symbol (str/replace (str/trim (:scope args)) #"^'" "")))
                           ctx-opt (when (contains? args :context) (boolean (:context args)))
                           want-ctx? (cond
                                       (false? ctx-opt) false
                                       (true? ctx-opt)  true
                                       :else (boolean (or (= (:handle ans) 'result/e1) task-kw scope-sym)))
                           ctx-ans (try
                                     (call-server-edge!
                                      server-in-ch
                                      (cond-> {:op :agent-context
                                               :branch br
                                               :agent-id (or aid :agent/mcp)
                                               :include-context? want-ctx?}
                                        task-kw (assoc :task task-kw)
                                        scope-sym (assoc :scope scope-sym))
                                      15000)
                                     (catch Throwable _ nil))
                           unread (:unread ctx-ans)
                           unread-txt (when (seq unread)
                                        (str "<unread-messages>\n"
                                             (str/join "\n"
                                                       (map (fn [m]
                                                              (str "- [" (name (or (:seon.agent.message/type m) :chat))
                                                                   " from " (:seon.agent.message/from m)
                                                                   " #" (:seon.agent.message/seq m) "]: "
                                                                   (:seon.agent.message/body m)))
                                                            unread))
                                             "\n</unread-messages>"))
                           parts (cond-> []
                                   (and want-ctx? (not (str/blank? (:context-text ctx-ans))))
                                   (conj (:context-text ctx-ans))
                                   unread-txt
                                   (conj unread-txt)
                                   true
                                   (conj (mwire/format-eval-output (cond-> ans db (assoc :db db :main-db db)))))]
                       (str/join "\n\n" parts))
                     (pr-str ans))]
          {:status 200 :headers {"Content-Type" "application/json"}
           :body (json/write-str
                  {:jsonrpc "2.0" :id id
                   :result {:content [{:type "text" :text text}]
                            :isError (not (:ok? ans true))}})})))

    :else
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}})}))))

(defn ^:seon/callable toggle-vision!
  "Toggles `:seon.config/viewport-screenshot?` live for the currently viewed agent."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map]}
  [st]
  (let [db @(:conn st)
        {:keys [viewing-id]} (ui/user-view-state db)
        cur (boolean (store/config-val db viewing-id :seon.config/viewport-screenshot?))]
    (async-action st {:op :set-config :agent-id viewing-id :key :seon.config/viewport-screenshot? :value (not cur)} nil nil)))

(defn ^:seon/callable set-board-state!
  "Updates interactive state (`:active`, `:view-as`, `:inspect`) on any `:seon.board/entity` in Datahike."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :qualified-keyword :map] :map]}
  [st board-id patch]
  (let [db @(:conn st)
        cur (when (contains? (:schema db) :seon.board/id)
              (d/pull db '[:seon.board/inspect] [:seon.board/id board-id]))
        cur-insp (:seon.board/inspect cur)
        up (cond-> {:seon.board/id board-id}
             (:active patch) (assoc :seon.board/active (:active patch))
             (:view-as patch) (assoc :seon.board/view-as (:view-as patch))
             (and (contains? patch :inspect) (some? (:inspect patch)))
             (assoc :seon.board/inspect (:inspect patch)))
        tx (cond-> []
             (> (count up) 1) (conj up)
             (and (contains? patch :inspect) (nil? (:inspect patch)) (some? cur-insp))
             (conj [:db/retract [:seon.board/id board-id] :seon.board/inspect cur-insp]))]
    (if (seq tx)
      (async-action st {:op :transact :tx-data tx} nil nil)
      {:st st})))

(defn ^:seon/callable set-query-param!
  "Updates one parameter key inside a `:seon.query/entity`'s `:seon.query/spec` map in Datahike."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :qualified-keyword :keyword :any] :map]}
  [st query-id param-k param-v]
  (let [db @(:conn st)
        cur (or (:seon.query/spec (d/pull db '[:seon.query/spec] [:seon.query/id query-id])) {})
        nxt (assoc cur param-k param-v)]
    (async-action st {:op :transact :tx-data [{:seon.query/id query-id :seon.query/spec nxt}]} nil nil)))

(defn ^:seon/callable pin-to-agent!
  "Pins any Datahike lookup ref onto `target-agent-id`'s `:seon.agent/pinned` walk (optionally switching user view to that agent's Workspace)."
  {:malli/schema [:function [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any]] :map] [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any] :boolean] :map]]}
  ([st target-agent-id lookup-ref] (pin-to-agent! st target-agent-id lookup-ref false))
  ([st target-agent-id lookup-ref jump?]
   (let [db @(:conn st)
         cur (vec (or (:seon.agent/pinned (d/pull db '[:seon.agent/pinned] [:seon.agent/id target-agent-id])) []))
         nxt (if (some #(= % lookup-ref) cur) cur (conj cur lookup-ref))
         tx (cond-> [{:seon.agent/id target-agent-id :seon.agent/pinned nxt}]
              jump? (conj {:seon.user/id :user/default
                           :seon.user/viewing [:seon.agent/id target-agent-id]
                           :seon.user/view-mode :workspace}))]
     (async-action
      (assoc st :branch-feedback {:ok? true :text (str "Pinned " (pr-str lookup-ref) " onto " target-agent-id "'s walk.")})
      {:op :transact :tx-data tx}
      nil nil))))

(defn parse-kv-pairs
  {:malli/schema [:=> [:cat [:maybe :string]] :map], :cost/ticks 500000, :cost/bytes 262144}
  [s]
  (if (str/blank? s)
    {}
    (into {}
          (keep (fn [part]
                  (when (str/includes? part "=")
                    (let [[k v] (str/split part #"=" 2)]
                      (when (seq k)
                        [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                         (java.net.URLDecoder/decode (or v "") "UTF-8")])))))
          (str/split s #"&"))))

(defn parse-multipart-fields
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] :map], :cost/ticks 1000000, :cost/bytes 1048576}
  [^String ct ^String body]
  (if-let [[_ boundary] (re-find #"boundary=([^\s;]+)" (str ct))]
    (let [parts (str/split body (re-pattern (str "--" (java.util.regex.Pattern/quote boundary))))]
      (into {}
            (keep (fn [part]
                    (when-let [[_ fname] (re-find #"name=\"([^\"]+)\"" part)]
                      (let [sep (or (str/index-of part "\r\n\r\n") (str/index-of part "\n\n"))
                            offset (if (str/index-of part "\r\n\r\n") 4 2)]
                        (when sep
                          (let [raw-val (subs part (+ sep offset))
                                val (str/replace raw-val #"(?:\r?\n)$" "")]
                            [(keyword fname) val]))))))
            parts))
    {}))

(defn parse-form-body
  {:malli/schema [:=> [:cat :map] :map]}
  [req]
  (let [ct (str/lower-case (or (get-in req [:headers "content-type"]) ""))
        body-str (cond
                   (nil? (:body req)) ""
                   (string? (:body req)) (:body req)
                   :else (slurp (:body req)))]
    (cond
      (str/blank? body-str) {}
      (str/includes? ct "multipart/form-data") (parse-multipart-fields ct body-str)
      (str/includes? ct "application/x-www-form-urlencoded") (parse-kv-pairs body-str)
      ;; Datastar sends client UI signals (e.g. `{"showThinking":false}`) as `application/json`
      ;; on non-form `@post(...)` clicks — never treat client signals as HTML form fields!
      (str/includes? ct "application/json") {}
      :else (parse-kv-pairs body-str))))

(defn ^:seon/callable run-dyno-eval!
"Fires a real read-only telemetry eval in COW worker `:repl/dyno` and commits its real measured `:seon.agent.eval/*` datom for `:agent/guide` onto `:main` so the Engine Cockpit advances live via SSE."
{:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map]}
[{:keys [conn server-in-ch] :as st}]
(let [code "(seon.instruments/read-tachometer (user/main-db))"
      intent "Live dyno tachometer pulse"]
  (async-action
   (assoc st :branch-feedback {:ok? true :text "Running live COW telemetry pulse on :repl/dyno..."})
   {:op :eval :branch :branch/dyno :agent-id :repl/dyno :intent intent :code code :keep-warm? true}
   :branch-feedback
   (fn [ans]
     (when (and (:ok? ans) server-in-ch)
       (let [ent (inst/dyno-eval-entity (when conn @conn) (java.util.UUID/randomUUID) ans code intent)]
         (a/put! server-in-ch {:req {:op :transact :tx-data [ent]} :reply-ch (a/chan 1)})))
     {:ok? (:ok? ans)
      :text (if (:ok? ans)
              (str "Live pulse recorded (" (:handle ans) ", " (:ms ans) " ms, " (:ticks ans) " ticks) — instruments updated via SSE!")
              (str "Pulse error: " (:error ans)))}))))

(defn ^:seon/callable trigger-demo-lamp!
  "Records a real typed `:seon.error/entity` warning on `:agent/guide` so the Check-Engine Warning Lamp turns AMBER and `:rule.error/surface-open-agent-error` fires live."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map]}
  [st]
  (let [fid (hasch/uuid [:instrument :demo-lamp])]
    (async-action
     (assoc st :branch-feedback {:ok? true :text "Triggered Check-Engine warning lamp on :agent/guide — watch Warning Lamps & :rule.error/surface-open-agent-error!"})
     {:op :record-fault :id fid :class :instrument/thermal-check :severity :warning
      :proc :instruments :agent :agent/guide :ns 'seon.instruments
      :subject "seon.instruments/thermal-check"
      :message "Live check-engine lamp check on :agent/guide (retract via Clear Lamp)."}
     nil nil)))

(defn ^:seon/callable clear-demo-lamp!
  "Clears the live check-engine warning lamp `:seon.error/entity` so Warning Lamps return GREEN via SSE."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map]}
  [st]
  (let [fid (hasch/uuid [:instrument :demo-lamp])]
    (async-action
     (assoc st :branch-feedback {:ok? true :text "Cleared check-engine warning lamp — all lamps nominal."})
     {:op :retract-fault :id fid}
     nil nil)))