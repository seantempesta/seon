(ns seon.web.actions
  "Stateless, `^:seon/callable` UI actions dispatched from Datastar `?form=` calls (sidebar/view-mode/branch/canvas-history/chat forms), plus the `/call` & `/mcp` dispatch helpers (`invoke-call!`, `handle-mcp-jsonrpc!`, `mcp-tools`, `call-server-edge!`)."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.ui :as ui]
            [seon.store :as store]
            [seon.model.wire :as mwire]))

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
        txs (ui/canvas-history-txs bdb viewing-id)
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
        txs (ui/canvas-history-txs bdb viewing-id)
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
    :description "Evaluate Clojure forms inside an isolated COW forked Seon agent REPL on a Datahike branch. Returns live runtime handle `result/e<n>`, `@seon.ctx/ctx`, `(user/pin! ...)`, `(user/show-user! ...)`, `(user/health)`, and `(user/register-schema! ...)`."
    :inputSchema {:type "object"
                  :properties {:code   {:type "string" :description "Clojure form(s) to evaluate."}
                               :branch {:type "string" :description "Datahike branch keyword, e.g. \":branch/work\" (default \":branch/mcp\")."}
                               :agent  {:type "string" :description "Agent keyword id, e.g. \":agent/mcp\"."}}
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
  [server-in-ch ^String body-str]
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
            req   (case tname
                    "seon_repl"   {:op :eval
                                   :branch (parse-kw-input (or (:branch args) ":branch/mcp") :branch/mcp)
                                   :agent-id (when (:agent args) (parse-kw-input (:agent args) :agent/mcp))
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
                      (let [br (:branch req)
                            t-ans (call-server-edge! server-in-ch {:op :test :branch br} 30000)]
                        (if-not (:ok? t-ans)
                          {:ok? false :branch br :error "Reach tests failed; merge blocked" :test-result t-ans}
                          (call-server-edge! server-in-ch req 30000)))
                      (call-server-edge! server-in-ch req 30000))]
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (json/write-str
                    {:jsonrpc "2.0" :id id
                     :result {:content [{:type "text" :text (pr-str ans)}]
                              :isError (not (:ok? ans true))}})})))

      :else
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}})})))

(defn ^:seon/callable toggle-vision!
  "Toggles `:seon.config/viewport-screenshot?` live for the currently viewed agent."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map]}
  [st]
  (let [db @(:conn st)
        {:keys [viewing-id]} (ui/user-view-state db)
        cur (boolean (store/config-val db viewing-id :seon.config/viewport-screenshot?))]
    (async-action st {:op :set-config :agent-id viewing-id :key :seon.config/viewport-screenshot? :value (not cur)} nil nil)))