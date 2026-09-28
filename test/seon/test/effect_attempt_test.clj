(ns seon.test.effect-attempt-test
  "An agent-authored capability stub crosses the real effect path in a test attempt (design §2c)."
  (:require [clojure.test :refer [deftest is]] [seon.config :as config] [seon.db :as db]
            [seon.program :as program] [seon.sci.eval :as sci.eval] [seon.test :as sut]
            [seon.test-support :as support]))

(defn- admit!
  "Evaluate `source` in `my.catalog` as an agent does and write its declaration entity."
  [{connection :seon.db/connection ctx :seon.sci.eval/ctx} source]
  (let [evaluation (support/await-event!
                    (future (db/call-with-custody {:seon.db/connection connection}
                              #(sci.eval/evaluate {:seon.sci.eval/ctx ctx :seon.cluster.eval/source source
                                                   :seon.cluster.eval/ns [:seon.ns/name 'my.catalog]
                                                   :seon.sci.admit/caps (config/result-caps config/defaults)
                                                   :seon.sci.eval/time-limit-ms 5000})))
                    ::admitted)]
    (is (nil? (:seon.cluster.eval/error evaluation)))
    (support/transacted! connection [(program/declaration-row (db/carried-projection (db/db connection))
                                                              (:seon.program/row evaluation) :all :agent)])))

(def ^:private receipts
  "(seon.db/q '[:find ?o ?c ?q ?r :where [?e :seon.effect/owner ?of] [?of :seon.fn/sym ?o]
     [?e :seon.effect/capability ?c] [?e :seon.effect/request-edn ?q] [?e :seon.effect/result-edn ?r]] (seon.db/db))")

(deftest ^{:seon.test/long "One nested request over four agent entities; unmeasured here, the same shape in one-request-test measured 10.0-23.2 s (routed per-request acquisition)."
           :seon.test/long-ms 30000}
  an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt
  (support/with-database
   (fn [connection]
     (let [handle (support/execution-handle connection)
           tests #{'my.catalog/reads-canned-label 'my.catalog/a-hidden-call-refuses-without-an-evaluation}]
       (support/transacted! connection [(support/namespace-entity (db/db connection) 'my.catalog)])
       (doseq [source ["(defn canned-label {:malli/schema [:=> [:cat :string :seon.config/effective] :string]} [_request _effective] \"fixture label\")"
                       "(defn label {:seon.effect/capability my.catalog/canned-label :malli/schema [:=> [:cat :string] :seon.effect/request-result]} [request] (seon.effect/request! #'label request))"
                       ;; The body reads its own attempt branch: owner, handler, request, settled result.
                       (str "(clojure.test/deftest reads-canned-label (clojure.test/is (= \"fixture label\" (label \"item-1\")))"
                            " (clojure.test/is (= #{['my.catalog/label 'my.catalog/canned-label \"\\\"item-1\\\"\" \"\\\"fixture label\\\"\"]} " receipts ")))")
                       ;; A call the reach walk cannot see gets no evaluation, so it refuses by name.
                       (str "(clojure.test/deftest a-hidden-call-refuses-without-an-evaluation"
                            " (clojure.test/is (= :seon.turn/id (:seon.effect/missing-context-member ((resolve (symbol \"my.catalog\" \"label\")) \"x\"))))"
                            " (clojure.test/is (empty? " receipts ")))")]]
         (admit! handle source))
       (let [result (sut/run {:seon.test/execution handle :seon.test/recording-connection connection
                              :seon.test/policy :named :seon.test/identities tests})]
         (is (true? (:seon.test/passed? result)))
         (is (= 2 (:seon.test/executed-count result)))
         (is (empty? (db/q '[:find ?e :where [?e :seon.effect/id]] (db/db connection)))
             "no receipt reaches the requesting branch"))))))
