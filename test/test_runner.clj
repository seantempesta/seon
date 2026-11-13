(ns test-runner
  "bin/test's and bin/prove's runner:
  :seon.config.source/dirs imported as program facts (program-db), as root imports them, each test keyed by its reach digest
  (seon.program/graph-index) and each bin/check step by its functions' (seon.proofs/
  check-steps); seon.proofs/plan selects what has no green result at its digest.
    --plan WHAT OUT [--all] [NS ...]  the selection: tests dealt into OUT/shard-<i>.edn, steps
                                      into OUT/steps (WHAT: tests, checks or both)
    --shard FILE                      one shard's tests run here, each result stored
    --store-steps OUT                 the steps bin/check ran green stored at their digests
    --overran FILE                    a shard bin/stalls ended: its overran test stored as an error, the rest to FILE's -rest.edn
  bin/test runs each shard in a process of its own, at once. The program the plan reads is kept
  per git tree in a store of its own (program-db)."
  (:require [konserve.jolt.lmdb :as klmdb] [seon.profile :as profile] [konserve.jolt.fasl-store :as fasl] [clojure.java.io :as io] [clojure.java.shell :as sh] [clojure.string :as str] [clojure.edn :as edn]
            [datahike.api :as d] [seon.config :as config] [seon.db :as pdb] [seon.test-store :as ts] [seon.agent]
            [seon.edn :as sedn] [seon.error :as error] [seon.lib :as lib] [seon.contract :as contract] [seon.fx :as fx] [seon.program :as program] [seon.proofs :as proofs] [seon.print :as print] [seon.source :as source] [seon.graph :as graph]
            [seon.doc :as doc] [seon.task :as task] [seon.problems :as problems] [my.root]))

(defn- test-namespaces
  "Namespace symbols of <tree>/test/**/*_test.clj(c): tree the checkout planned (source-tree, or
  the one a proof names). Never the working directory: a root and its agents run in target/
  and the agents' scratch dir, where a relative \"test\" is nothing."
  [tree]
  (let [prefix (str tree "/test/")]
    (->> (file-seq (io/file tree "test"))
         (map #(.getPath %))
         (filter #(re-find #"_test\.cljc?$" %))
         (map #(-> % (subs (count prefix)) (str/replace #"\.cljc?$" "") (str/replace "/" ".") (str/replace "_" "-") symbol))
         sort)))

(defn- source-tree
  "The tree the config names (:seon.config.source/root, when a root was given one: a clone an
  agent works on), else the checkout this process runs from."
  []
  (str (or (get config/config :seon.config.source/root) (config/checkout))))

;; ---- reach -----------------------------------------------------------------------------

;; The program db is a function of the git tree (owner via root, 2026-10-23, option B of
;; docs/source.md): kept as a branch named by the tree's id, a content key
;; that is never updated, in one LMDB store per checkout (:seon.config.proofs/program-store).

(defn- prefix-of
  "The prefix of the branches a tree's program is kept on, as :seon.config.source/dirs and an importer import it: text, a function
  of a relative path, answers the importer's files, the code that derives a program's facts from its files (seon.source,
  seon.program), since a form's :form/refs are its answer and a kept tree's unchanged files are not read again, so a store's
  branches made under other dirs or other code (another import) are none of this one's. See docs/source.md#tests-run-by-reach."
  [text]
  (str "tree-" (lib/digest (str (pr-str (config/value :seon.config.source/dirs))
                                (lib/digest (str/join "\n" (map text ["src/seon/source.clj" "src/seon/program.clj"]))))) "-"))

(def ^:private own-prefix "prefix-of this process's checkout, read once." (delay (prefix-of #(slurp (io/file (config/checkout) %)))))

(defn- tree-prefix "The prefix of the branches this process's code keeps a tree's program on (prefix-of, docs/source.md#tests-run-by-reach)." [] @own-prefix)

(defn tree-branch "The branch keeping the program of git tree id tree." [tree] (keyword (str (tree-prefix) tree)))

(defn- branch-tree "The tree id branch b keeps, or nil (b keeps no tree of this import)." [b]
  (let [n (name b) p (tree-prefix)] (when (str/starts-with? n p) (subs n (count p)))))

(defn- program-file?
  "Is relative path p one of the program's files: what root's import reads of
  :seon.config.source/dirs (a .clj, or a schema resource: seon.config/file-kind), so the
  program a plan reads here is root's, and a test's digest is the same on both sides?"
  [p] (and (some #(str/starts-with? p (str % "/")) (config/value :seon.config.source/dirs))
           (or (str/ends-with? p ".clj") (config/file-kind p))))

(defn- changed-paths
  "The program files that differ between git trees a and b of root's repository (git diff
  --name-only --no-renames: a moved file is its old path removed and its new one added)."
  [root a b]
  (filterv program-file? (str/split-lines (:out (sh/sh "git" "-C" root "diff" "--name-only" "--no-renames" a b)))))

(defn- tree-files
  "{path text} of the program files of git tree t in root's repository, read from git's objects,
  never the working tree (an edit after t was taken cannot be kept under t): paths' (nil for one
  t does not hold), or every program file under :seon.config.source/dirs. Through git archive into a
  directory of its own, removed after."
  [root t paths]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/seon-tree-" (random-uuid))
        tar (str dir ".tar")
        ;; what t holds of what is asked (git archive refuses a path it does not hold)
        held (filterv seq (str/split-lines (:out (apply sh/sh "git" "-C" root "ls-tree" (concat (when paths ["-r"]) ["--name-only" t "--"] (or paths (config/value :seon.config.source/dirs)))))))]
    (.mkdirs (io/file dir))
    (when (seq held)
      (apply sh/sh "git" "-C" root "archive" "--format=tar" "-o" tar t "--" held)
      (sh/sh "tar" "-x" "-f" tar "-C" dir))
    (let [files (if paths
                  (into {} (for [p paths :let [f (io/file dir p)]] [p (when (.isFile f) (slurp f))]))
                  (into {} (filter #(program-file? (key %))) (:value (fx/read-source {:req {:root dir :dirs (config/value :seon.config.source/dirs)}}))))]
      (seon.lib/remove-tree! dir tar)
      files)))

(defn keep-tree!
  "Makes branch b, keeping the program of git tree id tree of root's repository, in the store of conn
  (cfg: its config), whole or not at all, whichever run makes it first. The import commits on a
  staging branch of its own (:stage-<uuid>, none of branch-tree's), and b is branched from the
  staging branch's committed head in one step: b never exists half imported, so a run that finds
  it made meanwhile by another process (Datahike's :branch-already-exists, the one catch, docs/
  failure-points.md#edges-that-catch) reads the same program, and drops its staging branch
  (docs/source.md). branches: the store's, from which the nearest
  kept tree is chosen."
  [conn cfg root tree b branches]
  (let [[from paths] (->> (for [k branches :let [t (branch-tree k)]
                                 :when (and t (zero? (:exit (sh/sh "git" "-C" root "cat-file" "-e" t))))]
                             [k (changed-paths root t tree)])
                           (sort-by (comp count second))
                           first)
        ;; the parent's db, a value (the store's own connection's for :db)
        parent (if from (let [p (pdb/connect (assoc cfg :branch from))] (try @p (finally (d/release p)))) @conn)
        ;; a namespace the kept tree holds whose file the checkout lacks (another tree's, e.g. one since
        ;; renamed) is read again from this tree with the change, so a changed file requiring it finds it
        ;; analyzed in this process (seon.source's read-nss marks it loaded), never a load from disk that cannot
        ;; and every file where tree differs from the checkout this process runs (fx/tree-id): analysis resolves a name the
        ;; changed files use against what this process has loaded, so a file left unread because the kept tree holds it as tree
        ;; does is still this process's text, not tree's (health's base, main, read by a branch that deleted a var main's tests
        ;; use: `Unable to resolve var`). Nothing for the checkout's own tree; for another, the files the two differ by
        paths (if from
                (-> (set paths)
                    (into (for [dt (d/datoms parent :aevt :ns/file) :let [p (:v dt)]
                                :when (not (.exists (io/file root p)))
                                :when (zero? (:exit (sh/sh "git" "-C" root "cat-file" "-e" (str tree ":" p))))]
                            p))
                    (into (let [running (fx/tree-id root)] (when (not= running tree) (changed-paths root running tree)))))
                paths)
        tx (if from
             (source/refresh-tx parent (tree-files root tree paths))
             (source/import-tx parent (tree-files root tree nil)))
        stage (keyword (str "stage-" (random-uuid)))]
    (d/branch! conn (or from :db) stage)
    (try (let [c (pdb/connect (assoc cfg :branch stage))]
           (try (d/transact c {:tx-data tx}) (finally (d/release c))))
         (try (d/branch! conn stage b)
              (catch Throwable t (when-not (= :branch-already-exists (:type (ex-data t))) (throw t))))
         (finally (d/delete-branch! conn stage)))))

(defn program-db
  "The db holding :seon.config.source/dirs of the checkout at root as program facts (seon.source: analyzed,
  not run), kept in the LMDB store at store on a branch named by root's git tree id (seon.fx/
  tree-id): the same tree reads its branch; a tree never seen is branched from the kept tree of
  the fewest changed program files (one in root's repository) and brought current by
  seon.source/refresh-tx; a store keeping no tree imports the whole program (seon.source/
  import-tx). A tree's branch exists only once its import committed: its facts are made before
  the branch (a file that does not read throws first), and a transaction refused takes the
  branch it made with it, so no later plan reads a partial program as the tree's (task
  seon.proofs/a-failed-import-leaves-no-branch: one selected 0 tests and said OK). Every text is
  read from the tree's git objects (tree-files). Two runs on one new tree both import it and the first to
  finish keeps the branch (keep-tree!). A store past
  max-bytes on disk (an LMDB file never shrinks) is removed when a tree must be imported into it, and starts again from
  that tree; a tree it keeps is read whatever its size. Only the plan passes a bound (tests-of:
  :seon.config.proofs/program-store-max-bytes), before anything of its run reads the store: the health step importing
  the base tree beside the shards removed the store, and the head's branch the plan had kept with it, so every shard's
  first program reader imported the program inside its own budget. Any other caller (nil) imports beside what is kept. The branch's connection stays open for
  the process (the db value reads the store); the store's main connection is released. tree: the
  checkout's tree-id when the caller holds it (the plan computes it once)."
  ([root store] (program-db root store (fx/tree-id root)))
  ([root store tree] (program-db root store tree nil))
  ([root store tree max-bytes]
  (let [b (tree-branch tree)
        ;; the store is output keyed by the program's attributes alone (seon.test-store/program-schema-digest), and its open checks
        ;; every attribute's value type: one the open refuses (another attribute's type changed since it was made, a retired name)
        ;; is made again, never a refusal every checkout must clear by hand (2026-11-10: two message attributes made refs)
        open #(try (pdb/open! :lmdb store 0 :db)
                   (catch clojure.lang.ExceptionInfo e
                     (if (#{:seon.db/types-differ :seon.db/name-retired} (:seon.error/type (ex-data e)))
                       ;; its env, opened before the refusal, closed first (seon.lib/remove-tree! closes it too: docs/failure-points.md#lmdb-locks)
                       (do (klmdb/close-env! store) (seon.lib/remove-tree! store) (pdb/open! :lmdb store 0 :db))
                       (throw e))))
        ;; the bound is the write's: a tree the store keeps is read whatever the store's size (a shard read the plan's tree
        ;; from a store the plan's import had taken past it, removed it, and its first program reader imported the tree
        ;; again inside its own tick budget); only a tree to import starts a store past it again
        ;; the tree's branch asked for alone (seon.db/branch?, one read); the store's branches listed only for a tree to
        ;; import (keep-tree! picks the nearest kept tree), since d/branches reads every roster entry (35k ticks, 102 KB a branch)
        {:keys [conn cfg]} (let [o (open)]
                             (if (or (nil? max-bytes) (pdb/branch? (:conn o) b) (<= (.length (io/file store "data.mdb")) max-bytes))
                               o
                               (do (d/release (:conn o)) (seon.lib/remove-tree! store) (open))))]
    (when-not (pdb/branch? conn b)
      (keep-tree! conn cfg root tree b (d/branches conn)))
    (d/release conn)
    @(pdb/connect (assoc cfg :branch b)))))

(defn- builder-id
  "The id of the code that builds a reach graph on db: the digest of the :form/id of every var of
  seon.program (an index range of the var names, one entity read each), so a change to any of them
  and the config keys its rows and flags read (pin-namespaces, process-vars, binary-vars), makes a stored graph (reach-graph-of) another builder's."
  [db]
  (lib/digest (str (str/join " " (sort (for [dt (d/index-range db {:attrid :var/name :start (symbol "seon.program" "") :end (symbol "seon.program" "\uffff")})
                                               :let [f (:var/form (d/entity db (:e dt)))] :when f]
                                           (:form/id f))))
                     ;; and the config the graph's rows and flags are read under
                     (pr-str (map #(config/value %) [:seon.config.source/pin-namespaces :seon.config.source/process-vars :seon.config.source/binary-vars])))))

(defn program
  "The program this process judges, as a db: in a process that holds a branch (an agent's test child, a fork of the
  agent: seon.agent/state's :db) that branch's db, which holds the program as facts already, so nothing is imported
  and the test judges the agent's change; otherwise (a bin/test shard) the checkout's tree as the plan kept it
  (program-db of seon.config/checkout in seon.test-store/kept-program-store). A test that walks the program's forms
  reads this; only a test of the import itself reads program-db. A test that composed program-db of the checkout
  itself imported the whole program inside an agent's prove, where no plan had kept the tree: 3.87 G ticks, cut at
  the test's 4.0 G budget, and it judged root's checkout, never the branch (the stray reds of 2026-11-10). The
  second arm goes when a session's tree is a branch of the store. docs/merging.md#a-merge-runs-what-the-change-reaches."
  [] (or (:db @seon.agent/state) (program-db (config/checkout) (ts/kept-program-store))))

(defn reach-graph-of
  "The reach graph of db: the one stored in the program store at store (reach-graph.fasl, jfasl:
  a stored graph decodes in about a sixth of what building one costs, 23M ticks against 146M on
  Seon's program) brought to db by seon.program/reach-update, which re-folds only what differs,
  else built (seon.program/reach-graph); stored again when it moved. A stored graph is used
  only when the code that builds one (builder-id) and the system's data-refs are the ones it was
  built under; one that does not read is built over: it is regenerable output of the kept store."
  [db data-refs store]
  (let [f (io/file store "reach-graph.fasl")
        id (builder-id db)
        stored (when (.isFile f)
                 (let [{:keys [builder graph]} (fasl/deserialize {} (java.nio.file.Files/readAllBytes (.toPath f)))]
                   (when (and (= id builder) (= data-refs (:data-refs graph))) graph)))
        g (if stored (program/reach-update stored db) (program/reach-graph db data-refs))]
    (when (or (nil? stored) (not= (:live g) (:live stored)))
      (let [tmp (io/file store (str "reach-graph.fasl." (random-uuid)))]
        (java.nio.file.Files/write (.toPath tmp) ^bytes (fasl/serialize {:builder id :graph (dissoc g :affected)})
                                   (make-array java.nio.file.OpenOption 0))
        (java.nio.file.Files/move (.toPath tmp) (.toPath f) (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))
    g))

(defn- reads-index-of
  "The reads index of the checkout at root on tree (seon.program/reads-index over graph g and inputs in), kept in the program
  store at store beside the reach graph (reads-index.fasl, by tree): a plan on a tree already indexed reads it, never makes
  it again (Tests: plans remade every digest on each ask, 1.4-12.6 s). Regenerable output of the kept store."
  [root store tree g in]
  (let [f (io/file store "reads-index.fasl")
        kept (when (.isFile f) (let [{t :tree rd :rd} (fasl/deserialize {} (java.nio.file.Files/readAllBytes (.toPath f)))] (when (= t tree) rd)))]
    (or kept
        (let [rd (seon.program/reads-index g in (fx/blob-ids root tree))
              tmp (io/file store (str "reads-index.fasl." (random-uuid)))]
          (java.nio.file.Files/write (.toPath tmp) ^bytes (fasl/serialize {:tree tree :rd rd}) (make-array java.nio.file.OpenOption 0))
          (java.nio.file.Files/move (.toPath tmp) (.toPath f) (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          rd))))

(defn- tests-of
  "{test digest} of the named test namespaces (or every one) on the checkout at root, the
  ones among them that reach a process of their own (:process, seon.program/graph-index), its
  program db (program-db, kept in store), inputs and reach graph (seon.program/reach-graph:
  walked once, the check steps' index is read from it too), and :no-test, the namespaces of nss
  of which the program db holds no test (a namespace with no deftest, or a program db missing
  it: never an empty selection that says OK)."
  [root names store]
  (let [vars (set (keep #(when (str/includes? % "/") (symbol %)) names))
        nss (set (if (seq names) (map #(symbol (first (str/split % #"/"))) names) (test-namespaces root)))
        tree (fx/tree-id root)
        ;; the plan alone bounds the store, at its own import, before anything of this run reads it
        db (program-db root store tree (config/value :seon.config.proofs/program-store-max-bytes))
        in (fx/inputs root config/config (config/checkout) tree)
        g (reach-graph-of db (:data-refs in) store)
        ;; the process store a process test's root takes is indexed by this tree's code: its key is an input of theirs
        in (seon.program/with-index-code g in)
        ;; a process test's config values are its root's, whoever plans (seon.config/test-system-config)
        in (assoc in :test-system-config (fx/config-input config/test-system-config (config/checkout)))
        all (into {} (filter #(nss (symbol (namespace (key %))))) (program/graph-index g in nil))
        ;; a name of the form ns/test selects that one deftest (bin/test NS/TEST); a bare ns selects its tests
        whole (if (seq names) (set (keep #(when-not (str/includes? % "/") (symbol %)) names)) nss)
        idx (into {} (filter #(or (whole (symbol (namespace (key %)))) (vars (key %)))) all)]
    {:graph g :inputs in
     ;; the tests that reach each type of fixture (a var marked ^:seon.test/fixture, the test store, or
     ;; ^:seon.test/shard-fixture, the shared root), as their reach says: {type #{test}} (warm-by decides what a shard warms)
     :fixture-tests (into {} (for [t [:seon.test/fixture :seon.test/shard-fixture]]
                               [t (into #{} (mapcat #(program/tests-reaching g %))
                                        (d/q '[:find [?n ...] :in $ ?t :where [?v :var/meta ?t] [?v :var/name ?n]] db t))]))
     :rd (delay (reads-index-of root store tree g in))
     ;; whether the binary target/seon is (its build record, target/seon.program) runs this tree's program as a build of it would:
     ;; nil, or what differs that it bakes (seon.system/binary-current, the one rule; docs/source.md#the-binary-records-its-program)
     :binary-current (seon.system/binary-current db (seon.program/binary-program) tree {:graph g :scope :all})
     :no-test (vec (sort-by str (concat (remove (set (map (comp symbol namespace key) all)) nss)
                                        (remove all vars))))
     :digests (update-vals idx :digest)
     ;; each test's reads besides code (config keys, the tree, a root, the binary: graph-index's :reads), what its recorded
     ;; :reads holds beside the functions it called, so a reuse is sound until those reads are recorded as they happen
     :static-reads (into {} (keep (fn [[t v]] (when (seq (:reads v)) [t (:reads v)]))) idx)
     ;; recorded reads (seon.proofs/read-digests): the tests' newest greens that recorded what they read, and, when any did,
     ;; the digest of everything a run can read on this tree; an unreadable record only makes its test run. Not a test that
     ;; reaches a process of its own: its run records what its own process called, never what the agent or root it drives
     ;; ran for it (an eval's code is a string no reach shows), so it is reused at its digest only
     :held (let [h (fx/held-reads (proofs/reads-reusable idx))] (if (:seon.error/type h) {} h))
     ;; the proofs of this tree that pass by a waiver (an :overrun in the form), counted on the db the plan reads
     :waived (count (problems/waived-proofs db {}))
     :process (set (keep (fn [[t v]] (when (:process? v) t)) idx))
     :binary (set (keep (fn [[t v]] (when (:binary? v) t)) idx))}))

(defn- refused-names
  "Why the arguments after --plan WHAT OUT [--all] cannot select tests, or nil: a flag this runner
  does not know (bin/test --reach ran nothing and passed: reach is the default since bin/prove),
  or a name that is no test namespace of test/ (NS, or NS/TEST: its namespace is checked here, the
  deftest by tests-of, which answers it in :no-test)."
  [names]
  (let [flags (filter #(str/starts-with? % "--") names)
        nss (set (map str (test-namespaces (source-tree))))
        unknown (remove #(or (str/starts-with? % "--") (nss (first (str/split % #"/")))) names)]
    (cond (seq flags) (str "no flag " (str/join " " flags) " (bin/test [--all] [NS ...]: --all, the one flag, comes first)")
          (seq unknown) (str "no test namespace " (str/join " " unknown) " in test/"))))

(defn- write!
  "text to path through the effect owner's file write (seon.fx/write-file); its declared
  error is thrown with its data, so the run ends red naming the path, never as if written."
  [path text]
  (let [r (fx/write-file {:req {:path path :text text}})]
    (when (:seon.error/type r) (throw (ex-info (str "write failed: " (:seon.error/message r)) r)))
    r))

(defn read-results
  "The verdicts cached for cand ({key digest}, seon.fx/stored-results): {digest result}, or the typed
  :seon.proofs/unread naming the file that could not be read, never {} for a read that failed: zero
  greens is an answer, a failed read is not."
  [cand]
  (let [r (fx/stored-results cand)]
    (if (contract/error? r)
      (contract/error :seon.proofs/unread 'test-runner/read-results (str "verdicts not read: " (:seon.error/message r)) {:why (:seon.error/type r)})
      r)))

(defn record-run!
  "The verdicts ran ({key result}) of run ({:id :tree}) and their failures cached (seon.fx/write-verdicts!).
  One path: cached, or the run is red. Answers the unrecorded ([{:key :message}]: a file the cache would
  not take), empty when cached."
  [run ran failures]
  (let [r (fx/write-verdicts! run ran failures)]
    (if (contract/error? r) [{:key :all :message (:seon.error/message r)}] [])))

(defn- measured-ms
  "{test ms} of tests: the median of the ms each one's cached verdicts measured (seon.fx/typical-ms): what the deal
  orders by, never what a test proves."
  [tests]
  (if (seq tests) (fx/typical-ms (vec tests)) {}))

(defn deal-mg
  "The generative check's keys (mg, {key digest}) added to shards sh (shards' answer: vectors of tests), each longest
  first (by its median measured ms, ms; one never measured counts as the mean of those measured, else 1) to the
  shard with the least work so far (tests' ms from tms): the contracts ride in shards that already run, so a
  change that reaches 5 costs 5 contracts' work and no process start. With no test shard, one: every key in a
  process of its own. Answers [[tests {key digest}] ...], the shards' contents."
  [sh mg tms ms]
  (let [mean (if (seq ms) (max 1 (quot (reduce + (vals ms)) (count ms))) 1)
        cost #(get ms % mean)
        base (if (seq sh) (mapv (fn [ts] [ts {}]) sh) (if (seq mg) [[[] {}]] []))
        load (fn [[ts m]] (+ (reduce + (map #(get tms % 1) ts)) (reduce + (map cost (keys m)))))]
    (reduce (fn [acc k] (let [i (apply min-key #(load (acc %)) (range (count acc)))] (update-in acc [i 1] assoc k (mg k))))
            base (sort-by (juxt #(- (cost %)) identity) (keys mg)))))

(defn- warm-by
  "What each fixture type's warm is for, by the tests whose shards warm it ({type #{test}}): the test store for every test that
  reaches it; the shared root only for a test that reaches it and the binary (binary, the plan's :binary), the one rule by
  which the plan makes the process store a shared root starts on (prove!'s out/process-store). A test that reaches the shared
  root's var but not the binary starts no root, so its shard warms none (a docs-only land's doc-tests, 2026-11-13: their shard
  warmed a root whose store the plan never made, and died before its first test). See docs/merging.md#the-test-service."
  [fixture-tests binary]
  {:seon.test/fixture (get fixture-tests :seon.test/fixture #{})
   :seon.test/shard-fixture (into #{} (filter (set binary)) (get fixture-tests :seon.test/shard-fixture))})

(defn- write-shards!
  "tests (var symbols) dealt into at most jobs shards (shards: :seon.config.proofs/jobs for a full run or a merge's
  verification, what their work is worth for what a change reaches: seon.proofs/worth-shards), the generative check's keys mg
  ({key digest}) dealt among them (deal-mg), each written as out/shard-<i>.edn ({:tests [...] :mg {key digest}
  :digests {test digest} :tree t :rerun #{test}}, t the planned tree's id, rerun the tests that run whatever the cache
  holds: every one of a full run, and the reuse audit's) for bin/test to run, one process each (bin/test
  removes the last run's first), and each measured test's stall bound and end bound as out/stall-bounds (\"<test> <stall ms>
  <end ms>\" lines, for bin/stalls). Answers the shard count."
  [out tests digests tree mg jobs ms rerun warm]
  (let [sh (proofs/shards tests jobs ms)
        mg-ms (measured-ms (keys mg))
        floor (config/value :seon.config.agent/stall-ms) k (config/value :seon.config.proofs/stall-factor)
        e (config/value :seon.config.check/step-end-factor)
        dealt (deal-mg sh mg ms mg-ms)]
    (doseq [[i [ts m]] (map-indexed vector dealt)]
      (write! (str out "/shard-" i ".edn") (sedn/encode {:tests ts :mg m :digests (select-keys digests ts) :tree tree :rerun (into (sorted-set) (filter (set ts)) rerun)
                                                          :warm (into #{} (keep (fn [[t ts']] (when (some ts' ts) t))) warm)})))
    ;; each measured test's stall bound, k times its own last run, and its end bound, the step-end factor times the
    ;; same run floored at stall-ms, past which bin/stalls captures its evidence and ends its shard (OVERRAN:
    ;; docs/clocks.md#time-decides-the-owners-exceptions); a test with no record takes the floors, out/stall-ms
    (write! (str out "/stall-bounds") (apply str (for [[t m] (sort-by key ms)] (str t " " (max floor (* k m)) " " (* e (max floor m)) "\n"))))
    (count dealt)))

(defn- write-reads!
  "The run's reads, for its shards to key each green by what it read (seon.proofs/reads-key): out/reads-index.fasl, the digest
  of every read on the planned tree (plan's :rd, seon.program/reads-index, kept per tree in the program store), and
  out/static-reads.edn, each test's reads besides code (plan's :static-reads). Written only when tests run. See
  docs/source.md#tests-run-by-reach."
  [out plan run-tests]
  (when (seq run-tests)
    (java.nio.file.Files/write (.toPath (io/file out "reads-index.fasl")) ^bytes (fasl/serialize @(:rd plan)) (make-array java.nio.file.OpenOption 0))
    (write! (str out "/static-reads.edn") (sedn/encode (select-keys (:static-reads plan) run-tests)))))

(defn- shard-reads
  "{:rd :static} of the run whose shard file is file (write-reads!: beside it), or nil when the run wrote none."
  [file]
  (let [d (.getParentFile (io/file file)) f (io/file d "reads-index.fasl") st (io/file d "static-reads.edn")]
    (when (.isFile f)
      {:rd (fasl/deserialize {} (java.nio.file.Files/readAllBytes (.toPath f)))
       :static (if (.isFile st) (edn/read-string (slurp st)) {})})))

(defn- shard!
  "One shard bin/test runs (out/shard-<i>.edn): its namespaces required, its tests run
  (seon.proofs/run-tests!), each test named as it starts (started) and as it ends (ended), each
  red one printed (RED). A test another run stored green at its digest since the plan is not run (looked up just before
  it would start: reused), unless the plan says it runs whatever the cache holds (:rerun: a full run's, the reuse audit's). A green is stored the moment its test ends (seon.fx/write-verdicts!, with its contracted
  functions' cost), so every run beside this one reuses it at once, and a stopped shard keeps what ended; its reds and
  the generative keys are stored at the end, in one write. Unless the tree is not the plan's before and after the run
  (an edit during the run: what ran is not what the digests name): then its greens are taken back
  (seon.fx/forget-greens!) and nothing else is stored, and the run stays as reported. Then SHARD n green m red. Answers the exit status."
  [file]
  (let [{:keys [tests digests tree mg rerun warm]} (edn/read-string (slurp file))
        ;; what each green read, keyed by the run's reads index (write-reads!): the functions it called, itself, its static reads
        {:keys [rd static]} (shard-reads file)
        with-reads (fn [x] (cond-> x rd (assoc :reads (proofs/reads-key rd {:fns (conj (into #{} (map :var) (:fns x)) (:test x))
                                                                              :keys (get static (:test x))}))))
        t0 (profile/now-ns)]
    (doseq [n (distinct (map (comp symbol namespace) tests))] (require n))
    ;; armed as root arms them at boot, so a proof counts the same contracted calls here as in an agent's test child (docs/cost.md#counting-calls)
    (contract/instrument! contract/boot-nss)
    ;; the fixtures its tests reach made before the first of them runs, so none pays the test store's seed (~65M ticks)
    ;; inside its own budget; a shard of none that reaches one makes none
    (when (seq warm) (proofs/warm-fixtures! warm))
    (let [;; one writer for every verdict this shard stores
          run {:id (str "bin/test " tree " " (random-uuid)) :tree tree}
          ;; the greens this shard wrote as each ended (the cache's, so a run beside it reuses them at once), and the ones it reused
          written (volatile! {}) reused (volatile! #{})
          ;; each test under the one bound an agent's run has (seon.proofs/run-tests!): one verdict wherever it runs
          reports (volatile! [])
          ran (proofs/run-tests! tests digests
                                 (fn [x] (cond (:seon.test/started x) (println "started" (:seon.test/started x))
                                               (:seon.test/output x) (print (:seon.test/output x))
                                               (:seon.test/report x) (let [m (:seon.test/report x)]
                                                                        ;; a failing assertion, as the results keep it (seon.proofs/failures): the agent's one reading of a report
                                                                        (when (#{:fail :error} (:type m))
                                                                          (vswap! reports conj (seon.agent/test-report m (str "#'" (:test x))))))
                                               ;; a test's end, so bin/stalls never names one that finished
                                               ;; and its cost, the work it did (a green test's is read nowhere else)
                                               (:reused x) (do (println "reused" (:test x) "(green at its digest since the plan: another run stored it)")
                                                              (vswap! reused conj (:test x)))
                                               :else (do (println "ended" (:test x) (str (:ticks x) " ticks"))
                                                         ;; a green is stored the moment it ends, so every run beside this one reuses it
                                                         (when (proofs/green? x)
                                                           (let [own (filterv #(= (:var %) (str "#'" (:test x))) @reports)
                                                                 c ((proofs/costs {:reports own}) (:test x))]
                                                             (doseq [u (record-run! run {(:test x) (with-reads (cond-> x c (assoc :cost c)))} {})]
                                                               (println "UNRECORDED" (:key u) (:message u)))
                                                             (vswap! written assoc (:test x) (:digest x))))
                                                         (when-not (proofs/green? x) (println "RED" (:test x) (pr-str (select-keys x [:pass :fail :error :message]))))))
                                   (flush))
                                 (config/value :seon.config.agent/test-ticks)
                                 ;; a green another run stored since the plan, looked up just before the test would start
                                 ;; (not one the plan says runs whatever the cache holds: a full run's, the reuse audit's)
                                 (fn [t dg] (when-not (contains? rerun t)
                                              (let [r (get (fx/stored-results {t dg}) dg)] (when (and (map? r) (proofs/green? r)) r)))))
          ;; the tree the tests read is the plan's, still, after the run: an edit during the run (a test
          ;; reading files reads it) is not what the digests name. The plan's tree is the before (one
          ;; tree-id per shard, not two)
          same? (= tree (fx/tree-id (seon.config/checkout)))
          ;; an edit during the run: the greens stored as they ended name code they may not have run, so they are taken back
          ;; (a green is a cache; a red is never removed, and none was written before this line)
          _ (when-not same? (fx/forget-greens! @written))
          ;; the shard's reds, their failures and each test's function costs, cached (its greens were, as each ended)
          unstored (when same?
                     (let [cost-of (proofs/costs {:reports @reports})]
                       (record-run! run
                                    (into {} (for [[t r] ran :when (not (or (@written t) (@reused t)))] [t (cond-> r (cost-of t) (assoc :cost (cost-of t)))]))
                                    (proofs/failures {:reports @reports}))))
          ;; the generative check's keys of this shard (seon.proofs/mg-run), cached as steps at their digests; a red
          ;; one with its failure, so it reads as a red test does
          mgs (for [[k dg] (sort-by key mg)] (let [r (proofs/mg-run k dg)] (println (if (= :failed (:status r)) "RED" "ok") k (:ms r) "ms") r))
          mg-ran (into {} (for [r mgs] [(:key r) {:step (:key r) :digest (:digest r) :ms (:ms r) :pass (if (= :failed (:status r)) 0 1) :fail (if (= :failed (:status r)) 1 0) :error 0}]))
          mg-failures (into {} (for [r mgs :when (= :failed (:status r))]
                                 [(:key r) [{:type :fail :message (str (:key r) ": " (or (get-in r [:failure :problem]) "the generative check failed"))
                                             :actual (print/text (:failure r) (:part print/budgets))}]]))
          _ (doseq [[k fs] mg-failures] (println (:message (first fs)) (:actual (first fs))))
          unstored-mg (when (and same? (seq mg-ran))
                        (record-run! run mg-ran mg-failures))
          red (+ (count (remove proofs/green? (vals ran))) (count mg-failures))]
      (when-not same? (println "the tree changed since the plan: no result stored"))
      (doseq [u (concat unstored unstored-mg)] (println "UNRECORDED" (:key u) (:message u)))
      (println (str "SHARD " (+ (count ran) (count mg-ran)) " tests, " (- (+ (count ran) (count mg-ran)) red) " green (" (count @reused) " reused from another run), " red " red, in "
                    (quot (- (profile/now-ns) t0) 1000000) " ms"))
      (if (and (zero? red) (empty? unstored) (empty? unstored-mg)) 0 1))))

(defn- overran!
  "A shard bin/stalls ended (its log, file's .log, holds \"OVERRAN <test> <bound> ms ...\": the test ran past its end bound,
  docs/clocks.md#time-decides-the-owners-exceptions): each overran test is a bug, recorded as an error at its digest with the
  line as its failure (unless the tree is not the plan's, as shard! keeps) and printed RED (the owner, 2026-11-12: \"A test
  killed for running too long is a bug and it should be recorded as an error and fixed ... we shoudln't do nothing and not
  record anything\"); the shard's tests that never ended (no ended,
  reused or OVERRAN line) and its generative keys (which a shard runs last) written to file's -rest.edn, for bin/test to
  run in a shard of their own (nothing left, no file). The reds of tests that ended before are not stored, as a stopped
  shard's are not (shard!). Answers 1: the shard ran a red."
  [file]
  (let [{:keys [tests digests tree rerun mg] :as sh} (edn/read-string (slurp file))
        lines (str/split-lines (slurp (str/replace file #"\.edn$" ".log")))
        over (into (sorted-map) (keep #(when-let [[_ t] (re-matches #"OVERRAN (\S+) .*" %)] [(symbol t) %])) lines)
        done (into (set (keys over)) (keep #(when-let [[_ t] (re-matches #"(?:ended|reused) (\S+)(?: .*)?" %)] (symbol t))) lines)
        left (vec (remove done tests))
        same? (= tree (fx/tree-id (config/checkout)))
        unstored (when same?
                   (record-run! {:id (str "bin/test overran " tree " " (random-uuid)) :tree tree}
                                (into {} (for [t (keys over)] [t {:test t :digest (digests t) :pass 0 :fail 0 :error 1}]))
                                (into {} (for [[t l] over] [t [{:type :error :message l}]]))))]
    (doseq [[t l] over] (println "RED" t (pr-str {:pass 0 :fail 0 :error 1 :message l})))
    (when-not same? (println "the tree changed since the plan: no result stored"))
    (doseq [u unstored] (println "UNRECORDED" (:key u) (:message u)))
    (when (or (seq left) (seq mg))
      (write! (str/replace file #"\.edn$" "-rest.edn")
              (sedn/encode (assoc sh :tests left :digests (select-keys digests left) :rerun (into (sorted-set) (filter (set left)) rerun)))))
    1))

(defn plan-of
  "What bin/prove and bin/test run on the checkout at root (what: tests, checks or both): the
  program's reach read from the kept store at store (program-db), selected by seon.proofs/plan-of
  (the policy a root's service shares). Answers its plan, with :unread, the typed
  :seon.proofs/unread, when the results could not be read (read-results)."
  [root what all? names store]
  (let [unread (volatile! nil)
        reach (tests-of root names store)
        p (proofs/plan-of (assoc reach :slow-of fx/slow-tests) what all? names
                  (some->> (fx/base-commit root) (fx/tree-of root))
                  ;; the verdicts cached at the candidates' digests; a read that failed is the plan's
                  ;; :unread (prove! stops on it), never zero greens
                  (fn [cand]
                    (let [r (read-results cand)]
                      (if (contract/error? r) (do (vreset! unread r) {}) r))))]
    (cond-> (assoc p :binary-current (:binary-current reach) :waived (:waived reach) :static-reads (:static-reads reach) :rd (:rd reach)
                   :fixture-tests (:fixture-tests reach))
      @unread (assoc :unread @unread))))

(defn build-text
  "out/build's text, what bin/test builds target/seon for: when a selected key reaches the binary (bin-keys) and the binary is
  not current for the tree (binary-current's answer, seon.system/binary-current), that answer; else empty, and nothing is
  built. docs/source.md#the-binary-records-its-program."
  [bin-keys binary-current]
  (if (and (seq bin-keys) binary-current) (str (pr-str binary-current) "\n") ""))

(defn- prove!
  "The planning part of bin/prove and bin/test (what is tests, checks or both): plan-of's tests
  are dealt into out's shards (write-shards!), and its bin/check steps are written to out/steps
  (names, one line) and out/steps.edn ({:tree :digests {step digest}}) for bin/prove to run;
  out/tests.edn ({:tree :digests {test digest}}) names each candidate test's digest;
  out/binary names the selected keys that reach target/seon, out/steps-binary the steps among
  them (both empty: nothing to build, nothing to seed), out/stall-ms the stall bound and the end bound of a test with no record, which bin/test reads. Answers the exit
  status: 1 when a check step names a function the program does not define."
  [what all? out names]
  (if-let [bad (refused-names names)]
    (do (println (str "TEST FAILED: " bad)) 1)
  (let [t0 (profile/now-ns)
        ;; the selection's time is the plan's: program db, reach graph, digests and the results read
        plan (plan-of (source-tree) what all? names (ts/shared-program-store! (config/value :seon.config.proofs/program-store) @ts/kept-program-digest))]
  (if-let [u (:unread plan)]
    ;; a read that failed is not zero greens: nothing runs, and the run says why
    (do (println (str "TEST FAILED: " (:seon.error/message u)))
        1)
  (let [dir (str "the verdict cache " (config/value :seon.config.proofs/results-dir))
        tests? (not= what "checks") checks? (not= what "tests")
        {:keys [run-tests run-steps reused cand left slow digests steps missing inputs binary no-test process flaky waived binary-current audit fixture-tests]} plan
        ;; the reuse audit's tests run beside the plan's (seon.proofs/plan-of's :audit), never a slow one: a slow test runs
        ;; last, once all else is green (docs/merging.md#a-slow-test-runs-last)
        audit (let [ms (measured-ms audit) bound (config/value :seon.config.proofs/slow-test-ms)]
                (filterv #(< (get ms % 0) bound) audit))
        run-tests (into run-tests audit)
        ;; a merge runs these last, once all else is green (bin/prove): out/slow, names, one line
        ;; the proofs of the tree that pass by a waiver (an :overrun in the form: seon.problems/waived-proofs), for bin/prove's last lines
        _ (write! (str out "/waived") (str waived "\n"))
        _ (write! (str out "/slow") (str (str/join " " (sort (map str (keys slow)))) (when (seq slow) "\n")))
        _ (when (seq slow)
            (println (str "prove: " (count slow) " slow tests left for last (bin/prove runs them once all else is green; run one by name: bin/test NS/TEST, all: bin/test --all): "
                          (str/join " " (map (fn [[t ms]] (str t " " ms "ms")) (sort-by (comp - val) slow))))))
        ;; a test namespace of the tree that its program db holds no test of: nothing it holds can
        ;; be selected, so a run would say OK of tests it never saw
        no-test (when tests? (seq no-test))
        _ (when no-test
            (println (str "prove: the program db holds no test of " (str/join " " no-test)
                          ": a namespace with no deftest, a name (NS/TEST) that is no deftest, or a program db that misses it (a store made before"
                          " seon.proofs/a-failed-import-leaves-no-branch: move "
                          (config/value :seon.config.proofs/program-store) "-* aside, and the tree imports)")))
        ;; the generative check's keys (one per function, and the schema registry) run in the shards beside the
        ;; tests (shard!, seon.proofs/mg-run): no binary, no process of their own
        {mg-run true run-steps false} (group-by proofs/mg-key? run-steps)
        mg-run (vec mg-run) run-steps (vec run-steps)
        ;; an incremental run (what a change reaches) is one process while four starts would cost more than the few
        ;; seconds of work they split (the owner, 2026-10-30); past that it shards, as a gate of 206 tests took 751 s on
        ;; one; the full run and a merge's verification shard
        ;; each test's ms, measured or its class's configured estimate (never 1 ms: an unmeasured run dealt itself one shard)
        ms (proofs/estimated-ms run-tests (measured-ms run-tests) (into #{} (filter qualified-symbol?) process))  ; process holds the check steps' names too (strings)
        ;; as many shards as free memory holds, at most the configured jobs (never fewer for the load average: bin/test)
        avail (long (:available-mb (seon.lib/sysmem)))
        jobs (proofs/memory-jobs (config/value :seon.config.proofs/jobs) avail
                                 (config/value :seon.config.proofs/shard-mb) (config/value :seon.config.proofs/free-floor-mb))
        _ (write! (str out "/jobs") (str jobs))
        _ (when (< jobs (config/value :seon.config.proofs/jobs))
            (println (str "prove: " avail " MB free: " jobs " of " (config/value :seon.config.proofs/jobs) " shards at most ("
                          (config/value :seon.config.proofs/shard-mb) " MB a shard above " (config/value :seon.config.proofs/free-floor-mb) " MB kept free)")))
        n (write-shards! out (if no-test [] run-tests) digests (:tree inputs) (select-keys steps mg-run)
                         (if all? jobs (proofs/worth-shards run-tests jobs ms (config/value :seon.config.proofs/shard-min-ms)))
                         ms
                         ;; run whatever the cache holds: every test of a full run (bin/test --all, which a rerun by name asks
                         ;; for too), and the reuse audit's greens, whose rerun is its point
                         (if (true? all?) run-tests audit)
                         (warm-by fixture-tests binary))
        _ (write-reads! out plan (if no-test [] run-tests))
        ;; what runs and reaches the binary (a test that reaches binary-sh, a step that runs a seon.check
        ;; mode): bin/test builds target/seon only when this is not empty, bin/check seeds only for the steps
        step-bin (filterv proofs/step-needs-binary? run-steps)
        bin-keys (into (vec (filter binary run-tests)) step-bin)
        _ (write! (str out "/binary") (str (str/join " " bin-keys) (when (seq bin-keys) "\n")))
        ;; out/build: what bin/test builds target/seon for, empty when the binary is current for this tree (seon.system/binary-current):
        ;; a change that reaches nothing root runs from its binary builds nothing (rule (a), docs/source.md#the-binary-records-its-program)
        _ (write! (str out "/build") (build-text bin-keys binary-current))
        ;; this tree's inputs beside the binary (target/seon.inputs, keyed by their :tree): what a root booting on this tree with a
        ;; binary current for it by reach hands its agents (seon.system/booted-source), read as a file: root runs no git
        _ (write! (str (config/checkout) "/target/seon.inputs") (sedn/encode inputs))
        _ (when (and (seq bin-keys) (not binary-current))
            (println "prove: target/seon is current for this tree by reach (seon.system/binary-current): no build"))
        ;; the stall bound bin/test names a quiet test by (:seon.config.agent/stall-ms), and the end bound of a test with no
        ;; record (the step-end factor times it): "<stall ms> <end ms>"
        _ (write! (str out "/stall-ms") (str (config/value :seon.config.agent/stall-ms) " "
                                             (* (config/value :seon.config.check/step-end-factor) (config/value :seon.config.agent/stall-ms)) "\n"))
        ;; the process tests' store, made here before any shard starts (shards' roots that each made
        ;; it raced: seon.test-store/process-store), for this schema (seon.db/kept-store!)
        _ (when (seq (filter binary run-tests)) (write! (str out "/process-store") (str (ts/make-process-store! (.getCanonicalPath (io/file (config/checkout)))) "\n")))
        _ (write! (str out "/steps-binary") (str (str/join " " step-bin) (when (seq step-bin) "\n")))
        ;; each candidate test's digest: where its verdict is read (seon.fx/test-results, the merge gate)
        _ (when tests? (write! (str out "/tests.edn") (sedn/encode {:tree (:tree inputs) :digests (select-keys digests (filter symbol? (keys cand)))})))]
    (when left
      (println (str "prove: " (count left) " tests and steps that reach a process left out (run them by name:"
                    " bin/test NS, bin/check MODE, or the full run): " (str/join " " (map str left)))))
    (when (= :merge all?)
      (let [ran (count (filter (set process) (concat run-tests run-steps)))
            late (count (filter (set process) (keys slow)))]
        (println (str "prove: a merge's verification: " (+ ran late) " keys that reach a process run (no green at their digest), 0 left out: "
                      ran " in this plan, " late " the slow ones run last (the process plan after it)"))))
    ;; a key green and red at one digest: red, never run again until a change moves its digest;
    ;; out/flaky names each, and bin/test ends red on it after its shards ran
    ;; the reuse audit (seon.proofs/plan-of): reused greens run again; out/audit names them, and bin/test calls a red among
    ;; them a reuse mismatch: a digest that missed an input
    (write! (str out "/audit") (apply str (map #(str % "\n") audit)))
    (when (seq audit)
      (println (str "prove: reuse audit: " (count audit) " reused greens run again (chosen by the tree): " (str/join " " (map str audit)))))
    (let [flaky (for [[k r] flaky]
                  (str "FLAKY " k " " (:digest r) ": green and red at this digest, red until a change moves it (seon.proofs/flaky)"))]
      (write! (str out "/flaky") (apply str (map #(str % "\n") flaky)))
      (run! println flaky))
    (println (str "prove: " (count cand) " keys (" (if tests? (count digests) 0) " tests, " (count (remove proofs/mg-key? (keys steps))) " check steps, " (count (filter proofs/mg-key? (keys steps))) " contracts), "
                  (count (remove (comp :flaky val) reused)) " green at their digest (reused), " (count flaky) " flaky, " (count run-tests) " tests to run in " (if (or (seq run-tests) (seq mg-run)) n 0)
                  " shards, " (count run-steps) " steps and " (count mg-run) " contracts to run; selected in " (quot (- (profile/now-ns) t0) 1000000) " ms; results " dir))
    (when checks?
      (write! (str out "/steps") (str (str/join " " run-steps) (when (seq run-steps) "\n")))
      (write! (str out "/steps.edn") (sedn/encode {:tree (:tree inputs) :digests (select-keys steps run-steps)}))
      ;; every step's bound, past which bin/check captures its evidence and ends it red (STEP-OVERRAN): seon.proofs/step-bounds,
      ;; the one rule (a step never measured takes the largest measured median; docs/clocks.md#time-decides-the-owners-exceptions)
      (let [steps (vec (remove proofs/mg-key? run-steps))]
        (write! (str out "/step-bounds") (apply str (for [[st b] (sort-by key (proofs/step-bounds steps (measured-ms (keys proofs/check-steps))))]
                                                      (str st " " b "\n")))))
      (when (seq run-steps) (println "prove: check steps to run:" (str/join " " run-steps)
                                     ))
      (when (seq missing)
        (println "prove: check steps naming a function the program does not define (seon.proofs/check-steps):" (str/join " " missing))))
    (if (or (seq missing) no-test)
      1
      (do (when (and (empty? run-tests) (empty? run-steps))
            (println (str "prove: nothing to run: " (count reused) " reused green at their digest")))
          0)))))))

(defn- store-steps!
  "bin/prove's last part: each step of out/steps.edn that bin/check named green in out/green
  (check-<step>, space separated) cached green at its digest (seon.fx/write-verdicts!),
  unless the step ran on a binary (seon.proofs/step-needs-binary?) not current for the plan's tree (seon.system/binary-current,
  the rule the plan's build asked: an edit between the plan and the build). A red one is not
  stored, so it runs again. Answers the exit status: 1 when a step ran red or was not stored.
  See docs/source.md#the-binary-records-its-program."
  [out]
  (let [{:keys [tree digests]} (edn/read-string (slurp (str out "/steps.edn")))
        f (io/file out "green")
        green (set (when (.isFile f) (map #(str/replace % #"^check-" "") (str/split (str/trim (slurp f)) #"\s+"))))
        ;; a step bin/check ended past its bound is one of its reds (STEP-OVERRAN, the owner, 2026-11-12), as any step it failed
        red (sort (remove green (keys digests)))
        ;; a step that runs a seon.check mode ran on target/seon: kept only if that binary is current for the plan's tree, the
        ;; one rule the build asked (an empty out/build: current; the plan kept its answer beside the builds, so this reads it);
        ;; laws and the lmdb steps ran no mode, so no binary is theirs to judge
        current? (delay (nil? (seon.system/binary-current (program) (seon.program/binary-program) tree {:scope :all})))
        keep? (fn [k] (and (green k) (or (not (proofs/step-needs-binary? k)) @current?)))
        ;; each step's wall ms as bin/check measured it (out/step-ms, "<step> <ms>" a line; a kill/reopen pair's two phases
        ;; summed under their step), kept on its verdict: its bound's measure (out/step-bounds) and the deal's
        step-ms (let [f (io/file out "step-ms")]
                  (reduce (fn [m [nm ms]] (update m (str/replace nm #"-(write|read)$" "") (fnil + 0) (parse-long ms))) {}
                          (when (.isFile f) (keep #(next (re-matches #"(\S+) (\d+)" %)) (str/split-lines (slurp f))))))
        unstored (do (when (and (some #(and (green %) (proofs/step-needs-binary? %)) (keys digests)) (not @current?))
                       (println "prove: the binary is not current for the plan's tree (seon.system/binary-current): no step that ran on it stored"))
                     (record-run! {:id (str "bin/prove steps " tree " " (random-uuid)) :tree tree}
                                  (into {} (for [[k dg] digests :when (keep? k)] [k (cond-> {:step k :digest dg :pass 1 :fail 0 :error 0} (step-ms k) (assoc :ms (step-ms k)))])) {}))]
    (doseq [u unstored] (println "UNRECORDED" (:key u) (:message u)))
    (println (str "prove: " (count (filter green (keys digests))) " check steps green, " (count red) " red"
                  (when (seq red) (str ": " (str/join " " red)))))
    (if (and (empty? red) (empty? unstored)) 0 1)))

(defn branch-health
  "A branch's health (root's ruling, 2026-10-23): what head caused that base had not, the done rule's
  own reading (seon.problems/caused-split), judged on classes (every class caused-split reads when nil).
  {:caused [{:class :message}] :attention [{:class :message}] :checks {:no-<class> bool}}: a
  problem base already had is not the branch's, and is root's to show (its context and page)."
  [base head classes]
  (let [{:keys [rows]} (problems/caused-split base head)
        attention? (:seon.problems/attention (contract/properties :seon.problems/class))
        derived problems/caused-classes
        ;; a class asked for that no verdict judges (not read by caused-split, or attention: it never fails a branch) is refused, never a green
        _ (when-let [bad (seq (remove #(and (derived %) (not (attention? %))) classes))]
            (throw (ex-info (str "health judges the derived classes that fail a branch, not " (str/join ", " (map name bad))) {})))
        cs (or (seq classes) (sort derived))
        made (filterv (comp (set cs) :class) rows)]
    {:caused (filterv (complement (comp attention? :class)) made)
     :attention (filterv (comp attention? :class) made)
     :checks (into {} (for [c cs :when (not (attention? c))]
                        [(keyword (str "no-" (name c))) (not-any? #(= c (:class %)) made)]))}))

(defn- tasks-dir
  "Where the tree keeps its task files, relative to it (:seon.config.source/tasks-dir: the tree's layout, never this process's
  own task dir, :seon.config.root/tasks-dir, which a check run points at its own), so it names the same files in a tree
  extracted elsewhere."
  [] (config/value :seon.config.source/tasks-dir))

(defn- with-wiki
  "Program db db with the wiki and the tasks of the checkout at dir, by root's own loaders
  (seon.doc/load-dir-tx of the wiki dirs, then seon.task/load-tx: a task's cite of a section is a ref to it, so the
  sections are there first), written through the one checked write (seon.db/db-with), as root writes them. Answers
  the db, or the typed error that refused it: a task file that does not read, or a citation of nothing, which the
  write refuses naming its target (docs/references.md#task-references), never a lookup the store throws on."
  [db dir]
  ;; the program store is keyed by the program's attributes alone (seon.test-store/program-schema-digest),
  ;; so a kept tree's db may lack an attribute a task or a page holds (the cite entities, :seon.cite/id): the ones it
  ;; lacks are added to this value first, as seon.db/install-missing! adds them to a store a root opens
  (let [db (d/db-with db (vec (remove #(contains? (:schema db) (:db/ident %)) pdb/schema)))
        ;; the step's edge: the checked write's refusal is its answer, as a root's load answers it (seon.db/with throws it typed)
        write (fn [db tx] (try (pdb/db-with db tx) (catch clojure.lang.ExceptionInfo e (if (:seon.error/type (ex-data e)) (ex-data e) (throw e)))))
        db (write db (doc/load-dir-tx db dir (config/value :seon.config.root/wiki-dirs) {}))
        tx (when-not (contract/error? db) (task/load-tx db (seon.system/task-files (str dir "/" (tasks-dir)))))]
    (cond (contract/error? db) db
          (contract/error? tx) tx
          :else (write db tx))))

(defn remove-checkout!
  "Remove the checkout base-program made at at, its submodule worktrees with it (bin/worktree --remove-at), when there is one.
  See docs/health.md#checks-are-callers-of-seon."
  [at]
  (when (.isDirectory (io/file at))
    (let [r (lib/sh (str (config/checkout) "/bin/worktree") "--remove-at" at)]
      (when-not (zero? (:exit r)) (seon.lib/remove-tree! at)))))

(defn- read-branch
  "The db of branch b in the LMDB store at path, read only (no seon.db/open!, which would install this code's schema there), or
  nil when the store or the branch is not there. Its connection stays open for the process, as program-db's does. See
  docs/health.md#checks-are-callers-of-seon."
  [path b]
  (when (pdb/store? path)
    (let [cfg (pdb/store-cfg :lmdb path)
          c (pdb/connect cfg)]
      (when (try (pdb/branch? c b) (finally (d/release c)))
        @(pdb/connect (assoc cfg :branch b))))))

(defn- make-base-tree!
  "Make the base's tree's branch in the base store at path (base-program) by the base's own code: a checkout of commit base of the
  repository at root at at (bin/worktree --at, its built products cloned from this checkout's), whose runner plans its tests
  (bin/test --plan tests) with its program store set to stem, path's (stem-<program schema digest>); path seeded from store, this checkout's kept program store,
  when it is not there, so the base's import refreshes the nearest kept tree. The checkout is removed after. Answers nil, or
  :seon.fx/io-error naming the step that failed and the end of what it said. See docs/health.md#checks-are-callers-of-seon."
  [root base tree store stem path at]
  (when-not (pdb/store? path)
    (.mkdirs (io/file path))
    (lib/sh "cp" "-c" (str store "/data.mdb") (str path "/data.mdb")))
  (try
    (let [fail (fn [msg r] (contract/error :seon.fx/io-error #'test-runner/make-base-tree! (str msg ": " (fx/runner-end (str (:err r) "\n" (:out r)))) {:path at}))
          made (lib/sh (str (config/checkout) "/bin/worktree") "--at" at base root (str (config/checkout)))
          out (str at "/target/prove/health-base")
          r (when (zero? (:exit made))
              (apply lib/sh (concat fx/child-env-scrub [(str at "/bin/test") "--plan" "tests" out
                                                        "--set" (str ":seon.config.proofs/program-store=" (pr-str stem))
                                                        :dir at])))
          planned (let [f (io/file out "tests.edn")] (when (.isFile f) (:tree (edn/read-string (slurp f)))))]
      (cond (not (zero? (:exit made))) (fail (str "bin/worktree --at " at " " base " exited " (:exit made)) made)
            (nil? planned) (fail (str "the base's runner made no plan in " out " (exit " (:exit r) ")") r)
            (not= tree planned) (fail (str "the base's runner planned tree " planned ", not the base's " tree) r)))
    (finally (remove-checkout! at))))

(defn base-program
  "The program db of commit base of the repository at root, for the health step (docs/health.md#checks-are-callers-of-seon): its
  tree's branch in store, this checkout's kept program store, when it keeps it; else its branch in the base store, stem-<program
  schema digest> as store is keyed (health!'s stem: :seon.config.proofs/program-store's with -health-base), which the base's own
  code makes when it is not there (make-base-tree!, a process of its own in a checkout at at), read here as facts. Never analyzed
  in this image: analysis runs each file's ns form in the live namespace, so a base's ns form that aliases a namespace
  this image holds under another target retargets this image's alias (across the rename of seon.system to seon.graph,
  seon.server's `c`), and a macro there expands with this checkout's code. Answers the db, or :seon.fx/io-error: a base whose
  program schema (seon.test-store/program-schema-digest) is not this code's keeps no branch this code reads."
  [root base store stem at]
  (let [;; the base's tree as a plan of its checkout names it (seon.fx/tree-id: less :seon.config.source/tree-excludes), by bin/tree
        tree (let [r (lib/sh (str (config/checkout) "/bin/tree") root base)]
               (if (zero? (:exit r)) (str/trim (:out r)) (throw (ex-info (str "bin/tree " root " " base ": " (:err r)) {}))))
        b (keyword (str (prefix-of #(fx/tree-file root base %)) tree))
        path (str stem "-" @ts/kept-program-digest)
        ;; the base's own runner names its store by its own key (seon.test-store/program-store-key: its runtime and pins may be
        ;; another's), so its branch is read from whichever store of the stem holds it, never only this code's
        stems (fn [] (let [d (.getParentFile (io/file stem)) n (.getName (io/file stem))]
                       (for [f (sort (or (.list d) (make-array String 0))) :when (re-matches (re-pattern (str "\\Q" n "\\E-[0-9a-f]{8}")) f)]
                         (str d "/" f))))
        any (fn [] (some #(read-branch % b) (stems)))]
    (or (read-branch store (tree-branch tree))
        (any)
        (make-base-tree! root base tree store stem path at)
        (any)
        (contract/error :seon.fx/io-error #'test-runner/base-program
                        (str "the base's program store " path " keeps no branch " b ": the base's program schema or importer is not this code's") {:path path}))))

(defn unestimated
  "The tests of program db that state no estimate (a test var with no :seon.test/estimate fact), each {:test :ns :ticks}, its
  ticks the median its newest greens spent (seon.fx/typical-ticks; nil when it has none), costliest first: the list each area
  states estimates from, worked from what each test must do (plans: tests-spent-ticks). A report, never a gate or a budget.
  See docs/merging.md#a-tests-tick-budget."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector [:map [:test :qualified-symbol] [:ns :symbol] [:ticks [:maybe :int]]]]]}
  [db]
  (let [ts (vec (sort (d/q '[:find [?n ...] :where [?v :var/meta :test] [?v :var/name ?n] (not [?v :seon.test/estimate _])] db)))
        ;; the repository's verdict cache (the main tree's target/results, bin/env's SEON_RESULTS_DIR), never the one this
        ;; process was given: bin/check gives each of its runs a fresh private cache, and health as a prove's check step read
        ;; no green there and wrote every tick nil (2026-11-13)
        main (str/trim (:out (lib/sh "git" "-C" (str (config/checkout)) "rev-parse" "--path-format=absolute" "--git-common-dir")))
        ticks (fx/typical-ticks (str (.getParent (io/file main)) "/target/results") ts)]
    (vec (sort-by (juxt #(- (or (:ticks %) -1)) (comp str :test))
                  (for [t ts] {:test t :ns (symbol (namespace t)) :ticks (get ticks t)})))))

(defn- health!
  "The health step (docs/health.md#checks-are-callers-of-seon): what this checkout caused since its
  base (seon.fx/base-commit), branch-health of classes. The head is the working tree's program db, kept in the
  program store (program-db: a tree it never saw is the nearest kept tree with the changed files refreshed), and the
  base the base tree's, made by the base's own code in a process of its own (base-program), each with its tasks and wiki (with-wiki):
  it costs what the trees differ by and two wiki loads, never a root booted or the program imported
  whole. One line per row the branch caused, one per attention row, the report, and HEALTH OK or
  HEALTH FAILED. Answers the exit status."
  [classes]
  (let [root (str (config/checkout))
        base (fx/base-commit root)]
    (if-not base
      (do (println (str "HEALTH FAILED: no base: " root " has no commit before this one on " (config/value :seon.config.source/main))) 1)
      (let [store (ts/shared-program-store! (config/value :seon.config.proofs/program-store) @ts/kept-program-digest)
            head (with-wiki (program-db root store) root)
            ;; the base's tasks and wiki, as git holds them, in a directory of this run's own, removed once read; and where the base's
            ;; checkout is made when its program must be (base-program), removed once its runner ran
            dir (str root "/target/tmp/health-base-" base "-" (random-uuid))
            based (try (let [db (base-program root base store (str (config/value :seon.config.proofs/program-store) "-health-base")
                                              (str root "/target/tmp/health-checkout-" base "-" (random-uuid)))]
                         (if (contract/error? db) db (with-wiki db (fx/checkout-at root base ["docs" (tasks-dir) "*.md"] dir))))
                       (finally (seon.lib/remove-tree! dir)))
            refused (first (filter contract/error? [head based]))
            _ (when refused
                (println (str "HEALTH FAILED: the " (if (contract/error? head) "tree's" (str "base " base "'s")) " program, wiki or tasks were refused: "
                              (:seon.error/message refused) " " (pr-str (dissoc refused :seon.error/message)))))
            {:keys [caused attention checks]} (when-not refused (branch-health based head classes))
            h (when-not refused (problems/health head))
            open (reduce + 0 (for [c (or (seq classes) (sort problems/caused-classes))] (get-in h [:classes c :n] 0)))]
        (doseq [r caused] (println (name (:class r)) (:message r)))
        (doseq [r attention] (println "attention" (name (:class r)) (:message r)))
        ;; every test that states no estimate, costliest first: its count and top ten here, the whole list in
        ;; target/unestimated-tests.edn for each area to state its estimates from (a report, no gate)
        (when-not refused
          (let [u (unestimated head)]
            (write! (str root "/target/unestimated-tests.edn") (str "[" (apply str (map #(str (sedn/encode %) "\n") u)) "]\n"))
            (println (str "unestimated " (count u) " tests state no estimate (target/unestimated-tests.edn); the costliest:"))
            (doseq [{:keys [test ticks]} (take 10 u)] (println "  " test (or ticks "no green")))))
        (println (pr-str {:base base :caused (count caused) :attention (count attention) :open-in-head open :checks checks}))
        (let [ok (and (not refused) (every? val checks))]
          (println (if ok "HEALTH OK" "HEALTH FAILED"))
          (if ok 0 1))))))

(defn route
  "The route a landing of the checkout at root takes, against the standing root whose binary was built from tree running
  (docs/merging.md#a-branch-lands-with-binland): {:route :live} when that binary is current for root's tree by
  seon.system/binary-current at :root scope (what root runs from its binary while its agents load the rest: the branch is
  proved in a fork of main's agent, its diff loaded, through the test service); else {:route :local :why answer}, the
  checkout's own build (a pin, native code, a baked resource, root's own code, or a binary of no known tree). db and g
  are the plan's program and reach graph of root's tree."
  [db g running tree]
  (if-let [why (seon.system/binary-current db {:inputs {:tree running}} tree {:graph g :scope :root})]
    {:route :local :why why}
    {:route :live}))

(defn- route!
  "bin/test --route RUNNING: prints `route: live` or `route: local <why>` for this checkout's tree against a binary built
  from tree RUNNING (bin/land asks the standing root for it). Answers 0 live, 3 local."
  [running]
  (let [root (source-tree)
        tree (fx/tree-id root)
        store (ts/shared-program-store! (config/value :seon.config.proofs/program-store) @ts/kept-program-digest)
        db (program-db root store tree (config/value :seon.config.proofs/program-store-max-bytes))
        in (fx/inputs root config/config (config/checkout) tree)
        g (reach-graph-of db (:data-refs in) store)
        {r :route why :why} (route db g (not-empty running) tree)]
    (println (str "route: " (name r) (when why (str " " (pr-str (select-keys why [:binary :tree :reaches :missing]))))))
    (if (= :live r) 0 3)))

(defn -main [& args]
  ;; a shard forks (a test's child, its store's create), so it starts no collector sweeper, as the binary does
  ;; (seon.main/-main, seon.lib/gc-sweepers!): a sweeper started in a forked child deadlocked it on Chez's
  ;; thread mutex, and a-create-that-fails-leaves-no-store hung run 29's gate, its orphan left 70 min
  (lib/gc-sweepers! 0)
  (let [[flag & more] (config/without-overrides args)]
    (System/exit
      (case flag
        ;; --plan WHAT OUT [--all] [NS ...]: bin/test's and bin/prove's planning part
        "--plan" (let [[what out & names] more all? (case (first names) "--all" true "--merge" :merge false)]
                   (prove! what all? out (if all? (rest names) names)))
        "--shard" (shard! (first more))
        "--store-steps" (store-steps! (first more))
        "--overran" (overran! (first more))
        ;; --route RUNNING: bin/land's route, live through the standing root or the checkout's own build
        "--route" (route! (first more))
        ;; --health [CLASS ...]: bin/check's health step
        "--health" (health! (map keyword more))))))
