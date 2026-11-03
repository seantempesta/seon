(ns seon.doc-test
  "The wiki as data in the store (task seon.doc/docs-are-data-in-the-store): a section entity
  per heading, written only where a section changed; its references are what its own text
  cites, so its backlinks are who cites it."
  (:require [seon.test-store :as ts] [my.test :as mt] [clojure.java.shell] [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.doc :as doc] [seon.sqlite :as sqlite] [seon.root] [seon.config] [seon.source] [seon.fx] [seon.ref] [seon.problems :as problems]
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
  (let [tx (doc/load-tx db "demo" pages)]
    (is (vector? tx))
    (d/db-with db tx)))

(deftest an-unchanged-page-writes-nothing
  (let [db (load-pages (empty-db) pages)]
    (is (= "Count messages, with `app.m0/f1`." (:seon.ref/text (doc/view db "demo/flow.md#quiet" :code)))
        "the first load wrote each section")
    (is (some #{"demo/flow.md#idioms"} (map :seon.ref/address (:seon.ref/below (doc/view db "demo/flow.md#flow" :doc)))) "a section is below its parent")
    (is (= [] (doc/load-tx db "demo" pages)) "loading the same pages again writes nothing")))

(deftest a-changed-section-rewrites-only-itself
  (let [db (load-pages (empty-db) pages)
        quiet (:db/id (d/pull db [:db/id] [:seon.doc/address "demo/flow.md#quiet"]))
        edited (assoc pages "demo/flow.md" (str/replace flow "Count messages" "Count every message"))
        tx (doc/load-tx db "demo" edited)]
    (is (seq tx) "the changed section is written")
    (is (= #{quiet} (into #{} (map #(if (map? %) (:db/id %) (second %))) tx))
        "the load states only the changed section")
    (is (= "Count every message, with `app.m0/f1`."
           (:seon.ref/text (doc/view (d/db-with db tx) "demo/flow.md#quiet" :code))))))

;; docs/references.md#the-wiki-in-the-store: md-scan 29's agent added a row to one page with load-tx and its branch lost
;; the other 33 pages (556 sections), so the system prompt read nil and two turns threw
(deftest a-load-of-one-page-leaves-the-others
  (let [db (load-pages (empty-db) pages)
        edited {"demo/flow.md" (str/replace flow "Count messages" "Count every message")}
        db' (d/db-with db (doc/load-tx db "demo" edited))]
    (is (= "Count every message, with `app.m0/f1`." (:seon.ref/text (doc/view db' "demo/flow.md#quiet" :code))) "the page given is loaded")
    (is (= #{"demo/async.md#async" "demo/async.md#waiting"}
           (set (filter #(str/starts-with? % "demo/async.md") (map :v (d/datoms db' :avet :seon.doc/address)))))
        "a page not given keeps every section")))

(deftest a-link-to-no-section-is-a-dead-link-and-the-rest-loads
  (let [broken (assoc pages "demo/async.md" (str/replace async "flow.md#idioms" "flow.md#nowhere"))
        db (load-pages (empty-db) broken)
        dead #(get-in (problems/health %) [:classes :dead-citation :rows])]
    (is (= #{"demo/flow.md#flow" "demo/flow.md#idioms" "demo/flow.md#quiet" "demo/async.md#async" "demo/async.md#waiting"}
           (set (map :v (d/datoms db :avet :seon.doc/address)))) "every section loads")
    (is (= #{"demo/flow.md#nowhere"} (set (:seon.doc/dead-links (d/pull db [:seon.doc/dead-links] [:seon.doc/address "demo/async.md#waiting"]))))
        "the link is kept on the section that makes it")
    (is (= [["demo/async.md#waiting" "demo/flow.md#nowhere" "demo/async.md#waiting links to demo/flow.md#nowhere, which is no section"]]
           (mapv (juxt :section :target :message) (dead db))) "and is exactly one dead citation, naming page, section and target")
    (is (empty? (doc/load-tx db "demo" broken)) "an unchanged wiki writes nothing")
    (let [fixed (d/db-with db (doc/load-tx db "demo" (assoc broken "demo/flow.md" (str flow "\n## Nowhere\n\nNow it exists.\n"))))]
      (is (empty? (dead fixed)) "the target section is added: the dead citation closes, and nothing was written to close it"))))

(defn- backlinks
  "The addresses of the sections whose text cites target (a section's address or a var's name), sorted."
  [db target]
  (let [e (if (string? target)
            (d/q '[:find ?e . :in $ ?a :where [?e :seon.doc/address ?a]] db target)
            (d/q '[:find ?e . :in $ ?n :where [?e :var/name ?n]] db target))]
    (vec (sort (when e (d/q '[:find [?a ...] :in $ ?t :where [?s :seon.doc/references ?t] [?s :seon.doc/address ?a]] db e))))))

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
        idioms (doc/view db "demo/flow.md#idioms" :summary)
        counting (doc/view db "demo/quiet.md#counting" :summary)]
    (is (= ["demo/async.md#waiting" "demo/quiet.md#counting"] (mapv :seon.ref/address (:seon.ref/cited-by idioms)))
        "who cites it, from the stored edges")
    (is (= "Count as the [idiom](flow.md#idioms) does, by `app.m0/f0`."
           (:seon.ref/summary (second (:seon.ref/cited-by idioms)))) "each at summary depth")
    (is (= ["app.m0/f0" "demo/flow.md#idioms"] (mapv :seon.ref/address (:seon.ref/references counting)))
        "what it cites, a section and a var")
    (is (= "Park on a channel, as ```app.m0/f0``` does."
           (:seon.ref/summary (second (:seon.ref/references counting)))))
    (is (not (contains? (doc/view db "demo/flow.md#quiet" :summary) :seon.ref/cited-by)) "an edge-less section lists none")
    (is (= (:seon.ref/references counting) (:seon.ref/references (doc/view db "demo/quiet.md#counting" :code))) "at every depth")))

(deftest a-page-reads-at-a-depth
  (let [db (load-pages (empty-db) pages)
        summary (doc/page db "demo/flow.md" :summary)
        code (doc/page db "demo/flow.md" :code)]
    (is (= ["demo/flow.md#flow" "demo/flow.md#idioms" "demo/flow.md#quiet"] (mapv :seon.ref/address summary)))
    (is (= [1 2 2] (mapv :seon.ref/level summary)))
    (is (= (mapv #(doc/view db (:seon.ref/address %) :summary) summary) (mapv #(dissoc % :seon.ref/level) summary))
        "each section is its view at that depth")
    (is (= "Park on a channel, as ```app.m0/f0``` does." (:seon.ref/text (second code))))
    (is (not-any? :seon.ref/text summary))
    (is (nil? (doc/page db "demo/none.md" :summary)))))

(deftest every-section-of-the-wiki-has-a-lead
  ;; docs/README.md: every section leads with one sentence that stands alone; the list is read from the
  ;; real wiki as main holds it (the archived paper dogfood/docs-audit.md (git history) found 54 of 364 without)
  (let [db0 (empty-db)
        db (d/db-with db0 (doc/load-dir-tx db0 (config/checkout) "docs"))]
    (is (< 300 (count (d/datoms db :aevt :seon.doc/address))) "the real wiki is read")
    (is (= [] (mapv :section (get-in (problems/health db) [:classes :leadless-section :rows]))) "no section lacks a lead")))

(deftest no-section-names-a-bench-run
  ;; docs/README.md#how-the-wiki-is-kept: a section states its rule as current truth, never which bench run showed it
  ;; (what an agent reads must not hint at a task's answer); decisions.md and plan.md name the runs they were made on.
  ;; src is read whole, docstrings and comments both: an agent reads them through `doc`, `source` and `lines`.
  ;; the ids are the runs' real names (docs/decisions.md, docs/plan.md): md-scan, scope-<N>, bench-<md-scan|tools|scope-N|b..>,
  ;; a known bench's name before " #N" or " bench", and a session's id after "session"; a hyphenated word alone (step-fn #2, read-only bench, bench-top) is not one
  (let [id #"(?:^|[^A-Za-z0-9_-])(md-scan\b|bench-(?:md-scan|tools|scope-[0-9]+|b[0-9]*)\b|scope-[0-9]+\b|(?:md-scan|fence|scope) (?:#[0-9]+|bench\b)|session,? \(?[0-9a-f]{10}\b)"
        root (config/checkout)
        pages (for [^java.io.File f (.listFiles (java.io.File. (str root "/docs")))
                    :when (and (str/ends-with? (.getName f) ".md") (not (#{"decisions.md" "plan.md"} (.getName f))))]
                f)
        srcs (for [^java.io.File f (file-seq (java.io.File. (str root "/src")))
                   :when (re-find #"\.cljc?$" (.getName f))]
               f)
        hits (for [^java.io.File f (sort-by #(.getPath ^java.io.File %) (concat pages srcs))
                   :let [ids (distinct (map second (re-seq id (slurp f))))]
                   :when (seq ids)]
               [(subs (.getPath f) (inc (count root))) (vec ids)])]
    (is (< 50 (count srcs)) "src is read")
    (is (empty? hits) "no page outside decisions.md and plan.md, and no src file, names a bench or a numbered run: each is [path ids]")))

(deftest a-loaded-page-draws-back-as-its-file
  ;; docs as code (task seon.doc/any-markdown-file-is-docs-it-writes-back): the store holds each page, so the file is
  ;; drawn from it as src is from the program (seon.source-test/import-then-write-back-is-a-zero-diff); the real wiki,
  ;; both dirs, every page
  (let [db0 (empty-db)
        co (config/checkout)
        dirs (config/value :seon.config.root/wiki-dirs)
        db (d/db-with db0 (doc/load-dir-tx db0 co dirs))
        files (into {} (for [dir dirs [p t] (doc/pages co dir)] [p t]))]
    (is (< 30 (count files)) "the real wiki is read")
    ;; a page that does not draw back names where it first differs: the file's text there and the drawn
    (is (= {} (into {} (for [[p t] (sort files) :let [m (doc/markdown db p)] :when (not= t m)
                             :let [i (or (first (keep-indexed (fn [i [a b]] (when (not= a b) i)) (map vector t m))) (min (count t) (count m)))]]
                         [p {:at i :file (subs t (max 0 (- i 60)) (min (count t) (+ i 60))) :drawn (subs m (max 0 (- i 60)) (min (count m) (+ i 60)))}])))
        "every page draws back as its file")
    (is (nil? (doc/markdown db "docs/none.md")) "a page the store holds no doc of is none")))

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
      (is (= text (:seon.ref/text (doc/view db a :code))) (str "docs: " a)))
    (is (= ["demo/flow.md#idioms" "demo/flow.md#quiet"] (mapv :seon.ref/address (:seon.ref/below (doc/view db "demo/flow.md#flow" :code))))
        "the sections below by address, not inline")))

;; ---- names are Clojure's words (task seon.doc/names-are-clojures-words) ----------------

(def ^:private kept
  "Names that keep a word of AGENTS.md section 7a's table in another sense, each with its reason."
  {'seon.fx/admit "admission by workload (a token bucket and free workers: AGENTS.md section 4), not a contract"
   'seon.fx-admit-test "the same admission (the namespace and its vars)"
   'seon.fx-test/owner-admits-by-workers-and-priority "the same admission"
   "test/seon/fx_admit_test.clj" "the same admission"})

(def ^:private old-words seon.problems/old-words)

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
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (seon.config/checkout) "/target/tmp/git-fixture-" (random-uuid))) (.mkdirs)))
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
        pages (seon.doc/pages dir "docs")
        plan (seon.doc/baseline-plan db1 pages)
        answer (seon.fx/wiki-history {:req {:root dir :sections plan}})
        baseline (seon.doc/baseline-reads db1 plan (:value answer))]
    {:db1 db1 :plan plan :answer answer :baseline baseline :pages pages
     :db (d/db-with db1 (seon.doc/load-tx db1 "docs" pages baseline))}))

(deftest a-sections-baseline-is-the-code-as-of-its-last-commit
  (let [{:keys [dir first second]} (git-fixture)
        {:keys [db plan answer baseline]} (db-of-fixture dir "src/app/gb.clj")
        read #(:seon.doc/read (d/pull db [:seon.doc/read] [:seon.doc/address %]))
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
      (is (= [] (seon.doc/load-tx db "docs" (seon.doc/pages dir "docs")))))))

(deftest a-baseline-git-cannot-give-is-today-and-says-so
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (seon.config/checkout) "/target/tmp/not-git-" (random-uuid))) (.mkdirs)))
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
              {:path "docs/p.md" :address "docs/p.md#d" :lines [10 12] :files {'app.m0/f3 {:file "src/app/m0.clj" :ns 'app.m0}}}
              {:path "docs/p.md" :address "docs/p.md#e" :lines [13 15] :files {'app.m0/f5 {:file "src/app/m0.clj" :ns 'app.m0}}}]
        ;; f5 is only in a string, a comment and the longer symbol f55: the file does not mention it
        old "(ns app.m0)\n\n(defn f0 [] 1)\n\n(my.macros/defthing f1 [] 2)\n\n;; f5 was here\n(defn f55 [] \"calls (f5 )\")\n"
        answer {:sections {"docs/p.md#a" {:commit (apply str (repeat 40 "a"))} "docs/p.md#b" {:commit (apply str (repeat 40 "a"))}
                           "docs/p.md#e" {:commit (apply str (repeat 40 "a"))} "docs/p.md#c" {:commit nil} "docs/p.md#d" {:commit (apply str (repeat 40 "b"))}}
                :files {[(apply str (repeat 40 "a")) "src/app/m0.clj"] old}
                :errors {[(apply str (repeat 40 "b")) "src/app/m0.clj"] "git show: exploded"}}
        r (seon.doc/baseline-reads db plan answer)]
    (testing "a var defined by a form of a head defining-ids knows is found at that commit, never absent"
      ;; this db holds no app.m0/f0 today, so what the section described of it (its meaning at the
      ;; commit) is not today's: :changed
      (is (= :changed (get-in r ["docs/p.md#a" :read 'app.m0/f0])))
      (is (nil? (:unread (r "docs/p.md#a")))))
    (testing "a var the old file mentions but no known head defines is unread, never absent"
      (is (re-find #"no form this reading knows" (:unread (r "docs/p.md#b")))))
    (testing "a name only in a string, a comment or a longer symbol is not mentioned: absent, never unread"
      (is (nil? (:unread (r "docs/p.md#e"))))
      (is (= :absent (get-in r ["docs/p.md#e" :read 'app.m0/f5]))))
    (testing "a section with an uncommitted line is as of today, nothing unread"
      (is (= {:read (seon.doc/read-map db #{'app.m0/f2})} (r "docs/p.md#c"))))
    (testing "a file git could not show is unread with git's words, never absent"
      (is (re-find #"git show: exploded" (:unread (r "docs/p.md#d")))))))

(deftest a-review-is-a-commit-and-survives-a-rebuilt-store
  ;; a review (my.doc/reviewed!) is an empty git commit with `Reviewed: <address>`; the baseline takes, per
  ;; section, the newer of the commit that last changed its lines and the last review naming it, from one
  ;; git log, so a store rebuilt from scratch reads the reviewed section fresh
  (let [{:keys [dir]} (git-fixture)
        stale-of (fn [] (let [{:keys [db]} (db-of-fixture dir "src/app/gb.clj")]
                          (set (map (juxt :section :name) (get-in (problems/health db) [:classes :stale-section :rows])))))
        branch (str/trim (:out (clojure.java.shell/sh "git" "-C" dir "rev-parse" "--abbrev-ref" "HEAD")))]
    (is (contains? (stale-of) ["docs/p.md#p" 'app.gb/f]) "before the review the section is stale")
    (let [r (seon.fx/git-commit {:req {:dir dir :branch branch :files {} :allow-empty? true :message (seon.doc/review-message "docs/p.md#p" "sean")}})]
      (is (string? (get-in r [:value :sha])) "the review is a commit")
      (is (= 1 (count (re-seq #"Reviewed: docs/p.md#p" (:out (clojure.java.shell/sh "git" "-C" dir "log" "HEAD" "--format=%B")))))))
    (let [after (stale-of)]
      (is (not (contains? after ["docs/p.md#p" 'app.gb/f])) "a store rebuilt from the repository reads the reviewed section fresh")
      (is (contains? after ["docs/p.md#q" 'app.gb/h]) "and the section nobody reviewed is still stale"))))

(deftest a-review-on-another-branch-does-not-freshen-the-checkouts-section
  (let [{:keys [dir]} (git-fixture)
        stale-of (fn [] (let [{:keys [db]} (db-of-fixture dir "src/app/gb.clj")]
                          (set (map (juxt :section :name) (get-in (problems/health db) [:classes :stale-section :rows])))))
        r (seon.fx/git-commit {:req {:dir dir :branch "elsewhere" :files {} :allow-empty? true :message (seon.doc/review-message "docs/p.md#p" "sean")}})]
    (is (string? (get-in r [:value :sha])) "the review commit exists, on a branch HEAD does not contain")
    (is (contains? (stale-of) ["docs/p.md#p" 'app.gb/f]) "and HEAD's section is still stale")))

;; ---- text uses Clojure's words too (task seon.doc/names-are-clojures-words) ---------------

(defn- texts-outside-store
  "The checkout files (under root) no-text-uses-an-old-word reads: what git lists under dirs, tracked or
  untracked and not ignored (`git ls-files -co --exclude-standard`, as the program's sample tree takes them),
  so a file the repository ignores (config/local.edn, which bin/ports writes with the worktree's own name)
  is no text of the repository; a tracked file deleted on disk is no file. text? and skip? pick by path."
  [root dirs text? skip?]
  (->> (let [r (apply clojure.java.shell/sh "git" "-C" root "ls-files" "-co" "--exclude-standard" "--" dirs)]
         (assert (zero? (:exit r)) (str "git ls-files failed: " (:err r)))
         (:out r))
       str/split-lines sort
       (filter #(and (text? %) (not (skip? %)) (.isFile (java.io.File. (str root "/" %)))))))

(deftest an-ignored-file-is-no-text-and-a-tracked-one-is
  ;; the red of 2026-11-01: the worktree terminal-verbs failed no-text-uses-an-old-word with
  ;; ["config/local.edn" 3 "verb"], a file git ignores that holds the worktree's name
  (let [{:keys [dir]} (git-fixture)
        put (fn [p t] (.mkdirs (.getParentFile (java.io.File. (str dir "/" p)))) (spit (str dir "/" p) t))
        _ (do (put ".gitignore" "/config/local.edn\n")
              (put "config/local.edn" "{:worktree \"terminal-verbs\"}\n")
              (put "config/default.edn" "{:a-verb 1}\n")
              (clojure.java.shell/sh "git" "-C" dir "add" ".gitignore" "config/default.edn"))
        paths (texts-outside-store dir ["config"] #(str/ends-with? % ".edn") (constantly false))
        words (seon.problems/old-word-spellings (slurp (str (config/checkout) "/AGENTS.md")))
        hits (for [p paths [i w] (seon.problems/old-word-hits (map-indexed vector (str/split-lines (slurp (str dir "/" p)))) words)] [p (inc i) w])]
    (is (= ["config/default.edn"] paths) "the ignored file is not read, the tracked one is")
    (is (= [["config/default.edn" 1 "verb"]] hits) "and the tracked file's old word is still found")))

(deftest no-text-uses-an-old-word
  ;; what the store holds is judged as a derived class (`seon.problems/old-words-in`, seon.problems-test/an-old-word-is-a-problem,
  ;; and by the health step on every branch); this reads what it does not hold: bin/, the root pages, the
  ;; native and web files, resources other than the task files (their plans are in the store) and the comment
  ;; lines of source
  ;; (a red proof of this test writes a file under the checkout that git does not track, and a result is kept
  ;; by the tracked tree's digest: prove it red in a tree of its own, then change this test, so the red is not
  ;; kept at the digest the green tree has, seon.proofs/flaky)
  (let [root (config/checkout)
        words (seon.problems/old-word-spellings (slurp (str root "/AGENTS.md")))
        text? (fn [^String p] (or (str/starts-with? p "bin/") (re-find #"\.(clj|edn|md|html|js|css|c|h)$" p)))
        in-store? (fn [^String p] (or (str/starts-with? p "docs/") (str/starts-with? p "resources/seon/tasks/")))
        comment-only? (fn [^String p] (and (str/ends-with? p ".clj") (not (str/starts-with? p "bin/"))))
        paths (texts-outside-store root ["bin" "resources" "src" "test" "web" "config" "native"] text? in-store?)
        hits (for [p (concat ["AGENTS.md" "BAGGAGE.md" "README.md"] paths) :when (not (seon.problems/old-word-kept p))
                   :let [lines (vec (str/split-lines (slurp (str root "/" p))))]
                   [i w] (seon.problems/old-word-hits (for [[i l] (map-indexed vector lines) :when (or (not (comment-only? p)) (str/starts-with? (str/triml l) ";"))] [i l]) words)]
               [p (inc i) w])]
    (is (contains? (set paths) "src/seon/problems.clj") "git listed the checkout's files: a listing that came back empty reads nothing")
    (is (contains? (set (map first words)) "attempt") "the table is read, the run row included")
    (is (empty? hits) "no text outside the store uses an old word outside old-word-kept: each is [file line word]")))

(defn grep-db
  "A test db holding forms [name doc src]: each a var app/<name> defined by its form."
  [forms]
  (let [db0 (mt/world)]
    (d/db-with db0 (vec (mapcat (fn [[n doc src]]
                                  [(cond-> {:db/id (str "v" n) :var/name (symbol "app" n)} doc (assoc :var/doc doc))
                                   {:db/id (str "f" n) :form/id (str "app/" n " " src) :form/src src :form/defines [(str "v" n)]}])
                                forms)))))

(defn kinds-db
  "grep-db's forms beside a wiki section, a task and a schema entry that hold the word `fusing`."
  []
  (d/db-with (grep-db [["g" nil "(defn g []\n  (fusing 1))"] ["h" nil "(defn h [] 3)"]])
             [{:seon.doc/address "p.md#fusing-ranks" :seon.doc/heading "Fusing ranks" :seon.doc/text "Ranks are fused.\nThe fusing is by reciprocal rank."}
              {:seon.doc/address "p.md#other" :seon.doc/heading "Other" :seon.doc/text "Fusing, in the middle."}
              {:seon.task/id "app/fusing-task" :seon.task/plan "Plan.\nMake the FUSING cheaper."}
              {:schema/key :app/fuse :schema/form "[:map {:doc \"fusing\"}]"}]))

(defn corpus-db
  "kinds-db among filler: 40 forms and 20 sections that hold none of the word, so a few matching texts stand
  out of the corpus' scores (tokens keeps only those z standard deviations above the mean)."
  []
  (d/db-with (kinds-db)
             (vec (concat (mapcat (fn [i] [{:db/id (str "fv" i) :var/name (symbol "fill" (str "v" i))}
                                           {:form/id (str "fill/" i) :form/src (str "(defn v" i " [] (inc " i "))") :form/defines [(str "fv" i)]}])
                                  (range 40))
                          (for [i (range 20)] {:seon.doc/address (str "fill.md#s" i) :seon.doc/heading (str "Filler " i)
                                               :seon.doc/text (str "Plain prose number " i ".")})))))

(deftest tokens-keep-the-texts-that-stand-out-by-the-words-of-a-query
  (let [db (corpus-db) hit (fn [h] [(:kind h) (:name h)])
        all #{:var :test :section :task :schema}]
    (is (= #{[:task "app/fusing-task"] [:section "p.md#fusing-ranks"] [:section "p.md#other"] [:var 'app/g] [:schema :app/fuse]}
           (set (map hit (seon.doc/tokens db "fusing" all 2.0)))) "every kind holding the word, and none of the filler")
    (is (= [:section "p.md#fusing-ranks"] (hit (first (seon.doc/tokens db "fusing ranks" all 2.0)))) "more of the words: first")
    (is (every? #(< 2.0 (:z %)) (seon.doc/tokens db "fusing" all 2.0)))
    (is (= [[:var 'app/g]] (map hit (seon.doc/tokens db "fusing" #{:var} 2.0))) "the kinds asked only")
    (is (= [] (seon.doc/tokens db "nowhere" all 2.0)) "no word of it in any text")
    (is (= [] (seon.doc/tokens db "" all 2.0)))
    (is (= ["datoms" "diff" "seon" "run" "outcome" "nearof" "ab"] (seon.doc/words "datoms-diff seon.run/outcome nearOf aB x")) "an identifier is its parts at separators, as the index tokenizes a text; a one-letter part is no word")
    (testing "the first line holding a word, and its number"
      (is (= {:line "(fusing 1))" :at 2} (select-keys (first (filter #(= 'app/g (:name %)) (seon.doc/tokens db "fusing" all 2.0))) [:line :at]))))))

(defn- with-checkout
  "(f dir) where dir is a checkout of a few pages, as the files the wiki loads are read (docs/ and the top level, the
  :seon.config.root/wiki-dirs): AGENTS.md, BAGGAGE.md and README.md citing a var and linking docs/agent.md, which
  cites one too. These tests judge the loading mechanism; the real pages' links are judged by health (seon.problems). The directory is made under target/ and removed when f returns."
  [f]
  (let [dir (str (seon.config/checkout) "/target/wiki-fixture-" (random-uuid))
        put (fn [path text] (let [file (java.io.File. (str dir "/" path))] (.mkdirs (.getParentFile file)) (spit file text)))]
    (put "docs/agent.md" "# Agent\n\nAn agent calls app.calc/add.\n\n## Turns\n\nA turn totals with app.calc/total.\n")
    (put "AGENTS.md" "# AGENTS\n\nHow we build, see [agent](docs/agent.md#agent) and app.calc/add.\n")
    (put "BAGGAGE.md" "# BAGGAGE\n\nWhat was left out, see [turns](docs/agent.md#turns).\n")
    (put "README.md" "# README\n\nStart with [AGENTS](AGENTS.md#agents).\n")
    (try (f dir) (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest the-wiki-holds-the-top-level-pages-beside-docs
  ;; one-index step 6: AGENTS.md, BAGGAGE.md and README.md are sections beside docs/'s, so one search and
  ;; one view reach them (:seon.config.root/wiki-dirs). A checkout of a few pages (with-checkout) on the world: the
  ;; load reads a checkout's dirs, never this one's whole wiki (2026-10-31, shrink-tests-4)
  (with-checkout
    (fn [co]
      (let [db0 (mt/world)
            tx (seon.root/wiki-tx db0 co)
            db (d/db-with db0 tx)
            paths (set (seon.doc/page-paths db))]
        (is (every? paths ["AGENTS.md" "BAGGAGE.md" "README.md" "docs/agent.md"]) "the top-level pages and docs/'s")
        (is (seq (seon.doc/page db "AGENTS.md" :summary)) "a top-level page reads through the one view")
        (is (= [] (seon.root/wiki-tx db co)) "an unchanged wiki writes nothing")
        (is (= [] (mapv (juxt :section :message) (filter #(re-find #"^[^/#]+#" (:section %)) (problems/dead-citations db {})))) "the top-level pages add no dead citation")
        (is (= "docs" (first (seon.config/value :seon.config.root/wiki-dirs))))
        (is (= {"AGENTS.md" true} (select-keys (into {} (map (fn [p] [p true])) (keys (seon.doc/pages co "."))) ["AGENTS.md"])) "pages of \".\" are named without a directory")))))

(deftest the-wiki-of-two-dirs-loads-in-one-pass
  ;; docs/references.md#the-wiki-in-the-store: a link from one dir's page to another's resolves in the one load
  ;; of the dirs (the boot used to load each dir twice for it), and a reload of the same pages writes nothing
  (let [db (ts/test-db)
        pages {"demo/a.md" "# A\n\nSee [b](../other/b.md#b).\n" "other/b.md" "# B\n\nIt is the target.\n"}
        tx (doc/load-tx db ["demo" "other"] pages)
        db' (d/db-with db tx)]
    (is (not-any? #(and (map? %) (:seon.doc/dead-links %)) tx) "the link reaches the section of the other dir")
    (is (= 1 (count (:seon.doc/references (d/pull db' [{:seon.doc/references [:seon.doc/address]}] [:seon.doc/address "demo/a.md#a"]))))
        "and is stored as a reference")
    (is (= [] (doc/load-tx db' ["demo" "other"] pages)) "an unchanged wiki writes nothing")
    (is (seq (doc/load-tx db' ["demo" "other"] (dissoc pages "other/b.md"))) "a page gone retracts its sections under the other dir")))

(deftest a-boot-whose-wiki-digest-is-recorded-loads-nothing
  ;; docs/root.md#boot: the digest of what the load reads (the pages, the program, the task ids) rides on root's
  ;; fact; a boot that computes the same writes no wiki and reads none, and a change of any input loads again.
  ;; A checkout of a few pages on the world (2026-10-31, shrink-tests-4)
  (with-checkout
    (fn [co]
      (let [db0 (mt/world)
            tx (seon.root/wiki-boot-tx db0 co (constantly {}))
            db (d/db-with db0 tx)]
        (is (seq tx) "a first boot loads the wiki")
        (is (= (seon.root/wiki-digest db0 co) (:seon.root/wiki (d/pull db [:seon.root/wiki] [:seon.root/id :root]))) "and records its digest")
        (is (= [] (seon.root/wiki-boot-tx db co (constantly {}))) "the same inputs load nothing")
        (is (seq (seon.root/wiki-boot-tx (d/db-with db [{:seon.task/id "demo/new-task"}]) co (constantly {}))) "a new task id loads again")))))

(deftest a-text-index-is-carried-past-a-move-that-writes-no-text
  ;; docs/vector.md#search: seon.doc/carry-index! keeps the index a move left as it was (no build),
  ;; and a move that writes a text attribute leaves it to be built again, holding the new text
  (let [db0 (mt/world)
        ix0 (seon.doc/text-index db0)
        n0 (sqlite/size ix0)
        attrs #(into #{} (map :a) (:tx-data %))
        r1 (d/with db0 [{:seon.root/id :root :seon.root/boot "a-boot"}])
        _ (seon.doc/carry-index! db0 (:db-after r1) (attrs r1))
        db1 (:db-after r1)
        ;; the slot is keyed to db1 by the carry itself, before any read could build
        slot @@#'seon.doc/indexes
        ix1 (seon.doc/text-index db1)
        r2 (d/with db1 ((mt/code 'app.qq "(ns app.qq)\n\n(defn qqzz [] 1)\n") db1))
        _ (seon.doc/carry-index! db1 (:db-after r2) (attrs r2))
        ix2 (seon.doc/text-index (:db-after r2))]
    (is (= [(:hash db1) (:max-tx db1)] (first slot)) "the carry keys the held index to the move's db")
    (is (identical? ix0 (second slot)) "and keeps the same index")
    (is (identical? ix0 ix1) "a move that wrote no text keeps the index")
    (is (identical? ix0 ix2) "a move that wrote form texts has those rows replaced in it")
    (is (= (+ 2 n0) (sqlite/size ix2)) "and the two texts are in it")
    (is (= 1 (count (:value (sqlite/using ix2 #(sqlite/fts-match % "\"qqzz\"" #{}))))) "found by its word")
    (is (= [(:hash (:db-after r2)) (:max-tx (:db-after r2))] (first @@#'seon.doc/indexes)) "keyed to the db it was carried to")))

(deftest a-written-plan-is-brought-into-the-index-without-a-rebuild
  ;; docs/vector.md#search: a move that writes a text attribute has that attribute's rows replaced in the held index
  ;; (seon.doc/carry-index!), reading its texts alone: a task's plan written on main must not read every form again
  (let [db0 (mt/world)
        ix0 (seon.doc/text-index db0)
        r (d/with db0 [{:seon.task/id "app/zorb" :seon.task/plan "Zorblax the thing."}])
        _ (seon.doc/carry-index! db0 (:db-after r) (into #{} (map :a) (:tx-data r)))
        ix1 (seon.doc/text-index (:db-after r))
        found #(count (:value (sqlite/using ix1 (fn [ix] (sqlite/fts-match ix "\"zorblax\"" #{})))))]
    (is (identical? ix0 ix1) "the index stands")
    (is (= 1 (found)) "and holds the plan")))

(deftest tokens-match-stems-and-whole-terms
  ;; docs/vector.md#search: the text ranking is FTS5's over Porter stems. The substring match it replaces found
  ;; "it" in every term holding it ("edit"), and a request's "watching" never found `watch!`
  (let [db (mt/with (mt/world)
                    (mt/code 'app.sw "(ns app.sw)\n\n(defn watch! \"Observes a path.\" [] 1)\n(defn edit [] 2)\n(defn keep-it [] 3)\n(defn seonRun [] 4)\n"))
        names (fn [q] (set (map :name (seon.doc/tokens db q #{:var} 1.0))))]
    (is (contains? (names "a watcher, watching") 'app.sw/watch!) "a stemmed word: watching is watch")
    (is (contains? (names "keep it") 'app.sw/keep-it) "a whole term")
    (is (contains? (names "SeonRun") 'app.sw/seonRun) "a query is tokenized as a text is: an identifier is one term in both")
    (is (not (contains? (names "it") 'app.sw/edit)) "a short word is no part of a longer term")))

(deftest grep-finds-a-string-inside-a-term-as-grep-does
  ;; docs/vector.md#search: grep's candidates come from the index's trigram table, so a string inside a longer
  ;; identifier, or across a separator inside one, is found; a string of two characters scans every text
  (let [db (mt/with (mt/world)
                    (mt/code 'app.gi "(ns app.gi)\n(defn eval-n! [x] (destructure x))\n(defn decode-string [] 1)\n(defn other [] 1)"))
        at (fn [p] (set (keep #(when (re-find #"^app\.gi" (:at %)) (:at %)) (doc/grep db p #{:var} nil))))]
    (is (= #{"app.gi/eval-n!:1"} (at "val-n")) "inside a term and across its separator")
    (is (= #{"app.gi/eval-n!:1"} (at "stru")) "inside a term")
    (is (= #{"app.gi/eval-n!:1"} (at "n! [x")) "across a space and brackets")
    (is (= #{"app.gi/decode-string:1"} (at "code-st")) "across a separator, from inside one term to the start of the next: the longest word, `code`, begins no term")
    (is (= #{"app.gi/eval-n!:1"} (at "x)")) "two characters: every text is read")))

(deftest a-superseded-index-is-closed-once-nothing-uses-it
  ;; docs/vector.md#search: SQLite's memory is native, invisible to the collector, so text-index retires the index it
  ;; replaces: closed at once when nobody uses it, by the last reader's release when one does
  (let [db1 (mt/world)
        db2 (d/db-with db1 [{:schema/key :app/one :schema/form "[:map]"}])
        db3 (d/db-with db1 [{:schema/key :app/two :schema/form "[:map]"}])
        ix1 (doc/text-index db1)
        ix2 (doc/text-index db2)
        during (sqlite/using ix2 (fn [ix] (doc/text-index db3) (sqlite/fts-match ix "\"map\"" #{})))
        ix3 (doc/text-index db3)]
    (is (nil? (sqlite/using ix1 identity)) "an index nobody used is closed when replaced")
    (is (vector? (:value during)) "an index in use stays open until its reader is done")
    (is (nil? (sqlite/using ix2 identity)) "and is closed after")
    (is (some? (sqlite/using ix3 identity)) "the current one is open")))

(deftest grep-answers-each-matching-line-as-a-row
  ;; the owner, 2026-11-01: tools like the terminal's, with the same meaning, answering vectors of maps the agent
  ;; narrows itself. grep is every line holding the pattern, over code, tests, sections and tasks, a row each,
  ;; numbered within its text as lines numbers it
  (let [db (mt/with (mt/world)
                    (mt/code 'app.gr "(ns app.gr)\n(defn total\n  \"Sums the needle.\"\n  [xs] (reduce + xs))\n(defn other [] 1)")
                    (mt/code 'app.gr-test "(ns app.gr-test (:require [clojure.test :refer [deftest is]]))\n(deftest needle-test (is true))")
                    (mt/page "docs/gr.md" "# Gr\n\nA lead.\n\n## Where the needle is\n\nThe needle lives here.\nAnd not here."))
        all #{:var :test :section :task :schema}
        rows (doc/grep db "needle" all nil)]
    ;; the world may hold texts of its own: this test's are app.gr's and docs/gr.md's
    (is (= #{"app.gr/total:2" "app.gr-test/needle-test:1" "docs/gr.md#where-the-needle-is:1"}
           (set (keep #(when (re-find #"^(app\.gr|docs/gr\.md)" (:at %)) (:at %)) rows)))
        (str "each line holding it, at its thing and line: " (pr-str rows)))
    (is (every? #(and (:at %) (:kind %) (string? (:text %))) rows) "rows are plain maps: :at :kind :text")
    (is (= [:var] (distinct (map :kind (doc/grep db "needle" #{:var} nil)))) ":in keeps the kinds asked")
    (is (= ["app.gr/total:3"] (map :at (doc/grep db #"reduce \+" #{:var} "app.gr"))) "a regex, and a name prefix")
    (is (= [] (doc/grep db "nowhere-at-all" all nil)) "no match is the empty vector")
    (is (= ["app.gr/total:2"] (map :at (doc/grep db "needle" #{:var :test} "app.gr")))
        ":ns is a name and what is under it: app.gr holds app.gr/total, not app.gr-test")
    ;; a test namespace's requires are in its ns form: grep answered [] for them, and six evals went on it
    (is (= [{:at "app.gr-test:1" :kind :test}] (map #(dissoc % :text) (doc/grep db "clojure.test :refer" #{:var :test} "app.gr-test")))
        "an ns form's lines are the namespace's, at its line as (lines 'the.ns) numbers it, a test namespace's a :test")
    (is (= ["app.gr:1"] (map :at (doc/grep db "ns app.gr" #{:var :test} "app.gr"))) "and a namespace's own, its -test one not under it")
    (is (= [":app/fuse:1"] (map :at (doc/grep (kinds-db) "fusing" #{:schema} "app")))
        "a schema key is under its namespace too, read without its colon")
    (is (= ["docs/gr.md#gr" "docs/gr.md#where-the-needle-is"] (map :at (doc/outline db "docs/gr.md"))) "outline: the page's sections in order")
    (is (nil? (doc/outline db "docs/none.md")) "a page db holds none of is nil")))
