(ns seon.web
  "The `:web` Flow proc (`:io` workload) — 100% Non-Blocking HTTP/SSE Edge + 120fps Multi-Route
   Incremental Datastar Walk + Ephemeral Viewport Screenshot Edge + Live MCP:
   - ZERO `<!!`, `>!!`, `alts!!`, `Thread/sleep`, or `flow/ping` inside `web-proc` (verified by `seon.health`!).
   - 3-level progressive-disclosure routes: `/` (Root canvas), `/agent/<slug>` (2-panel Workspace),
     `/root/debug` & `/agent/<slug>/debug` (Zero-cost Wire & Context Debug).
   - Lock-free `GET` serving from per-route cached HTML + `8 ms` (120 Hz) SSE patch coalescer.
   - Tracks active human viewers per route (`:active-routes`) and captures an ephemeral tail
     viewport screenshot via headless Chrome ONLY when someone is viewing (never accumulated in history!).
   - Serves live MCP JSON-RPC 2.0 at `POST /mcp` (`seon_repl`, `seon_health`, `seon_test`, `seon_merge`, `seon_status`)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [ring-chez.adapter :as adapter]
            [seon.git :as git]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.store :as store]))

;; --- Seon Phosphor Theme (Warm Obsidian, Cream Text, Amber Signal — Light Mode in Warm Parchment) ---

(def ^:private app-css
  ":root{
     --font-mono:'JetBrains Mono','SF Mono',ui-monospace,Menlo,Monaco,monospace;
     --font-text:ui-sans-serif,system-ui,-apple-system,'Segoe UI',sans-serif;
     --base-950:#0d0d0c;--base-900:#1a1918;--base-850:#252422;--base-800:#302e2b;--base-700:#3d3a36;
     --text-50:#faf9f7;--text-100:#ece9e3;--text-200:#d4d0c8;--text-300:#b0aa9e;--text-400:#8a8478;--text-500:#6b665d;
     --signal:#f0b429;--success:#34d399;--error:#f87171;--warning:#fbbf24;--info:#60a5fa;--eval:#c084fc;
   }
   @media(prefers-color-scheme:light){
     :root:not([data-theme='dark']){
       --base-950:#f7f4ee;--base-900:#fffdf9;--base-850:#f1ece3;--base-800:#e4ddd1;--base-700:#d3cabb;
       --text-50:#17140f;--text-100:#221e18;--text-200:#3a352d;--text-300:#5c564b;--text-400:#7d766a;--text-500:#a29a8c;
       --signal:#b7791f;--success:#0f8a5f;--error:#c53030;--warning:#b7791f;--info:#2563eb;--eval:#7c3aed;
     }
   }
   @view-transition{navigation:auto}
   @media(prefers-reduced-motion:reduce){@view-transition{navigation:none}}
   *{box-sizing:border-box}
   html,body{margin:0;background:var(--base-950);color:var(--text-200);font-family:var(--font-text);font-size:14.5px;line-height:1.6;-webkit-font-smoothing:antialiased;min-height:100vh}
   a{color:inherit;text-decoration:none}
   .mono,pre,code{font-family:var(--font-mono)}
   .muted{color:var(--text-400)} .tiny{font-size:11.5px} .strong{font-weight:600} .sm-copy{font-size:13.5px;line-height:1.58;margin:4px 0;color:var(--text-200)}
   .kicker{font-family:var(--font-mono);text-transform:uppercase;letter-spacing:.14em;font-size:10.5px;color:var(--text-400);font-weight:500}
   .truncate{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:480px}
   .flex-1{flex:1;min-width:140px}

   /* Quiet Phosphor Top Bar */
   .topbar{view-transition-name:seon-bar;background:var(--base-950);border-bottom:1px solid var(--base-800);padding:10px 24px;position:sticky;top:0;z-index:30}
   .brand-row{display:flex;justify-content:space-between;align-items:center;gap:14px;flex-wrap:wrap}
   .brand{display:flex;align-items:baseline;gap:12px}
   .brand-link{display:inline-flex;align-items:baseline;gap:7px;color:var(--text-50)}
   .brand-link:hover{color:var(--signal)}
   .diamond{color:var(--signal);font-size:13px}
   .wordmark{font-family:var(--font-mono);font-size:14px;font-weight:700;letter-spacing:.04em}
   .crumb{font-family:var(--font-mono);color:var(--text-500);font-size:12px}
   .dot{width:7px;height:7px;border-radius:50%;display:inline-block}
   .dot.live{background:var(--success)}
   .dot.bad{background:var(--error)}
   .stats-strip{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
   .status-pill{display:inline-flex;align-items:center;gap:7px;background:transparent;border:1px solid var(--base-800);color:var(--text-300);padding:3px 10px;border-radius:999px;font-family:var(--font-mono);font-size:11.5px;cursor:pointer}
   .status-pill.ok:hover{border-color:var(--signal);color:var(--text-50)}
   .status-pill.bad{border-color:var(--error);color:var(--error)}
   .nav-pill{display:inline-flex;align-items:center;color:var(--text-400);padding:3px 9px;border-radius:5px;font-family:var(--font-mono);font-size:12px;cursor:pointer}
   .nav-pill:hover{background:var(--base-850);color:var(--text-50)}
   .overview-drawer{margin-top:12px;padding-top:12px;border-top:1px solid var(--base-800)}
   .overview-grid{display:grid;grid-template-columns:1.3fr 1fr;gap:20px}
   .agent-chip-list{display:flex;flex-direction:column;gap:5px;max-height:190px;overflow:auto}
   .agent-chip-row{display:flex;align-items:center;justify-content:space-between;gap:10px;background:var(--base-900);border:1px solid var(--base-800);border-radius:6px;padding:6px 10px}
   .link-strong{color:var(--signal);font-weight:600;font-size:12.5px}
   .link-strong:hover{text-decoration:underline}

   /* Subtle Live Stream Strip */
   .stream-strip{padding:4px 24px;border-bottom:1px solid var(--base-800);background:var(--base-950);font-family:var(--font-mono);font-size:11px;color:var(--text-500)}
   .stream-strip.active{background:var(--base-900);color:var(--signal)}

   /* Main Container & Cards */
   .main-wrap{padding:24px 24px 64px;max-width:1600px;margin:0 auto}
   .card{background:var(--base-900);border:1px solid var(--base-800);border-radius:8px;padding:15px 17px}
   .tile-wrap{display:flex;flex-direction:column;gap:4px}
   .tile-meta{display:flex;justify-content:space-between;align-items:center;padding:0 2px}
   .tile-card{padding:13px 15px}
   .card-head{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:10px;flex-wrap:wrap}
   h2{margin:0;font-family:var(--font-mono);font-size:12.5px;font-weight:600;letter-spacing:.03em;color:var(--text-100)}
   h3{margin:0;font-family:var(--font-mono);font-size:10.5px;text-transform:uppercase;letter-spacing:.14em;color:var(--text-400)}
   .stack{display:flex;flex-direction:column;gap:14px}
   .stack-sm{display:flex;flex-direction:column;gap:8px}
   .grid-2{display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start}
   .row-between{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap}
   .pill-row{display:flex;flex-wrap:wrap;gap:6px;align-items:center}

   /* Level 1: Root Conversational Canvas (Warm Editorial Typography) */
   .root-canvas{max-width:820px;margin:18px auto 0;display:flex;flex-direction:column;gap:28px}
   .root-hero{padding:8px 0 4px;border-bottom:1px solid var(--base-800);padding-bottom:20px}
   .root-kicker{font-family:var(--font-mono);text-transform:uppercase;letter-spacing:.14em;font-size:10.5px;color:var(--signal);margin-bottom:10px}
   .root-title{margin:0 0 10px;font-family:var(--font-text);font-size:34px;line-height:1.14;font-weight:650;letter-spacing:-.028em;color:var(--text-50)}
   .root-sub{margin:0;font-family:var(--font-text);font-size:16px;line-height:1.65;color:var(--text-300);max-width:660px}
   .root-visuals{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:14px}
   .root-stream{display:flex;flex-direction:column;gap:16px}
   .empty-canvas{border-left:2px solid var(--base-700);padding:6px 0 6px 16px;color:var(--text-400);font-size:15px}
   .empty-canvas code{background:var(--base-850);color:var(--signal);padding:1px 6px;border-radius:4px;font-size:13px}
   .chat-msg{padding:14px 18px;border-radius:8px;border:1px solid var(--base-800)}
   .chat-msg.from-user{background:var(--base-850);border-color:var(--base-700);margin-left:56px}
   .chat-msg.from-agent{background:transparent;border:none;border-left:2px solid var(--signal);border-radius:0;padding:4px 0 4px 18px}
   .chat-role{font-family:var(--font-mono);text-transform:uppercase;letter-spacing:.12em;font-size:10px;color:var(--text-400);margin-bottom:5px}
   .chat-body{font-family:var(--font-text);font-size:16px;line-height:1.7;color:var(--text-50);white-space:pre-wrap}
   .root-composer{background:var(--base-900);border:1px solid var(--base-700);border-radius:10px;padding:12px 15px;display:flex;flex-direction:column;gap:10px;position:sticky;bottom:16px}
   .root-composer:focus-within{border-color:var(--signal)}
   .composer-inp{background:transparent;border:none;color:var(--text-50);font-size:15.5px;line-height:1.55;resize:vertical;outline:none;font-family:var(--font-text);width:100%}
   .composer-actions{display:flex;justify-content:space-between;align-items:center;gap:10px}

   /* Level 2: Two-Panel Agent Workspace */
   .workspace-grid{display:grid;grid-template-columns:minmax(380px,1fr) minmax(420px,1.15fr);gap:18px;align-items:start}
   .ws-left,.ws-right{background:var(--base-900);border:1px solid var(--base-800);border-radius:8px;padding:15px 16px;display:flex;flex-direction:column;gap:12px;min-height:76vh}
   .ws-head{display:flex;justify-content:space-between;align-items:flex-start;gap:10px;padding-bottom:10px;border-bottom:1px solid var(--base-800)}
   .ws-timeline{display:flex;flex-direction:column;gap:10px;flex:1;max-height:58vh;overflow:auto;padding-right:4px}
   .ws-composer{border-top:1px solid var(--base-800);padding-top:10px}
   .goal-banner{background:var(--base-850);border-left:2px solid var(--signal);padding:8px 12px;border-radius:4px}
   .ws-msg{background:var(--base-850);border:1px solid var(--base-700);border-radius:6px;padding:9px 11px}
   .ws-eval-card{background:var(--base-950);border:1px solid var(--base-800);border-radius:6px;padding:9px 11px}
   .tiles-scroll{display:flex;flex-direction:column;gap:14px;max-height:66vh;overflow:auto;padding-right:4px}

   /* Level 3: Zero-Cost Debug View */
   .debug-wrap{display:flex;flex-direction:column;gap:16px}
   .viewport-bar{background:var(--base-950);border:1px solid var(--base-800);border-radius:6px;padding:10px 12px;margin-bottom:12px}
   .viewport-thumb{max-width:100%;max-height:420px;border-radius:6px;border:1px solid var(--base-700);margin-top:8px;display:block}
   .wire-list{display:flex;flex-direction:column;gap:6px}
   .wire-row{background:var(--base-950);border:1px solid var(--base-800);border-radius:6px;padding:7px 10px}
   .wire-summary{display:flex;align-items:center;gap:8px;cursor:pointer;list-style:none;font-family:var(--font-mono);font-size:12px}
   .wire-summary::-webkit-details-marker{display:none}
   .wire-summary::before{content:'▸';color:var(--text-500)}
   .wire-row[open]>.wire-summary::before{content:'▾';color:var(--signal)}
   .wire-body{margin-top:8px;padding-top:8px;border-top:1px solid var(--base-800)}
   .xml-tag{background:transparent;border:1px solid var(--base-700);color:var(--eval)}

   /* Shared Phosphor Controls */
   .eval-fold{background:var(--base-950);border:1px solid var(--base-800);border-radius:6px;padding:6px 10px}
   .eval-summary{display:flex;align-items:center;gap:8px;cursor:pointer;list-style:none;flex-wrap:wrap;font-family:var(--font-mono);font-size:12px}
   .eval-summary::-webkit-details-marker{display:none}
   .eval-summary::before{content:'▸';color:var(--text-500)}
   .eval-fold[open]>.eval-summary::before{content:'▾';color:var(--signal)}
   .eval-detail{margin-top:8px;display:flex;flex-direction:column;gap:6px}
   .fold summary{cursor:pointer;font-family:var(--font-mono)}
   .handle-pill{background:transparent;border:1px solid var(--base-700);color:var(--eval)}
   .btn{border:1px solid var(--base-700);background:var(--base-850);color:var(--text-100);padding:6px 13px;border-radius:6px;cursor:pointer;font-family:var(--font-mono);font-size:12px;font-weight:500}
   .btn:hover{border-color:var(--signal);color:var(--text-50)}
   .btn.sm{padding:4px 9px;font-size:11px;border-radius:5px}
   .btn.primary{background:var(--signal);border-color:var(--signal);color:#0d0d0c;font-weight:600}
   .btn.primary:hover{opacity:.92;color:#0d0d0c}
   .btn.success{border-color:var(--success);color:var(--success);background:transparent}
   .btn.danger{border-color:var(--error);color:var(--error);background:transparent}
   .link-muted{background:none;border:none;color:var(--text-500);cursor:pointer;padding:0;font-family:var(--font-mono)}
   .link-muted:hover{color:var(--error)}
   .badge{padding:1px 7px;border-radius:999px;border:1px solid var(--base-700);font-family:var(--font-mono);font-size:10.5px;font-weight:500;display:inline-flex;align-items:center;color:var(--text-300)}
   .badge.blue{border-color:rgba(96,165,250,.4);color:var(--info)}
   .badge.green{border-color:rgba(52,211,153,.4);color:var(--success)}
   .badge.amber{border-color:rgba(240,180,41,.45);color:var(--signal)}
   .badge.red{border-color:rgba(248,113,113,.45);color:var(--error)}
   .badge.purple{border-color:rgba(192,132,252,.45);color:var(--eval)}
   .badge.muted{border-color:var(--base-800);color:var(--text-400)}
   .pill{background:var(--base-850);border:1px solid var(--base-800);color:var(--text-200);padding:1px 7px;border-radius:4px;font-family:var(--font-mono);font-size:11px}
   .kv-grid{display:grid;grid-template-columns:180px 1fr;gap:4px 10px;margin:0}
   .kv-grid dt{color:var(--text-400);font-family:var(--font-mono);font-size:11.5px}
   .kv-grid dd{margin:0;word-break:break-word;font-family:var(--font-mono);font-size:11.5px;color:var(--text-100)}
   .data-table{width:100%;border-collapse:collapse;font-family:var(--font-mono);font-size:11.5px}
   .data-table th,.data-table td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--base-800);vertical-align:top}
   .data-table th{color:var(--text-400);font-weight:500}
   .code-box{background:var(--base-950);border:1px solid var(--base-800);border-radius:6px;padding:9px 11px;margin:0;overflow-x:auto;font-family:var(--font-mono);font-size:11.5px;color:var(--text-100)}
   .doc-box{background:var(--base-850);border-left:2px solid var(--signal);padding:8px 12px;border-radius:4px;color:var(--text-200);font-family:var(--font-text)}
   .form-stack{display:flex;flex-direction:column;gap:8px}
   .row-form{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
   .inp{background:var(--base-950);border:1px solid var(--base-700);color:var(--text-100);padding:7px 10px;border-radius:5px;font-family:var(--font-mono);font-size:12px;width:100%;outline:none}
   .inp:focus{border-color:var(--signal)}
   .inp.sm{padding:5px 8px;font-size:11.5px;width:auto}
   .notice{padding:8px 12px;border-radius:6px;font-family:var(--font-mono);font-size:12px;border:1px solid var(--base-700);background:var(--base-900)}
   .notice.green{border-color:rgba(52,211,153,.45);color:var(--success)}
   .notice.red{border-color:rgba(248,113,113,.5);color:var(--error)}
   .green-text{color:var(--success)} .red-text{color:var(--error)}
   @media(max-width:1080px){.workspace-grid,.grid-2,.overview-grid{grid-template-columns:1fr}}")

(def ^:private datastar-js-text
  (delay
    (let [f (io/file store/checkout-root "resources/public/datastar.js")]
      (if (.isFile f) (slurp f) "// datastar.js missing"))))

(defn url-enc [^String s] (render/url-enc s))

(defn parse-route
  "Maps clean URLs to `{:view :root|:agent|:debug :agent-id kw :url str}`:
   - `/`                    -> Level 1 Root canvas
   - `/root/debug`          -> Level 3 Debug for `:agent/root`
   - `/agent/<slug>`        -> Level 2 Workspace for `<slug>`
   - `/agent/<slug>/debug`  -> Level 3 Debug for `<slug>`"
  [^String uri]
  (let [u (or uri "/")]
    (cond
      (or (= u "/") (= u ""))
      {:view :root :agent-id :agent/root :url "/"}

      (= u "/root/debug")
      {:view :debug :agent-id :agent/root :url "/root/debug"}

      (str/starts-with? u "/agent/")
      (let [rest-part (subs u 7)
            parts (str/split rest-part #"/" -1)
            slug (java.net.URLDecoder/decode (first parts) "UTF-8")
            aid (ui/slug->agent-id slug)
            debug? (= "debug" (second parts))]
        {:view (if debug? :debug :agent)
         :agent-id aid
         :url (if debug? (ui/debug-href aid) (ui/agent-href aid))})

      :else nil)))

(defn- parse-kv-pairs [s]
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

(defn- parse-multipart-fields [^String ct ^String body]
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

(defn- parse-form-body [req]
  (let [ct (str/lower-case (or (get-in req [:headers "content-type"]) ""))
        body-str (cond
                   (nil? (:body req)) ""
                   (string? (:body req)) (:body req)
                   :else (slurp (:body req)))]
    (cond
      (str/blank? body-str) {}
      (str/includes? ct "multipart/form-data") (parse-multipart-fields ct body-str)
      (str/includes? ct "application/x-www-form-urlencoded") (parse-kv-pairs body-str)
      (str/includes? ct "application/json")
      (let [parsed (try (json/read-str body-str :key-fn keyword) (catch Throwable _ nil))]
        (if (and (map? parsed) (seq parsed)) parsed {}))
      :else (parse-kv-pairs body-str))))

(defn- parse-kw-input [^String s default-kw]
  (let [t (str/trim (str s))]
    (cond
      (str/blank? t) default-kw
      (str/starts-with? t ":") (edn/read-string t)
      :else (keyword t))))

;; --- Ephemeral Viewport Snapshot Slot (Zero Chrome Subprocess — Ready for Native WRY `takeSnapshot`!) ---

(defn ^:seon.flow/edge capture-viewport-async!
  "No-op stub until native WRY `WKWebView.takeSnapshotWithConfiguration:` is wired (Phase 2).
   Intentionally never spawns `Google Chrome.app` (which triggers macOS Keychain dialogs)."
  [_port _route-url _tx _ui-ch]
  nil)

;; --- Non-Blocking `^:seon/callable` UI Actions (Zero Blocking Inside `web-proc`!) ---

(defn- async-action [st req slot format-fn]
  {:st st :async {:req req :slot slot :format-fn format-fn}})

(defn ^:seon/callable toggle-overview! [st]
  {:st (update st :overview-open? not)})

;; Kept for backward compatibility with existing tests & shortcuts
(defn ^:seon/callable switch-tab! [st tab]
  (let [next-route (case tab
                     (:schemas :health :branches) {:view :debug :agent-id :agent/root :url "/root/debug"}
                     :agents {:view :agent :agent-id :agent/root :url "/agent/root"}
                     {:view :root :agent-id :agent/root :url "/"})]
    {:st (assoc st :active-tab tab :route next-route)}))

(defn ^:seon/callable select-branch! [st branch]
  {:st (assoc st :selected-branch branch :branch-feedback nil)})

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

(defn ^:seon/callable spawn-agent-form! [st form]
  (let [aid (parse-kw-input (:agent_id form) (keyword "agent" (str "w" (mod (System/currentTimeMillis) 10000))))
        branch (keyword "branch" (str (or (namespace aid) "agent") "." (name aid)))
        scope (when-not (str/blank? (:scope form)) (symbol (str/trim (:scope form))))
        goal (str/trim (str (:goal form "")))]
    (async-action
     (assoc st :overview-open? false
            :branch-feedback {:ok? true :text (str "Spawned " aid " on " branch)})
     (cond-> {:op :spawn-agent :id aid :parent :agent/root :branch branch :role :worker :goal goal}
       scope (assoc :scope scope))
     nil nil)))

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

(defn ^:seon/callable chat-root-form! [st form]
  (let [txt (str/trim (str (:text form "")))
        root-br (or (:seon.agent/branch (d/pull @(:conn st) '[:seon.agent/branch] [:seon.agent/id :agent/root]))
                    :branch/root)]
    (cond
      (str/blank? txt) {:st st}

      ;; Starting with `(` or `[` evaluates directly in `:agent/root`'s live COW REPL on its branch!
      (or (str/starts-with? txt "(") (str/starts-with? txt "["))
      (async-action
       (assoc st :branch-feedback {:ok? true :text (str "Evaluating in :agent/root REPL (" root-br ")...")})
       {:op :eval :branch root-br :agent-id :agent/root :code txt :keep-warm? true}
       :branch-feedback
       (fn [ans]
         {:ok? (:ok? ans)
          :text (if (:ok? ans)
                  (str (or (:handle ans) 'result/e?) " => " (:value ans) " (" (:ms ans) " ms)")
                  (str "Eval error: " (:error ans)))}))

      :else
      (async-action
       (assoc st :branch-feedback {:ok? true :text "Sent message to :agent/root."})
       {:op :send-message :from :user :to :agent/root :type :chat :body txt}
       nil nil))))

(defn ^:seon/callable eval-agent-form! [st agent-id branch form]
  (let [code-str (str/trim (str (:code form "")))]
    (if (str/blank? code-str)
      {:st st}
      (async-action
       (assoc st :branch-feedback {:ok? true :text (str "Evaluating on " branch "...")})
       {:op :eval :branch branch :agent-id agent-id :code code-str :keep-warm? true}
       :branch-feedback
       (fn [ans]
         {:ok? (:ok? ans)
          :text (if (:ok? ans)
                  (str (or (:handle ans) 'result/e?) " => " (:value ans)
                       " (" (:ms ans) " ms · " (:ticks ans) " ticks)")
                  (str "Error: " (:error ans)))})))))

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

(defn ^:seon/callable capture-viewport! [st target-url]
  {:st (assoc st :branch-feedback {:ok? true :text (str "Ephemeral viewport slot ready for " target-url " (native WRY snapshot pending Phase 2 — Chrome subprocess disabled).")})})

(defn invoke-call!
  "Dispatches either a registered Reagent closure (`?h=...`) OR a stateless `^:seon/callable`
   Clojure call form (`?form=(ns/fn ...)`). Fault-isolated: never throws out of `web-proc`!"
  [st {:keys [hkey call-form form-fields]}]
  (try
    (cond
      hkey
      (let [hfn (or (get-in st [:walk :handlers hkey])
                    (some #(get-in % [:handlers hkey]) (vals (:walks st))))]
        (if hfn
          (let [res (if (seq form-fields) (hfn st form-fields) (hfn st))]
            (if (and (map? res) (contains? res :st))
              (assoc res :ok? true)
              {:ok? true :st (if (and (map? res) (contains? res :conn)) res st)}))
          {:ok? false :error :seon.web/stale-handler :handler hkey :st st}))

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

;; --- Live MCP JSON-RPC 2.0 Edge (`POST /mcp` -> Direct Repl & Health Into Standing Seon!) ---

(def ^:private mcp-tools
  [{:name "seon_repl"
    :description "Evaluate Clojure forms inside an isolated COW forked Seon agent REPL on a Datahike branch. Returns live runtime handle `result/e<n>`, `@seon.ctx/ctx` reactive entity access, `(user/pin! ...)`, `(user/health)`, and `(user/register-schema! ...)`."
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
          (let [ans (call-server-edge! server-in-ch req 30000)]
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (json/write-str
                    {:jsonrpc "2.0" :id id
                     :result {:content [{:type "text" :text (pr-str ans)}]
                              :isError (not (:ok? ans true))}})})))

      :else
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}})})))

;; --- Page Shell & Persistent Multi-Route HTTP/SSE Listener (`^:seon.flow/edge`) ---

(defn- page-shell [walk route]
  (let [url (or (:url route) (ui/route->url route))
        title (case (:view route)
                :root  "Seon — Root"
                :agent (str "Seon — " (:agent-id route))
                :debug (str "Seon Debug — " (:agent-id route))
                "Seon")
        header (get-in walk [:units "seon-header" :html] "")
        stream (get-in walk [:units "seon-stream-unit" :html] "")
        view   (get-in walk [:units "seon-view-unit" :html] "")]
    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>" (render/esc title) "</title>"
         "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='6' fill='%23f0b429'/%3E%3C/svg%3E\">"
         "<style>" app-css "</style>"
         "<script type=\"module\" src=\"/datastar.js\"></script>"
         "</head><body>"
         "<div id=\"seon-app\">" header stream "<main id=\"seon-main\" class=\"main-wrap\">" view "</main></div>"
         "<div style=\"display:none\" data-init=\"@get('/events?route=" (url-enc url)
         "', {retry: 'always', retryMaxCount: Infinity, openWhenHidden: true})\"></div>"
         "</body></html>")))

(defn ^:seon.flow/edge start-http-listener! [port]
  (let [delegate (volatile! nil)
        latest-pages (volatile! {})     ; {route-url html}
        sse-tabs (volatile! {})         ; {tid {:ch ch :url url}}
        tab-seq (volatile! 0)
        pending-chunks (volatile! {})   ; {url [chunk ...]} coalesced at 120 Hz (8 ms)
        flushing? (volatile! false)
        flush-now! (fn []
                     (let [batch @pending-chunks]
                       (vreset! pending-chunks {})
                       (when (seq batch)
                         (doseq [[target-url chunks] batch
                                 :let [merged (apply str chunks)]
                                 :when (not (str/blank? merged))]
                           (vswap! sse-tabs
                                   (fn [tabs]
                                     (reduce-kv
                                      (fn [acc tid {:keys [ch url] :as entry}]
                                        (if (or (= target-url :all) (= url target-url))
                                          (if (a/offer! ch merged)
                                            (assoc acc tid entry)
                                            (do (a/close! ch) acc))
                                          (assoc acc tid entry)))
                                      {}
                                      tabs)))))))
        schedule-flush! (fn []
                          (when (not @flushing?)
                            (vreset! flushing? true)
                            (a/go
                              (a/<! (a/timeout 8))
                              (vreset! flushing? false)
                              (flush-now!))))
        active-routes-fn (fn []
                           (frequencies (map :url (vals @sse-tabs))))
        handler (fn [req]
                  (let [m (:request-method req)
                        uri (:uri req)]
                    (cond
                      (= [:get "/events"] [m uri])
                      (let [qmap (parse-kv-pairs (:query-string req))
                            route-url (or (:route qmap) "/")
                            route (or (parse-route route-url) {:view :root :agent-id :agent/root :url "/"})
                            ch (a/chan 64)
                            tid (vswap! tab-seq inc)]
                        (vswap! sse-tabs assoc tid {:ch ch :url (:url route)})
                        (when-let [d @delegate]
                          ((:on-new-tab! d) ch route (active-routes-fn)))
                        {:status 200
                         :headers {"Content-Type" "text/event-stream"
                                   "Cache-Control" "no-cache"
                                   "Connection" "keep-alive"}
                         :body ch})

                      (and (= m :get) (parse-route uri))
                      (let [route (parse-route uri)
                            u (:url route)]
                        (if-let [html (get @latest-pages u)]
                          {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html}
                          (if-let [d @delegate]
                            (let [html ((:render-cold! d) route)]
                              (vswap! latest-pages assoc u html)
                              {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html})
                            {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon booting..."})))

                      :else
                      (if-let [d @delegate]
                        ((:handle d) req)
                        {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon rebooting..."}))))
        srv (when (not= 0 port)
              (adapter/run-server handler {:port (if (neg? port) 0 port) :host "127.0.0.1"}))
        bound-port (if srv (:port srv) 0)]
    {:server srv
     :port bound-port
     :handler handler
     :set-page! (fn [url ^String html] (vswap! latest-pages assoc url html))
     :active-routes active-routes-fn
     :broadcast! (fn [url ^String chunk]
                   (when (and (seq @sse-tabs) (not (str/blank? chunk)))
                     (vswap! pending-chunks update url (fnil conj []) chunk)
                     (schedule-flush!)))
     :tab-count (fn [] (count @sse-tabs))
     :set-delegate! (fn [d]
                      (vreset! delegate d)
                      (when d ((:on-delegate! d))))
     :stop! (fn []
              (doseq [[_ {:keys [ch]}] @sse-tabs] (a/close! ch))
              (vreset! sse-tabs {})
              (when srv
                (try (adapter/stop-server srv {:drain-timeout-ms 1500}) (catch Throwable _))))}))

(defn- advance-route-walk! [st route tx-datoms]
  (let [db @(:conn st)
        url (:url route)
        prev-walk (get-in st [:walks url] (render/empty-walk))
        st-for-route (assoc st :route route :walk prev-walk)
        specs (ui/page-unit-specs st-for-route)
        {:keys [walk patches render-errors]} (render/step-walk prev-walk db tx-datoms specs)
        st1 (cond-> (-> st
                        (assoc-in [:walks url] walk)
                        (cond-> (= url (get-in st [:route :url])) (assoc :walk walk)))
              (seq render-errors) (update :errors #(vec (take 20 (concat render-errors %)))))]
    (when-let [sp (:set-page! st1)]
      (sp url (page-shell walk route)))
    (when (and (seq patches) (:broadcast! st1))
      ((:broadcast! st1) url (apply str patches)))
    st1))

(defn- advance-all-watched-walks! [st tx-datoms]
  (let [active-map (if-let [ar (:active-routes-fn st)] (ar) {})
        st0 (-> st (assoc :active-routes active-map) (update :frames inc))
        routes-to-step (distinct (concat [(:route st0)]
                                         (keep parse-route (keys active-map))
                                         (keep parse-route (keys (:walks st0)))))]
    (reduce (fn [acc r] (advance-route-walk! acc r tx-datoms)) st0 routes-to-step)))

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
             :stream "120fps live token & agent stream deltas from :model"}
       :outs {}
       :workload :io
       :doc "100% non-blocking multi-route 120fps Datastar render proc, ephemeral viewport capture, Reagent /call & /mcp."
       :ping-map-fn (fn [st]
                      {:port (:bound-port st)
                       :tabs (if-let [tc (:tab-count st)] (tc) 0)
                       :active-routes (:active-routes st)
                       :frames (:frames st)})})
  ([{:keys [port listener conn cfg repo-dir store-path fork-parent-pid fork-parent-ceiling
            server-in-ch report-chan error-chan on-power-loss boot-seq]}]
   (let [ui-ch (a/chan 128)
         own-listener (when (and (nil? listener) (pos? (or port 0)))
                        (start-http-listener! port))
         eff-listener (or listener own-listener)
         root-route {:view :root :agent-id :agent/root :url "/"}
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
                  :set-page! (:set-page! eff-listener)
                  :broadcast! (:broadcast! eff-listener)
                  :tab-count (:tab-count eff-listener)
                  :active-routes-fn (:active-routes eff-listener)
                  :route root-route
                  :walk (render/empty-walk)
                  :walks {}
                  :active-routes {}
                  :viewports {}
                  :overview-open? false
                  :selected-branch nil
                  :live-streams {}
                  :reports []
                  :errors []
                  :frames 0}
         st0 (advance-route-walk! st-init root-route nil)]
     (when eff-listener
       ((:set-delegate! eff-listener)
        {:on-delegate! (fn [] (a/put! ui-ch {:ev :broadcast-keyframe}))
         :on-new-tab!  (fn [ch route active-routes]
                         (a/put! ui-ch {:ev :new-tab :ch ch :route route :active-routes active-routes}))
         :render-cold! (fn [route]
                         (let [res (render/step-walk (render/empty-walk) @conn nil
                                                     (ui/page-unit-specs (assoc st0 :route route)))]
                           (a/put! ui-ch {:ev :watch-route :route route})
                           (page-shell (:walk res) route)))
         :handle
         (fn [req]
           (let [m (:request-method req)
                 uri (:uri req)]
             (cond
               (and (= m :get) (= uri "/datastar.js"))
               {:status 200
                :headers {"Content-Type" "text/javascript; charset=utf-8" "Cache-Control" "public, max-age=3600"}
                :body @datastar-js-text}

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
                     form-fields (parse-form-body req)
                     call-form (when-let [fs (:form qmap)]
                                 (try (edn/read-string fs) (catch Throwable _ nil)))
                     hkey (:h qmap)]
                 (if (and (nil? hkey)
                          (or (not (and (seq? call-form) (qualified-symbol? (first call-form))))
                              (let [v (resolve (first call-form))]
                                (not (and v (:seon/callable (meta v)))))))
                   {:status 403
                    :headers {"Content-Type" "application/edn; charset=utf-8"}
                    :body (pr-str {:ok? false :error :seon.web/not-callable :form call-form})}
                   ;; Clean empty 200 text/event-stream -> Datastar completes with 0 console/network errors,
                   ;; while actual DOM morphs arrive over `/events` SSE at 120 Hz!
                   (do (a/put! ui-ch {:ev :call :hkey hkey :call-form call-form :form-fields form-fields})
                       {:status 200
                        :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
                        :body ""})))

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
       (let [st1 (update st :reports #(vec (take 30 (cons msg %))))]
         [(advance-all-watched-walks! st1 []) nil])

       :error
       (let [st1 (update st :errors #(vec (take 20 (cons msg %))))]
         [(advance-all-watched-walks! st1 []) nil])

       :stream
       (let [{:keys [agent-id status text]} msg
             st1 (if (= status :done)
                   (update st :live-streams dissoc agent-id)
                   (update-in st [:live-streams agent-id]
                              (fn [prev]
                                {:status status
                                 :text (if text (str (:text prev "") text) (:text prev ""))})))]
         [(advance-all-watched-walks! st1 []) nil])

       :db
       (let [st1 (advance-all-watched-walks! st (:tx-data msg))]
         [st1 nil])

       :ui
       (case (:ev msg)
         :new-tab
         (let [{:keys [ch route active-routes]} msg
               st1 (advance-route-walk! (assoc st :active-routes (or active-routes {})) route nil)
               kf (render/datastar-patch-event (render/compose-keyframe (get-in st1 [:walks (:url route)])))]
           (a/offer! ch kf)
           [st1 nil])

         :watch-route
         [(advance-route-walk! st (:route msg) nil) nil]

         :viewport-captured
         (let [st1 (assoc-in st [:viewports (:url msg)] (:shot msg))]
           [(advance-all-watched-walks! st1 []) nil])

         :broadcast-keyframe
         (let [st1 (advance-all-watched-walks! st nil)]
           [st1 nil])

         :action-done
         (let [{:keys [slot val]} msg
               st1 (assoc st slot val)]
           ;; Pass `nil` tx-datoms so branch-backed agent/root/debug views re-read latest branch head!
           [(advance-all-watched-walks! st1 nil) nil])

         :call
         (let [res (invoke-call! st msg)]
           (if-not (:ok? res)
             (let [st1 (update st :errors #(vec (take 20 (cons (dissoc res :st) %))))]
               [(advance-all-watched-walks! st1 []) nil])
             (let [st1 (:st res)
                   _ (when-let [async-spec (:async res)]
                       (launch-async-server-op! (:server-in-ch st1) (:ui-ch st1) async-spec))]
               [(advance-all-watched-walks! st1 []) nil])))

         [st nil])))))
