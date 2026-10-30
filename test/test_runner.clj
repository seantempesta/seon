(ns test-runner
  "bin/test's and bin/prove's runner (task seon.proofs/prove-runs-what-a-change-reaches):
  :seon.config.source/dirs imported as program facts (program-db), as root imports them, each test keyed by its reach digest
  (seon.program/graph-index) and each bin/check step by its functions' (seon.proofs/
  check-steps); seon.proofs/plan selects what has no green result at its digest.
    --plan WHAT OUT [--all] [NS ...]  the selection: tests dealt into OUT/shard-<i>.edn, steps
                                      into OUT/steps (WHAT: tests, checks or both)
    --shard FILE                      one shard's tests run here, each result stored
    --store-steps OUT                 the steps bin/check ran green stored at their digests
  bin/test runs each shard in a process of its own, at once. The program the plan reads is kept
  per git tree in a store of its own (program-db)."
  (:require [seon.profile :as profile] [konserve.jolt.fasl-store :as fasl] [clojure.java.io :as io] [clojure.java.shell :as sh] [clojure.string :as str] [clojure.edn :as edn]
            [datahike.api :as d] [seon.config :as config] [seon.db :as pdb] [seon.test-store :as ts]
            [seon.edn :as sedn] [seon.error :as error] [seon.lib :as lib] [seon.contract :as contract] [seon.fx :as fx] [seon.program :as program] [seon.proofs :as proofs] [seon.print :as print] [seon.source :as source] [seon.system :as system]
            [seon.docs :as docs] [seon.task :as task] [seon.problems :as problems] [my.root]))

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

(defn- tree-prefix
  "The prefix of the branches keeping a tree's program as :seon.config.source/dirs import it:
  a store's branches made under other dirs (another import) are none of this one's."
  [] (str "tree-" (lib/digest (pr-str (config/value :seon.config.source/dirs))) "-"))

(defn tree-branch "The branch keeping the program of git tree id tree." [tree] (keyword (str (tree-prefix) tree)))

(defn- branch-tree "The tree id branch b keeps, or nil (b keeps no tree of this import)." [b]
  (let [n (name b) p (tree-prefix)] (when (str/starts-with? n p) (subs n (count p)))))

(defn- program-file?
  "Is relative path p one of the program's files: what root's import reads of
  :seon.config.source/dirs (a .clj, or a schema resource: seon.source/schema-resource?), so the
  program a plan reads here is root's, and a test's digest is the same on both sides?"
  [p] (and (some #(str/starts-with? p (str % "/")) (config/value :seon.config.source/dirs))
           (or (str/ends-with? p ".clj") (source/schema-resource? p))))

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
      (run! fx/remove-tree! [dir tar])
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
        parent (if from (let [p (d/connect (assoc cfg :branch from))] (try @p (finally (d/release p)))) @conn)
        tx (if from
             (source/refresh-tx parent (tree-files root tree paths))
             (source/import-tx parent (tree-files root tree nil)))
        stage (keyword (str "stage-" (random-uuid)))]
    (d/branch! conn (or from :db) stage)
    (try (let [c (d/connect (assoc cfg :branch stage))]
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
  :seon.config.proofs/program-store-max-bytes on disk (an LMDB file never shrinks) is removed
  before it is opened, and starts again from this tree. The branch's connection stays open for
  the process (the db value reads the store); the store's main connection is released. tree: the
  checkout's tree-id when the caller holds it (the plan computes it once)."
  ([root store] (program-db root store (fx/tree-id root)))
  ([root store tree]
  (when (> (.length (io/file store "data.mdb")) (config/value :seon.config.proofs/program-store-max-bytes))
    (fx/remove-tree! store))
  (let [b (tree-branch tree)
        {:keys [conn cfg]} (pdb/open! :lmdb store 0 :db)
        branches (d/branches conn)]
    (when-not (contains? (set branches) b)
      (keep-tree! conn cfg root tree b branches))
    (d/release conn)
    @(d/connect (assoc cfg :branch b)))))

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
        db (program-db root store tree)
        in (fx/inputs root config/config (config/checkout) tree)
        g (reach-graph-of db (:data-refs in) store)
        all (into {} (filter #(nss (symbol (namespace (key %))))) (program/graph-index g in nil))
        ;; a name of the form ns/test selects that one deftest (bin/test NS/TEST); a bare ns selects its tests
        whole (if (seq names) (set (keep #(when-not (str/includes? % "/") (symbol %)) names)) nss)
        idx (into {} (filter #(or (whole (symbol (namespace (key %)))) (vars (key %)))) all)]
    {:graph g :inputs in
     :no-test (vec (sort-by str (concat (remove (set (map (comp symbol namespace key) all)) nss)
                                        (remove all vars))))
     :digests (update-vals idx :digest)
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

(defn- write-shards!
  "tests (var symbols) dealt into :seon.config.proofs/jobs shards (shards), the generative check's keys mg
  ({key digest}) dealt among them (deal-mg), each written as out/shard-<i>.edn ({:tests [...] :mg {key digest}
  :digests {test digest} :tree t}, t the planned tree's id) for bin/test to run, one process each (bin/test
  removes the last run's first), and each measured test's stall bound as out/stall-bounds (\"<test> <ms>\"
  lines, for bin/stalls). Answers the shard count."
  [out tests digests tree mg]
  (let [ms (measured-ms tests)
        sh (proofs/shards tests (config/value :seon.config.proofs/jobs) ms)
        mg-ms (measured-ms (keys mg))
        floor (config/value :seon.config.agent/stall-ms) k (config/value :seon.config.proofs/stall-factor)
        dealt (deal-mg sh mg ms mg-ms)]
    (doseq [[i [ts m]] (map-indexed vector dealt)]
      (write! (str out "/shard-" i ".edn") (sedn/encode {:tests ts :mg m :digests (select-keys digests ts) :tree tree})))
    ;; each measured test's stall bound, k times its own last run, for bin/stalls (a test with no
    ;; record takes the floor, out/stall-ms)
    (write! (str out "/stall-bounds") (apply str (for [[t m] (sort-by key ms)] (str t " " (max floor (* k m)) "\n"))))
    (count dealt)))

(defn- shard!
  "One shard bin/test runs (out/shard-<i>.edn): its namespaces required, its tests run
  (seon.proofs/run-tests!), each test named as it starts (started) and as it ends (ended), each
  red one printed (RED); then
  its results, greens and reds, recorded at their digests in the results store in one commit (seon.fx/write-verdicts!:
  their failures and each test's contracted functions' cost with them), unless the tree is not the
  plan's before and after the run (an edit during the run: what ran is not what the digests name, so
  nothing is kept, and the run stays as reported). A shard that dies midway stores nothing.
  Then SHARD n green m red. Answers the exit status."
  [file]
  (let [{:keys [tests digests tree mg]} (edn/read-string (slurp file))
        t0 (profile/now-ns)]
    (doseq [n (distinct (map (comp symbol namespace) tests))] (require n))
    ;; armed as root arms them at boot, so a proof counts the same contracted calls here as in an agent's test child (docs/cost.md#counting-calls)
    (contract/instrument! contract/boot-nss)
    (let [;; each test under the one bound an agent's run has (seon.proofs/run-tests!): one verdict wherever it runs
          reports (volatile! [])
          ran (proofs/run-tests! tests digests
                                 (fn [x] (cond (:seon.test/started x) (println "started" (:seon.test/started x))
                                               (:seon.test/output x) (print (:seon.test/output x))
                                               (:seon.test/report x) (let [m (:seon.test/report x)]
                                                                        ;; a failing assertion, as the results keep it (seon.proofs/failures): by the one printer
                                                                        (when (#{:fail :error} (:type m))
                                                                          (vswap! reports conj (cond-> {:type (:type m) :var (str "#'" (:test x))}
                                                                                                 (:message m) (assoc :message (str (:message m)))
                                                                                                 (contains? m :expected) (assoc :expected (print/text (:expected m) (:part print/budgets)))
                                                                                                 (contains? m :actual) (assoc :actual (print/text (:actual m) (:part print/budgets)))
                                                                                                 ;; a cost proof's verdict, as data, so its reused red reports it (seon.proofs/costs)
                                                                                                 (and (map? (:actual m)) (:verdict (:actual m))) (assoc :cost (select-keys (:actual m) [:rows :verdict]))))))
                                               ;; a test's end, so bin/stalls never names one that finished
                                               :else (do (println "ended" (:test x))
                                                         (when-not (proofs/green? x) (println "RED" (:test x) (pr-str (select-keys x [:pass :fail :error :message]))))))
                                   (flush))
                                 (config/value :seon.config.agent/test-ticks)
                                 (fx/test-budgets tests))
          ;; the tree the tests read is the plan's, still, after the run: an edit during the run (a test
          ;; reading files reads it) is not what the digests name. The plan's tree is the before (one
          ;; tree-id per shard, not two)
          same? (= tree (fx/tree-id (seon.config/checkout)))
          ;; the shard's verdicts, their failures and each test's function costs, cached
          unstored (when same?
                     (let [cost-of (proofs/costs {:reports @reports})]
                       (record-run! {:id (str "bin/test " tree " " (random-uuid)) :tree tree}
                                    (into {} (for [[t r] ran] [t (cond-> r (cost-of t) (assoc :cost (cost-of t)))]))
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
                        (record-run! {:id (str "bin/test " tree " " (random-uuid)) :tree tree} mg-ran mg-failures))
          red (+ (count (remove proofs/green? (vals ran))) (count mg-failures))]
      (when-not same? (println "the tree changed since the plan: no result stored"))
      (doseq [u (concat unstored unstored-mg)] (println "UNRECORDED" (:key u) (:message u)))
      (println (str "SHARD " (+ (count ran) (count mg-ran)) " tests, " (- (+ (count ran) (count mg-ran)) red) " green, " red " red, in "
                    (quot (- (profile/now-ns) t0) 1000000) " ms"))
      (if (and (zero? red) (empty? unstored) (empty? unstored-mg)) 0 1))))

(defn plan-of
  "What bin/prove and bin/test run on the checkout at root (what: tests, checks or both): the
  program's reach read from the kept store at store (program-db), selected by seon.proofs/plan-of
  (the policy a root's service shares). Answers its plan, with :unread, the typed
  :seon.proofs/unread, when the results could not be read (read-results)."
  [root what all? names store]
  (let [unread (volatile! nil)
        p (proofs/plan-of (tests-of root names store) what all? names
                  (some->> (fx/base-commit root) (fx/tree-of root))
                  ;; the verdicts cached at the candidates' digests; a read that failed is the plan's
                  ;; :unread (prove! stops on it), never zero greens
                  (fn [cand]
                    (let [r (read-results cand)]
                      (if (contract/error? r) (do (vreset! unread r) {}) r))))]
    (cond-> p @unread (assoc :unread @unread))))

(defn- prove!
  "The planning part of bin/prove and bin/test (what is tests, checks or both): plan-of's tests
  are dealt into out's shards (write-shards!), and its bin/check steps are written to out/steps
  (names, one line) and out/steps.edn ({:tree :digests {step digest}}) for bin/prove to run;
  out/tests.edn ({:tree :digests {test digest}}) names each candidate test's digest;
  out/binary names the selected keys that reach target/seon, out/steps-binary the steps among
  them (both empty: nothing to build, nothing to seed), out/stall-ms the stall bound bin/test reads. Answers the exit
  status: 1 when a check step names a function the program does not define."
  [what all? out names]
  (if-let [bad (refused-names names)]
    (do (println (str "TEST FAILED: " bad)) 1)
  (let [t0 (profile/now-ns)
        ;; the selection's time is the plan's: program db, reach graph, digests and the results read
        plan (plan-of (source-tree) what all? names (ts/kept-store! (config/value :seon.config.proofs/program-store) ts/kept-program-digest))]
  (if-let [u (:unread plan)]
    ;; a read that failed is not zero greens: nothing runs, and the run says why
    (do (println (str "TEST FAILED: " (:seon.error/message u)))
        1)
  (let [dir (str "the verdict cache " (config/value :seon.config.proofs/results-dir))
        tests? (not= what "checks") checks? (not= what "tests")
        {:keys [run-tests run-steps reused cand left digests steps missing inputs binary no-test process flaky]} plan
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
        n (write-shards! out (if no-test [] run-tests) digests (:tree inputs) (select-keys steps mg-run))
        ;; what runs and reaches the binary (a test that reaches binary-sh, a step that runs a seon.check
        ;; mode): bin/test builds target/seon only when this is not empty, bin/check seeds only for the steps
        step-bin (filterv proofs/step-needs-binary? run-steps)
        bin-keys (into (vec (filter binary run-tests)) step-bin)
        _ (write! (str out "/binary") (str (str/join " " bin-keys) (when (seq bin-keys) "\n")))
        ;; the stall bound bin/test names a quiet test by (:seon.config.agent/stall-ms)
        _ (write! (str out "/stall-ms") (str (config/value :seon.config.agent/stall-ms) "\n"))
        ;; the process tests' store, made here before any shard starts (shards' roots that each made
        ;; it raced: seon.test-store/process-store), for this schema (seon.test-store/kept-store!)
        _ (when (seq (filter binary run-tests)) (write! (str out "/process-store") (str (ts/make-process-store! (.getCanonicalPath (io/file (config/checkout)))) "\n")))
        _ (write! (str out "/steps-binary") (str (str/join " " step-bin) (when (seq step-bin) "\n")))
        ;; each candidate test's digest: where its verdict is read (seon.fx/test-results, the merge gate)
        _ (when tests? (write! (str out "/tests.edn") (sedn/encode {:tree (:tree inputs) :digests (select-keys digests (filter symbol? (keys cand)))})))]
    (when left
      (println (str "prove: " (count left) " tests and steps that reach a process left out (run them by name:"
                    " bin/test NS, bin/check MODE, or the full run): " (str/join " " (map str left)))))
    (when (= :merge all?)
      (println (str "prove: a merge's verification: " (count (filter (set process) (concat run-tests run-steps)))
                    " keys that reach a process run (no green at their digest), 0 left out")))
    ;; a key green and red at one digest: red, never run again until a change moves its digest;
    ;; out/flaky names each, and bin/test ends red on it after its shards ran
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
  unless the step ran on a binary (seon.proofs/step-needs-binary?) built from another tree
  than the plan's (target/seon.tree, bin/build's): an edit between the plan and the build. A red one is not
  stored, so it runs again. Answers the exit status: 1 when a step ran red or was not stored."
  [out]
  (let [{:keys [tree digests]} (edn/read-string (slurp (str out "/steps.edn")))
        f (io/file out "green")
        green (set (when (.isFile f) (map #(str/replace % #"^check-" "") (str/split (str/trim (slurp f)) #"\s+"))))
        red (sort (remove green (keys digests)))
        built (let [b (io/file (config/checkout) "target/seon.tree")] (when (.isFile b) (str/trim (slurp b))))
        ;; a step that runs a seon.check mode ran on target/seon: kept only if built from the plan's tree;
        ;; laws, lmdb-kill and lmdb-sem ran no mode, so no binary's tree is theirs to compare
        keep? (fn [k] (and (green k) (or (= tree built) (not (proofs/step-needs-binary? k)))))
        unstored (do (when (and (not= tree built) (some #(and (green %) (proofs/step-needs-binary? %)) (keys digests)))
                       (println "prove: the binary was built from another tree than the plan's: no step that ran on it stored"))
                     (record-run! {:id (str "bin/prove steps " tree " " (random-uuid)) :tree tree}
                                  (into {} (for [[k dg] digests :when (keep? k)] [k {:step k :digest dg :pass 1 :fail 0 :error 0}])) {}))]
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
  "The task files' directory relative to the checkout (:seon.config.root/tasks-dir, which the
  config holds resolved against it), so it names the same files in a tree extracted elsewhere."
  [] (str (.relativize (.toPath (io/file (str (config/checkout)))) (.toPath (io/file (config/value :seon.config.root/tasks-dir))))))

(defn- with-wiki
  "Program db db with the tasks and the wiki of the checkout at dir, by root's own loaders
  (seon.task/load-tx, then seon.docs/load-dir-tx: a prose word is a citation when it names a task
  too). A task file that does not read throws its error: no tasks is no answer."
  [db dir]
  ;; the program store is keyed by the program's attributes alone (seon.test-store/program-schema-digest),
  ;; so a kept tree's db may lack an attribute a task or a page holds (:seon.task/citations): the ones it
  ;; lacks are added to this value first, as seon.db/install-missing! adds them to a store a root opens
  (let [db (d/db-with db (vec (remove #(contains? (:schema db) (:db/ident %)) pdb/schema)))
        tx (task/load-tx db (my.root/task-files (str dir "/" (tasks-dir))))
        _ (when (contract/error? tx) (throw (ex-info (:seon.error/message tx) tx)))
        db (d/db-with db tx)]
    (d/db-with db (docs/load-dir-tx db dir "docs" {}))))

(defn- health!
  "The health step (docs/health.md#checks-are-callers-of-seon): what this checkout caused since its
  base (seon.fx/base-commit), branch-health of classes. The head is the working tree's program db
  and the base the base tree's, both kept in the program store (program-db: a tree it never saw is
  the nearest kept tree with the changed files refreshed), each with its tasks and wiki (with-wiki):
  it costs what the trees differ by and two wiki loads, never a root booted or the program imported
  whole. One line per row the branch caused, one per attention row, the report, and HEALTH OK or
  HEALTH FAILED. Answers the exit status."
  [classes]
  (let [root (str (config/checkout))
        base (fx/base-commit root)]
    (if-not base
      (do (println (str "HEALTH FAILED: no base: " root " has no commit before this one on " (config/value :seon.config.source/main))) 1)
      (let [store (ts/kept-store! (config/value :seon.config.proofs/program-store) ts/kept-program-digest)
            head (with-wiki (program-db root store) root)
            ;; the base's tasks and wiki, as git holds them, in a directory of this run's own, removed once read
            dir (str root "/target/tmp/health-base-" base "-" (random-uuid))
            based (try (with-wiki (program-db root store (fx/tree-of root base))
                                  (fx/checkout-at root base ["docs" (tasks-dir)] dir))
                       (finally (fx/remove-tree! dir)))
            {:keys [caused attention checks]} (branch-health based head classes)
            h (problems/health head)
            open (reduce + 0 (for [c (or (seq classes) (sort problems/caused-classes))] (get-in h [:classes c :n] 0)))]
        (doseq [r caused] (println (name (:class r)) (:message r)))
        (doseq [r attention] (println "attention" (name (:class r)) (:message r)))
        (println (pr-str {:base base :caused (count caused) :attention (count attention) :open-in-head open :checks checks}))
        (let [ok (every? val checks)]
          (println (if ok "HEALTH OK" "HEALTH FAILED"))
          (if ok 0 1))))))

(defn -main [& args]
  (let [[flag & more] (config/without-overrides args)]
    (System/exit
      (case flag
        ;; --plan WHAT OUT [--all] [NS ...]: bin/test's and bin/prove's planning part
        "--plan" (let [[what out & names] more all? (case (first names) "--all" true "--merge" :merge false)]
                   (prove! what all? out (if all? (rest names) names)))
        "--shard" (shard! (first more))
        "--store-steps" (store-steps! (first more))
        ;; --health [CLASS ...]: bin/check's health step
        "--health" (health! (map keyword more))))))
