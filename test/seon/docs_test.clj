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
    (is (= "Count messages, with `app.m0/f1`." (:seon.ref/text (docs/view db "demo/flow.md#quiet" :code)))
        "the first load wrote each section")
    (is (some #{"demo/flow.md#idioms"} (map :seon.ref/address (:seon.ref/below (docs/view db "demo/flow.md#flow" :doc)))) "a section is below its parent")
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
           (:seon.ref/text (docs/view (d/db-with db tx) "demo/flow.md#quiet" :code))))))

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

(deftest a-view-at-summary-lists-references-and-cited-by
  ;; the edges are the stored ones (backlinks-are-a-sections-instances), each at summary depth:
  ;; a section by its address and lead's first sentence, a var by its name
  (let [quiet "# Quiet\n\nQuiet is the absence of messages.\n\n## Counting\n\nCount as the [idiom](flow.md#idioms) does, by `app.m0/f0`.\n"
        db (load-pages (empty-db) (assoc pages "demo/quiet.md" quiet))
        idioms (docs/view db "demo/flow.md#idioms" :summary)
        counting (docs/view db "demo/quiet.md#counting" :summary)]
    (is (= ["demo/async.md#waiting" "demo/quiet.md#counting"] (mapv :seon.ref/address (:seon.ref/cited-by idioms)))
        "who cites it, from the stored edges")
    (is (= "Count as the [idiom](flow.md#idioms) does, by `app.m0/f0`."
           (:seon.ref/summary (second (:seon.ref/cited-by idioms)))) "each at summary depth")
    (is (= ["app.m0/f0" "demo/flow.md#idioms"] (mapv :seon.ref/address (:seon.ref/references counting)))
        "what it cites, a section and a var")
    (is (= "Park on a channel, as ```app.m0/f0``` does."
           (:seon.ref/summary (second (:seon.ref/references counting)))))
    (is (not (contains? (docs/view db "demo/flow.md#quiet" :summary) :seon.ref/cited-by)) "an edge-less section lists none")
    (is (= (:seon.ref/references counting) (:seon.ref/references (docs/view db "demo/quiet.md#counting" :code))) "at every depth")))

(deftest a-page-reads-at-a-depth
  (let [db (load-pages (empty-db) pages)
        summary (docs/page db "demo/flow.md" :summary)
        code (docs/page db "demo/flow.md" :code)]
    (is (= ["demo/flow.md#flow" "demo/flow.md#idioms" "demo/flow.md#quiet"] (mapv :seon.ref/address summary)))
    (is (= [1 2 2] (mapv :seon.ref/level summary)))
    (is (= (mapv #(docs/view db (:seon.ref/address %) :summary) summary) (mapv #(dissoc % :seon.ref/level) summary))
        "each section is its view at that depth")
    (is (= "Park on a channel, as ```app.m0/f0``` does." (:seon.ref/text (second code))))
    (is (not-any? :seon.ref/text summary))
    (is (nil? (docs/page db "demo/none.md" :summary)))))

(deftest every-section-of-the-wiki-has-a-lead
  ;; docs/README.md: every section leads with one sentence that stands alone; the list is read from the
  ;; real wiki as main holds it (notes/archive/dogfood/docs-audit.md found 54 of 364 without)
  (let [db0 (empty-db)
        db (d/db-with db0 (docs/load-dir-tx db0 (config/checkout) "docs"))]
    (is (< 300 (count (d/datoms db :aevt :seon.section/address))) "the real wiki is read")
    (is (= [] (mapv :section (get-in (problems/health db) [:classes :leadless-section :rows]))) "no section lacks a lead")))

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
  "A git repository of its own in a new directory under target/tmp (not the one test store, which holds no
  git, and not the checkout's repository, whose history answers for the real wiki): bin/test and bin/check
  run roots with SEON_WIKI_BASELINE=false, so this is the history path's proof against real git. It holds
  src/app/gb.clj with functions f and g and docs/p.md citing them and app.gb/h, app.gl/z, committed
  together; then a second commit that changes f (its body and its docstring), adds h, and adds
  src/app/gl.clj defining z.
  Answers {:dir :first :second}."
  []
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "user.dir") "/target/tmp/git-fixture-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (let [r (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t" args)]
                           (assert (zero? (:exit r)) (str args (:err r))) (str/trim (:out r))))
        put (fn [p t] (.mkdirs (.getParentFile (java.io.File. (str dir "/" p)))) (spit (str dir "/" p) t))]
    (git "init" "-q")
    (put "src/app/gb.clj" "(ns app.gb)\n\n(defn f [] 1)\n\n(clojure.core/defn g [] 2)\n")
    (put "docs/p.md" "# P\n\nIt reads `app.gb/f`.\n\n## Q\n\nIt reads `app.gb/g`, `app.gb/h` and `app.gl/z`.\n")
    (git "add" ".") (git "commit" "-q" "-m" "first")
    (let [first (git "rev-parse" "HEAD")]
      (put "src/app/gb.clj" "(ns app.gb)\n\n(defn f \"Eleven.\" [] 11)\n\n(clojure.core/defn g [] 2)\n\n(defn h [] 3)\n")
      (put "src/app/gl.clj" "(ns app.gl)\n\n(defn z [] 4)\n")
      (git "add" ".") (git "commit" "-q" "-m" "second")
      {:dir dir :first first :second (git "rev-parse" "HEAD")})))

(defn- db-of-fixture
  "A db holding the fixture's current source (src, as the second commit has it) and its wiki
  loaded with the baseline git gives, and the baseline."
  [dir src-path]
  (let [srcs (into {} (for [p (cons src-path (when (.exists (java.io.File. (str dir "/src/app/gl.clj"))) ["src/app/gl.clj"]))] [p (slurp (str dir "/" p))]))
        db0 (empty-db)
        db1 (d/db-with db0 (seon.source/import-tx db0 srcs))
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
    (is (empty? (get-in answer [:value :errors])) "git answered every request")
    (is (= {:commit first} (get-in answer [:value :sections "docs/p.md#p"])) "the section was last changed in the first commit")
    (testing "f's docstring was changed by the second commit, after the section: recorded changed"
      (is (= :changed (get (read "docs/p.md#p") 'app.gb/f))))
    (testing "g is unchanged since: recorded as it is now"
      (is (= (form-id 'app.gb/g) (get (read "docs/p.md#q") 'app.gb/g))))
    (testing "h did not exist at the commit: recorded absent, stale now that it does (a citation ahead of the code)"
      (is (= :absent (get (read "docs/p.md#q") 'app.gb/h))))
    (testing "z lives in a file that did not exist at the commit (git ls-tree says so): absent, not an error"
      (is (= :absent (get (read "docs/p.md#q") 'app.gl/z))))
    (testing "so the sections stale today are exactly those, one name each"
      (is (= #{["docs/p.md#p" 'app.gb/f] ["docs/p.md#q" 'app.gb/h] ["docs/p.md#q" 'app.gl/z]} (set (map (juxt :section :name) stale)))))
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

(deftest a-baseline-never-guesses
  ;; baseline-reads over a history answer made by hand: the three ways a var is not simply found
  (let [db (empty-db)
        plan [{:path "docs/p.md" :address "docs/p.md#a" :lines [1 3] :files {'app.m0/f0 {:file "src/app/m0.clj" :ns 'app.m0}}}
              {:path "docs/p.md" :address "docs/p.md#b" :lines [4 6] :files {'app.m0/f1 {:file "src/app/m0.clj" :ns 'app.m0}}}
              {:path "docs/p.md" :address "docs/p.md#c" :lines [7 9] :files {'app.m0/f2 {:file "src/app/m0.clj" :ns 'app.m0}}}
              {:path "docs/p.md" :address "docs/p.md#d" :lines [10 12] :files {'app.m0/f3 {:file "src/app/m0.clj" :ns 'app.m0}}}]
        old "(ns app.m0)\n\n(defn f0 [] 1)\n\n(my.macros/defthing f1 [] 2)\n"
        answer {:sections {"docs/p.md#a" {:commit (apply str (repeat 40 "a"))} "docs/p.md#b" {:commit (apply str (repeat 40 "a"))}
                           "docs/p.md#c" {:commit nil} "docs/p.md#d" {:commit (apply str (repeat 40 "b"))}}
                :files {[(apply str (repeat 40 "a")) "src/app/m0.clj"] old}
                :errors {[(apply str (repeat 40 "b")) "src/app/m0.clj"] "git show: exploded"}}
        r (seon.docs/baseline-reads db plan answer)]
    (testing "a var defined by a form of a head defining-ids knows is found at that commit, never absent"
      ;; this db holds no app.m0/f0 today, so what the section described of it (its meaning at the
      ;; commit) is not today's: :changed (the string sentinel "changed" passed a string? check here)
      (is (= :changed (get-in r ["docs/p.md#a" :read 'app.m0/f0])))
      (is (nil? (:unread (r "docs/p.md#a")))))
    (testing "a var the old file mentions but no known head defines is unread, never absent"
      (is (re-find #"no form this reading knows" (:unread (r "docs/p.md#b")))))
    (testing "a section with an uncommitted line is as of today, nothing unread"
      (is (= {:read (seon.docs/read-map db #{'app.m0/f2})} (r "docs/p.md#c"))))
    (testing "a file git could not show is unread with git's words, never absent"
      (is (re-find #"git show: exploded" (:unread (r "docs/p.md#d")))))))

(deftest a-review-is-a-commit-and-survives-a-rebuilt-store
  ;; a review (my.docs/reviewed!) is an empty git commit with `Reviewed: <address>`; the baseline takes, per
  ;; section, the newer of the commit that last changed its lines and the last review naming it, from one
  ;; git log, so a store rebuilt from scratch reads the reviewed section fresh
  (let [{:keys [dir]} (git-fixture)
        stale-of (fn [] (let [{:keys [db]} (db-of-fixture dir "src/app/gb.clj")]
                          (set (map (juxt :section :name) (get-in (problems/health db) [:classes :stale-section :rows])))))
        branch (str/trim (:out (clojure.java.shell/sh "git" "-C" dir "rev-parse" "--abbrev-ref" "HEAD")))]
    (is (contains? (stale-of) ["docs/p.md#p" 'app.gb/f]) "before the review the section is stale")
    (let [r (seon.fx/git-commit {:req {:dir dir :branch branch :files {} :allow-empty? true :message (seon.docs/review-message "docs/p.md#p" "sean")}})]
      (is (string? (get-in r [:value :sha])) "the review is a commit")
      (is (= 1 (count (re-seq #"Reviewed: docs/p.md#p" (:out (clojure.java.shell/sh "git" "-C" dir "log" "HEAD" "--format=%B")))))))
    (let [after (stale-of)]
      (is (not (contains? after ["docs/p.md#p" 'app.gb/f])) "a store rebuilt from the repository reads the reviewed section fresh")
      (is (contains? after ["docs/p.md#q" 'app.gb/h]) "and the section nobody reviewed is still stale"))))

(deftest a-review-on-another-branch-does-not-freshen-the-checkouts-section
  (let [{:keys [dir]} (git-fixture)
        stale-of (fn [] (let [{:keys [db]} (db-of-fixture dir "src/app/gb.clj")]
                          (set (map (juxt :section :name) (get-in (problems/health db) [:classes :stale-section :rows])))))
        r (seon.fx/git-commit {:req {:dir dir :branch "elsewhere" :files {} :allow-empty? true :message (seon.docs/review-message "docs/p.md#p" "sean")}})]
    (is (string? (get-in r [:value :sha])) "the review commit exists, on a branch HEAD does not contain")
    (is (contains? (stale-of) ["docs/p.md#p" 'app.gb/f]) "and HEAD's section is still stale")))

;; ---- text uses Clojure's words too (task seon.docs/names-are-clojures-words) ---------------

(def ^:private text-kept
  "Files where an old word of section 7a's table is the subject or another sense, each with its reason.
  The quoted span of an owner's sentence is kept too (the quote is what he said), and the Jolt message
  and image name that hold the old word."
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
   "BAGGAGE.md" "cites a decision by its title, \"No locks, no lane cap, no waiting\""
   "resources/seon/tasks/seon.context.edn" "t5.refusal/* is the name of a past agent's test namespace"})

(defn- old-word-spellings
  "The table's old words as the text spells them, [word [spelling ...]] (\"merge train\" either way); \"admit\"
  and \"land\" are also plain English and Seon's admission, so only names are read for them."
  [agents]
  (for [w (old-words agents) :when (not (#{"admit" "land"} w))]
    [w (if (= w "merge-train") ["merge train" "merge-train"] [w])]))

(defn- word-char? [c] (let [c (int c)] (or (<= 97 c 122) (<= 48 c 57) (= c 95))))

(defn- whole-word?
  "Does lower-case text low hold spelling s as a whole word, a plural too? What the pattern
  (?<!\\w)s s?(?!\\w) matches, found by indexOf (a regex costs ~140 us a line here, so the tree's 59,000
  lines against 17 patterns took 141 s; this reads the whole tree in ~150 ms)."
  [^String low ^String s]
  (let [n (.length low) k (.length s)]
    (loop [i (.indexOf low s 0)]
      (when (<= 0 i)
        (let [e (+ i k)]
          (if (and (or (zero? i) (not (word-char? (.charAt low (dec i)))))
                   (or (>= e n) (not (word-char? (.charAt low e)))
                       (and (= \s (.charAt low e)) (or (>= (inc e) n) (not (word-char? (.charAt low (inc e))))))))
            true
            (recur (.indexOf low s (inc i)))))))))

(deftest no-text-uses-an-old-word
  (let [root (config/checkout)
        words (old-word-spellings (slurp (str root "/AGENTS.md")))
        kept-span #"(?i)owner,? [^\"]{0,40}?\"[^\"]*\"|Attempting to call unbound fn|zoom-a8-attempt|\(was attempt\)"
        text? (fn [^String p] (or (str/starts-with? p "bin/") (re-find #"\.(clj|edn|md|html|js|css|c|h)$" p)))
        used (fn [^String line] (let [low (str/lower-case line)] (filter (fn [[_ ss]] (some #(whole-word? low %) ss)) words)))
        paths (for [dir ["bin" "docs" "resources" "src" "test" "web" "config" "native"]
                    f (file-seq (java.io.File. (str root "/" dir))) :when (.isFile ^java.io.File f)
                    :let [p (subs (.getPath ^java.io.File f) (inc (count root)))] :when (text? p)]
                p)
        hits (for [p (concat ["AGENTS.md" "BAGGAGE.md" "README.md"] paths) :when (not (text-kept p))
                   [i line0] (map-indexed vector (str/split-lines (slurp (str root "/" p))))
                   ;; the kept spans only remove text: a line that uses no word before they go uses none after
                   :when (seq (used line0))
                   [w _] (used (str/replace line0 kept-span ""))]
               [p (inc i) w])]
    (is (contains? (set (map first words)) "attempt") "the table is read, the run row included")
    (is (empty? hits) "no text uses an old word outside text-kept: each is [file line word]")))

(defn grep-db
  "A test db holding forms [name doc src]: each a var app/<name> defined by its form."
  [forms]
  (let [db0 (ts/test-db)]
    (d/db-with db0 (vec (mapcat (fn [[n doc src]]
                                  [(cond-> {:db/id (str "v" n) :var/name (symbol "app" n)} doc (assoc :var/doc doc))
                                   {:db/id (str "f" n) :form/id (str "app/" n " " src) :form/src src :form/defines [(str "v" n)]}])
                                forms)))))

(defn- grep-on [db s] (seon.docs/grep db s))

(deftest grep-ranks-names-before-bodies
  ;; dogfood 2026-10-25: grep answered ~30 names, unranked and without their line. A var whose name
  ;; holds the word comes first, then one whose docstring does, then the rest; each with its line
  (let [db (grep-db [["g" nil "(defn g []\n  (accumulate 1))"]
                     ["f" "Sums, as accumulate does." "(defn f\n  \"Sums, as accumulate does.\"\n  [] 2)"]
                     ["accumulate" nil "(defn accumulate [x]\n  x)"]
                     ["h" nil "(defn h [] 3)"]])]
    (is (= ['app/accumulate 'app/f 'app/g] (mapv :var (grep-on db "accumulate"))) "name, then docstring, then body; h left out")
    (is (= "(accumulate 1))" (:line (last (grep-on db "accumulate")))) "the first line holding it")
    (is (= [] (grep-on db "nowhere")))
    (is (= "(defn g []" (:line (first (grep-on db "[]\n  (accumulate")))) "a match across a line break: the line it begins in")
    (is (= [{:var 'app/f :line "\"Sums, as accumulate does.\""}] (grep-on db "Sums")) "a docstring's match: its line")
    (let [two (d/db-with db [{:db/id "a" :var/name 'app/a} {:db/id "b" :var/name 'app/b}
                             {:form/id "app/ab" :form/src "(do (defn a [] :zed) (defn b [] :zed))" :form/defines ["a" "b"]}])]
      (is (= ['app/a 'app/b] (mapv :var (grep-on two ":zed"))) "a form defining two vars: each, once"))))

(defn kinds-db
  "grep-db's forms beside a wiki section, a task and a schema entry that hold the word `fusing`."
  []
  (d/db-with (grep-db [["g" nil "(defn g []\n  (fusing 1))"] ["h" nil "(defn h [] 3)"]])
             [{:seon.section/address "p.md#fusing-ranks" :seon.section/heading "Fusing ranks" :seon.section/text "Ranks are fused.\nThe fusing is by reciprocal rank."}
              {:seon.section/address "p.md#other" :seon.section/heading "Other" :seon.section/text "Fusing, in the middle."}
              {:seon.task/id "app/fusing-task" :seon.task/plan "Plan.\nMake the FUSING cheaper."}
              {:schema/key :app/fuse :schema/form "[:map {:doc \"fusing\"}]"}]))

(deftest exact-text-reads-every-kind-and-ignores-case-when-asked
  (let [db (kinds-db) hit (fn [h] [(:kind h) (:name h)])]
    (is (= [[:task "app/fusing-task"] [:section "p.md#fusing-ranks"] [:schema :app/fuse] [:var 'app/g] [:section "p.md#other"]]
           (map hit (seon.docs/exact db "fusing" #{:var :test :section :task :schema} true)))
        "name or heading first, then the rest by name; every kind; case ignored")
    (is (= [[:var 'app/g]] (map hit (seon.docs/exact db "fusing" #{:var} true))) "the kinds asked only")
    (is (= [] (seon.docs/exact db "FUSING" #{:var} false)) "case matters when asked to")
    (is (= "Make the FUSING cheaper." (:line (first (filter #(= :task (:kind %)) (seon.docs/exact db "fusing" #{:task} true))))))
    (is (every? (set (keys seon.docs/text-attrs)) #{:var :test :section :task :schema}))))

(defn corpus-db
  "kinds-db among filler: 40 forms and 20 sections that hold none of the word, so a few matching texts stand
  out of the corpus' scores (tokens keeps only those z standard deviations above the mean)."
  []
  (d/db-with (kinds-db)
             (vec (concat (mapcat (fn [i] [{:db/id (str "fv" i) :var/name (symbol "fill" (str "v" i))}
                                           {:form/id (str "fill/" i) :form/src (str "(defn v" i " [] (inc " i "))") :form/defines [(str "fv" i)]}])
                                  (range 40))
                          (for [i (range 20)] {:seon.section/address (str "fill.md#s" i) :seon.section/heading (str "Filler " i)
                                               :seon.section/text (str "Plain prose number " i ".")})))))

(deftest tokens-keep-the-texts-that-stand-out-by-the-words-of-a-query
  (let [db (corpus-db) hit (fn [h] [(:kind h) (:name h)])
        all #{:var :test :section :task :schema}]
    (is (= #{[:task "app/fusing-task"] [:section "p.md#fusing-ranks"] [:section "p.md#other"] [:var 'app/g] [:schema :app/fuse]}
           (set (map hit (seon.docs/tokens db "fusing" all 2.0)))) "every kind holding the word, and none of the filler")
    (is (= [:section "p.md#fusing-ranks"] (hit (first (seon.docs/tokens db "fusing ranks" all 2.0)))) "more of the words: first")
    (is (every? #(< 2.0 (:z %)) (seon.docs/tokens db "fusing" all 2.0)))
    (is (= [[:var 'app/g]] (map hit (seon.docs/tokens db "fusing" #{:var} 2.0))) "the kinds asked only")
    (is (= [] (seon.docs/tokens db "nowhere" all 2.0)) "no word of it in any text")
    (is (= [] (seon.docs/tokens db "" all 2.0)))
    (is (= ["datoms" "diff" "seon" "run" "outcome" "a" "b"] (seon.docs/words "datoms-diff seon.run/outcome aB")) "an identifier is its parts, camelCase too")
    (testing "the first line holding a word, and its number"
      (is (= {:line "(fusing 1))" :at 2} (select-keys (first (filter #(= 'app/g (:name %)) (seon.docs/tokens db "fusing" all 2.0))) [:line :at]))))))
