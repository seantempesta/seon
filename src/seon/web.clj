(ns seon.web
  "The `:web` Flow proc (`:io` workload) — 100% Non-Blocking HTTP/SSE Edge + Incremental Datastar Walk + MCP:
   - ZERO `<!!`, `>!!`, `alts!!`, `Thread/sleep`, or `flow/ping` inside `web-proc` (verified by `seon.health`!).
   - Incremental `render/step-walk` via `datahike.reads`: untouched panels do 0 work; 60+ fps stream deltas
     re-render ONLY `#seon-stream-unit` (~0.15 ms/frame).
   - Supports ALL Reagent/Clojure `:on-click`/`:on-submit` forms (`#(...)`, `[fn args]`, bare fn, `'(fn args)`).
   - Bounded SSE tab channels live inside `web-proc` Flow state (closed/stale tabs pruned automatically).
   - Serves live MCP JSON-RPC 2.0 at `POST /mcp` (`seon_repl`, `seon_health`, `seon_test`, `seon_merge`, `seon_status`)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring-chez.adapter :as adapter]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.store :as store]))

;; --- CSS & Static Shell ---

(def ^:private explorer-css
  ":root{color-scheme:dark;--bg:#090d16;--card:#111827;--border:#1e293b;--txt:#f1f5f9;--muted:#94a3b8;--blue:#38bdf8;--green:#10b981;--amber:#f59e0b;--red:#ef4444}
   *{box-sizing:border-box}
   body{margin:0;background:var(--bg);color:var(--txt);font-family:-apple-system,BlinkMacSystemFont,'Inter',sans-serif;font-size:13px;line-height:1.45}
   .mono{font-family:'SFMono-Regular',ui-monospace,Menlo,monospace}
   .muted{color:var(--muted)} .tiny{font-size:11px} .strong{font-weight:600}
   .topbar{background:#0b1120;border-bottom:1px solid var(--border);padding:10px 18px;position:sticky;top:0;z-index:20}
   .brand-row{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
   .brand{display:flex;align-items:center;gap:10px}
   .dot{width:9px;height:9px;border-radius:50%}
   .dot.live{background:var(--green);box-shadow:0 0 8px var(--green)}
   .dot.bad{background:var(--red);box-shadow:0 0 8px var(--red)}
   .stats-strip{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
   .stat-chip{background:#1e293b;padding:3px 9px;border-radius:999px;font-size:11.5px;color:#cbd5e1}
   .stat-chip.green{background:rgba(16,185,129,.15);color:#34d399}
   .stat-chip.red{background:rgba(239,68,68,.18);color:#f87171}
   .tabs-nav{display:flex;gap:6px;margin-top:10px;flex-wrap:wrap}
   .tab-btn{background:#162033;color:var(--muted);border:1px solid var(--border);padding:6px 12px;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px}
   .tab-btn.active,.tab-btn:hover{background:#1e293b;color:#fff;border-color:var(--blue)}
   .main-wrap{padding:14px 18px;max-width:1640px;margin:0 auto;display:flex;flex-direction:column;gap:12px}
   .stream-bar{padding:8px 12px}
   .grid-2{display:grid;grid-template-columns:1.1fr 1fr;gap:14px;align-items:start}
   .grid-3{display:grid;grid-template-columns:250px 330px 1fr;gap:14px;align-items:start}
   @media(max-width:1100px){.grid-2,.grid-3{grid-template-columns:1fr}}
   .stack{display:flex;flex-direction:column;gap:14px}
   .stack-sm{display:flex;flex-direction:column;gap:10px}
   .card{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:13px}
   .scroll-pane{max-height:76vh;overflow:auto}
   .card-head{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:10px}
   h2{margin:0;font-size:13.5px;font-weight:700;color:#f8fafc}
   h3{margin:6px 0 4px;font-size:11.5px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}
   .btn{border:1px solid var(--border);background:#1e293b;color:#fff;padding:5px 11px;border-radius:6px;cursor:pointer;font-size:12px;font-weight:600}
   .btn.primary{background:#0284c7;border-color:#0369a1}
   .btn.success{background:#059669;border-color:#047857}
   .btn.danger{background:#991b1b;border-color:#b91c1c}
   .badge{padding:2px 7px;border-radius:999px;font-size:10.5px;font-weight:700}
   .badge.blue{background:rgba(56,189,248,.16);color:#38bdf8}
   .badge.green{background:rgba(16,185,129,.18);color:#34d399}
   .badge.amber{background:rgba(245,158,11,.18);color:#fbbf24}
   .badge.red{background:rgba(239,68,68,.18);color:#f87171}
   .list-col{display:flex;flex-direction:column;gap:5px}
   .list-item{text-align:left;background:#0b1120;border:1px solid #1e293b;color:var(--txt);padding:8px 10px;border-radius:7px;cursor:pointer}
   .list-item.active,.list-item:hover{border-color:var(--blue);background:#131f35}
   .row-between{display:flex;justify-content:space-between;align-items:center;gap:8px}
   .pill-row{display:flex;flex-wrap:wrap;gap:5px;align-items:center}
   .pill{background:#1e293b;color:#cbd5e1;padding:2px 7px;border-radius:5px;font-size:11px}
   .pill-btn{background:#172554;color:#93c5fd;border:1px solid #1e3a8a;padding:2px 8px;border-radius:5px;font-size:11px;cursor:pointer;font-family:monospace}
   .link-btn{background:none;border:none;color:#38bdf8;cursor:pointer;padding:0;font-size:12.5px}
   .kv-grid{display:grid;grid-template-columns:190px 1fr;gap:5px 10px;margin:0}
   .kv-grid dt{color:var(--muted);font-family:monospace;font-size:11.5px}
   .kv-grid dd{margin:0;word-break:break-word;font-family:monospace;font-size:11.5px}
   .data-table{width:100%;border-collapse:collapse;font-size:11.5px}
   .data-table th,.data-table td{text-align:left;padding:6px 8px;border-bottom:1px solid #1e293b;vertical-align:top}
   .data-table th{color:var(--muted);font-weight:600}
   .code-box{background:#060911;border:1px solid #1e293b;border-radius:7px;padding:10px;margin:0;overflow-x:auto;font-family:'SFMono-Regular',ui-monospace,Menlo,monospace;font-size:11.5px;color:#e2e8f0}
   .doc-box{background:#0b1120;border-left:3px solid var(--blue);padding:8px 10px;margin:0;color:#cbd5e1}
   .form-stack{display:flex;flex-direction:column;gap:8px}
   .row-form{display:flex;gap:8px;flex-wrap:wrap}
   .flex-1{flex:1;min-width:180px}
   .inp{background:#090d16;border:1px solid #334155;color:#fff;padding:7px 9px;border-radius:6px;font-size:12px;width:100%}
   .row-form .inp{width:auto}
   .notice{padding:8px 10px;border-radius:7px;font-size:12px}
   .notice.green{background:rgba(16,185,129,.15);border:1px solid #059669;color:#6ee7b7}
   .notice.red{background:rgba(239,68,68,.15);border:1px solid #b91c1c;color:#fca5a5}
   .eval-card{background:#0b1120;border:1px solid #1e293b;border-radius:7px;padding:8px;display:flex;flex-direction:column;gap:5px}
   .green-text{color:#34d399} .red-text{color:#f87171}")

(def ^:private datastar-js-text
  (delay
    (let [f (io/file store/checkout-root "resources/public/datastar.js")]
      (if (.isFile f) (slurp f) "// datastar.js missing"))))

(defn url-enc [^String s] (render/url-enc s))

(defn- parse-kv-pairs [s]
  (if (str/blank? s)
    {}
    (into {}
          (keep (fn [part]
                  (let [[k v] (str/split part #"=" 2)]
                    (when (seq k)
                      [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                       (java.net.URLDecoder/decode (or v "") "UTF-8")]))))
          (str/split s #"&"))))

(defn- parse-kw-input [^String s default-kw]
  (let [t (str/trim (str s))]
    (cond
      (str/blank? t) default-kw
      (str/starts-with? t ":") (edn/read-string t)
      :else (keyword t))))

;; --- Non-Blocking `^:seon/callable` UI Actions (Zero Blocking Inside `web-proc`!) ---

(defn- async-action
  "Returns a non-blocking directive `{:st st' :async {:req ... :slot ... :format-fn ...}}`
   so `web-proc` dispatches to `:server` via `a/put!` + `a/go` and returns in < 0.1 ms!"
  [st req slot format-fn]
  {:st st
   :async {:req req :slot slot :format-fn format-fn}})

(defn ^:seon/callable switch-tab! [st tab]
  {:st (assoc st :active-tab tab)})

(defn ^:seon/callable select-ns! [st nsym]
  {:st (assoc st :selected-ns nsym :selected-var nil)})

(defn ^:seon/callable select-var! [st nsym vsym]
  {:st (assoc st :active-tab :program :selected-ns nsym :selected-var vsym)})

(defn ^:seon/callable select-schema! [st skey]
  {:st (assoc st :active-tab :schemas :selected-schema skey)})

(defn ^:seon/callable select-branch! [st branch]
  {:st (assoc st :selected-branch branch :branch-feedback nil)})

(defn ^:seon/callable select-agent! [st agent-id]
  {:st (assoc st :selected-agent agent-id)})

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
  (async-action
   (assoc st :branch-feedback {:ok? true :text (str "Merging " branch " -> :main...")})
   {:op :merge :branch branch}
   :branch-feedback
   (fn [ans]
     {:ok? (:ok? ans)
      :text (if (:ok? ans)
              (str "Merged " branch " -> :main @ tx " (:tx ans) " (" (count (:touched-paths ans)) " files)")
              (str "Blocked / Conflict: " (pr-str (:conflicts ans))))})))

(defn ^:seon/callable toggle-pause! [st agent-id]
  (async-action st {:op :toggle-pause-agent :agent-id agent-id} nil nil))

(defn ^:seon/callable assign-health-fix! [st cls subject message]
  (let [tid (keyword "seon.health" (str "fix-" (mod (System/currentTimeMillis) 100000)))
        scope-ns (try
                   (let [s (str subject)]
                     (if (str/includes? s "/") (symbol (first (str/split s #"/" 2))) 'seon))
                   (catch Throwable _ 'seon))]
    (async-action
     (assoc st :active-tab :agents
            :branch-feedback {:ok? true :text (str "Spawned agent for health issue " cls " on " subject)})
     {:op :create-task :id tid :scope scope-ns :title (str "Fix " (name cls) " on " subject)
      :plan (str message) :spawn-agent? true :role :worker}
     nil nil)))

(defn ^:seon/callable register-schema-form! [st form]
  (let [k (parse-kw-input (:key form) :seon.custom/item)]
    (async-action
     (assoc st :schema-feedback {:ok? true :text (str "Validating & deriving " k "...")})
     (cond-> {:op :register-schema :key k :form (:form form "")}
       (not (str/blank? (:doc form))) (assoc :doc (:doc form))
       (not (str/blank? (:examples form))) (assoc :examples (:examples form)))
     :schema-feedback
     (fn [ans]
       {:ok? (:ok? ans)
        :selected-schema (when (:ok? ans) (:key ans))
        :text (if (:ok? ans)
                (str "Registered " (:key ans)
                     (when (seq (:derived-attributes ans))
                       (str " + derived Datahike attr " (:derived-attributes ans)))
                     " — seed=42 sample: " (:sample ans))
                (str "Rejected: " (:error ans)))}))))

(defn ^:seon/callable spawn-task-form! [st form]
  (let [title (str/trim (:title form "Build feature"))
        role (keyword (:role form "worker"))
        scope (when-not (str/blank? (:scope form)) (symbol (str/trim (:scope form))))
        tid (keyword "seon.task" (str "t" (mod (System/currentTimeMillis) 100000)))]
    (async-action
     st
     (cond-> {:op :create-task :id tid :title title :spawn-agent? true :role role}
       scope (assoc :scope scope))
     nil nil)))

(defn ^:seon/callable eval-repl-form! [st form]
  (let [b (parse-kw-input (:branch form) :branch/explorer)
        code-str (:code form "")]
    (async-action
     (assoc st :repl-branch (str b) :last-code code-str
            :eval-feedback {:ok? true :value "Evaluating..." :branch b})
     {:op :eval :branch b :code code-str :keep-warm? true}
     :eval-feedback
     identity)))

(defn invoke-call!
  "Dispatches either a registered Reagent closure (`?h=...` in `walk`'s `:handlers`) OR a stateless
   `^:seon/callable` Clojure call form (`?form=(ns/fn ...)`)."
  [st {:keys [hkey call-form form-fields]}]
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
    {:ok? false :error :seon.web/malformed-call :form call-form :st st}))

;; --- Live MCP JSON-RPC 2.0 Edge (`POST /mcp` -> Direct Repl & Health Into Standing Seon!) ---

(def ^:private mcp-tools
  [{:name "seon_repl"
    :description "Evaluate Clojure forms inside an isolated COW forked Seon agent REPL on a Datahike branch. `@seon.ctx/ctx` holds your reactive entity & namespace subtree; `(user/health)` checks system health; `(user/register-schema! ...)` adds Malli->Datahike schemas."
    :inputSchema {:type "object"
                  :properties {:code   {:type "string" :description "Clojure form(s) to evaluate."}
                               :branch {:type "string" :description "Datahike branch keyword, e.g. \":branch/work\" (default \":branch/mcp\")."}
                               :agent  {:type "string" :description "Agent keyword id, e.g. \":agent/mcp\"."}}
                  :required ["code"]}}
   {:name "seon_health"
    :description "Read system health (derived structural violations, blocking Flow procs, stored-data schema checks, global failing tests across branches, open proc faults)."
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
  "HTTP edge helper (runs ONLY on ring-chez edge fibers, NEVER inside any Flow proc!)."
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
          (let [ans (call-server-edge! server-in-ch req 30000)]
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (json/write-str
                    {:jsonrpc "2.0" :id id
                     :result {:content [{:type "text" :text (pr-str ans)}]
                              :isError (not (:ok? ans true))}})})))

      :else
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}})})))

;; --- Page Shell & Persistent HTTP Listener (`^:seon.flow/edge`) ---

(defn- page-shell [walk]
  (let [header (get-in walk [:units "seon-header" :html] "")
        stream (get-in walk [:units "seon-stream-unit" :html] "")
        tab    (get-in walk [:units "seon-tab-unit" :html] "")]
    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>Seon — Reactive System Health & Explorer</title>"
         "<style>" explorer-css "</style>"
         "<script type=\"module\" src=\"/datastar.js\"></script>"
         "</head><body>"
         "<div id=\"seon-app\">" header "<main id=\"seon-main\" class=\"main-wrap\">" stream tab "</main></div>"
         "<div style=\"display:none\" data-init=\"@get('/events', {retry: 'always', retryMaxCount: Infinity, openWhenHidden: true})\"></div>"
         "</body></html>")))

(defn ^:seon.flow/edge start-http-listener! [port]
  (let [delegate (volatile! nil)
        sse-tabs (volatile! {})
        tab-seq (volatile! 0)
        handler (fn [req]
                  (if (= [:get "/events"] [(:request-method req) (:uri req)])
                    (let [ch (a/chan 64)
                          tid (vswap! tab-seq inc)]
                      (vswap! sse-tabs assoc tid ch)
                      (when-let [d @delegate]
                        ((:on-new-tab! d) ch))
                      {:status 200
                       :headers {"Content-Type" "text/event-stream"
                                 "Cache-Control" "no-cache"
                                 "Connection" "keep-alive"}
                       :body ch})
                    (if-let [d @delegate]
                      ((:handle d) req)
                      {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon rebooting..."})))
        srv (when (not= 0 port)
              (adapter/run-server handler {:port (if (neg? port) 0 port) :host "127.0.0.1"}))
        bound-port (if srv (:port srv) 0)]
    {:server srv
     :port bound-port
     :handler handler
     :broadcast! (fn [^String chunk]
                   (when (and (seq @sse-tabs) (not (str/blank? chunk)))
                     (vswap! sse-tabs
                             (fn [tabs]
                               (reduce-kv
                                (fn [acc tid ch]
                                  (if (a/offer! ch chunk)
                                    (assoc acc tid ch)
                                    (do (a/close! ch) acc)))
                                {}
                                tabs)))))
     :tab-count (fn [] (count @sse-tabs))
     :set-delegate! (fn [d]
                      (vreset! delegate d)
                      (when d ((:on-delegate! d))))
     :stop! (fn []
              (doseq [[_ ch] @sse-tabs] (a/close! ch))
              (vreset! sse-tabs {})
              (when srv
                (try (adapter/stop-server srv {:drain-timeout-ms 1500}) (catch Throwable _))))}))

(defn- broadcast-patches! [st patches]
  (when (and (seq patches) (:broadcast! st))
    ((:broadcast! st) (apply str patches)))
  st)

(defn- advance-walk-and-broadcast! [st tx-datoms]
  (let [db @(:conn st)
        specs (ui/page-unit-specs st)
        {:keys [walk patches]} (render/step-walk (:walk st) db tx-datoms specs)
        st1 (-> st (assoc :walk walk) (update :frames inc))]
    (if (seq patches)
      [(broadcast-patches! st1 patches) patches]
      [st1 []])))

(defn- launch-async-server-op! [server-in-ch ui-ch {:keys [req slot format-fn]}]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (a/go
      (let [[ans _] (a/alts! [rch (a/timeout 30000)])]
        (when slot
          (let [val (if format-fn (format-fn (or ans {:ok? false :error :timeout})) (or ans {:ok? false :error :timeout}))]
            (a/put! ui-ch {:ev :action-done :slot slot :val val})))))))

(defn web-proc
  ([] {:params {:port "HTTP port"
                :listener "Persistent HTTP listener map"
                :conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository dir"
                :store-path "LMDB store path"
                :fork-parent-pid "PID of 1-thread Fork Parent"
                :fork-parent-ceiling "Chez GC ceiling of Fork Parent"
                :server-in-ch "Channel to :server"
                :report-chan "Flow report channel"
                :error-chan "Flow error channel"
                :on-power-loss "0-arg callback for Power-Loss reboot"
                :boot-seq "Boot sequence integer"}
       :ins {:db "Commit notices {:commit :tx :tx-data :reason} from :transactor"
             :stream "60fps live token & agent stream deltas from :model"}
       :outs {}
       :workload :io
       :doc "100% non-blocking Datastar incremental render proc, Reagent /call dispatcher & /mcp endpoint."
       :ping-map-fn (fn [st]
                      {:port (:bound-port st)
                       :active-tab (:active-tab st)
                       :tabs (if-let [tc (:tab-count st)] (tc) 0)
                       :frames (:frames st)})})
  ([{:keys [port listener conn cfg repo-dir store-path fork-parent-pid fork-parent-ceiling
            server-in-ch report-chan error-chan on-power-loss boot-seq]}]
   (let [ui-ch (a/chan 128)
         own-listener (when (and (nil? listener) (pos? (or port 0)))
                        (start-http-listener! port))
         eff-listener (or listener own-listener)
         st-init {:conn conn
                  :cfg cfg
                  :repo-dir repo-dir
                  :store-path store-path
                  :fork-parent-pid fork-parent-pid
                  :fork-parent-ceiling fork-parent-ceiling
                  :server-in-ch server-in-ch
                  :ui-ch ui-ch
                  :on-power-loss on-power-loss
                  :boot-seq (or boot-seq 1)
                  :bound-port (:port eff-listener 0)
                  :own-listener own-listener
                  :broadcast! (:broadcast! eff-listener)
                  :tab-count (:tab-count eff-listener)
                  :walk (render/empty-walk)
                  :active-tab :health
                  :selected-ns nil
                  :selected-var nil
                  :selected-schema nil
                  :selected-branch nil
                  :selected-agent nil
                  :repl-branch ":branch/explorer"
                  :last-code ""
                  :live-streams {}
                  :reports []
                  :errors []
                  :frames 0}
         init-walk (:walk (render/step-walk (render/empty-walk) @conn nil (ui/page-unit-specs st-init)))
         st0 (assoc st-init :walk init-walk)]
     (when eff-listener
       ((:set-delegate! eff-listener)
        {:on-delegate! (fn [] (a/put! ui-ch {:ev :broadcast-keyframe}))
         :on-new-tab!  (fn [ch] (a/put! ui-ch {:ev :new-tab :ch ch}))
         :handle
         (fn [req]
           (let [m (:request-method req)
                 uri (:uri req)]
             (cond
               (and (= m :get) (= uri "/datastar.js"))
               {:status 200
                :headers {"Content-Type" "text/javascript; charset=utf-8" "Cache-Control" "public, max-age=3600"}
                :body @datastar-js-text}

               (and (= m :get) (= uri "/"))
               (let [p (promise)]
                 (a/put! ui-ch {:ev :render-page :reply p})
                 {:status 200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body (deref p 5000 "Timeout rendering page")})

               (and (= m :post) (= uri "/op"))
               (let [body-str (if (string? (:body req)) (:body req) (slurp (:body req)))
                     edn-req (edn/read-string body-str)
                     ans (call-server-edge! server-in-ch edn-req 30000)]
                 {:status 200
                  :headers {"Content-Type" "application/edn; charset=utf-8"}
                  :body (pr-str ans)})

               (and (= m :post) (= uri "/mcp"))
               (let [body-str (if (string? (:body req)) (:body req) (slurp (:body req)))]
                 (handle-mcp-jsonrpc! server-in-ch body-str))

               (and (= m :post) (= uri "/call"))
               (let [qmap (parse-kv-pairs (:query-string req))
                     body-str (cond (nil? (:body req)) "" (string? (:body req)) (:body req) :else (slurp (:body req)))
                     form-fields (parse-kv-pairs body-str)
                     call-form (when-let [fs (:form qmap)] (edn/read-string fs))
                     hkey (:h qmap)
                     p (promise)]
                 (a/put! ui-ch {:ev :call :hkey hkey :call-form call-form :form-fields form-fields :reply p})
                 (let [ans (deref p 5000 {:ok? false :error :timeout})]
                   (if (:ok? ans)
                     {:status 200
                      :headers {"Content-Type" "text/event-stream; charset=utf-8" "Cache-Control" "no-cache"}
                      :body (apply str (:patches ans))}
                     {:status 403
                      :headers {"Content-Type" "application/edn; charset=utf-8"}
                      :body (pr-str (dissoc ans :st))})))

               :else
               {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"})))}))
     (assoc st0
            ::flow/in-ports (cond-> {:ui ui-ch}
                              report-chan (assoc :report report-chan)
                              error-chan  (assoc :error error-chan)))))
  ([st transition]
   (when (and (= transition ::flow/stop) (:own-listener st))
     ((:stop! (:own-listener st))))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :report
       (let [st1 (update st :reports #(vec (take 30 (cons msg %))))
             ;; Empty tx-datoms [] skips every DB-only panel — ONLY `#seon-stream-unit` re-renders!
             [st2 _] (advance-walk-and-broadcast! st1 [])]
         [st2 nil])

       :error
       (let [st1 (update st :errors #(vec (take 20 (cons msg %))))
             [st2 _] (advance-walk-and-broadcast! st1 [])]
         [st2 nil])

       :stream
       (let [{:keys [agent-id status text]} msg
             st1 (if (= status :done)
                   (update st :live-streams dissoc agent-id)
                   (update-in st [:live-streams agent-id]
                              (fn [prev]
                                {:status status
                                 :text (if text (str (:text prev "") text) (:text prev ""))})))
             ;; 60+ fps fast path: empty tx-datoms [] skips ALL DB panels, re-rendering ONLY `#seon-stream-unit`!
             [st2 _] (advance-walk-and-broadcast! st1 [])]
         [st2 nil])

       :db
       (let [[st2 _] (advance-walk-and-broadcast! st (:tx-data msg))]
         [st2 nil])

       :ui
       (case (:ev msg)
         :new-tab
         (let [ch (:ch msg)
               kf (render/datastar-patch-event (render/compose-keyframe (:walk st)))]
           (a/offer! ch kf)
           [st nil])

         :broadcast-keyframe
         (let [[st2 _] (advance-walk-and-broadcast! st nil)
               kf (render/datastar-patch-event (render/compose-keyframe (:walk st2)))]
           (broadcast-patches! st2 [kf])
           [st2 nil])

         :render-page
         (do (deliver (:reply msg) (page-shell (:walk st)))
             [st nil])

         :action-done
         (let [{:keys [slot val]} msg
               st1 (cond-> (assoc st slot val)
                     (and (map? val) (:selected-schema val))
                     (assoc :selected-schema (:selected-schema val)))
               [st2 _] (advance-walk-and-broadcast! st1 [])]
           [st2 nil])

         :call
         (let [res (invoke-call! st msg)]
           (if-not (:ok? res)
             (do (deliver (:reply msg) res)
                 [st nil])
             (let [st1 (:st res)
                   _ (when-let [async-spec (:async res)]
                       (launch-async-server-op! (:server-in-ch st1) (:ui-ch st1) async-spec))
                   [st2 patches] (advance-walk-and-broadcast! st1 [])]
               (deliver (:reply msg) {:ok? true :patches patches})
               [st2 nil])))

         [st nil])))))
