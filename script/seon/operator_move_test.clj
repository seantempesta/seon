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
      (let [first-pass (#'operator/share-caches! (str repository) (str archive) pins)
            second-pass (#'operator/share-caches! (str repository) (str archive) pins)]
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
      (doseq [sha ["kept" "held" "old"]] (fs/create-dirs (fs/path source sha)))
      (fs/create-dirs outside)
      (spit (str (fs/path outside "f")) "outside")
      (fs/create-sym-link (fs/path source "old" "reference-code") outside)
      (spit (str (fs/path root "data" "store")) "store")
      ;; A live process naming `held` as its program, as a launched JVM does.
      (reset! holder (.start (ProcessBuilder.
                              ^java.util.List
                              ["sh" "-c" "cat" (str "-Dseon.repository.root=" (fs/path source "held"))])))
      (let [result (#'operator/prune-archives!
                    (str root) (str (fs/canonicalize (fs/path source "kept"))))]
        (is (= ["old"] (:seon.operator/pruned result)))
        (is (= #{"kept" "held"} (set (:seon.operator/retained result))))
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
