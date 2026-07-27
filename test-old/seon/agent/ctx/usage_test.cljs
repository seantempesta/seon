(ns seon.agent.ctx.usage-test
  "Provider usage normalization and compact turn projections."
  (:require
    [cljs.test :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.agent.ctx.usage :as usage]))

(deftest openai-compatible-named-attributes-normalize-with-cache-data
  (testing "DeepSeek's validated cache projection"
    (is (= {::usage/total 9100
            ::usage/cached 8500
            ::usage/output 220
            ::usage/provider-shape :openai-compat}
           (usage/extract
            {:seon.agent.turn.usage/prompt-tokens 9100
             :seon.agent.turn.usage/completion-tokens 220
             :seon.agent.turn.usage/cached-tokens 8500}))))
  (testing "Muse/Meta reports only the nested OpenAI cache field"
    (is (= {::usage/total 9000
            ::usage/cached 8400
            ::usage/output 200
            ::usage/provider-shape :openai-compat}
           (usage/extract
            {:seon.agent.turn.usage/prompt-tokens 9000
             :seon.agent.turn.usage/completion-tokens 200
             :seon.agent.turn.usage/cached-tokens 8400})))))

(deftest anthropic-total-adds-uncached-cache-read-and-cache-creation
  (is (= {::usage/total 760
          ::usage/cached 600
          ::usage/output 45
          ::usage/provider-shape :anthropic}
         (usage/extract
          {:seon.agent.turn.usage/input-tokens 100
           :seon.agent.turn.usage/cache-read-input-tokens 600
           :seon.agent.turn.usage/cache-creation-input-tokens 60
           :seon.agent.turn.usage/output-tokens 45}))))

(deftest estimated-openai-shape-omits-cache-and-is-never-framed-as-actual
  (let [turn {:seon.agent.turn.usage/prompt-tokens 120
              :seon.agent.turn.usage/completion-tokens 8
              :seon.agent.turn/usage-estimated? true}
        {normalized ::usage/usage line ::usage/line}
        (usage/turn-projection turn)]
    (is (= {::usage/total 120
            ::usage/output 8
            ::usage/provider-shape :openai-compat}
           normalized))
    (is (not (contains? normalized ::usage/cached)))
    (is (str/includes? line "est. (stream abort)"))
    (is (str/includes? line "no cache data"))))

(deftest unknown-and-invalid-usage-never-become-zeroes
  (doseq [[turn expected]
          [[{:unexpected/count 4} "Unknown usage shape"]
           [{:seon.agent.turn.usage/prompt-tokens -1
             :seon.agent.turn.usage/completion-tokens 3}
            "non-negative integer"]
           [{:seon.agent.turn.usage/input-tokens 2
             :seon.agent.turn.usage/output-tokens -3}
            "non-negative integer"]]]
    (let [analysis (usage/analyze turn)]
      (is (nil? (::usage/usage analysis)))
      (is (str/includes? (::usage/diagnostic analysis) expected))))
  (is (= {} (usage/turn-projection {}))
      "a turn without provider usage produces no plausible projection"))
