(ns test-runner
  "bin/test's and bin/prove's runner (task seon.proofs/prove-runs-what-a-change-reaches):
  src and test imported as program facts (program-db), each test keyed by its reach digest
  (seon.program/reach-digests) and each bin/check step by its functions' (seon.proofs/
  check-steps); seon.proofs/plan selects what has no green result at its digest.
    --plan WHAT OUT [--all] [NS ...]  the selection: tests dealt into OUT/shard-<i>.edn, steps
                                      into OUT/steps (WHAT: tests, checks or both)
    --shard FILE                      one shard's tests run here, each result stored
    --store-steps OUT                 the steps bin/check ran green stored at their digests
  bin/test runs each shard in a process of its own, at once (a fork of this runtime cannot
  connect to a store: Datahike's connection state does not survive fork)."
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.java.io :as io] [clojure.java.shell :as sh] [clojure.string :as str] [clojure.edn :as edn]
            [datahike.api :as d] [seon.config :as config] [seon.db :as pdb]
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

(defn program-db
  "A test-store branch's db holding src and test of the checkout at root as program facts
  (seon.source/import-tx: analyzed, not run)."
  [root]
  (let [files (into {} (filter #(str/ends-with? (key %) ".clj")) (:value (fx/read-source {:req {:root root :dirs ["src" "test"]}})))
        db (ts/test-db)]
    (d/db-with db (source/import-tx db files))))

(defn- tests-of
  "{test digest} of the named test namespaces (or every one) on the checkout at root, the
  ones among them that reach a process of their own (:process, seon.program/reach-index), its
  program db (program-db) and inputs."
  [root names]
  (let [nss (set (if (seq names) (map symbol names) (test-namespaces root)))
        db (program-db root)
        in (fx/inputs root)
        idx (into {} (filter #(nss (symbol (namespace (key %))))) (program/reach-index db in))]
    {:db db :inputs in
     :digests (update-vals idx :digest)
     :process (set (keep (fn [[t v]] (when (:process? v) t)) idx))}))

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

(defn shards
  "tests (var symbols) dealt into at most jobs shards, a namespace's tests (and so its fixtures)
  in one, the largest namespace first into the smallest shard; no empty shard."
  [tests jobs]
  (let [by-ns (sort-by (juxt (comp - count val) (comp str key)) (group-by (comp symbol namespace) tests))]
    (vec (remove empty? (reduce (fn [sh [_ ts]] (update sh (apply min-key #(count (sh %)) (range (count sh))) into ts))
                                (vec (repeat (max 1 (min jobs (count by-ns))) [])) by-ns)))))

(defn- write-shards!
  "tests (var symbols) dealt into :seon.config.proofs/jobs shards (shards), each written as
  out/shard-<i>.edn ({:tests [...] :digests {test digest} :tree t}, t the planned tree's id)
  for bin/test to run, one process each (bin/test removes the last run's first). Answers the
  shard count."
  [out tests digests tree]
  (let [sh (shards tests (config/value :seon.config.proofs/jobs))]
    (doseq [[i ts] (map-indexed vector sh)]
      (write! (str out "/shard-" i ".edn") (sedn/encode {:tests ts :digests (select-keys digests ts) :tree tree})))
    (count sh)))

(defn- shard!
  "One shard bin/test runs (out/shard-<i>.edn): its namespaces required, its tests run
  (seon.proofs/run-tests!), each test named as it starts and each red one printed (RED); then
  its greens stored at their digests (seon.proofs/store-greens!), unless the tree changed
  since the plan was made (an edit during the run: what ran is not what the digests name, so
  nothing is kept, and the run stays as reported). A shard that dies midway stores nothing.
  Then SHARD n green m red. Answers the exit status."
  [file]
  (let [{:keys [tests digests tree]} (edn/read-string (slurp file))
        t0 (profile/now-ns)]
    (doseq [n (distinct (map (comp symbol namespace) tests))] (require n))
    (let [same? (= tree (fx/tree-id (System/getProperty "user.dir")))
          ran (proofs/run-tests! tests digests
                                 (fn [x] (cond (:seon.test/started x) (println "started" (:seon.test/started x))
                                               (:seon.test/output x) (print (:seon.test/output x))
                                               (:seon.test/report x) nil
                                               (not (proofs/green? x)) (println "RED" (:test x) (pr-str (select-keys x [:pass :fail :error :message]))))
                                   (flush)))
          unstored (when same? (proofs/store-greens! #(fx/write-file {:req %}) (config/value :seon.config.source/test-results-dir) ran))
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
  process (automatic). Answers {:run-tests :run-steps :reused :cand :left (the process keys
  left out) :digests :steps :missing :inputs}."
  [root what all? names]
  (let [{:keys [db inputs digests process]} (tests-of root names)
        dir (config/value :seon.config.source/test-results-dir)
        tests? (not= what "checks") checks? (not= what "tests")
        ;; the steps' functions walked once: their digests and which boot a root or a process
        step-idx (when checks? (program/reach-index db inputs (into {} (filter (comp vector? val)) proofs/check-steps)))
        {steps :digests missing :missing} (when checks? (proofs/step-digests #(update-vals (select-keys step-idx (keys %)) :digest) inputs))
        process (into (if tests? process #{}) (keep (fn [[k v]] (when (:process? v) k))) step-idx)
        named? (or all? (seq names))
        all-cand (merge (when tests? digests) steps)
        cand (if named? all-cand (automatic all-cand process))
        {:keys [run reused]} (proofs/plan nil cand (if all? {} (proofs/greens dir (vec (vals cand)))) #{})
        {run-steps true run-tests false} (group-by string? run)]
    {:run-tests (vec run-tests) :run-steps (vec run-steps) :reused reused :cand cand
     :left (when-not named? (vec (sort-by str (filter all-cand process))))
     :digests digests :steps steps :missing missing :inputs inputs}))

(defn- prove!
  "The planning part of bin/prove and bin/test (what is tests, checks or both): plan-of's tests
  are dealt into out's shards (write-shards!), and its bin/check steps are written to out/steps
  (names, one line) and out/steps.edn ({:tree :digests {step digest}}) for bin/prove to run. Answers the exit
  status: 1 when a check step names a function the program does not define."
  [what all? out names]
  (if-let [bad (refused-names names)]
    (do (println (str "TEST FAILED: " bad)) 1)
  (let [t0 (profile/now-ns)
        dir (config/value :seon.config.source/test-results-dir)
        tests? (not= what "checks") checks? (not= what "tests")
        {:keys [run-tests run-steps reused cand left digests steps missing inputs]} (plan-of (source-tree) what all? names)
        n (write-shards! out run-tests digests (:tree inputs))]
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
  unless the binary the steps ran was built from another tree than the plan's
  (target/seon.tree, bin/build's): an edit between the plan and the build. A red one is not
  stored, so it runs again. Answers the exit status: 1 when a step ran red or was not stored."
  [out]
  (let [dir (config/value :seon.config.source/test-results-dir)
        {:keys [tree digests]} (edn/read-string (slurp (str out "/steps.edn")))
        f (io/file out "green")
        green (set (when (.isFile f) (map #(str/replace % #"^check-" "") (str/split (str/trim (slurp f)) #"\s+"))))
        red (sort (remove green (keys digests)))
        built (let [b (io/file "target/seon.tree")] (when (.isFile b) (str/trim (slurp b))))
        unstored (if (= tree built)
                   (proofs/store-greens! #(fx/write-file {:req %}) dir (into {} (for [[k dg] digests :when (green k)] [k {:step k :digest dg :pass 1 :fail 0 :error 0}])))
                   (do (println "prove: the binary was built from another tree than the plan's: no step stored") []))]
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
