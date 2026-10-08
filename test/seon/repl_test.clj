(ns seon.repl-test
  "The REPL response grammar: what an agent reads back for one form."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as check]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.repl :as repl]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

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
    (is (:pass? result)))
  (doseq [source ["\n```clojure\n;; café <tag>\n(+ 1 2)\n```"
                  "[\"unfinished\\\"" "\\; \\newline ; comment\n{:x -3.2}" ""]
          response [{:seon.eval/shown "[1 :two result/e3]"}
                    {:seon.eval/shown "Plain help.\nUse (dir my.task)."
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
                  "#:seon.repl{:result result/e41, :ms 3, :value 2}")
             emitted))
      (is (= 3 (count (str/split-lines emitted)))
          "prompt and comment, form, response")))
  (testing "no comment means no blank line before the prompt"
    (is (= (str "my.agents.juniper=> (+ 1 1)\n"
                "#:seon.repl{:result result/e41, :ms 3, :value 2}")
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
    (is (= 1 (count thinking)))
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
      (is (< (position ":result") (position ":out") (position ":ns") (position ":ms")
             (position ":value"))
          "result, out, ns, ms, then the shown value — enforced by the ordered vector")
      (is (= 2 (count (str/split-lines response)))
          "the response map is one line but for the output's own line break, which its string literal keeps"))))

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
    (is (= {:seon.repl/out "hi\n" :seon.repl/value nil}
           (select-keys (support/reply response) [:seon.repl/out :seon.repl/value]))
        "output is its own key, never folded into the value")))

(deftest a-def-note-follows-the-evaluations-surface-and-outcome
  ;; THE CLASS: the note is decided from the evaluation's recorded outcome
  ;; (the class of the Var it returned), never from the source text alone:
  ;; an SCI def is told it lives in SCI, a host def names its Var, a failed
  ;; def and a definer that becomes a program entity carry none.
  (let [reply (fn [source extra]
                (support/reply
                 (repl/response (merge {:seon.cluster.eval/source source
                                        :seon.ns/name 'my.agents.juniper
                                        :seon.cluster.eval/ordinal 0
                                        :seon.eval/shown "nil"}
                                       extra))))
        note (fn [source extra] (:seon.repl/note (reply source extra)))]
    (testing "an SCI def keeps the SCI note"
      (let [text (note "(def secret 99)" {:seon.eval/value-class "sci.lang.Var"})]
        (is (str/starts-with? text "secret lives only in your SCI context"))
        (is (str/ends-with? text "transact the data into the database."))))
    (testing "a JVM def names its host Var and its lifetime"
      (let [text (note "(def secret 99)" {:seon.eval/value-class "clojure.lang.Var"})]
        (is (str/starts-with? text "secret is the host Var my.agents.juniper/secret"))
        (is (str/includes? text "lasts until the JVM restarts"))
        (is (not (str/includes? text "SCI")))))
    (testing "a failed def carries no note"
      (is (not (contains? (reply "(def secret (nope))" {:seon.cluster.eval/error "Unable to resolve symbol"})
                          :seon.repl/note))))
    (testing "a def with no recorded Var outcome, and a defn's value, carry none"
      (is (not (contains? (reply "(def secret 99)" {}) :seon.repl/note)))
      (is (not (contains? (reply "(defn hello [] 42)" {:seon.eval/value-class "sci.lang.Var"})
                          :seon.repl/note))))
    (testing "the note sits after :out and before :ns, and before the shown value, in the one order"
      (is (= [:seon.repl/note :seon.repl/value]
             (filter #{:seon.repl/value :seon.repl/note :seon.repl/out :seon.repl/ns :seon.repl/ms}
                     (keys (reply "(def secret 99)" {:seon.eval/value-class "sci.lang.Var"}))))))))

(deftest an-error-response-carries-no-value
  (let [response (repl/response {:seon.cluster.eval/source "(/ 1 0)"
                                 :seon.ns/name 'my.agents.juniper
                                 :seon.cluster.eval/ordinal 4
                                 :seon.cluster.eval/error "Divide by zero"
                                 :seon.eval/duration-ms 0})]
    (is (= {:seon.repl/error "Divide by zero"}
           (select-keys (support/reply response) [:seon.repl/error :seon.repl/value]))
        "exactly one of :value and :error is ever present")))

(deftest the-namespace-appears-only-when-the-form-changed-it
  (testing "a form that moved the session says where it landed"
    (is (= 'my.agents.probe
           (:seon.repl/ns (support/reply
                           (repl/response {:seon.cluster.eval/source "(in-ns 'my.agents.probe)"
                                           :seon.ns/name 'my.agents.juniper
                                           :seon.cluster.eval/ordinal 1
                                           :seon.sci.eval/ending-ns 'my.agents.probe
                                           :seon.eval/shown "1"}))))))
  (testing "a form that stayed put does not repeat its own prompt"
    (is (not (contains? (support/reply
                         (repl/response {:seon.cluster.eval/source "(+ 1 1)"
                                         :seon.ns/name 'my.agents.juniper
                                         :seon.cluster.eval/ordinal 1
                                         :seon.sci.eval/ending-ns 'my.agents.juniper
                                         :seon.eval/shown "2"}))
                        :seon.repl/ns)))))

(deftest history-preserves-shown-text-without-applying-a-later-profile
  (let [shown "[my.turn/complete my.turn/wait]"
        emission {:seon.cluster.eval/source "(dir my.turn)"
                  :seon.ns/name 'my.agents.juniper
                  :seon.eval/shown shown}
        response (repl/response emission)]
    (is (= '[my.turn/complete my.turn/wait] (:seon.repl/value (support/reply response)))
        "the stored shown text is the value, as it was shown")
    (doseq [settings [{:seon.print/length 1}
                      {:seon.print/level 0}
                      {:seon.print/options {:seon.print/length 0}}]]
      (is (= response (repl/response (merge emission settings)))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/deliver-evaluation-errors-as-values 'seon.turn/late-faults-accompany-evaluated-values}} a-response-carries-errors-as-data-and-late-diagnostics-beside-the-value
  ;; ONE SAVED OBSERVATION (AGENTS.md, one error shape): a failed
  ;; evaluation's shown text is its whole flat error, and the response embeds
  ;; it as data; a later stage's failure is its own entry beside the value.
  (let [error {:seon.error/message "Divide by zero"
               :seon.error/chain [{:seon.error/throwable-class "java.lang.ArithmeticException"
                                   :seon.error/message "Divide by zero"}]
               :seon.sci.eval/stacktrace [['user 1 1]]}
        read-response (fn [emission] (binding [*read-eval* false] (read-string (repl/response emission))))]
    (testing "an evaluation's own failure answers :error with its data"
      (let [response (read-response {:seon.cluster.eval/error "Divide by zero"
                                     :seon.eval/shown (pr-str error)})]
        (is (= error (:seon.repl/error response)))
        (is (not (contains? response :seon.repl/value)))))
    (testing "a refused settlement keeps the value and names its refusal"
      (let [refusal [{:seon.error/message "The stored definition digest is not the one its definition produces."}]
            response (read-response {:seon.cluster.eval/error "Not settled: this run's settlement was refused."
                                     :seon.eval/shown "#'user/f"
                                     :seon.cluster.eval/diagnostics (pr-str refusal)})]
        (is (= '(var user/f) (:seon.repl/value response)))
        (is (= refusal (:seon.repl/diagnostics response)))
        (is (not (contains? response :seon.repl/error)))))
    (testing "a renderer failure is a diagnostic beside the value, the reply still readable"
      (let [response (read-response {:seon.eval/shown "{:audit/value 42}"
                                     :seon.cluster.eval/diagnostics "[{:seon.error/message \"renderer refused\"}]"})]
        (is (= {:audit/value 42} (:seon.repl/value response)))
        (is (= "renderer refused" (get-in response [:seon.repl/diagnostics 0 :seon.error/message])))))
    (testing "an unshown failure's prose is one readable string, its line breaks kept"
      (is (= "No such namespace: missing.function"
             (:seon.repl/error (read-response {:seon.cluster.eval/error "No such namespace: missing.function"}))))
      (is (= "facts not proposed:\nthe writer refused"
             (:seon.repl/error (read-response {:seon.cluster.eval/error "facts not proposed:\nthe writer refused"}))))
      (is (str/includes? (repl/response {:seon.cluster.eval/error "facts not proposed:\nthe writer refused"})
                         "proposed:\nthe")
          "a line break stays a line break, never an escape"))
    (testing "a shown failure is the printer's text, spliced unchanged"
      (is (= "Execution error (ArithmeticException) at (REPL:1:1).\nDivide by zero"
             (:seon.repl/error (into {} (#'repl/response-entries
                                         {:seon.cluster.eval/error "Divide by zero"
                                          :seon.eval/shown "Execution error (ArithmeticException) at (REPL:1:1).\nDivide by zero"}))))))))

(deftest a-unit-that-is-not-an-evaluation-refuses-instead-of-throwing
  ;; RENDERS NEVER THROW (law 2.4, audit F5). `entity-emission`'s declared
  ;; output required the source, so a unit without one raised a contract
  ;; violation from inside a page derivation — and one throw took the whole
  ;; page with it — while `render-ai`'s own guard could never run.
  (testing "an error value arriving where a unit was expected renders nil"
    (is (nil? (repl/render-ai {
                               :seon.error/message "no producer"}))))
  (testing "so does the HTML projection of the same non-evaluation"
    (is (nil? (repl/render-html {
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
    (is (= {:seon.repl/interrupted (java.util.Date. 1785500000000) :seon.repl/ms 30000}
           (select-keys (support/reply (subs emitted (str/index-of emitted "#:seon.repl{")))
                        [:seon.repl/interrupted :seon.repl/ms]))
        "an interrupted evaluation says so with its instant as data")))

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

(defn- one-map
  "The reply read back: exactly one EDN map, nothing before or after it."
  [text]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. text))]
    (let [end (Object.)
          reply (binding [*read-eval* false *default-data-reader-fn* tagged-literal] (read {:eof end} reader))]
      (is (map? reply))
      (is (identical? end (binding [*read-eval* false *default-data-reader-fn* tagged-literal] (read {:eof end} reader)))
          "nothing follows the reply map")
      (is (not (str/starts-with? (str/triml text) ";")) "nothing precedes the reply map")
      reply)))

(deftest ^{:seon/tasks #{'seon.repl/reply-map-is-not-edn-when-elided-or-rendered}} the-reply-is-one-edn-map-whatever-the-value
  ;; Owner 2026-09-27: "The repl should be returning the timing and the result
  ;; so it's already a map" — "return data whenever possible so it's
  ;; unambiguous." Value, error, findings, handle and time are keys of the one
  ;; map; nothing is printed above or around it.
  (let [handle (admit/result-handle "e7b3")]
    (testing "an elided value keeps its cut and continuation as data"
      (let [reply (one-map (repl/response
                            {:seon.cluster.eval/source "(vec (range 3000))"
                             :seon.repl/handle handle
                             :seon.eval/duration-ms 711
                             :seon.eval/shown "[0 1 2 …2997 (drop 3 result/ee7b3)]"}))]
        (is (= [0 1 2 (symbol "…2997") '(drop 3 result/ee7b3)] (:seon.repl/value reply)))
        (is (= handle (:seon.repl/result reply)))
        (is (= 711 (:seon.repl/ms reply)))))
    (testing "a comment-ended text still leaves the map readable"
      (doseq [[shown value] [["[1 2]\n;; a trailing diagnostic line" [1 2]]
                             ["[\"a;b\"]" ["a;b"]]]]
        (let [reply (one-map (repl/response {:seon.cluster.eval/source "x" :seon.repl/handle handle
                                             :seon.eval/shown shown :seon.eval/duration-ms 1}))]
          (is (= value (:seon.repl/value reply)))
          (is (= 1 (:seon.repl/ms reply))))))
    (testing "a declared renderer's text is the :value as shown, and the keys after it read as data"
      (let [emission {:seon.cluster.eval/source "(my.task/steps {})"
                      :seon.eval/renderer 'my.task/steps
                      :seon.repl/handle handle
                      :seon.eval/duration-ms 12
                      :seon.eval/shown ";; The plan has two steps.\n(my.task/steps {})"}
            entries (into {} (#'repl/response-entries emission))
            reply (one-map (repl/response emission))]
        (is (= ";; The plan has two steps.\n(my.task/steps {})" (:seon.repl/value entries)))
        (is (= handle (:seon.repl/result reply)))
        (is (= 12 (:seon.repl/ms reply))))
      (let [reply (one-map (repl/response {:seon.cluster.eval/source "(dir x)"
                                           :seon.eval/renderer 'seon.repl/render-directory-ai
                                           :seon.eval/shown "#:seon.repl{:columns [:sym], :rows [[x/f]]}"
                                           :seon.eval/duration-ms 3}))]
        (is (= {:seon.repl/columns [:sym] :seon.repl/rows [['x/f]]} (:seon.repl/value reply))
            "a render that is EDN is data")))
    (testing "prose with an open delimiter or quote cannot swallow a key the composer wrote"
      (let [emission {:seon.cluster.eval/source "(steps)" :seon.eval/renderer 'my.task/steps
                      :seon.repl/handle handle :seon.eval/duration-ms 12
                      :seon.eval/shown "Steps: 1) open it\n2) say \"hi"}
            reply (repl/response emission)
            composed (one-map (str (subs reply 0 (str/index-of reply ", :value ")) "}"))]
        (is (= :seon.repl/value (first (peek (#'repl/response-entries emission)))) "the shown text comes last")
        (is (= {:seon.repl/result handle :seon.repl/ms 12} composed))))
    (testing "only spliced printer text is ended before the next key"
      (is (= "#:seon.repl{:findings [{:seon.repl/fix \"a; b\"}], :ms 1, :value [\"a;b\"]\n}"
             (#'repl/reply-map [[:seon.repl/findings "[{:seon.repl/fix \"a; b\"}]"] [:seon.repl/ms "1"]
                                [:seon.repl/value "[\"a;b\"]"]]))))))

(deftest ^{:seon/tasks #{'seon.repl/a-selected-render-arrives-as-an-escaped-string}} a-rendered-value-with-cuts-is-spliced-verbatim
  ;; A render's shown text, its cut and dig form included, is the printer's
  ;; output: the reply holds it once, as shown, never quoted again.
  (let [shown "{:seon.ns/name seon.render,\n :seon.ns/doc \"Renders \\\"values\\\".\"}\nline two\n…+40 (seon.print/full result/e7b3)"
        emission {:seon.cluster.eval/source "(pull ns)" :seon.eval/renderer 'seon.render.ns/render-ai
                  :seon.repl/handle (admit/result-handle "e7b3") :seon.eval/duration-ms 3 :seon.eval/shown shown}]
    (is (= shown (:seon.repl/value (into {} (#'repl/response-entries emission)))))
    (is (str/includes? (repl/response emission) shown))))

(deftest an-error-in-the-printers-grammar-is-a-map-not-a-string
  ;; An error holding a host object, a Var (every `[:fn #'pred]` contract) or a
  ;; regex came back as `:error "{…}"`: none of them is EDN. The printer's
  ;; grammar is Clojure's, so the reply embeds the map as data.
  (doseq [[offending read-back?] [["#object[clojure.core.async.impl.channels.ManyToManyChannel 0x7c6ba730]" tagged-literal?]
                                  ["#'seon.db/database-value?" #(= '(var seon.db/database-value?) %)]
                                  ["#\"a+\"" #(instance? java.util.regex.Pattern %)]]]
    (let [reply (one-map (repl/response
                          {:seon.cluster.eval/source "(f {})"
                           :seon.cluster.eval/error "refused"
                           :seon.eval/shown (str "{:seon.error/message \"f refused argument 0\",\n"
                                                 " :seon.error/offending {:c " offending "}}")}))]
      (is (= "f refused argument 0" (get-in reply [:seon.repl/error :seon.error/message])))
      (is (read-back? (get-in reply [:seon.repl/error :seon.error/offending :c]))))))

(deftest a-reply-within-every-declared-bound-adds-nothing-and-an-exceeded-bound-is-a-findings-entry
  (let [emission {:seon.cluster.eval/source "(inc 1)"
                  :seon.repl/handle (admit/result-handle "e5885")
                  :seon.eval/shown "2"
                  :seon.eval/duration-ms 4}]
    (testing "the normal reply is the answer, the handle and the time"
      (is (= "#:seon.repl{:result result/ee5885, :ms 4, :value 2}" (repl/response emission)))
      (is (= [] (repl/findings emission))))
    (testing "each exceeded bound is one data map under :findings in the one reply map"
      (let [slow (assoc emission :seon.eval/duration-ms 1450
                        :seon.cluster.eval/output (apply str (repeat 400 "noisy line\n")))
            reply (one-map (repl/response slow))
            found (:seon.repl/findings reply)]
        (is (= [:slow :large-output] (mapv :seon.repl/finding found)))
        (is (= 1450 (:seon.eval/duration-ms (first found))))
        (is (every? :seon.repl/fix found) "every finding carries its declared fix")
        (is (= 2 (:seon.repl/value reply)))))
    (testing "a value over the whole-value bound reports its whole and shown size"
      (is (= [{:seon.repl/finding :large-value :seon.eval/value-tokens 4650 :seon.eval/shown-tokens 30}]
             (mapv #(dissoc % :seon.repl/fix)
                   (repl/findings (assoc emission :seon.eval/value-tokens 4650 :seon.eval/shown-tokens 30)))))
      (is (= [] (repl/findings (assoc emission :seon.eval/value-tokens 900 :seon.eval/shown-tokens 900)))
          "within the bound the value is simply shown"))))

(deftest ^{:seon/tasks #{'seon.repl/the-slow-fix-ranks-the-whole-process}}
  the-reply-carries-no-call-bookkeeping
  (let [emission {:seon.cluster.eval/source "(inc 1)"
                  :seon.repl/handle (admit/result-handle "e5886")
                  :seon.eval/shown "2"
                  :seon.eval/duration-ms 1450
                  :seon.cluster.eval/call-count 8769
                  :seon.cluster.eval/repeated-calls #{['seon.db/database-value? 2887 0]}}
        reply (one-map (repl/response emission))]
    (is (= [:slow] (mapv :seon.repl/finding (:seon.repl/findings reply))))
    (is (empty? (select-keys reply [:seon.repl/calls :seon.repl/repeated]))
        "a slow reply names its finding and numbers, not the armed call counts")))

(deftest every-reply-path-reads-the-recorded-cost-its-findings-need
  ;; Live 2026-09-27: the receipt recorded :seon.eval/value-tokens 1604 but the
  ;; MCP reply showed no LARGE VALUE, because the transcript's pull selector
  ;; named its attributes by hand. The attributes the findings read are one
  ;; derived list, and the entity render carries them too.
  (is (every? (set (repl/cost-attributes))
              [:seon.eval/duration-ms :seon.eval/value-tokens :seon.eval/shown-tokens
               :seon.eval/allocated-bytes]))
  (is (not-any? #(= "seon.repl" (namespace %)) (repl/cost-attributes))
      "a measure the reply derives itself is not pulled")
  (let [text (repl/render-ai {:db/id 7 :seon.cluster.eval/id "eb53" :seon.cluster.eval/source "(vec (range 3000))"
                              :seon.eval/shown "[0 1 2 …2997 (drop 3 result/eeb53)]"
                              :seon.eval/value-tokens 1604 :seon.eval/shown-tokens 39 :seon.eval/duration-ms 5})
        reply (one-map (subs text (str/index-of text "#:seon.repl{")))]
    (is (= :large-value (get-in reply [:seon.repl/findings 0 :seon.repl/finding])))))

(deftest a-failed-evaluation-says-error-in-the-one-map
  (let [reply (one-map (repl/response {:seon.cluster.eval/source "(/ 1 0)"
                                       :seon.eval/shown "{:seon.error/message \"Divide by zero\", :seon.error/exception-class java.lang.ArithmeticException}"
                                       :seon.cluster.eval/error "Divide by zero"
                                       :seon.repl/handle (admit/result-handle "e64f")
                                       :seon.eval/duration-ms 0}))]
    (is (= "Divide by zero" (get-in reply [:seon.repl/error :seon.error/message])))
    (is (not (contains? reply :seon.repl/value)))
    (is (= 0 (:seon.repl/ms reply)))))


(deftest ^{:seon/tasks #{'seon.turn/the-user-prompt-stop-sequence-cuts-the-models-first-form}}
  the-model-stop-sequences-never-cut-a-form-the-transcript-teaches
  (let [emission {:seon.cluster.eval/source "(f)" :seon.ns/name 'user :seon.eval/shown "3"}
        stops (:seon.config.ai/stop config/defaults)]
    (is (seq stops))
    (doseq [stop stops]
      (is (not (str/includes? (first (str/split-lines (repl/text emission))) stop))
          "a reply that writes a form the way the transcript shows it is not cut")
      (is (str/includes? (repl/response emission) stop)
          "a reply that starts writing a result is stopped"))))
