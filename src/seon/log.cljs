(ns seon.log
  "Error / warn / info / debug logging. Thin wrappers around
   `seon.db/transact!` that stamp `:seon.log/at` + `:seon.log/level` and
   submit a `:seon.log/entry` entity.

   Per spec-05 §15.4a errors are FIRST-CLASS DATA in the pod: every
   thrown exception inside a renderer, eval, agent loop, or boundary
   call lands in the DB so

     1. the agent sees its own errors via `seon.render.default/recent-errors`
        in next turn's ctx
     2. the user sees them in the agent tile's red error banner

   Never throws — if the DB transact itself fails (the catastrophic
   case), falls back to `js/console.error` so the message reaches stderr
   at minimum.

   Schema for `:seon.log/entry`:
     :seon.log/at      :inst      ; auto-stamped
     :seon.log/level   :enum      ; auto-stamped
     :seon.log/source  :keyword   ; required by caller, namespaced
     :seon.log/agent   :string    ; optional — agent id when applicable
     :seon.log/message :string    ; required
     :seon.log/stack   :string    ; optional — JS stack trace
     :seon.log/data    :any       ; optional — structured payload
     :seon.log/dismissed-at :inst ; set when user clicks × on the tile"
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; Console output — structured, grepable. Format:
;;
;;   2026-05-19T18:23:45.123Z  INFO  [seon.web.serve] listening on …
;;
;; ISO timestamp + 5-char level + bracketed source + message. Errors
;; go to stderr (`console.error`); everything else to stdout
;; (`console.log`). Use these for the ephemeral process-lifetime log
;; (boot messages, request handling, turn ticks). Use error!/warn!/
;; info!/debug! BELOW (the DB-backed pair) for entries you want the
;; agent to see in `recent-errors` ctx or the user to see in the tile.
;; ============================================================

(defn- ->safe-str
  "Render a non-string log payload as a flat one-line edn string. JS's
   `console.log` prints CLJS persistent collections as their internal
   Object guts; pr-str gives a readable, grepable, machine-friendly
   string instead. Errors and primitive types pass through unchanged."
  [x]
  (cond
    (nil? x)     ""
    (string? x)  x
    (number? x)  (str x)
    (boolean? x) (str x)
    (instance? js/Error x)
    (str x (when-let [s (.-stack x)] (str "\n" s)))
    :else        (try (pr-str x) (catch :default _ (str x)))))

(defn- console!
  "Emit one structured line. Internal — public helpers below.

   Format: `2026-05-19T18:23:45.123Z  INFO  [source] message [edn ...]`
   Extras get pr-str'd so CLJS maps/seqs print as readable edn instead
   of `{cljs$lang$protocol_mask… 16647951 …}`."
  [level source msg & extra]
  (let [ts   (.toISOString (js/Date.))
        lvl  (str/upper-case (name level))
        pad  (str lvl (apply str (repeat (- 5 (count lvl)) " ")))
        body (if (seq extra)
               (str msg " " (str/join " " (map ->safe-str extra)))
               msg)
        line (str ts "  " pad " [" source "] " body)
        sink (case level
               :error js/console.error
               :warn  js/console.warn
               js/console.log)]
    (sink line)))

(defn error-console!
  "stderr log line — use for boot errors, request failures, etc.
   For agent-visible errors (with `recent-errors` integration) use
   `error!` instead (DB-backed)."
  [source msg & extra]
  (apply console! :error source msg extra))

(defn warn-console!  [source msg & extra] (apply console! :warn  source msg extra))
(defn info-console!  [source msg & extra] (apply console! :info  source msg extra))
(defn debug-console! [source msg & extra] (apply console! :debug source msg extra))

;; ============================================================
;; Schemas. Registered here so any namespace requiring seon.log
;; gets them automatically.
;; ============================================================

;; :inst validates js/Date in CLJS (inst? returns true). The bridge
;; maps :inst → :db.type/instant; datahike accepts js/Date for that.
;; Confirmed working pattern — :seon.message/at, :seon.eval/at, and
;; :seon.turn/at all use :inst.
(schema/register! :seon.log/at            :inst)
(schema/register! :seon.log/level         [:enum :error :warn :info :debug])
(schema/register! :seon.log/source        :keyword)
(schema/register! :seon.log/agent         :string)
(schema/register! :seon.log/message       :string)
(schema/register! :seon.log/stack         :string)
;; :seon.log/data is genuinely polymorphic (arbitrary log payloads).
;; Not in agent-bootstrap-schema — log code stringifies before transact
;; via :seon.log/message + :seon.log/stack. Kept registered as :any
;; for in-memory validation only.
(schema/register! :seon.log/data          :any)
(schema/register! :seon.log/dismissed-at  :inst)

;; ============================================================
;; Internal — transact one entry. Promise-returning; soft-fails to
;; console.error if the DB write itself blows up.
;; ============================================================

(defn- log!
  "Transact one `:seon.log/entry`. Returns the transact Promise. On
   any failure (validation, datahike commit, missing *conn*) logs to
   stderr instead of bubbling the error — broken logging must never
   take down the caller."
  [level data]
  (let [entry (-> data
                  (assoc :seon.log/at    (js/Date.)
                         :seon.log/level level))]
    (try
      (let [p (db/transact! {:seon.db/tx-data [entry]})]
        (.catch p
                (fn [err]
                  (js/console.error "[seon.log]" (name level)
                                    "DB transact failed:" (pr-str entry)
                                    "—" err))))
      (catch :default e
        (js/console.error "[seon.log]" (name level)
                          "threw synchronously:" (pr-str entry) "—" e)
        nil))))

;; ============================================================
;; Public API — one entry per level. Map-in (the entry data), Promise-out.
;; ============================================================

(defn error!
  "Transact a `:seon.log/level :error` entity. Required keys:
   `:seon.log/source` (keyword) and `:seon.log/message` (string).
   Optional: `:seon.log/agent`, `:seon.log/stack`, `:seon.log/data`."
  {:malli/schema [:=> [:cat [:map
                             [:seon.log/source :keyword]
                             [:seon.log/message :string]
                             [:seon.log/agent {:optional true} :string]
                             [:seon.log/stack {:optional true} :string]
                             [:seon.log/data {:optional true} :any]]]
                  :any]}
  [data] (log! :error data))

(defn warn!  [data] (log! :warn data))
(defn info!  [data] (log! :info data))
(defn debug! [data] (log! :debug data))
