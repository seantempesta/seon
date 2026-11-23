(ns seon.web.actions
  "Stateless, `^:seon/callable` UI actions dispatched from Datastar `?form=` calls
   (holistic navigation, fractal inspector back/forward/trail, context lab prefs,
   live semantic search, branch ops, chat/eval forms) plus `/call` & `/mcp` dispatch."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.render.ui :as ui]
            [seon.store :as store]
            [seon.model.wire :as mwire]))

(defn parse-kw-input [^String s default-kw]
  (let [t (str/trim (str s))]
    (cond (str/blank? t) default-kw
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
                {:op :show-user :viewing agent-id :mode (or mode :session) :clear-inspect true}
                nil nil))

(defn ^:seon/callable set-view-mode! [st mode]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :mode mode :clear-inspect true}
                nil nil))

(defn ^:seon/callable select-branch! [st branch]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :selected-branch branch}
                nil nil))

(defn ^:seon/callable switch-tab! [st tab]
  (set-view-mode! st (case tab (:schemas :health :branches) :system :agents :system :context)))

;; Holistic Fractal Inspector & Context Lab Callable Actions

(defn ^:seon/callable inspect-entity!
  "Navigates the fractal entity-rooted surface to `lookup-ref`, pushing current ref onto back trail."
  {:malli/schema [:=> [:cat :map :any] :map] :cost/ticks 200000}
  [st lookup-ref]
  (if-let [r (rdata/normalize-inspect-ref lookup-ref)]
    (async-action (assoc st :branch-feedback nil) {:op :show-user :push-inspect r} nil nil)
    {:st st}))

(defn ^:seon/callable inspect-back!
  "Steps backward in the fractal Inspector navigation stack (pushing current ref onto forward stack)."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st]
  (async-action (assoc st :branch-feedback nil) {:op :show-user :pop-inspect true} nil nil))

(defn ^:seon/callable inspect-forward!
  "Steps forward in the fractal Inspector navigation stack (pushing current ref onto back stack)."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st]
  (async-action (assoc st :branch-feedback nil) {:op :show-user :forward-inspect true} nil nil))

(defn ^:seon/callable inspect-close!
  "Exits the fractal Inspector back to the active top-level view."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st]
  (async-action (assoc st :branch-feedback nil) {:op :show-user :clear-inspect true} nil nil))

(defn ^:seon/callable jump-inspect-trail!
  "Jumps directly to breadcrumb index `idx` in `:seon.user/inspect-trail`, preserving forward history."
  {:malli/schema [:=> [:cat :map :int] :map] :cost/ticks 200000}
  [st idx]
  (async-action (assoc st :branch-feedback nil) {:op :show-user :jump-trail (long idx)} nil nil))

(defn ^:seon/callable update-lab-prefs!
  "Merges `patch` into `:seon.user/lab-prefs` (target, budget, width, strategy, weights, inspector controls)."
  {:malli/schema [:=> [:cat :map :map] :map] :cost/ticks 200000}
  [st patch]
  (async-action st {:op :show-user :lab-prefs (if (map? patch) patch {})} nil nil))

(defn- parse-d [s fb]
  (try (let [v (Double/parseDouble (str/trim (str s)))]
         (if (and (== v v) (not (Double/isInfinite v))) (max 0.0 (min 5.0 v)) (double fb)))
       (catch Throwable _ (double fb))))

(defn ^:seon/callable update-lab-weights-form!
  "Parses numeric 4-signal weights from the Context & Cache form and persists them to `:seon.user/lab-prefs`."
  {:malli/schema [:=> [:cat :map :map] :map] :cost/ticks 200000}
  [st form]
  (let [ws {:urgency (parse-d (:urgency form) 1.0)
            :recency (parse-d (:recency form) 0.75)
            :convergence (parse-d (:convergence form) 0.5)
            :semantic (parse-d (:semantic form) 0.5)}]
    (async-action st {:op :show-user :lab-prefs {:weights ws :weight-preset :custom}} nil nil)))

(defn ^:seon/callable toggle-lab-rule!
  "Toggles `rule-id` in `:seon.user/lab-prefs` `:disabled-rules` for live A/B Context Lab testing."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :map] :cost/ticks 200000}
  [st rule-id]
  (async-action st {:op :show-user :toggle-lab-rule rule-id} nil nil))

(defn ^:seon/callable reset-lab-prefs!
  "Resets `:seon.user/lab-prefs` back to balanced defaults."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st]
  (async-action st {:op :show-user :reset-lab-prefs true} nil nil))

(defn ^:seon/callable semantic-search-form!
  "Runs live semantic vector search (`:op :embed-search`) across embedded vars, docs, tasks, schemas, and errors."
  {:malli/schema [:=> [:cat :map :map] :map] :cost/ticks 500000}
  [st form]
  (let [raw (str/trim (str (:query form "")))
        of-s (str/trim (str (:of form "")))
        of-kw (when-not (str/blank? of-s) (try (let [k (edn/read-string of-s)] (when (qualified-keyword? k) k)) (catch Throwable _ nil)))
        q (if (str/starts-with? raw "[")
            (or (try (rdata/normalize-inspect-ref (edn/read-string raw)) (catch Throwable _ nil)) raw)
            raw)]
    (if (str/blank? raw)
      {:st (assoc st :semantic-search nil)}
      (async-action
       (assoc st :branch-feedback {:ok? true :text (str "Searching vectors for \"" raw "\"...")})
       {:op :embed-search :query q :k 12 :opts (if of-kw {:of of-kw} {})}
       :semantic-search
       (fn [ans] (assoc ans :query raw))))))

(defn ^:seon/callable refresh-vector-status!
  "Fetches live `:vectors` Flow proc backpressure and indexing telemetry (`:op :vector-status`)."
  {:malli/schema [:=> [:cat :map] :map] :cost/ticks 200000}
  [st]
  (async-action st {:op :vector-status} :vector-status identity))

(defn ^:seon/callable power-loss-reboot! [st]
  (when-let [pl (:on-power-loss st)] (a/thread (pl)))
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
  (let [srv (:server-in-ch st) ev-ch (or (:ui-ch st) (:event-ch st))]
    (when (and srv ev-ch)
      (a/go
        (let [t-ans (let [rc (a/chan 1)]
                      (a/>! srv {:req {:op :test :branch branch} :reply-ch rc})
                      (let [[v _] (a/alts! [rc (a/timeout 30000)])] (or v {:ok? false :error :timeout})))]
          (if-not (:ok? t-ans)
            (a/>! ev-ch {:ev :action-done :slot :branch-feedback
                         :val {:ok? false
                               :text (str "Merge blocked on " branch ": tests failed ("
                                          (or (:error t-ans)
                                              (str (:ran t-ans 0) " ran, " (count (remove :seon.test/pass? (:results t-ans))) " failed")) ")")}})
            (let [m-ans (let [rc (a/chan 1)]
                          (a/>! srv {:req {:op :merge :branch branch} :reply-ch rc})
                          (let [[v _] (a/alts! [rc (a/timeout 30000)])] (or v {:ok? false :error :timeout})))]
              (a/>! ev-ch {:ev :action-done :slot :branch-feedback
                           :val {:ok? (:ok? m-ans)
                                 :text (if (:ok? m-ans)
                                         (str "Tested & merged " branch " -> :main @ tx " (:tx m-ans)
                                              " (" (count (:touched-paths m-ans)) " files)")
                                         (str "Blocked / Conflict: " (pr-str (or (:conflicts m-ans) (:error m-ans)))))}}))))))
    (assoc st :branch-feedback {:ok? true :text (str "Running reach tests before merging " branch " -> :main...")})))

(defn ^:seon/callable assign-health-fix! [st cls subject message]
  (let [tid (keyword "seon.health" (str "fix-" (mod (System/currentTimeMillis) 100000)))
        scope-ns (try (let [s (str subject)] (if (str/includes? s "/") (symbol (first (str/split s #"/" 2))) 'seon)) (catch Throwable _ 'seon))]
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
                     (when (seq (:derived-attributes ans)) (str " + derived Datahike attr " (:derived-attributes ans)))
                     " — seed=42 sample: " (:sample ans))
                (str "Rejected: " (:error ans)))}))))

(defn ^:seon/callable step-canvas-history!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :int] :map]}
  [st delta]
  (let [db @(:conn st)
        {:keys [viewing-id as-of-tx]} (ui/user-view-state db)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        bdb (or (try (store/head (:conn st) branch) (catch Throwable _ nil)) db)
        txs (ui/canvas-history-txs bdb db viewing-id)
        n (count txs)]
    (if (< n 2)
      {:st st}
      (let [cur-idx (if as-of-tx (or (first (keep-indexed #(when (= %2 as-of-tx) %1) txs)) (dec n)) (dec n))
            nxt-idx (max 0 (min (dec n) (+ cur-idx (long delta))))
            target (if (= nxt-idx (dec n)) :live (nth txs nxt-idx))]
        (async-action st {:op :show-user :as-of-tx target} nil nil)))))

(defn ^:seon/callable jump-canvas-tx!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] [:maybe :int]] :map]}
  [st tx]
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
         txt (str/trim (str (or (:text form) (:code form) "")))
         aid (or agent-id (ui/entry-agent-id db))
         br (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id aid])) :branch/repl)]
     (cond
       (str/blank? txt) {:st st}
       (or (boolean (re-find #"^[\(\[\{'#@]" txt))
           (and (str/starts-with? txt ":") (not (str/includes? txt " "))))
       (async-action (assoc st :branch-feedback nil)
                     {:op :eval :branch br :agent-id aid :code txt :keep-warm? true}
                     :branch-feedback
                     (fn [ans] (when-not (:ok? ans) {:ok? false :text (str "Eval error: " (:error ans))})))
       :else
       (do (when (:as-of-tx (ui/user-view-state db))
             (a/put! (:server-in-ch st) {:req {:op :show-user :as-of-tx :live}}))
           (async-action (assoc st :branch-feedback nil)
                         {:op :send-message :from :user :to aid :type :chat :body txt}
                         nil nil))))))

(defn ^:seon/callable chat-root-form!
  ([st form] (chat-agent-form! st form))
  ([st agent-id form] (chat-agent-form! st agent-id form)))

(defn ^:seon/callable eval-agent-form! [st agent-id branch form]
  (let [code-str (str/trim (str (:code form "")))]
    (cond
      (str/blank? code-str) {:st st}
      (or (boolean (re-find #"^[\(\[\{'#@]" code-str))
          (and (str/starts-with? code-str ":") (not (str/includes? code-str " "))))
      (async-action (assoc st :branch-feedback nil)
                    {:op :eval :branch branch :agent-id agent-id :code code-str :keep-warm? true}
                    :branch-feedback
                    (fn [ans] (when-not (:ok? ans) {:ok? false :text (str "Error: " (:error ans))})))
      :else
      (async-action (assoc st :branch-feedback nil)
                    {:op :send-message :from :user :to agent-id :type :chat :body code-str}
                    nil nil))))

(defn ^:seon/callable pin-ref-form! [st agent-id form]
  (let [raw (str/trim (str (:ref form "")))
        parsed (try (rdata/normalize-inspect-ref (edn/read-string raw)) (catch Throwable _ nil))]
    (if-not parsed
      {:st (assoc st :branch-feedback {:ok? false :text "Pin expects a lookup ref vector like [:seon.code.ns/name 'seon.web]"})}
      (let [cur (vec (or (:seon.agent/pinned (d/pull @(:conn st) '[:seon.agent/pinned] [:seon.agent/id agent-id])) []))
            nxt (if (some #(= % parsed) cur) cur (conj cur parsed))]
        (async-action (assoc st :branch-feedback {:ok? true :text (str "Pinned " (pr-str parsed) " to " agent-id "'s walk.")})
                      {:op :transact :tx-data [{:seon.agent/id agent-id :seon.agent/pinned nxt}]}
                      nil nil)))))

(defn ^:seon/callable unpin-tile! [st agent-id ref-val]
  (let [cur (vec (or (:seon.agent/pinned (d/pull @(:conn st) '[:seon.agent/pinned] [:seon.agent/id agent-id])) []))
        nxt (vec (remove #(= % ref-val) cur))]
    (async-action st {:op :transact :tx-data [{:seon.agent/id agent-id :seon.agent/pinned nxt}]} nil nil)))

(defn invoke-call!
  "Dispatches either a registered Reagent closure (`?h=...`) OR a stateless `^:seon/callable` Clojure call form."
  [st {:keys [hkey call-form form-fields]}]
  (try
    (cond
      hkey
      (if-let [hfn (get-in st [:walk :handlers hkey])]
        (let [res (if (seq form-fields) (hfn st form-fields) (hfn st))]
          (if (and (map? res) (contains? res :st)) (assoc res :ok? true) {:ok? true :st (if (and (map? res) (contains? res :conn)) res st)}))
        {:ok? false :error :seon.web/stale-handler :handler hkey :st st})
      (and (seq? call-form) (qualified-symbol? (first call-form)))
      (let [sym (first call-form) v (resolve sym)]
        (if-not (and v (:seon/callable (meta v)))
          {:ok? false :error :seon.web/not-callable :message (str sym " is not marked ^:seon/callable") :fn sym :st st}
          (let [args (cond-> (vec (rest call-form)) (seq form-fields) (conj form-fields))
                res (apply @v st args)]
            (if (and (map? res) (contains? res :st)) (assoc res :ok? true) {:ok? true :st (if (and (map? res) (contains? res :conn)) res st)}))))
      :else {:ok? false :error :seon.web/malformed-call :form call-form :st st})
    (catch Throwable ex
      {:ok? false :error :seon.web/handler-error :message (or (.getMessage ex) (str ex)) :st st})))

(def mcp-tools
  [{:name "seon_repl"
    :description "Evaluate Clojure forms inside an isolated COW forked Seon agent REPL on a Datahike branch. Auto-attaches `<walk-context>` + `<ephemeral-tail>` on Turn 1 or when `task`/`scope`/`context: true` is passed."
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Clojure form(s) to evaluate."}
                               :branch {:type "string" :description "Datahike branch keyword, e.g. \":branch/work\"."}
                               :agent {:type "string" :description "Agent keyword id, e.g. \":agent/mcp\"."}
                               :task {:type "string" :description "Optional task keyword id."}
                               :scope {:type "string" :description "Optional namespace symbol."}
                               :context {:type "boolean" :description "Include/suppress walk context."}}
                  :required ["code"]}}
   {:name "seon_health" :description "Read system health report." :inputSchema {:type "object" :properties {}}}
   {:name "seon_test" :description "Run reach-selected fail-fast tests + seed=42 generative checks on a branch."
    :inputSchema {:type "object" :properties {:branch {:type "string"} :force {:type "boolean"}}}}
   {:name "seon_merge" :description "Malli-driven 3-way merge of a branch into :main & local Git."
    :inputSchema {:type "object" :properties {:branch {:type "string"} :message {:type "string"}} :required ["branch"]}}
   {:name "seon_status" :description "Read system status snapshot." :inputSchema {:type "object" :properties {}}}])

(defn ^:seon.flow/edge call-server-edge! [server-in-ch req timeout-ms]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (let [[ans _] (a/alts!! [rch (a/timeout timeout-ms)])] (or ans {:ok? false :error :timeout}))))

(defn ^:seon.flow/edge handle-mcp-jsonrpc!
  {:malli/schema [:function [:=> [:cat :seon.host/chan [:or :string :map]] :map]
                  [:=> [:cat :seon.host/chan [:or :string :map] [:maybe :seon.host/db]] :map]]}
  ([server-in-ch ^String body-str] (handle-mcp-jsonrpc! server-in-ch body-str nil))
  ([server-in-ch ^String body-str db]
   (let [msg (try (json/read-str body-str :key-fn keyword) (catch Throwable _ nil))
         id (:id msg) method (:method msg)]
     (cond
       (nil? msg) {:status 400 :headers {"Content-Type" "application/json"}
                   :body (json/write-str {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Invalid JSON"}})}
       (= method "initialize") {:status 200 :headers {"Content-Type" "application/json"}
                                :body (json/write-str {:jsonrpc "2.0" :id id :result {:protocolVersion "2024-11-05" :capabilities {:tools {}} :serverInfo {:name "seon-next" :version "2.0.0"}}})}
       (= method "notifications/initialized") {:status 200 :headers {"Content-Type" "application/json"} :body ""}
       (= method "tools/list") {:status 200 :headers {"Content-Type" "application/json"}
                                :body (json/write-str {:jsonrpc "2.0" :id id :result {:tools mcp-tools}})}
       (= method "tools/call")
       (let [tname (get-in msg [:params :name]) args (get-in msg [:params :arguments] {})
             br (parse-kw-input (or (:branch args) ":branch/mcp") :branch/mcp)
             aid (when (:agent args) (parse-kw-input (:agent args) :agent/mcp))
             req (case tname
                   "seon_repl"   {:op :eval :branch br :agent-id aid :code (str (:code args "")) :keep-warm? true}
                   "seon_health" {:op :health}
                   "seon_test"   {:op :test :branch (parse-kw-input (or (:branch args) ":main") :main) :force? (boolean (:force args))}
                   "seon_merge"  {:op :merge :branch (parse-kw-input (:branch args) :branch/mcp) :message (:message args)}
                   "seon_status" {:op :status}
                   nil)]
         (if-not req
           {:status 200 :headers {"Content-Type" "application/json"}
            :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Unknown tool: " tname)}})}
           (let [ans (if (= tname "seon_merge")
                       (let [mbr (:branch req) t-ans (call-server-edge! server-in-ch {:op :test :branch mbr} 30000)]
                         (if-not (:ok? t-ans) {:ok? false :branch mbr :error "Reach tests failed; merge blocked" :test-result t-ans}
                             (call-server-edge! server-in-ch req 30000)))
                       (call-server-edge! server-in-ch req 30000))
                 text (if (= tname "seon_repl")
                        (let [task-kw (when (and (:task args) (not (str/blank? (str (:task args))))) (parse-kw-input (:task args) nil))
                              scope-sym (when (and (string? (:scope args)) (not (str/blank? (:scope args))))
                                          (symbol (str/replace (str/trim (:scope args)) #"^'" "")))
                              ctx-opt (when (contains? args :context) (boolean (:context args)))
                              want-ctx? (cond (false? ctx-opt) false (true? ctx-opt) true
                                              :else (boolean (or (= (:handle ans) 'result/e1) task-kw scope-sym)))
                              ctx-ans (try (call-server-edge! server-in-ch
                                                              (cond-> {:op :agent-context :branch br :agent-id (or aid :agent/mcp) :include-context? want-ctx?}
                                                                task-kw (assoc :task task-kw) scope-sym (assoc :scope scope-sym))
                                                              15000)
                                           (catch Throwable _ nil))
                              unread (:unread ctx-ans)
                              unread-txt (when (seq unread)
                                           (str "<unread-messages>\n"
                                                (str/join "\n" (map #(str "- [" (name (or (:seon.agent.message/type %) :chat))
                                                                          " from " (:seon.agent.message/from %) " #" (:seon.agent.message/seq %) "]: "
                                                                          (:seon.agent.message/body %)) unread))
                                                "\n</unread-messages>"))
                              parts (cond-> [] (and want-ctx? (not (str/blank? (:context-text ctx-ans)))) (conj (:context-text ctx-ans))
                                            unread-txt (conj unread-txt)
                                            true (conj (mwire/format-eval-output (cond-> ans db (assoc :db db :main-db db)))))]
                          (str/join "\n\n" parts))
                        (pr-str ans))]
             {:status 200 :headers {"Content-Type" "application/json"}
              :body (json/write-str {:jsonrpc "2.0" :id id :result {:content [{:type "text" :text text}] :isError (not (:ok? ans true))}})})))
       :else {:status 200 :headers {"Content-Type" "application/json"}
              :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}})}))))

(defn ^:seon/callable toggle-vision!
  "Toggles `:seon.config/viewport-screenshot?` live for the currently viewed agent."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map]}
  [st]
  (let [db @(:conn st) {:keys [viewing-id]} (ui/user-view-state db)
        cur (boolean (store/config-val db viewing-id :seon.config/viewport-screenshot?))]
    (async-action st {:op :set-config :agent-id viewing-id :key :seon.config/viewport-screenshot? :value (not cur)} nil nil)))

(defn ^:seon/callable set-viewport!
  "Clamps browser viewport `w`/`h` to `[200, 10000]` and persists `:seon.user/viewport-w` / `:seon.user/viewport-h` only when changed."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] number? number?] :map]}
  [st w h]
  (let [cw (long (max 200 (min 10000 (Math/round (double w)))))
        ch (long (max 200 (min 10000 (Math/round (double h)))))
        db @(:conn st)
        cur (when (and db (contains? (:schema db) :seon.user/id))
              (d/pull db '[:seon.user/viewport-w :seon.user/viewport-h] [:seon.user/id :user/default]))]
    (if (and (= cw (:seon.user/viewport-w cur)) (= ch (:seon.user/viewport-h cur)))
      {:st st}
      (async-action st {:op :transact :tx-data [{:seon.user/id :user/default :seon.user/viewport-w cw :seon.user/viewport-h ch}]} nil nil))))

(defn ^:seon/callable pin-to-agent!
  "Pins any Datahike lookup ref onto `target-agent-id`'s `:seon.agent/pinned` walk."
  {:malli/schema [:function [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any]] :map]
                  [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any] :boolean] :map]]}
  ([st target-agent-id lookup-ref] (pin-to-agent! st target-agent-id lookup-ref false))
  ([st target-agent-id lookup-ref jump?]
   (let [db @(:conn st)
         ref (or (rdata/normalize-inspect-ref lookup-ref) lookup-ref)
         cur (vec (or (:seon.agent/pinned (d/pull db '[:seon.agent/pinned] [:seon.agent/id target-agent-id])) []))
         nxt (if (some #(= % ref) cur) cur (conj cur ref))
         tx (cond-> [{:seon.agent/id target-agent-id :seon.agent/pinned nxt}]
              jump? (conj {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id target-agent-id]
                           :seon.user/view-mode :session}))]
     (async-action (assoc st :branch-feedback {:ok? true :text (str "Pinned " (pr-str ref) " onto " target-agent-id "'s walk.")})
                   {:op :transact :tx-data tx} nil nil))))

(defn parse-kv-pairs
  {:malli/schema [:=> [:cat [:maybe :string]] :map], :cost/ticks 500000, :cost/bytes 262144}
  [s]
  (if (str/blank? s) {}
      (into {} (keep (fn [part]
                       (when (str/includes? part "=")
                         (let [[k v] (str/split part #"=" 2)]
                           (when (seq k) [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                                          (java.net.URLDecoder/decode (or v "") "UTF-8")])))))
            (str/split s #"&"))))

(defn parse-multipart-fields
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] :map], :cost/ticks 1000000, :cost/bytes 1048576}
  [^String ct ^String body]
  (if-let [[_ boundary] (re-find #"boundary=([^\s;]+)" (str ct))]
    (let [parts (str/split body (re-pattern (str "--" (java.util.regex.Pattern/quote boundary))))]
      (into {} (keep (fn [part]
                       (when-let [[_ fname] (re-find #"name=\"([^\"]+)\"" part)]
                         (let [sep (or (str/index-of part "\r\n\r\n") (str/index-of part "\n\n"))
                               offset (if (str/index-of part "\r\n\r\n") 4 2)]
                           (when sep [(keyword fname) (str/replace (subs part (+ sep offset)) #"(?:\r?\n)$" "")])))))
            parts))
    {}))

(defn parse-form-body
  {:malli/schema [:=> [:cat :map] :map]}
  [req]
  (let [ct (str/lower-case (or (get-in req [:headers "content-type"]) ""))
        body-str (cond (nil? (:body req)) "" (string? (:body req)) (:body req) :else (slurp (:body req)))]
    (cond (str/blank? body-str) {}
          (str/includes? ct "multipart/form-data") (parse-multipart-fields ct body-str)
          (str/includes? ct "application/x-www-form-urlencoded") (parse-kv-pairs body-str)
          (str/includes? ct "application/json") {}
          :else (parse-kv-pairs body-str))))
