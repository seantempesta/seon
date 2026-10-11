(ns seon.test.seed-preparation-test
  (:require [clojure.java.io :as io] [clojure.string :as str] [clojure.test :refer [deftest is]]
            [seon.db :as db] [seon.fn :as seon.fn] [seon.program :as program] [seon.schema :as schema] [seon.schema.edn :as schema.edn]
            [seon.test :as sut] [seon.test-support :as support]))

(defn- run-seeded
  "Run one host probe Var carrying `seeds` as a member body on a fixture branch,
  reported under `test-symbol` when given (the probe's own symbol otherwise)."
  {:malli/schema
   [:function
    [:=> [:cat [:vector :seon.schema/value] [:=> [:cat] :seon.schema/value]] :seon.test.runner/captured-result]
    [:=> [:cat [:maybe :seon.test/sym] [:vector :seon.schema/value] [:=> [:cat] :seon.schema/value]] :seon.test.runner/captured-result]]}
  ([seeds body] (run-seeded nil seeds body))
  ([test-symbol seeds body]
    (support/with-database
     (fn [connection]
       (let [handle (support/execution-handle connection)
             owned (create-ns (gensym "seon.test.seed-preparation-test.probe-"))
             probe (intern owned (with-meta 'probe {:test body :seon.test/seed seeds}) nil)]
         ;; The body receives this member's custody, as `seon.test/member-result` supplies it.
         (try (db/call-without-custody
               #(#'sut/bounded-result (atom {}) {} (or test-symbol (symbol probe)) (constantly probe) 5000
                                      {:seon.db/connection connection :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.test/member handle}
                                      (fn [_] nil)))
              (finally (remove-ns (ns-name owned)))))))))

;; A seed entity is a complete data entity: `:seon.maintenance.result/entity` is its string identity alone.
(defn- reasons [database] (set (db/q '[:find [?r ...] :where [_ :seon.maintenance.result/id ?r]] database)))

(deftest seeds-prepare-the-member-world-in-order-before-the-body
  (let [seen (atom nil)
        result (run-seeded [{:seon.maintenance.result/id "seed-0"}
                            (fn [{database :seon.db/db}]
                              {:seon.test.seed/tx-data [{:seon.maintenance.result/id (str "saw " (count (filter #{"seed-0"} (reasons database))))}]})]
                           #(let [{connection :seon.db/connection commit :seon.source/commit-id} sut/*member*
                                  database (db/db connection)]
                              (reset! seen [(reasons database) (= commit (db/commit-id database))])))]
    (is (zero? (:seon.test/error-count result)))
    (is (every? (first @seen) ["seed-0" "saw 1"]) "each entry reads the database the previous one left")
    (is (true? (second @seen)) "fixtures branch from the prepared commit")))

(deftest a-host-body-and-its-held-handle-read-the-prepared-member
  (let [seen (atom nil)
        result (run-seeded [{:seon.maintenance.result/id "custody"}]
                           #(let [head (db/db)]
                              (reset! seen [(reasons head) (db/commit-id head)
                                            (:seon.source/commit-id (support/execution-handle db/*conn*))])))
        [seeded head held] @seen]
    (is (zero? (:seon.test/error-count result)))
    (is (contains? seeded "custody") "a bare (db/db) in a host body reads its seeded member branch")
    (is (= head held) "the handle held for that connection is the prepared one, so fixtures branch with the seeds")))

(deftest ^{:seon/tasks #{'seon.test/a-member-body-reads-the-world-its-seeds-prepared}}
  a-refused-seed-prevents-body-entry
  (doseq [[seeds rule] [[[{:seon.maintenance.result/id 42}] "Seed entry 0"]
                        [[{:seon.maintenance.result/id "ok"} (fn [_] {:seon.test.seed/tx-data "nope"})] "Seed entry 1"]]
          :let [entered (atom false) result (run-seeded seeds #(reset! entered true))]]
    (is (false? @entered))
    (is (str/includes? (str (:seon.test/failure-message result)) rule))))

(deftest literal-and-inline-seed-attributes-are-the-tests-keywords
  (let [root (io/file "tmp" "seed-preparation-test" (str (random-uuid)))
        file (io/file root "sample/seeded_test.clj")]
    (.mkdirs (.getParentFile file))
    (spit file (str "(ns sample.seeded-test (:require [clojure.test :refer [deftest is]]))\n(deftest ^{:seon.test/seed "
                    "[{:seon.test/skip-reason \"l\"} (fn [_] {:seon.test.seed/tx-data [{:seon.test/long-ms 1}]})]} seeded (is true))\n"))
    (try (is (every? (set (:seon.fn/keywords (first (filter :seon.test/sym (seon.fn/program-entities {:seon.fn/roots [(.getPath root)] :seon.schema/projection (schema/build-projection (schema.edn/packaged-forms))})))))
                     [:seon.test/skip-reason :seon.test/long-ms :seon.test.seed/tx-data]))
         (finally (run! io/delete-file (reverse (file-seq root)))))))

(deftest a-seed-that-redefines-its-own-test-refuses-by-name
  ;; The body would run the Var resolved before seeding, so a changed definition digest refuses.
  (let [test-symbol 'seon.test.seed-preparation-test/redefined-by-its-own-seed
        redefine (fn [{database :seon.db/db}]
                   (let [namespace-entity (let [entity {:seon.ns/name 'seon.test.seed-preparation-test
                                                  :seon.ns/source "(ns seon.test.seed-preparation-test (:require [clojure.test :refer [deftest is]]))"}]
                                         (assoc entity :seon.program/definition-digest (program/definition-digest entity)))
                         entities (seon.fn/source-rows database (program/shapes-in (schema/projection-from-database database))
                                                   namespace-entity "(deftest redefined-by-its-own-seed (is false))" #{})]
                     {:seon.test.seed/tx-data (seon.fn/reconcile-tx database (mapv #(support/revised-entity database % {}) entities) [])}))
        entered (atom false)
        result (run-seeded test-symbol [redefine] #(reset! entered true))]
    (is (false? @entered) "the pre-seed Var never runs")
    (is (str/includes? (str (:seon.test/failure-message result)) "change its own definition digest"))))
