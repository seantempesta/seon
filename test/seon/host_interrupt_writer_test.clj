(ns seon.host-interrupt-writer-test
  "Interrupt-aware SCI core behavior through the real JVM agent host."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [sci.core :as sci]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host-registry-writer-test :as registry-test])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]))

(def ^:private artifact-digest (apply str (repeat 64 "c")))
(def ^:private deadline-duration-ms 100)

(def ^:private ^:dynamic *host-socket* nil)
(def ^:private ^:dynamic *writer-session* nil)

(defn- registry-value [sym]
  (var-get (ns-resolve 'seon.host-registry-writer-test sym)))

(defn- socket-path [label]
  ((registry-value 'socket-path) label))

(defn- open-host-session! [agent-id database-name]
  (let [^SocketChannel channel (uds/connect! *host-socket*)
        output (Channels/newOutputStream channel)
        input (Channels/newInputStream channel)]
    (uds/write-frame!
     output
     {:seon.execution/protocol-version 3
      :seon.execution/agent-id agent-id
      :seon.execution/artifact-digest artifact-digest
      :seon.execution/shadow-build-id "host-interrupt-writer-test"
      :seon.execution/database-selection
      {:seon.db/socket-path "unused-by-the-host"
       :seon.db/database-name database-name}})
    {::channel channel
     ::output output
     ::input input
     ::ready (uds/read-frame input)}))

(defn- close-host-session! [session]
  (try (.close ^SocketChannel (::channel session)) (catch Throwable _)))

(defn- invoke! [session agent-id source duration-ms]
  (let [invocation-id (str "interrupt-test-" (random-uuid))
        started-ns (System/nanoTime)]
    (uds/write-frame!
     (::output session)
     {:seon.execution/message :seon.execution.message/invoke
      :seon.execution/protocol-version 3
      :seon.execution/agent-id agent-id
      :seon.execution/invocation-id invocation-id
      :seon.db/db (context/resolve-head! *writer-session*)
      :seon.execution/function-identity
      {:seon.execution/function-symbol 'seon.execution.runtime/eval-batch!
       :seon.execution/artifact-digest artifact-digest}
      :seon.execution/arguments
      [{:seon.eval/parsed [{:seon.repl/kind :form
                            :seon.repl/source source}]
        :seon.eval/starting-ns (symbol (str "my.agent." agent-id))}]
      :seon.execution/deadline-ms (+ (System/currentTimeMillis) duration-ms)
      :seon.execution/result-limit-bytes 1000000})
    {:response (uds/read-frame (::input session))
     :elapsed-ms (/ (- (System/nanoTime) started-ns) 1e6)}))

(defn- eval-value [response]
  (get-in response [:seon.execution/result
                    :seon.host/results 0
                    :seon.eval/value]))

(defn- assert-normal-eval! [session agent-id]
  (let [{:keys [response]} (invoke! session agent-id "(+ 20 22)" 2000)]
    (is (= :seon.execution.message/result
           (:seon.execution/message response))
        (pr-str response))
    (is (= 42 (eval-value response)) (pr-str response))))

(defn- assert-runaway-recovers! [session agent-id label source]
  (let [{:keys [response elapsed-ms]}
        (invoke! session agent-id source deadline-duration-ms)
        error (:seon.execution/error response)]
    (println (str "W0.1 " label " runaway wall-clock: "
                  (format "%.3f" elapsed-ms) " ms"))
    (is (= :seon.execution.message/error
           (:seon.execution/message response))
        (pr-str response))
    (is (map? error) (pr-str response))
    (is (= :agent (:seon.error/kind error)) (pr-str response))
    (is (= :interrupt
           (get-in error [:seon.error/data :seon.error.sci/class]))
        (pr-str response))
    (is (= :timeout
           (get-in error [:seon.error/data :seon.error/kind]))
        (pr-str response))
    (is (<= elapsed-ms (+ deadline-duration-ms 200))
        (str label " exceeded deadline + 200ms: " elapsed-ms " ms"))
    ;; The host has one eval worker, so success here proves the interrupted
    ;; worker returned to the pool rather than a sibling masking its loss.
    (assert-normal-eval! session agent-id)
    elapsed-ms))

(defn- eval-error
  [session agent-id source]
  (let [{:keys [response]} (invoke! session agent-id source 2000)]
    (is (= :seon.execution.message/result
           (:seon.execution/message response))
        (pr-str response))
    (get-in response [:seon.execution/result
                      :seon.host/results 0
                      :seon/error])))

(use-fixtures
  :once
  (fn [run-tests!]
    (let [database-name (str "host-interrupt-" (random-uuid))
          request-path (socket-path "interrupt-writer")
          host-socket (socket-path "interrupt-host")
          server (writer/start!
                  {::writer/dependencies ((registry-value 'dependencies))
                   ::writer/database-name database-name
                   ::writer/backend :memory
                   ::writer/request-socket-path request-path})
          writer-session
          (context/writer-session
           {::context/writer-socket-path request-path
            ::context/database-name database-name
            ::context/backend :memory})
          base (context/build-base! writer-session)
          seed-ctx (context/fork-context base)]
      (try
        (let [seeded
              (sci/eval-string*
               seed-ctx
               (str "(require 'seon.db)"
                    "(seon.db/transact! {:seon.db/tx-data "
                    (pr-str (into (registry-value 'corpus-schema-rows)
                                  [(registry-value 'value-sampling-policy)]))
                    "})"))]
          (is (true? (:seon.db/ok? seeded)) (pr-str seeded)))
        (let [started
              (host/start!
               {::host/socket-path host-socket
                ::host/eval-threads 1
                ::context/writer-socket-path request-path
                ::context/database-name database-name
                ::context/backend :memory})]
          (try
            (binding [*host-socket* host-socket
                      *writer-session* writer-session]
              (run-tests!))
            (finally
              (host/stop! started))))
        (finally
          (context/close-session! writer-session)
          (writer/stop! server)
          (.delete (File. ^String request-path))
          (.delete (File. ^String host-socket)))))))

(deftest infinite-reduce-settles-and-the-same-host-recovers
  (let [agent-id "interrupt-reduce"
        session (open-host-session! agent-id
                                    (::context/database-name *writer-session*))]
    (try
      (is (= :seon.execution.message/ready
             (:seon.execution/message (::ready session)))
          (pr-str (::ready session)))
      (assert-runaway-recovers! session agent-id "reduce"
                                "(reduce + (range))")
      (finally
        (close-host-session! session)))))

(deftest other-native-runaways-settle-and-the-same-host-recovers
  (let [agent-id "interrupt-other"
        session (open-host-session! agent-id
                                    (::context/database-name *writer-session*))]
    (try
      (doseq [[label source]
              [["into" "(into [] (range))"]
               ["regex"
                "(re-find #\"(.*a){15}b\" (apply str (repeat 32 \"a\")))"]]]
        (assert-runaway-recovers! session agent-id label source))
      (finally
        (close-host-session! session)))))

(deftest lazy-eval-results-serialize-under-their-sci-context
  (let [agent-id "interrupt-lazy"
        session (open-host-session! agent-id
                                    (::context/database-name *writer-session*))]
    (try
      (let [{:keys [response]}
            (invoke! session agent-id "(map count [[1] [2]])" 2000)]
        (is (= :seon.execution.message/result
               (:seon.execution/message response))
            (pr-str response))
        (is (= '(1 1) (eval-value response)) (pr-str response)))
      (finally
        (close-host-session! session)))))

(deftest hostile-errors-retain-their-structural-class-through-the-host
  (let [agent-id "structural-errors"
        session (open-host-session! agent-id
                                    (::context/database-name *writer-session*))]
    (try
      (is (= :seon.execution.message/ready
             (:seon.execution/message (::ready session)))
          (pr-str (::ready session)))
      (assert-normal-eval! session agent-id)
      (let [defined (invoke! session agent-id "(defn total [row] row)" 2000)]
        (is (= :seon.execution.message/result
               (get-in defined [:response :seon.execution/message]))
            (pr-str defined)))
      (let [resolution (eval-error session agent-id "(totl {:amount 1})")
            arity (eval-error session agent-id "(total)")
            refusal (eval-error
                     session agent-id
                     "(alter-var-root (var clojure.core/+) (fn [_] 1))")
            runtime (eval-error session agent-id "(/ 1 0)")]
        (is (= :resolution
               (get-in resolution [:seon.error/data
                                   :seon.error.sci/class])))
        (is (= 'totl
               (get-in resolution [:seon.error/data
                                   :seon.error.sci/symbol])))
        (is (seq (get-in resolution [:seon.error/data
                                    :seon.repair/suggestions])))
        (is (= :arity
               (get-in arity [:seon.error/data :seon.error.sci/class])))
        (is (= :refusal
               (get-in refusal [:seon.error/data :seon.error.sci/class])))
        (is (= :runtime
               (get-in runtime [:seon.error/data :seon.error.sci/class])))
        (is (vector?
             (get-in runtime [:seon.error/data
                              :seon.error.sci/callstack-head]))))
      (finally
        (close-host-session! session)))))

(deftest interrupt-aware-overrides-preserve-clojure-semantics
  (let [agent-id "interrupt-semantics"
        session (open-host-session! agent-id
                                    (::context/database-name *writer-session*))
        forms ["(reduce + (range 10))"
               "(reduce + 10 [1 2 3])"
               "(reduce (fn [a x] (if (> a 5) (reduced a) (+ a x))) 0 (range 100))"
               "(reduce (fn [a x] (if (> a 5) (reduced a) (+ a x))) (range 100))"
               "(into [] (comp (map inc) (filter odd?)) (range 8))"
               "(re-seq #\"\\w+\" \"one two\")"
               "(clojure.string/replace \"abcabc\" #\"b\" \"X\")"
               "((fn [f] (f + [1 2 3])) reduce)"]]
    (try
      (doseq [source forms]
        (let [expected (eval (read-string source))
              {:keys [response]} (invoke! session agent-id source 2000)]
          (is (= :seon.execution.message/result
                 (:seon.execution/message response))
              (pr-str response))
          (is (= expected (eval-value response)) source)))
      (finally
        (close-host-session! session)))))
