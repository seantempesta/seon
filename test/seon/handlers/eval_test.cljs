(ns seon.handlers.eval-test
  (:require
    [cljs.test :refer [deftest is]]
    [clojure.string :as str]
    [seon.handlers.eval :as eval-handler]))

(def ^:private failed-eval
  {:seon.eval/id "eval-structured-message"
   :seon.eval/source "(broken)"
   :seon.eval/ok? false
   :seon.eval/error
   (str "literal :seon.error/message \"is ordinary guidance\"\n"
        "fix the actual input")})

(deftest stored-error-guidance-is-not-reparsed-as-an-envelope
  (let [ai (eval-handler/render-ai {:seon.render/node failed-eval})
        html (eval-handler/render-html {:seon.render/node failed-eval})
        rendered-html (pr-str html)]
    (is (str/includes? ai
                       "literal :seon.error/message \"is ordinary guidance\""))
    (is (str/includes? rendered-html
                       "literal :seon.error/message \\\"is ordinary guidance\\\""))
    (is (str/includes? rendered-html "fix the actual input"))))
