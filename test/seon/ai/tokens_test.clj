(ns seon.ai.tokens-test
  "Recurring acceptance for the one token sizer and its calibration."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai.tokens :as tokens]))

(def ^:private budget 32768)

(def ^:private observed-usage
  "Exact prompt characters against DeepSeek's own `prompt_tokens` for the
  same run, measured on cluster `default` 2026-08-08 and recorded in
  `docs/prds/sci-execution-runtime/research/whole-system-arc-observer-2026-08-08.md`
  section (e2). These are the facts a live cluster commits per attempt;
  here they stand in for them so the class has a runner-visible proof."
  [[53137 16772] [56073 17695] [63538 19811] [108032 33476]
   [115415 35453] [116572 35827] [54026 16812] [53864 16778]
   [54260 16900] [32786 9496]])

(defn- observations
  [pairs]
  (mapv (fn [[characters provider-tokens]]
          {:seon.ai.tokens/characters characters
           :seon.ai.usage/prompt-tokens provider-tokens})
        pairs))

(defn- text-of [characters] (str/join (repeat characters "x")))

(deftest run7-late-prompts-are-predicted-from-prior-attempts
  (let [evidence (edn/read-string (slurp "test/seon/fixtures/run7_token_observations.edn"))
        observations (:seon.ai.tokens/observations evidence)]
    (is (= 30 (count observations)) "missing captures cannot pass")
    (is (= 244933 (reduce + (map :seon.ai.usage/prompt-tokens observations))))
    (doseq [position (range (- (count observations) 10) (count observations))]
      (let [observation (nth observations position)
            calibration (tokens/recent-calibration (subvec observations 0 position)
                                                   10 tokens/shipped-calibration)
            billed (:seon.ai.usage/prompt-tokens observation)
            estimated (tokens/estimate-of-characters (:seon.ai.tokens/characters observation)
                                                     calibration)
            relative-error (/ (abs (- estimated billed)) (double billed))]
        (is (= 10 (:seon.ai.tokens/sample-count calibration)))
        (is (< relative-error 0.05)
            (str (:seon.ai.attempt/id observation) ": estimated " estimated
                 ", billed " billed ", relative error " relative-error))))))

(deftest token-budget-derivations-share-one-character-ratio
  (is (= 9 (tokens/estimate-chars 3)))
  (testing "a character count sizes through the same owner as a string"
    (is (= (tokens/estimate (text-of 4096))
           (tokens/estimate-of-characters 4096)))))

(deftest a-first-turn-estimate-uses-the-measured-prior-without-a-band
  (testing "the fallback path — no usage recorded for this model yet"
    (let [calibration (tokens/calibrate [])]
      (is (= tokens/shipped-calibration calibration))
      (is (= :seon.ai.tokens/shipped-prior
             (:seon.ai.tokens/basis calibration)))
      (is (= 17 (:seon.ai.tokens/sample-count calibration)))
      (testing "no invented error band: with no observations it is unknown"
        (is (not (contains? calibration :seon.ai.tokens/relative-error))))
      (let [provider-tokens 10766
            report (tokens/budget-report (text-of 34798) budget calibration)
            miss (/ (abs (- (double (:seon.ai.tokens/estimated report))
                            provider-tokens))
                    provider-tokens)]
        (is (= 10874 (:seon.ai.tokens/estimated report)))
        (is (< miss 0.02) "the shipped prior corrects the measured 19.2% miss")
        (is (= :seon.ai.tokens/within (:seon.ai.tokens/verdict report))
            "the measured first-turn prompt is well within the budget")
        (is (not (contains? report :seon.ai.tokens/upper-bound)))
        (is (str/includes? (tokens/report-sentence report) "measured prior")
            "the first-turn number must name its evidence basis")))))

(deftest a-calibrated-estimate-predicts-the-provider-within-its-own-band
  (let [calibration (tokens/calibrate (observations observed-usage))]
    (is (= :seon.ai.tokens/observed (:seon.ai.tokens/basis calibration)))
    (is (= 10 (:seon.ai.tokens/sample-count calibration)))
    (testing "the fitted ratio is the recorded one, not the shipped 4"
      (is (< 3.0 (:seon.ai.tokens/chars-per-token calibration) 3.5)))
    (testing "every observation is predicted within the reported band"
      (let [band (:seon.ai.tokens/relative-error calibration)]
        (is (pos? band))
        (doseq [[characters provider-tokens] observed-usage]
          (let [estimated (tokens/estimate-of-characters characters
                                                         calibration)
                miss (/ (abs (- (double estimated) provider-tokens))
                        (double provider-tokens))]
            (is (<= miss band)
                (str characters " characters estimated at " estimated
                     " against a recorded " provider-tokens))))))))

(deftest a-prompt-over-the-real-budget-is-refused-not-sent
  (testing "THE CLASS: chars/4 admitted every prompt the provider counted
  over budget. A calibrated estimate refuses exactly those."
    (let [calibration (tokens/calibrate (observations observed-usage))]
      (doseq [[characters provider-tokens] observed-usage]
        (let [text (text-of characters)
              calibrated (tokens/budget-report text budget calibration)
              prior (tokens/budget-report text budget
                                          tokens/shipped-calibration)
              really-over? (> provider-tokens budget)]
          (is (= really-over?
                 (= :seon.ai.tokens/over (:seon.ai.tokens/verdict prior)))
              "the shipped prior agrees with the recorded budget verdict")
          (is (= really-over?
                 (= :seon.ai.tokens/over (:seon.ai.tokens/verdict calibrated)))
              (str characters " characters really cost " provider-tokens
                   " tokens; the calibrated verdict must agree with the"
                   " budget about that")))))))

(deftest an-estimate-that-fits-only-within-the-band-is-loud
  (testing "a point estimate under budget whose own measured error reaches
  past it is near-limit, never a silent admission"
    (let [calibration (tokens/calibrate (observations observed-usage))
          ratio (:seon.ai.tokens/chars-per-token calibration)
          band (:seon.ai.tokens/relative-error calibration)
          ;; sized so the estimate lands just under the budget while the
          ;; band reaches past it
          characters (long (* ratio (- budget 10)))
          report (tokens/budget-report (text-of characters) budget
                                       calibration)]
      (is (< (:seon.ai.tokens/estimated report) budget))
      (is (> (:seon.ai.tokens/upper-bound report) budget))
      (is (= :seon.ai.tokens/near-limit (:seon.ai.tokens/verdict report)))
      (is (str/includes? (tokens/report-sentence report) "worst observed miss")
          "the note must carry the margin, not only the point estimate")
      (is (pos? band)))))
