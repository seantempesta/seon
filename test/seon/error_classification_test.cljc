(ns seon.error-classification-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [seon.error :as error]))

(defn- projection [source-admissions artifact-exports]
  {:seon.schema.projection/function-source-admissions source-admissions
   :seon.schema.projection/artifact-exports artifact-exports})

(deftest authorship-is-derived-from-source-provenance-and-artifact-exports
  (let [core {:seon.schema.admission/source :core}
        agent {:seon.schema.admission/source :agent}]
    (testing "a corpus source row decides before artifact membership"
      (is (= :core
             (error/fault-for
              'my.plan.internal/plan-html
              (projection {'my.plan.internal/plan-html core} #{}))))
      (is (= :agent
             (error/fault-for
              'seon.looks.core/hostile
              (projection {'seon.looks.core/hostile agent}
                          #{'seon.looks.core/hostile})))))
    (testing "an artifact-only compiled terminal is core"
      (is (= :core
             (error/fault-for
              'my.compiled/renderer
              (projection {} #{'my.compiled/renderer})))))
    (testing "unknown symbols fail closed"
      (is (= :agent
             (error/fault-for 'seon.unknown/function (projection {} #{}))))
      (is (= :agent
             (error/fault-for 'unqualified (projection {} #{})))))))
