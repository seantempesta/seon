(ns seon.test-provenance-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test :as seon-test]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(defn completion
  ([database passes] (completion database passes 'provenance.example/check))
  ([database passes test-symbol]
  (let [run (runner/provenance database)]
    {:seon.test.runner/results
     [{:seon.test/sym test-symbol
       :seon.test/pass-count passes
       :seon.test/fail-count (if (pos? passes) 0 1)
       :seon.test/error-count 0}]
     :seon.test/run-basis-t (:seon.test.run/basis-t run)
     :seon.test/run-at (:seon.test.run/at run)
     :seon.test.run/provenance run})))

(deftest runs-are-distinct-immutable-and-linked-atomically
  (support/with-database
    (fn [connection]
      (let [captured (completion @connection 1)
            first-run (:seon.test.run/provenance captured)
            stored (runner/commit-results! connection captured)
            again (assoc-in captured [:seon.test.run/provenance :seon.test.run/id]
                            "second-provenance-run")
            second-stored (runner/commit-results! connection again)
            runs (db/q '[:find [?id ...] :where [_ :seon.test.run/id ?id]] @connection)]
        (is (= 1 (:seon.test/pass-count (first stored))) (pr-str stored))
        (is (= 1 (:seon.test/pass-count (first second-stored))) (pr-str second-stored))
        (is (= #{(:seon.test.run/id first-run) "second-provenance-run"} (set runs)))
        (is (nil? (get-in @connection [:schema :seon.test.run/program-digest :db/unique]))
            "many runs may test the same program")
        (is (= first-run
               (dissoc (db/pull @connection '[*]
                                [:seon.test.run/id (:seon.test.run/id first-run)]) :db/id)))
        (is (= "second-provenance-run"
               (get-in (db/pull @connection
                                '[{:seon.test/run [:seon.test.run/id]}]
                                [:seon.test/sym 'provenance.example/check])
                       [:seon.test/run :seon.test.run/id])))
        (let [basis (db/basis-t @connection)
              refusal (support/refusal-data
                       #(runner/commit-results!
                         connection
                         (assoc-in captured
                                   [:seon.test.run/provenance :seon.test.run/program-digest]
                                   (apply str (repeat 64 "b")))))]
          (is (not= support/committed refusal))
          (is (= basis (db/basis-t @connection)) "the refused write is atomic"))))))

(deftest ^{:seon.test/long "The admitted source change derives the digest once: 11.6 s for the member on a scratch cluster at 04da53689, whose adoption rewrote 1,797 program rows since the seal (derivation 4.7 s measured at the REPL; the derivation reads every program row changed since the seal, filed in lane-realities-commit-5-2026-09-23.md)."
           :seon.test/long-ms 20000}
  program-identity-excludes-results-and-includes-admitted-source
  ;; The digest is a function of the source seal and the program rows,
  ;; memoized by the program attributes' revisions: a result commit reads the
  ;; digest already derived (it was derived three times per request, 1,529 ms,
  ;; and 2,279 ms on a new branch; 2026-09-23 cache audit).
  (support/with-database
    (fn [connection]
      ;; An indexed test the fixture branch carries: the recorder refuses a
      ;; completion for a symbol with no surviving test definition.
      (let [s 'seon.test-provenance-test/verified-requires-the-subject-positive-assertions-and-exact-program
            before (runner/program-digest @connection)
            captured (completion @connection 1 s)
            recorded (runner/commit-results! connection captured)
            started (System/nanoTime)
            after-results (runner/program-digest @connection)
            held-ms (/ (- (System/nanoTime) started) 1e6)]
        (is (vector? recorded) (pr-str recorded))
        (is (= before after-results) "result writes are not program identity")
        (is (< held-ms 100) (str "a result commit reads the held digest: " held-ms " ms"))
        (is (= before (:seon.test.run/program-digest (:seon.test.run/provenance captured))))
        (is (:db-after
             (db/transact! connection
                           [[:db/add [:seon.test/sym s]
                             :seon.test/source "(deftest check (is false))"]])))
        (is (not= before (runner/program-digest @connection))
            "an admitted source change is program identity")))))

(deftest verified-requires-the-subject-positive-assertions-and-exact-program
  (support/with-database
    (fn [connection]
      (let [symbol 'provenance.example/check
            digest (runner/program-digest @connection)]
        (is (false? (seon-test/verified? @connection symbol digest)))
        (support/transacted! connection
                             [{:seon.test/sym symbol
                               :seon.schema.admission/source :core
                               :seon.test/source "(deftest check (is true))"}])
        (let [captured (completion @connection 1)
              digest (get-in captured [:seon.test.run/provenance :seon.test.run/program-digest])]
          (is (false? (seon-test/verified? @connection symbol digest)))
          (runner/commit-results! connection captured)
          (is (true? (seon-test/verified? @connection symbol digest)))
          (is (false? (seon-test/verified? @connection symbol (apply str (repeat 64 "c")))))
          (is (:db-after (db/transact! connection
                                      [[:db/add [:seon.test/sym symbol] :seon.test/pass-count 0]])))
          (is (false? (seon-test/verified? @connection symbol digest)))
          (is (:db-after (db/transact! connection
                                      [[:db/add [:seon.test/sym symbol] :seon.test/pass-count 1]
                                       [:db/add [:seon.test/sym symbol] :seon.test/error-count 1]])))
          (is (false? (seon-test/verified? @connection symbol digest))))
        (let [refused {:seon.db/invalid-read true :seon.error/at (java.util.Date.) :seon.error/layer :seon.db/invocation :seon.error/operation 'seon.db/q
                       :seon.error/message "injected query refusal"}]
          (with-redefs [db/q (fn [& _] refused)]
            (is (= refused (seon-test/verified? @connection symbol digest)))))))))
