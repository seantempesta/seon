(ns seon.repl-test
  "The REPL response grammar: what an agent reads back for one form."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.repl :as repl]
            [seon.sci.admit :as admit]))

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
                  :seon.repl/handle (admit/result-handle 41)
                  :seon.cluster.eval/result-edn (number-node 2)
                  :seon.eval/duration-ms 3})]
      (is (= (str "; the agent's comment, verbatim, above the prompt\n"
                  "my.agents.juniper=> (+ 1 1)\n"
                  "#:seon.repl{:value 2, :result result/e41, :ms 3}")
             emitted))
      (is (= 3 (count (str/split-lines emitted)))
          "comment, prompt, response — never a comment glued into the prompt")))
  (testing "no comment means no blank line before the prompt"
    (is (= (str "my.agents.juniper=> (+ 1 1)\n"
                "#:seon.repl{:value 2, :result result/e41, :ms 3}")
           (repl/text {:seon.cluster.eval/source "(+ 1 1)"
                       :seon.ns/name 'my.agents.juniper
                       :seon.repl/handle (admit/result-handle 41)
                       :seon.cluster.eval/result-edn (number-node 2)
                       :seon.eval/duration-ms 3})))))

(deftest response-key-order-is-the-emitter-not-the-map
  (testing "keys appear in the declared order whatever order they arrive in"
    (let [emission {:seon.eval/duration-ms 1
                    :seon.sci.eval/ending-ns 'my.agents.probe
                    :seon.cluster.eval/output "hi\n"
                    :seon.repl/handle (admit/result-handle 2)
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
                                 ;; the declared `::nil` face carries its
                                 ;; value key, exactly as admission emits it
                                 "#:seon.print{:face :seon.print/nil, :value nil}"
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

(deftest the-stored-print-keys-are-the-ones-the-emitter-reads
  ;; THE STORED KEYS REACH THE EMITTER (audit C3). `:seon.print/length` and
  ;; `/level` are what settlement writes when a form `set!`s *print-length*
  ;; or *print-level*; the emitter used to read `:seon.print/options`, a key
  ;; no stored evaluation carries, so every per-form print setting was
  ;; recorded and then silently discarded.
  (testing "a stored :seon.print/length bounds the stored node"
    (is (str/includes?
         (repl/response {:seon.cluster.eval/source "(dir my.run)"
                         :seon.ns/name 'my.agents.juniper
                         :seon.print/length 1
                         :seon.cluster.eval/result-edn symbol-vector})
         ":value [my.run/complete ...]")))
  (testing "a stored :seon.print/level bounds the same node's depth"
    (is (str/includes?
         (repl/response
          {:seon.cluster.eval/source "(dir my.run)"
           :seon.ns/name 'my.agents.juniper
           :seon.print/level 0
           :seon.cluster.eval/result-edn symbol-vector})
         ":value #")))
  (testing "no stored key leaves the shipped defaults in effect"
    (is (str/includes?
         (repl/response {:seon.cluster.eval/source "(dir my.run)"
                         :seon.ns/name 'my.agents.juniper
                         :seon.cluster.eval/result-edn symbol-vector})
         ":value [my.run/complete my.run/wait]"))))

(deftest an-error-names-its-throwable-and-never-invents-a-location
  ;; A REFUSAL NAMES WHAT IT KNOWS AND NOTHING MORE (law 2.4, audit F2).
  ;; The recorded triage is the authority; without it the fallback used to
  ;; print `Execution error () at (REPL:1).` — an empty class and a source
  ;; location the evaluation never had.
  (let [throwable (try (/ 1 0) (catch Throwable failure failure))
        triage (pr-str (main/ex-triage (Throwable->map throwable)))]
    (testing "recorded triage names the throwable's own class"
      (let [text (repl/error-text {:seon.cluster.eval/error "Divide by zero"
                                   :seon.cluster.eval/triage-edn triage})]
        (is (str/includes? text "ArithmeticException"))
        (is (str/includes? text "Divide by zero"))))
    (testing "without triage the class parens and the location are absent"
      (let [text (repl/error-text {:seon.cluster.eval/error "Divide by zero"})]
        (is (= "Execution error.\nDivide by zero" text))
        (is (not (str/includes? text "()")))
        (is (not (str/includes? text "REPL:1")))))
    (testing "unreadable triage degrades to the same honest fallback"
      (is (= "Execution error.\nDivide by zero"
             (repl/error-text {:seon.cluster.eval/error "Divide by zero"
                               :seon.cluster.eval/triage-edn "#unreadable("}))))))

(deftest a-unit-that-is-not-an-evaluation-refuses-instead-of-throwing
  ;; RENDERS NEVER THROW (law 2.4, audit F5). `entity-emission`'s declared
  ;; output required the source, so a unit without one raised a contract
  ;; violation from inside a page derivation — and one throw took the whole
  ;; page with it — while `render-ai`'s own guard could never run.
  (testing "an error value arriving where a unit was expected renders nil"
    (is (nil? (repl/render-ai {:seon.error/kind :seon.render/refused
                               :seon.error/message "no producer"}))))
  (testing "so does the HTML projection of the same non-evaluation"
    (is (nil? (repl/render-html {:seon.error/kind :seon.render/refused
                                 :seon.error/message "no producer"}))))
  (testing "an evaluation entity with a source still renders"
    (is (str/includes?
         (repl/render-ai
          {:seon.cluster.eval/source "(+ 1 1)"
           :seon.cluster.eval/ns {:seon.ns/name 'my.agents.juniper}
           :seon.cluster.eval/result-edn (number-node 2)})
         "my.agents.juniper=> (+ 1 1)"))))

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

(deftest an-interrupted-evaluation-says-so-as-readable-data
  ;; An evaluation boot cut settles `:ms` alone, so without this key it read
  ;; exactly like one still running — absence of signal as health, in the
  ;; agent's own history. The instant is `#inst` data, like every other
  ;; instant the one print grammar renders, never a quoted string.
  (let [emitted (repl/text {:seon.cluster.eval/source "(range)"
                            :seon.ns/name 'my.agents.probe
                            :seon.cluster.eval/interrupted-at
                            (java.util.Date. 1785500000000)
                            :seon.eval/duration-ms 30000})]
    (is (str/includes? emitted ":interrupted #inst")
        emitted)
    (is (not (str/includes? emitted ":interrupted \"")))
    (is (str/includes? emitted ":ms 30000"))))

(deftest the-handle-is-the-evaluations-own-identity
  (testing "a stored evaluation names its value by its entity id, not its ordinal"
    (let [handle (admit/result-handle 8143)]
      (is (qualified-symbol? handle))
      (is (= 'result (symbol (namespace handle)))
          "the handle is interned in the one `result` namespace")
      (is (str/includes?
           (repl/render-ai {:db/id 8143
                            :seon.cluster.eval/source "(+ 1 1)"
                            :seon.cluster.eval/ordinal 0
                            :seon.cluster.eval/result-edn (number-node 2)})
           (str ":result " handle)))))
  (testing "two evaluations at the same ordinal never share a handle"
    (let [emitted (fn [entity-id]
                    (repl/render-ai {:db/id entity-id
                                     :seon.cluster.eval/source "(+ 1 1)"
                                     :seon.cluster.eval/ordinal 0
                                     :seon.cluster.eval/result-edn
                                     (number-node 2)}))]
      (is (not= (emitted 11) (emitted 12)))))
  (testing "ruling 59c: no handle rather than a handle naming nothing"
    (is (not (str/includes?
              (repl/render-ai
               {:db/id 8144
                :seon.cluster.eval/source "(atom 1)"
                :seon.ns/name 'my.agents.juniper
                :seon.cluster.eval/result-edn
                "#:seon.print{:face :seon.print/object, :name \"clojure.lang.Atom\"}"})
              ":result"))
        "a node that kept only a class name never held the value")
    (is (not (str/includes?
              (repl/render-ai {:seon.cluster.eval/source "(+ 1 1)"
                               :seon.cluster.eval/ordinal 0
                               :seon.cluster.eval/result-edn (number-node 2)})
              ":result"))
        "an evaluation that never persisted has no identity to name")
    ;; A MISSING VALUE ABLATES ITS HANDLE. It stored no node at all, so the
    ;; fork binds nothing and emitting `:result` would name a symbol that
    ;; resolves to nothing.
    (is (not (str/includes?
              (repl/render-ai
               {:db/id 8145
                :seon.cluster.eval/source "(range)"
                :seon.ns/name 'my.agents.juniper
                :seon.eval/missing :over-bound
                :seon.eval/size 8388608})
              ":result"))
        "a value over the storage bound is not a value to name")))

(deftest a-missing-value-states-the-reason-and-the-bytes-it-reached
  ;; ONE GENERATOR for the sentence, and it is DATA, never comment-shaped
  ;; (ruling 45): the agent reads what happened rather than an empty
  ;; `:value` that would say the form produced nothing.
  (testing "over the storage bound"
    (let [emitted (repl/render-ai
                   {:db/id 9001
                    :seon.cluster.eval/source "(range)"
                    :seon.ns/name 'my.agents.juniper
                    :seon.eval/missing :over-bound
                    :seon.eval/size 8388608})]
      (is (str/includes?
           emitted "#:seon.repl{:value #:seon.eval{:missing :over-bound"))
      (is (str/includes? emitted ":size 8388608"))
      (is (not (str/includes? emitted ";")) "nothing emitted is a comment")))
  (testing "not serializable — no size, because none was measured"
    (let [emitted (repl/render-ai
                   {:db/id 9002
                    :seon.cluster.eval/source "(async/chan)"
                    :seon.ns/name 'my.agents.juniper
                    :seon.eval/missing :unserializable})]
      (is (str/includes? emitted ":value #:seon.eval{:missing :unserializable}"))
      (is (not (str/includes? emitted ":size")))))
  (testing "missing wins over any stored node the reader might still find"
    (is (str/includes?
         (repl/render-ai {:db/id 9003
                          :seon.cluster.eval/source "(range)"
                          :seon.eval/missing :lost
                          :seon.cluster.eval/result-edn (number-node 2)})
         ":value #:seon.eval{:missing :lost}"))))
