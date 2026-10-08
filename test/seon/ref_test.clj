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
       (is (= (assoc as-intent 1 [nil "docs/seon/schedule/agent-loop-ledger.md" :anchor-missing]) as-doc)
           "against the doc's directory the link names a file that exists, without that heading")))))

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
        source-form (some #(when (= 'my.program/source (first %)) %) (:seon.render/operations (#'ref/resolve-name database 'seon.ref/references)))]
    (is (= '(my.program/source 'seon.ref/references) source-form))
    (is (string? (:source (program/source database (second (second source-form))))))))

(deftest a-span-of-a-name-and-more-words-is-prose
  (let [found (seon.ref/references (seon.db/db) {:seon.ref/text "Use `seon.ref/references`, not `seon.ref/section AGENTS.md` or `foo/bar baz`."})]
    (is (= ['seon.ref/references] (vec (keep :seon.ref/name found))))))

(deftest a-missing-anchor-names-the-closest-spelled-heading-first
  (doseq [reference ["AGENTS.md#Data-and-contracts" "AGENTS.md#bata-and-contracts"]]
    (is (= "data-and-contracts" (:seon.ref/anchor (first (:seon.ref/nearest (seon.ref/section reference)))))
        "a capital letter or a first-letter typo still ranks the heading first")))

(deftest a-missing-heading-reply-names-the-nearest-heading
  (let [reply (seon.error/reply-value nil (seon.ref/section "AGENTS.md#data-and-contract"))]
    (is (some #{"data-and-contracts"} (tree-seq coll? seq reply)) "the agent's reply carries the nearest heading")))

(deftest ^{:seon/tasks #{'seon.ref/a-referenced-doc-path-reads-as-unresolved}}
  a-referenced-doc-shows-its-heading-size-and-one-read
  (let [[malli agents link missing-file missing-heading]
        (ref/references (db/db) {:seon.ref/text (str "Read `reference-code/malli/README.md#quickstart` and `AGENTS.md#data-and-contracts`,"
                                                     " the [vocabulary](docs/seon/architecture/vocabulary.md),"
                                                     " not `reference-code/malli/NO-SUCH.md` or `AGENTS.md#no-such-heading`.")})]
    (is (= ["reference-code/malli/README.md" "Quickstart"] [(:seon.ref/path malli) (:seon.ref/heading malli)]))
    (is (= 40 (count (:seon.ref/revision (:seon.ref/pin malli)))))
    (is (= ["AGENTS.md" "Data and contracts" nil] [(:seon.ref/path agents) (:seon.ref/heading agents) (:seon.ref/pin agents)]))
    (is (= "docs/seon/architecture/vocabulary.md" (:seon.ref/path link)) "a relative Markdown link is a path reference")
    (doseq [entry [malli agents link]]
      (is (pos? (:seon.ref/tokens entry)))
      (is (not (contains? entry :seon.ref/text)) "the block never carries the document's text")
      (let [[operation] (:seon.render/operations entry)]
        (is (= 'my.fs/section (first operation)))
        (is (= (:seon.ref/heading entry) (:seon.ref/heading (ref/section (second operation)))) "the one read answers that section")))
    (is (= [:unresolved :anchor-missing] (mapv :seon.ref/problem-kind [missing-file missing-heading])))
    (is (= "no-such-heading" (:seon.ref/anchor missing-heading)))))

(deftest ^{:seon/tasks #{'seon.ref/a-referenced-function-shows-no-callers-or-tests}}
  a-referenced-function-shows-its-callers-and-reaching-tests
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref/section`"})
        operations (set (:seon.render/operations entry))]
    (is (some #{'my.fs/section} (:seon.ref/callers entry)) "its direct callers, as symbols")
    (is (some #{'seon.ref-test/a-missing-anchor-names-the-closest-spelled-heading-first} (:seon.ref/tests entry))
        "the tests reaching it, as symbols")
    (is (operations '(my.program/callers {:seon.program/subject 'seon.ref/section})))
    (is (operations '(my.program/tests-reaching {:seon.program/subject 'seon.ref/section})))))
