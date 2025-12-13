;; Auto-test hook verification - testing from main agent
(ns ml-options.data.date-utils-test
  "Tests for date utility functions in ml-options.data.date-utils."
  (:require [clojure.test :refer [deftest is testing]]
            [ml-options.data.date-utils :as sut])
  (:import [java.time LocalDate Instant ZoneId]))

(deftest local-date->eod-instant-test
  (testing "converts LocalDate to 5pm ET Instant"
    (let [date (LocalDate/of 2024 1 15)  ; Winter (EST)
          inst (sut/local-date->eod-instant date)]
      (is (instance? Instant inst))
      ;; 5pm EST = 22:00 UTC
      (is (= "2024-01-15T22:00:00Z" (str inst)))))

  (testing "handles EDT (summer time) correctly"
    (let [date (LocalDate/of 2024 7 15)  ; Summer (EDT)
          inst (sut/local-date->eod-instant date)]
      ;; 5pm EDT = 21:00 UTC
      (is (= "2024-07-15T21:00:00Z" (str inst))))))

(deftest instant->local-date-test
  (testing "converts Instant to LocalDate in ET timezone"
    (let [inst (Instant/parse "2024-01-15T22:00:00Z")  ; 5pm EST
          date (sut/instant->local-date inst)]
      (is (instance? LocalDate date))
      (is (= (LocalDate/of 2024 1 15) date))))

  (testing "handles date boundary correctly"
    ;; 2am UTC on Jan 16 = 9pm EST on Jan 15
    (let [inst (Instant/parse "2024-01-16T02:00:00Z")
          date (sut/instant->local-date inst)]
      (is (= (LocalDate/of 2024 1 15) date)))))

(deftest weekend?-test
  (testing "returns true for Saturday"
    (is (true? (sut/weekend? (LocalDate/of 2024 1 13)))))  ; Saturday

  (testing "returns true for Sunday"
    (is (true? (sut/weekend? (LocalDate/of 2024 1 14)))))  ; Sunday

  (testing "returns false for weekdays"
    (is (false? (sut/weekend? (LocalDate/of 2024 1 15))))  ; Monday
    (is (false? (sut/weekend? (LocalDate/of 2024 1 16))))  ; Tuesday
    (is (false? (sut/weekend? (LocalDate/of 2024 1 17))))  ; Wednesday
    (is (false? (sut/weekend? (LocalDate/of 2024 1 18))))  ; Thursday
    (is (false? (sut/weekend? (LocalDate/of 2024 1 19)))))) ; Friday
