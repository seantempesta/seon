(ns seon.sci.open-derivers-test
  "The open `seon.cache` derivers: functions whose `:seon.fn/calls` hold `seon.cache/lookup` or
  `seon.cache/lookup-within` and that the program's reach index (`seon.sci.eval/reach-index`) leaves open, because
  they reach a call that resolves a name at run time. Their derivations cannot be keyed by the code they reach.
  The closed-runtime plan (`docs/research/agent-platform/2026-10-19/closed-runtime-hybrid.md`) lowers the set one
  phase at a time; each phase's commit edits `open-derivers` and adds its row to `converted`."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.sci.eval]
            [seon.sci.runtime :as runtime]))

(def ^:private open-derivers
  "The open cache derivers of the program, exactly: a name joining it is a new open derivation, a name leaving it is
  a phase's progress, and both edit this set in the commit that causes them."
  '#{seon.cluster/branch-declaration-changes seon.cluster/derivation-keys seon.cluster/stored-derivation-schema-keys
     seon.cluster.agent/basis-valid? seon.cluster.agent/branch-admission-end seon.cluster.agent/branch-end
     seon.cluster.source/file-proof seon.cluster.source/member-contributions seon.cluster.source/members-of
     seon.cluster.source/merge-base seon.cluster.source/publication seon.cluster.source/unmerged-count
     seon.config/effective seon.context/conflicts seon.context/declared-tests seon.db/behavior-digest
     seon.db/content-projection seon.db/request-view seon.embed/corpus seon.fn/declared-edge-attributes
     seon.fn/gate-edges seon.health/problems-unit seon.health/profile-unit seon.health/replaced-roots
     seon.program/authored-shapes seon.program/content-digest seon.program/own-changed-identities
     seon.schema/build-projection seon.schema/declaration-projection
     seon.schema/direct-references seon.sci.eval/derive-program-context seon.sci.eval/loaded-authored
     seon.sci.eval/loaded-digests seon.sci.eval/overlay-plan seon.sci.eval/program-context
     seon.sci.eval/reach-index seon.sci.eval/record-refusals-once! seon.sci.runtime-test/shaped-main
     seon.task/task-basis seon.task/tasks-tx seon.task/tasks-tx-in
     seon.test/destructive-reach seon.test/host seon.test/observable-changes seon.test.runner/current-results
     seon.test.runner/reach-attribute seon.test.runner/reach-entries seon.test.runner/reach-graph
     seon.test.runner/reached-graph seon.test.runner/retain-results! seon.test.runner/tests-reaching})

(def ^:private converted
  "Each landed phase's converted functions: none of them calls a run-time resolution
  (`seon.sci.eval/run-time-resolution`) directly any more."
  ;; P2's functions are deleted seams: built from names, never quoted as symbols, so the program keeps no reference
  ;; to a function that no longer exists (a deleted function with a surviving referrer refuses the deletion).
  [{:phase :P2
    :functions (into #{} (map #(symbol "seon.db" %))
                     ["program-definition-digest" "program-behavior-digest" "stored-namespace-context"
                      "program-shapes-in" "program-canonical-entity"])}
   {:phase :P3b
    :functions (into #{} (map #(symbol "seon.program" %)) ["derived-shape" "derived-program-attributes"])}
   {:phase :P1
    :functions (into #{} (map #(symbol "seon.db" %))
                     ["call-preparation-snapshot" "call-preparation-report-snapshot" "call-preparation-plan-for"])}
   {:phase :P3a-1
    :functions (into #{} (map #(symbol "seon.schema" %)) ["canonical-schema-entities"])}])

(defn- open-cache-derivers
  "The cache derivers of `database`'s program that its reach index leaves open."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:set :qualified-symbol]]}
  [database]
  (let [closed (::runtime/closed (@#'seon.sci.eval/reach-index database))]
    (into #{} (remove closed)
          (db/q '[:find [?sym ...] :in $ [?callee ...] :where [?f :seon.fn/calls ?callee] [?f :seon.fn/sym ?sym]]
                database '[seon.cache/lookup seon.cache/lookup-within]))))

(deftest open-cache-derivers-are-exactly-the-recorded-set
  (let [open (open-cache-derivers (db/db))]
    (is (= {:opened #{} :closed #{}}
           {:opened (set/difference open open-derivers) :closed (set/difference open-derivers open)})
        "a newly opened deriver is a regression; a newly closed one is recorded by removing it from open-derivers")))

(deftest converted-functions-resolve-no-name-at-run-time
  (let [database (db/db)
        opening @#'seon.sci.eval/run-time-resolution]
    (is (= [] (into [] (keep (fn [{:keys [phase functions]}]
                               (let [still (into (sorted-map)
                                                 (keep (fn [sym]
                                                         (let [calls (db/q '[:find [?callee ...] :in $ ?sym
                                                                             :where [?f :seon.fn/sym ?sym] [?f :seon.fn/calls ?callee]]
                                                                           database sym)]
                                                           (when-let [held (seq (filter opening calls))] [sym (set held)]))))
                                                 functions)]
                                 (when (seq still) {:phase phase :still-opening still}))))
                    converted)))))
