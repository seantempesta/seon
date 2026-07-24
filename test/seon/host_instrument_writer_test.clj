(ns seon.host-instrument-writer-test
  "JVM-host SCI instrumentation generation and wire proofs."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [sci.core :as sci]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer-test-support :as writer-test]
            [seon.error.instrument :as error.instrument]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.eval :as host.eval]
            [seon.host.instrument :as instrument]
            [seon.host-registry-writer-test :as registry-test]
            [seon.db.writer :as writer]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.nio.channels SocketChannel]))

(def ^:private value-sampling-policy
  (var-get #'registry-test/value-sampling-policy))
(def ^:private dependencies
  (var-get #'registry-test/dependencies))
(def ^:private host-session!
  (var-get #'registry-test/host-session!))
(def ^:private invoke-batch!
  (var-get #'registry-test/invoke-batch!))
(def ^:private socket-path
  (var-get #'registry-test/socket-path))

(defn- unconnected-writer []
  (context/writer-session
   {::context/writer-socket-path "tmp/unused-instrument-test.sock"
    ::context/database-name "instrument-test"}))

(defn- fixture []
  (let [base (context/build-base! (unconnected-writer))
        ctx (context/fork-context base)
        projection (schema/build-projection {} {})
        projection-state
        (atom {::context/database {:db-name "instrument-test" :t 1}
               ::context/projection projection})
        contexts (atom {"agent" ctx})
        state (instrument/state
               {::context/registry (::context/registry base)
                ::context/projection-state projection-state
                :seon.host/contexts contexts})]
    {::base base ::ctx ctx ::projection-state projection-state
     ::contexts contexts ::state state}))

(defn- projection [contracts]
  (schema/build-projection {} contracts))

(defn- install-projection! [{::keys [state projection-state]} generation]
  (reset! projection-state
          {::context/database {:db-name "instrument-test" :t 2}
           ::context/projection generation})
  (instrument/call-with-write-admission
   state #(instrument/apply-projection! state generation)))

(defn- thrown-data [f]
  (try
    (f)
    nil
    (catch Throwable throwable
      (loop [current throwable
             deepest nil]
        (if current
          (recur (.getCause current) (or (ex-data current) deepest))
          deepest)))))

(deftest misspelled-namespaced-input-key-is-humanized
  (let [payload
        (error.instrument/explain-payload
         :malli.core/invalid-input
         {:input
          (m/schema
           [:cat [:map {:closed true} [:my.domain/value :int]]])
          :args [{:my.dmoain/value 1}]})]
    (is (re-find #"should be spelled :my.domain/value"
                 (pr-str (:seon.error.malli/humanized payload))))
    (is (re-find #"the key is :my.domain/value"
                 (:seon.error.malli/hint payload)))))

(deftest multi-arity-private-var-reconciles-and-survives-redefinition
  (let [{::keys [ctx state] :as live} (fixture)
        sym 'my.instrument/multi
        generation
        (projection
         {sym [:function
               [:=> [:cat] :int]
               [:=> [:cat :int] :int]]})]
    (sci/eval-string*
     ctx
     "(ns my.instrument) (defn multi ([] 0) ([x] x))")
    (install-projection! live generation)
    (is (= 0 (instrument/call-with-read-admission
              state #(sci/eval-string* ctx "(my.instrument/multi)"))))
    (is (= 7 (instrument/call-with-read-admission
              state #(sci/eval-string* ctx "(my.instrument/multi 7)"))))
    (is (= :seon.error.kind/malli-instrument-input
           (:seon.error/kind
            (thrown-data #(sci/eval-string* ctx
                                            "(my.instrument/multi \"bad\")")))))
    (is (= :seon.error.kind/malli-instrument-arity
           (:seon.error/kind
            (thrown-data #(sci/eval-string* ctx
                                            "(my.instrument/multi 1 2)")))))
    ;; SCI defn calls bindRoot; the installed watch must immediately wrap the
    ;; fresh root against the still-current contract.
    (instrument/call-with-read-admission
     state #(sci/eval-string*
             ctx "(in-ns 'my.instrument) (defn multi ([] 1) ([x] (inc x)))"))
    (is (= :seon.error.kind/malli-instrument-input
           (:seon.error/kind
            (thrown-data #(sci/eval-string* ctx
                                            "(my.instrument/multi \"bad\")")))))))

(deftest shared-registry-and-private-context-vars-are-both-targeted
  (let [{::keys [base ctx] :as live} (fixture)
        registry (::context/registry base)
        shared-sym 'my.shared/answer
        private-sym 'my.private/answer
        generation
        (projection
         {shared-sym [:=> [:cat :int] :int]
          private-sym [:=> [:cat :int] :int]})]
    (context/register-wrappers!
     {::context/registry registry
      ::context/lib 'my.shared
      ::context/wrappers
      {'answer {::context/wrapper-fn identity}}})
    (sci/eval-string* ctx "(ns my.private) (defn answer [x] x)")
    (install-projection! live generation)
    (context/install-registered-wrappers!
     {::context/registry registry ::context/ctx ctx ::context/lib 'my.shared})
    (is (= :seon.error.kind/malli-instrument-input
           (:seon.error/kind
            (thrown-data #(sci/eval-string* ctx "(my.shared/answer \"bad\")")))))
    (is (= :seon.error.kind/malli-instrument-input
           (:seon.error/kind
            (thrown-data #(sci/eval-string* ctx "(my.private/answer \"bad\")")))))))

(deftest built-in-specced-var-instruments-without-a-redefinition-watch
  (let [{::keys [base ctx state] :as live} (fixture)
        registry (::context/registry base)
        sym 'my.protected/echo
        generation (projection {sym [:=> [:cat :int] :int]})]
    (context/register-host-wrappers!
     {::context/registry registry
      ::context/lib 'my.protected
      ::context/wrappers
      {'echo {::context/wrapper-fn identity}}})
    (context/install-registered-wrappers!
     {::context/registry registry ::context/ctx ctx ::context/lib 'my.protected})
    (let [sci-var (sci/resolve ctx sym)]
      (is (true? (:sci/built-in (meta sci-var))))
      (install-projection! live generation)
      (is (= :seon.error.kind/malli-instrument-input
             (:seon.error/kind
              (thrown-data #(sci/eval-string* ctx
                                              "(my.protected/echo \"bad\")")))))
      (is (= :sci/built-in
             (::instrument/redefinition-protection
              (get @(::instrument/apply-ledger state) sci-var))))
      ;; A privileged host root replacement would synchronously trigger any
      ;; installed watch. Remaining unwrapped proves this protected var has no
      ;; meaningless redefinition watch.
      (sci/alter-var-root sci-var (constantly identity))
      (is (= "bad" (sci/eval-string* ctx "(my.protected/echo \"bad\")"))))))

(deftest removed-contract-does-not-resurrect-through-the-root-watch
  (let [{::keys [ctx] :as live} (fixture)
        sym 'my.removal/echo]
    (sci/eval-string* ctx "(ns my.removal) (defn echo [x] x)")
    (install-projection! live (projection {sym [:=> [:cat :int] :int]}))
    (is (= :seon.error.kind/malli-instrument-input
           (:seon.error/kind
            (thrown-data #(sci/eval-string* ctx "(my.removal/echo \"ok\")")))))
    (install-projection! live (projection {}))
    (sci/eval-string* ctx "(in-ns 'my.removal) (defn echo [x] [:new x])")
    (sci/eval-string* ctx "(in-ns 'my.removal) (defn echo [x] [:newer x])")
    (is (= [:newer "ok"]
           (sci/eval-string* ctx "(my.removal/echo \"ok\")")))))

(deftest generation-admission-excludes-a-mixed-refresh-window
  (let [{::keys [ctx state projection-state] :as live} (fixture)
        sym 'my.generation/echo
        old-projection (projection {sym [:=> [:cat :int] :int]})
        new-projection (projection {sym [:=> [:cat :string] :string]})
        _ (sci/eval-string* ctx "(ns my.generation) (defn echo [x] x)")
        _ (install-projection! live old-projection)
        _ (is (= 1 (sci/eval-string* ctx "(my.generation/echo 1)")))
        writer-entered (promise)
        release-writer (promise)
        observed (promise)
        writer
        (future
          (instrument/call-with-write-admission
           state
           (fn []
             (reset! projection-state
                     {::context/database {:db-name "instrument-test" :t 3}
                      ::context/projection new-projection})
             (instrument/apply-projection! state new-projection)
             (deliver writer-entered true)
             @release-writer)))
        _ @writer-entered
        reader
        (future
          (deliver observed
                   (instrument/call-with-read-admission
                    state
                    (fn []
                      [(get-in @projection-state
                               [::context/projection
                                :seon.schema.projection/fingerprint])
                       (sci/eval-string* ctx
                                         "(my.generation/echo \"new\")")]))))]
    (is (= ::blocked (deref observed 100 ::blocked)))
    (deliver release-writer true)
    (is (= [(:seon.schema.projection/fingerprint new-projection) "new"]
           (deref observed 1000 ::timed-out)))
    (is (= :seon.error.kind/malli-instrument-input
           (:seon.error/kind
            (thrown-data #(sci/eval-string* ctx "(my.generation/echo 1)")))))
    @reader
    @writer))

(deftest classified-input-envelope-is-wire-safe-and-hints-on-the-jvm
  (let [wrapped
        (m/-instrument
         {:schema [:=> [:cat :int] :int]
          :report (fn [report-type data]
                    (error.instrument/report-fn
                     report-type (assoc data :fn-name 'my.wire/needs-int)))}
         identity)
        classified (thrown-data #(wrapped "bad"))
        wire-safe ((var-get #'host.eval/wire-safe-value)
                   {:seon.eval/ok? false
                    :seon/error {:seon.error/data classified}})
        round-tripped (uds/decode (uds/encode wire-safe))]
    (is (= wire-safe round-tripped))
    (is (= :seon.error.kind/malli-instrument-input
           (get-in round-tripped
                   [:seon/error :seon.error/data :seon.error/kind])))
    (is (= 'my.wire/needs-int
           (get-in round-tripped
                   [:seon/error :seon.error/data :seon.error.malli/fn-sym])))
    (is (string?
         (get-in round-tripped
                 [:seon/error :seon.error/data :seon.error.malli/hint])))))

(deftest new-private-specced-var-fails-next-form-and-next-batch
  (let [database-name (str "host-instrument-" (random-uuid))
        request-path (socket-path "instrument-writer")
        host-socket (socket-path "instrument-host")
        agent-candidates
        (db.id/candidate-manifest
         {:seon.agent/id :seon.db.id.generator/human-readable
          :seon.agent.turn/id :seon.db.id.generator/compact}
         [{:seon.db.id/key :fixture/agent
           :seon.db.id/identity-attr :seon.agent/id}
          {:seon.db.id/key :fixture/turn
           :seon.db.id/identity-attr :seon.agent.turn/id}])
        agent-id (:seon.db.id/value (first agent-candidates))
        turn-id (:seon.db.id/value (second agent-candidates))
        server (writer-test/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/request-socket-path request-path})
        session (context/writer-session
                 {::context/writer-socket-path request-path
                  ::context/database-name database-name
                  ::context/backend :memory})]
    (try
      (let [seeded
            (writer-test/seed-canonical-schema!
             session database-name
             [value-sampling-policy
              {:seon.user/id "user"}
              {:seon.db.process/id :seon.db.process/repl}])]
        (is (true? (::protocol/success? seeded)) (pr-str seeded)))
      (let [database (db.host/resolve-db! session nil false)
            allocated
            (db.host/call!
             session
             (protocol/transaction-request
              {::protocol/request-id (str (random-uuid))
               :seon.db/db database
               ::protocol/transaction-data
               [{:seon.agent/id agent-id}
                {:seon.agent.turn/id turn-id}]
               ::protocol/generated-candidates agent-candidates}))]
        (is (true? (::protocol/success? allocated)) (pr-str allocated)))
      ;; Install optional corpus attributes that exact-set terminal recording
      ;; may retract, matching a real cluster's genesis population.
      (let [probe
            (sci/eval-string*
             (context/fork-context (context/build-base! session))
             (str "(require 'seon.db)"
                  "(seon.db/transact! {:seon.db/tx-data "
                  (pr-str [{:seon.db/user [:seon.agent/id agent-id]
                            :seon.db/process
                            [:seon.db.process/id :seon.db.process/repl]}
                           {:seon.fn/sym "seed/install-probe"
                            :seon.fn/spec "[:=> [:cat :int] :int]"
                            :seon.fn/schema-error "none"
                            :seon.fn/read-attrs [:seed/attr]}
                           {:seon.fn/sym "seon.ai.tokens/estimate-chars"
                            :seon.fn/spec "[:=> [:cat :int] :int]"}])
                  "})"))]
        (is (map? (:db-after probe)) (pr-str probe)))
      (let [started
            (host/start! {::host/socket-path host-socket
                          ::context/writer-socket-path request-path
                          ::context/database-name database-name
                          ::context/backend :memory})
            tokens-var
            (get-in @(::context/registry (::host/base started))
                    ['seon.ai.tokens ::context/vars 'estimate-chars])
            live (host-session! host-socket agent-id database-name)]
        (try
          (is (true? (:sci/built-in (meta tokens-var)))
              "the cold-start base retains W0.2 wrapper protection")
          (is (= :sci/built-in
                 (::instrument/redefinition-protection
                  (get @(::instrument/apply-ledger
                         (::instrument/state started))
                       tokens-var)))
              "cold startup instruments the real stamped host wrapper")
          (let [response
                (invoke-batch!
                 live agent-id turn-id "instrument-built-in-parity"
                 (context/resolve-head! session)
                 [{:seon.repl/kind :form
                   :seon.repl/source
                   "(seon.ai.tokens/estimate-chars 4)"}
                  {:seon.repl/kind :form
                   :seon.repl/source
                   "(seon.ai.tokens/estimate-chars \"bad\")"}
                  {:seon.repl/kind :form
                   :seon.repl/source
                   "(seon.ai.tokens/estimate-chars 8)"}])
                result (:seon.execution/result response)
                results (:seon.host/results result)
                error (get-in results [1 :seon/error])]
            (is (= :seon.execution.message/result
                   (:seon.execution/message response))
                (pr-str response))
            (is (nil? (:seon.execution/error response)) (pr-str response))
            (is (= 2 (:seon.eval/n-ok result)) (pr-str response))
            (is (= 1 (:seon.eval/n-fail result)) (pr-str response))
            (is (= 3 (count (:seon.eval/ids result))) (pr-str response))
            (is (= 3 (count results)) (pr-str response))
            (is (= [true false true] (mapv :seon.eval/ok? results)))
            (is (= [16 32]
                   (mapv :seon.eval/value [(first results) (last results)])))
            (is (= :seon.error.kind/malli-instrument-input
                   (get-in error [:seon.error/data :seon.error/kind])))
            (is (= :schema-input
                   (get-in error
                           [:seon.error/data :seon.error.sci/class])))
            (is (= 'seon.ai.tokens/estimate-chars
                   (get-in error
                           [:seon.error/data :seon.error.malli/fn-sym])))
            (is (= ":int"
                   (get-in error
                           [:seon.error/data :seon.error.malli/expected])))
            (is (= "\"bad\""
                   (get-in error
                           [:seon.error/data :seon.error.malli/got-edn])))
            (is (seq (get-in error
                             [:seon.error/data
                              :seon.error.malli/humanized])))
            (is (string? (get-in error
                                 [:seon.error/data
                                  :seon.error.malli/hint]))))
          (let [head (context/resolve-head! session)
                definition
                (str "(defn private-multi\n"
                     "  {:malli/schema [:function\n"
                     "                   [:=> [:cat] :int]\n"
                     "                   [:=> [:cat :int] :int]]}\n"
                     "  ([] 0) ([x] x))")
                first-response
                (invoke-batch!
                 live agent-id turn-id "instrument-first" head
                 [{:seon.repl/kind :form :seon.repl/source definition}
                  {:seon.repl/kind :form
                   :seon.repl/source "(private-multi \"bad\")"}])
                first-result (:seon.execution/result first-response)
                first-error (get-in first-result
                                    [:seon.host/results 1 :seon/error])]
            (is (= 1 (:seon.eval/n-ok first-result)) (pr-str first-response))
            (is (= 1 (:seon.eval/n-fail first-result)) (pr-str first-response))
            (is (= :schema-input
                   (get-in first-error
                           [:seon.error/data :seon.error.sci/class])))
            (let [next-response
                  (invoke-batch!
                   live agent-id turn-id "instrument-next"
                   (context/resolve-head! session)
                   [{:seon.repl/kind :form
                     :seon.repl/source "(private-multi \"still-bad\")"}])
                  next-error
                  (get-in next-response
                          [:seon.execution/result :seon.host/results 0
                           :seon/error])]
              (is (= :schema-input
                     (get-in next-error
                             [:seon.error/data :seon.error.sci/class]))))
            ;; A fresh host has no live private contexts at cold apply time.
            ;; Session startup must replay, link, reconcile, publish, then READY.
            (.close ^SocketChannel (::registry-test/channel live))
            (host/stop! started)
            (let [restarted
                  (host/start! {::host/socket-path host-socket
                                ::context/writer-socket-path request-path
                                ::context/database-name database-name
                                ::context/backend :memory})
                  restored-live
                  (host-session! host-socket agent-id database-name)]
              (try
                (let [response
                      (invoke-batch!
                       restored-live agent-id turn-id "instrument-restored"
                       (context/resolve-head! session)
                       [{:seon.repl/kind :form
                         :seon.repl/source
                         "(private-multi \"restored-bad\")"}])]
                  (is (= :schema-input
                         (get-in response
                                 [:seon.execution/result :seon.host/results 0
                                  :seon/error :seon.error/data
                                  :seon.error.sci/class]))))
                (finally
                  (try (.close ^SocketChannel
                               (::registry-test/channel restored-live))
                       (catch Throwable _))
                  (host/stop! restarted)))))
          (finally
            (try (.close ^SocketChannel (::registry-test/channel live))
                 (catch Throwable _))
            (host/stop! started))))
      (finally
        (context/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))
        (.delete (File. ^String host-socket))))))
