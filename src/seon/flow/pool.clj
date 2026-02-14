(ns seon.flow.pool
  "Pre-warmed JVM pool for instant agent startup.

   Maintains a pool of idle agent JVMs with Clojure + deps loaded.
   When a task arrives, assigns a warm JVM by sending code via nREPL.

   Usage:
     (def pool (create-pool! {::size 3 ::base-port 7900}))

     ;; Get a warm JVM and load namespace code into it
     (def agent (acquire! pool {::namespace 'seon.trading.signals
                                ::forms ['(defn ema [period data] ...)]}))
     ;; => {::port 7901 ::pid 12345 ::namespace 'seon.trading.signals}

     ;; Return JVM to pool (resets namespace) or dispose
     (release! pool agent)
     (dispose! pool agent)

     ;; Shut down entire pool
     (shutdown! pool)"
  (:require [clojure.java.process :as process]
            [nrepl.core :as nrepl]
            [taoensso.timbre :as log])
  (:import [java.io BufferedReader InputStreamReader]))

;;; ---------------------------------------------------------------------------
;;; Constants
;;; ---------------------------------------------------------------------------

(def ^:const default-pool-size 3)
(def ^:const default-base-port 7900)
(def ^:const ready-timeout-ms 15000)
(def ^:const nrepl-eval-timeout-ms 10000)

;;; ---------------------------------------------------------------------------
;;; Process spawning
;;; ---------------------------------------------------------------------------

(defn- kill-process!
  "Destroy an agent JVM process."
  [^Process proc]
  (.destroy proc))

(defn- spawn-agent-jvm!
  "Spawn an agent JVM on the given port. Blocks until AGENT_READY signal
   or timeout. Returns {::port ::pid ::process ...} or throws."
  [port]
  (let [^Process proc (process/start {:dir "."} "clojure" "-M:agent"
                                     "--port" (str port)
                                     "--namespace" "seon.pool.warm")
        stdout (BufferedReader. (InputStreamReader. (process/stdout proc)))
        ready? (promise)
        reader-thread (future
                        (try
                          (loop []
                            (when-let [line (.readLine stdout)]
                              (log/debug "Agent JVM stdout" {:port port :line line})
                              (when (.contains ^String line "AGENT_READY")
                                (deliver ready? true))
                              (recur)))
                          (catch Exception e
                            (log/error "Agent JVM reader error"
                                       {:port port :error (.getMessage e)}))))]
    (if (deref ready? ready-timeout-ms nil)
      (let [pid (.pid proc)]
        (log/info "Agent JVM ready" {:port port :pid pid})
        {::port port
         ::pid pid
         ::process proc
         ::reader reader-thread
         ::status :idle})
      (do
        (.destroy proc)
        (throw (ex-info "Agent JVM failed to start within timeout"
                        {:port port :timeout-ms ready-timeout-ms}))))))

;;; ---------------------------------------------------------------------------
;;; nREPL communication
;;; ---------------------------------------------------------------------------

(defn- nrepl-eval!
  "Evaluate code on an agent JVM via nREPL. Returns the result value string
   or throws on error."
  [port code]
  (with-open [conn (nrepl/connect :port port)]
    (let [client (nrepl/client conn nrepl-eval-timeout-ms)
          responses (nrepl/message client {:op "eval" :code code})
          errors (keep :err responses)
          ex (first (keep :ex responses))
          values (keep :value responses)]
      (when (or ex (seq errors))
        (throw (ex-info "nREPL eval error"
                        {:port port
                         :code code
                         :ex ex
                         :errors (vec errors)})))
      (last values))))

(defn- setup-namespace!
  "Create a namespace on the agent JVM and eval forms into it."
  [port ns-sym forms]
  (let [ns-str (str ns-sym)
        setup-code (str "(do"
                        " (create-ns '" ns-str ")"
                        " (in-ns '" ns-str ")"
                        " (clojure.core/refer-clojure)"
                        " (require '[malli.core :as m])"
                        " (require '[clojure.core.async :as async])"
                        " (require '[cheshire.core :as json])"
                        " :ok)")]
    (nrepl-eval! port setup-code)
    (doseq [form forms]
      (nrepl-eval! port (pr-str form)))
    ns-str))

(defn- reset-namespace!
  "Remove the assigned namespace from an agent JVM, returning it to idle."
  [port ns-sym]
  (let [code (str "(do"
                  " (remove-ns '" (str ns-sym) ")"
                  " (in-ns 'seon.pool.warm)"
                  " :reset)")]
    (nrepl-eval! port code)))

;;; ---------------------------------------------------------------------------
;;; Pool management
;;; ---------------------------------------------------------------------------

(defn create-pool!
  "Create a pre-warmed JVM pool. Spawns `size` agent JVMs on consecutive
   ports starting from `base-port`.

   Options:
     ::size      - Number of warm JVMs (default 3)
     ::base-port - Starting port number (default 7900)

   Returns pool atom containing pool state."
  [{::keys [size base-port]
    :or {size default-pool-size
         base-port default-base-port}}]
  (log/info "Creating agent pool" {:size size :base-port base-port})
  (let [jvms (vec (for [i (range size)
                        :let [port (+ base-port i)]]
                    (spawn-agent-jvm! port)))
        pool (atom {::jvms jvms
                    ::base-port base-port
                    ::size size
                    ::next-port (+ base-port size)})]
    (log/info "Pool ready" {:idle (count jvms)
                            :ports (mapv ::port jvms)})
    pool))

(declare dispose!)

(defn acquire!
  "Acquire a warm JVM from the pool and load namespace code into it.

   Options:
     ::namespace - Namespace symbol to create
     ::forms     - Vector of forms to eval in the namespace (optional)

   Returns agent handle map or nil if pool exhausted."
  [pool {::keys [namespace forms]}]
  (let [jvm (first (filter #(= :idle (::status %))
                           (::jvms @pool)))]
    (if-not jvm
      (do (log/warn "Pool exhausted, no idle JVMs available")
          nil)
      (let [port (::port jvm)
            start (System/nanoTime)
            _ (setup-namespace! port namespace (or forms []))
            elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (log/info "Acquired agent" {:port port
                                     :namespace namespace
                                     :setup-ms (long elapsed-ms)})
        (swap! pool update ::jvms
               (fn [jvms]
                 (mapv (fn [j]
                         (if (= (::port j) port)
                           (assoc j ::status :active ::namespace namespace)
                           j))
                       jvms)))
        (assoc jvm ::status :active ::namespace namespace ::setup-ms (long elapsed-ms))))))

(defn release!
  "Return an agent JVM to the pool. Resets the namespace so it can be reused."
  [pool {::keys [port namespace]}]
  (try
    (reset-namespace! port namespace)
    (swap! pool update ::jvms
           (fn [jvms]
             (mapv (fn [j]
                     (if (= (::port j) port)
                       (assoc j ::status :idle ::namespace nil)
                       j))
                   jvms)))
    (log/info "Released agent back to pool" {:port port})
    (catch Exception e
      (log/warn "Failed to reset JVM, disposing" {:port port :error (.getMessage e)})
      (dispose! pool {::port port}))))

(defn dispose!
  "Kill an agent JVM and spawn a replacement."
  [pool {::keys [port]}]
  (let [state @pool
        jvm (first (filter #(= port (::port %)) (::jvms state)))]
    (when jvm
      (try
        (kill-process! (::process jvm))
        (catch Exception _))
      (try
        (let [new-jvm (spawn-agent-jvm! port)]
          (swap! pool update ::jvms
                 (fn [jvms]
                   (mapv (fn [j]
                           (if (= (::port j) port)
                             new-jvm
                             j))
                         jvms)))
          (log/info "Replaced agent JVM" {:port port}))
        (catch Exception e
          (log/error "Failed to respawn agent JVM"
                     {:port port :error (.getMessage e)}))))))

(defn pool-status
  "Return current pool status."
  [pool]
  (let [state @pool
        jvms (::jvms state)]
    {::total (count jvms)
     ::idle (count (filter #(= :idle (::status %)) jvms))
     ::active (count (filter #(= :active (::status %)) jvms))
     ::jvms (mapv #(select-keys % [::port ::pid ::status ::namespace]) jvms)}))

(defn shutdown!
  "Shut down the entire pool, killing all JVMs."
  [pool]
  (log/info "Shutting down pool")
  (doseq [jvm (::jvms @pool)]
    (try
      (kill-process! (::process jvm))
      (catch Exception _)))
  (reset! pool {::jvms [] ::size 0})
  (log/info "Pool shut down"))

;;; ---------------------------------------------------------------------------
;;; REPL exploration
;;; ---------------------------------------------------------------------------

(comment
  ;; Create a pool of 2 warm JVMs
  (def pool (create-pool! {::size 2 ::base-port 7900}))

  ;; Check status
  (pool-status pool)

  ;; Acquire and load code
  (def agent1 (acquire! pool {::namespace 'seon.trading.signals
                              ::forms ['(defn ema [period data]
                                          (let [k (/ 2.0 (inc period))]
                                            (reduce (fn [prev x]
                                                      (+ (* k x) (* (- 1 k) prev)))
                                                    (first data)
                                                    (rest data))))
                                       '(def schema
                                          [:map
                                           [:period :int]
                                           [:data [:vector :double]]])]}))

  ;; Test the loaded code
  (nrepl-eval! (::port agent1) "(ema 3 [1.0 2.0 3.0 4.0 5.0])")

  ;; Release back to pool
  (release! pool agent1)

  ;; Shut down
  (shutdown! pool)
  )
