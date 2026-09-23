(ns seon.dev.citations-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [seon.dev.citations :as citations]))

(def ^:private source "(ns demo.core)\n\n(defn helper\n  [x]\n  (inc x))\n\n(def other 1)\n")

(def ^:private skill
  (str "`helper` and `other` (`demo/core.clj:3`, `:7`); `core.clj:4` (`helper`).\n"
       "The note `alpha` (`notes.txt:2`) and `demo/gone.clj:1`.\n"
       "```\n`other` (`demo/core.clj:1`) inside a fence is no citation\n```\n"))

(defn- failures [root]
  (mapv (juxt :line :text :why) (citations/check (str root) ["skill.md"])))

(deftest a-drifted-anchor-fails-by-name-and-a-current-one-passes
  (let [root (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/path root "demo"))
      (spit (str (fs/path root "demo/core.clj")) source)
      (spit (str (fs/path root "notes.txt")) "zero\nalpha\n")
      (spit (str (fs/path root "skill.md")) skill)
      (is (= [[2 "demo/gone.clj:1" "demo/gone.clj does not exist"]] (failures root)))
      ;; shift every definition down one line: each named anchor now drifts
      (spit (str (fs/path root "demo/core.clj")) (str ";; moved\n" source))
      (spit (str (fs/path root "notes.txt")) "zero\n\nalpha\n")
      (is (= [[1 "demo/core.clj:3" "`helper` is at 4-6"]
              [1 ":7" "`other` is at 8-8"]
              [2 "notes.txt:2" "`alpha` is at line 3"]
              [2 "demo/gone.clj:1" "demo/gone.clj does not exist"]]
             (failures root)))
      (finally (fs/delete-tree root)))))
