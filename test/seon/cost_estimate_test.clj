(ns seon.cost-estimate-test
  "Estimates of ordinary operations, put up front (owner, 2026-10-17: \"it's a failing test if any thing exceeds
  them\"). `estimates` is the table: each operation's expected cost, derived from the work it must do, never from
  today's measurement. Each operation is measured end to end through the path agents use: an MCP eval request whose
  reply carries its own request cost (`seon.batch/render-request-cost-ai`: the request's attributed allocation, its
  armed calls), and its reads; root's merge through `seon.profile/measure` on the merging thread.

  Two deftests read the same table:
  - `ordinary-operations-cost-no-more-than-their-estimates` is red for every row over its estimate: the work queue.
  - `no-ordinary-operation-grows-past-its-accepted-overrun` runs on every `src` commit (`bin/lane`): a row over its
    estimate is accepted only while its `:overrun` task is open in its task file and its measurement stays within
    `ratchet` of the overrun recorded beside it, so a regression is always red and an existing overrun is red only
    in the first test."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [edamame.core :as edamame]
            [my.root]
            [seon.batch :as batch]
            [seon.cluster.host :as host]
            [seon.context]
            [seon.cost-support :as cost-support]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.id :as id]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.program :as program]
            [seon.task :as task]
            [seon.test :as seon-test]
            [seon.test-support :as support]))

(def estimates
  "Each ordinary operation's estimate: `:estimate` holds the most it may cost (allocated MB, armed calls, database
  reads, wall ms) and `:why` the work it must do, what that is proportional to and N. Wall ms is judged at
  `wall-slack` times its estimate (concurrent checks move it); allocation, calls and reads are exact. `:overrun`,
  where present, is the open task filed for the row and the measurement recorded when it was filed."
  [{:op :unchanged-request
    :why "Read one form, evaluate `nil`, one recording transaction of ~10 datoms, print one value: proportional to 1 form."
    :estimate {:alloc-mb 4.0 :calls 500 :reads 10 :ms 25.0}
    :overrun '{:task seon.repl/an-unchanged-request-costs-seventeen-megabytes-and-six-thousand-calls :alloc-mb 17.1 :calls 6617 :reads 44}}
   {:op :fresh-agent-first-request
    :why "The unchanged request plus one agent entity, one `d/branch!` pointer and one `sci/fork`: constant, about 3x the unchanged request."
    :estimate {:alloc-mb 12.0 :calls 1500 :reads 30 :ms 75.0}
    :overrun '{:task seon.cluster.agent/a-fresh-agents-environment-costs-one-fork :alloc-mb 49.0 :calls 8033 :reads 62}}
   {:op :new-def
    :why "The unchanged request plus one form's analysis, digest and entity (~30 datoms); no test reaches a new name: proportional to 1 form."
    :estimate {:alloc-mb 16.0 :calls 2500 :reads 40 :ms 60.0}
    :overrun '{:task seon.repl/a-new-def-costs-a-hundred-forty-megabytes :alloc-mb 63.7 :calls 15558 :reads 203}}
   {:op :unmap-one-def
    :why "The unchanged request plus one `my.program/ns-unmap!` of a def nothing calls: the def's referrers as indexed reads of its edges (none), read once at the form and once where the writer re-derives the retraction, and the retraction of its ~30 datoms; no test reaches it: proportional to the definition's edges, never to the program. Its overrun is the program-change request's own, which a new def shares."
    :estimate {:alloc-mb 12.0 :calls 2500 :reads 60 :ms 80.0}
    :overrun '{:task seon.repl/a-new-def-costs-a-hundred-forty-megabytes :alloc-mb 74.8 :calls 15184 :reads 178}}
   {:op :redefine-one-form
    :why "A new def plus finding the one test reaching the changed digest and running it: proportional to 1 form and 1 reaching test."
    :estimate {:alloc-mb 24.0 :calls 4000 :reads 60 :ms 100.0}
    :overrun '{:task seon.repl/redefining-one-form-costs-three-hundred-megabytes :alloc-mb 287.9 :calls 41125 :reads 334}}
   {:op :reuse-one-unchanged-test
    :why "The unchanged request plus one reach digest over the test's closure (~5 definitions) and one indexed read of its record: no run."
    :estimate {:alloc-mb 8.0 :calls 1500 :reads 25 :ms 40.0}
    :overrun '{:task my.test/reusing-one-unchanged-test-costs-nine-thousand-calls :alloc-mb 26.9 :calls 8960 :reads 72}}
   {:op :current-state-with-task
    :why "The unchanged request plus the agent's one-hop attachments: its task, 7 references, 1 proof: proportional to ~10 attachments."
    :estimate {:alloc-mb 24.0 :calls 3000 :reads 100 :ms 100.0}
    :overrun '{:task seon.context/one-agents-current-state-costs-hundreds-of-megabytes :alloc-mb 194.0 :calls 37754 :reads 211}}
   {:op :seon-db-nine-forms
    :why "Nine unchanged forms of a 6,500-line namespace: read, analyze and digest each form, all digests equal, nothing written, no test: proportional to 9 forms, never to the namespace."
    :estimate {:alloc-mb 80.0 :calls 12000 :reads 150 :ms 400.0}
    :overrun '{:task seon.repl/re-evaluating-nine-seon-db-forms-allocates-nine-gigabytes :alloc-mb 871.4 :calls 93090 :reads 1358}}
   {:op :hub-docstring-change
    :why "One redefinition of the hub `seon.db/as-of-key` that changes only its docstring: read, analyze and digest one form; its behavior digest equals the merge base's, so no caller is re-armed and no test is selected: an unchanged form's cost, proportional to 1 form."
    :estimate {:alloc-mb 30.0 :calls 4000 :reads 60 :ms 150.0}
    :overrun '{:task seon.repl/a-docstring-only-change-to-a-hub-function-costs-hundreds-of-megabytes :alloc-mb 221.6 :calls 56529 :reads 305}}
   {:op :hub-behavior-change
    :why "One redefinition of the hub `seon.db/as-of-key` that changes its behavior digest (`AsOfDB` spelled fully qualified): read, analyze, digest and record one form, interpret that one definition (its src callers stay compiled), and run its one direct test on the branch (the ~160 tests above its src callers are the merge's platform run): the redefined form's cost plus one small test, proportional to 1 form and 1 test."
    :estimate {:alloc-mb 40.0 :calls 6000 :reads 100 :ms 300.0}
    :overrun '{:task seon.repl/a-hub-behavior-change-costs-hundreds-of-megabytes :alloc-mb 852.2 :calls 188480 :reads 1255}}
   {:op :cold-stale-merge-members
    :why "The first `seon.test/stale` after boot over the tests reaching the hub `seon.db/as-of-key` (~140 members, a merge's members): one reach digest per member over the union of their reach (~3,500 definitions), each definition's behavior digest computed once from its stored row and one read of its source (~50 KB, ~10 calls), then one indexed result read per member: proportional to the reached definitions."
    :estimate {:alloc-mb 175.0 :calls 35000 :reads 150 :ms 350.0}
    :overrun '{:task seon.db/a-cold-behavior-digest-allocates-a-third-of-a-megabyte :alloc-mb 371.4 :calls 107809 :reads 121}}
   {:op :merge-one-function
    :why "Root merges a branch changing one function: a three-way diff of ~30 datoms from the merge base, its proof and one reaching test run on the merge branch, one merge transaction: proportional to the delta."
    :estimate {:alloc-mb 48.0 :calls 8000 :reads 200 :ms 400.0}
    :overrun '{:task seon.cluster.source/accepting-a-one-function-merge-allocates-hundreds-of-megabytes :alloc-mb 104.7 :calls 28216}}])

(def ^:private wall-slack
  "Wall time is judged at this multiple of its estimate: concurrent checks and cold JIT move it, allocation and
  counts do not."
  3.0)

(def ^:private ratchet
  "An accepted overrun stays accepted while each measure is within this multiple of the recorded one: the spread
  of one operation measured on separate JVMs (a warm redefinition allocated 118-199 MB on default)."
  1.5)

(defn- answer
  "The parsed MCP result of `code` evaluated in SCI as `agent-id` on `running`."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id "mode" "sci" "code" code}}))))

(defn- request-cost
  "The cost of `code` as one eval request of `agent-id`: what its reply reports."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [running agent-id code]
  (let [cost (cost-support/bound-cost running agent-id (answer running agent-id code))]
    {:alloc-mb (:seon.cost/alloc-mb (batch/render-request-cost-ai cost))
     :calls (:seon.cost/calls cost) :reads (:seon.cost/reads cost) :ms (:seon.cost/ms cost)}))

(defn- seon-db-forms
  "The text of nine consecutive top-level forms of `seon.db`, from `seon.db/as-of-key`, as the checkout holds them."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [text (slurp (io/resource "seon/db.clj"))
        lines (vec (.split ^String text "\n" -1))
        forms (vec (edamame/parse-string-all text {:all true :end-location true :auto-resolve name
                                                   :readers (fn [_] identity) :read-cond :allow :features #{:clj}}))
        from (first (keep-indexed (fn [i form] (when (and (seq? form) (= 'as-of-key (second form))) i)) forms))]
    (str/join "\n" (for [form (subvec forms from (+ from 9))
                         :let [{:keys [row end-row]} (meta form)]
                         line (subvec lines (dec row) end-row)]
                     line))))

(defn- hub-docstring-change
  "`seon.db/as-of-key` as the checkout holds it with one more sentence in its docstring: a change no test observes."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [forms (seon-db-forms)]
    (str/replace-first (subs forms 0 (str/index-of forms "\n(" 1)) "\"An as-of view" "\"Documented again. An as-of view")))

(defn- hub-behavior-change
  "`seon.db/as-of-key` as the checkout holds it with `AsOfDB` spelled fully qualified: equal behavior, another
  behavior digest, so a change its tests can observe."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [forms (seon-db-forms)]
    (str/replace-first (subs forms 0 (str/index-of forms "\n(" 1)) "(instance? AsOfDB" "(instance? datahike.db.AsOfDB")))

(def ^:private merged-source
  "The checkout's one file the merged branch changes: a function, a test reaching it and the red proof of the task."
  (str "(ns tq.cost-merge\n  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn one\n  \"One.\"\n  {:malli/schema [:=> [:cat] :int]}\n  []\n  1)\n\n"
       "(deftest one-test (is (pos? (one))))\n\n"
       "(deftest two-test (is (= 2 (one))))\n"))

(defn- merge-cost
  "Root's merge of an own-branch agent's assignment that changes one function, measured on the merging thread:
  the checkout's file declared on main, the agent's redefinition on its branch, then `my.root/merge!`."
  {:malli/schema [:=> [:cat :string :seon.db/connection] :map]}
  [running connection]
  (let [cluster-name (:seon.cluster/name (support/execution-handle nil))
        _ (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch])
                                     'user cluster-name]])
        root (support/commit-root! (let [root (support/temp-root "cost-merge")]
                                     (io/make-parents (io/file root "test/tq/cost_merge.clj"))
                                     (spit (io/file root "test/tq/cost_merge.clj") merged-source)
                                     root))]
    (try
      (support/apply-config! connection cluster-name {:seon.config.fs/working-root root})
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})
            _ (support/transacted! connection (fn/reconcile-tx (db/db connection) entities (mapv program/row-identity entities)))
            agent-id (str "cost-merge-" (id/id))
            {branch :seon.store/branch} (support/own-branch-agent! running agent-id "(ns tq.cost-merge)")
            task-id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns 'tq.cost-merge
                                                  :seon.task/name "two" :seon.task/plan "Return two."
                                                  :seon.task/proofs #{'tq.cost-merge/two-test}}))
            assigned (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection
                                    :seon.task/assigned task-id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
            _ (when-not (:seon.task/assignment assigned) (throw (ex-info "The merge fixture's assignment was refused." assigned)))
            _ (answer running agent-id "(in-ns 'tq.cost-merge) (defn one \"One.\" {:malli/schema [:=> [:cat] :int]} [] 2)")
            execution (support/running-cluster running)
            measured (profile/measure #(my.root/merge! {:my.program/context (assoc execution :my.program/base-ctx (:seon.sci.eval/ctx execution))
                                                        :seon.task/assignment (:seon.task/assignment assigned)}))]
        (is (nil? (:seon.error/message (:seon.profile/value measured))) "the merge measured is a merge")
        {:alloc-mb (/ (:seon.profile/alloc-bytes measured 0) 1048576.0) :calls (:seon.profile/calls measured)
         :reads (:seon.profile/reads measured) :ms (:seon.profile/ms measured)})
      (finally (support/delete-root! root)))))

(defn- cold-stale-cost
  "The first `seon.test/stale` of the JVM over the tests reaching `seon.db/as-of-key`, measured on the calling thread."
  {:malli/schema [:=> [:cat :seon.db/connection] :map]}
  [connection]
  (let [database (db/db connection)
        ;; the merge's members: what the branch observes and the platform run's
        selected (seon-test/reaching-selection {:seon.db/db database :seon.test/changed ['seon.db/as-of-key]})
        members (into (vec (:seon.fn/tests selected)) (:seon.fn/platform-tests selected))
        measured (profile/measure #(seon-test/stale database members))]
    (is (< 100 (count members)) "the hub's members are the merge's members")
    {:alloc-mb (/ (:seon.profile/alloc-bytes measured 0) 1048576.0) :calls (:seon.profile/calls measured)
     :reads (:seon.profile/reads measured) :ms (:seon.profile/ms measured) :members (count members)}))

(defn- measured
  "Each operation of `estimates` measured once, warm, on a running agent: op -> measures."
  {:malli/schema [:=> [:cat] [:map-of :keyword :map]]}
  []
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [cold-stale (cold-stale-cost connection)
           request (partial answer running agent-id)
           cost (partial request-cost running agent-id)
           _warm (dotimes [_ 2] (request "nil"))
           unchanged (cost "nil")
           fresh (request-cost running (str agent-id "-fresh") "nil")
           _ (request "(in-ns 'cost.estimate.defs) (def warm-up 1)")
           new-def (cost "(def answer-value 42)")
           _ (request "(def unmapped-value 1)")
           unmap (cost "(my.program/ns-unmap! 'cost.estimate.defs/unmapped-value)")
           _ (request (str "(ns cost.estimate.redefine (:require [clojure.test :refer [deftest is]]))\n"
                           "(defn tiny \"One more.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                           "(deftest tiny-test (is (= 2 (tiny 1))))"))
           _ (request "(defn tiny \"One more.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))")
           redefine (cost "(defn tiny \"One more.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))")
           check "(:seon.test/reused-count (my.test/check {:seon.test/identities #{'cost.estimate.redefine/tiny-test}}))"
           _ (request check)
           reuse (cost check)
           _ (support/declare-proof! connection 'cost.estimate.proof 'pending 'cost.estimate/read)
           task-id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id
                                                 :seon.task/ns 'cost.estimate :seon.task/name "read"
                                                 :seon.task/plan (str "Read `seon.ref/references`, `seon.ref/resolve-path`, "
                                                                      "`seon.context/current-state`, `seon.task/agent-attempt`, "
                                                                      "`seon.task/agent-task`, `seon.db/pull` and `seon.db/q`.")}))
           _ (is (not (:seon.error/type
                       (task/assign! {:seon.agent/context-source (support/running-cluster running)
                                      :seon.db/connection connection :seon.task/assigned task-id
                                      :seon.task/agent [:seon.agent/id agent-id]
                                      :seon.task/branch (get-in (db/db connection) [:config :branch])})))
                 "the agent holds a task")
           _ (request "(seon.context/current-state)")
           current-state (cost "(seon.context/current-state)")
           nine (cost (str "(in-ns 'seon.db)\n" (seon-db-forms)))
           hub-docstring (cost (str "(in-ns 'seon.db)\n" (hub-docstring-change)))
           hub-behavior (cost (str "(in-ns 'seon.db)\n" (hub-behavior-change)))]
       {:unchanged-request unchanged :fresh-agent-first-request fresh :new-def new-def :unmap-one-def unmap :redefine-one-form redefine
        :reuse-one-unchanged-test reuse :current-state-with-task current-state :seon-db-nine-forms nine
        :hub-docstring-change hub-docstring :hub-behavior-change hub-behavior :cold-stale-merge-members cold-stale
        :merge-one-function (merge-cost running connection)}))))

(defn- over
  "The measures of `observed` above their `limit`, wall time at `slack` times its limit: measure -> [observed limit]."
  {:malli/schema [:=> [:cat :map :map number?] :map]}
  [observed limit slack]
  (into {} (keep (fn [[k bound]]
                   (let [bound (if (= k :ms) (* slack bound) bound)
                         seen (get observed k)]
                     (when (or (nil? seen) (> seen bound)) [k [seen bound]]))))
        limit))

(defn- task-open?
  "Whether task `sym` is still declared in its task file `resources/seon/tasks/<ns>.edn`: the merge of the work
  that does it deletes the entry."
  {:malli/schema [:=> [:cat :qualified-symbol] :boolean]}
  [sym]
  (let [file (io/resource (str "seon/tasks/" (namespace sym) ".edn"))]
    (boolean (and file (some #(= (name sym) (:seon.task/name %))
                             (edamame/parse-string (slurp file) {:readers (fn [_] identity)}))))))

(defn- unaccepted
  "The rows of `estimates` over their estimate in `seen` that no open task accepts, or that grew past `ratchet`
  times their recorded overrun (a measure the overrun does not record keeps its estimate). Judged on the exact counts
  only (allocation, calls, reads): this runs on every commit beside concurrent checks, where wall time moves with
  load, so wall time is judged by the table test alone."
  {:malli/schema [:=> [:cat [:map-of :keyword :map]] [:vector :map]]}
  [seen]
  (into [] (keep (fn [{:keys [op estimate overrun]}]
                   (let [estimate (dissoc estimate :ms)
                         o (over (seen op) estimate wall-slack)
                         accepted (merge estimate (into {} (map (fn [[k v]] [k (* ratchet v)])) (dissoc overrun :task :ms)))]
                     (cond (empty? o) nil
                           (nil? overrun) {:op op :over o}
                           (not (task-open? (:task overrun))) {:op op :over o :closed (:task overrun)}
                           :else (let [grown (over (seen op) accepted wall-slack)]
                                   (when (seq grown) {:op op :grown grown :task (:task overrun)}))))))
        estimates))

(deftest ^{:seon.test/long "Nine ordinary operations through one running agent, each measured warm once."
           :seon.test/long-ms 60000}
  ordinary-operations-cost-no-more-than-their-estimates
  (let [seen (measured)]
    (is (= [] (into [] (keep (fn [{:keys [op estimate]}]
                               (let [o (over (seen op) estimate wall-slack)] (when (seq o) {:op op :over o}))))
                    estimates)))))

(deftest ^{:seon.test/long "Nine ordinary operations through one running agent, each measured warm once."
           :seon.test/long-ms 60000}
  no-ordinary-operation-grows-past-its-accepted-overrun
  (is (= [] (unaccepted (measured)))))
