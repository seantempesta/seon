(ns seon.operator-nuke-test
  "A nuke of a scratch root wipes that root's own derived state and never a
  cache it shares through a link (owner 2026-09-23); run with babashka:
  bb --config bb.edn --deps-root . --classpath script:src:resources \\
     -e \"(require 'seon.operator-nuke-test) (clojure.test/run-tests 'seon.operator-nuke-test)\""
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [seon.operator :as operator]))

(deftest a-scratch-nuke-leaves-linked-shared-caches-intact
  (let [base (fs/create-temp-dir {:prefix "seon-nuke-wipe"})
        shared (fs/path base "shared-target")
        root (fs/path base "root")]
    (try
      (fs/create-dirs (fs/path shared "dev-dependency-classes" "abc"))
      (spit (str (fs/path shared "dev-dependency-classes" "abc" "k.class")) "classes")
      (fs/create-dirs (fs/path root "data" "source" "sha"))
      (fs/create-dirs (fs/path root ".cpcache"))
      (fs/create-dirs (fs/path root "data" "clusters" "default"))
      (spit (str (fs/path root "data" "clusters" "default" "prepl.edn")) "{}")
      (fs/create-sym-link (fs/path root "target") shared)
      (let [result (#'operator/wipe-derived-state! (str root))]
        (is (fs/exists? (fs/path shared "dev-dependency-classes" "abc" "k.class"))
            "the shared class cache behind the link survives")
        (is (fs/sym-link? (fs/path root "target")) "the root's own link survives")
        (is (= ["target"] (:seon.operator/kept-shared result)))
        (is (false? (:seon.operator/repository-root? result)))
        (is (= #{"data/source" ".cpcache" "data/clusters/default/prepl.edn"}
               (set (:seon.operator/deleted result))))
        (is (not (fs/exists? (fs/path root "data" "source"))))
        (is (not (fs/exists? (fs/path root ".cpcache")))))
      (finally (fs/delete-tree base)))))
