(ns seon.ref-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [my.program :as program]
            [seon.db :as db]
            [seon.error]
            [seon.mcp :as mcp]
            [seon.ref :as ref]
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

(deftest an-unloaded-library-name-resolves-from-the-dependency-cache
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [code (str "(let [found (seon.ref/references {:seon.ref/text \"`seon.operator/check!` `rewrite-clj.paredit/slurp-backward` `rewrite-clj.paredit/nope`\"})]"
                     " (mapv (fn [r] (-> r (select-keys [:seon.ref/name :seon.ref/problem-kind :arglists :seon.ref/pin]) (update :seon.ref/pin :seon.ref/project))) found))")
           [operator slurp-backward absent] (shown running agent-id code)
           source (shown running agent-id "(with-out-str (clojure.repl/source rewrite-clj.paredit/slurp-backward))")]
       (is (= '[[request] nil] [(first (:arglists operator)) (:seon.ref/problem-kind operator)]))
       (is (= {:arglists '([zloc]) :seon.ref/pin "rewrite-clj"} (select-keys slurp-backward [:arglists :seon.ref/pin])))
       (is (= :unresolved (:seon.ref/problem-kind absent)))
       (is (str/starts-with? source "(defn ^{:deprecated"))))))

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

(deftest a-resolved-function-offers-a-source-form-that-shows-its-source
  (let [database (db/db)
        source-form (last (:seon.render/operations (#'ref/resolve-name database 'seon.ref/references)))]
    (is (= '(my.program/source 'seon.ref/references) source-form))
    (is (string? (:source (program/source database (second (second source-form))))))))

(deftest a-span-of-a-name-and-more-words-is-prose
  (let [found (seon.ref/references (seon.db/db) {:seon.ref/text "Use `seon.ref/references`, not `seon.ref/section AGENTS.md` or `foo/bar baz`."})]
    (is (= ['seon.ref/references] (vec (keep :seon.ref/name found))))))

(deftest ^{:seon/tasks #{'seon.ref/a-missing-anchor-ranks-headings-by-shared-prefix}}
  a-missing-anchor-names-the-closest-spelled-heading-first
  (doseq [reference ["AGENTS.md#Data-and-contracts" "AGENTS.md#bata-and-contracts"]]
    (is (= "data-and-contracts" (:seon.ref/anchor (first (:seon.ref/nearest (seon.ref/section reference)))))
        "a capital letter or a first-letter typo still ranks the heading first")))

(deftest a-missing-heading-reply-names-the-nearest-heading
  (let [reply (seon.error/reply-value nil (seon.ref/section "AGENTS.md#data-and-contract"))]
    (is (some #{"data-and-contracts"} (tree-seq coll? seq reply)) "the agent's reply carries the nearest heading")))
