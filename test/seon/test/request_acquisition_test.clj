(ns seon.test.request-acquisition-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.registry :as mr]
            [sci.lang]
            [seon.schema :as schema]
            [seon.db :as db]
            [seon.test :as sut]
            [seon.test-support :as support]))

(def ^:private marker :seon.test.request-acquisition-test/marker)

(defn- marker-test
  "A test that writes its own marker and must see only that marker."
  [label]
  (str "(clojure.test/deftest writes-" label
       " (seon.db/transact! [{" marker " \"" label "\"}])"
       " (clojure.test/is (= #{\"" label "\"}"
       " (set (seon.db/q '[:find [?m ...] :where [_ " marker " ?m]] (seon.db/db))))))"))

(deftest members-of-one-request-share-its-acquired-program
  (support/with-database
   {:seon.test-support/extra-schema (support/file-store-probe-schema marker)}
   (fn [connection]
     (support/transacted! connection [(support/namespace-entity (db/db connection) 'seon.test.request-acquisition-test)])
     (support/transacted!
      connection
      (mapv #(support/program-entity (db/db connection)
                                  [:seon.test/sym (symbol "seon.test.request-acquisition-test" (str "writes-" %))]
                                  (marker-test %))
            ["a" "b" "c"]))
     (let [result (sut/run {:seon.test/execution (support/execution-handle connection)
                            :seon.test/recording-connection connection
                            :seon.test/policy :named
                            :seon.test/identities (into #{} (map #(symbol "seon.test.request-acquisition-test" (str "writes-" %)))
                                                        ["a" "b" "c"])})
           timings (:seon.test/timings result)]
       (is (true? (:seon.test/passed? result)))
       (is (= 3 (:seon.test/executed-count result)))
       ;; The request acquires its program once; each member forks it and finds
       ;; that program by commit (29-35 ms measured) instead of re-acquiring it
       ;; (325-581 ms before, 2026-09-25).
       (is (every? #(< (+ (:seon.test.timing/acquire-ms %) (:seon.test.timing/resolve-ms %)) 50) timings))
       (is (= 3 (count (set (map :seon.agent/branch timings)))) "every member ran on its own branch")
       (is (empty? (support/file-store-markers connection marker))
           "no member write reaches the execution branch")))))

(deftest a-member-installs-its-program-unobserved-by-the-calling-evaluation
  ;; A merge candidate's new function is installed into the MEMBER's context;
  ;; the SCI write observer of the evaluation that called `run` (root's
  ;; prepare-merge!) must see none of it, or it arms the function in a
  ;; context holding no such Var (merge-in-sci).
  (support/with-database
   (fn [connection]
     (let [ns-sym 'seon.test.request-acquisition-test]
       (support/transacted! connection [(support/namespace-entity (db/db connection) ns-sym)])
       (support/transacted!
        connection
        [(support/program-entity (db/db connection) [:seon.fn/sym 'seon.test.request-acquisition-test/plus-one]
                                 "(defn plus-one {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
         (support/program-entity (db/db connection) [:seon.test/sym 'seon.test.request-acquisition-test/uses-plus-one]
                                 "(clojure.test/deftest uses-plus-one (clojure.test/is (= 2 (seon.test.request-acquisition-test/plus-one 1))))")])
       (let [observed (atom [])
             result (binding [sci.lang/*write-observer* #(swap! observed conj (:op %))]
                      (sut/run {:seon.test/execution (support/execution-handle connection)
                                :seon.test/recording-connection connection
                                :seon.test/policy :named
                                :seon.test/identities #{'seon.test.request-acquisition-test/uses-plus-one}}))]
         (is (true? (:seon.test/passed? result)))
         (is (= [] @observed) "no member write reaches the caller's observer"))))))

(deftest acquiring-a-context-installs-its-program-unobserved-by-the-calling-evaluation
  ;; candidate-gate! acquires the candidate's execution on root's thread, inside
  ;; root's evaluation: a new namespace's definition is the acquired context's
  ;; own, never a write of the caller armed where no Var exists.
  (support/with-database
   (fn [connection]
     (let [ns-sym 'seon.test.request-acquisition-test]
       (support/transacted! connection [(support/namespace-entity (db/db connection) ns-sym)])
       (support/transacted!
        connection
        [(support/program-entity (db/db connection) [:seon.fn/sym 'seon.test.request-acquisition-test/plus-two]
                                 "(defn plus-two {:malli/schema [:=> [:cat :int] :int]} [x] (+ 2 x))")])
       (let [observed (atom [])
             handle (support/execution-handle connection)
             child (binding [sci.lang/*write-observer* #(swap! observed conj (:op %))]
                     ((requiring-resolve 'seon.cluster.agent/acquire-context!)
                      handle nil {:seon.agent/isolate? true}))]
         (try (is (some? (:seon.db/connection child)))
              (is (= [] @observed) "no install reaches the caller's observer")
              (finally ((requiring-resolve 'seon.cluster.agent/release-context!) child))))))))

(deftest acquisition-options-name-no-store-so-the-handles-own-store-decides
  ;; An option named :seon.store/store is a world member, so an agent's evaluation
  ;; supplied its file store over a test request's overlay: every fixture branch
  ;; refused "no branch or commit <overlay head> to branch from".
  (support/with-database
   (fn [connection]
     (let [projection (schema/projection-from-database (db/db connection))
           compiled (mr/schema (:seon.schema.projection/registry projection) :seon.agent/acquisition-options)
           declared (into #{} (map first) (m/children compiled))]
       (is (contains? declared :seon.agent/isolate?) "the probe reads the real options schema")
       (is (not (contains? declared :seon.store/store)))))))
