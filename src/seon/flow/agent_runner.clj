(ns seon.flow.agent-runner
  "Minimal agent JVM entry point for isolated process execution.

  Starts a lightweight nREPL server and optionally connects to a Datalevin
  server. Designed to run in a separate JVM with moderate memory footprint
  (512MB max heap).

  Malli instrumentation is NOT started here -- it is deferred to claim time
  when the pool assigns the JVM to a session (see pool.clj:claim!).

  Usage:
    clj -M:agent -m seon.flow.agent-runner --port 7890 --namespace seon.test.hello"
  (:require [nrepl.server :as nrepl]
            [taoensso.timbre :as log]
            [clojure.core.async :as async]
            [malli.core :as m])
  (:gen-class))

(def ^:dynamic *ctx*
  "Agent context atom. Set during startup with agent metadata."
  nil)

(defn- parse-args
  "Parse CLI args into a map. Supports --port, --datalevin-uri, --namespace."
  [args]
  (loop [args (seq args)
         result {:port 7890
                 :namespace 'seon.test.hello}]
    (if-not args
      result
      (let [[flag val & rest] args]
        (case flag
          "--port" (recur rest (assoc result :port (parse-long val)))
          "--datalevin-uri" (recur rest (assoc result :datalevin-uri val))
          "--namespace" (recur rest (assoc result :namespace (symbol val)))
          (do (log/warn "Unknown arg" {:arg flag})
              (recur (next args) result)))))))

(defn- try-connect-datalevin
  "Attempt to connect to a Datalevin server. Returns connection or nil."
  [uri]
  (when uri
    (try
      (let [d (requiring-resolve 'datalevin.core/get-conn)]
        (log/info "Connecting to Datalevin" {:uri uri})
        (d uri))
      (catch Exception e
        (log/warn "Datalevin connection failed (continuing without DB)"
                  {:uri uri :error (.getMessage e)})
        nil))))

(defn- ensure-namespace
  "Ensure target namespace exists, require if on classpath."
  [ns-sym]
  (try
    (require ns-sym)
    (catch java.io.FileNotFoundException _
      (create-ns ns-sym))
    (catch Exception e
      (log/warn "Could not require namespace" {:ns ns-sym :error (.getMessage e)})
      (create-ns ns-sym)))
  (find-ns ns-sym))

(defn -main [& args]
  (let [start-time (System/nanoTime)
        opts (parse-args args)
        {:keys [port namespace datalevin-uri]} opts
        _ (log/info "Starting agent JVM" opts)

        ;; Connect to Datalevin (optional)
        db-conn (try-connect-datalevin datalevin-uri)

        ;; Ensure target namespace
        target-ns (ensure-namespace namespace)

        ;; Create context
        ctx-atom (atom {:seon.agent/namespace namespace
                        :seon.agent/db db-conn
                        :seon.agent/nrepl-port port
                        :seon.agent/started-at (java.util.Date.)
                        :seon.agent/jvm-isolated? true})

        ;; Intern *ctx* in target namespace
        _ (let [v (intern target-ns '*ctx* ctx-atom)]
            (.setDynamic v true))

        ;; Start nREPL (plain, no CIDER middleware)
        server (nrepl/start-server :port port)
        startup-ms (/ (- (System/nanoTime) start-time) 1e6)]

    (log/info "Agent JVM ready"
              {:port (:port server)
               :namespace namespace
               :startup-ms (long startup-ms)
               :datalevin (if db-conn :connected :unavailable)})

    ;; Ready signal for parent process to detect
    (println (str "AGENT_READY port=" (:port server)
                  " ns=" namespace
                  " startup_ms=" (long startup-ms)))
    (flush)

    ;; Prove core.async and malli loaded (no instrumentation yet -- deferred to claim time)
    (log/debug "core.async version:" (async/<!! (async/go :ok)))
    (log/debug "malli loaded:" (m/validate :string "hello"))

    ;; Block on server
    @(promise)))
