(ns test-runner
  "bin/test's and bin/prove's runner (task seon.proofs/prove-runs-what-a-change-reaches):
  src and test imported as program facts (program-db), each test keyed by its reach digest
  (seon.program/reach-digests) and each bin/check step by its functions' (seon.proofs/
  check-steps); seon.proofs/plan selects what has no green result at its digest.
    --plan WHAT OUT [--all] [NS ...]  the selection: tests dealt into OUT/shard-<i>.edn, steps
                                      into OUT/steps (WHAT: tests, checks or both)
    --shard FILE                      one shard's tests run here, each result stored
    --store-steps OUT                 the steps bin/check ran green stored at their digests
  bin/test runs each shard in a process of its own, at once. The program the plan reads is kept
  per git tree in a store of its own (program-db)."
  (:require [seon.profile :as profile] [clojure.java.io :as io] [clojure.java.shell :as sh] [clojure.string :as str] [clojure.edn :as edn]
            [datahike.api :as d] [seon.config :as config] [seon.db :as pdb] [seon.test-store :as ts]
            [seon.edn :as sedn] [seon.error :as error] [seon.fx :as fx] [seon.program :as program] [seon.proofs :as proofs] [seon.source :as source] [seon.system :as system]))

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
;; notes/test-fork-main-2026-10-23.md): kept as a branch named by the tree's id, a content key
;; that is never updated, in one LMDB store per checkout (:seon.config.proofs/program-store).

(defn- tree-branch "The branch keeping the program of git tree id tree." [tree] (keyword (str "tree-" tree)))

(defn- branch-tree "The tree id branch b keeps, or nil (b keeps no tree)." [b]
  (let [n (name b)] (when (str/starts-with? n "tree-") (subs n 5))))

(defn- program-file?
  "Is relative path p one of the program's files: a .clj under src or test (what the import reads)?"
  [p] (and (str/ends-with? p ".clj") (or (str/starts-with? p "src/") (str/starts-with? p "test/"))))

(defn- changed-paths
  "The program files that differ between git trees a and b of root's repository (git diff
  --name-only --no-renames: a moved file is its old path removed and its new one added)."
  [root a b]
  (filterv program-file? (str/split-lines (:out (sh/sh "git" "-C" root "diff" "--name-only" "--no-renames" a b)))))

(defn- tree-files
  "{path text} of the program files of git tree t in root's repository, read from git's objects,
  never the working tree (an edit after t was taken cannot be kept under t): paths' (nil for one
  t does not hold), or every program file under src and test. Through git archive into a
  directory of its own, removed after."
  [root t paths]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/seon-tree-" (random-uuid))
        tar (str dir ".tar")
        ;; what t holds of what is asked (git archive refuses a path it does not hold)
        held (filterv seq (str/split-lines (:out (apply sh/sh "git" "-C" root "ls-tree" (concat (when paths ["-r"]) ["--name-only" t "--"] (or paths ["src" "test"]))))))]
    (.mkdirs (io/file dir))
    (when (seq held)
      (apply sh/sh "git" "-C" root "archive" "--format=tar" "-o" tar t "--" held)
      (sh/sh "tar" "-x" "-f" tar "-C" dir))
    (let [files (if paths
                  (into {} (for [p paths :let [f (io/file dir p)]] [p (when (.isFile f) (slurp f))]))
                  (into {} (filter #(program-file? (key %))) (:value (fx/read-source {:req {:root dir :dirs ["src" "test"]}}))))]
      (sh/sh "rm" "-rf" dir tar)
      files)))

(defn program-db
  "The db holding src and test of the checkout at root as program facts (seon.source: analyzed,
  not run), kept in the LMDB store at store on a branch named by root's git tree id (seon.fx/
  tree-id): the same tree reads its branch; a tree never seen is branched from the kept tree of
  the fewest changed program files (one in root's repository) and brought current by
  seon.source/refresh-tx; a store keeping no tree imports the whole program (seon.source/
  import-tx). Every text is read from the tree's git objects (tree-files). One writer at a time
  per store (two runs never share a checkout, AGENTS section 3). A store past
  :seon.config.proofs/program-store-max-bytes on disk (an LMDB file never shrinks) is removed
  before it is opened, and starts again from this tree. The branch's connection stays open for
  the process (the db value reads the store); the store's main connection is released."
  [root store]
  (when (> (.length (io/file store "data.mdb")) (config/value :seon.config.proofs/program-store-max-bytes))
    (sh/sh "rm" "-rf" store))
  (let [tree (fx/tree-id root)
        b (tree-branch tree)
        {:keys [conn cfg]} (pdb/open! :lmdb store 0 :db)
        branches (d/branches conn)]
    (when-not (contains? (set branches) b)
      (let [[from paths] (->> (for [k branches :let [t (branch-tree k)]
                                     :when (and t (zero? (:exit (sh/sh "git" "-C" root "cat-file" "-e" t))))]
                                 [k (changed-paths root t tree)])
                               (sort-by (comp count second))
                               first)
            _ (d/branch! conn (or from :db) b)
            c (d/connect (assoc cfg :branch b))]
        (d/transact c {:tx-data (if from
                                  (source/refresh-tx @c (tree-files root tree paths))
                                  (source/import-tx @c (tree-files root tree nil)))})))
    (d/release conn)
    @(d/connect (assoc cfg :branch b))))

(defn- tests-of
  "{test digest} of the named test namespaces (or every one) on the checkout at root, the
  ones among them that reach a process of their own (:process, seon.program/graph-index), its
  program db (program-db, kept in store), inputs and reach graph (seon.program/reach-graph:
  walked once, the check steps' index is read from it too)."
  [root names store]
  (let [nss (set (if (seq names) (map symbol names) (test-namespaces root)))
        db (program-db root store)
        in (fx/inputs root)
        g (program/reach-graph db (:data-refs in))
        idx (into {} (filter #(nss (symbol (namespace (key %))))) (program/graph-index g in nil))]
    {:graph g :inputs in
     :digests (update-vals idx :digest)
     :process (set (keep (fn [[t v]] (when (:process? v) t)) idx))
     :binary (set (keep (fn [[t v]] (when (:binary? v) t)) idx))}))

(defn automatic
  "What a run that names nothing selects from cand ({key digest}): every key but those in
  process, the tests and bin/check steps that reach a process of their own (a root, an agent,
  a shell, a fork, the binary: seon.program/reach-index's :process?). Owner, 2026-10-23: tests
  shouldn't be running agent turns, not automatically at least. Those run when named (bin/test
  NS, bin/check MODE), in the full run, or by root's gate before a merge that reaches them."
  {:malli/schema [:=> [:cat [:map-of :any :string] [:set :any]] [:map-of :any :string]]}
  [cand process] (apply dissoc cand process))

(defn- refused-names
  "Why the arguments after --plan WHAT OUT [--all] cannot select tests, or nil: a flag this runner
  does not know (bin/test --reach ran nothing and passed: reach is the default since bin/prove),
  or a name that is no test namespace of test/."
  [names]
  (let [flags (filter #(str/starts-with? % "--") names)
        nss (set (map str (test-namespaces (source-tree))))
        unknown (remove #(or (str/starts-with? % "--") (nss %)) names)]
    (cond (seq flags) (str "no flag " (str/join " " flags) " (bin/test [--all] [NS ...]: --all, the one flag, comes first)")
          (seq unknown) (str "no test namespace " (str/join " " unknown) " in test/"))))

(defn- write!
  "text to path through the effect owner's file write (seon.fx/write-file); its declared
  error is thrown with its data, so the run ends red naming the path, never as if written."
  [path text]
  (let [r (fx/write-file {:req {:path path :text text}})]
    (when (:seon.error/type r) (throw (ex-info (str "write failed: " (:seon.error/message r)) r)))
    r))

(def ^:private ms-dir
  "Where each test's last measured ms is kept, one file per test (written by the shard that ran
  it: no two shards write one file): this checkout's own, what orders its next run's deal."
  "target/prove/ms")

(defn- measured-ms
  "{test ms} of tests whose last run is kept in ms-dir: what the deal orders by, never what a
  test proves."
  [tests]
  (into {} (for [t tests :let [f (io/file ms-dir (str t))] :when (.isFile f)] [t (parse-long (str/trim (slurp f)))])))

(defn shards
  "tests (var symbols) dealt into at most jobs shards by their measured ms ({test ms}; a test
  never measured counts as the mean of those measured), so the shards end together: a
  namespace's tests (and so its fixtures) in one, unless the namespace alone is more than a
  shard's share, when it is split into share-sized parts (its fixtures run in each); the
  longest part first into the shard with least so far; no empty shard."
  [tests jobs ms]
  (let [mean (if (seq ms) (quot (reduce + (vals ms)) (count ms)) 1)
        cost #(get ms % mean)
        share (max 1 (quot (reduce + (map cost tests)) (max 1 jobs)))
        parts (mapcat (fn [[_ ts]]
                        (let [ts (vec (sort-by (juxt (comp - cost) str) ts))]
                          (if (<= (reduce + (map cost ts)) share)
                            [ts]
                            ;; longest first into the lightest part of as many as the share needs
                            (let [k (int (Math/ceil (/ (reduce + (map cost ts)) (double share))))]
                              (remove empty? (reduce (fn [ps t] (update ps (apply min-key #(reduce + (map cost (ps %))) (range k)) conj t))
                                                     (vec (repeat k [])) ts))))))
                      (sort-by (comp str key) (group-by (comp symbol namespace) tests)))
        parts (sort-by (juxt #(- (reduce + (map cost %))) (comp str first)) parts)
        n (max 1 (min jobs (count parts)))]
    (vec (remove empty? (reduce (fn [sh p] (update sh (apply min-key #(reduce + (map cost (sh %))) (range n)) into p))
                                (vec (repeat n [])) parts)))))

(defn- write-shards!
  "tests (var symbols) dealt into :seon.config.proofs/jobs shards (shards), each written as
  out/shard-<i>.edn ({:tests [...] :digests {test digest} :tree t}, t the planned tree's id)
  for bin/test to run, one process each (bin/test removes the last run's first). Answers the
  shard count."
  [out tests digests tree]
  (let [sh (shards tests (config/value :seon.config.proofs/jobs) (measured-ms tests))]
    (doseq [[i ts] (map-indexed vector sh)]
      (write! (str out "/shard-" i ".edn") (sedn/encode {:tests ts :digests (select-keys digests ts) :tree tree})))
    (count sh)))

(defn- shard!
  "One shard bin/test runs (out/shard-<i>.edn): its namespaces required, its tests run
  (seon.proofs/run-tests!), each test named as it starts and each red one printed (RED); then
  its greens stored at their digests (seon.proofs/store-greens!), unless the tree is not the
  plan's before and after the run (an edit during the run: what ran is not what the digests name, so
  nothing is kept, and the run stays as reported). A shard that dies midway stores nothing.
  Then SHARD n green m red. Answers the exit status."
  [file]
  (let [{:keys [tests digests tree]} (edn/read-string (slurp file))
        t0 (profile/now-ns)]
    (doseq [n (distinct (map (comp symbol namespace) tests))] (require n))
    (let [before (fx/tree-id (System/getProperty "user.dir"))
          ;; each test under the one bound an agent's run has (seon.proofs/run-tests!): one verdict wherever it runs
          ran (proofs/run-tests! tests digests
                                 (fn [x] (cond (:seon.test/started x) (println "started" (:seon.test/started x))
                                               (:seon.test/output x) (print (:seon.test/output x))
                                               (:seon.test/report x) nil
                                               (not (proofs/green? x)) (println "RED" (:test x) (pr-str (select-keys x [:pass :fail :error :message]))))
                                   (flush))
                                 (config/value :seon.config.agent/test-ticks))
          ;; the tree the tests read is the plan's from before the run to after it: an edit
          ;; during the run (a test reading files reads it) is not what the digests name
          same? (= tree before (fx/tree-id (System/getProperty "user.dir")))
          unstored (when same? (proofs/store-greens! #(fx/write-file {:req %}) (config/value :seon.config.source/test-results-dir) ran))
          _ (.mkdirs (io/file ms-dir))
          _ (doseq [[t r] ran] (write! (str ms-dir "/" t) (str (:ms r))))
          red (count (remove proofs/green? (vals ran)))]
      (when-not same? (println "the tree changed since the plan: no result stored"))
      (doseq [u unstored] (println "UNSTORED" (:key u) (:message u)))
      (println (str "SHARD " (count ran) " tests, " (- (count ran) red) " green, " red " red, in "
                    (quot (- (profile/now-ns) t0) 1000000) " ms"))
      (if (and (zero? red) (empty? unstored)) 0 1))))

(defn plan-of
  "What bin/prove and bin/test run on the checkout at root (what: tests, checks or both): the
  tests of names (or all) and the bin/check steps with no green at their digest
  (seon.proofs/plan; all? every one), less, when nothing is named, the ones that reach a
  process (automatic), its program read from the kept store at store (program-db). Answers
  {:run-tests :run-steps :reused :cand :left (the process keys left out) :digests :steps
  :missing :inputs}."
  [root what all? names store]
  (let [{:keys [graph inputs digests process binary]} (tests-of root names store)
        dir (config/value :seon.config.source/test-results-dir)
        tests? (not= what "checks") checks? (not= what "tests")
        ;; the steps' functions read from the tests' graph: their digests and which boot a root or a process
        step-idx (when checks? (program/graph-index graph inputs (into {} (filter (comp vector? val)) proofs/check-steps)))
        ;; the health step's base: the tree this checkout's caused problems are read against
        base (some->> (fx/base-commit root) (fx/tree-of root))
        {steps :digests missing :missing} (when checks? (proofs/step-digests #(update-vals (select-keys step-idx (keys %)) :digest) (assoc inputs :base base)))
        process (into (if tests? process #{}) (keep (fn [[k v]] (when (:process? v) k))) step-idx)
        named? (or all? (seq names))
        all-cand (merge (when tests? digests) steps)
        cand (if named? all-cand (automatic all-cand process))
        {:keys [run reused]} (proofs/plan nil cand (if all? {} (proofs/greens dir (vec (vals cand)))) #{})
        {run-steps true run-tests false} (group-by string? run)]
    {:run-tests (vec run-tests) :run-steps (vec run-steps) :reused reused :cand cand :process process :binary binary
     :left (when-not named? (vec (sort-by str (filter all-cand process))))
     :digests digests :steps steps :missing missing :inputs inputs}))

(defn- prove!
  "The planning part of bin/prove and bin/test (what is tests, checks or both): plan-of's tests
  are dealt into out's shards (write-shards!), and its bin/check steps are written to out/steps
  (names, one line) and out/steps.edn ({:tree :digests {step digest}}) for bin/prove to run;
  out/binary names the selected keys that reach target/seon, out/steps-binary the steps among
  them (both empty: nothing to build, nothing to seed). Answers the exit
  status: 1 when a check step names a function the program does not define."
  [what all? out names]
  (if-let [bad (refused-names names)]
    (do (println (str "TEST FAILED: " bad)) 1)
  (let [t0 (profile/now-ns)
        dir (config/value :seon.config.source/test-results-dir)
        tests? (not= what "checks") checks? (not= what "tests")
        {:keys [run-tests run-steps reused cand left digests steps missing inputs binary]} (plan-of (source-tree) what all? names (ts/kept-store! (config/value :seon.config.proofs/program-store)))
        n (write-shards! out run-tests digests (:tree inputs))
        ;; what runs and reaches the binary (a test that reaches binary-sh, a step that runs a seon.check
        ;; mode): bin/test builds target/seon only when this is not empty, bin/check seeds only for the steps
        step-bin (filterv proofs/step-needs-binary? run-steps)
        bin-keys (into (vec (filter binary run-tests)) step-bin)
        _ (write! (str out "/binary") (str (str/join " " bin-keys) (when (seq bin-keys) "\n")))
        ;; the process tests' store, made here before any shard starts (shards' roots that each made
        ;; it raced: seon.test-store/process-store), for this schema (seon.test-store/kept-store!)
        _ (when (seq (filter binary run-tests)) (ts/make-process-store! (.getCanonicalPath (io/file (config/checkout)))))
        _ (write! (str out "/steps-binary") (str (str/join " " step-bin) (when (seq step-bin) "\n")))]
    (when left
      (println (str "prove: " (count left) " tests and steps that reach a process left out (run them by name:"
                    " bin/test NS, bin/check MODE, or the full run): " (str/join " " (map str left)))))
    (println (str "prove: " (count cand) " keys (" (if tests? (count digests) 0) " tests, " (count steps) " check steps), "
                  (count reused) " green at their digest (reused), " (count run-tests) " tests to run in " (if (seq run-tests) n 0)
                  " shards, " (count run-steps) " steps to run; selected in " (quot (- (profile/now-ns) t0) 1000000) " ms; results " dir))
    (when checks?
      (write! (str out "/steps") (str (str/join " " run-steps) (when (seq run-steps) "\n")))
      (write! (str out "/steps.edn") (sedn/encode {:tree (:tree inputs) :digests (select-keys steps run-steps)}))
      (when (seq run-steps) (println "prove: check steps to run:" (str/join " " run-steps)))
      (when (seq missing)
        (println "prove: check steps naming a function the program does not define (seon.proofs/check-steps):" (str/join " " missing))))
    (if (seq missing)
      1
      (do (when (and (empty? run-tests) (empty? run-steps))
            (println (str "prove: nothing to run: " (count reused) " reused green at their digest")))
          0)))))

(defn- store-steps!
  "bin/prove's last part: each step of out/steps.edn that bin/check named green in out/green
  (check-<step>, space separated) stored green at its digest (seon.proofs/store-greens!),
  unless the step ran on a binary (seon.proofs/step-needs-binary?) built from another tree
  than the plan's (target/seon.tree, bin/build's): an edit between the plan and the build. A red one is not
  stored, so it runs again. Answers the exit status: 1 when a step ran red or was not stored."
  [out]
  (let [dir (config/value :seon.config.source/test-results-dir)
        {:keys [tree digests]} (edn/read-string (slurp (str out "/steps.edn")))
        f (io/file out "green")
        green (set (when (.isFile f) (map #(str/replace % #"^check-" "") (str/split (str/trim (slurp f)) #"\s+"))))
        red (sort (remove green (keys digests)))
        built (let [b (io/file "target/seon.tree")] (when (.isFile b) (str/trim (slurp b))))
        ;; a step that runs a seon.check mode ran on target/seon: kept only if built from the plan's tree;
        ;; laws and lmdb-kill ran no mode, so no binary's tree is theirs to compare
        keep? (fn [k] (and (green k) (or (= tree built) (not (proofs/step-needs-binary? k)))))
        unstored (do (when (and (not= tree built) (some #(and (green %) (proofs/step-needs-binary? %)) (keys digests)))
                       (println "prove: the binary was built from another tree than the plan's: no step that ran on it stored"))
                     (proofs/store-greens! #(fx/write-file {:req %}) dir (into {} (for [[k dg] digests :when (keep? k)] [k {:step k :digest dg :pass 1 :fail 0 :error 0}]))))]
    (doseq [u unstored] (println "UNSTORED" (:key u) (:message u)))
    (println (str "prove: " (count (filter green (keys digests))) " check steps green, " (count red) " red"
                  (when (seq red) (str ": " (str/join " " red)))))
    (if (and (empty? red) (empty? unstored)) 0 1)))

(defn -main [& args]
  (let [[flag & more] args]
    (System/exit
      (case flag
        ;; --plan WHAT OUT [--all] [NS ...]: bin/test's and bin/prove's planning part
        "--plan" (let [[what out & names] more all? (= "--all" (first names))]
                   (prove! what all? out (if all? (rest names) names)))
        "--shard" (shard! (first more))
        "--store-steps" (store-steps! (first more))))))
