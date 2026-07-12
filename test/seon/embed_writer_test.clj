(ns seon.embed-writer-test
  "Token-reporting contract for the optional JVM embedding writer."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [seon.ai.tokens :as tokens]
   [seon.embed :as embed]
   [taoensso.timbre :as log]))

(deftest oversized-input-log-reports-only-canonical-token-estimates
  (let [events    (atom [])
        source    (apply str (repeat (+ (tokens/estimate-chars
                                         embed/max-text-tokens)
                                        8)
                                     "x"))
        result    (atom nil)
        config    (assoc log/default-config
                         :appenders
                         {:capture {:enabled? true
                                    :async?   false
                                    :fn       #(swap! events conj %)}})]
    (log/with-config config
      (reset! result (#'embed/truncate-to-token-cap source)))
    (let [event    (first @events)
          vargs    (vec (:vargs event))
          reported (filterv number? vargs)
          rendered (str/join " " vargs)]
      (is (= embed/max-text-tokens (tokens/estimate @result))
          "the internal substring boundary honors the model token cap")
      (is (= [(tokens/estimate source) (tokens/estimate @result)] reported)
          "the log's before/after values come from the canonical estimator")
      (is (not (re-find #"(?i)\d+\s*(?:chars?|characters?|bytes?|[kmg]b)\b"
                        rendered))
          "the generated log does not expose raw text-size units"))))
