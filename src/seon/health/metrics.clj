(ns seon.health.metrics
  "Body composition metrics: BMI computation and categorization."
  (:require [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::height-cm
                  [:double {:min 50.0 :max 300.0
                            :description "Height in centimeters"}])

(schema/register! ::weight-kg
                  [:double {:min 10.0 :max 500.0
                            :description "Weight in kilograms"}])

(schema/register! ::bmi
                  [:double {:min 1.0
                            :description "Body Mass Index value"}])

(schema/register! ::bmi-category
                  [:enum :underweight :normal :overweight :obese])

(schema/register! ::compute-bmi-request
                  [:map
                   [::height-cm ::height-cm]
                   [::weight-kg ::weight-kg]])

(schema/register! ::compute-bmi-response
                  [:map
                   [::bmi ::bmi]
                   [::bmi-category ::bmi-category]])

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- classify-bmi [bmi]
  (cond
    (< bmi 18.5) :underweight
    (< bmi 25.0) :normal
    (< bmi 30.0) :overweight
    :else         :obese))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn compute-bmi
  "Compute Body Mass Index from height and weight.

   Request keys:
     ::height-cm - Required. Height in centimeters (50-300)
     ::weight-kg - Required. Weight in kilograms (10-500)

   Response keys:
     ::bmi          - The computed BMI value
     ::bmi-category - Classification (:underweight :normal :overweight :obese)

   Example:
     (compute-bmi {::height-cm 175.0 ::weight-kg 70.0})
     ;; => {::bmi 22.86 ::bmi-category :normal}"
  {:malli/schema [:=> [:cat ::compute-bmi-request] ::compute-bmi-response]}
  [{::keys [height-cm weight-kg]}]
  (let [height-m (/ height-cm 100.0)
        bmi      (/ weight-kg (* height-m height-m))]
    {::bmi          (/ (Math/round (* bmi 100.0)) 100.0)
     ::bmi-category (classify-bmi bmi)}))
