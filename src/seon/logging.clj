(ns seon.logging
  "Centralized Timbre logging configuration for Seon.

  Call `(configure! {})` early in startup to set up file appenders.
  Timbre handles all application logging (seon.* namespaces).
  Logback handles library logging (Datalevin, nREPL, etc.) via SLF4J.

  Log files:
    logs/app.log      - Current session, all levels
    logs/startup.log  - Wiped each startup, captures boot sequence
    logs/error.log    - Errors only (via logback, library errors)"
  (:require
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.core :as appenders]
   [seon.schema :as schema])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::log-dir
                  [:string {:default "logs"
                            :description "Directory for log files"}])

(schema/register! ::status
                  [:enum :ok :error])

(schema/register! ::configure-request
                  [:map {:closed true}
                   [::log-dir {:optional true} ::log-dir]])

(schema/register! ::configure-response
                  [:map
                   [::status ::status]
                   [::log-dir ::log-dir]])

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(defn configure!
  "Configure Timbre with file appenders. Call once at startup.

  Sets up:
  - :println  - stdout (already default)
  - :app-file - <log-dir>/app.log (all levels)
  - :startup  - <log-dir>/startup.log (wiped on each call)

  Usage:
    (configure! {})
    (configure! {::log-dir \"logs\"})"
  {:malli/schema [:=> [:cat ::configure-request] ::configure-response]}
  [{::keys [log-dir] :or {log-dir "logs"}}]
  (.mkdirs (File. ^String log-dir))

  ;; Wipe startup.log on each startup
  (let [startup-path (str log-dir "/startup.log")
        app-path     (str log-dir "/app.log")]
    (spit startup-path "")

    (timbre/merge-config!
     {:min-level :info

      :appenders
      {:println  {:enabled? true}

       :app-file (appenders/spit-appender
                  {:fname app-path})

       :startup  (appenders/spit-appender
                  {:fname startup-path})}})

    {::status  :ok
     ::log-dir log-dir}))
