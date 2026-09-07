(ns seon.repl-test
  "The REPL response grammar: what an agent reads back for one form."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.repl :as repl]))

(def ^:private symbol-vector
  (str "#:seon.print{:face :seon.print/vector, :items ["
       "#:seon.print{:face :seon.print/symbol, :value my.run/complete} "
       "#:seon.print{:face :seon.print/symbol, :value my.run/wait}]}"))

(defn- number-node
  [value]
  (str "#:seon.print{:face :seon.print/number, :value " value "}"))

(deftest one-evaluation-emits-comment-prompt-and-response
  (testing "the comment sits above the prompt so the prompt holds one form"
    (let [emitted (repl/text
                 {:seon.cluster.eval/comment
                  "; the agent's comment, verbatim, above the prompt"
                  :seon.cluster.eval/source "(+ 1 1)"
                  :seon.ns/name 'my.agents.juniper
                  :seon.cluster.eval/ordinal 0
                  :seon.cluster.eval/result-edn (number-node 2)
                  :seon.eval/duration-ms 3})]
      (is (= (str "; the agent's comment, verbatim, above the prompt\n"
                  "my.agents.juniper=> (+ 1 1)\n"
                  "#:seon.repl{:value 2, :result result/e0, :ms 3}")
             emitted))
      (is (= 3 (count (str/split-lines emitted)))
          "comment, prompt, response — never a comment glued into the prompt")))
  (testing "no comment means no blank line before the prompt"
    (is (= (str "my.agents.juniper=> (+ 1 1)\n"
                "#:seon.repl{:value 2, :result result/e0, :ms 3}")
           (repl/text {:seon.cluster.eval/source "(+ 1 1)"
                       :seon.ns/name 'my.agents.juniper
                       :seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/result-edn (number-node 2)
                       :seon.eval/duration-ms 3})))))

(deftest response-key-order-is-the-emitter-not-the-map
  (testing "keys appear in the declared order whatever order they arrive in"
    (let [emission {:seon.eval/duration-ms 1
                    :seon.sci.eval/ending-ns 'my.agents.probe
                    :seon.cluster.eval/output "hi\n"
                    :seon.cluster.eval/ordinal 2
                    :seon.cluster.eval/result-edn (number-node 41)
                    :seon.cluster.eval/source "(do (println \"hi\") 41)"
                    :seon.ns/name 'my.agents.juniper}
          response (repl/response emission)
          position (fn [k] (str/index-of response k))]
      (is (< (position ":value") (position ":result")
             (position ":out") (position ":ns") (position ":ms"))
          "value, result, out, ns, ms — enforced by the ordered vector")
      (is (= 1 (count (str/split-lines response)))
          "the response map is one line"))))

(deftest printed-output-is-separate-from-the-value
  (let [response (repl/response {:seon.cluster.eval/source "(println \"hi\")"
                                 :seon.ns/name 'my.agents.juniper
                                 :seon.cluster.eval/ordinal 2
                                 :seon.cluster.eval/output "hi\n"
                                 :seon.cluster.eval/result-edn
                                 "#:seon.print{:face :seon.print/nil}"
                                 :seon.eval/duration-ms 1})]
    (is (str/includes? response ":out \"hi\\n\"")
        "output is its own pr-str'd key, never folded into the value")
    (is (str/includes? response ":value"))))

(deftest an-error-response-carries-no-value
  (let [response (repl/response {:seon.cluster.eval/source "(/ 1 0)"
                                 :seon.ns/name 'my.agents.juniper
                                 :seon.cluster.eval/ordinal 4
                                 :seon.cluster.eval/error "Divide by zero"
                                 :seon.eval/duration-ms 0})]
    (is (str/includes? response ":error"))
    (is (not (str/includes? response ":value"))
        "exactly one of :value and :error is ever present")
    (is (str/includes? response "Divide by zero"))))

(deftest the-namespace-appears-only-when-the-form-changed-it
  (testing "a form that moved the session says where it landed"
    (is (str/includes?
         (repl/response {:seon.cluster.eval/source "(in-ns 'my.agents.probe)"
                         :seon.ns/name 'my.agents.juniper
                         :seon.cluster.eval/ordinal 1
                         :seon.sci.eval/ending-ns 'my.agents.probe
                         :seon.cluster.eval/result-edn (number-node 1)})
         ":ns my.agents.probe")))
  (testing "a form that stayed put does not repeat its own prompt"
    (is (not (str/includes?
              (repl/response {:seon.cluster.eval/source "(+ 1 1)"
                              :seon.ns/name 'my.agents.juniper
                              :seon.cluster.eval/ordinal 1
                              :seon.sci.eval/ending-ns 'my.agents.juniper
                              :seon.cluster.eval/result-edn (number-node 2)})
              ":ns ")))))

(deftest a-value-is-rendered-from-the-stored-node-under-the-print-options
  (testing "the stored admitted node is the one value source"
    (is (str/includes?
         (repl/response {:seon.cluster.eval/source "(dir my.run)"
                         :seon.ns/name 'my.agents.juniper
                         :seon.cluster.eval/ordinal 4
                         :seon.cluster.eval/result-edn symbol-vector})
         ":value [my.run/complete my.run/wait]")))
  (testing "a print option the form set bounds the same stored node"
    (is (str/includes?
         (repl/response {:seon.cluster.eval/source "(dir my.run)"
                         :seon.ns/name 'my.agents.juniper
                         :seon.cluster.eval/ordinal 4
                         :seon.print/options {:seon.print/length 1}
                         :seon.cluster.eval/result-edn symbol-vector})
         ":value [my.run/complete ...]"))))

(deftest nothing-emitted-is-comment-shaped
  (testing "ruling 45: only the agent's own comment begins with a semicolon"
    (let [emitted (repl/text {:seon.cluster.eval/source "(dir my.run)"
                            :seon.ns/name 'my.agents.juniper
                            :seon.cluster.eval/ordinal 4
                            :seon.cluster.eval/output "complete\n"
                            :seon.cluster.eval/result-edn symbol-vector
                            :seon.eval/duration-ms 2})]
      (is (not (str/includes? emitted ";; result/"))
          "the result handle is a map key, never a comment")
      (is (not-any? #(str/starts-with? (str/trim %) ";")
                    (str/split-lines emitted))))))

(deftest a-running-form-has-a-prompt-and-no-response
  (testing "absence of a terminal fact still means running"
    (is (= "my.agents.probe=> (range)"
           (repl/text {:seon.cluster.eval/source "(range)"
                       :seon.ns/name 'my.agents.probe
                       :seon.cluster.eval/ordinal 3}))
        "an empty response map would claim the form had answered")))

(deftest a-value-that-could-not-be-bound-carries-no-handle
  (testing "ruling 59c: no handle rather than a handle naming nothing"
    (is (not (str/includes?
              (repl/response {:seon.cluster.eval/source "(atom 1)"
                              :seon.ns/name 'my.agents.juniper
                              :seon.cluster.eval/ordinal 6
                              :seon.repl/result-handle? false
                              :seon.cluster.eval/result-edn (number-node 1)})
              ":result")))))
