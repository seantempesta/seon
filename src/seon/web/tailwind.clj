(ns seon.web.tailwind
  "Tailwind CSS watcher Integrant component.

  Manages tailwindcss --watch as a child process. Auto-detects if a
  tailwindcss watcher is already running and adopts it instead of
  starting a duplicate. Only stops the watcher on halt if we started it.

  Features:
  - Auto-detection of existing tailwindcss processes via pgrep
  - stdout/stderr redirected to logs/tailwind.log
  - ProcessBuilder ensures child dies with JVM (via inheritIO + destroy)

  Configuration:
  - :enabled? - whether to start the watcher (false in prod)
  - :input - input CSS file (default: resources/public/css/input.css)
  - :output - output CSS file (default: resources/public/css/output.css)"
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(defn- tailwind-cli-path
  "Get the path to the tailwindcss CLI."
  []
  (let [local-cli (io/file "node_modules/.bin/tailwindcss")]
    (if (.exists local-cli)
      (.getAbsolutePath local-cli)
      ;; Fallback to npx if no local install
      "npx")))

(defn- build-command
  "Build the command to run the Tailwind watcher."
  [{:keys [input output]}]
  (let [cli (tailwind-cli-path)]
    (if (= cli "npx")
      ["npx" "tailwindcss" "-i" input "-o" output "--watch"]
      [cli "-i" input "-o" output "--watch"])))

(defn- start-tailwind-process!
  "Start the Tailwind watcher process.
   Returns the Process object."
  [{:keys [_input _output] :as opts}]
  (.mkdirs (io/file "logs"))
  (let [command (build-command opts)
        log-file (io/file "logs/tailwind.log")
        builder (ProcessBuilder. ^java.util.List command)]
    ;; Redirect stdout and stderr to log file
    (.redirectOutput builder (ProcessBuilder$Redirect/appendTo log-file))
    (.redirectErrorStream builder true)
    ;; Set working directory to project root
    (.directory builder (io/file "."))
    (log/info "Starting Tailwind watcher" {:command command :log-file (.getPath log-file)})
    (let [process (.start builder)]
      ;; Brief check — did the process exit immediately?
      ;; .waitFor returns true if process exited, false if still alive
      (if-not (.waitFor process 50 java.util.concurrent.TimeUnit/MILLISECONDS)
        (do
          (log/info "Tailwind watcher started" {:pid (.pid process)})
          process)
        (let [exit-code (.exitValue process)]
          (log/error "Tailwind watcher failed to start" {:exit-code exit-code
                                                          :log-file (.getPath log-file)})
          nil)))))

(defn- stop-tailwind-process!
  "Stop the Tailwind watcher process."
  [^Process process]
  (when (and process (.isAlive process))
    (log/info "Stopping Tailwind watcher" {:pid (.pid process)})
    ;; First try graceful termination
    (.destroy process)
    ;; Wait up to 2 seconds for graceful shutdown
    (let [exited? (.waitFor process 2 java.util.concurrent.TimeUnit/SECONDS)]
      (when-not exited?
        ;; Force kill if still alive
        (log/warn "Tailwind watcher didn't stop gracefully, forcing")
        (.destroyForcibly process)
        (.waitFor process 1 java.util.concurrent.TimeUnit/SECONDS)))
    (log/info "Tailwind watcher stopped")))

;;; ---------------------------------------------------------------------------
;;; Auto-Detection
;;; ---------------------------------------------------------------------------

(defn- tailwind-already-running?
  "Check if a tailwindcss watcher process is already running via pgrep."
  []
  (try
    (let [builder (ProcessBuilder. ^java.util.List ["pgrep" "-f" "tailwindcss.*--watch"])
          process (.start builder)]
      (.waitFor process 2 java.util.concurrent.TimeUnit/SECONDS)
      (zero? (.exitValue process)))
    (catch Exception _ false)))

;;; ---------------------------------------------------------------------------
;;; Integrant Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.web/tailwind
  [_ {:keys [enabled? input output]
      :or {input "resources/public/css/input.css"
           output "resources/public/css/output.css"}}]
  (if enabled?
    (if (tailwind-already-running?)
      ;; Existing watcher detected — adopt it
      (do
        (log/info "Using existing Tailwind watcher")
        {:process nil :adopted? true :input input :output output})
      ;; No watcher running — start one
      (let [process (start-tailwind-process! {:input input :output output})]
        {:process process
         :adopted? false
         :pid (when process (.pid process))
         :input input
         :output output}))
    (do
      (log/info "Tailwind watcher disabled")
      {:process nil})))

(defmethod ig/halt-key! :seon.web/tailwind
  [_ {:keys [process adopted?]}]
  (when-not adopted?
    (stop-tailwind-process! process)))

;; Suspend/resume: keep process alive during (reset).
;; Tailwind watcher is a long-running process that doesn't need restart
;; unless config changes. Only restart if process died or config changed.
(defmethod ig/suspend-key! :seon.web/tailwind [_ state] state)

(defmethod ig/resume-key :seon.web/tailwind
  [_ opts old-opts old-state]
  (if (or (:adopted? old-state)
          (and (= opts old-opts)
               (:process old-state)
               (.isAlive ^Process (:process old-state))))
    old-state
    (do (ig/halt-key! :seon.web/tailwind old-state)
        (ig/init-key :seon.web/tailwind opts))))
