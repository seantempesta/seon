(ns seon.dev.state
  "Atomic filesystem state and lifecycle locks for the Seon operator."
  (:require [babashka.fs :as fs]
            [clojure.string :as string]
            [seon.operator.state :as operator.state]))

(def process-start-instant operator.state/process-start-instant)

(def process-identity-alive? operator.state/process-identity-alive?)

(def read-edn operator.state/read-edn)
(def write-edn! operator.state/write-edn!)
(def delete-edn! operator.state/delete-edn!)

(defn with-lock
  "Run a lifecycle transition under one kernel-owned file lock.

  The locking itself — bounded waiting, the loud announcement, and the holder
  record naming the process that holds it — is
  `seon.operator.state/with-lifecycle-lock!`. This function only derives the
  named lock path below the caller's own process directory, so two callers
  with different process directories never contend."
  [config lock-name timeout-ms transition]
  (let [process-dir (:seon.dev.config/process-dir config)]
    (when-not (and (string? process-dir)
                   (not (string/blank? process-dir))
                   (fs/absolute? process-dir))
      (throw (ex-info "with-lock requires an absolute :seon.dev.config/process-dir"
                      {:seon.dev.lock/name lock-name
                       :seon.dev.config/process-dir process-dir})))
    (operator.state/with-lifecycle-lock!
     {:seon.operator.lock/path
      (fs/path process-dir "locks" (str (name lock-name) ".lock"))
      :seon.operator.lock/command (str (name lock-name) " transition")
      :seon.operator.lock/wait-timeout-ms timeout-ms
      :seon.operator.lock/hold-timeout-ms timeout-ms}
     transition)))
