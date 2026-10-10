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
    (is (instance? Throwable outcome) "an invalid input returned")
    (is (= :input (first checks)))))

(deftest ^{:seon/tasks #{'seon.test/an-unloaded-agent-namespace-runs-interpreted-at-the-gate}}
  a-namespace-the-loaded-program-declares-otherwise-runs-interpreted-never-loaded
  ;; Root's request (the merge gate) interprets a namespace this JVM never
  ;; loaded when the request's program declares it otherwise than the loaded
  ;; program: compiled code runs only what the JVM loads unchanged.
  (test-support/with-database
   (fn [connection]
     (let [fixture 'seon.test.first-load-branch-fixture
           function 'seon.test.first-load-branch-fixture/identity-int
           loaded (db/db connection)
           _ (test-support/transacted!
              connection
              [(test-support/program-fn-entity
                loaded function
                "(defn identity-int {:malli/schema [:=> [:cat :string] :string]} [value] value)")])
           changed (db/db connection)]
       (is (= #{fixture} (#'seon.test/interpreted-namespaces changed loaded #{fixture})))
       (is (= #{} (#'seon.test/interpreted-namespaces changed changed #{fixture}))
           "a namespace the loaded program declares unchanged loads compiled")
       (is (nil? (find-ns fixture)) "deciding loads nothing")))))

(deftest a-stale-acquisition-snapshot-does-not-decide-the-loaded-program
  ;; The loaded program is the one `seon.sci.eval/loaded-identity` names, never
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
                                     :seon.sci.eval/loaded-database changed)))]
       (is (= #{fixture} (#'seon.test/interpreted-namespaces changed (#'seon.test/loaded-program stale) #{fixture}))
           "a snapshot agreeing with the branch does not make its namespace loadable")
       (is (nil? (find-ns fixture)))))))
