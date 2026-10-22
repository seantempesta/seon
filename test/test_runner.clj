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
            [seon.edn :as sedn] [seon.error :as error] [seon.fx :as fx] [seon.program :as program] [seon.proofs :as proofs] [seon.source :as source]))

(defn- test-namespaces
  "Namespace symbols of test/**/*_test.clj(c)."
  []
  (->> (file-seq (io/file "test"))
       (map #(.getPath %))
       (filter #(re-find #"_test\.cljc?$" %))
       (map #(-> % (str/replace #"^test/" "") (str/replace #"\.cljc?$" "") (str/replace "/" ".") (str/replace "_" "-") symbol))
       sort))

;; ---- reach -----------------------------------------------------------------------------

(defn- git [root & args] (:out (apply sh/sh "git" "-C" root args)))

(defn- env-input?
  "Is the file at relative path p an input of every test: everything git tracks or would
  track but the source program facts carry (.clj under src and test), task files (data
  root loads, no test's code) and prose (.md)?"
  [p]
  (not (or (str/ends-with? p ".md")
           (str/starts-with? p "resources/seon/tasks/")
           (and (str/ends-with? p ".clj") (or (str/starts-with? p "src/") (str/starts-with? p "test/"))))))

(defn inputs
  "The checkout at root's inputs that have no program facts: {:env (every test's: env-input?
  files and every submodule's pin, Jolt's included) :tree (a test reading files: every file
  and pin)}, digests of relative paths and content, so the same content at another path is
  the same inputs."
  [root]
  (let [pins (filter #(str/starts-with? % "160000 ") (str/split-lines (git root "ls-files" "-s")))
        files (for [p (sort (str/split (git root "ls-files" "--cached" "--others" "--exclude-standard" "-z") #"\u0000"))
                    :let [f (io/file root p)] :when (.isFile f)]
                [p (str p " " (error/digest (slurp f)))])
        digest #(error/digest (str/join "\n" %))]
    {:env (digest (concat pins (keep (fn [[p line]] (when (env-input? p) line)) files)))
     :tree (digest (concat pins (map second files)))}))

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
        in (inputs root)]
    {:db db :inputs in
     :digests (into {} (filter #(nss (symbol (namespace (key %))))) (program/reach-digests db in))}))

(defn- write!
  "text to path through the effect owner's file write (seon.fx/write-file); its declared
  error is thrown with its data, so the run ends red naming the path, never as if written."
  [path text]
  (let [r (fx/write-file {:req {:path path :text text}})]
    (when (:seon.error/type r) (throw (ex-info (str "write failed: " (:seon.error/message r)) r)))
    r))

(defn- write-shards!
  "tests (var symbols) dealt into :seon.config.proofs/jobs shards (seon.proofs/shards), each
  written as out/shard-<i>.edn ({:tests [...] :digests {test digest}}) for bin/test to run, one
  process each. Answers the shard count."
  [out tests digests]
  (doseq [f (.listFiles (io/file out)) :when (re-find #"^shard-" (.getName f))] (.delete f))
  (let [sh (proofs/shards tests (config/value :seon.config.proofs/jobs))]
    (doseq [[i ts] (map-indexed vector sh)]
      (write! (str out "/shard-" i ".edn") (sedn/encode {:tests ts :digests (select-keys digests ts)})))
    (count sh)))

(defn- shard!
  "One shard bin/test runs (out/shard-<i>.edn): its namespaces required, its tests run
  (seon.proofs/run-tests!), each result stored at its digest; each test named as it starts,
  each red one printed (RED), then SHARD n green m red. Answers the exit status."
  [file]
  (let [{:keys [tests digests]} (edn/read-string (slurp file))
        t0 (profile/now-ns)]
    (doseq [n (distinct (map (comp symbol namespace) tests))] (require n))
    (let [ran (proofs/run-tests! tests digests (config/value :seon.config.source/test-results-dir)
                                 (fn [x] (cond (:seon.test/started x) (println "started" (:seon.test/started x))
                                               (:seon.test/output x) (print (:seon.test/output x))
                                               (:seon.test/report x) nil
                                               (not (proofs/green? x)) (println "RED" (:test x) (pr-str (select-keys x [:pass :fail :error :message]))))
                                   (flush)))
          red (count (remove proofs/green? (vals ran)))]
      (println (str "SHARD " (count ran) " tests, " (- (count ran) red) " green, " red " red, in "
                    (quot (- (profile/now-ns) t0) 1000000) " ms"))
      (if (zero? red) 0 1))))

(defn- prove!
  "The planning part of bin/prove and bin/test (what is tests, checks or both): the tests of
  names (or all) with no green at their digest (seon.proofs/plan; all? every one) are dealt
  into out's shards (write-shards!), and the bin/check steps with none are written to out/steps
  (names, one line) and out/steps.edn ({step digest}) for bin/prove to run. Answers the exit
  status: 1 when a check step names a function the program does not define."
  [what all? out names]
  (let [root (System/getProperty "user.dir")
        t0 (profile/now-ns)
        {:keys [db inputs digests]} (tests-of root names)
        dir (config/value :seon.config.source/test-results-dir)
        tests? (not= what "checks") checks? (not= what "tests")
        {steps :digests missing :missing} (when checks? (proofs/step-digests #(program/reach-digests db inputs %) inputs))
        cand (merge (when tests? digests) steps)
        {:keys [run reused]} (proofs/plan nil cand (if all? {} (proofs/greens dir (vals cand))) #{})
        {run-steps true run-tests false} (group-by string? run)
        n (write-shards! out (vec run-tests) digests)]
    (println (str "prove: " (count cand) " keys (" (if tests? (count digests) 0) " tests, " (count steps) " check steps), "
                  (count reused) " green at their digest (reused), " (count run-tests) " tests to run in " (if (seq run-tests) n 0)
                  " shards, " (count run-steps) " steps to run; selected in " (quot (- (profile/now-ns) t0) 1000000) " ms; results " dir))
    (when checks?
      (write! (str out "/steps") (str (str/join " " run-steps) (when (seq run-steps) "\n")))
      (write! (str out "/steps.edn") (sedn/encode (select-keys steps run-steps)))
      (when (seq run-steps) (println "prove: check steps to run:" (str/join " " run-steps)))
      (when (seq missing)
        (println "prove: check steps naming a function the program does not define (seon.proofs/check-steps):" (str/join " " missing))))
    (if (seq missing) 1 0)))

(defn- store-steps!
  "bin/prove's last part: each step of out/steps.edn that bin/check named green in out/green
  (check-<step>, space separated) stored green at its digest; a red one is not stored, so it
  runs again. Answers the exit status: 1 when a step ran red."
  [out]
  (let [dir (config/value :seon.config.source/test-results-dir)
        steps (edn/read-string (slurp (str out "/steps.edn")))
        f (io/file out "green")
        green (set (when (.isFile f) (map #(str/replace % #"^check-" "") (str/split (str/trim (slurp f)) #"\s+"))))
        red (sort (remove green (keys steps)))]
    (doseq [[k dg] steps :when (green k)
            :let [w (proofs/store! dir {:step k :digest dg :pass 1 :fail 0 :error 0})]]
      (when (:seon.error/type w) (throw (ex-info (str "step " k " green, its result not stored: " (:seon.error/message w)) w))))
    (println (str "prove: " (count (filter green (keys steps))) " check steps green and stored, " (count red) " red"
                  (when (seq red) (str ": " (str/join " " red)))))
    (if (empty? red) 0 1)))

(defn -main [& args]
  (let [[flag & more] args]
    (System/exit
      (case flag
        ;; --plan WHAT OUT [--all] [NS ...]: bin/test's and bin/prove's planning part
        "--plan" (let [[what out & names] more all? (= "--all" (first names))]
                   (prove! what all? out (if all? (rest names) names)))
        "--shard" (shard! (first more))
        "--store-steps" (store-steps! (first more))))))
