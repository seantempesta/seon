(ns test-runner
  "bin/test: loads every *_test namespace under test/ (or the namespaces named on the
  command line) and runs its deftests; exits 1 when any fails or errors.

  bin/test --reach [NS ...] (task seon.program/tests-run-by-reach): runs only the tests with
  no green result at their reach digest (seon.program/reach-digests over src and test
  imported as program facts), reusing the rest, and stores each result it runs in the
  results directory every checkout and worktree shares (:seon.config.source/test-results-dir),
  one <digest>.edn per result. A merge runs it: what the change reaches, minus what is
  green at its digest."
  (:require [seon.test-store :as ts] [seon.profile :as profile] [clojure.java.io :as io] [clojure.java.shell :as sh] [clojure.string :as str] [clojure.edn :as edn]
            [clojure.test :as t] [datahike.api :as d] [seon.config :as config] [seon.db :as pdb]
            [seon.edn :as sedn] [seon.error :as error] [seon.fx :as fx] [seon.program :as program] [seon.source :as source]))

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

(defn- green? [r] (and (zero? (:fail r)) (zero? (:error r))))

(defn- result-path [dir digest] (str dir "/" digest ".edn"))

(defn plan
  "Of digests ({test digest}): {:reused {test result} (green at its digest in dir) :run [test
  ...] (the rest, sorted)}."
  [digests dir]
  (let [found (into {} (for [[t dg] digests :let [f (io/file (result-path dir dg))] :when (.isFile f)
                             :let [r (edn/read-string (slurp f))] :when (green? r)]
                         [t r]))]
    {:reused found :run (vec (sort (remove found (keys digests))))}))

(defn run-vars!
  "Run tests (var symbols), each in its namespace (required), and store each result
  {:test :digest :pass :fail :error :ms} at its digest in dir. Answers {test result}."
  [tests digests dir]
  (.mkdirs (io/file dir))
  (doseq [n (distinct (map (comp symbol namespace) tests))] (require n))
  (into {} (for [tst tests
                 :let [rc (ref t/*initial-report-counters*)
                       t0 (profile/now-ns)
                       _ (binding [t/*report-counters* rc] (t/test-vars [(resolve tst)]))
                       r (assoc (select-keys @rc [:pass :fail :error]) :test tst :digest (digests tst)
                                :ms (quot (- (profile/now-ns) t0) 1000000))]]
             (do (fx/write-file {:req {:path (result-path dir (digests tst)) :text (sedn/encode r)}})
                 [tst r]))))

(defn- reach-main
  "bin/test --reach [NS ...]: the named test namespaces' (or all) tests not green at their
  digest run; exits 1 when one is red."
  [names]
  (let [root (System/getProperty "user.dir")
        t0 (profile/now-ns)
        nss (set (if (seq names) (map symbol names) (test-namespaces)))
        db (program-db root)
        digests (into {} (filter #(nss (symbol (namespace (key %))))) (program/reach-digests db (inputs root)))
        dir (config/value :seon.config.source/test-results-dir)
        {:keys [reused run]} (plan digests dir)
        _ (println (str "reach: " (count digests) " tests, " (count reused) " green at their digest (reused), "
                        (count run) " to run; digests in " (quot (- (profile/now-ns) t0) 1000000) " ms; results " dir))
        ran (run-vars! run digests dir)
        red (sort (keep (fn [[tst r]] (when-not (green? r) tst)) ran))]
    (println (str "reach: ran " (count ran) ", " (- (count ran) (count red)) " green, " (count red) " red"
                  (when (seq red) (str ": " (str/join " " red)))))
    (System/exit (if (empty? red) 0 1))))

(defn -main [& args]
  (if (= "--reach" (first args))
    (reach-main (rest args))
    (let [nss (if (seq args) (map symbol args) (test-namespaces))]
      (apply require nss)
      (let [{:keys [fail error]} (apply t/run-tests nss)]
        (System/exit (if (zero? (+ fail error)) 0 1))))))
