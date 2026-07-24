(ns seon.host-cancel-writer-test
  "Cancellation races through the real one-worker JVM agent host."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [sci.core :as sci]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.invoke :as host.invoke]
            [seon.host-registry-writer-test :as registry-test]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]))

(def ^:private artifact-digest (apply str (repeat 64 "d")))
(def ^:private controls (atom {}))

(def ^:private ^:dynamic *host-socket* nil)
(def ^:private ^:dynamic *writer-session* nil)
(def ^:private ^:dynamic *database-name* nil)

(defn- registry-value [sym]
  (var-get (ns-resolve 'seon.host-registry-writer-test sym)))

(defn- host-private [sym]
  (ns-resolve 'seon.host.invoke sym))

(defn- socket-path [label]
  ((registry-value 'socket-path) label))

(defn- install-control-wrappers! [started]
  (context/register-wrappers!
   {::context/registry (::context/registry (::host/base started))
    ::context/lib 'seon.host.cancel-test
    ::context/wrappers
    {'block
     {::context/wrapper-fn
      (fn [label]
        (let [{::keys [entered release]} (get @controls label)]
          (deliver entered true)
          @release
          :released))
      ::context/arglists '([label])
      ::context/doc "Block until the writer test releases this call."}
     'signal
     {::context/wrapper-fn
      (fn [label]
        (deliver (get-in @controls [label ::entered]) true)
        true)
      ::context/arglists '([label])
      ::context/doc "Signal that the writer test reached this call."}}}))

(defn- allocate-agent-turn!
  ([]
   (let [candidates
         (db.id/candidate-manifest
          {:seon.agent/id :seon.db.id.generator/human-readable
           :seon.agent.turn/id :seon.db.id.generator/compact}
          [{:seon.db.id/key :fixture/agent
            :seon.db.id/identity-attr :seon.agent/id}
           {:seon.db.id/key :fixture/turn
            :seon.db.id/identity-attr :seon.agent.turn/id}])
         agent-id (:seon.db.id/value (first candidates))
         turn-id (:seon.db.id/value (second candidates))
         database (db.host/resolve-db! *writer-session* nil false)
         result
         (db.host/call!
          *writer-session*
          (protocol/transaction-request
           {::protocol/request-id (str (random-uuid))
            :seon.db/db database
            ::protocol/transaction-data
            [{:seon.agent/id agent-id}
             {:seon.agent.turn/id turn-id}]
            ::protocol/generated-candidates candidates}))]
     (is (true? (::protocol/success? result)) (pr-str result))
     [agent-id turn-id]))
  ([agent-id]
   (let [candidates
         (db.id/candidate-manifest
          {:seon.agent.turn/id :seon.db.id.generator/compact}
          [{:seon.db.id/key :fixture/turn
            :seon.db.id/identity-attr :seon.agent.turn/id}])
         turn-id (:seon.db.id/value (first candidates))
         database (db.host/resolve-db! *writer-session* nil false)
         result
         (db.host/call!
          *writer-session*
          (protocol/transaction-request
           {::protocol/request-id (str (random-uuid))
            :seon.db/db database
            ::protocol/transaction-data
            [{:seon.agent/id agent-id}
             {:seon.agent.turn/id turn-id}]
            ::protocol/generated-candidates candidates}))]
     (is (true? (::protocol/success? result)) (pr-str result))
     turn-id)))

(defn- open-session! [agent-id]
  (let [^SocketChannel channel (uds/connect! *host-socket*)
        output (Channels/newOutputStream channel)
        input (Channels/newInputStream channel)]
    (uds/write-frame!
     output
     {:seon.execution/protocol-version 3
      :seon.execution/agent-id agent-id
      :seon.execution/artifact-digest artifact-digest
      :seon.execution/shadow-build-id "host-cancel-writer-test"
      :seon.execution/database-selection
      {:seon.db/socket-path "unused-by-the-host"
       :seon.db/database-name *database-name*}})
    {::channel channel
     ::output output
     ::input input
     ::ready (uds/read-frame input)}))

(defn- close-session! [session]
  (try (.close ^SocketChannel (::channel session)) (catch Throwable _)))

(defn- form [source]
  {:seon.repl/kind :form :seon.repl/source source})

(defn- send-invoke! [session agent-id invocation-id turn-id sources]
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
    [{:seon.eval/parsed (mapv form sources)
      :seon.eval/starting-ns (symbol (str "my.agent." agent-id))
      :seon.agent.turn/id-of-turn turn-id}]
    :seon.execution/deadline-ms (+ (System/currentTimeMillis) 30000)
    :seon.execution/result-limit-bytes 1000000}))

(defn- send-cancel! [session invocation-id]
  (uds/write-frame!
   (::output session)
   {:seon.execution/message :seon.execution.message/cancel
    :seon.execution/protocol-version 3
    :seon.execution/invocation-id invocation-id}))

(defn- receive! [session]
  (uds/read-frame (::input session)))

(defn- canceled-response? [response invocation-id]
  (and (= :seon.execution.message/error
          (:seon.execution/message response))
       (= invocation-id (:seon.execution/invocation-id response))
       (= "The invocation was canceled."
          (get-in response [:seon.execution/error :seon.error/message]))
       (= :agent
          (get-in response [:seon.execution/error :seon.error/kind]))))

(defn- turn-eval-count [turn-id]
  (count
   (context/query-writer!
    *writer-session*
    '[:find [?eval ...]
      :in $ ?turn-id
      :where
      [?turn :seon.agent.turn/id ?turn-id]
      [?turn :seon.agent.turn/evals ?eval]]
    [turn-id])))

(defn- sentinel-count [sentinel]
  (count
   (context/query-writer!
    *writer-session*
    '[:find [?entity ...]
      :in $ ?sentinel
      :where
      [?entity :seon.host-cancel-writer-test/sentinel ?sentinel]]
    [sentinel])))

(defn- wait-for! [p label]
  (is (= true (deref p 5000 ::timed-out))
      (str "timed out waiting for " label)))

(defn- await-release-uninterruptibly! [release]
  (loop []
    (when-not (realized? release)
      (try
        (deref release 50 false)
        (catch InterruptedException _))
      (recur))))

(use-fixtures
  :once
  (fn [run-tests!]
    (let [database-name (str "host-cancel-" (random-uuid))
          request-path (socket-path "cancel-writer")
          host-socket (socket-path "cancel-host")
          server (writer-test/start!
                  {::writer/dependencies ((registry-value 'dependencies))
                   ::writer/database-name database-name
                   ::writer/backend :memory
                   ::writer/request-socket-path request-path})
          writer-session
          (context/writer-session
           {::context/writer-socket-path request-path
            ::context/database-name database-name
            ::context/backend :memory})
          _ (schema/register! :seon.host-cancel-writer-test/sentinel :string)]
      (try
        (let [seeded
              (writer-test/seed-canonical-schema!
               writer-session
               database-name
               [(registry-value 'value-sampling-policy)
                {:seon.user/id "user"}
                {:seon.db.process/id :seon.db.process/repl}])]
          (is (true? (::protocol/success? seeded)) (pr-str seeded)))
        (let [declared
              (context/transact-writer!
               writer-session
               [{:seon.schema/key
                 :seon.host-cancel-writer-test/sentinel
                 :seon.schema/form ":string"}])]
          (is (:seon.db/ok? declared) (pr-str declared)))
        ;; Install optional provenance and sentinel attrs before the host's
        ;; recorder/query paths use them. Schema rows alone are intentionally
        ;; not physical Datahike attribute installation.
        (let [seed-ctx
              (context/fork-context (context/build-base! writer-session))
              installed
              (sci/eval-string*
               seed-ctx
               (str "(require 'seon.db)"
                    "(seon.db/transact!"
                    " {:seon.db/tx-data"
                    "  [{:seon.db/user [:seon.user/id \"user\"]"
                    "    :seon.db/process"
                    "    [:seon.db.process/id :seon.db.process/repl]"
                    "    :seon.host-cancel-writer-test/sentinel"
                    "    \"schema-install-probe\"}]})"))]
          (is (map? (:db-after installed)) (pr-str installed)))
        (let [started
              (host/start!
               {::host/socket-path host-socket
                ::host/eval-threads 1
                ::context/writer-socket-path request-path
                ::context/database-name database-name
                ::context/backend :memory})]
          (try
            (install-control-wrappers! started)
            (binding [*host-socket* host-socket
                      *writer-session* writer-session
                      *database-name* database-name]
              (run-tests!))
            (finally
              (host/stop! started))))
        (finally
          (reset! controls {})
          (context/close-session! writer-session)
          (writer/stop! server)
          (.delete (File. ^String request-path))
          (.delete (File. ^String host-socket)))))))

(deftest queued-cancel-produces-no-receipts-or-database-writes
  (let [[busy-agent busy-turn] (allocate-agent-turn!)
        [queued-agent queued-turn] (allocate-agent-turn!)
        queued-invocation "invocation-cancel-queued"
        sentinel "queued-ghost"
        sentinel-source
        (str "(seon.db/transact! {:seon.db/tx-data "
             "[{:seon.host-cancel-writer-test/sentinel \"" sentinel "\"}]})")
        entered (promise)
        release (promise)
        label "queue-saturator"
        busy-session (open-session! busy-agent)
        queued-session (open-session! queued-agent)]
    (swap! controls assoc label {::entered entered ::release release})
    (try
      (is (= :seon.execution.message/ready
             (:seon.execution/message (::ready busy-session))))
      (is (= :seon.execution.message/ready
             (:seon.execution/message (::ready queued-session))))
      (send-invoke!
       busy-session busy-agent "invocation-queue-saturator" busy-turn
       [(str "(require '[seon.host.cancel-test :as cancel-test])"
             " (cancel-test/block \"" label "\")")])
      (wait-for! entered "the one-worker pool saturator")
      (send-invoke! queued-session queued-agent queued-invocation queued-turn
                    [sentinel-source "(def canceled-before-start 41)"])
      (send-cancel! queued-session queued-invocation)
      (is (canceled-response? (receive! queued-session) queued-invocation))
      (deliver release true)
      (is (= :seon.execution.message/result
             (:seon.execution/message (receive! busy-session))))
      (is (nil? (receive! queued-session)) "cancel ends the queued session")
      (is (zero? (turn-eval-count queued-turn))
          "the canceled queued invocation records zero eval rows")
      (is (zero? (sentinel-count sentinel))
          "the canceled queued invocation commits zero sentinel facts")
      (finally
        (deliver release true)
        (swap! controls dissoc label)
        (close-session! busy-session)
        (close-session! queued-session)))))

(deftest running-cancel-preserves-the-context-and-next-eval
  (let [[agent-id turn-id] (allocate-agent-turn!)
        invocation-id "invocation-cancel-running"
        entered (promise)
        label "running-signal"
        session (open-session! agent-id)]
    (swap! controls assoc label {::entered entered})
    (try
      (send-invoke!
       session agent-id invocation-id turn-id
       ["(def before-running-cancel 11)"
        (str "(require '[seon.host.cancel-test :as cancel-test])"
             " (cancel-test/signal \"" label "\")")
        "(loop [i 0] (recur (inc i)))"])
      (wait-for! entered "the running invocation")
      (send-cancel! session invocation-id)
      (is (canceled-response? (receive! session) invocation-id))
      (is (nil? (receive! session)) "cancel ends the running session")
      (finally
        (swap! controls dissoc label)
        (close-session! session)))
    (let [revisit-turn (allocate-agent-turn! agent-id)
          reconnected (open-session! agent-id)]
      (try
        (send-invoke! reconnected agent-id "invocation-after-running-cancel"
                      revisit-turn ["before-running-cancel"])
        (is (= 11 (get-in (receive! reconnected)
                          [:seon.execution/result :seon.host/results 0
                           :seon.eval/value])))
        (finally
          (close-session! reconnected))))))

(deftest cancel-as-the-pool-enters-the-body-skips-the-settled-generation
  (let [[agent-id turn-id] (allocate-agent-turn!)
        invocation-id "invocation-cancel-start-race"
        sentinel "start-race-ghost"
        entered (promise)
        release (promise)
        original (var-get (host-private 'run-invocation!))
        session (open-session! agent-id)]
    (try
      (with-redefs-fn
        {(host-private 'run-invocation!)
         (fn [host-session token invocation]
           (deliver entered true)
           (await-release-uninterruptibly! release)
           (original host-session token invocation))}
        (fn []
          (send-invoke!
           session agent-id invocation-id turn-id
           [(str "(seon.db/transact! {:seon.db/tx-data "
                 "[{:seon.host-cancel-writer-test/sentinel \"" sentinel
                 "\"}]})")
            "(def canceled-in-start-race 42)"])
          (wait-for! entered "the selected invocation body")
          (send-cancel! session invocation-id)
          (is (canceled-response? (receive! session) invocation-id))
          ;; Settlement has revoked the token. Releasing into the original
          ;; body now deterministically exercises its first settled check.
          (deliver release true)
          (is (nil? (receive! session)) "cancel ends the raced session")))
      (is (zero? (turn-eval-count turn-id))
          "the settled generation records zero eval rows")
      (is (zero? (sentinel-count sentinel))
          "the settled generation commits zero sentinel facts")
      (finally
        (deliver release true)
        (close-session! session)))))

(deftest uncanceled-batches-still-record-and-return
  (let [[agent-id turn-id] (allocate-agent-turn!)
        session (open-session! agent-id)]
    (try
      (send-invoke! session agent-id "invocation-normal" turn-id
                    ["(def normal-value 40)" "(+ normal-value 2)"])
      (let [response (receive! session)
            result (:seon.execution/result response)]
        (is (= :seon.execution.message/result
               (:seon.execution/message response))
            (pr-str response))
        (is (= 2 (:seon.eval/n-ok result)) (pr-str result))
        (is (= 2 (count (:seon.eval/ids result))) (pr-str result))
        (is (= 42 (get-in result [:seon.host/results 1 :seon.eval/value])))
        (is (= 2 (turn-eval-count turn-id))
            "an ordinary batch still records one eval row per form"))
      (finally
        (close-session! session)))))
