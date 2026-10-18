(ns seon.guard-census-test
  "The error law's ratchet (AGENTS.md, Nothing crashes; owner 2026-10-17): every function whose definition changed
  holds no guard. Old guards leave with their code; none enters with a change. `bin/lane commit` runs the same census
  (`seon.guard-census`) in Babashka on each commit's changed functions; this test runs it on a scratch commit."
  (:require [clojure.test :refer [deftest is]]
            [seon.guard-census :as census]))

(defn- texts
  "A reader of path texts by tree-ish from the map `trees` of tree-ish to path to text."
  {:malli/schema [:=> [:cat [:map-of :string [:map-of :string :string]]] ifn?]}
  [trees]
  (fn [tree path] (get-in trees [tree path])))

(def ^:private producer
  "(ns p.core)\n(defn plain {:malli/schema [:=> [:cat] :int]} [] 1)\n(defn fallible {:malli/schema [:=> [:cat] [:or :int :seon.error/value]]} [] 1)\n")

(deftest a-changed-function-holds-no-guard
  ;; only the functions whose form changed from the base are judged; unchanged guards wait for their change
  (let [before "(ns a.core (:require [p.core :as p]))\n(defn old [] (try 1 (catch Exception _ nil)))\n(defn same [] 1)\n"
        after (str before "(defn added [] (try 1 (catch Throwable _ nil)))\n"
                   "(defn edge [] (try 1 (catch java.io.IOException _ nil)))\n"
                   "(defn entrant {:seon/entrant true} [] (try 1 (catch Exception _ nil)))\n"
                   "(defn rechecks [] (let [v (p/plain)] (when (:seon.error/type v) v)))\n"
                   "(defn handles [] (let [v (p/fallible)] (when (:seon.error/type v) v)))\n")
        found (census/census (texts {"base" {"src/a/core.clj" before "src/p/core.clj" producer}
                                     "tree" {"src/a/core.clj" after "src/p/core.clj" producer}})
                             "tree" "base" ["src/a/core.clj"])]
    (is (= '#{a.core/added a.core/rechecks} (set (keys found)))
        "a new general catch and a re-check of a contract without an error variant are guards; an interop edge's own catch, the entrant boundary and a declared variant's branch are not")))
