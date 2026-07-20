(ns seon.host-registry-writer-test
  "Wrapper-registry provisioning and capability op-id receipts (U2).

   Two proof families:
   - the registry is the ONE capability-provisioning mechanism — a
     namespace registered AFTER contexts exist becomes require-able in
     every live context through the shared `:load-fn` closure, and a
     re-registered implementation reaches already-required contexts on
     their next call (the var-epoch upgrade property);
   - `seon.db/transact!` through the registry is exactly-once — the
     `:seon.capability/op-id` crosses the boundary as the writer's
     durable `:seon.db.protocol/request-id` receipt, so a connection
     killed between commit and acknowledgement never re-executes and a
     retry with the same op-id returns the recorded outcome. The writer
     here is the REAL `seon.db.writer` on a memory backend."
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.host.context :as context])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]))

;;; Registry provisioning over an unconnected writer session — the
;;; wrappers only dial the writer when called, so provisioning proofs
;;; need no server.

(defn- unconnected-writer []
  (context/writer-session
   {::context/writer-socket-path "tmp/unused-registry-test.sock"
    ::context/database-name "registry-test"}))

(deftest registering-after-forks-exist-provisions-every-live-context
  (let [base (context/build-base! (unconnected-writer))
        fork-a (context/fork-context base)
        fork-b (context/fork-context base)
        hits (atom [])]
    (context/register-wrappers!
     {::context/registry (::context/registry base)
      ::context/lib 'seon.net
      ::context/wrappers
      {'fetch {::context/wrapper-fn (fn [request]
                                      (swap! hits conj request)
                                      [:fetched request])
               ::context/arglists '([request])
               ::context/doc "Fetch one request."}}})
    (is (= [:fetched 1]
           (sci/eval-string*
            fork-a "(require '[seon.net :as net]) (net/fetch 1)")))
    (is (= [:fetched 2]
           (sci/eval-string*
            fork-b "(require '[seon.net :as net]) (net/fetch 2)")))
    (is (= [1 2] @hits))
    (is (= {:arglists '([request]) :doc "Fetch one request."}
           (sci/eval-string*
            fork-a
            "(select-keys (meta #'seon.net/fetch) [:arglists :doc])")))))

(deftest re-registering-upgrades-live-contexts-on-their-next-call
  (let [base (context/build-base! (unconnected-writer))
        fork-a (context/fork-context base)
        fork-b (context/fork-context base)
        register!
        (fn [implementation]
          (context/register-wrappers!
           {::context/registry (::context/registry base)
            ::context/lib 'seon.upgrade
            ::context/wrappers
            {'answer {::context/wrapper-fn implementation
                      ::context/arglists '([x])
                      ::context/doc "Probe answer."}}}))]
    (register! (fn [x] [:v1 x]))
    (is (= [:v1 1] (sci/eval-string*
                    fork-a "(require '[seon.upgrade :as up]) (up/answer 1)")))
    (is (= [:v1 2] (sci/eval-string*
                    fork-b "(require '[seon.upgrade :as up]) (up/answer 2)")))
    ;; Swap the implementation live: no re-require anywhere, both
    ;; contexts' next calls use it (shared vars, root alteration).
    (register! (fn [x] [:v2 x]))
    (is (= [:v2 1] (sci/eval-string* fork-a "(up/answer 1)")))
    (is (= [:v2 2] (sci/eval-string* fork-b "(up/answer 2)")))))

(deftest the-db-family-provisions-through-the-one-registry-path
  (let [base (context/build-base! (unconnected-writer))
        ctx (context/fork-context base)
        registered (get-in @(::context/registry base)
                           ['seon.db ::context/vars 'transact!])
        resolved (sci/eval-string*
                  ctx "(require 'seon.db) #'seon.db/transact!")]
    ;; The var an agent context resolves IS the registry's cached var —
    ;; no second binding path exists for the db capability family.
    (is (some? registered))
    (is (identical? registered resolved))))

;;; Op-id receipts against the real writer.

(defn- socket-path [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory
            (str "host-registry-" label "-" (random-uuid) ".sock")))))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- note-count [ctx value]
  (sci/eval-string*
   ctx
   (str "(count (seon.db/query"
        " (quote [:find ?e :in $ ?v"
        "         :where [?e :seon.host-registry-writer-test/note ?v]])"
        " " (pr-str value) "))")))

(defn- transact-note-form [value op-id]
  (str "(seon.db/transact!"
       " {:seon.db/tx-data"
       "  [{:seon.host-registry-writer-test/note " (pr-str value) "}]"
       (when op-id (str " :seon.capability/op-id " (pr-str op-id)))
       "})"))

(deftest transact-through-the-registry-is-exactly-once-by-op-id-receipt
  (let [database-name (str "host-registry-" (random-uuid))
        request-path (socket-path "writer")
        server (writer/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/request-socket-path request-path})
        session (context/writer-session
                 {::context/writer-socket-path request-path
                  ::context/database-name database-name
                  ::context/backend :memory})
        base (context/build-base! session)
        ctx (context/fork-context base)]
    (try
      ;; Declare the note attribute's canonical schema (with the
      ;; self-describing `:seon.schema` rows a fresh database needs —
      ;; the same shapes the pod's compiled-program reconcile writes),
      ;; then commit one fact with a wrapper-generated op-id.
      (let [first-outcome
            (sci/eval-string*
             ctx
             (str "(require 'seon.db)"
                  "(seon.db/transact!"
                  " {:seon.db/tx-data"
                  "  [{:seon.schema/key :seon.schema/key"
                  "    :seon.schema/form \"[:keyword {:seon.db/identity true}]\"}"
                  "   {:seon.schema/key :seon.schema/form"
                  "    :seon.schema/form \":string\"}"
                  "   {:seon.schema/key :seon.host-registry-writer-test/note"
                  "    :seon.schema/form \"[:string {:min 1}]\"}"
                  "   {:seon.host-registry-writer-test/note \"first\"}]})"))]
        (is (true? (:seon.db/ok? first-outcome)))
        (is (string? (:seon.capability/op-id first-outcome)))
        (is (not (contains? first-outcome :seon.capability/replayed?))))

      ;; A caller-supplied op-id commits once; the repeated call returns
      ;; the recorded outcome instead of re-executing.
      (let [outcome (sci/eval-string*
                     ctx (transact-note-form "alpha" "op-alpha"))
            replay (sci/eval-string*
                    ctx (transact-note-form "alpha" "op-alpha"))]
        (is (true? (:seon.db/ok? outcome)))
        (is (= "op-alpha" (:seon.capability/op-id outcome)))
        (is (not (contains? outcome :seon.capability/replayed?)))
        (is (true? (:seon.db/ok? replay)))
        (is (true? (:seon.capability/replayed? replay)))
        (is (= 1 (note-count ctx "alpha"))))

      ;; Crash drill: the request is delivered and committed, then the
      ;; connection dies before the acknowledgement arrives. The retry
      ;; with the same op-id replays the receipt; the effect happened
      ;; exactly once.
      (let [head (context/resolve-head! session)
            ^SocketChannel raw (uds/connect! request-path)
            raw-output (Channels/newOutputStream raw)]
        (uds/call! {::uds/channel raw
                    ::uds/message
                    (protocol/ensure-database-request
                     {::protocol/request-id "op-crash/ensure"
                      ::protocol/database-name database-name
                      ::protocol/backend :memory})})
        (uds/write-frame!
         raw-output
         (protocol/transaction-request
          {::protocol/request-id "op-crash"
           :seon.db/db head
           ::protocol/transaction-data
           [{:seon.host-registry-writer-test/note "crash"}]}))
        ;; Wait until the fact is committed (observed over the surviving
        ;; wrapper connection), then kill the raw connection so the
        ;; acknowledgement is lost mid-call.
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (loop []
            (when (and (zero? (note-count ctx "crash"))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 20)
              (recur))))
        (is (= 1 (note-count ctx "crash")))
        (try (.close raw) (catch Throwable _))
        (let [retry (sci/eval-string*
                     ctx (transact-note-form "crash" "op-crash"))]
          (is (true? (:seon.db/ok? retry)))
          (is (true? (:seon.capability/replayed? retry)))
          (is (= 1 (note-count ctx "crash"))))
        ;; "Did it happen?" is answered by query — the receipt is the
        ;; writer's durable request-id fact on the transaction entity.
        (is (number?
             (sci/eval-string*
              ctx
              (str "(seon.db/query"
                   " (quote [:find ?tx . :in $ ?op"
                   "         :where [?tx :seon.db.protocol/request-id ?op]])"
                   " \"op-crash\")")))))
      (finally
        (context/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))))))
