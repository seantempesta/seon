(ns seon.cost-estimate-test
  "Estimates of ordinary operations, put up front (owner, 2026-10-17: \"it's a failing test if any thing exceeds
  them\"). `estimates` is the table: each operation's expected cost, derived from the work it must do, never from
  today's measurement. Each operation is measured end to end through the path agents use: an MCP eval request whose
  reply carries its own request cost (`seon.batch/render-request-cost-ai`: the request's attributed allocation, its
  armed calls), and its reads; root's merge and the cold stale through `seon.profile/measure` on the calling
  thread, their calls and reads counted in a call sink of their own (`measured-call`).

  A row measures the request it names, in a stated state, so it repeats on unchanged code: content that a row's
  result is keyed by is fresh in every measurement (`program-change-pass`), the program-change rows are measured
  after one identical warm-up pass, and the cold stale runs on an evicted cache (`cold-stale-cost`). Each test var
  runs on a branch of its own, so the two deftests measure the same state.

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
            [seon.cache :as cache]
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
  where present, is the open task filed for the row and the measurement recorded when it was filed or when the
  row's measure last changed."
  [{:op :unchanged-request
    :why "Read one form, evaluate `nil`, one recording transaction of ~10 datoms, print one value: proportional to 1 form."
    :estimate {:alloc-mb 4.0 :calls 500 :reads 10 :ms 25.0}
    :overrun '{:task seon.repl/an-unchanged-request-costs-seventeen-megabytes-and-six-thousand-calls :alloc-mb 18.5 :calls 6357 :reads 46}}
   {:op :fresh-agent-first-request
    :why "The unchanged request plus one agent entity, one `d/branch!` pointer and one `sci/fork`: constant, about 3x the unchanged request."
    :estimate {:alloc-mb 12.0 :calls 1500 :reads 30 :ms 75.0}
    :overrun '{:task seon.cluster.agent/a-fresh-agents-environment-costs-one-fork :alloc-mb 54.3 :calls 7401 :reads 45}}
   {:op :new-def
    :why "The unchanged request plus one form's analysis, digest and entity (~30 datoms); no test reaches a new name: proportional to 1 form."
    :estimate {:alloc-mb 16.0 :calls 2500 :reads 40 :ms 60.0}
    :overrun '{:task seon.repl/a-new-def-costs-a-hundred-forty-megabytes :alloc-mb 92.9 :calls 18676 :reads 348}}
   {:op :unmap-one-def
    :why "The unchanged request plus one `my.program/ns-unmap!` of a def nothing calls: the def's referrers as indexed reads of its edges (none), read once at the form and once where the writer re-derives the retraction, and the retraction of its ~30 datoms; no test reaches it: proportional to the definition's edges, never to the program. Its overrun is the program-change request's own, which a new def shares."
    :estimate {:alloc-mb 12.0 :calls 2500 :reads 60 :ms 80.0}
    :overrun '{:task seon.repl/a-new-def-costs-a-hundred-forty-megabytes :alloc-mb 102.6 :calls 17960 :reads 321}}
   {:op :redefine-one-form
    :why "A new def plus finding the one test reaching the changed digest and running it: proportional to 1 form and 1 reaching test."
    :estimate {:alloc-mb 24.0 :calls 4000 :reads 60 :ms 100.0}
    :overrun '{:task seon.repl/redefining-one-form-costs-three-hundred-megabytes :alloc-mb 333.2 :calls 47993 :reads 355}}
   {:op :reuse-one-unchanged-test
    :why "The unchanged request plus one reach digest over the test's closure (~5 definitions) and one indexed read of its record: no run."
    :estimate {:alloc-mb 8.0 :calls 1500 :reads 25 :ms 40.0}
    :overrun '{:task my.test/reusing-one-unchanged-test-costs-nine-thousand-calls :alloc-mb 29.9 :calls 7727 :reads 58}}
   {:op :current-state-with-task
    :why "The unchanged request plus the agent's one-hop attachments: its task, 7 references, 1 proof: proportional to ~10 attachments."
    :estimate {:alloc-mb 24.0 :calls 3000 :reads 100 :ms 100.0}
    :overrun '{:task seon.context/one-agents-current-state-costs-hundreds-of-megabytes :alloc-mb 210.2 :calls 41373 :reads 208}}
   {:op :seon-db-nine-forms
    :why "Nine unchanged forms of a 6,500-line namespace: read, analyze and digest each form, all digests equal, nothing written, no test: proportional to 9 forms, never to the namespace."
    :estimate {:alloc-mb 80.0 :calls 12000 :reads 150 :ms 400.0}
    :overrun '{:task seon.repl/re-evaluating-nine-seon-db-forms-allocates-nine-gigabytes :alloc-mb 955.0 :calls 98573 :reads 1558}}
   {:op :hub-docstring-change
    :why "One redefinition of the hub `seon.db/as-of-key` that changes only its docstring: read, analyze and digest one form; its behavior digest equals the merge base's, so no caller is re-armed and no test is selected: an unchanged form's cost, proportional to 1 form."
    :estimate {:alloc-mb 30.0 :calls 4000 :reads 60 :ms 150.0}
    :overrun '{:task seon.repl/a-docstring-only-change-to-a-hub-function-costs-hundreds-of-megabytes :alloc-mb 251.4 :calls 57994 :reads 377}}
   {:op :hub-behavior-change
    :why "One redefinition of the hub `seon.db/as-of-key` that changes its behavior digest (`AsOfDB` spelled fully qualified): read, analyze, digest and record one form, interpret that one definition (its src callers stay compiled), and run its one direct test on the branch (the ~160 tests above its src callers are the merge's platform run): the redefined form's cost plus one small test, proportional to 1 form and 1 test."
    :estimate {:alloc-mb 40.0 :calls 6000 :reads 100 :ms 300.0}
    :overrun '{:task seon.repl/a-hub-behavior-change-costs-hundreds-of-megabytes :alloc-mb 905.8 :calls 191752 :reads 1276}}
   {:op :cold-stale-merge-members
    :why "`seon.test/stale` with every derived value cold over the tests reaching the hub `seon.db/as-of-key` (~140 members, a merge's members): one reach digest per member over the union of their reach (~3,500 definitions), each definition's behavior digest computed once from its stored row and one read of its source (~50 KB, ~10 calls), then one indexed result read per member: proportional to the reached definitions."
    :estimate {:alloc-mb 175.0 :calls 35000 :reads 150 :ms 350.0}
    :overrun '{:task seon.db/a-cold-behavior-digest-allocates-a-third-of-a-megabyte :alloc-mb 4323.9 :calls 794485 :reads 14}}
   {:op :merge-one-function
    :why "Root merges a branch changing one function: a three-way diff of ~30 datoms from the merge base, its proof and one reaching test run on the merge branch, one merge transaction: proportional to the delta."
    :estimate {:alloc-mb 48.0 :calls 8000 :reads 200 :ms 400.0}
    :overrun '{:task seon.cluster.source/accepting-a-one-function-merge-allocates-hundreds-of-megabytes :alloc-mb 117.3 :calls 18537 :reads 184}}
   {:op :similar-by-name-warm
    :why "The unchanged request plus `(my.program/similar 'seon.db/transcat! 5)` with its index derived: the program's ~11,000 declared names read once, one content key over them, one hit on the index, one embed request for the query and one pass of 11,000 x 256 multiply-adds: proportional to the declared names, a few bytes each."
    :estimate {:alloc-mb 5.0 :calls 800 :reads 10 :ms 40.0}
    :overrun '{:task my.program/a-warm-similar-by-name-costs-seventy-megabytes :alloc-mb 73.6 :calls 19951 :reads 57}}
   {:op :unresolved-symbol-request
    :why "The unchanged request plus an unresolved `seon.db/transcat!`: the reader's refusal, the namespace's public names and the alias's library names read once, their warm index ranked against one query embedding, eight calling forms printed: proportional to the public names."
    :estimate {:alloc-mb 10.0 :calls 1500 :reads 30 :ms 80.0}
    :overrun '{:task seon.error/an-unresolved-symbol-request-costs-thirty-megabytes :alloc-mb 34.3 :calls 15563 :reads 66}}])

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

(defn- merged-source
  "The checkout's one file the merged branch changes, namespace `ns-sym`: a function, a test reaching it and the red
  proof of the task."
  {:malli/schema [:=> [:cat :symbol] :string]}
  [ns-sym]
  (str "(ns " ns-sym "\n  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn one\n  \"One.\"\n  {:malli/schema [:=> [:cat] :int]}\n  []\n  1)\n\n"
       "(deftest one-test (is (pos? (one))))\n\n"
       "(deftest two-test (is (= 2 (one))))\n"))

(defn- measured-call
  "`(f)` measured on the calling thread through `seon.profile/measure`, its calls and reads counted in a call sink
  of its own (`seon.profile/sink-counts`): the work of this call and the worlds it starts, never the JVM's
  concurrent work, which `measure`'s JVM-wide entry counters include."
  {:malli/schema [:=> [:cat :seon.profile/thunk] :map]}
  [f]
  (let [sink (batch/sink)
        measured (with-bindings {#'batch/*world* (assoc batch/*world* :seon.batch/sink sink)} (profile/measure f))
        counts (profile/sink-counts sink)]
    {:value (:seon.profile/value measured)
     :alloc-mb (/ (:seon.profile/alloc-bytes measured 0) 1048576.0) :calls (:seon.cost/calls counts)
     :reads (:seon.cost/reads counts) :ms (:seon.profile/ms measured)}))

(defn- merge-cost
  "Root's merge of an own-branch agent's assignment that changes one function, measured on the merging thread:
  the checkout's file declared on main, the agent's redefinition on its branch, then `my.root/merge!`. `suffix`
  names its namespace, so each merge carries a change main has not merged."
  {:malli/schema [:=> [:cat :string :seon.db/connection :string] :map]}
  [running connection suffix]
  (let [cluster-name (:seon.cluster/name (support/execution-handle nil))
        _ (db/transact! connection [[:db.fn/call #'host/home-call "root" (get-in (db/db connection) [:config :branch])
                                     'user cluster-name]])
        ns-sym (symbol (str "tq.cost-merge-" suffix))
        file (str "test/tq/cost_merge_" suffix ".clj")
        root (support/commit-root! (let [root (support/temp-root "cost-merge")]
                                     (io/make-parents (io/file root file))
                                     (spit (io/file root file) (merged-source ns-sym))
                                     root))]
    (try
      (support/apply-config! connection cluster-name {:seon.config.fs/working-root root})
      (let [entities (fn/analyze-entities {:seon.fn/root root :seon.fn/roots ["test"]})
            _ (support/transacted! connection (fn/reconcile-tx (db/db connection) entities (mapv program/row-identity entities)))
            agent-id (str "cost-merge-" (id/id))
            {branch :seon.store/branch} (support/own-branch-agent! running agent-id (str "(ns " ns-sym ")"))
            task-id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns ns-sym
                                                  :seon.task/name "two" :seon.task/plan "Return two."
                                                  :seon.task/proofs #{(symbol (str ns-sym) "two-test")}}))
            assigned (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection
                                    :seon.task/assigned task-id :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})
            _ (when-not (:seon.task/assignment assigned) (throw (ex-info "The merge fixture's assignment was refused." assigned)))
            _ (answer running agent-id (str "(in-ns '" ns-sym ") (defn one \"One.\" {:malli/schema [:=> [:cat] :int]} [] 2)"))
            execution (support/running-cluster running)
            measured (measured-call #(my.root/merge! {:my.program/context (assoc execution :my.program/base-ctx (:seon.sci.eval/ctx execution))
                                                      :seon.task/assignment (:seon.task/assignment assigned)}))]
        (is (nil? (:seon.error/message (:value measured))) "the merge measured is a merge")
        (dissoc measured :value))
      (finally (support/delete-root! root)))))

(defn- cold-stale-cost
  "`seon.test/stale` over the tests reaching `seon.db/as-of-key` with every derived value cold: every kind of the
  process cache but the SCI program contexts the running agents fork evicted first (`seon.cache/evict-kinds!`), as
  after a restart. What boot or an earlier check happened to derive never decides the measure."
  {:malli/schema [:=> [:cat :seon.db/connection] :map]}
  [connection]
  (let [database (db/db connection)
        ;; the merge's members: what the branch observes and the platform run's
        selected (seon-test/reaching-selection {:seon.db/db database :seon.test/changed ['seon.db/as-of-key]})
        members (into (vec (:seon.fn/tests selected)) (:seon.fn/platform-tests selected))
        _ (cache/evict-kinds! (into #{} (remove #(str/starts-with? (namespace %) "seon.sci"))
                                    (keys (:seon.cache/kinds (cache/status)))))
        measured (measured-call #(seon-test/stale database members))]
    (is (< 100 (count members)) "the hub's members are the merge's members")
    (assoc (dissoc measured :value) :members (count members))))

(defn- program-change-pass
  "The rows whose content `suffix` makes fresh, measured in order through `agent-id`: an unchanged request, a fresh
  agent's first request, a new def, an unmap, a redefinition running its reaching test, that test reused, and
  root's merge. Fresh names keep every result keyed by content (a test's reach, a merge's delta) from being one an
  earlier pass in this JVM left: op -> measures."
  {:malli/schema [:=> [:cat :string :string :seon.db/connection :string] [:map-of :keyword :map]]}
  [running agent-id connection suffix]
  (let [request (partial answer running agent-id)
        cost (partial request-cost running agent-id)
        defs (str "cost.estimate.defs-" suffix)
        redefine (str "cost.estimate.redefine-" suffix)
        _warm (dotimes [_ 2] (request "nil"))
        unchanged (cost "nil")
        fresh (request-cost running (str agent-id "-fresh-" suffix) "nil")
        _ (request (str "(in-ns '" defs ") (def warm-up 1)"))
        new-def (cost "(def answer-value 42)")
        _ (request "(def unmapped-value 1)")
        unmap (cost (str "(my.program/ns-unmap! '" defs "/unmapped-value)"))
        _ (request (str "(ns " redefine " (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn tiny \"One more.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                        "(deftest tiny-test (is (= 2 (tiny 1))))"))
        _ (request "(defn tiny \"One more.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))")
        redefined (cost "(defn tiny \"One more.\" {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))")
        check (str "(:seon.test/reused-count (my.test/check {:seon.test/identities #{'" redefine "/tiny-test}}))")
        _ (request check)
        reuse (cost check)]
    {:unchanged-request unchanged :fresh-agent-first-request fresh :new-def new-def :unmap-one-def unmap
     :redefine-one-form redefined :reuse-one-unchanged-test reuse
     :merge-one-function (merge-cost running connection suffix)}))

(defn- measured
  "Each operation of `estimates` measured once on a running agent: op -> measures. The cold stale runs first, on an
  evicted cache; the program-change rows run twice under fresh names and the second pass is kept, so each is
  measured warm, after one identical pass on other content, whatever ran earlier in the JVM; the rest follow once."
  {:malli/schema [:=> [:cat] [:map-of :keyword :map]]}
  []
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [cold-stale (cold-stale-cost connection)
           _warm-pass (program-change-pass running agent-id connection (str "w" (id/id)))
           changes (program-change-pass running agent-id connection (str "m" (id/id)))
           request (partial answer running agent-id)
           cost (partial request-cost running agent-id)
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
           hub-behavior (cost (str "(in-ns 'seon.db)\n" (hub-behavior-change)))
           ;; joins the background fill's derivation of this branch's corpora: the rows measure warm calls
           _ (request "(in-ns 'user) (seon.embed/fill! (seon.db/db) seon.embed/request!) nil")
           _ (request "(my.program/similar 'seon.db/transcat! 5)")
           similar-by-name (cost "(my.program/similar 'seon.db/transcat! 5)")
           _ (request "(seon.db/transcat! 1)")
           unresolved (cost "(seon.db/transcat! 1)")]
       (assoc changes :current-state-with-task current-state :seon-db-nine-forms nine
              :similar-by-name-warm similar-by-name :unresolved-symbol-request unresolved
              :hub-docstring-change hub-docstring :hub-behavior-change hub-behavior :cold-stale-merge-members cold-stale)))))

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

(deftest ^{:seon.test/long "Twelve ordinary operations through one running agent: a cold stale over ~160 tests (about 4 s), then the program-change rows twice, the second pass measured."
           :seon.test/long-ms 60000}
  ordinary-operations-cost-no-more-than-their-estimates
  (let [seen (measured)]
    (is (= [] (into [] (keep (fn [{:keys [op estimate]}]
                               (let [o (over (seen op) estimate wall-slack)] (when (seq o) {:op op :over o}))))
                    estimates)))))

(deftest ^{:seon.test/long "Twelve ordinary operations through one running agent: a cold stale over ~160 tests (about 4 s), then the program-change rows twice, the second pass measured."
           :seon.test/long-ms 60000}
  no-ordinary-operation-grows-past-its-accepted-overrun
  (is (= [] (unaccepted (measured)))))
