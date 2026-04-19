(ns seon.health.metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.health.metrics :as metrics]))

(deftest compute-bmi-example-test
  (testing "Normal BMI for 175cm, 70kg"
    (let [result (metrics/compute-bmi {::metrics/height-cm 175.0
                                       ::metrics/weight-kg 70.0})]
      (is (= 22.86 (::metrics/bmi result)))
      (is (= :normal (::metrics/bmi-category result)))))

  (testing "Underweight BMI"
    (let [result (metrics/compute-bmi {::metrics/height-cm 180.0
                                       ::metrics/weight-kg 55.0})]
      (is (= :underweight (::metrics/bmi-category result)))))

  (testing "Overweight BMI"
    (let [result (metrics/compute-bmi {::metrics/height-cm 170.0
                                       ::metrics/weight-kg 80.0})]
      (is (= :overweight (::metrics/bmi-category result)))))

  (testing "Obese BMI"
    (let [result (metrics/compute-bmi {::metrics/height-cm 165.0
                                       ::metrics/weight-kg 100.0})]
      (is (= :obese (::metrics/bmi-category result))))))

(deftest compute-bmi-boundary-test
  (testing "Exact boundary at 18.5 is normal"
    ;; 18.5 = weight / (height_m^2) => weight = 18.5 * 1.0^2 = 18.5 at 100cm
    (let [result (metrics/compute-bmi {::metrics/height-cm 100.0
                                       ::metrics/weight-kg 18.5})]
      (is (= :normal (::metrics/bmi-category result)))))

  (testing "Exact boundary at 25.0 is overweight"
    (let [result (metrics/compute-bmi {::metrics/height-cm 100.0
                                       ::metrics/weight-kg 25.0})]
      (is (= :overweight (::metrics/bmi-category result)))))

  (testing "Exact boundary at 30.0 is obese"
    (let [result (metrics/compute-bmi {::metrics/height-cm 100.0
                                       ::metrics/weight-kg 30.0})]
      (is (= :obese (::metrics/bmi-category result))))))

(deftest compute-bmi-generative-test
  (testing "All valid inputs produce valid responses"
    (doseq [request (mg/sample ::metrics/compute-bmi-request {:size 50})]
      (let [response (metrics/compute-bmi request)]
        (is (m/validate ::metrics/compute-bmi-response response)
            (str "Invalid response for input: " request))))))

(deftest schema-registration-test
  (testing "Schemas are registered"
    (is (m/validate ::metrics/height-cm 175.0))
    (is (not (m/validate ::metrics/height-cm -1.0)))
    (is (m/validate ::metrics/bmi-category :normal))
    (is (not (m/validate ::metrics/bmi-category :invalid)))))
