(ns seon.test.branch-reuse-test
  "A terminated result, red included, carries to another branch holding the same program."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.cache]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.test :as sut]
            [seon.test-support :as support]))

(defn- agent-value
  "The value of the last form of `code` evaluated as `agent-id` through `seon.mcp/call`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :seon.schema/value]}
  [running agent-id code]
  (->> (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                       "arguments" {"code" code "agent" agent-id "cluster" running}})))
            "content")
       (keep #(let [item (support/reply (get % "text"))]
                (when (and (map? item) (contains? item :seon.repl/value)) [(:seon.repl/value item)])))
       last
       first))

(defn- branch-members
  "Every member `branch`'s own runs hold for `test-symbol`, pulled whole, oldest admission first."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch :qualified-symbol] [:vector :map]]}
  [database branch test-symbol]
  (->> (db/q '[:find ?member ?selected :in $ ?branch ?symbol
               :where [?run :seon.test.run/branch ?branch] [?run :seon.test.run/selection-tx ?selected]
                      [?run :seon.test.run/members ?member] [?member :seon.test.member/symbol ?symbol]]
             database branch test-symbol)
       (sort-by second)
       (mapv #(db/pull database '[*] (first %)))))

(def ^:private program
  (str "(ns br.area (:require [clojure.test :refer [deftest is]]))\n"
       "(defn area \"The area.\" {:malli/schema [:=> [:cat :int] :int]} [r] (* r r))\n"
       "(deftest area-test (is (= 25 (area 5))))\n"
       "(deftest red-test (is (= 26 (area 5))))"))

(def ^:private check
  (str "(select-keys (my.test/check {:seon.test/identities #{'br.area/area-test 'br.area/red-test}})"
       " [:seon.test/passed? :seon.test/executed-count :seon.test/reused-count])"))

(def ^:private rerun
  (str "(select-keys (my.test/check {:seon.test/identities #{'br.area/area-test 'br.area/red-test} :seon.test/rerun? true})"
       " [:seon.test/executed-count])"))

(defn- evict-recorded!
  "Evict from `seon.cache` the retained outcome of each executed member `branch`'s runs hold for `test-symbols`, so
  only the recorded runs can answer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch [:sequential :qualified-symbol]] :nil]}
  [database branch test-symbols]
  (doseq [[test-symbol digest inputs]
          (db/q '[:find ?symbol ?digest ?inputs :in $ ?branch [?symbol ...]
                  :where [?run :seon.test.run/branch ?branch] [?run :seon.test.run/members ?member]
                         [?member :seon.test.member/symbol ?symbol] [?member :seon.test.member/reach-digest ?digest]
                         [?run :seon.test.run/input-digest ?inputs]]
                database branch test-symbols)]
    (seon.cache/evict! :seon.test.runner/result [test-symbol digest inputs])))

(deftest ^{:seon.test/long "Two own-branch agents, six eval requests, each with its automatic test step: about 5 s measured on default's JVM."
           :seon.test/long-ms 20000}
  a-result-carries-to-another-branch-of-the-same-program
  (support/with-running-agent
   (fn [running _ _]
     (let [first-agent (str "reuse-first-" (random-uuid))
           second-agent (str "reuse-second-" (random-uuid))
           {first-lane :seon.store/branch first-connection :seon.db/connection} (support/own-branch-agent! running first-agent program)
           first-check (agent-value running first-agent check)
           first-db (db/db first-connection)
           first-runs (set (map :seon.test.run/id (db/q '[:find [(pull ?run [:seon.test.run/id]) ...] :in $ ?branch
                                                          :where [?run :seon.test.run/branch ?branch]]
                                                        first-db first-lane)))
           _ (evict-recorded! first-db first-lane ['br.area/area-test 'br.area/red-test])
           {lane :seon.store/branch connection :seon.db/connection} (support/own-branch-agent! running second-agent program)
           second-check (agent-value running second-agent check)
           database (db/db connection)
           green (branch-members database lane 'br.area/area-test)
           red (branch-members database lane 'br.area/red-test)]
       (is (false? (:seon.test/passed? first-check)) "the red test is red on the first branch")
       (is (false? (:seon.test/passed? second-check)) "the red test is answered red on the second branch")
       (is (= 0 (:seon.test/executed-count second-check)) "the second branch executes nothing")
       (is (and (seq green) (every? (comp first-runs :seon.test.member/reused-run) green))
           "the second branch records the green it answered from the first branch's run, with the cache entry evicted")
       (is (= [1 0 0] ((juxt :seon.test.member/pass-count :seon.test.member/fail-count :seon.test.member/error-count) (first green)))
           "the recorded green carries its counts")
       (is (true? (sut/verified? database 'br.area/area-test)) "the recorded green is the branch's own green evidence")
       (is (and (seq red) (every? (comp first-runs :seon.test.member/reused-run) red))
           "the second branch records the red it answered from the first branch's run")
       (is (= [0 1 0] ((juxt :seon.test.member/pass-count :seon.test.member/fail-count :seon.test.member/error-count) (first red)))
           "the recorded red carries its counts: reuse never converts an outcome")
       (is (= 2 (:seon.test/executed-count (agent-value running second-agent rerun))) "an explicit rerun executes")
       (agent-value running second-agent
                    "(in-ns 'br.area)\n(defn area \"The area.\" {:malli/schema [:=> [:cat :int] :int]} [r] (* r (+ r 0)))")
       (agent-value running second-agent check)
       (let [changed (last (branch-members (db/db connection) lane 'br.area/area-test))]
         (is (and (:seon.test.member/began? changed) (not (:seon.test.member/reused-run changed)))
             "a change to what the test reaches executes it again"))))))
