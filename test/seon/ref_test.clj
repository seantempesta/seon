(ns seon.ref-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error]
            [seon.mcp :as mcp]
            [seon.ref :as ref]
            [seon.render :as render]
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
                      "{:seon.ref/text \"Use `seon.task/agent-task`, see [ledger](agent-loop-ledger.md#5-critical-path)"
                      " and `src/seon/task.clj:381`.\" %s}))")
           as-intent (shown running agent-id (format kinds ""))
           as-doc (shown running agent-id (format kinds ":seon.ref/document-path \"docs/seon/schedule/README.md\""))]
       (is (= [['seon.task/agent-task nil nil]
               [nil "agent-loop-ledger.md" :unresolved]
               [nil "src/seon/task.clj" :line-reference]]
              as-intent))
       (is (= (assoc as-intent 1 [nil "docs/seon/schedule/agent-loop-ledger.md" :anchor-missing]) as-doc)
           "against the doc's directory the link names a file that exists, without that heading")))))

(deftest fenced-code-and-prose-spans-are-not-references
  (test-support/with-running-agent
   (fn [running agent-id _]
     (is (= [] (shown running agent-id
                      "(seon.ref/references {:seon.ref/text \"```clojure\\n`seon.task/agent-task`\\n```\\n\\nAdd `(+ 1 2)`.\\n\"})"))))))

(deftest a-code-span-carries-its-reference-level
  ;; TASK-CHAIN §3a: the author picks the level with the delimiter, read from the code span's source span
  (are [text expected] (= expected (mapv (juxt :seon.ref/name :seon.ref/path :seon.ref/level)
                                         (ref/references (db/db) {:seon.ref/text text})))
    "Read seon.ref/locate first." [['seon.ref/locate nil :summary]]
    "Read `seon.ref/locate` first." [['seon.ref/locate nil :doc]]
    "Read ```seon.ref/locate``` first." [['seon.ref/locate nil :code]]
    "Read ````seon.ref/locate```` first." [['seon.ref/locate nil :code]]
    "Read the [guide](AGENTS.md#data-and-contracts) first." [[nil "AGENTS.md" :doc]]
    ;; two backticks wrap a literal: a code span, never a reference
    "Read ``seon.ref/locate`` first." []
    ;; a fenced block is code
    "```\nseon.ref/locate\n```\n" []))

(deftest front-matter-is-not-a-heading
  (let [{:seon.ref/keys [headings body-line]} (ref/document "---\ntitle: x\n---\n# A\n")]
    (is (= [["A" 4]] (mapv (juxt :seon.ref/heading :seon.ref/line) headings)) "the front matter's lines are no heading")
    (is (= 4 body-line) "the document starts at the line after the closing delimiter")))

(def ^:private templates "docs/seon/schedule/cards/TASK-CHAIN.md#4-templates-and-their-proofs-version-1-live-runs-revise-them")

(defn- cited-section
  "The one entry `text` makes, read from the doc `TASK-CHAIN.md`."
  [text]
  (first (ref/references (db/db) {:seon.ref/text text})))

(deftest a-section-doc-is-its-lead-and-headings-below
  (let [doc (cited-section (str "`" templates "`"))
        summary (cited-section (str "Read " templates "."))]
    (is (str/starts-with? (:seon.ref/lead doc) "Each stage's predicate is one contracted function") "the lead paragraph, whole")
    (is (not (contains? doc :seon.ref/text)) "never the whole section")
    (is (= "design-seonstepdesigntemplate" (:seon.ref/anchor (first (:seon.ref/below doc)))))
    (is (pos? (:seon.ref/tokens (first (:seon.ref/below doc)))))
    (is (not (:seon.ref/problem-kind doc)))
    (is (str/includes? (:seon.ref/lead doc) "a member body runs in its own") "the whole paragraph, past its first sentence")
    (is (= [nil true] [(:seon.ref/lead summary) (pos? (:seon.ref/tokens summary))]) "a summary is a sentence and a size")
    (is (str/starts-with? (:doc summary) "Each stage's predicate"))))

(deftest a-section-opening-with-a-list-has-no-lead
  (let [path "docs/seon/schedule/cards/TASK-CHAIN.md"
        listed (cited-section (str "`" path "#2-flow`"))
        tree (cited-section (str "`" path "#1-data`"))]
    (is (= [:no-lead path "2-flow"] ((juxt :seon.ref/problem-kind :seon.ref/path :seon.ref/anchor) listed)))
    (is (str/starts-with? (:seon.ref/opening listed) "1. **Create**") "the structural fallback: the first block, whole")
    (is (= :no-lead (:seon.ref/problem-kind tree)))
    (is (not (contains? tree :seon.ref/opening)) "a heading followed by a heading has the heading tree as its doc")
    (is (= "the-task" (:seon.ref/anchor (first (:seon.ref/below tree)))))
    (is (nil? (:seon.ref/problem-kind (cited-section (str "```" path "#2-flow```")))) "a code-level citation needs no lead")))

(deftest a-var-with-no-docstring-cited-for-its-summary-is-a-problem-on-the-var
  (test-support/with-running-agent
   (fn [running agent-id _]
     (shown running agent-id "(ns tq.nodoc)\n(defn bare {:malli/schema [:=> [:cat] :int]} [] 1)")
     (let [kinds #(shown running agent-id (str "(mapv (juxt :seon.ref/name :seon.ref/problem-kind) (seon.ref/references (seon.db/db) {:seon.ref/text \"" % "\"}))"))]
       (is (= [['tq.nodoc/bare :no-summary]] (kinds "Then tq.nodoc/bare follows.")))
       (is (= [['tq.nodoc/bare nil]] (kinds "`tq.nodoc/bare`")) "its doc value is not a summary")))))

(deftest a-repeated-parse-is-a-cache-hit
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [code "(let [t (str \"# \" (random-uuid))] (seon.ref/document t) (count (:seon.ref/headings (seon.ref/document t))))"
           [headings calls] (test-support/armed-calls '#{seon.ref/parsed} #(shown running agent-id code))]
       (is (= 1 headings))
       (is (= {'seon.ref/parsed 1} calls))))))

(deftest an-unloaded-library-name-resolves-from-the-dependency-cache
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [code (str "(let [found (seon.ref/references {:seon.ref/text \"```seon.operator/check!``` `rewrite-clj.paredit/slurp-backward` `rewrite-clj.paredit/nope`\"})]"
                     " (mapv (fn [r] (-> r (select-keys [:seon.ref/name :seon.ref/problem-kind :arglists :seon.ref/pin :source]) (update :seon.ref/pin :seon.ref/project))) found))")
           [operator slurp-backward absent] (shown running agent-id code)
           source (shown running agent-id "(with-out-str (clojure.repl/source rewrite-clj.paredit/slurp-backward))")]
       (is (string? (:source operator)) "a program function cited at its code shows its source")
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

(deftest a-function-cited-at-its-code-shows-its-source-whole
  (let [[entry] (ref/references (db/db) {:seon.ref/text "Read ```seon.ref/references``` whole."})]
    (is (= (:seon.fn/source (db/pull (db/db) [:seon.fn/source] [:seon.fn/sym 'seon.ref/references])) (:source entry))
        "a function at its code is its source, as code")))

(deftest an-alias-form-in-a-code-span-is-code-not-a-reference
  (let [found (seon.ref/references (seon.db/db) {:seon.ref/text "Call `str/subs` and `m/validator`, not `seon.ref/references`."})]
    (is (= ['seon.ref/references] (vec (keep :seon.ref/name found))))))

(deftest a-library-name-resolves-where-its-alias-form-is-code
  (let [found (seon.ref/references (seon.db/db) {:seon.ref/text "Alias `m/validator`, name `malli.core/validator`."})]
    (is (= [['malli.core/validator nil]] (mapv (juxt :seon.ref/name :seon.ref/problem-kind) found)))))

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

(deftest a-referenced-doc-shows-its-heading-size-and-one-read
  (let [[malli agents link missing-file missing-heading]
        (ref/references (db/db) {:seon.ref/text (str "Read `reference-code/malli/README.md#quickstart` and `AGENTS.md#data-and-contracts`,"
                                                     " the [vocabulary](docs/seon/architecture/vocabulary.md),"
                                                     " not `reference-code/malli/NO-SUCH.md` or `AGENTS.md#no-such-heading`.")})]
    (is (= ["reference-code/malli/README.md" "Quickstart"] [(:seon.ref/path malli) (:seon.ref/heading malli)]))
    (is (= 40 (count (:seon.ref/revision (:seon.ref/pin malli)))))
    (is (= ["AGENTS.md" "Data and contracts" nil] [(:seon.ref/path agents) (:seon.ref/heading agents) (:seon.ref/pin agents)]))
    (is (= "docs/seon/architecture/vocabulary.md" (:seon.ref/path link)) "a relative Markdown link is a path reference")
    (doseq [entry [malli agents link]]
      (is (not (contains? entry :seon.ref/text)) "the block never carries the document's text")
      (let [[operation] (:seon.render/operations (ref/render-doc-ai {:seon.render/value entry}))]
        (is (= 'my.fs/section (first operation)))
        (is (= (:seon.ref/heading entry) (:seon.ref/heading (ref/section (second operation)))) "the one read answers that section")))
    (is (= [:unresolved :anchor-missing] (mapv :seon.ref/problem-kind [missing-file missing-heading])))
    (is (= "no-such-heading" (:seon.ref/anchor missing-heading)))))

(deftest a-function-cited-at-its-doc-is-its-doc-value
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref/section`"})]
    (is (= ((requiring-resolve 'seon.sci.lookup/function-documentation) (db/db) 'seon.ref/section)
           (dissoc entry :seon.ref/name :seon.ref/level))
        "its doc value, with the schemas its contract names; no callers or tests pulled in")))

(deftest a-held-path-resolves-as-a-span-does
  (let [[entry] (ref/references (db/db) {:seon.ref/text "" :seon.ref/paths #{"AGENTS.md#data-and-contracts"}})]
    (is (= ["AGENTS.md" "data-and-contracts" "Data and contracts"]
           [(:seon.ref/path entry) (:seon.ref/anchor entry) (:seon.ref/heading entry)]))))

(deftest a-referenced-task-shows-its-intent
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref/documentation-and-source-take-a-path-string`"})]
    (is (= 'seon.ref/documentation-and-source-take-a-path-string (:seon.ref/name entry)))
    (is (string? (:seon.task/plan entry)) "a task resolves to its own content, its intent")))

(deftest a-referenced-test-shows-its-own-source
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref-test/a-span-of-a-name-and-more-words-is-prose`"})]
    (is (= #{:seon.ref/name :seon.ref/level :seon.test/sym :seon.test/source :seon.render/ai :seon.render/html} (set (keys entry)))
        "a test resolves to its own content and declared render, like any reference")
    (is (re-find #"a-span-of-a-name-and-more-words-is-prose" (:seon.test/source entry)))))

(defn- cited
  "The names `text`'s one referent cites in its own content, each as `seon.ref/brief` shows it."
  [text]
  (into {} (map (juxt :seon.ref/name identity)) (:seon.ref/references (first (ref/references (db/db) {:seon.ref/text text})))))

(deftest a-referents-own-references-show-one-level-for-every-text-kind
  (are [text name] (= [(list 'my.program/source (list 'quote name))] (:seon.render/operations (get (cited text) name)))
    ;; a task's intent
    "`seon.ref/documentation-and-source-take-a-path-string`" 'seon.ref/section
    ;; a doc section's text
    "`AGENTS.md#data-and-contracts`" 'seon.blob/inline-or-stage))

(deftest a-referents-own-reference-is-a-summary-and-a-read-never-its-content
  (let [brief (get (cited "`AGENTS.md#data-and-contracts`") 'seon.blob/inline-or-stage)]
    (is (= #{:seon.ref/name :doc :seon.render/operations} (set (keys brief))))
    (is (not (contains? brief :seon.ref/references)) "one level: a cited name's own references are not pulled")))

(deftest a-referenced-function-shows-its-contract-as-the-agent-writes-it
  (let [[entry] (ref/references (db/db) {:seon.ref/text "Read ```seon.ref/section``` whole."})]
    (is (str/includes? (:source entry) "[:=> [:cat :seon.ref/reference-text] [:or :seon.ref/section :seon.ref/section-error]]")
        "the contract is in its source, as the agent wrote it")
    (is (not (contains? entry :seon.fn/spec)))
    (is (not (contains? entry :seon.ref/line)) "a reference carries no line in its text")))

(deftest an-unresolved-reference-says-what-failed-and-what-to-do
  (let [[problem] (ref/references (db/db) {:seon.ref/text "`:seon.nope/missing-key`"})]
    (is (= :unresolved (:seon.ref/problem-kind problem)))
    (is (not (re-find #"(?i)create" (:seon.ref/message problem))) "it never tells the agent to create the name: the plan says that")
    (is (= '[(my.program/search "missing-key")] (:seon.render/operations problem)) "it offers the search form that finds what exists")))

(deftest a-reference-with-no-path-is-a-missing-file
  ;; section declares [:or :seon.ref/section :seon.ref/section-error]; an anchor with no path threw from locate's contract.
  (are [reference] (= :file (:seon.ref/section-missing (try (ref/section reference) (catch Exception e (ex-message e)))))
    "no-such-file.md#the-goal"
    "#the-goal"
    "#"))

(deftest a-reference-with-no-path-answers-an-error-that-holds-no-path
  ;; an empty path names no file: the error carries no :seon.ref/path (absence is no key) and lists the checkout root's Markdown
  (are [reference expected] (= expected
                               (let [error (ref/section reference)]
                                 [(:seon.ref/section-missing error) (:seon.ref/anchor error) (contains? error :seon.ref/path)
                                  (some? (some #{"README.md"} (:seon.ref/nearest error)))]))
    "#the-goal" [:file "the-goal" false true]
    "#" [:file nil false true]))

(deftest a-nested-docstring-problem-belongs-to-its-var
  (test-support/with-running-agent
   (fn [running agent-id _]
     (shown running agent-id
            (str "(ns tq.nested)\n"
                 "(defn cites\n  \"Return 1, after reading `seon.task/effective` and `seon.nope/inside`.\"\n"
                 "  {:malli/schema [:=> [:cat] :int]}\n  [] 1)"))
     (let [kinds (shown running agent-id
                        (str "(let [[entry direct] (seon.ref/references (seon.db/db) "
                             "{:seon.ref/text \"`tq.nested/cites` and `seon.ref/nope-direct`\"})] "
                             "{:inner (mapv (juxt :seon.ref/name :seon.ref/problem-kind) (:seon.ref/references entry)) "
                             ":direct ((juxt :seon.ref/name :seon.ref/problem-kind) direct)})"))]
       (is (= [] (:inner kinds))
           "a function cited at its doc carries its doc value, never the names its docstring cites")
       (is (= ['seon.ref/nope-direct :unresolved] (:direct kinds))
           "a name the task itself cites that resolves to nothing stays its problem")))))

(deftest a-schema-is-described-once-in-one-shown-unit
  ;; seon.ref/brief and seon.ref/answer both name :seon.db/database-value; the text also cites :seon.ref/problem itself
  (let [[brief answer] (ref/references (db/db) {:seon.ref/text "`seon.ref/brief`, `seon.ref/answer` and `:seon.ref/problem`"})]
    (is (contains? (:schemas brief) :seon.db/database-value) "the first doc value describes the schema")
    (is (not (contains? (:schemas answer) :seon.db/database-value)) "a later one leaves it out: its contract still names it")
    (is (not-any? #(contains? (:schemas %) :seon.ref/problem) [brief answer]) "a schema cited at top level is shown there")))

;;; TASK-CHAIN §5 A5 and A5b: one reference rule for all prose, one declared render per kind.

(defn- identities
  "The identity of each reference `text` makes on `database`, problems included: a name, or a path and anchor, and
  the problem kind when it has one."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:vector :seon.schema/value]]}
  [database text]
  (mapv (juxt :seon.ref/name :seon.ref/path :seon.ref/anchor :seon.ref/problem-kind)
        (ref/references database {:seon.ref/text text})))

(def ^:private colon-headings
  "The docstring section headings written as `Name:` lines, which the heading sweep converts to `## Name`."
  #{"Example:" "Examples:" "Edge cases:" "Errors:" "Known issues:"})

(deftest all-prose-uses-one-reference-rule
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [database (db/db)]
       (testing "explicit and bare forms of one name resolve to the same identity"
         (doseq [[explicit bare] [["`seon.task/obligations`" "seon.task/obligations"]
                                  ["`:seon.task/plan`" ":seon.task/plan"]
                                  ["`docs/seon/schedule/cards/TASK-CHAIN.md#7-slices`" "docs/seon/schedule/cards/TASK-CHAIN.md#7-slices"]]]
           (is (seq (identities database explicit)) "the explicit form resolves")
           (is (= (identities database explicit) (identities database (str "Read " bare " first.")))
               "the bare form names the same entity")))
       (are [text expected] (= expected (identities database text))
         ;; a namespace that exists resolves by its name
         "`my.agent`" [['my.agent nil nil nil]]
         ;; neither a dependency file nor a Java class is a problem
         "`deps.edn` and `java.lang.String`" [[nil "deps.edn" nil nil]]
         ;; an explicit qualified name that resolves to nothing stays a problem
         "`seon.ref/nope-x`" [['seon.ref/nope-x nil nil :unresolved]]
         ;; a bare token naming nothing is prose
         "Then seon.nope/x and :seon.nope/y follow." []
         ;; fenced code is code
         "```clojure\n(seon.task/obligations db)\n```\n" [])
       (is (= [:anchor-missing] (keep :seon.ref/problem-kind (ref/references database {:seon.ref/text "See [the phases](#6-phases)."})))
           "an anchor-only link in prose with no document is a problem")
       (testing "a docstring's citations are its definition's references"
         (shown running agent-id
                (str "(ns tq.prose-refs)\n"
                     "(defn cites\n  \"Return 1, after reading `seon.task/obligations` and seon.task/effective; it is `tq.prose-refs/cites`.\n\n"
                     "  ## Examples\n\n  ```clojure\n  (seon.task/declaration 1)\n  ```\"\n"
                     "  {:malli/schema [:=> [:cat] :int]}\n  [] 1)"))
         (is (= #{'seon.task/obligations 'seon.task/effective}
                (shown running agent-id "(set (:seon.fn/references (seon.db/pull (seon.db/db) [:seon.fn/references] [:seon.fn/sym 'tq.prose-refs/cites])))"))
             "mid-docstring explicit and bare citations, never itself or its fenced example code"))
       (is (empty? (for [[sym doc] (db/q '[:find ?s ?d :where [?e :seon.fn/doc ?d] [?e :seon.fn/sym ?s]] database)
                         :when (some (comp colon-headings str/trim) (str/split-lines doc))]
                     sym))
           "no docstring keeps a colon heading")))))

(deftest each-reference-kind-uses-its-declared-render
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           request {:seon.db/db database :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)
                    :seon.render/output :seon.render/ai :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 2000}
           selected (fn [text]
                      (let [[answer :as answers] (ref/references database {:seon.ref/text text})]
                        [(count answers) ((juxt :seon.ref/name :seon.ref/path :seon.ref/anchor) answer)
                         (:seon.render.selection/selected (render/selection (assoc request :seon.render/value answer)))]))]
       (doseq [[kind text expected render]
               [[:function "Merged by `seon.task/effective` down the children." ['seon.task/effective nil nil] 'seon.render.value/render-ai]
                [:function-bare "Merged by seon.task/effective down the children." ['seon.task/effective nil nil] 'seon.render.value/render-ai]
                [:deftest "`seon.ref-test/fenced-code-and-prose-spans-are-not-references`"
                 ['seon.ref-test/fenced-code-and-prose-spans-are-not-references nil nil] 'seon.render.test/render-ai]
                [:task "`seon.ref/documentation-and-source-take-a-path-string`"
                 ['seon.ref/documentation-and-source-take-a-path-string nil nil] 'seon.render.ns/task-ai]
                [:library-var "`malli.core/validator`" ['malli.core/validator nil nil] 'seon.render.value/render-ai]
                [:schema-key "`:seon.task/handle`" [:seon.task/handle nil nil] 'seon.render.value/render-ai]
                [:attribute "`:seon.task/plan`" [:seon.task/plan nil nil] 'seon.render.value/render-ai]
                [:error-type "`:my.fs/not-found-error`" [:my.fs/not-found-error nil nil] 'seon.render.value/render-ai]
                [:dial "`:seon.config.eval/time-limit-ms`" [:seon.config.eval/time-limit-ms nil nil] 'seon.render.value/render-ai]
                [:namespace "`my.agent`" ['my.agent nil nil] 'seon.render.ns/render-ai]
                [:document "`docs/seon/schedule/cards/TASK-CHAIN.md`" [nil "docs/seon/schedule/cards/TASK-CHAIN.md" nil] 'seon.ref/render-doc-ai]
                [:section-link "See [the phases](docs/seon/schedule/cards/TASK-CHAIN.md#7-slices)."
                 [nil "docs/seon/schedule/cards/TASK-CHAIN.md" "7-slices"] 'seon.ref/render-doc-ai]
                [:reference-code-section "`reference-code/malli/README.md#quickstart`"
                 [nil "reference-code/malli/README.md" "quickstart"] 'seon.ref/render-doc-ai]]]
         (testing (name kind)
           (is (= [1 expected render] (selected text)) "one reference, its entity, then its declared render")))
       (is (= [] (ref/references database {:seon.ref/text "Then seon.nope/x follows."}))
           "an unknown bare form is neither a reference nor a problem")))))

(deftest a-schema-key-doc-names-who-returns-it
  ;; TASK-CHAIN §3a: a schema key's doc is its form, description and `:returns`; takers wait for its code level
  (let [doc (first (ref/references (db/db) {:seon.ref/text "Read `:my.edit/parse-refused-error` first."}))]
    (is (= :doc (:seon.ref/level doc)))
    (is (some #{'seon.edit/single-form} (:returns doc)) "the functions whose output names the key")
    (is (not (contains? doc :takes)) "takers are the code level's")))
