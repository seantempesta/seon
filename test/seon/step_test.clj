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
  "The task a default deftest judges, its one `:seon/tasks` tag (no `:seon.test/task` input names another), with its
  effective intent and obligations on the bound database."
  {:malli/schema [:=> [:cat :seon.schema/value] [:tuple :seon.db/database-value :seon.task/effective :seon.task/obligations]]}
  [test-var]
  (let [database (db/db)
        sym (first (:seon/tasks (meta test-var)))
        handle (:seon.task/id (db/pull database [:seon.task/id] [:seon.task/sym sym]))]
    [database (task/effective database handle) (task/obligations database sym)]))

(def ^:private worked-results
  "The worked example's product result: an executed run of the table test with one failing assertion."
  {'seon.fs-test/under-path-tells-what-lies-under-a-root
   {:seon.test/pass-count 4 :seon.test/fail-count 1 :seon.test/error-count 0 :seon.test.failure/reports [{:type :fail}]}})

(deftest ^{:seon/tasks #{'seon.step/survey}} survey-handoff
  (let [[database {intent :seon.task/intent}] (judged #'survey-handoff)]
    (is (= [] (document-gaps database intent ["problem" "what-exists"])) "Problem and What exists have content and every reference resolves")))

(deftest ^{:seon/tasks #{'seon.step/define}} define-handoff
  (let [[database {intent :seon.task/intent}] (judged #'define-handoff)]
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost"]))
        "Examples and Expected cost join the earlier sections")
    (is (= #{} (unmeasured-claims database (get (sections intent) "expected-cost"))) "a section citing no deftest makes no claim")
    (is (= '#{seon.fs-test/under-path-tells-what-lies-under-a-root}
           (unmeasured-claims database "`seon.fs-test/under-path-tells-what-lies-under-a-root`"))
        "a cited deftest that measures nothing is an unmet cost claim")
    (is (= #{} (unmeasured-claims database "`seon.task-test/ready-answers-in-under-10-ms-for-fifty-leaves`"))
        "a cited deftest calling seon.profile/measure meets it")))

(deftest ^{:seon/tasks #{'seon.step/design}} design-handoff
  (let [[database {intent :seon.task/intent}] (judged #'design-handoff)]
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost" "approach"]))
        "Approach joins the earlier sections")
    (is (= #{} (uncontracted database (get (sections intent) "approach"))) "every function Approach cites is contracted")))

(deftest ^{:seon/tasks #{'seon.step/prove}} prove-handoff
  (let [[database {intent :seon.task/intent} {step :seon.task/step}] (judged #'prove-handoff)
        product (set (keys worked-results))]
    (is (= :prove step))
    (is (= [] (document-gaps database intent ["problem" "what-exists" "examples" "expected-cost" "approach"])))
    (is (designed-product? product worked-results) "the worked product ran red by design")
    (is (not (designed-product? #{} worked-results)) "an empty product is no handoff")
    (is (not (designed-product? product (update-vals worked-results #(assoc % :seon.test/fail-count 0 :seon.test.failure/reports []))))
        "a green product test is no designed red")))

(deftest ^{:seon/tasks #{'seon.step/implement}} implement-handoff
  (let [[database {intent :seon.task/intent} {step :seon.task/step}] (judged #'implement-handoff)]
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
