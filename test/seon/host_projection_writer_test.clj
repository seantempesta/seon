(ns seon.host-projection-writer-test
  "Committed schema projection admission and publication proofs."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db.protocol :as protocol]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.sample :as host.sample]
            [seon.schema :as schema]))

(defn- projection [k form]
  (schema/build-projection {k form}))

(defn- acquired [t k form]
  {::context/database {:db-name "projection-test" :t t}
   ::context/projection (projection k form)})

(deftest publication-is-monotonic-across-success-and-fault-races
  (let [state (atom (acquired 9 :projection.test/nine :int))
        ten (acquired 10 :projection.test/ten :int)
        eleven (acquired 11 :projection.test/eleven :int)]
    (context/publish-committed-projection! state eleven)
    (context/publish-committed-projection! state ten)
    (is (= 11 (get-in @state [::context/database :t])))
    (reset! state {::context/fault {:seon.error/message "t13 failed"}
                   ::context/committed-basis 13})
    (context/publish-committed-projection! state (acquired 12 :projection.test/twelve :int))
    (is (= 13 (::context/committed-basis @state)))
    (context/publish-committed-projection! state (acquired 13 :projection.test/thirteen :int))
    (is (= 13 (get-in @state [::context/database :t])))
    (is (nil? (::context/fault @state)))))

(deftest a-newer-generation-fault-refuses-the-host-drill-read
  (let [fault {:seon.error/message "projection refresh failed"
               :seon.error/kind :core-bug}
        state (atom {::context/fault fault
                     ::context/committed-basis 11})
        result (host.sample/drill-value
                 {::host/projection-state state}
                 {:projection.test/id 1}
                 {:seon.render.value/path []
                  :seon.render.value/offset 0
                  :seon.render.value/effective-limits
                  {:seon.config.render/value-max-path-segments 32
                   :seon.config.render/value-max-path-bytes 4096
                   :seon.config.render/value-max-realized-items 32
                   :seon.config.render/value-max-depth 3
                   :seon.config.render/value-max-string 80
                   :seon.config.render/value-shape-sample 8
                   :seon.render.value/page-size 8}})]
    (is (false? (:seon.render.value/ok? result)))
    (is (= {:seon.error/message
            "Schema-aware value browsing is unavailable."
            :seon.error/kind :core-bug}
           (:seon/error result)))
    (is (< (count (pr-str result)) 256))))

(deftest one-database-value-pins-both-authority-queries
  (let [database {:db-name "projection-test" :t 42}
        request (atom nil)
        response
        (protocol/success
          {::protocol/results
           [(protocol/success {:datahike.query/result
                               #{[:projection.test/value ":int"
                                  {:seon.db/process
                                   {:seon.db.process/id
                                    :seon.db.process/boot}}]}})
            (protocol/success {:datahike.query/result #{}})]})]
    (with-redefs [context/resolve-head! (fn [_] database)]
      (with-redefs-fn
        {#'context/writer-call! (fn [_ sent]
                                  (reset! request sent)
                                  response)}
        (fn []
          (let [result (context/acquire-committed-projection! {})]
            (is (= 42 (get-in result [::context/database :t])))
            (is (= [database database]
                   (mapv :seon.db/db (::protocol/members @request))))
            (is (= [[schema/asserting-transaction-provenance-pattern]
                    [schema/asserting-transaction-provenance-pattern]]
                   (mapv ::protocol/arguments
                         (::protocol/members @request))))))))))

(deftest cap-plus-one-sentinel-refuses-a-silently-partial-population
  (let [database {:db-name "projection-test" :t 42}
        rows (mapv (fn [i] [(keyword "projection.overflow" (str "k" i))
                            ":int"])
                   (range 4097))
        response
        (protocol/success
          {::protocol/results
           [(protocol/success {:datahike.query/result rows})
            (protocol/success {:datahike.query/result []})]})]
    (with-redefs [context/resolve-head! (fn [_] database)]
      (with-redefs-fn
        {#'context/writer-call! (fn [_ _] response)}
        (fn []
          (is (= :core-bug
                 (get-in (context/acquire-committed-projection! {})
                         [:seon/error :seon.error/kind]))))))))

(deftest acquisition-does-not-replace-jvm-private-candidates
  (let [before (schema/snapshot-state)
        private-key :projection.test.jvm/private]
    (try
      (schema/register! private-key [:map [:projection.test.jvm/id :int]])
      (let [database {:db-name "projection-test" :t 5}
            response
            (protocol/success
              {::protocol/results
               [(protocol/success
                  {:datahike.query/result
                   #{[:projection.test.browser/id ":int"]
                     [:projection.test.browser/shape
                      "[:map [:projection.test.browser/id :projection.test.browser/id]]"]}})
                (protocol/success {:datahike.query/result #{}})]})]
        (with-redefs [context/resolve-head! (fn [_] database)]
          (with-redefs-fn
            {#'context/writer-call! (fn [_ _] response)}
            (fn []
              (let [result (context/acquire-committed-projection! {})]
                (is (schema/valid-candidate-value?
                      private-key {:projection.test.jvm/id 1}))
                (is (not (contains?
                           (:seon.schema.projection/forms
                             (::context/projection result))
                           private-key))))))))
      (finally (schema/restore-state! before)))))

(deftest malformed-population-refuses-host-before-readiness
  (let [closed? (atom false)]
    (with-redefs [context/writer-session (fn [_] ::writer)
                  context/close-session! (fn [_] (reset! closed? true))
                  context/acquire-committed-projection!
                  (fn [_] {:seon/error
                           {:seon.error/message "unresolved committed form"
                            :seon.error/kind :core-bug}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"unresolved committed form"
                            (host/start! {::host/socket-path "tmp/never-ready.sock"
                                          ::context/writer-socket-path "unused"
                                          ::context/database-name "unused"})))
      (is @closed?))))

(deftest terminal-result-reports-projection-change-from-actual-tx-rows
  (let [before (schema/snapshot-state)
        at (java.util.Date.)
        request {:seon.eval/id "eval-test"
                 ::context/envelope {:seon.eval/ok? true
                                     :seon.eval/result :ok}
                 ::context/at at
                 ::context/duration-ms 1
                 ::context/source "(schema/register! :projection.test/new :int)"
                 ::context/narration ""
                 ::context/ns-sym 'projection.test
                 ::context/agent-id "agent-test"
                 ::context/forms
                 '[(schema/register! :projection.test/new :int)]
                 ::context/var-meta {}
                 ::context/new-schema-keys #{:projection.test/new}}]
    (try
      (schema/register! :projection.test/new :int)
      (with-redefs-fn
        {#'context/record-transact!
         (fn [_ _] {:seon.db/ok? true :db-after {:t 7}})}
        (fn []
          (is (true? (::context/projection-changed?
                       (context/record-eval-terminal! {} request))))))
      (finally (schema/restore-state! before)))))

(deftest a-covering-newer-generation-makes-an-older-refresh-failure-harmless
  (let [state (atom (acquired 11 :projection.test/eleven :int))]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_] {:seon/error
                           {:seon.error/message "late t10 refresh failed"
                            :seon.error/kind :core-bug}})]
      (is (= 11
             (get-in (context/refresh-committed-projection! {} state 10)
                     [::context/database :t])))
      (is (= 11 (get-in @state [::context/database :t]))))))

(deftest older-failure-and-success-cannot-lower-a-newer-fault-floor
  (let [state (atom (acquired 9 :projection.test/nine :int))
        responses (atom [{:seon/error
                          {:seon.error/message "t13 failed"
                           :seon.error/kind :core-bug}}
                         {:seon/error
                          {:seon.error/message "t12 failed"
                           :seon.error/kind :core-bug}}
                         (acquired 12 :projection.test/twelve :int)])]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_] (let [response (first @responses)]
                            (swap! responses subvec 1)
                            response))]
      (context/refresh-committed-projection! {} state 13)
      (context/refresh-committed-projection! {} state 12)
      (context/refresh-committed-projection! {} state 12)
      (is (= 13 (::context/committed-basis @state)))
      (is (= "t13 failed"
             (get-in @state [::context/fault :seon.error/message]))))))

(deftest stale-success-after-a-newer-commit-faults-and-refuses-drill
  (let [state (atom (acquired 8 :projection.test/eight :int))]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_] (acquired 9 :projection.test/nine :int))]
      (is (= :core-bug
             (get-in (context/refresh-committed-projection! {} state 10)
                     [:seon/error :seon.error/kind])))
      (is (= 10 (::context/committed-basis @state)))
      (is (:seon/error (context/current-committed-projection state))))))

(deftest drill-refuses-through-the-whole-post-commit-refresh-window
  (let [state (atom (acquired 9 :projection.test/nine :int))
        entered (promise)
        release (promise)]
    (with-redefs [context/acquire-committed-projection!
                  (fn [_]
                    (deliver entered true)
                    @release
                    (acquired 10 :projection.test/ten :int))]
      (let [refresh (future
                      (context/refresh-committed-projection! {} state 10))]
        @entered
        (is (= 10 (::context/committed-basis @state)))
        (is (:seon/error (context/current-committed-projection state)))
        (deliver release true)
        (is (= 10 (get-in @refresh [::context/database :t])))
        (is (= 10 (get-in @state [::context/database :t])))
        (is (nil? (::context/fault @state)))))))
