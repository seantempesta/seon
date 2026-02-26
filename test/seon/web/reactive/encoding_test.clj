(ns seon.web.reactive.encoding-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.web.reactive.encoding :as enc]))

(deftest kebab-camel-round-trip-test
  (testing "kebab->camel conversions"
    (is (= "gettingStarted" (enc/kebab->camel "getting-started")))
    (is (= "userInput" (enc/kebab->camel "user-input")))
    (is (= "exercise" (enc/kebab->camel "exercise")))
    (is (= "seon" (enc/kebab->camel "seon"))))

  (testing "camel->kebab conversions"
    (is (= "getting-started" (enc/camel->kebab "gettingStarted")))
    (is (= "user-input" (enc/camel->kebab "userInput")))
    (is (= "exercise" (enc/camel->kebab "exercise")))
    (is (= "seon" (enc/camel->kebab "seon"))))

  (testing "round-trip: kebab -> camel -> kebab"
    (doseq [s ["getting-started" "user-input" "exercise" "foo-bar-baz"]]
      (is (= s (enc/camel->kebab (enc/kebab->camel s)))
          (str "round-trip failed for: " s)))))

(deftest encode-keyword-test
  (testing "qualified keywords with hyphenated namespace"
    (is (= "seon.gettingStarted.exercise"
           (enc/encode-keyword :seon.getting-started/exercise))))

  (testing "qualified keywords without hyphens"
    (is (= "seon.ctx.userInput"
           (enc/encode-keyword :seon.ctx/user-input))))

  (testing "deeply nested namespace"
    (is (= "seon.health.workout.exercise"
           (enc/encode-keyword :seon.health.workout/exercise))))

  (testing "unqualified keywords"
    (is (= "exercise" (enc/encode-keyword :exercise)))
    (is (= "userInput" (enc/encode-keyword :user-input)))))

(deftest decode-signals-test
  (testing "nested JSON from Datastar"
    (is (= {:seon.getting-started/exercise "Pull-up"}
           (enc/decode-signals {"seon" {"gettingStarted" {"exercise" "Pull-up"}}}))))

  (testing "flat camelCase keys"
    (is (= {:user-input "hello"}
           (enc/decode-signals {"userInput" "hello"}))))

  (testing "multiple namespaces in one body"
    (is (= {:seon.getting-started/exercise "Pull-up"
            :seon.ctx/user-input "hello"}
           (enc/decode-signals
            {"seon" {"gettingStarted" {"exercise" "Pull-up"}
                     "ctx" {"userInput" "hello"}}}))))

  (testing "nil and empty inputs"
    (is (= {} (enc/decode-signals nil)))
    (is (= {} (enc/decode-signals {}))))

  (testing "numeric and boolean values preserved"
    (is (= {:seon.getting-started/sets 3
            :seon.getting-started/reps 10}
           (enc/decode-signals
            {"seon" {"gettingStarted" {"sets" 3 "reps" 10}}})))))

(deftest encode-decode-round-trip-test
  (testing "encode keyword -> build nested -> decode = identity"
    (doseq [kw [:seon.getting-started/exercise
                :seon.ctx/user-input
                :seon.health.workout/total-volume
                :exercise]]
      (let [path (enc/encode-keyword kw)
            ;; Simulate what Datastar does: signal path -> nested JSON
            segments (str/split path #"\.")
            nested (reduce (fn [acc seg] {seg acc})
                           "test-value"
                           (reverse segments))
            decoded (enc/decode-signals nested)]
        (is (= kw (ffirst decoded))
            (str "round-trip failed for: " kw))))))

(deftest encode-signals-json-test
  (testing "single qualified keyword"
    (let [json-str (enc/encode-signals-json {:seon.getting-started/exercise ""})]
      (is (str/includes? json-str "gettingStarted"))
      (is (str/includes? json-str "exercise"))))

  (testing "multiple keywords from different namespaces"
    (let [json-str (enc/encode-signals-json
                    {:seon.getting-started/exercise ""
                     :seon.ctx/user-input ""})]
      (is (str/includes? json-str "gettingStarted"))
      (is (str/includes? json-str "ctx"))
      (is (str/includes? json-str "userInput"))))

  (testing "unqualified keywords"
    (let [json-str (enc/encode-signals-json {:exercise ""})]
      (is (= "{\"exercise\":\"\"}" json-str)))))
