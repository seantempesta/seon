(ns seon.ref-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as test-support]))

(defn- shown
  "The value of `code` evaluated as `agent-id` through `seon.mcp/call`, read back as data."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :seon.schema/value]}
  [running agent-id code]
  (let [answer (json/read-str (mcp/call (json/write-str {"name" "eval_clj" "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (test-support/mcp-reply answer))))

(deftest one-text-yields-names-paths-and-line-citations-by-the-same-rules
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [kinds (str "(mapv (juxt :seon.ref/name :seon.ref/path :seon.ref/problem-kind) (seon.ref/references "
                      "{:seon.ref/text \"Use `seon.task/home`, see [ledger](agent-loop-ledger.md#5-critical-path)"
                      " and `src/seon/task.clj:381`.\" %s}))")
           as-intent (shown running agent-id (format kinds ""))
           as-doc (shown running agent-id (format kinds ":seon.ref/document-path \"docs/seon/schedule/README.md\""))]
       (is (= [['seon.task/home nil nil]
               [nil "agent-loop-ledger.md" :unresolved]
               [nil "src/seon/task.clj" :line-reference]]
              as-intent))
       (is (= (assoc-in as-intent [1 1] "docs/seon/schedule/agent-loop-ledger.md") as-doc))))))

(deftest fenced-code-and-prose-spans-are-not-references
  (test-support/with-running-agent
   (fn [running agent-id _]
     (is (= [] (shown running agent-id
                      "(seon.ref/references {:seon.ref/text \"```clojure\\n`seon.task/home`\\n```\\n\\nAdd `(+ 1 2)`.\\n\"})"))))))

(deftest front-matter-is-not-a-heading
  (test-support/with-running-agent
   (fn [running agent-id _]
     (is (= [{:seon.ref/level 1 :seon.ref/heading "A" :seon.ref/anchor "a" :seon.ref/line 4}]
            (shown running agent-id "(:seon.ref/headings (seon.ref/document \"---\\ntitle: x\\n---\\n# A\\n\"))"))))))

(deftest a-repeated-parse-is-a-cache-hit
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [code "(let [t (str \"# \" (random-uuid))] (seon.ref/document t) (count (:seon.ref/headings (seon.ref/document t))))"
           [headings calls] (test-support/armed-calls '#{seon.ref/parsed} #(shown running agent-id code))]
       (is (= 1 headings))
       (is (= {'seon.ref/parsed 1} calls))))))

(deftest a-function-whose-arglists-auto-resolve-a-keyword-resolves
  (test-support/with-running-agent
   (fn [running agent-id _]
     (is (= ['([{:seon.print/keys [node children]}])]
            (shown running agent-id "(mapv :arglists (seon.ref/references {:seon.ref/text \"`seon.print/close-frame`\"}))"))))))

(deftest a-doc-section-reads-with-its-headings-below-and-size
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [{:seon.ref/keys [heading text tokens below pin]}
           (shown running agent-id "(my.fs/section \"AGENTS.md#data-and-contracts\")")]
       (is (= "Data and contracts" heading))
       (is (str/starts-with? text "## Data and contracts"))
       (is (not-any? #(str/starts-with? % "## Failure") (str/split-lines text)))
       (is (pos? tokens))
       (is (nil? pin))
       (is (every? :seon.ref/anchor below))))))

(deftest a-reference-code-section-carries-its-pin
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [{:seon.ref/keys [pin heading]} (shown running agent-id "(my.fs/section \"reference-code/malli/README.md#quickstart\")")]
       (is (= "malli" (:seon.ref/project pin)))
       (is (= 40 (count (:seon.ref/revision pin))))
       (is (some? heading))))))

(deftest a-missing-heading-names-the-nearest-headings
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [answer (shown running agent-id "(select-keys (my.fs/section \"AGENTS.md#data-and-contract\") [:seon.ref/section-missing :seon.ref/nearest])")]
       (is (= :heading (:seon.ref/section-missing answer)))
       (is (= "data-and-contracts" (:seon.ref/anchor (first (:seon.ref/nearest answer)))))))))

(deftest a-missing-file-names-the-nearest-files
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [answer (shown running agent-id "(select-keys (my.fs/section \"docs/seon/architecture/NO-SUCH.md\") [:seon.ref/section-missing :seon.ref/nearest])")]
       (is (= :file (:seon.ref/section-missing answer)))
       (is (some #{"README.md"} (:seon.ref/nearest answer)))))))
