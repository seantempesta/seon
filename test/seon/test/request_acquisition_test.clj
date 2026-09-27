(ns seon.test.request-acquisition-test
  (:require [clojure.test :refer [deftest is]]
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
       (is (true? (:seon.test/passed? result)) (sut/tally result))
       (is (= 3 (:seon.test/executed-count result)))
       ;; The request acquires its program once; each member forks it and finds
       ;; that program by commit (29-35 ms measured) instead of re-acquiring it
       ;; (325-581 ms before, 2026-09-25).
       (is (every? #(< (+ (:seon.test.timing/acquire-ms %) (:seon.test.timing/resolve-ms %)) 50) timings)
           (pr-str (map #(select-keys % [:seon.test.timing/acquire-ms :seon.test.timing/resolve-ms]) timings)))
       (is (= 3 (count (set (map :seon.agent/branch timings)))) "every member ran on its own branch")
       (is (empty? (support/file-store-markers connection marker))
           "no member write reaches the execution branch")))))
