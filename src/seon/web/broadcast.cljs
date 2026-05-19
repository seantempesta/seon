(ns seon.web.broadcast
  "DB tx-listener that pushes per-agent render morphs to all open SSE
   connections. Per spec-05 §15.4 (watch/diff/push) + §15.4a (per-agent
   error isolation).

   Pipeline per tx:

     1. Listener fires with `:seon.db/db` (post-commit snapshot).
     2. For each `:seon.agent/state {:idle :running}` agent:
        a. Resolve `:seon.render/html` slot (defaults to
           `'seon.render.default/view`).
        b. Dispatch via `seon.render/html-dispatch` (literal hiccup
           short-circuits; symbol resolves via globalThis walker).
        c. Render the returned hiccup to an HTML string.
        d. If the per-agent error wraps the body (renderer threw),
           transact a `:seon.log/level :error` entity AND render the
           fallback pretty-html with the error visible in the tile —
           the agent sees its own error next turn, the user sees a
           red banner this turn.
        e. Diff against `!last-rendered` cache; emit
           `datastar-patch-elements` SSE only if changed.

   V0.5 re-renders every running agent on every tx. For N ≤ 20 agents
   this is fast enough; the byte-level diff (step 3e) elides
   transactor-internal txes that didn't change any tile. The smarter
   'which attrs did this render fn read last time, only re-run on
   those' tracking is V1+ work.

   Future cleanup: when the JVM seon server takes over rendering
   (V1+) this file becomes vestigial. The JVM has an analogous
   path in `seon.web.sse/refresh-all!`."
  (:require
    [seon.db :as db]
    [seon.log :as log]
    [seon.render :as render]
    [seon.render.default :as default]
    [seon.ui.html :as html]
    [seon.web.sse :as sse]))

;; ============================================================
;; Cache — agent-id → last HTML string we pushed. In-memory; rebuilt
;; on pod restart (first render after boot is always pushed).
;; ============================================================

(defonce ^:private !last-rendered (atom {}))

(defn clear-cache!
  "Drop the per-agent last-rendered cache. Next tx will push a fresh
   morph for every agent. Useful from the REPL when iterating on a
   renderer that didn't structurally change the DOM."
  []
  (reset! !last-rendered {}))

;; ============================================================
;; Render with per-agent error isolation
;; ============================================================

(defn- render-agent!
  "Resolve + dispatch the agent's `:seon.render/html`, return the
   serialized HTML string. On any exception, transacts an error log
   AND returns a fallback HTML that visibly shows the error message
   so the user sees what broke."
  [db ent]
  (let [aid   (:seon.agent/id ent)
        slot  (:seon.render/html ent 'seon.render.default/view)
        input {:seon.db/db db :seon.agent/id aid}]
    (try
      (let [{:seon.render/keys [hiccup]} (render/html-dispatch slot input)]
        (html/->string hiccup))
      (catch :default e
        (let [msg (or (some-> e .-message) (str e))]
          ;; Persist the error (best-effort; soft-fails to stderr).
          (log/error! {:seon.log/source  ::render
                       :seon.log/agent   aid
                       :seon.log/message msg
                       :seon.log/stack   (or (some-> e .-stack) "")})
          ;; Render a fallback tile that shows the error inline so the
          ;; user sees what's happening without consulting the log.
          (html/->string
            [:div {:id (str "agent-" aid)
                   :class "h-full p-3 bg-base-900 border border-error/40 rounded"}
             [:header {:class "flex items-center gap-2 text-error font-bold mb-2"}
              "⚠ render error"]
             [:p {:class "text-xs text-error font-mono break-all"} msg]
             [:p {:class "text-xs text-text-500 mt-2"}
              (str "agent " aid " — slot " (pr-str slot))]]))))))

;; ============================================================
;; Tx-listener
;; ============================================================

(defn- log-only-tx?
  "True when every attr touched by the tx is in the `seon.log` namespace.
   We skip these to break a self-trigger loop: render-agent!'s
   `log/error!` is itself a tx; if the broken renderer keeps throwing
   on every listener fire, we'd loop forever transacting new log
   entries. Log writes never change a tile's render anyway — `recent-errors`
   is read at next legitimate tx."
  [attr-index]
  (let [touched-attrs (keys attr-index)]
    (and (seq touched-attrs)
         (every? #(and (keyword? %)
                       (= "seon.log" (namespace %)))
                 touched-attrs))))

(defn- broadcast-on-tx
  "Iterate every running agent; emit a `datastar-patch-elements`
   per-agent only when its rendered HTML differs from the last push."
  [{:seon.db/keys [db attr-index]}]
  (when-not (log-only-tx? attr-index)
    (doseq [ent (default/all-running-agents db)
            :when (some? ent)
            :let [aid  (:seon.agent/id ent)
                  html-str (render-agent! db ent)
                  prev (get @!last-rendered aid)]
            :when (not= prev html-str)]
      (sse/emit-patch! html-str)
      (swap! !last-rendered assoc aid html-str))))

;; ============================================================
;; Install / remove
;; ============================================================

(defn install!
  "Register the broadcast tx-listener under `::broadcast`. Idempotent —
   re-registering replaces the prior handler (datahike upstream
   semantics). Returns the listener key."
  []
  (db/listen!
    {:seon.db/key     ::broadcast
     :seon.db/handler broadcast-on-tx}))

(defn uninstall!
  "Remove the broadcast listener. Useful from the REPL when iterating
   on the listener body without restarting the pod."
  []
  (db/unlisten! {:seon.db/key ::broadcast}))
