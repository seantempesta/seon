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
  "Namespace symbols of test/**/*_test.clj(c)."
  []
  (->> (file-seq (io/file "test"))
       (map #(.getPath %))
       (filter #(re-find #"_test\.cljc?$" %))
       (map #(-> % (str/replace #"^test/" "") (str/replace #"\.cljc?$" "") (str/replace "/" ".") (str/replace "_" "-") symbol))
       sort))

;; ---- reach -----------------------------------------------------------------------------

(defn program-db
  "A test-store branch's db holding src and test of the checkout at root as program facts
  (seon.source/import-tx: analyzed, not run)."
  [root]
  (let [files (into {} (filter #(str/ends-with? (key %) ".clj")) (:value (fx/read-source {:req {:root root :dirs ["src" "test"]}})))
        db (ts/test-db)]
    (d/db-with db (source/import-tx db files))))

(defn- tests-of
  "{test digest} of the named test namespaces (or every one) on the checkout at root: its
  program db (program-db) and inputs, and the db itself."
  [root names]
  (let [nss (set (if (seq names) (map symbol names) (test-namespaces)))
        db (program-db root)
        in (fx/inputs root)]
    {:db db :inputs in
     :digests (into {} (filter #(nss (symbol (namespace (key %))))) (program/reach-digests db in))}))

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
          ran (proofs/run-tests! tests digests
                                 (fn [x] (cond (:seon.test/started x) (println "started" (:seon.test/started x))
                                               (:seon.test/output x) (print (:seon.test/output x))
                                               (:seon.test/report x) nil
                                               (not (proofs/green? x)) (println "RED" (:test x) (pr-str (select-keys x [:pass :fail :error :message]))))
                                   (flush)))
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

(defn- prove!
  "The planning part of bin/prove and bin/test (what is tests, checks or both): the tests of
  names (or all) with no green at their digest (seon.proofs/plan; all? every one) are dealt
  into out's shards (write-shards!), and the bin/check steps with none are written to out/steps
  (names, one line) and out/steps.edn ({:tree :digests {step digest}}) for bin/prove to run. Answers the exit
  status: 1 when a check step names a function the program does not define."
  [what all? out names]
  (let [root (System/getProperty "user.dir")
        t0 (profile/now-ns)
        {:keys [db inputs digests]} (tests-of root names)
        dir (config/value :seon.config.source/test-results-dir)
        tests? (not= what "checks") checks? (not= what "tests")
        {steps :digests missing :missing} (when checks? (proofs/step-digests #(program/reach-digests db inputs %) inputs))
        cand (merge (when tests? digests) steps)
        {:keys [run reused]} (proofs/plan nil cand (if all? {} (proofs/greens dir (vec (vals cand)))) #{})
        {run-steps true run-tests false} (group-by string? run)
        n (write-shards! out (vec run-tests) digests (:tree inputs))]
    (println (str "prove: " (count cand) " keys (" (if tests? (count digests) 0) " tests, " (count steps) " check steps), "
                  (count reused) " green at their digest (reused), " (count run-tests) " tests to run in " (if (seq run-tests) n 0)
                  " shards, " (count run-steps) " steps to run; selected in " (quot (- (profile/now-ns) t0) 1000000) " ms; results " dir))
    (when checks?
      (write! (str out "/steps") (str (str/join " " run-steps) (when (seq run-steps) "\n")))
      (write! (str out "/steps.edn") (sedn/encode {:tree (:tree inputs) :digests (select-keys steps run-steps)}))
      (when (seq run-steps) (println "prove: check steps to run:" (str/join " " run-steps)))
      (when (seq missing)
        (println "prove: check steps naming a function the program does not define (seon.proofs/check-steps):" (str/join " " missing))))
    (if (seq missing) 1 0)))

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
