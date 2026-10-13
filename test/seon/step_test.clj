(ns seon.step-test
  "The off-the-shelf step templates' default proofs and the handoffs they gate
  (`docs/seon/schedule/cards/TASK-CHAIN.md#4-off-the-shelf-templates`)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.contracts :as contracts]
            [seon.db :as db]
            [seon.ref :as ref]
            [seon.task :as task]
            [seon.test]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(def ^:private templates
  "The five off-the-shelf templates, in chain order."
  '[seon.step/survey seon.step/define seon.step/design seon.step/prove seon.step/implement])

;;; The shared handoff checks: pure functions of the effective document, the database's entities and result values.

(defn- sections
  "Each heading anchor of Markdown `text` (`seon.ref/document`), at any level, with its section's text up to the next heading."
  {:malli/schema [:=> [:cat :string] [:map-of :seon.ref/anchor :string]]}
  [text]
  (let [lines (vec (str/split-lines text))
        headings (:seon.ref/headings (ref/document text))
        ends (concat (map :seon.ref/line (rest headings)) [(inc (count lines))])]
    (into {} (map (fn [{:seon.ref/keys [anchor line]} end] [anchor (str/join "\n" (subvec lines line (dec end)))]) headings ends))))

(defn- document-gaps
  "What keeps the document `intent` from a handoff on `database`: each `required` section anchor without content,
  then each reference of the intent that does not resolve; empty when none."
  {:malli/schema [:=> [:cat :seon.db/database-value :string [:vector :seon.ref/anchor]] [:vector :seon.schema/value]]}
  [database intent required]
  (let [present (sections intent)]
    (into (filterv #(str/blank? (get present % "")) required)
          (filter :seon.ref/problem-kind)
          (ref/references database {:seon.ref/text intent}))))

(defn- unmeasured-claims
  "The deftests `text` cites that do not call `seon.profile/measure` on `database`: a cited deftest is a cost claim."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:set :qualified-symbol]]}
  [database text]
  (let [cited (vec (ref/names text))
        tests (db/q '[:find [?s ...] :in $ [?s ...] :where [_ :seon.test/sym ?s]] database cited)
        measured (db/q '[:find [?s ...] :in $ [?s ...] :where [?e :seon.test/sym ?s] [?e :seon.fn/calls seon.profile/measure]]
                       database cited)]
    (set/difference (set tests) (set measured))))

(defn- uncontracted
  "The functions `text` cites on `database` without a complete contract (`seon.contracts/gaps`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:set :qualified-symbol]]}
  [database text]
  (let [functions (db/q '[:find [?s ...] :in $ [?s ...] :where [_ :seon.fn/sym ?s]] database (vec (ref/names text)))]
    (into #{} (remove #(empty? (contracts/gaps database %))) functions)))

(defn- designed-product?
  "Whether `product` is not empty and each of its tests has a designed red among `results`, by test
  (`seon.test.runner/designed-red?`)."
  {:malli/schema [:=> [:cat :seon.task/product [:map-of :qualified-symbol :map]] :boolean]}
  [product results]
  (boolean (and (seq product) (every? #(some-> (results %) runner/designed-red?) product))))

(defn- judged
  "The task this default deftest judges (`seon.test/task`: the request's `:seon.test/task`, else its one tag): the
  member's database, the task's effective declaration and its obligations, both read on the request's database."
  {:malli/schema [:=> [:cat] [:tuple :seon.db/database-value :seon.task/effective :seon.task/obligations]]}
  []
  (let [{effective :seon.task/effective obligations :seon.task/obligations} (seon.test/task)]
    [(db/db) effective obligations]))

(def ^:private worked-results
  "The worked example's product result: an executed run of the table test with one failing assertion."
  {'seon.fs-test/under-path-tells-what-lies-under-a-root
   {:seon.test/pass-count 4 :seon.test/fail-count 1 :seon.test/error-count 0 :seon.test.failure/reports [{:type :fail}]}})

(deftest ^{:seon/tasks #{'seon.step/survey}} survey-handoff
  (let [[database {intent :seon.task/intent}] (judged)]
    (is (= [] (document-gaps database intent ["problem" "what-exists"])) "Problem and What exists have content and every reference resolves")))

(deftest ^{:seon/tasks #{'seon.step/define}} define-handoff
  (let [[database {intent :seon.task/intent}] (judged)]
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost"]))
        "Examples and Expected cost join the earlier sections")
    (is (= #{} (unmeasured-claims database (get (sections intent) "expected-cost"))) "a section citing no deftest makes no claim")
    (is (= '#{seon.fs-test/under-path-tells-what-lies-under-a-root}
           (unmeasured-claims database "`seon.fs-test/under-path-tells-what-lies-under-a-root`"))
        "a cited deftest that measures nothing is an unmet cost claim")
    (is (= #{} (unmeasured-claims database "`seon.task-test/ready-answers-in-under-10-ms-for-fifty-leaves`"))
        "a cited deftest calling seon.profile/measure meets it")))

(deftest ^{:seon/tasks #{'seon.step/design}} design-handoff
  (let [[database {intent :seon.task/intent}] (judged)]
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost" "approach"]))
        "Approach joins the earlier sections")
    (is (= #{} (uncontracted database (get (sections intent) "approach"))) "every function Approach cites is contracted")))

(deftest ^{:seon/tasks #{'seon.step/prove}} prove-handoff
  (let [[database {intent :seon.task/intent} {step :seon.task/step}] (judged)
        product (set (keys worked-results))]
    (is (= :prove step))
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost" "approach"])))
    (is (designed-product? product worked-results) "the worked product ran red by design")
    (is (not (designed-product? #{} worked-results)) "an empty product is no handoff")
    (is (not (designed-product? product (update-vals worked-results #(assoc % :seon.test/fail-count 0 :seon.test.failure/reports []))))
        "a green product test is no designed red")))

(deftest ^{:seon/tasks #{'seon.step/implement}} implement-handoff
  (let [[database {intent :seon.task/intent} {step :seon.task/step}] (judged)]
    (is (= :implement step))
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost" "approach"]))
        "the cumulative document holds and its references resolve")))

(deftest ^{:seon/tasks #{'seon.task/chain-intake-approval}}
  survey-waits-for-the-latest-user-approval
  ;; TASK-CHAIN I3 (`docs/seon/schedule/cards/TASK-CHAIN.md#interfaces`)
  (support/with-running-agent
   (fn [running _ connection]
     (let [chain! (requiring-resolve 'seon.task/chain!)
           answer! (requiring-resolve 'my.agent/answer!)
           survey-task 'work/survey-fix
           _ (chain! connection {:seon.task/ns 'work :seon.task/name "fix" :seon.task/intent "Fix the observed failure."
                                 :seon.task/answer-schema :seon.task/approval}
                     templates {})
           {question :seon.test/question survey :seon.db/connection handle :seon.turn.loop/cluster}
           (support/user-reply! running "survey" "Approve this intent?" "Not yet")
           replies (fn [] (db/q '[:find [?id ...] :in $ ?q
                                  :where [?c :seon.message/id ?q] [?m :seon.message/caused-by ?c] [?m :seon.message/id ?id]]
                                (db/db survey) question))
           latest (fn [seen] (first (remove (set seen) (replies))))
           answer (fn [reply approved?] (answer! {:seon.db/connection survey :seon.task/sym survey-task
                                                  :my.message/id reply :seon.answer/value {:seon.task/approved? approved?}}))
           survey-green? (fn [] (:seon.test/passed? (seon.test/run {:seon.test/policy :named :seon.test/execution handle
                                                                    :seon.test/recording-connection survey
                                                                    :seon.test/task survey-task
                                                                    :seon.test/changed ['seon.step-test/survey-handoff]})))
           first-reply (latest [])]
       (is (false? (survey-green?)) "no answer: the send and the wait alone never complete Survey")
       (answer first-reply false)
       (is (false? (survey-green?)) "a rejection stays red")
       (support/user-post! running "survey" question "Approve")
       (let [approving (latest [first-reply])]
         (answer approving true)
         (support/user-post! running "survey" question "On second thought, no")
         (answer (latest [first-reply approving]) false)
         (is (false? (survey-green?)) "an older approval followed by a rejection stays red")
         (support/user-post! running "survey" question "Approve now")
         (answer (latest [first-reply approving]) true)
         (is (true? (survey-green?)) "the latest matching approval makes the Survey proof green")
         (support/transacted! connection [[:db/add [:seon.task/id (task/task-handle 'work "fix")]
                                           :seon.task/intent "Fix the observed failure and its neighbor."]])
         (is (false? (survey-green?)) "an approval of an earlier intent stays red"))))))

(deftest a-default-judges-the-task-it-is-given
  ;; `docs/seon/schedule/cards/TASK-CHAIN.md#4-off-the-shelf-templates`: a clone runs its copied default with
  ;; `:seon.test/task`; the inputless run judges the template; evidence never crosses tasks.
  (support/with-database
   (fn [connection]
     (task/chain! connection {:seon.task/ns 'work :seon.task/name "fix" :seon.task/intent "Fix the observed failure."}
                  '[seon.step/define] {})
     (let [run (fn [task] (seon.test/run (cond-> {:seon.test/execution (support/execution-handle connection)
                                                  :seon.test/recording-connection connection :seon.test/policy :named
                                                  :seon.test/identities #{'seon.step-test/define-handoff}}
                                           task (assoc :seon.test/task task))))
           counts (juxt :seon.test/passed? :seon.test/executed-count :seon.test/reused-count)
           template (run nil)
           clone (run 'work/define-fix)
           clone-again (run 'work/define-fix)
           template-again (run nil)]
       (is (= [true 1 0] (counts template)) "the inputless run judges its one tagged template, whose worked example passes")
       (is (= [false 1 0] (counts clone)) "the clone's document has no Problem section: its run executes, never reusing the template's green")
       (is (= [false 0 1] (counts clone-again)) "the same task input reuses its own result")
       (is (= [true 1 0] (counts template-again)) "the latest result is the clone's, so the template's input executes again")
       (support/transacted! connection [[:db/add [:seon.task/id (task/task-handle 'work "fix")] :seon.task/intent
                                         (str "## Problem\n\nIt fails.\n\n## What exists\n\nThe owner.\n\n"
                                              "## Examples\n\nOne.\n\n## Expected cost\n\nNone claimed.\n")]])
       (let [database (db/db connection)
             {intent :seon.task/intent} (task/effective database (task/task-handle 'work "define-fix"))]
         (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost"]))
             "the refined clone document has every Define section"))
       (is (= [true 1 0] (counts (run 'work/define-fix))) "a refined clone document runs again and passes")
       (task/chain! connection {:seon.task/ns 'work :seon.task/name "other" :seon.task/intent "Another goal."} '[seon.step/define] {})
       (is (= [true 0 1] (counts (run 'work/define-fix))) "a write to another task leaves the clone's input unchanged")))))
