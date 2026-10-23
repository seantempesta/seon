(ns seon.docs-test
  "The wiki as data in the store (task seon.docs/docs-are-data-in-the-store): a section entity
  per heading, written only where a section changed; its references are what its own text
  cites, so its backlinks are who cites it."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.docs :as docs] [seon.source] [seon.fx] [seon.ref] [seon.problems :as problems]
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

;; ---- the baseline: what a section was written against, from git ---------------------------

(defn- git-fixture
  "A git repository in a new directory: src/app/gb.clj with a function f and docs/p.md citing it,
  committed together; then a second commit that changes f. Answers {:dir :first :second}."
  []
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "user.dir") "/target/tmp/git-fixture-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (let [r (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t" args)]
                           (assert (zero? (:exit r)) (str args (:err r))) (str/trim (:out r))))
        put (fn [p t] (.mkdirs (.getParentFile (java.io.File. (str dir "/" p)))) (spit (str dir "/" p) t))]
    (git "init" "-q")
    (put "src/app/gb.clj" "(ns app.gb)\n\n(defn f [] 1)\n\n(defn g [] 2)\n")
    (put "docs/p.md" "# P\n\nIt reads `app.gb/f`.\n\n## Q\n\nIt reads `app.gb/g` and `app.gb/h`.\n")
    (git "add" ".") (git "commit" "-q" "-m" "first")
    (let [first (git "rev-parse" "HEAD")]
      (put "src/app/gb.clj" "(ns app.gb)\n\n(defn f [] 11)\n\n(defn g [] 2)\n\n(defn h [] 3)\n")
      (git "add" ".") (git "commit" "-q" "-m" "second")
      {:dir dir :first first :second (git "rev-parse" "HEAD")})))

(defn- db-of-fixture
  "A db holding the fixture's current source (src, as the second commit has it) and its wiki
  loaded with the baseline git gives, and the baseline."
  [dir src-path]
  (let [src (slurp (str dir "/" src-path))
        db0 (empty-db)
        db1 (d/db-with db0 (seon.source/import-tx db0 {src-path src}))
        pages (seon.docs/pages dir "docs")
        plan (seon.docs/baseline-plan db1 pages)
        answer (seon.fx/wiki-history {:req {:root dir :sections plan}})
        baseline (seon.docs/baseline-reads db1 plan (:value answer))]
    {:db1 db1 :plan plan :answer answer :baseline baseline :pages pages
     :db (d/db-with db1 (seon.docs/load-tx db1 "docs" pages baseline))}))

(deftest a-sections-baseline-is-the-code-as-of-its-last-commit
  (let [{:keys [dir first second]} (git-fixture)
        {:keys [db plan answer baseline]} (db-of-fixture dir "src/app/gb.clj")
        read #(:seon.section/read (d/pull db [:seon.section/read] [:seon.section/address %]))
        form-id #(:form/id (:var/form (d/entity db (pdb/eid db :var/name %))))
        stale (get-in (problems/health db) [:classes :stale-section :rows])]
    (is (= 2 (count plan)) "both sections cite code")
    (is (= {:commit first} (get-in answer [:value :sections "docs/p.md#p"])) "the section was last changed in the first commit")
    (testing "f was changed by the second commit, after the section: the recorded id is not today's"
      (is (not= (form-id 'app.gb/f) (get (read "docs/p.md#p") 'app.gb/f)))
      (is (some? (get (read "docs/p.md#p") 'app.gb/f))))
    (testing "g is unchanged since: recorded as it is now"
      (is (= (form-id 'app.gb/g) (get (read "docs/p.md#q") 'app.gb/g))))
    (testing "h did not exist at the commit: recorded absent, stale now that it does (a citation ahead of the code)"
      (is (= "absent" (get (read "docs/p.md#q") 'app.gb/h))))
    (testing "so the sections stale today are exactly those two, one name each"
      (is (= #{["docs/p.md#p" 'app.gb/f] ["docs/p.md#q" 'app.gb/h]} (set (map (juxt :section :name) stale)))))
    (testing "an unchanged reload keeps the baseline: nothing is rewritten, nothing forgiven"
      (is (= [] (seon.docs/load-tx db "docs" (seon.docs/pages dir "docs")))))))

(deftest a-baseline-git-cannot-give-is-today-and-says-so
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "user.dir") "/target/tmp/not-git-" (random-uuid))) (.mkdirs)))
        _ (doseq [[p t] {"src/app/gn.clj" "(ns app.gn)\n\n(defn f [] 1)\n" "docs/p.md" "# P\n\nIt reads `app.gn/f`.\n"}]
            (.mkdirs (.getParentFile (java.io.File. (str dir "/" p)))) (spit (str dir "/" p) t))
        {:keys [db baseline]} (db-of-fixture dir "src/app/gn.clj")
        rows (get-in (problems/health db) [:classes :stale-section :rows])]
    (is (some? (:unread (get baseline "docs/p.md#p"))) "not a git checkout: the section is unread")
    (is (= #{nil} (set (map :name rows))) "its record is today's, so no var is stale")
    (is (re-find #"recorded as of today: its baseline could not be taken from git" (:message (first rows))) "and it says so, as attention")))
