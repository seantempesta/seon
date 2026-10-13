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

(deftest an-unloaded-library-name-resolves-from-the-dependency-cache
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [code (str "(let [found (seon.ref/references {:seon.ref/text \"`seon.operator/check!` `rewrite-clj.paredit/slurp-backward` `rewrite-clj.paredit/nope`\"})]"
                     " (mapv (fn [r] (-> r (select-keys [:seon.ref/name :seon.ref/problem-kind :arglists :seon.ref/pin :seon.fn/source]) (update :seon.ref/pin :seon.ref/project))) found))")
           [operator slurp-backward absent] (shown running agent-id code)
           source (shown running agent-id "(with-out-str (clojure.repl/source rewrite-clj.paredit/slurp-backward))")]
       (is (string? (:seon.fn/source operator)) "a program function shows its source")
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

(deftest a-referenced-function-shows-its-source-whole
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref/references`"})]
    (is (= (:seon.fn/source (db/pull (db/db) [:seon.fn/source] [:seon.fn/sym 'seon.ref/references])) (:seon.fn/source entry))
        "a function is its source, as code")))

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
      (let [[operation] (:seon.render/operations entry)]
        (is (= 'my.fs/section (first operation)))
        (is (= (:seon.ref/heading entry) (:seon.ref/heading (ref/section (second operation)))) "the one read answers that section")))
    (is (= [:unresolved :anchor-missing] (mapv :seon.ref/problem-kind [missing-file missing-heading])))
    (is (= "no-such-heading" (:seon.ref/anchor missing-heading)))))

(deftest a-referenced-function-offers-its-callers-and-reaching-tests-as-forms
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref/section`"})]
    (is (= ['(my.program/callers 'seon.ref/section) '(my.program/tests-reaching 'seon.ref/section)]
           (:seon.render/operations entry))
        "the forms that read its callers and its reaching tests")
    (is (= #{:seon.ref/name :seon.fn/source :malli/schema :seon.render/operations :seon.ref/references} (set (keys entry)))
        "its own content only: no callers or tests pulled in")))

(deftest a-held-path-resolves-as-a-span-does
  (let [[entry] (ref/references (db/db) {:seon.ref/text "" :seon.ref/paths #{"AGENTS.md#data-and-contracts"}})]
    (is (= ["AGENTS.md" "data-and-contracts" "Data and contracts"]
           [(:seon.ref/path entry) (:seon.ref/anchor entry) (:seon.ref/heading entry)]))))

(deftest a-referenced-task-shows-its-intent
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref/a-reference-with-no-path-is-a-missing-file`"})]
    (is (= 'seon.ref/a-reference-with-no-path-is-a-missing-file (:seon.ref/name entry)))
    (is (string? (:seon.task/intent entry)) "a task resolves to its own content, its intent")))

(deftest a-referenced-test-shows-its-own-source
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref-test/a-span-of-a-name-and-more-words-is-prose`"})]
    (is (= #{:seon.ref/name :seon.test/source :seon.ref/references} (set (keys entry))) "a test resolves to its own content, like any reference")
    (is (re-find #"a-span-of-a-name-and-more-words-is-prose" (:seon.test/source entry)))))

(defn- cited
  "The names `text`'s one referent cites in its own content, each as `seon.ref/brief` shows it."
  [text]
  (into {} (map (juxt :seon.ref/name identity)) (:seon.ref/references (first (ref/references (db/db) {:seon.ref/text text})))))

(deftest a-referents-own-references-show-one-level-for-every-text-kind
  (are [text name] (= [(list 'my.program/source (list 'quote name))] (:seon.render/operations (get (cited text) name)))
    ;; a function's docstring
    "`my.task/current`" 'seon.context/task-sections
    ;; a test body's program calls
    "`seon.ref-test/a-span-of-a-name-and-more-words-is-prose`" 'seon.ref/references
    ;; a task's intent
    "`seon.ref/a-reference-with-no-path-is-a-missing-file`" 'seon.ref/locate
    ;; a schema's description
    "`:seon.batch/inserted`" 'seon.sci.forgive/forgive
    ;; a doc section's text
    "`AGENTS.md#data-and-contracts`" 'seon.blob/inline-or-stage))

(deftest a-referents-own-reference-is-a-summary-and-a-read-never-its-content
  (let [brief (get (cited "`my.task/current`") 'seon.context/task-sections)]
    (is (= #{:seon.ref/name :doc :seon.render/operations} (set (keys brief))))
    (is (not (contains? brief :seon.ref/references)) "one level: a cited name's own references are not pulled")))

(deftest a-referenced-function-shows-its-contract-as-the-agent-writes-it
  (let [[entry] (ref/references (db/db) {:seon.ref/text "`seon.ref/section`"})]
    (is (= [:=> [:cat :seon.ref/reference-text] [:or :seon.ref/section :seon.ref/section-error]] (:malli/schema entry))
        "the contract is the :malli/schema form, as data")
    (is (not (contains? entry :seon.fn/spec)))
    (is (not (contains? entry :seon.ref/line)) "a reference carries no line in its text")))

(deftest an-unresolved-reference-says-what-failed-and-what-to-do
  (let [[problem] (ref/references (db/db) {:seon.ref/text "`:seon.nope/missing-key`"})]
    (is (= :unresolved (:seon.ref/problem-kind problem)))
    (is (re-find #"my.message/send" (:seon.ref/message problem)) "it names what to do")))

(deftest ^{:seon/tasks #{'seon.ref/a-reference-with-no-path-is-a-missing-file}}
  a-reference-with-no-path-is-a-missing-file
  ;; section declares [:or :seon.ref/section :seon.ref/section-error]; an anchor with no path threw from locate's contract.
  (are [reference] (= :file (:seon.ref/section-missing (try (ref/section reference) (catch Exception e (ex-message e)))))
    "no-such-file.md#the-goal"
    "#the-goal"
    "#"))

(deftest ^{:seon/tasks #{'seon.ref/a-reference-with-no-path-is-a-missing-file}}
  a-reference-with-no-path-answers-an-error-that-holds-no-path
  ;; an empty path names no file: the error carries no :seon.ref/path (absence is no key) and lists the checkout root's Markdown
  (are [reference expected] (= expected
                               (let [error (ref/section reference)]
                                 [(:seon.ref/section-missing error) (:seon.ref/anchor error) (contains? error :seon.ref/path)
                                  (some? (some #{"README.md"} (:seon.ref/nearest error)))]))
    "#the-goal" [:file "the-goal" false true]
    "#" [:file nil false true]))

(deftest a-name-resolved-at-top-level-is-cited-by-name-below
  ;; my.task/current's docstring names seon.context/task-sections; the text cites both, so the second shows once
  (let [[current task-sections] (ref/references (db/db) {:seon.ref/text "`my.task/current` and `seon.context/task-sections`"})
        below (first (filter #(= 'seon.context/task-sections (:seon.ref/name %)) (:seon.ref/references current)))]
    (is (some? (:seon.fn/source task-sections)) "at top level the name shows its content")
    (is (= {:seon.ref/name 'seon.context/task-sections} below) "below it is cited by name only")))

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

(deftest ^{:seon/tasks #{'seon.task/chain-prose}}
  all-prose-uses-one-reference-rule
  (test-support/with-running-agent
   (fn [running agent-id _]
     (let [database (db/db)]
       (testing "explicit and bare forms of one name resolve to the same identity"
         (doseq [[explicit bare] [["`seon.task/obligations`" "seon.task/obligations"]
                                  ["`:seon.task/intent`" ":seon.task/intent"]
                                  ["`docs/seon/schedule/cards/TASK-CHAIN.md#6-phases`" "docs/seon/schedule/cards/TASK-CHAIN.md#6-phases"]]]
           (is (seq (identities database explicit)) "the explicit form resolves")
           (is (= (identities database explicit) (identities database (str "Read " bare " first.")))
               "the bare form names the same entity")))
       (are [text expected] (= expected (identities database text))
         ;; a namespace that exists resolves by its name
         "`my.agent`" [['my.agent nil nil nil]]
         ;; neither a dependency file nor a Java class is a problem
         "`deps.edn` and `java.lang.String`" [[nil "deps.edn" nil nil]]
         ;; an explicit qualified name that resolves to nothing stays a problem
         "`seon.nope/x`" [['seon.nope/x nil nil :unresolved]]
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

(deftest ^{:seon/tasks #{'seon.task/chain-prose}}
  each-reference-kind-uses-its-declared-render
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
               [[:function "Merged by `seon.task/effective` down the children." ['seon.task/effective nil nil] 'seon.render.ns/function-ai]
                [:function-bare "Merged by seon.task/effective down the children." ['seon.task/effective nil nil] 'seon.render.ns/function-ai]
                [:deftest "`seon.ref-test/fenced-code-and-prose-spans-are-not-references`"
                 ['seon.ref-test/fenced-code-and-prose-spans-are-not-references nil nil] 'seon.render.test/render-ai]
                [:task "`seon.ref/a-reference-with-no-path-is-a-missing-file`"
                 ['seon.ref/a-reference-with-no-path-is-a-missing-file nil nil] 'seon.render.ns/task-ai]
                [:library-var "`malli.core/validator`" ['malli.core/validator nil nil] 'seon.render.ns/function-ai]
                [:schema-key "`:seon.task/handle`" [:seon.task/handle nil nil] 'seon.render.schema/render-ai]
                [:attribute "`:seon.task/intent`" [:seon.task/intent nil nil] 'seon.render.schema/render-ai]
                [:error-type "`:my.fs/not-found-error`" [:my.fs/not-found-error nil nil] 'seon.render.schema/render-ai]
                [:dial "`:seon.config.eval/time-limit-ms`" [:seon.config.eval/time-limit-ms nil nil] 'seon.render.schema/render-ai]
                [:namespace "`my.agent`" ['my.agent nil nil] 'seon.render.ns/render-ai]
                [:document "`docs/seon/schedule/cards/TASK-CHAIN.md`" [nil "docs/seon/schedule/cards/TASK-CHAIN.md" nil] 'seon.ref/render-doc-ai]
                [:section-link "See [the phases](docs/seon/schedule/cards/TASK-CHAIN.md#6-phases)."
                 [nil "docs/seon/schedule/cards/TASK-CHAIN.md" "6-phases"] 'seon.ref/render-doc-ai]
                [:reference-code-section "`reference-code/malli/README.md#quickstart`"
                 [nil "reference-code/malli/README.md" "quickstart"] 'seon.ref/render-doc-ai]]]
         (testing (name kind)
           (is (= [1 expected render] (selected text)) "one reference, its entity, then its declared render")))
       (is (= [] (ref/references database {:seon.ref/text "Then seon.nope/x follows."}))
           "an unknown bare form is neither a reference nor a problem")))))
