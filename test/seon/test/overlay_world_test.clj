(ns seon.test.overlay-world-test
  "A request made on an agent branch runs on the loaded published program plus
  that branch's differing program rows, never on a fork of the branch: the
  branch's operational rows and live default's agents stay out, a retracted
  declaration is absent and a changed callee runs its candidate definition."
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
            [seon.sci.eval :as sci.eval]
            [seon.test :as sut]
            [seon.test-support :as support]))

(def ^:private probe-ns 'seon.id)

(def ^:private sentinel ::sentinel)

(def ^:private victim 'seon.test.one-request-test/a-member-body-runs-on-its-own-listed-branch)

(defn- admit!
  "Evaluate one form as an agent does and write its canonical declaration row."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :string] :seon.db/transaction-report]}
  [handle source]
  (let [connection (:seon.db/connection handle)
        evaluation (support/await-event!
                    (future
                     (db/call-with-custody
                      {:seon.db/connection connection}
                      #(sci.eval/evaluate
                        {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                         :seon.cluster.eval/source source
                         :seon.cluster.eval/ns [:seon.ns/name probe-ns]
                         :seon.sci.admit/caps (config/result-caps config/defaults)
                         :seon.sci.eval/time-limit-ms 5000
                         :seon.config/on-core-error :panic})))
                    ::admitted)]
    (is (nil? (:seon.cluster.eval/error evaluation)) (pr-str evaluation))
    (support/transacted! connection [(program/declaration-row
                                      (db/carried-projection (db/db connection))
                                      (:seon.program/row evaluation) :all :agent)])))

(deftest ^{:seon.test/long "Four agent definitions, one retraction and one nested request on a fixture branch. Unmeasured (lane load rule): the sibling one-request-test nested request measured 13.8 s on default's JVM; root replaces this with the measured clock."
           :seon.test/long-ms 30000}
  an-agent-branch-request-runs-its-candidate-on-a-clean-world
  (support/with-database
   {:seon.test-support/extra-schema (support/file-store-probe-schema sentinel)}
   (fn [connection]
     (let [handle (support/execution-handle connection)]
       (support/transacted! connection [{sentinel "operational"}])
       (support/transacted! connection [[:db/retractEntity [:seon.test/sym victim]]])
       (admit! handle "(defn overlay-callee [] 1)")
       (admit! handle "(defn overlay-caller [] (overlay-callee))")
       (admit! handle "(defn overlay-callee [] 2)")
       (admit! handle
               (str "(clojure.test/deftest overlay-candidate-runs"
                    " (clojure.test/is (= 2 (overlay-caller)))"
                    " (clojure.test/is (nil? (get-in (seon.db/db) [:schema " sentinel "])))"
                    " (clojure.test/is (empty? (seon.db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]] (seon.db/db))))"
                    " (clojure.test/is (nil? (seon.db/q '[:find ?e . :in $ ?s :where [?e :seon.test/sym ?s]]"
                    " (seon.db/db) '" victim "))))"))
       (let [result (sut/run {:seon.test/execution (support/execution-handle connection)
                              :seon.test/recording-connection connection
                              :seon.test/policy :named
                              :seon.test/identities #{(symbol (str probe-ns) "overlay-candidate-runs")}})]
         (is (true? (:seon.test/passed? result)) (sut/tally result))
         (is (= 1 (:seon.test/executed-count result)) (sut/tally result)))))))
