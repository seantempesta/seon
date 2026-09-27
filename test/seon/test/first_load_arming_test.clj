(ns seon.test.first-load-arming-test
  "A test namespace no boot or adoption loads: the test request that first loads
  it arms its own contracted Vars before its body runs (`seon.test/run`),
  against the loaded program's contracts, never a requesting branch's."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test]
            [seon.test-support :as test-support]))

(defn- identity-int
  {:malli/schema [:=> [:cat :int] :int]}
  [value]
  value)

(deftest a-namespace-first-loaded-by-a-request-has-its-contracts-armed
  (let [outcome (try (identity-int "not an integer")
                     (catch Throwable failure failure))
        checks (keep (comp :seon.instrument/check ex-data)
                     (take-while some? (iterate ex-cause outcome)))]
    (is (:malli.instrument/original (meta @#'identity-int)) "the Var root is a wrapper")
    (is (instance? Throwable outcome) (str "an invalid input returned " (pr-str outcome)))
    (is (= :input (first checks)) (pr-str (ex-data outcome)))))

(deftest a-branch-changed-contract-refuses-instead-of-arming-the-host-var
  (test-support/with-database
   (fn [connection]
     (let [fixture 'seon.test.first-load-branch-fixture
           function 'seon.test.first-load-branch-fixture/identity-int
           ctx (:seon.sci.eval/ctx (test-support/execution-handle connection))
           _ (test-support/transacted!
              connection
              [(test-support/program-fn-entity
                (db/db connection) function
                "(defn identity-int {:malli/schema [:=> [:cat :string] :string]} [value] value)")])
           refusal (#'seon.test/arm-first-loads!
                    (db/db connection) (test-support/effective-config) ctx #{fixture})]
       (is (= function (:seon.sci.eval/refused-function refusal)) (pr-str refusal))
       (is (nil? (find-ns fixture)) "the refusal loads, and so arms, nothing")))))

(deftest a-stale-acquisition-snapshot-does-not-decide-a-first-load
  ;; The comparison reads the program `seon.sci.eval/loaded-identity` names, never
  ;; the ctx's acquisition snapshot, which stays behind a later adoption.
  (test-support/with-database
   (fn [connection]
     (let [fixture 'seon.test.first-load-branch-fixture
           function 'seon.test.first-load-branch-fixture/identity-int
           ctx (:seon.sci.eval/ctx (test-support/execution-handle connection))
           _ (test-support/transacted!
              connection
              [(test-support/program-fn-entity
                (db/db connection) function
                "(defn identity-int {:malli/schema [:=> [:cat :string] :string]} [value] value)")])
           changed (db/db connection)
           stale (assoc ctx :seon.sci.kernel/program-snapshot
                        (atom (assoc @(:seon.sci.kernel/program-snapshot ctx)
                                     :seon.sci.eval/loaded-database changed)))
           refusal (#'seon.test/arm-first-loads! changed (test-support/effective-config) stale #{fixture})]
       (is (= function (:seon.sci.eval/refused-function refusal)) (pr-str refusal))
       (is (nil? (find-ns fixture)) "a snapshot agreeing with the branch neither loads nor arms")))))
