(ns seon.cluster.program-change-test
  "Development adoption waits for no one. A test member reads the loaded
  program identity when it starts and when it ends; a change between them
  makes it unknown (`:program-unknown`, never green or red). A failed adoption
  leaves the program unknown until one records. Every test injects its own
  identity atom; the JVM's `seon.sci.eval/loaded-identity` is never touched."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test]
            [seon.test-support :as support]))

(def ^:private adopted
  {:seon.source/reloaded-namespaces [] :seon.source/arming-identities #{}})

(defn- adopt!
  "Run one adoption toward `commit` on `loaded`; a refusal answers its message."
  [loaded commit work]
  (try (@#'cluster/call-with-program-change loaded commit work)
       (catch clojure.lang.ExceptionInfo failure (ex-message failure))))

(defn- member-run
  "Run `body` as one member test through `seon.test/bounded-result` on `loaded`."
  [loaded body]
  (let [owned (create-ns (gensym "seon.cluster.program-change-test.member-"))
        probe (intern owned (with-meta 'probe {:test body}) nil)]
    (try (@#'seon.test/bounded-result
          loaded (symbol probe) (constantly probe) 5000
          {:seon.sci.eval/ctx (:seon.sci.eval/ctx (support/execution-handle nil))} (fn [_] nil))
         (finally (remove-ns (ns-name owned))))))

(deftest an-adoption-proceeds-while-a-member-runs-and-the-member-reads-unknown
  (testing "no change: the member's ordinary result"
    (let [result (member-run (atom {:seon.source/commit-id (random-uuid)}) #(is true))]
      (is (= 1 (:seon.test/pass-count result)))
      (is (not (contains? result :seon.test.run/stopped)))))
  (testing "an adoption completes while the member is still running"
    (let [loaded (atom {:seon.source/commit-id (random-uuid)})
          commit (random-uuid)
          entered (promise)
          release (promise)
          member (future (member-run loaded #(do (deliver entered true) (is @release))))
          _ (is (true? (deref entered 1000 false)) "the member started")
          adoption (future (adopt! loaded commit (constantly adopted)))]
      (is (= adopted (deref adoption 1000 nil)) "the adoption never waited for the member")
      (is (= {:seon.source/commit-id commit} @loaded))
      (deliver release true)
      (let [result (deref member 5000 nil)]
        (is (= :program-unknown (:seon.test.run/stopped result)) "unknown, never green")
        (is (zero? (:seon.test/pass-count result))))))
  (testing "an adoption the member runs itself is its own work"
    (let [loaded (atom {:seon.source/commit-id (random-uuid)})]
      (is (= :own (sci.eval/call-detecting-program-change
                   loaded `own #(do (adopt! loaded (random-uuid) (constantly adopted)) :own)))))))

(deftest an-adoption-failing-after-roots-change-keeps-the-program-unknown-until-one-records
  (let [loaded (atom {})
        database (db/db (:seon.db/connection (support/execution-handle nil)))
        entries (atom 0)
        member #(sci.eval/call-detecting-program-change
                 loaded `member (fn [] (swap! entries inc) :entered))
        scratch "seon.cluster.program-change-scratch"]
    (testing "a reload that fails after defining a Var"
      (let [commit (random-uuid)]
        (is (= "reload stopped midway"
               (adopt! loaded commit #(load-string (str "(ns " scratch ") (def loaded :new)"
                                                        " (throw (ex-info \"reload stopped midway\" {}))")))))
        (is (= :new @(resolve (symbol scratch "loaded"))) "the root changed before the failure")
        (is (= commit (:seon.source/commit-id @loaded)))
        (is (= ["reload stopped midway"] (keep :seon.error/message (:seon.error/chain @loaded))))))
    (doseq [[label message] [["arming" "Development JVM instrumentation did not restore contracts."]
                             ["the record" "The cluster population transaction was refused: basis moved"]]]
      (testing (str label " refused after the reload")
        (let [commit (random-uuid)]
          (is (= message (adopt! loaded commit #(@#'cluster/refused! message {:seon.cluster.source/phase :adoption}))))
          (is (= commit (:seon.source/commit-id @loaded))))))
    (testing "nothing starts in the unknown program; it answers the diagnostic"
      (let [refusal (member)]
        (is (zero? @entries))
        (is (= (:seon.source/commit-id @loaded) (:seon.source/commit-id refusal)))
        (is (schema/valid-candidate-value? (db/carried-projection database)
                                           :seon.test/not-runnable-error refusal))))
    (testing "a later adoption that records makes execution available again"
      (let [commit (random-uuid)]
        (is (= adopted (adopt! loaded commit (constantly adopted))))
        (is (= {:seon.source/commit-id commit} @loaded))
        (is (= :entered (member)))
        (is (= 1 @entries))))
    (remove-ns (symbol scratch))))
