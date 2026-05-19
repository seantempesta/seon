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
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas. Registered here so any namespace requiring seon.log
;; gets them automatically.
;; ============================================================

(schema/register! :seon.log/at            :any)  ; js/Date — not really :inst in CLJS
(schema/register! :seon.log/level         [:enum :error :warn :info :debug])
(schema/register! :seon.log/source        :keyword)
(schema/register! :seon.log/agent         :string)
(schema/register! :seon.log/message       :string)
(schema/register! :seon.log/stack         :string)
(schema/register! :seon.log/data          :any)
(schema/register! :seon.log/dismissed-at  :any)

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
