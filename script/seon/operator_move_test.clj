(ns seon.operator-move-test
  "A move to HEAD keeps the store and every shared cache, and its archive keys
  the dependency class cache exactly as a checkout does; run with babashka:
  bb --config bb.edn --deps-root . --classpath script:src:resources \\
     -e \"(require 'seon.operator-move-test) (clojure.test/run-tests 'seon.operator-move-test)\""
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.dev.dependency-digest :as dependency-digest]
            [seon.operator :as operator])
  (:import [java.util.concurrent TimeUnit]))

(defn- git-stage-of-commit
  "Git's own `ls-files --stage` of HEAD's gitlinks, read through a scratch index."
  [index]
  (let [repository (operator/repository-root)
        run (fn [argv]
              (let [builder (doto (ProcessBuilder. ^java.util.List argv)
                              (.directory (fs/file repository))
                              (.redirectErrorStream true))
                    _ (.put (.environment builder) "GIT_INDEX_FILE" (str index))
                    child (.start builder)
                    out (future (slurp (.getInputStream child)))]
                (is (.waitFor child 10000 TimeUnit/MILLISECONDS) (str argv " exits within 10 s"))
                (is (zero? (.exitValue child)) (str argv))
                @out))]
    (run ["git" "read-tree" "HEAD"])
    (run ["git" "ls-files" "--stage" "--" "reference-code"])))

(deftest an-archive-states-the-pins-git-states-for-its-commit
  (let [base (fs/create-temp-dir {:prefix "seon-move-pins"})]
    (try
      (let [repository (operator/repository-root)
            pins (#'operator/gitlinks repository "HEAD")]
        (is (seq pins))
        (is (= (git-stage-of-commit (fs/path base "index"))
               (#'operator/pins-text pins))
            "the recorded pins are byte-identical to git's own stage listing"))
      (finally (fs/delete-tree base)))))

(deftest a-move-keeps-the-store-and-links-every-shared-cache
  (let [base (fs/create-temp-dir {:prefix "seon-move-share"})
        repository (fs/path base "checkout")
        root (fs/path base "root")
        archive (fs/path root "data" "source" "sha")
        pins [{:path "reference-code/a" :pin (apply str (repeat 40 "a"))}]]
    (try
      (fs/create-dirs (fs/path root "data" "store"))
      (spit (str (fs/path root "data" "store" "datoms")) "store")
      (fs/create-dirs (fs/path repository "target" "dev-dependency-classes" "abc"))
      (spit (str (fs/path repository "target" "dev-dependency-classes" "abc" "k.class")) "classes")
      (fs/create-dirs (fs/path repository ".clj-kondo" ".cache" "v1"))
      (spit (str (fs/path repository ".clj-kondo" ".cache" "v1" "ns.transit.json")) "analysis")
      ;; An archive a JVM ran from before carries its own private kondo cache.
      (fs/create-dirs (fs/path archive ".clj-kondo" ".cache" "v1"))
      (spit (str (fs/path archive ".clj-kondo" ".cache" "v1" "private")) "private")
      (let [links {"target" (str (fs/path repository "target"))
                   ".clj-kondo/.cache" (str (fs/path repository ".clj-kondo" ".cache"))}
            first-pass (#'operator/share-caches! links (str archive) pins)
            second-pass (#'operator/share-caches! links (str archive) pins)]
        (is (= "store" (slurp (str (fs/path root "data" "store" "datoms")))) "the store is untouched")
        (is (= {"target" :linked ".clj-kondo/.cache" :replaced}
               (into {} (map (juxt :path :placed)) (:seon.operator/linked first-pass))))
        (is (= {"target" :kept ".clj-kondo/.cache" :kept}
               (into {} (map (juxt :path :placed)) (:seon.operator/linked second-pass)))
            "a second move reuses the links")
        (is (= "classes" (slurp (str (fs/path archive "target" "dev-dependency-classes" "abc" "k.class"))))
            "the archive reads the checkout's class cache")
        (is (= "analysis" (slurp (str (fs/path archive ".clj-kondo" ".cache" "v1" "ns.transit.json"))))
            "the archive reads the checkout's analyzer cache")
        (is (fs/exists? (fs/path repository "target" "dev-dependency-classes" "abc" "k.class"))
            "the shared class cache survives")
        (is (= (#'operator/pins-text pins)
               (dependency-digest/dependency-pins (str archive)))
            "the dependency digest reads the recorded pins in an archive"))
      (finally (fs/delete-tree base)))))

(deftest pruning-keeps-the-running-and-held-archives-and-never-follows-a-link
  (let [base (fs/create-temp-dir {:prefix "seon-move-prune"})
        root (fs/path base "root")
        outside (fs/path base "outside")
        source (fs/path root "data" "source")
        holder (atom nil)]
    (try
      (doseq [sha ["kept" "held" "old"]] (fs/create-dirs (fs/path source sha "reference-code")))
      ;; Archived pins: one linked by the kept archive, one by nothing.
      (fs/create-dirs (fs/path source "pins" "reference-code-a" "linked-pin"))
      (fs/create-dirs (fs/path source "pins" "reference-code-a" "stale-pin"))
      (fs/create-sym-link (fs/path source "kept" "reference-code" "a")
                          (fs/canonicalize (fs/path source "pins" "reference-code-a" "linked-pin")))
      (fs/create-dirs outside)
      (spit (str (fs/path outside "f")) "outside")
      (fs/create-sym-link (fs/path source "old" "reference-code" "b") outside)
      (spit (str (fs/path root "data" "store")) "store")
      ;; A live process naming `held` as its program, as a launched JVM does.
      (reset! holder (.start (ProcessBuilder.
                              ^java.util.List
                              ["sh" "-c" "cat" (str "-Dseon.repository.root=" (fs/path source "held"))])))
      (let [result (#'operator/prune-archives!
                    (str root) (str (fs/canonicalize (fs/path source "kept"))))]
        (is (= ["old"] (:seon.operator/pruned result)))
        (is (= #{"kept" "held"} (set (:seon.operator/retained result))) "pins is not an archive")
        (is (= ["reference-code-a/stale-pin"] (:seon.operator/pruned-pins result)))
        (is (fs/exists? (fs/path source "pins" "reference-code-a" "linked-pin"))
            "a pin a retained archive links survives")
        (is (= "outside" (slurp (str (fs/path outside "f")))) "a link's target survives")
        (is (= "store" (slurp (str (fs/path root "data" "store"))))))
      (finally
        (when-let [child @holder]
          (.destroy child)
          (.waitFor child 5000 TimeUnit/MILLISECONDS))
        (fs/delete-tree base)))))

(deftest start-head-is-argv-data
  (is (true? (:seon.operator/head? (operator/parse-argv ["start" "--head"]))))
  (is (str/includes? (try (operator/parse-argv ["stop" "--head"]) ""
                          (catch clojure.lang.ExceptionInfo refusal (ex-message refusal)))
                     "only by start")))

(deftest a-root-analysis-cache-follows-the-program-it-replaces
  (let [base (fs/create-temp-dir {:prefix "seon-move-analysis"})
        root (fs/path base "root")
        previous (fs/path base "previous")
        fresh (fs/path base "fresh")]
    (try
      (fs/create-dirs (fs/path previous ".clj-kondo" ".cache" "v1"))
      (spit (str (fs/path previous ".clj-kondo" ".cache" "v1" "seon.a.transit.json")) "program")
      (fs/create-dirs fresh)
      (let [seeded (#'operator/root-analysis-cache! (str root) (str previous))
            kept (#'operator/root-analysis-cache! (str root) (str fresh))
            cache (:seon.operator/analysis-cache seeded)]
        (is (= (str (fs/canonicalize (fs/path previous ".clj-kondo" ".cache")))
               (:seon.operator/seeded seeded))
            "the replaced program's analysis seeds the root's cache")
        (is (= "program" (slurp (str (fs/path cache "v1" "seon.a.transit.json")))))
        (is (= :kept (:seon.operator/seeded kept)) "a later move keeps the root's own cache")
        (is (= "program" (slurp (str (fs/path previous ".clj-kondo" ".cache" "v1" "seon.a.transit.json"))))
            "the seed is copied, never moved"))
      (is (= :empty (:seon.operator/seeded
                     (#'operator/root-analysis-cache! (str (fs/path base "other")) nil)))
          "a root with no prior program starts empty and says so")
      (finally (fs/delete-tree base)))))

(deftest a-move-states-branch-heads-before-its-boot-writes
  (let [with-capture (read-string (#'operator/launch-form
                                   {:seon.boot/cluster-name "default"
                                    :seon.operator/capture-heads "/root/data/store"} 1))
        without (read-string (#'operator/launch-form {:seon.boot/cluster-name "default"} 1))
        symbols (fn [form] (set (filter symbol? (tree-seq coll? seq form))))
        boot-request (fn [form] (some #(when (and (seq? %) (= 'clojure.core/assoc (first %))
                                                  (map? (second (second %))))
                                         (second (second %)))
                                      (tree-seq coll? seq form)))]
    (is (contains? (symbols with-capture) 'seon.cluster.registry/branch-commit-id)
        "the child reads every head from the roster")
    (is (not (contains? (symbols without) 'seon.cluster.registry/branch-commit-id))
        "a start or nuke reads none")
    (is (= "default" (:seon.boot/cluster-name (boot-request with-capture))))
    (is (not (contains? (boot-request with-capture) :seon.operator/capture-heads))
        "boot receives the request without the operator's capture key"))
  (let [form (read-string (pr-str (#'operator/head-restore-form
                                   "/root/data/store"
                                   {:current-src #uuid "6ab327f6-766f-5ea5-8c16-5f386271f129"})))]
    (is (contains? (set (filter symbol? (tree-seq coll? seq form))) 'datahike.api/branch!)
        "a head is restored to its exact commit by branching from it, never force-branch!'s new commit")
    (is (not (contains? (set (filter symbol? (tree-seq coll? seq form))) 'datahike.api/force-branch!)))))
