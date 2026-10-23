(ns seon.docs-test
  "The wiki as data in the store (task seon.docs/docs-are-data-in-the-store): a section entity
  per heading, written only where a section changed; its references are what its own text
  cites, so its backlinks are who cites it."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.docs :as docs] [seon.ref] [seon.problems :as problems]
            [seon.config :as config]))

(defn- empty-db [] (ts/test-db))

(def ^:private flow
  "# Flow\n\nFlow runs procs.\n\n## Idioms\n\nPark on a channel, as ```app.m0/f0``` does.\n\n## Quiet\n\nCount messages, with `app.m0/f1`.\n")

(def ^:private async
  "# Async\n\nAsync is channels.\n\n## Waiting\n\nWait by parking, as [idioms](flow.md#idioms) says.\n")

(def ^:private pages {"demo/flow.md" flow "demo/async.md" async})

(defn- load-pages
  "db after loading pages (path -> text) as the demo wiki."
  [db pages]
  (let [tx (docs/load-tx db "demo" pages)]
    (is (vector? tx))
    (d/db-with db tx)))

(deftest an-unchanged-page-writes-nothing
  (let [db (load-pages (empty-db) pages)]
    (is (= "Count messages, with `app.m0/f1`." (:seon.section/text (docs/section db "demo/flow.md#quiet")))
        "the first load wrote each section")
    (is (= "demo/flow.md#flow" (:seon.section/parent (docs/section db "demo/flow.md#idioms"))))
    (is (= [] (docs/load-tx db "demo" pages)) "loading the same pages again writes nothing")))

(deftest a-changed-section-rewrites-only-itself
  (let [db (load-pages (empty-db) pages)
        quiet (:db/id (d/pull db [:db/id] [:seon.section/address "demo/flow.md#quiet"]))
        edited (assoc pages "demo/flow.md" (str/replace flow "Count messages" "Count every message"))
        tx (docs/load-tx db "demo" edited)]
    (is (seq tx) "the changed section is written")
    (is (= #{quiet} (into #{} (map #(if (map? %) (:db/id %) (second %))) tx))
        "the load states only the changed section")
    (is (= "Count every message, with `app.m0/f1`."
           (:seon.section/text (docs/section (d/db-with db tx) "demo/flow.md#quiet"))))))

(deftest a-link-to-no-section-is-a-dead-link-and-the-rest-loads
  (let [broken (assoc pages "demo/async.md" (str/replace async "flow.md#idioms" "flow.md#nowhere"))
        db (load-pages (empty-db) broken)
        dead #(get-in (problems/health %) [:classes :dead-citation :rows])]
    (is (= #{"demo/flow.md#flow" "demo/flow.md#idioms" "demo/flow.md#quiet" "demo/async.md#async" "demo/async.md#waiting"}
           (set (map :v (d/datoms db :avet :seon.section/address)))) "every section loads")
    (is (= #{"demo/flow.md#nowhere"} (set (:seon.section/dead-links (d/pull db [:seon.section/dead-links] [:seon.section/address "demo/async.md#waiting"]))))
        "the link is kept on the section that makes it")
    (is (= [["demo/async.md#waiting" "demo/flow.md#nowhere" "demo/async.md#waiting links to demo/flow.md#nowhere, which is no section"]]
           (mapv (juxt :section :target :message) (dead db))) "and is exactly one dead citation, naming page, section and target")
    (is (empty? (docs/load-tx db "demo" broken)) "an unchanged wiki writes nothing")
    (let [fixed (d/db-with db (docs/load-tx db "demo" (assoc broken "demo/flow.md" (str flow "\n## Nowhere\n\nNow it exists.\n"))))]
      (is (empty? (dead fixed)) "the target section is added: the dead citation closes, and nothing was written to close it"))))

(defn- backlinks
  "The addresses of the sections whose text cites target (a section's address or a var's name), sorted."
  [db target]
  (let [e (if (string? target)
            (d/q '[:find ?e . :in $ ?a :where [?e :seon.section/address ?a]] db target)
            (d/q '[:find ?e . :in $ ?n :where [?e :var/name ?n]] db target))]
    (vec (sort (when e (d/q '[:find [?a ...] :in $ ?t :where [?s :seon.section/references ?t] [?s :seon.section/address ?a]] db e))))))

(deftest backlinks-are-a-sections-instances
  (let [quiet "# Quiet\n\nQuiet is the absence of messages.\n\n## Counting\n\nCount as the [idiom](flow.md#idioms) does, by `app.m0/f0`.\n"
        db (load-pages (empty-db) (assoc pages "demo/quiet.md" quiet))]
    (is (= ["demo/async.md#waiting" "demo/quiet.md#counting"] (backlinks db "demo/flow.md#idioms"))
        "a section's backlinks are the sections that link to it")
    (is (= ["demo/flow.md#idioms" "demo/quiet.md#counting"] (backlinks db 'app.m0/f0))
        "a var's backlinks are the sections that cite it")
    (testing "a link to a page with no anchor reaches the page's first section"
      (let [db (load-pages db (assoc pages "demo/quiet.md" (str/replace quiet "flow.md#idioms" "flow.md")))]
        (is (= ["demo/quiet.md#counting"] (backlinks db "demo/flow.md#flow")))
        (is (= ["demo/async.md#waiting"] (backlinks db "demo/flow.md#idioms")) "the dropped link is retracted")))))

(deftest one-address-shows-one-text-through-ref-and-docs
  ;; research row 15: a section's text at code depth is its own text, the lines after its
  ;; heading up to the next heading of any level, whichever reader shows it: the expected
  ;; texts are written here from the page above, not read through either
  (let [db (load-pages (empty-db) pages)
        doc (seon.ref/parse "demo/flow.md" flow)
        own {"demo/flow.md#flow" "Flow runs procs."
             "demo/flow.md#idioms" "Park on a channel, as ```app.m0/f0``` does."
             "demo/flow.md#quiet" "Count messages, with `app.m0/f1`."}]
    (doseq [[a text] own]
      (is (= text (:seon.ref/text (seon.ref/view doc a :code))) (str "ref: " a))
      (is (= text (:seon.ref/text (docs/view db a :code))) (str "docs: " a)))
    (is (= ["demo/flow.md#idioms" "demo/flow.md#quiet"] (mapv :seon.ref/address (:seon.ref/below (docs/view db "demo/flow.md#flow" :code))))
        "the sections below by address, not inline")))

;; ---- names are Clojure's words (task seon.docs/names-are-clojures-words) ----------------

(def ^:private kept
  "Names that keep a word of AGENTS.md section 7a's table in another sense, each with its reason."
  {'seon.fx/admit "admission by workload (a token bucket and free workers: AGENTS.md section 4), not a contract"
   'seon.fx-admit-test "the same admission (the namespace and its vars)"
   'seon.fx-test/owner-admits-by-workers-and-priority "the same admission"
   'seon.reader-test/prose-without-a-fence-is-a-comment "a Markdown code fence"
   "test/seon/fx_admit_test.clj" "the same admission"
   'seon.reader/fence "a Markdown code fence, CommonMark's word"
   'seon.fixtures/fenced "a reply in a Markdown code fence"})

(defn- old-words
  "The old house words of AGENTS.md section 7a's table, hyphenated (\"merge train\" ->
  \"merge-train\"), leaving out a row still stored under its old name (\"stored as\": none now)."
  [agents]
  (let [table (->> (str/split-lines agents)
                   (drop-while #(not (str/starts-with? % "| Old house word")))
                   (drop 2)
                   (take-while #(str/starts-with? % "|")))]
    (set (for [row table :when (not (str/includes? row "stored as"))
               w (str/split (second (str/split row #"\|")) #",")]
           (str/replace (str/trim w) " " "-")))))

(defn- uses?
  "Does name n hold old word w as whole words (a plural too)?"
  [w n]
  (some? (re-find (re-pattern (str "(^|[^a-z])" w "s?([^a-z]|$)")) (str/lower-case n))))

(deftest no-name-uses-an-old-word
  (let [root (config/checkout)
        words (old-words (slurp (str root "/AGENTS.md")))
        files (fn [dir] (for [f (file-seq (java.io.File. (str root "/" dir))) :when (.isFile ^java.io.File f)]
                          (subs (.getPath ^java.io.File f) (inc (count root)))))
        _ (doseq [f (files "src") :when (str/ends-with? f ".clj")]
            (require (symbol (-> f (subs 4 (- (count f) 4)) (str/replace "/" ".") (str/replace "_" "-")))))
        names (concat (files "bin") (files "docs") (files "src") (files "test")
                      (for [n (all-ns) :when (re-find #"^(seon|my)\." (str (ns-name n)))
                            x (cons (ns-name n) (map #(symbol (str (ns-name n)) (str %)) (keys (ns-interns n))))]
                        x))]
    (is (contains? words "lane") "the table is read")
    (is (contains? words "refusal") "no row is still stored under its old name: the table is read whole")
    (is (empty? (for [n names w words :when (and (uses? w (str n)) (not (kept n)) (not (and (qualified-symbol? n) (kept (symbol (namespace n))))))] [w n]))
        "every name uses Clojure's, a library's or Jolt's word")))

;; ---- text uses Clojure's words too (task seon.docs/names-are-clojures-words) ---------------

(def ^:private text-kept
  "Files where an old word of section 7a's table is the subject or another sense, each with its reason.
  A line quoting the owner is kept too (the quote is what he said)."
  {"AGENTS.md" "the table names the old words"
   "docs/vocabulary.md" "its rows say `(was X)`"
   "docs/decisions.md" "the owner's rulings quote his words, and their titles stay as stated"
   "resources/seon/tasks/seon.docs.edn" "the rename's plan names the old words"
   "resources/seon/tasks/seon.db.edn" "the stored-names plan names the old words"
   "test/seon/docs_test.clj" "this test names the old words"
   "src/seon/reader.clj" "a Markdown code fence, CommonMark's word"
   "src/seon/ref.clj" "a Markdown code fence, CommonMark's word"
   "resources/seon/schemas/seon.ref.edn" "the :fence block kind is CommonMark's"
   "test/seon/reader_test.clj" "a Markdown code fence"
   "test/seon/ref_test.clj" "a Markdown code fence"
   "test/seon/agent_test.clj" "the named reader test is about a Markdown code fence"
   "test/seon/program_test.clj" "the named reader test is about a Markdown code fence"
   "test/seon/fixtures.clj" "a reply in a Markdown code fence"
   "src/seon/server.clj" "cites v1's namespace proto.agents.entrance"
   "test/seon/config_test.clj" "tests bin/ports' reading of the earlier entrance-port key and its comment"
   "BAGGAGE.md" "cites a decision by its title, \"No locks, no lane cap, no waiting\""
   "bin/ports" "reads the earlier :seon.config.root/entrance-port from a checkout's local.edn until it is rewritten"
   "resources/seon/tasks/seon.context.edn" "t5.refusal/* is the name of a past agent's test namespace"})

(defn- old-word-patterns
  "Whole-word patterns for the table's old words, as the text spells them (a plural, \"merge train\"); \"admit\"
  and \"land\" are also plain English and Seon's admission, so only names are read for them."
  [agents]
  (for [w (old-words agents) :when (not (#{"admit" "land"} w))]
    [w (re-pattern (str "(?i)(?<![\\w-])" (if (= w "merge-train") "merge[ -]train" w) "s?(?![\\w-])"))]))

(deftest no-text-uses-an-old-word
  (let [root (config/checkout)
        patterns (old-word-patterns (slurp (str root "/AGENTS.md")))
        kept-line #"(?i)owner,? [^\"]{0,40}?\"|Attempting to call unbound fn|zoom-a8-attempt"
        text? (fn [^String p] (or (str/starts-with? p "bin/") (re-find #"\.(clj|edn|md|html|js|css|c|h)$" p)))
        paths (for [dir ["bin" "docs" "resources" "src" "test" "web" "config" "native"]
                    f (file-seq (java.io.File. (str root "/" dir))) :when (.isFile ^java.io.File f)
                    :let [p (subs (.getPath ^java.io.File f) (inc (count root)))] :when (text? p)]
                p)
        hits (for [p (concat ["AGENTS.md" "BAGGAGE.md" "README.md"] paths) :when (not (text-kept p))
                   :let [lines (str/split-lines (slurp (str root "/" p)))]
                   [i line] (map-indexed vector lines) :when (not (re-find kept-line line))
                   [w re] patterns :when (re-find re line)]
               [p (inc i) w])]
    (is (contains? (set (map first patterns)) "attempt") "the table is read, the run row included")
    (is (empty? hits) "no text uses an old word outside text-kept: each is [file line word]")))
