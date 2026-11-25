(ns seon.web.mcp
  "MCP JSON-RPC 2.0 tool registry and handler extracted from `seon.web.actions`."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.model.wire :as mwire]
            [seon.web.router :as router]))

(defn parse-kw-input
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :keyword]] [:maybe :keyword]] :cost/ticks 100000}
  [^String s default-kw]
  (let [t (str/trim (str s))]
    (cond (str/blank? t) default-kw
          (str/starts-with? t ":") (or (try (let [k (edn/read-string t)] (when (keyword? k) k)) (catch RuntimeException _ nil)) default-kw)
          :else (keyword t))))

(def mcp-tools
  [{:name "seon_repl" :description "Eval Clojure on a COW branch REPL."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"} :branch {:type "string"} :agent {:type "string"}
                               :task {:type "string"} :scope {:type "string"} :context {:type "boolean"}}
                  :required ["code"]}}
   {:name "seon_health" :description "System health report." :inputSchema {:type "object" :properties {}}}
   {:name "seon_test" :description "Reach tests + seed=42 checks."
    :inputSchema {:type "object" :properties {:branch {:type "string"} :force {:type "boolean"}}}}
   {:name "seon_merge" :description "3-way merge branch to :main & Git."
    :inputSchema {:type "object" :properties {:branch {:type "string"} :message {:type "string"}} :required ["branch"]}}
   {:name "seon_status" :description "System status snapshot." :inputSchema {:type "object" :properties {}}}])

(defn ^:seon.flow/edge call-server-edge!
  {:malli/schema [:=> [:cat :seon.host/chan :map :int] :map]}
  [server-in-ch req timeout-ms]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (let [[ans _] (a/alts!! [rch (a/timeout timeout-ms)])] (or ans {:ok? false :error :timeout}))))

(defn ^:seon.flow/edge handle-mcp-jsonrpc!
  {:malli/schema [:function [:=> [:cat :seon.host/chan [:or :string :map]] :map]
                  [:=> [:cat :seon.host/chan [:or :string :map] [:maybe :seon.host/db]] :map]]}
  ([server-in-ch ^String body-str] (handle-mcp-jsonrpc! server-in-ch body-str nil))
  ([server-in-ch ^String body-str db]
   (let [msg (try (json/read-str body-str :key-fn keyword) (catch Throwable _ nil))
         id (:id msg) method (:method msg)
         jresp (fn [st body] {:status st :headers {"Content-Type" "application/json"} :body (if (string? body) body (json/write-str body))})]
     (cond
       (nil? msg) (jresp 400 {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Invalid JSON"}})
       (= method "initialize") (jresp 200 {:jsonrpc "2.0" :id id :result {:protocolVersion "2024-11-05" :capabilities {:tools {}} :serverInfo {:name "seon-next" :version "2.0.0"}}})
       (= method "notifications/initialized") (jresp 200 "")
       (= method "tools/list") (jresp 200 {:jsonrpc "2.0" :id id :result {:tools mcp-tools}})
       (= method "tools/call")
       (let [tname (get-in msg [:params :name]) args (get-in msg [:params :arguments] {})
             br (parse-kw-input (or (:branch args) ":branch/mcp") :branch/mcp)
             aid (when (:agent args) (parse-kw-input (:agent args) :agent/mcp))
             req (case tname
                   "seon_repl" {:op :eval :branch br :agent-id aid :code (str (:code args "")) :keep-warm? true}
                   "seon_health" {:op :health}
                   "seon_test" {:op :test :branch (parse-kw-input (or (:branch args) ":main") :main) :force? (boolean (:force args))}
                   "seon_merge" {:op :merge :branch (parse-kw-input (:branch args) :branch/mcp) :message (:message args)}
                   "seon_status" {:op :status}
                   nil)]
         (if-not req
           (jresp 200 {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Unknown tool: " tname)}})
           (let [ans (if (= tname "seon_merge")
                       (let [mbr (:branch req) t-ans (call-server-edge! server-in-ch {:op :test :branch mbr} (router/op-timeout-ms {:op :test}))]
                         (if-not (:ok? t-ans) {:ok? false :branch mbr :error "Reach tests failed; merge blocked" :test-result t-ans}
                             (call-server-edge! server-in-ch req (router/op-timeout-ms req))))
                       (call-server-edge! server-in-ch req (router/op-timeout-ms req)))
                 text (if (= tname "seon_repl")
                        (let [task-kw (when (not (str/blank? (str (:task args)))) (parse-kw-input (:task args) nil))
                              scope-sym (when (and (string? (:scope args)) (not (str/blank? (:scope args))))
                                          (symbol (str/replace (str/trim (:scope args)) #"^'" "")))
                              want-ctx? (if (contains? args :context) (boolean (:context args)) (boolean (or (= (:handle ans) (quote result/e1)) task-kw scope-sym)))
                              ctx-ans (try (call-server-edge! server-in-ch
                                             (cond-> {:op :agent-context :branch br :agent-id (or aid :agent/mcp) :include-context? want-ctx?}
                                               task-kw (assoc :task task-kw) scope-sym (assoc :scope scope-sym)) 15000)
                                           (catch Throwable _ nil))
                              unread (:unread ctx-ans)
                              unread-txt (when (seq unread)
                                           (str "<unread-messages>\n"
                                                (str/join "\n" (map #(str "- [" (name (or (:seon.agent.message/type %) :chat))
                                                                           " from " (:seon.agent.message/from %) " #" (:seon.agent.message/seq %) "]: "
                                                                           (:seon.agent.message/body %)) unread))
                                                "\n</unread-messages>"))]
                          (str/join "\n\n"
                            (cond-> [] (and want-ctx? (not (str/blank? (:context-text ctx-ans)))) (conj (:context-text ctx-ans))
                              unread-txt (conj unread-txt)
                              true (conj (mwire/format-eval-output (cond-> ans db (assoc :db db :main-db db)))))))
                        (pr-str ans))]
             (jresp 200 {:jsonrpc "2.0" :id id :result {:content [{:type "text" :text text}] :isError (not (:ok? ans true))}}))))
       :else (jresp 200 {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}})))))