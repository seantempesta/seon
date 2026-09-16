(ns seon.repl-test
  "The REPL response grammar: what an agent reads back for one form."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as check]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.repl :as repl]
            [seon.sci.admit :as admit]))

(defn- text-nodes [node]
  (cond
    (string? node) node
    (vector? node) (apply str (map text-nodes (drop (if (map? (second node)) 2 1) node)))
    (sequential? node) (apply str (map text-nodes node))
    :else ""))

(deftest colouring-preserves-every-character-including-malformed-source
  (let [result (check/quick-check
                500
                (prop/for-all [source (gen/fmap #(apply str (map char %))
                                               (gen/vector (gen/choose 0 65535))) ]
                  (= source (apply str (map second (repl/syntax-tokens source)))))
                :seed 20260914)]
    (is (:pass? result) (pr-str result)))
  (doseq [source ["\n```clojure\n;; café <tag>\n(+ 1 2)\n```"
                  "[\"unfinished\\\"" "\\; \\newline ; comment\n{:x -3.2}" ""]
          response [{:seon.eval/shown "[1 :two result/e3]"}
                    {:seon.eval/shown "Plain help.\nUse (dir my.plan)."
                     :seon.eval/renderer 'seon.bootstrap/render-help-ai}
                    {:seon.cluster.eval/error "Reader error"}]]
    (let [emission (merge (cond-> {:seon.ns/name 'my.agents.juniper}
                            (seq source) (assoc :seon.cluster.eval/source source))
                          response)]
      (is (= source (apply str (map second (repl/syntax-tokens source)))))
      (is (= (repl/text emission) (text-nodes (repl/render-emission-html emission)))))))

(deftest generated-source-uses-reader-syntax-without-changing-the-form
  (let [form '(seon.db/pull '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}]
                           [:seon.agent/id "juniper"])
        source (binding [*print-namespace-maps* true *print-length* 1]
                 (repl/source-text form))]
    (is (= "(seon.db/pull '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name]}] [:seon.agent/id \"juniper\"])"
           source))
    (is (= form (read-string source)))))

(deftest one-evaluation-emits-comment-prompt-and-response
  (testing "the prompt precedes the entire agent input"
    (let [emitted (repl/text
                 {:seon.cluster.eval/comment
                  ";; I should check the sum."
                  :seon.cluster.eval/source "(+ 1 1)"
                  :seon.ns/name 'my.agents.juniper
                  :seon.repl/handle (admit/result-handle "41")
                  :seon.eval/shown "2"
                  :seon.eval/duration-ms 3})]
      (is (= (str "my.agents.juniper=> ;; I should check the sum.\n"
                  "(+ 1 1)\n"
                  "#:seon.repl{:value 2, :result result/e41, :ms 3}")
             emitted))
      (is (= 3 (count (str/split-lines emitted)))
          "prompt and comment, form, response")))
  (testing "no comment means no blank line before the prompt"
    (is (= (str "my.agents.juniper=> (+ 1 1)\n"
                "#:seon.repl{:value 2, :result result/e41, :ms 3}")
           (repl/text {:seon.cluster.eval/source "(+ 1 1)"
                       :seon.ns/name 'my.agents.juniper
                       :seon.repl/handle (admit/result-handle "41")
                       :seon.eval/shown "2"
                       :seon.eval/duration-ms 3})))))

(deftest the-comment-is-its-own-thinking-block-in-html
  ;; F4/S11: the comment is a separately stored fact
  ;; (`:seon.cluster.eval/comment`), so the HTML pair gives it its own block
  ;; while the AI pair keeps it inside the REPL grammar. One fact, two
  ;; projections — not two grammars.
  (let [emission {:seon.cluster.eval/comment ";; I should check the sum."
                  :seon.cluster.eval/source "(+ 1 1)"
                  :seon.cluster.eval/ns {:seon.ns/name 'my.agents.juniper}
                  :seon.eval/shown "2"}
        hiccup (repl/render-html emission)
        blocks (filter vector? (tree-seq vector? seq hiccup))
        thinking (filter #(= "seon-eval-thinking" (:class (second %))) blocks)
        prompts (filter #(= "seon-eval-prompt" (:class (second %))) blocks)]
    (is (= 1 (count thinking)) (pr-str hiccup))
    (is (= ";; I should check the sum." (last (first thinking))))
    (is (= 1 (count prompts)))
    (is (= "my.agents.juniper=> (+ 1 1)" (last (first prompts)))
        "the prompt block no longer carries the comment")
    (is (str/includes? (text-nodes hiccup) "2")
        "the result still renders through the pair"))
  (testing "no comment means no thinking block"
    (let [hiccup (repl/render-html {:seon.cluster.eval/source "(+ 1 1)"
                                    :seon.cluster.eval/ns {:seon.ns/name 'my.agents.juniper}
                                    :seon.eval/shown "2"})]
      (is (not (str/includes? (pr-str hiccup) "seon-eval-thinking")))))
  (testing "the AI grammar is unchanged: the comment stays on the prompt line"
    (is (= (str "my.agents.juniper=> ;; I should check the sum.\n(+ 1 1)\n"
                "#:seon.repl{:value 2}")
           (repl/text {:seon.cluster.eval/comment ";; I should check the sum."
                       :seon.cluster.eval/source "(+ 1 1)"
                       :seon.ns/name 'my.agents.juniper
                       :seon.eval/shown "2"})))))

(deftest response-key-order-is-the-emitter-not-the-map
  (testing "keys appear in the declared order whatever order they arrive in"
    (let [emission {:seon.eval/duration-ms 1
                    :seon.sci.eval/ending-ns 'my.agents.probe
                    :seon.cluster.eval/output "hi\n"
                    :seon.repl/handle (admit/result-handle "2")
                    :seon.eval/shown "41"
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
                                 :seon.eval/shown
                                 ;; the declared `::nil` face carries its
                                 ;; value key, exactly as admission emits it
                                 "nil"
                                 :seon.eval/duration-ms 1})]
    (is (str/includes? response ":out \"hi\\n\"")
        "output is its own pr-str'd key, never folded into the value")
    (is (str/includes? response ":value"))))

(deftest every-def-says-it-is-not-persisted
  ;; THE CLASS: an agent told nothing keeps defining data it expects to keep.
  ;; Owner, 2026-09-08: every top-level def carries the one note; definers
  ;; that become program rows carry none.
  (let [emission (fn [source]
                   {:seon.cluster.eval/source source
                    :seon.ns/name 'my.agents.juniper
                    :seon.cluster.eval/ordinal 0
                    :seon.eval/shown
                    "nil"})
        def-response (repl/response (emission "(def secret 99)"))
        defn-response (repl/response (emission "(defn hello [] 42)"))]
    (is (str/includes? def-response ":note \"secret lives only in your SCI context")
        "a def carries the note, naming the var")
    (is (str/includes? def-response "transact the data into the database.\"")
        "the note ends by saying what persists")
    (is (str/includes? defn-response
                       "hello was not installed: every function needs a :malli/schema contract to become part of the program."))
    (is (not (str/includes?
              (repl/response
               (emission "(defn hello {:malli/schema [:=> [:cat] :int]} [] 42)"))
              ":note"))
        "a contracted defn is eligible for installation")
    (is (= [:seon.repl/value :seon.repl/note]
           (->> (re-seq #":(value|note|out|ns|ms)" def-response)
                (map (comp keyword #(str "seon.repl/" %) second))))
        "the note sits after :out and before :ns in the one order")))

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
                         :seon.eval/shown "1"})
         ":ns my.agents.probe")))
  (testing "a form that stayed put does not repeat its own prompt"
    (is (not (str/includes?
              (repl/response {:seon.cluster.eval/source "(+ 1 1)"
                              :seon.ns/name 'my.agents.juniper
                              :seon.cluster.eval/ordinal 1
                              :seon.sci.eval/ending-ns 'my.agents.juniper
                              :seon.eval/shown "2"})
              ":ns ")))))

(deftest history-preserves-shown-text-without-applying-a-later-profile
  (let [shown "[my.turn/complete my.turn/wait]"
        emission {:seon.cluster.eval/source "(dir my.turn)"
                  :seon.ns/name 'my.agents.juniper
                  :seon.eval/shown shown}
        response (repl/response emission)]
    (is (str/includes? response (str ":value " shown)))
    (doseq [settings [{:seon.print/length 1}
                      {:seon.print/level 0}
                      {:seon.print/options {:seon.print/length 0}}]]
      (is (= response (repl/response (merge emission settings)))))))

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
           :seon.eval/shown "2"})
         "my.agents.juniper=> (+ 1 1)"))))

(deftest nothing-emitted-is-comment-shaped
  (testing "ruling 45: only the agent's own comment begins with a semicolon"
    (let [emitted (repl/text {:seon.cluster.eval/source "(dir my.turn)"
                            :seon.ns/name 'my.agents.juniper
                            :seon.cluster.eval/ordinal 4
                            :seon.cluster.eval/output "complete\n"
                            :seon.eval/shown "[my.turn/complete my.turn/wait]"
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
  (testing "a stored evaluation names its value by its stable identity"
    (let [handle (admit/result-handle "8143")]
      (is (qualified-symbol? handle))
      (is (= 'result (symbol (namespace handle)))
          "the handle is interned in the one `result` namespace")
      (is (str/includes?
           (repl/render-ai {:db/id 8143
                            :seon.cluster.eval/id "8143"
                            :seon.cluster.eval/source "(+ 1 1)"
                            :seon.cluster.eval/ordinal 0
                            :seon.eval/shown "2"})
           (str ":result " handle)))))
  (testing "two evaluations at the same ordinal never share a handle"
    (let [emitted (fn [entity-id]
                    (repl/render-ai {:db/id entity-id
                                     :seon.cluster.eval/id (str entity-id)
                                     :seon.cluster.eval/source "(+ 1 1)"
                                     :seon.cluster.eval/ordinal 0
                                     :seon.eval/shown
                                     "2"}))]
      (is (not= (emitted 11) (emitted 12)))))
  (testing "shown text identifies an opaque live object without restoring it"
    (is (str/includes?
         (repl/render-ai {:db/id 8144
                          :seon.cluster.eval/id "8144"
                          :seon.cluster.eval/source "(atom 1)"
                          :seon.ns/name 'my.agents.juniper
                          :seon.eval/shown "#object[clojure.lang.Atom]"})
         ":result result/e8144"))
    (is (not (str/includes?
              (repl/render-ai {:seon.cluster.eval/source "(+ 1 1)"
                               :seon.eval/shown "2"})
              ":result"))
        "a preview with no evaluation identity has no handle")))
