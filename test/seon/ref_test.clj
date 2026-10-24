(ns seon.ref-test
  "References in Markdown text by one parse and one rule (seon.ref; v1's ref_test ported):
  the delimiter picks the depth, a literal and code are never references, a bare word counts
  only when it names something, a name shows once at its strongest depth, a line citation is
  its file marked :line-reference, a section's summary is its heading and its lead's first
  sentence; the scanner meets CommonMark 0.31.2's examples for its subset in one pass."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is are testing]] [seon.contract :refer [defn defn-]]
            [clojure.string :as str] [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.ref :as ref]))

(defn- kinds
  "What text cites, each once at its strongest depth: [name path anchor problem-kind depth]."
  {:malli/schema [:=> [:cat [:maybe :string] :string] [:vector :any]]}
  [path text]
  (mapv (juxt :seon.ref/name :seon.ref/path :seon.ref/anchor :seon.ref/problem-kind :seon.ref/depth)
        (ref/cited (:seon.ref/citations (ref/parse path text)))))

(defn- program-db
  "A branch of the seeded test store (app.m0/f0 ... are its vars) with one namespace and var more."
  {:malli/schema [:=> [:cat] :seon.host/db]}
  []
  (d/db-with (ts/test-db) [{:db/id "ns" :ns/name 'demo.calc} {:var/name 'demo.calc/add :var/ns "ns"}]))

(defn- names-on
  "The names of the references text makes on db."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:vector :any]]}
  [db text] (mapv :seon.ref/name (ref/references db text)))

(deftest one-text-gives-names-paths-and-line-citations-by-one-rule
  (let [text "Use `seon.ref/parse`, see [the API](docs/flow.md#the-api), `:seon.task/plan` and `src/seon/writer.clj:263`."]
    (is (= [['seon.ref/parse nil nil nil :doc]
            [nil "docs/flow.md" "the-api" nil :doc]
            [:seon.task/plan nil nil nil :doc]
            [nil "src/seon/writer.clj" nil :line-reference :doc]]
           (kinds nil text)))
    (is (= [nil "docs/flow.md" "the-api" nil :doc]
           (second (kinds "docs/README.md" "Use `seon.ref/parse`, see [the API](flow.md#the-api).")))
        "a relative link resolves against the document's directory")
    (is (= [nil "AGENTS.md" "3-laws" nil :doc] (first (kinds "docs/flow.md" "[the laws](../AGENTS.md#3-laws)"))))
    (is (= [[nil "docs/flow.md" "the-api" nil :doc]] (kinds "docs/flow.md" "[above](#the-api)"))
        "an anchor alone names its own document")
    (is (= [[nil nil "the-api" :anchor-missing :doc]] (kinds nil "[above](#the-api)"))
        "with no document of its own, an anchor names nothing")
    (is (= [] (kinds nil "[the spec](https://spec.commonmark.org/0.31.2/#links) and <mailto:a@b.c>")) "a URL is no reference")))

(deftest the-delimiter-picks-the-depth
  ;; TASK-CHAIN §3a: a bare word its summary, one backtick or a link its doc, three or more its code
  (are [text expected] (= expected (mapv (juxt :seon.ref/name :seon.ref/path :seon.ref/depth :seon.ref/resolve-only)
                                         (ref/cited (:seon.ref/citations (ref/parse nil text)))))
    "Read seon.ref/parse first." [['seon.ref/parse nil :summary true]]
    "Read `seon.ref/parse` first." [['seon.ref/parse nil :doc nil]]
    "Read ```seon.ref/parse``` first." [['seon.ref/parse nil :code nil]]
    "Read ````seon.ref/parse```` first." [['seon.ref/parse nil :code nil]]
    "Read the [guide](AGENTS.md#3-laws) first." [[nil "AGENTS.md" :doc nil]]
    "Read docs/flow.md#the-api first." [[nil "docs/flow.md" :summary true]]
    "Read ``seon.ref/parse`` first." []))

(deftest a-literal-and-fenced-code-are-not-references
  (are [text] (= [] (kinds nil text))
    ;; two backticks wrap a literal
    "Mention ``seon.nope/x`` and ``:seon.nope/y`` as text."
    ;; fenced code, backtick and tilde, with an info string
    "```clojure\n(seon.ref/parse nil \"x\")\n`seon.ref/parse`\n```\n"
    "~~~\n`seon.ref/parse` seon.ref/parse\n~~~\n"
    ;; a four-backtick fence is not closed by three
    "````\n```\n`seon.ref/parse`\n````\n"
    ;; an indented code block
    "Text.\n\n    `seon.ref/parse` seon.ref/parse\n"
    ;; a form is code, not the names in it
    "Add `(+ 1 2)` and `(my.task/current)`.")
  (is (= [['seon.ref/names nil nil nil :doc]] (kinds nil "```\nseon.ref/parse\n```\nthen `seon.ref/names`"))
      "prose after a closed fence is read again")
  (is (= [] (kinds nil "```\nnever closed\n`seon.ref/parse`\n")) "an unclosed fence runs to the end"))

(deftest a-bare-word-is-a-reference-only-when-it-names-something
  (let [db (program-db)]
    (is (= ['app.m0/f1] (names-on db "Then app.m0/f1 calls app.m0/nope and seon.nope/x; :seon.nope/y too."))
        "a bare word naming a var on the branch is a reference; one naming nothing is prose")
    (is (= [:seon.task/plan] (names-on db "Write :seon.task/plan, not :seon.nope/key."))
        "a bare keyword counts when it is a registered schema")
    (is (= ['app.m0/nope] (names-on db "Write `app.m0/nope`.")) "an explicit name that resolves to nothing stays a reference")
    (is (= [[nil "docs/flow.md"]] (mapv (juxt :seon.ref/name :seon.ref/path) (ref/references db "See docs/flow.md and docs/nope.md.")))
        "a bare path counts when its file exists")))

(deftest an-alias-form-in-a-code-span-is-code-not-a-reference
  (is (= ['demo.calc/add] (names-on (program-db) "Call `str/subs` and `m/validator`, not `demo.calc/add`."))))

(deftest a-name-is-shown-once-at-its-strongest-depth
  (is (= [['app.m0/f1 :code 1 nil] ['app.m0/f2 :doc 2 nil]]
         (mapv (juxt :seon.ref/name :seon.ref/depth :seon.ref/line :seon.ref/resolve-only)
               (ref/references (program-db) "Read app.m0/f1, then `app.m0/f1`,\n```app.m0/f1``` and `app.m0/f2`, then app.m0/f1."))))
  (is (= [['app.m0/f1 :doc nil]] (mapv (juxt :seon.ref/name :seon.ref/depth :seon.ref/resolve-only)
                                       (ref/cited (:seon.ref/citations (ref/parse nil "app.m0/f1 then `app.m0/f1`")))))
      "an explicit mention makes the name explicit")
  (is (= 3 (count (:seon.ref/citations (ref/parse nil "`a.b/c` `a.b/c` a.b/c")))) "the parse keeps every mention for its store"))

(deftest a-line-citation-is-its-file-marked-line-reference
  (are [text expected] (= expected (kinds nil text))
    "`src/seon/writer.clj:263`" [[nil "src/seon/writer.clj" nil :line-reference :doc]]
    "`src/seon/writer.clj:263-270`" [[nil "src/seon/writer.clj" nil :line-reference :doc]]
    "See src/seon/writer.clj:263." [[nil "src/seon/writer.clj" nil :line-reference :summary]]
    ;; not based at the checkout: prose
    "`async.ss:525-541`" []
    ;; the file cited plainly is its own reference beside the line citation
    "`src/seon/writer.clj` and `src/seon/writer.clj:9`" [[nil "src/seon/writer.clj" nil nil :doc]
                                                          [nil "src/seon/writer.clj" nil :line-reference :doc]]))

(deftest a-span-is-read-by-one-rule
  ;; seon.references-test's rows (next-review #3), converted to v1's rule: only a span of
  ;; exactly one qualified symbol or keyword is a name, so a form is code, not the names in it
  (are [span expected] (= expected (kinds nil (str "`" span "`")))
    "app.calc/add" [['app.calc/add nil nil nil :doc]]
    ":seon.task/plan" [[:seon.task/plan nil nil nil :doc]]
    "docs/failure-points.md" [[nil "docs/failure-points.md" nil nil :doc]]
    "docs/failure-points.md#physics" [[nil "docs/failure-points.md" "physics" nil :doc]]
    "src/seon/repl.clj" [[nil "src/seon/repl.clj" nil nil :doc]]
    "(my.task/current)" []
    "add" []
    "a b c" []
    "3" []
    "(unbalanced" [])
  (is (= '[app.calc/add] (ref/names "`:seon.task/plan` `docs/failure-points.md` `(my.task/current)` `app.calc/add`"))
      "the review's reproduction: one var, the form is code")
  (is (= '[app.calc/twice] (ref/names "Use `app.calc/twice`.\n```clojure\n(app.calc/add `x` 1)\n```\nnot `prose with/slash`")))
  (is (= [] (ref/names ""))))

(deftest a-span-of-a-name-and-more-words-is-prose
  (is (= [['seon.ref/parse nil nil nil :doc]]
         (kinds nil "Use `seon.ref/parse`, not `seon.ref/view docs/flow.md` or `foo/bar baz` or `docs/flow.md and more`."))))

(deftest a-citation-ending-in-a-wildcard-is-prose
  (is (= [['seon.ref/parse nil nil nil :doc]]
         (kinds nil "`seon.ref/parse`, not `:seon.render.unknown/*` and `seon.task.run/*` and seon.task.run/*"))))

(def ^:private page
  (str "# Flow\n\nFlow runs procs, e.g. steps. Each proc is a Var.\nSee `seon.step/step`.\n\n"
       "## Workloads\n\n- `:io` a fiber\n- `:compute` a pool\n\n"
       "## The API\n\n### create-flow\n\nPlans procs.\n\n### start\n\n| a | b |\n|---|---|\n\n"
       "## The API\n\nAgain.\n"))

(deftest a-section-summary-is-its-heading-and-leads-first-sentence
  (let [doc (ref/parse "docs/flow.md" page)]
    (is (= {:seon.ref/address "docs/flow.md#flow" :seon.ref/heading "Flow" :seon.ref/summary "Flow runs procs, e.g. steps."}
           (ref/view doc "docs/flow.md#flow" :summary))
        "an abbreviation's period ends no sentence")
    (is (= :no-lead (:seon.ref/problem-kind (ref/view doc "docs/flow.md#workloads" :summary))) "a list is no lead")
    (is (= :no-lead (:seon.ref/problem-kind (ref/view doc "docs/flow.md#the-api" :summary))) "a heading is no lead")
    (is (= :no-lead (:seon.ref/problem-kind (ref/view doc "docs/flow.md#start" :doc))) "a table is no lead")
    (is (nil? (:seon.ref/problem-kind (ref/view doc "docs/flow.md#the-api" :code))) "a code-depth citation needs no lead")
    (is (= "Again." (:seon.ref/summary (ref/view doc "docs/flow.md#the-api-1" :summary))) "a repeated heading's anchor is suffixed")
    (is (nil? (ref/view doc "docs/flow.md#nope" :summary)))))

(deftest a-section-doc-is-its-lead-and-headings-below
  (let [doc (ref/parse "docs/flow.md" page)
        flow (ref/view doc "docs/flow.md#flow" :doc)
        api (ref/view doc "docs/flow.md#the-api" :doc)]
    (is (= "Flow runs procs, e.g. steps. Each proc is a Var.\nSee `seon.step/step`." (:seon.ref/lead flow)) "the lead paragraph, whole")
    (is (= ["docs/flow.md#workloads" "docs/flow.md#the-api" "docs/flow.md#the-api-1"] (mapv :seon.ref/address (:seon.ref/below flow))))
    (is (= ["create-flow" "start"] (mapv :seon.ref/heading (:seon.ref/below api))) "the headings below, one level")
    (is (= [1 6 11] (mapv :seon.ref/line (map #(nth (:seon.ref/sections doc) %) [0 1 2]))))
    (is (= ["Flow runs procs, e.g. steps. Each proc is a Var.\nSee `seon.step/step`." nil]
           (mapv :seon.ref/text (map #(nth (:seon.ref/sections doc) %) [0 2])))
        "a section's own text stops at its first heading below; one opening straight into a heading has none")
    (is (= ["docs/flow.md#the-api" "docs/flow.md#flow"] (mapv :seon.ref/parent (map #(nth (:seon.ref/sections doc) %) [3 2]))))))

(deftest a-section-opening-with-a-list-has-no-lead
  (let [doc (ref/parse "docs/flow.md" page)
        listed (ref/view doc "docs/flow.md#workloads" :doc)]
    (is (= :no-lead (:seon.ref/problem-kind listed)))
    (is (= "- `:io` a fiber\n- `:compute` a pool" (:seon.ref/opening listed)) "its first block, whole")
    (is (not (contains? (ref/view doc "docs/flow.md#the-api" :doc) :seon.ref/opening)) "a heading followed by a heading opens with none")))

(deftest commonmark-examples-for-the-subset
  ;; CommonMark 0.31.2 (spec.commonmark.org/0.31.2/spec.json), by example number: the code
  ;; spans (content, width) and link destinations the scan reads, in order
  (testing "code spans (6.1)"
    (are [example md spans] (= spans (mapv (juxt :seon.ref/code :seon.ref/width) (:seon.ref/spans (ref/scan md))))
      328 "`foo`\n" [["foo" 1]]
      329 "`` foo ` bar ``\n" [["foo ` bar" 2]]
      330 "` `` `\n" [["``" 1]]
      331 "`  ``  `\n" [[" `` " 1]]
      332 "` a`\n" [[" a" 1]]
      334 "`\u00a0`\n`  `\n" [["\u00a0" 1] ["  " 1]]
      335 "``\nfoo\nbar  \nbaz\n``\n" [["foo bar   baz" 2]]
      336 "``\nfoo \n``\n" [["foo " 2]]
      337 "`foo   bar \nbaz`\n" [["foo   bar  baz" 1]]
      338 "`foo\\`bar`\n" [["foo\\" 1]]
      339 "``foo`bar``\n" [["foo`bar" 2]]
      340 "` foo `` bar `\n" [["foo `` bar" 1]]
      341 "*foo`*`\n" [["*" 1]]
      342 "[not a `link](/foo`)\n" [["link](/foo" 1]]
      347 "```foo``\n" []
      348 "`foo\n" []
      349 "`foo``bar``\n" [["bar" 2]]))
  (testing "inline links (6.3)"
    (are [example md dests] (= dests (keep :seon.ref/link (:seon.ref/spans (ref/scan md))))
      482 "[link](/uri \"title\")\n" ["/uri"]
      483 "[link](/uri)\n" ["/uri"]
      484 "[](./target.md)\n" ["./target.md"]
      485 "[link]()\n" [""]
      486 "[link](<>)\n" [""]
      488 "[link](/my uri)\n" []
      489 "[link](</my uri>)\n" ["/my uri"]
      490 "[link](foo\nbar)\n" []
      492 "[a](<b)c>)\n" ["b)c"]
      496 "[link](foo(and(bar)))\n" ["foo(and(bar))"]
      497 "[link](foo(and(bar))\n" []
      501 "[link](#fragment)\n\n[link](https://example.com#fragment)\n" ["#fragment" "https://example.com#fragment"]
      505 "[link](/url \"title\")\n[link](/url 'title')\n[link](/url (title))\n" ["/url" "/url" "/url"]
      508 "[link](/url \"title \"and\" title\")\n" []
      510 "[link](   /uri\n  \"title\"  )\n" ["/uri"]
      511 "[link] (/uri)\n" []
      512 "[link [foo [bar]]](/uri)\n" ["/uri"]
      513 "[link] bar](/uri)\n" []
      514 "[link [bar](/uri)\n" ["/uri"]
      525 "[foo`](/uri)`\n" []))
  (testing "ATX headings (4.2): level and text"
    (are [example md headings] (= headings (mapv :seon.ref/heading-level (filter #(= :heading (:seon.ref/kind %)) (:seon.ref/blocks (ref/scan md)))))
      62 "# foo\n## foo\n### foo\n#### foo\n##### foo\n###### foo\n" [1 2 3 4 5 6]
      63 "####### foo\n" []
      64 "#5 bolt\n\n#hashtag\n" []
      65 "\\## foo\n" []
      68 " ### foo\n  ## foo\n   # foo\n" [3 2 1]
      69 "    # foo\n" []
      70 "foo\n    # bar\n" []
      77 "****\n## foo\n****\n" [2]
      78 "Foo bar\n# baz\nBar foo\n" [1]
      79 "## \n#\n### ###\n" [2 1 3])
    (are [example md texts] (= texts (mapv :seon.ref/heading (:seon.ref/sections (ref/parse nil md))))
      66 "# foo *bar* \\*baz\\*\n" ["foo bar *baz*"]
      67 "#                  foo                     \n" ["foo"]
      71 "## foo ##\n  ###   bar    ###\n" ["foo" "bar"]
      72 "# foo ##################################\n##### foo ##\n" ["foo" "foo"]
      73 "### foo ###     \n" ["foo"]
      74 "### foo ### b\n" ["foo ### b"]
      75 "# foo#\n" ["foo#"]
      76 "### foo \\###\n## foo #\\##\n# foo \\#\n" ["foo ###" "foo ###" "foo #"]))
  (testing "fenced code blocks (4.5): what is a fence and where it ends"
    (are [example md kinds] (= kinds (mapv :seon.ref/kind (:seon.ref/blocks (ref/scan md))))
      119 "```\n<\n >\n```\n" [:fence]
      120 "~~~\n<\n >\n~~~\n" [:fence]
      121 "``\nfoo\n``\n" [:para]
      122 "```\naaa\n~~~\n```\n" [:fence]
      124 "````\naaa\n```\n``````\n" [:fence]
      126 "```\n" [:fence]
      127 "`````\n\n```\naaa\n" [:fence]
      131 " ```\n aaa\naaa\n```\n" [:fence]
      134 "    ```\n    aaa\n    ```\n" [:code]
      137 "```\naaa\n    ```\n" [:fence]
      138 "``` ```\naaa\n" [:para]
      140 "foo\n```\nbar\n```\nbaz\n" [:para :fence :para]
      145 "``` aa ```\nfoo\n" [:para]
      146 "~~~ aa ``` ~~~\nfoo\n~~~\n" [:fence]
      147 "```\n``` aaa\n```\n" [:fence])
    (is (= [] (:seon.ref/spans (ref/scan "```\n`a.b/c`\n``` aaa\n```\n"))) "147: an info string closes no fence")))

(defn markdown
  "The input of size n: Markdown of about n bytes, a doc page's mix of headings, prose with
  code spans, links and bare names, lists and fenced code, every block a few lines."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:maybe :string] :string]]}
  [n]
  (let [unit (str "## Section\n\nA lead that cites `seon.ref/parse` and [flow](docs/flow.md#the-api), then app.m0/f1.\n"
                  "It goes on with ```seon.ref/scan``` and ``a literal``.\n\n- `:io` a fiber\n- docs/flow.md\n\n"
                  "```clojure\n(seon.ref/parse nil \"x\")\n```\n\n")]
    ["docs/page.md" (apply str (repeat (max 1 (quot n (count unit))) unit))]))

(deftest one-parse-costs-one-pass
  (cost/proof
    {:of 'seon.ref/parse :input 'seon.ref-test/markdown
     :n "bytes of Markdown text"
     :work (str "one pass over the text: index-of finds each line's end, a few chars of each line pick its block, and "
                "each prose char is read once by charAt (~15 ns, nothing allocated); per block a map (~0.5 us, ~0.5 KB); "
                "per inline (a code span, link or word holding a /) its string, its span and citation maps and ~3 "
                "contracted calls (~2 us, ~3 KB); per candidate name one seon.reader/read-text (edamame: ~13 us, ~18 KB, "
                "3 calls); per section its heading, anchor (1 call) and lead copy (~3 us, ~3 KB). The unit here, 226 "
                "bytes, holds 7 inlines, 3 reads, 4 blocks and 1 section: ~61 us, ~80 KB and 31 calls, so per byte "
                "~270 ns, ~360 B and 0.14 calls; the reads are two thirds of the bytes")
     :growth :linear
     :per-n {:ns 270 :bytes 360 :calls 0.14}
     :fixed {:ns 20000 :bytes 20000 :calls 5}
     :sizes [1000 10000 100000]}))

(deftest a-namespace-is-read-as-a-namespace
  ;; task seon.ref/files-namespaces-and-symbols-are-plain-markdown (owner, 2026-10-21): a
  ;; backticked dotted name that names a namespace is a reference to it (v1's rule); one
  ;; that names nothing stays prose
  (let [names (set (map :seon.ref/name (ref/references (program-db) "Read `demo.calc` and `demo.nowhere` and `demo.calc/add`.")))]
    (is (contains? names 'demo.calc) "a namespace main holds")
    (is (contains? names 'demo.calc/add) "a qualified name, as before")
    (is (not (contains? names 'demo.nowhere)) "a dotted word naming nothing is prose")))

(deftest a-citation-naming-no-page-names-nothing
  ;; wiki audit 2026-10-23 R1: names-something? threw (io/file of a nil path) on an anchor
  ;; link read with no page of its own; it names nothing, and an anchor names a section only
  ;; when its page holds one
  (let [db (program-db)
        [c] (ref/cited (:seon.ref/citations (ref/parse nil "[above](#the-api)")))]
    (is (= :anchor-missing (:seon.ref/problem-kind c)))
    (is (false? (ref/names-something? db c)) "an anchor with no page names nothing")
    (is (true? (ref/names-something? db {:seon.ref/path "docs/references.md" :seon.ref/depth :doc :seon.ref/line 1})))
    (is (false? (ref/names-something? db {:seon.ref/path "docs/references.md" :seon.ref/anchor "no-such-section"
                                          :seon.ref/depth :doc :seon.ref/line 1}))
        "a path#anchor names something only when the page holds that section")
    (is (true? (ref/names-something? db {:seon.ref/path "docs" :seon.ref/depth :doc :seon.ref/line 1})) "a directory of the checkout")
    (is (true? (ref/names-something? db {:seon.ref/name 'clojure.core/map :seon.ref/depth :doc :seon.ref/line 1}))
        "a var of a namespace this process holds, as a namespace counts when loaded")
    (is (true? (ref/names-something? db {:seon.ref/name :seon.host/db :seon.ref/depth :doc :seon.ref/line 1}))
        "a host predicate of the contract registry, as a declared schema key counts")
    (is (true? (ref/names-on-db? db 'clojure.core/map)) "a name alone: no file of the checkout is read")
    (is (false? (ref/names-on-db? db 'no.such-ns/nothing)) "and one that names nothing is false")))

(deftest a-task-name-is-a-reference-to-the-task
  ;; wiki audit 2026-10-23 R4: a cited ns/name naming a :seon.task/id resolves, and shows the
  ;; task's plan by the one rule
  (let [db (d/db-with (program-db) [{:seon.task/id "demo.ops/add-works" :seon.task/ns 'demo.ops :seon.task/name "add-works"
                                     :seon.task/plan "Add two numbers. Then subtract them."}])
        rs (ref/references db "Needs `demo.ops/add-works`, demo.ops/add-works and `demo.ops/no-such-task`.")]
    (is (= ['demo.ops/add-works] (mapv :seon.ref/name rs))
        "a task in a namespace no file defines is a reference, not an alias form; one naming nothing is code")
    (is (ref/names-something? db (first rs)))
    (is (= {:seon.ref/address "demo.ops/add-works" :seon.ref/heading "demo.ops/add-works"
            :seon.ref/lead "Add two numbers. Then subtract them." :seon.ref/below []}
           (ref/task-view db 'demo.ops/add-works :doc)))
    (is (= "Add two numbers." (:seon.ref/summary (ref/task-view db 'demo.ops/add-works :summary))))
    (is (nil? (ref/task-view db 'demo.ops/no-such-task :doc)))))

(deftest a-summary-names-the-citation-that-reads-the-rest
  (let [doc (ref/parse "docs/flow.md" page)
        more (fn [a] (ref/view doc a :summary))]
    (is (= "docs/flow.md#flow" (:seon.ref/read-at (more "docs/flow.md#flow")))
        "a lead with more than its first sentence: the summary names the address that reads it at :doc")
    (is (= (count "Each proc is a Var. See `seon.step/step`.") (:seon.ref/left (more "docs/flow.md#flow")))
        "and how many characters of the lead it left (the lead's text after the summary sentence, trimmed)")
    (is (nil? (:seon.ref/read-at (more "docs/flow.md#the-api-1"))) "a lead that is one sentence left nothing: no key")
    (is (nil? (:seon.ref/left (more "docs/flow.md#the-api-1"))))))
