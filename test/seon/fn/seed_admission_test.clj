(ns seon.fn.seed-admission-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

(defn- with-seeded-root
  "Run `body` on a source root whose one deftest carries `seeds`, then delete it."
  [seeds body]
  (let [root (io/file "tmp" "seed-admission-test" (str (random-uuid)))
        file (io/file root "sample/seeded_test.clj")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str "(ns sample.seeded-test\n  (:require [clojure.test :refer [deftest is]]))\n"
               "(defn week\n  {:malli/schema [:=> [:cat :seon.test.seed/input] :seon.test.seed/value]}\n"
               "  [{database :seon.db/db}]\n"
               "  {:seon.test.seed/tx-data [{:seon.test/skip-reason (str database)}]})\n"
               "(defn helper [x] (str x))\n"
               "(defn plain [x] x)\n"
               "(defn wrong {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
               "(deftest ^{:seon.test/seed " seeds "} seeded (is true))\n"))
    (try
      (body (.getPath root))
      (finally
        (run! io/delete-file (reverse (file-seq root)))))))

(defn- seeded-row [rows]
  (some #(when (= 'sample.seeded-test/seeded (:seon.test/sym %)) %) rows))

(defn- packaged-projection []
  (schema/build-projection (schema.edn/packaged-forms)))

(defn- refusal [projection seeds]
  (with-seeded-root
    seeds
    (fn [root]
      (try
        (seon.fn/rows {:seon.fn/roots [root] :seon.schema/projection projection})
        nil
        (catch clojure.lang.ExceptionInfo failure
          (select-keys (ex-data failure)
                       [:seon.test.seed/refused :seon.test/sym :seon.test.seed/ordinal
                        :seon.test.seed/function :seon.schema/key :seon.error/offending]))))))

(def ^:private three-forms
  (str "[{:seon.test/skip-reason \"literal\"}\n"
       " sample.seeded-test/week\n"
       " (fn [{database :seon.db/db}]\n"
       "   {:seon.test.seed/tx-data [{:seon.test/skip-reason (helper database)}]})]"))

(def ^:private stored-in-order
  [{:seon.test.seed/ordinal 0 :seon.test.seed/writes #{:seon.test/skip-reason}}
   {:seon.test.seed/ordinal 1 :seon.test.seed/function 'sample.seeded-test/week}
   {:seon.test.seed/ordinal 2}])

(deftest seed-entries-of-all-three-forms-are-admitted-and-stored-in-order
  (with-seeded-root
    three-forms
    (fn [root]
      (let [rows (seon.fn/rows {:seon.fn/roots [root] :seon.schema/projection (packaged-projection)})
            seeded (seeded-row rows)]
        (testing "the analyzer reads the ordered vector into owned occurrences"
          (is (= stored-in-order (:seon.test/seed seeded)))
          ;; WANTED: a named seed is a CALL edge of its test, so changing the
          ;; seed function selects the test (selection follows `:seon.fn/calls`
          ;; only since 20a87b1d1; an unquoted symbol is exercised, not named).
          (is (contains? (:seon.fn/calls seeded) 'sample.seeded-test/week)
              "a named seed is a call-reach edge of its test"))
        (testing "the canonical writer stores the occurrences; ordinals rebuild the vector"
          (test-support/with-database
           (fn [connection]
             (test-support/transacted! connection (seon.fn/reconcile-tx (db/db connection) rows []))
             (let [pulled (db/pull (db/db connection)
                                   [{:seon.test/seed [:seon.test.seed/ordinal :seon.test.seed/function
                                                      :seon.test.seed/writes]}]
                                   [:seon.test/sym 'sample.seeded-test/seeded])]
               (is (= stored-in-order
                      (->> (:seon.test/seed pulled)
                           (map (fn [occurrence]
                                  (update-vals occurrence #(if (sequential? %) (set %) %))))
                           (sort-by :seon.test.seed/ordinal)
                           vec)))))))))))

(deftest an-empty-seed-vector-adds-no-occurrences
  (with-seeded-root
    "[]"
    (fn [root]
      (is (not (contains? (seeded-row (seon.fn/rows {:seon.fn/roots [root]
                                                     :seon.schema/projection (packaged-projection)}))
                          :seon.test/seed))))))

(deftest seed-admission-refuses-each-broken-entry-by-name
  (let [projection (packaged-projection)
        refused (partial refusal projection)]
    (is (= {:seon.test.seed/refused :missing-function :seon.test/sym 'sample.seeded-test/seeded
            :seon.test.seed/ordinal 1 :seon.test.seed/function 'sample.seeded-test/absent}
           (refused "[{:seon.test/skip-reason \"ok\"} sample.seeded-test/absent]")))
    (is (= :uncontracted-function
           (:seon.test.seed/refused (refused "[sample.seeded-test/plain]"))))
    (is (= :contract-mismatch
           (:seon.test.seed/refused (refused "[sample.seeded-test/wrong]"))))
    (is (= {:seon.test.seed/refused :unqualified-symbol :seon.test/sym 'sample.seeded-test/seeded
            :seon.test.seed/ordinal 0 :seon.error/offending 'week}
           (refused "[week]")))
    (is (= {:seon.test.seed/refused :invalid-attribute-value :seon.test/sym 'sample.seeded-test/seeded
            :seon.test.seed/ordinal 0 :seon.schema/key :seon.test/skip-reason :seon.error/offending 42}
           (refused "[{:seon.test/skip-reason 42}]")))
    (is (= {:seon.test.seed/refused :undeclared-attribute :seon.test/sym 'sample.seeded-test/seeded
            :seon.test.seed/ordinal 0 :seon.schema/key :sample.nothing/here}
           (refused "[[{:sample.nothing/here 1}]]")))))

(deftest an-sci-deftest-settles-with-its-seed-attributes-as-keywords
  ;; An SCI deftest's row comes from `source-rows`; its settlement re-analysis
  ;; (`analyze-form`) must keep the seed's schema edges, not only kondo's holders.
  (let [database (db/db)
        namespace-row (let [row {:seon.ns/name 'seon.fn.seed-admission-test
                                 :seon.ns/source "(ns seon.fn.seed-admission-test (:require [clojure.test :refer [deftest is]]))"}]
                        (assoc row :seon.program/definition-digest (seon.program/definition-digest row)))
        source "(deftest ^{:seon.test/seed [{:seon.test/skip-reason \"sci\"}]} evaluated (is true))"
        [row] (seon.fn/source-rows database (seon.program/shapes-in (schema/projection-from-database database))
                                   namespace-row source #{})
        [_ settled] (seon.fn/analyze-form database source
                                          [:seon.ns/name 'seon.fn.seed-admission-test] row)]
    (is (= [{:seon.test.seed/ordinal 0 :seon.test.seed/writes #{:seon.test/skip-reason}}]
           (:seon.test/seed row) (:seon.test/seed settled)))
    (is (contains? (:seon.fn/keywords row) :seon.test/skip-reason))
    (is (contains? (:seon.fn/keywords settled) :seon.test/skip-reason))))
