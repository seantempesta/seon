(ns jvm-render-design-mockup
  "Measured design probe for the ruled JVM render split.

  Run:
    clojure -J-Xmx512m -M:writer:host bench/jvm_render_design_mockup.clj 32

  This is deliberately not production code. It compares process-local
  registration retention with a durable-result analogue, drives real Datastar
  SSE connections over http-kit, and uses Seon's real SCI base/interrupt path."
  (:require [clojure.string :as str]
            [org.httpkit.server :as http-kit]
            [sci.core :as sci]
            [seon.sci.ctx :as sci.ctx]
            [seon.sci.eval :as sci.eval]
            [seon.sci.interrupt :as interrupt]
            [seon.ui.html :as html]
            [starfederation.datastar.clojure.adapter.http-kit :as ds-http-kit]
            [starfederation.datastar.clojure.api :as datastar])
  (:import (java.io BufferedReader InputStreamReader PrintWriter)
           (java.lang.management ManagementFactory)
           (java.net Socket)
           (java.util.concurrent ConcurrentHashMap CountDownLatch
                                 Executors TimeUnit)
           (java.util.concurrent.atomic AtomicInteger)
           (java.util.function Function)))

(def safe-source
  "[:main#app-view [:h1 \"one authored evaluation\"] [:p \"ordinary hiccup\"]]")

(def spin-source
  "(loop [] (recur))")

(defn- evaluate-authored [evaluation-count source]
  (swap! evaluation-count inc)
  (sci.eval/evaluate
   {::sci.eval/source source
    ::interrupt/time-limit-ms 50}))

(defn- evaluation-html [evaluation]
  (let [value (::sci.eval/value evaluation)
        record (::sci.eval/record evaluation)]
    (if (sci.eval/error? value)
      (html/->string
       [:main#app-view
        [:h1 "Agent-authored render failed"]
        [:p (:seon.error/message value)]
        [:pre (pr-str record)]])
      (html/->string value))))

(defn- entry-for
  [^ConcurrentHashMap entries policy canvas evaluation-count]
  (.computeIfAbsent
   entries
   [policy canvas]
   (reify Function
     (apply [_ _]
       (let [source (if (= canvas :spin) spin-source safe-source)]
         {:evaluation (evaluate-authored evaluation-count source)
          :consumers (AtomicInteger.)})))))

(defn- handler
  [^ConcurrentHashMap entries evaluation-count]
  (fn [request]
    (let [uri (:uri request)]
      (cond
        (= uri "/health")
        {:status 200 :headers {"content-type" "text/plain"} :body "alive"}

        (str/starts-with? uri "/canvas/")
        (let [[_ _ policy-name canvas-name] (str/split uri #"/")
              policy (keyword policy-name)
              canvas (keyword canvas-name)
              entry (entry-for entries policy canvas evaluation-count)
              consumers ^AtomicInteger (:consumers entry)]
          (.incrementAndGet consumers)
          (ds-http-kit/->sse-response
           request
           {ds-http-kit/on-open
            (fn [sse]
              (datastar/patch-elements!
               sse (evaluation-html (:evaluation entry))))
            ds-http-kit/on-close
            (fn [_ _]
              (when (and (= policy :returned)
                         (zero? (.decrementAndGet consumers)))
                (.remove entries [policy canvas] entry)))}))

        :else
        {:status 404 :headers {"content-type" "text/plain"} :body "missing"}))))

(defn- read-one-sse!
  [port path ^CountDownLatch received]
  (with-open [socket (Socket. "127.0.0.1" (int port))
              out (PrintWriter. (.getOutputStream socket) true)
              in (BufferedReader.
                  (InputStreamReader. (.getInputStream socket)))]
    (.println out (str "GET " path " HTTP/1.1\r"))
    (.println out (str "Host: 127.0.0.1:" port "\r"))
    (.println out "Accept: text/event-stream\r")
    (.println out "Connection: close\r")
    (.println out "\r")
    (loop [event? false]
      (when-let [line (.readLine in)]
        (let [event? (or event?
                         (str/includes? line "datastar-patch-elements"))]
          (if (and event? (str/blank? line))
            (do
              (.countDown received)
              (.await received 10 TimeUnit/SECONDS))
            (recur event?)))))))

(defn- simultaneous-round!
  [port path n]
  (let [received (CountDownLatch. n)
        executor (Executors/newVirtualThreadPerTaskExecutor)
        started (System/nanoTime)]
    (try
      (let [tasks
            (mapv
             (fn [_]
               (.submit executor
                        ^Runnable #(read-one-sse! port path received)))
             (range n))]
        (doseq [task tasks] (.get task 15 TimeUnit/SECONDS))
        {:consumers n
         :completed-consumers (- n (.getCount received))
         :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)})
      (finally
        (.shutdownNow executor)))))

(defn- health-status [port]
  (with-open [socket (Socket. "127.0.0.1" (int port))
              out (PrintWriter. (.getOutputStream socket) true)
              in (BufferedReader.
                  (InputStreamReader. (.getInputStream socket)))]
    (.println out "GET /health HTTP/1.1\r")
    (.println out (str "Host: 127.0.0.1:" port "\r"))
    (.println out "Connection: close\r")
    (.println out "\r")
    (.readLine in)))

(defn- used-heap []
  (let [usage (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    (.getUsed usage)))

(defn- collect! []
  (System/gc)
  (Thread/sleep 150))

(defn- fork-sample [n]
  (collect!)
  (let [before-heap (used-heap)
        started (System/nanoTime)
        forks
        (persistent!
         (loop [i 0 result (transient [])]
           (if (= i n)
             result
             (recur
              (inc i)
              (conj! result
                     (sci.ctx/fork {:interrupt-fn (fn [])}))))))
        elapsed (- (System/nanoTime) started)]
    (collect!)
    ;; Keeping `forks` live across the second heap sample is the point.
    (let [heap-delta (- (used-heap) before-heap)
          result {:forks (count forks)
                  :elapsed-ms (/ elapsed 1000000.0)
                  :ns-per-fork (/ (double elapsed) n)
                  :retained-heap-bytes heap-delta
                  :retained-bytes-per-fork (/ (double heap-delta) n)}]
      (when (zero? (count forks))
        (throw (ex-info "fork retention probe was optimized away" {})))
      result)))

(defn- lazy-escape-probe []
  (let [calls (atom 0)
        base-ctx
        (sci/init
         {:namespaces
          {'probe {'mark (fn [x] (swap! calls inc) x)}}})
        evaluation
        (sci.eval/evaluate
         {::sci.eval/source "(map probe/mark [1])"
          ::sci.eval/base-ctx base-ctx
          ::interrupt/time-limit-ms 50})
        calls-before @calls
        realized (doall (::sci.eval/value evaluation))]
    {:returned-class (.getName (class (::sci.eval/value evaluation)))
     :calls-before-outside-realization calls-before
     :calls-after-outside-realization @calls
     :outside-realized-value realized
     :evaluation-record (::sci.eval/record evaluation)}))

(defn- run-probe [n]
  (sci.eval/open! {::sci.eval/concurrency 4})
  ;; Warm the real process-shared base before measuring forks.
  @sci.ctx/base
  (dotimes [_ 1000] (sci.ctx/fork {:interrupt-fn (fn [])}))
  (let [entries (ConcurrentHashMap.)
        evaluation-count (atom 0)
        stop-server
        (http-kit/run-server
         (handler entries evaluation-count)
         {:ip "127.0.0.1" :port 0})
        port (:local-port (meta stop-server))]
    (try
      (let [returned-1-before @evaluation-count
            returned-1 (simultaneous-round! port "/canvas/returned/safe" n)
            returned-1-evals (- @evaluation-count returned-1-before)
            returned-2-before @evaluation-count
            returned-2 (simultaneous-round! port "/canvas/returned/safe" n)
            returned-2-evals (- @evaluation-count returned-2-before)
            retained-1-before @evaluation-count
            retained-1 (simultaneous-round! port "/canvas/retained/safe" n)
            retained-1-evals (- @evaluation-count retained-1-before)
            retained-2-before @evaluation-count
            retained-2 (simultaneous-round! port "/canvas/retained/safe" n)
            retained-2-evals (- @evaluation-count retained-2-before)
            spin-before @evaluation-count
            spin-round (simultaneous-round! port "/canvas/retained/spin" n)
            spin-evals (- @evaluation-count spin-before)
            spin-evaluation (:evaluation (.get entries [:retained :spin]))]
        {:prediction
         {:overlapping-consumers-evaluations 1
          :returned-reconnect-evaluations 1
          :retained-reconnect-evaluations 0
          :spin-evaluations 1
          :server-after-spin "HTTP/1.1 200 OK"}
         :sse
         {:returned-first (assoc returned-1
                                 :agent-evaluations returned-1-evals)
          :returned-reconnect (assoc returned-2
                                     :agent-evaluations returned-2-evals)
          :retained-first (assoc retained-1
                                 :agent-evaluations retained-1-evals)
          :retained-reconnect (assoc retained-2
                                     :agent-evaluations retained-2-evals)
          :spin (assoc spin-round
                       :agent-evaluations spin-evals
                       :error-value (::sci.eval/value spin-evaluation)
                       :diagnostic (::sci.eval/record spin-evaluation))
          :health-after-spin (health-status port)}
         :lazy-escape (lazy-escape-probe)
         :real-seon-base-forks (fork-sample 50000)})
      (finally
        (stop-server :timeout 1000)))))

(let [n (if-let [arg (first *command-line-args*)]
          (Long/parseLong arg)
          32)]
  (prn (run-probe n)))
