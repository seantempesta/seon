(ns seon.agent.ctx.usage-test
  "Provider usage normalization and compact turn projections."
  (:require
    [cljs.test :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.agent.ctx.usage :as usage]))

(deftest openai-compatible-live-shapes-normalize-with-honest-cache-data
  (testing "DeepSeek's two cache fields must agree"
    (is (= {::usage/total 9100
            ::usage/cached 8500
            ::usage/output 220
            ::usage/provider-shape :openai-compat}
           (usage/extract
             (pr-str {:prompt_tokens 9100
                      :completion_tokens 220
                      :prompt_cache_hit_tokens 8500
                      :prompt_tokens_details {:cached_tokens 8500}}))))
    (is (nil? (usage/extract
                (pr-str {:prompt_tokens 9100
                         :completion_tokens 220
                         :prompt_cache_hit_tokens 8400
                         :prompt_tokens_details {:cached_tokens 8500}}))))
    (is (str/includes?
          (::usage/diagnostic
            (usage/analyze
              (pr-str {:prompt_tokens 9100
                       :completion_tokens 220
                       :prompt_cache_hit_tokens 8400
                       :prompt_tokens_details {:cached_tokens 8500}})))
          "disagree")))
  (testing "Muse/Meta reports only the nested OpenAI cache field"
    (is (= {::usage/total 9000
            ::usage/cached 8400
            ::usage/output 200
            ::usage/provider-shape :openai-compat}
           (usage/extract
             (pr-str {:prompt_tokens 9000
                      :completion_tokens 200
                      :prompt_tokens_details {:cached_tokens 8400}}))))))

(deftest anthropic-total-adds-uncached-cache-read-and-cache-creation
  (is (= {::usage/total 760
          ::usage/cached 600
          ::usage/output 45
          ::usage/provider-shape :anthropic}
         (usage/extract
           (pr-str {:input_tokens 100
                    :cache_read_input_tokens 600
                    :cache_creation_input_tokens 60
                    :output_tokens 45})))))

(deftest estimated-openai-shape-omits-cache-and-is-never-framed-as-actual
  (let [turn {:seon.agent.turn/llm-usage
              (pr-str {:prompt_tokens 120 :completion_tokens 8 :total_tokens 128})
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

(deftest malformed-unknown-and-negative-usage-never-become-zeroes
  (doseq [[raw expected]
          [["not-edn" "Malformed usage EDN"]
           [(pr-str {:unexpected/count 4}) "Unknown usage shape"]
           [(pr-str {:prompt_tokens -1 :completion_tokens 3})
            "non-negative integer"]
           [(pr-str {:input_tokens 2 :output_tokens "3"})
            "non-negative integer"]]]
    (let [analysis (usage/analyze raw)]
      (is (nil? (::usage/usage analysis)))
      (is (str/includes? (::usage/diagnostic analysis) expected))))
  (is (= {} (usage/turn-projection {}))
      "a turn without provider usage produces no plausible projection"))
