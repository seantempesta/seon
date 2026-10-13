(ns seon.context
  "What a model pass sends and the current-state block it ends with.

  `model-context` is the one assembler: `(help)` as the system message, the
  agent's own model exchanges, then the block. Nothing of it is stored; an
  attempt records the basis it was assembled at."
  (:require [seon.batch :as batch]
            [seon.print :as print]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.source :as source]
            [seon.program :as program]
            [seon.cluster.message :as message]
            [seon.config :as config]
            [seon.error.refusal]
            [seon.eval]
            [seon.render]
            [seon.repl :as repl]
            [seon.sci.eval :as sci.eval]
            [seon.problems :as problems]
            [seon.ref :as ref]
            [seon.task :as task]
            [seon.test.runner :as runner]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Message custody in one run's rendered context
;;; ---------------------------------------------------------------------------

(defn message-custody
  "Classify one message relative to the run whose prompt is being rendered.

  DERIVED FROM `:t`, LIKE EVERY OTHER ANSWEREDNESS QUESTION. A turn's
  own transaction is the basis its context projected from, so a message
  is in this turn's context exactly when its wake datom's `:t` is at or
  before the turn's; it is what this turn is ABOUT when no earlier turn
  of the same agent had already seen it; and it arrived too late for
  this turn when its `:t` is greater. The recorded trigger reference
  this replaces could not express two messages in one transaction, and
  it swallowed a message transacted with the turn that answered it."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:maybe :seon.turn/id]
         :seon.agent/id
         :int]
    :keyword]}
  [database run-id agent-id message-eid]
  (if-not run-id
    ::history
    (let [message-t
          (db/q '[:find ?tx .
                  :in $ ?agent-id ?message
                  :where
                  [?agent :seon.agent/id ?agent-id]
                  [?message :seon.message/to ?agent ?tx]]
                database agent-id message-eid)
          run-t (db/q '[:find ?tx .
                        :in $ ?run-id
                        :where
                        [?run :seon.turn/id ?run-id ?tx]]
                      database run-id)]
      (cond
        (or (nil? message-t) (nil? run-t)) ::history
        (> message-t run-t) ::pending
        :else
        (let [previous-t
              (or (db/q '[:find (max ?tx) .
                          :in $ ?agent-id ?run-t
                          :where
                          [?agent :seon.agent/id ?agent-id]
                          [?run :seon.turn/agent ?agent]
                          [?run :seon.turn/id _ ?tx]
                          [(< ?tx ?run-t)]]
                        database agent-id run-t)
                  0)]
          (if (> message-t previous-t) ::current-trigger ::history))))))

;;; ---------------------------------------------------------------------------
;;; The current-state block
;;; ---------------------------------------------------------------------------

;; Every failing assertion of a red proof, in the order `seon.render.test` shows (file, line, report id)
;; so two passes show the same list. The
;; runner stores a claim's expected and actual as the printer's shown text; they are handed back as printed
;; values, so the block shows that text once, unchanged.
(def ^:private failure-keys
  {:seon.error/type :seon.error/type
   :seon.test.failure/type :type :seon.test.failure/message :message
   :seon.test.failure/testing-contexts :testing :seon.test.failure/reported-file :file
   :seon.test.failure/line :line :seon.test.failure/expected :expected
   :seon.test.failure/actual :actual})

(defn- proof-failures
  {:malli/schema [:=> [:cat [:or :seon.test/result :seon.test/result-unknown]]
                  [:or :nil :seon.context/proof-failures]]}
  [result]
  (when-let [reports (seq (:seon.test.failure/reports result))]
    (mapv #(cond-> (update-keys (select-keys % (keys failure-keys)) failure-keys)
             (:seon.test.failure/expected %) (update :expected print/printed)
             (:seon.test.failure/actual %) (update :actual print/printed))
          (sort-by (juxt #(get % :seon.test.failure/reported-file "") #(get % :seon.test.failure/line 0) :seon.test.report/id) reports))))

(defn- proof-status
  "A proof's status from its latest result and its current reach digest."
  {:malli/schema [:=> [:cat [:or :nil :seon.test/result :seon.test/result-unknown] [:or :nil :seon.test/reach-digest]]
                  :seon.context/proof-status]}
  [result current]
  (cond
    (or (nil? result) (:seon.error/at result) (nil? current)
        (not= current (:seon.test.member/reach-digest result))) :pending
    (and (pos? (:seon.test/pass-count result))
         (= 0 (:seon.test/fail-count result) (:seon.test/error-count result))) :green
    :else :red))

(defn- head-entries
  "Each of `names` `database` holds, by symbol, with its status at its current reach and, when red, its
  failing assertions; the reads' refusal when one refuses."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.test/sym]]
                  [:or :seon.context/head :seon.error/value]]}
  [database names]
  (let [names (into [] (filter #(seq (db/datoms database :avet :seon.test/sym %))) (sort names))
        digests (when (seq names) (runner/reach-digests database names))
        results (when (seq names) (runner/latest-results database names))]
    (or (some #(when (:seon.error/at %) %) [digests results])
        (let [by-sym (into {} (map (juxt #(or (:seon.test/sym %) (:seon.test/error-test-symbol %)) identity)) results)]
          (into {} (for [s names
                         :let [result (by-sym s)
                               status (proof-status result (get digests s))
                               failures (when (= :red status) (proof-failures result))]]
                     [s (cond-> {:seon.context/proof-status status}
                          failures (assoc :seon.context/proof-failures failures))]))))))

(defn proofs
  "The proofs section for the task `sym` on `database` (the agent's branch), its assigned obligations'
  (`seon.task/assigned-obligations`): counts first, then each proof with
  its status and, when red, its failures. Its source is its resolution among the task's references
  (`task-sections`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym]
                  [:or :nil :seon.context/proofs :seon.error/value]]}
  [database sym]
  (let [owed (task/assigned-obligations database sym)
        names (when-not (:seon.error/at owed) (vec (sort (:seon.task/proofs owed))))]
    (if (empty? names)
      (when (:seon.error/at owed) owed)
      (let [head (head-entries database (set names))]
        (or (when (:seon.error/at head) head)
            (let [entries (mapv #(merge {:seon.test/sym % :seon.context/proof-status :pending} (head %)) names)]
              {:seon.context/counts (frequencies (map :seon.context/proof-status entries))
               :seon.context/proof-list entries}))))))

(defn- designed-red?
  "True when the head entry `entry` is red by at least one failing report: a failing assertion or an error its body
  raised. A red with no report ran nothing; a test that cannot load never runs."
  {:malli/schema [:=> [:cat [:maybe :seon.context/observed]] :boolean]}
  [entry]
  (boolean (and (= :red (:seon.context/proof-status entry)) (seq (:seon.context/proof-failures entry)))))

(defn- reds
  "The head's reds split by the fork point: `:seon.context/broken`, present there and not red there;
  `:seon.context/new-red`, absent there."
  {:malli/schema [:=> [:cat :seon.context/head :seon.context/fork-point]
                  [:map [:seon.context/broken :seon.context/broken] [:seon.context/new-red :seon.context/new-red]]]}
  [head fork-point]
  (let [red (into #{} (comp (filter #(= :red (:seon.context/proof-status (val %)))) (map key)) head)]
    {:seon.context/broken (into #{} (filter #(false? (get fork-point %))) red)
     :seon.context/new-red (into #{} (remove #(contains? fork-point %)) red)}))

(defn unmet
  "Each clause of the step's done rule that `observation` does not meet, by clause, with the tests or functions
  it names: `:proofs`, its proofs not green at the head (empty when an Implement step declares none);
  `:product`, a Prove step's product tests at the head red by no failing report (empty when it
  declares none); `:product-absent`, those the head lacks; `:broken`, what its change
  broke; `:new-red`, new reds outside its proofs (Implement) or product (Prove); and
  `:uncontracted`, the functions its merge would refuse, for every step: done means the merge would accept.
  Empty when the step is done; `step-status` judges by it."
  {:malli/schema [:=> [:cat :seon.context/step-observation] :seon.context/unmet]}
  [{step :seon.task/step proofs :seon.task/proofs product :seon.task/product
    head :seon.context/head fork-point :seon.context/fork-point uncontracted :seon.context/uncontracted}]
  (let [{broken :seon.context/broken new-red :seon.context/new-red} (reds head fork-point)
        [owed-clause owed] (if (= :prove step) [:product product] [:proofs proofs])
        clauses (cond-> {:proofs (into #{} (remove #(= :green (get-in head [% :seon.context/proof-status]))) proofs)
                         :broken broken
                         :new-red (into #{} (remove owed) new-red)}
                  (= :prove step) (assoc :product (into #{} (filter #(and (head %) (not (designed-red? (head %))))) product)
                                         :product-absent (into #{} (remove head) product)))
        clauses (assoc clauses :uncontracted (set uncontracted))]
    (cond-> (into {} (filter (comp seq val)) clauses)
      (empty? owed) (assoc owed-clause #{}))))

(defn step-status
  "The status of a step from one observation: `:seon.context/broken`, the head's reds present at the fork point
  and not red there; `:seon.context/new-red`, the head's reds absent there; `:seon.context/green-before`, its
  proofs known green there, when any; and `:seon.task/status`. An `:implement` step is `:satisfied` when its
  proofs are nonempty, all green and all known green at the fork point: it has nothing to do. A step is `:done`
  when its done rule has no `unmet` clause, and for every step no function its merge would refuse for lacking a
  complete contract (`:seon.context/uncontracted`, named beside its status): an Implement step's proofs nonempty
  and all green, nothing broken and every new red a proof; a Prove step's own proofs all green, its product
  nonempty and every product test red at the head by a failing report (a failing assertion or an error its body
  raised), nothing broken and every new red a product test (the rest its fork point already held red). A Prove
  step is `:satisfied` when its fork point already held its whole product red and nothing is new: main holds its
  product. Any other step is `:working`."
  {:malli/schema [:=> [:cat :seon.context/step-observation] :seon.context/step-status]}
  [{step :seon.task/step proofs :seon.task/proofs product :seon.task/product
    head :seon.context/head fork-point :seon.context/fork-point uncontracted :seon.context/uncontracted :as observation}]
  (let [{broken :seon.context/broken new-red :seon.context/new-red} (reds head fork-point)
        green-before (into #{} (filter #(false? (get fork-point %))) proofs)
        green? (fn [tests] (every? #(= :green (get-in head [% :seon.context/proof-status])) tests))
        satisfied? (case step
                     :implement (and (seq proofs) (green? proofs) (= (count green-before) (count proofs)))
                     :prove (and (seq product) (green? proofs) (empty? broken) (empty? new-red)
                                 (every? #(true? (get fork-point %)) product) (every? (comp designed-red? head) product)))]
    (cond-> {:seon.task/status (cond satisfied? :satisfied (empty? (unmet observation)) :done :else :working)
             :seon.context/broken broken :seon.context/new-red new-red}
      (seq green-before) (assoc :seon.context/green-before green-before)
      (seq uncontracted) (assoc :seon.context/uncontracted uncontracted))))

(defn held-task
  "The effective task `agent-id` holds, read on `database`, its world (its branch holds its assignment): its
  subject's goal merged with its own declaration (`seon.task/effective`); nil when it holds none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :nil :seon.context/task :seon.error/value]]}
  [database agent-id]
  (let [handle (task/agent-task database agent-id)]
    (cond (:seon.error/at handle) handle
          (string? handle)
          (task/effective database handle))))

(defn- task-references
  "Every reference the held task makes, resolved once on `database` by the one rule (`seon.ref/references`): the
  names and paths its effective intent and instructions cite and its `proofs`. The tasks it needs are its chain's
  steps before it (`chain`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.context/task [:set :seon.test/sym]]
                  [:vector [:or :seon.ref/resolved :seon.ref/problem :seon.error/value]]]}
  [database {:seon.task/keys [intent instructions]} proofs]
  (ref/references database {:seon.ref/text (cond-> intent instructions (str "\n\n" instructions))
                            :seon.ref/names proofs}))

(def ^:private merged-families
  "The key each declaration family a merge moved is shown under, by its identity attribute."
  {:seon.fn/sym :seon.context/functions :seon.schema/key :seon.context/schemas
   :seon.test/sym :seon.context/tests :seon.task/sym :seon.context/tasks})

(defn- merged
  "What main's merge `commit` moved (`seon.cluster.source/merge-accepted`), shown on `database` by family, each
  name one level (`seon.ref/brief`): its summary and the form that reads it, never its content. Families are
  absent when empty."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/commit-id] [:or :seon.context/merged :seon.error/value]]}
  [database commit]
  (let [accepted (source/merge-accepted (db/branch-connection database (get-in database [:config :branch])) commit)]
    (if (:seon.error/at accepted)
      accepted
      (let [shown (reduce (fn [shown [attribute value]]
                            (if-let [family (merged-families attribute)]
                              (update shown family (fnil conj []) (ref/brief database value))
                              shown))
                          {} (sort-by (comp str second) (:seon.program/identities accepted)))]
        (or (some #(when (:seon.error/at %) %) (mapcat val shown)) shown)))))

(defn- step-before
  "The step `sym` the held task needs, as its chain shows it on `database`: its kind and, once main merged an
  assignment of it, `:seon.task/outcome` `:merged` with what that merge moved (`merged`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym] [:or :seon.context/step-before :seon.error/value]]}
  [database sym]
  (let [task (db/pull database [:seon.task/step] [:seon.task/sym sym])
        merges (db/q '[:find ?a ?tx :in $ ?s :where [?t :seon.task/sym ?s] [?a :seon.task/attempt-of ?t]
                       [?a :seon.task/outcome :merged ?tx]]
                     database sym)
        latest (when (and (seq merges) (not (:seon.error/at merges))) (first (apply max-key second merges)))
        commit (when latest (:seon.task/merge-commit (db/pull database [:seon.task/merge-commit] latest)))
        moved (when commit (merged database commit))]
    (or (some #(when (:seon.error/at %) %) [task merges moved])
        (cond-> {:seon.task/sym sym :seon.task/step (get task :seon.task/step :implement)}
          latest (assoc :seon.task/outcome :merged)
          (seq moved) (assoc :seon.context/merged moved)))))

(defn- chain
  "The chain the held task `id` is a step of, on `database`: its subject (the task whose `:seon.task/children`
  holds it), the steps before it (its `:seon.task/needs`, `step-before`) and the steps after it (the tasks that
  need it, each with its kind and instructions); nil when it has none of the three."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] [:or :nil :seon.context/chain :seon.error/value]]}
  [database id]
  (let [subject (db/q '[:find ?s . :in $ ?id :where [?t :seon.task/id ?id] [?p :seon.task/children ?t] [?p :seon.task/sym ?s]]
                      database id)
        needs (db/q '[:find [?s ...] :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/needs ?n] [?n :seon.task/sym ?s]]
                    database id)
        successors (db/q '[:find [?s ...] :in $ ?id :where [?t :seon.task/id ?id] [?n :seon.task/needs ?t] [?n :seon.task/sym ?s]]
                         database id)
        read-error (some #(when (:seon.error/at %) %) [subject needs successors])
        before (when-not read-error (mapv #(step-before database %) (sort needs)))
        after (when-not read-error
                (mapv #(let [task (db/pull database [:seon.task/sym :seon.task/step :seon.task/instructions] [:seon.task/sym %])]
                         (if (:seon.error/at task) task (merge {:seon.task/step :implement} task)))
                      (sort successors)))]
    (or read-error
        (some #(when (:seon.error/at %) %) (concat before after))
        (when (or subject (seq before) (seq after))
          (cond-> {}
            subject (assoc :seon.context/subject subject)
            (seq before) (assoc :seon.context/before before)
            (seq after) (assoc :seon.context/after after))))))

(defn send-backs
  "The latest send-back to the task `handle` on `database`: the attempt of another step that ended
  `:sent-back` targeting it, as its sender and the reason it recorded (`seon.turn/end-task-call`); a vector,
  empty when none was sent. The relaunched agent reads why it was sent back."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] :seon.context/send-backs]}
  [database handle]
  (let [sent (db/q '[:find ?tx ?sender ?reason :in $ ?handle
                     :where [?t :seon.task/id ?handle] [?a :seon.task.attempt/target ?t]
                     [?a :seon.task.attempt/end :sent-back ?tx] [?a :seon.task.attempt/reason ?reason]
                     [?a :seon.task/attempt-of ?s] [?s :seon.task/sym ?sender]]
                   database handle)]
    (if-let [[_ sender reason] (when (seq sent) (apply max-key first sent))]
      [{:seon.task/sym sender :seon.task.attempt/reason reason}]
      [])))

(defn- base-results
  "Each of `proofs` with a known result at `base`, the fork point of the agent's branch `database`
  (`seon.turn/fork-commit`): the run the branch already holds at the reach digest the proof has in that commit
  (`seon.test.runner/result-at-reach`), true when red, false when green. A proof with no run there, or absent
  there, is absent: unknown, never green."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:set :seon.test/sym]]
                  [:or :seon.context/fork-point :seon.error/value]]}
  [database base proofs]
  (let [names (when (seq proofs) (into [] (filter #(seq (db/datoms base :avet :seon.test/sym %))) (sort proofs)))
        digests (when (seq names) (runner/reach-digests base names))
        results (when (and (seq digests) (not (:seon.error/at digests))) (runner/result-at-reach database digests))]
    (or (some #(when (:seon.error/at %) %) [digests results])
        (into {} (keep (fn [[s result]] (case (proof-status result (get digests s)) :green [s false] :red [s true] nil)))
              results))))

(defn- contract-gaps
  "The gaps (`seon.cluster.source/contract-gaps`) in the contracts of the functions the agent `agent-id`'s branch
  `database` changed since its task's merge base: what that merge refuses. The merge base is the one its
  attempt row holds (a relaunched attempt keeps its predecessor's, so the change is the task's across attempts),
  for a row holding none the commit the branch forked at (`seon.turn/fork-commit`); nil on main itself."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :nil [:vector :seon.fn.contract/finding-row] :seon.error/value]]}
  [database agent-id]
  (let [held (db/q '[:find ?m . :in $ ?id :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/merge-base ?m]]
                   database agent-id)
        commit (if (uuid? held) held (turn/fork-commit database))
        base (when commit (db/commit-database database commit))]
    (cond (:seon.error/at held) held
          (nil? base) nil
          :else (try (let [changed (program/changed-identities (db/basis-t base) database source/merge-bound)]
                       (if (:seon.error/at changed) changed (source/contract-gaps database changed)))
                     (finally (source/release! [base]))))))

(defn step-observation
  "The observation the held task `sym`'s step is judged by on `database`, the agent's branch: its obligations
  over its assigned basis (`seon.task/assigned-obligations`), the head's entries for them and for `judged`, the tests the agent's test step
  found red there (`seon.turn/record-broken!`), and the fork point as that step observed it: each judged test
  present there unless it was new, red there unless it was broken. Called with `judged` nil, the agent's row
  supplies the step's last record, a test the fork point (`seon.turn/fork-commit`, main at the merge base) lacks is
  new whatever the record says (a relaunched attempt's fork holds its predecessor's unmerged work, and its fresh
  row records nothing), each proof's known result there (`base-results`) replaces the record and the contract gaps
  its merge would refuse (`contract-gaps`) are observed, for every step."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.task/sym
                       [:maybe [:map [:seon.context/broken [:set :seon.test/sym]] [:seon.context/new-red [:set :seon.test/sym]]]]]
                  [:or :seon.context/step-observation :seon.error/value]]}
  [database agent-id sym judged]
  (let [owed (task/assigned-obligations database sym)
        row (when-not judged (db/pull database [:seon.agent/broken :seon.agent/new-red] [:seon.agent/id agent-id]))
        broken (set (or (:seon.context/broken judged) (:seon.agent/broken row)))
        new-red (set (or (:seon.context/new-red judged) (:seon.agent/new-red row)))
        head (when-not (:seon.error/at owed)
               (head-entries database (into broken (concat new-red (:seon.task/proofs owed) (:seon.task/product owed)))))
        commit (when (and (not judged) (map? head) (not (:seon.error/at head))) (turn/fork-commit database))
        fork (when commit (db/commit-database database commit))
        held? (if fork #(seq (db/datoms fork :avet :seon.test/sym %)) (constantly true))
        base (when fork (base-results database fork (set (filter head (:seon.task/proofs owed)))))
        refused (when-not (or judged (:seon.error/at owed)) (contract-gaps database agent-id))]
    (or (some #(when (:seon.error/at %) %) [owed head base refused])
        (cond-> (assoc owed :seon.context/head head
                            :seon.context/fork-point (merge (into {} (for [s (keys head) :when (and (not (new-red s)) (held? s))]
                                                                       [s (not (broken s))]))
                                                            base))
          (seq refused) (assoc :seon.context/uncontracted (source/gap-symbols refused)
                                          :seon.context/contract-gaps (mapv #(select-keys % [:seon.fn/sym :seon.fn.contract/position :seon.fn.contract/finding]) refused))))))

(defn step-verdict
  "The held task's step on `database`, the agent's branch, judged by `step-status` over `step-observation`: the
  observation with its status; nil when the agent holds no task."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :seon.context/step-verdict :seon.error/value]]}
  [database agent-id]
  (let [held (held-task database agent-id)
        observation (when (and held (not (:seon.error/at held)))
                      (step-observation database agent-id (:seon.task/sym held) nil))]
    (cond (:seon.error/at held) held
          (:seon.error/at observation) observation
          observation (merge observation (step-status observation)))))

(defn step-section
  "The step section of `verdict` (`step-verdict`), as the agent reads it: the step's kind and status, the tests its
  rule counted broken and new red, for a working step each clause of its done rule it does not meet (`unmet`), then each
  test that keeps it from done beside its proofs: the tests its change broke, for a Prove step the product it
  owes its successors, and any other new red; each with its status and, when red, its failing assertions; and
  the functions its merge would refuse for lacking a complete contract. Lists are absent when empty."
  {:malli/schema [:=> [:cat :seon.context/step-verdict] :seon.context/step]}
  [{step :seon.task/step proofs :seon.task/proofs product :seon.task/product head :seon.context/head
    broken :seon.context/broken new-red :seon.context/new-red status :seon.task/status
    uncontracted :seon.context/uncontracted gaps :seon.context/contract-gaps :as verdict}]
  (let [entries (fn [tests] (mapv #(merge {:seon.test/sym % :seon.context/proof-status :pending} (head %)) (sort tests)))
        product (when (= :prove step) product)
        other (remove (into (set proofs) product) new-red)]
    (cond-> {:seon.task/step step :seon.task/status status :seon.context/broken broken :seon.context/new-red new-red}
      (= :working status) (assoc :seon.context/unmet (unmet verdict))
      (seq broken) (assoc :seon.context/broken-tests (entries broken))
      (seq product) (assoc :seon.context/product-tests (entries product))
      (seq other) (assoc :seon.context/new-red-tests (entries other))
      (seq uncontracted) (assoc :seon.context/uncontracted uncontracted)
      (seq gaps) (assoc :seon.context/contract-gaps gaps))))

(defn task-finished?
  "True when the held task's step is done or satisfied.
  The step is the one the task `agent-id` holds on `database`, the agent's branch (`step-verdict`): nothing is left
  for a model turn to do."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :boolean]}
  [database agent-id]
  (contains? #{:done :satisfied} (:seon.task/status (step-verdict database agent-id))))

(defn task-done?
  "True when the step of the task `agent-id` holds is done on `database`, the agent's branch (`step-verdict`): the
  task is done for the agent, pending root's merge, and the turn loop opens no continuation for it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :boolean]}
  [database agent-id]
  (= :done (:seon.task/status (step-verdict database agent-id))))

(defn- unshown
  "The inbox `items` no turn of `agent-id` has handled: a closed model turn's
  settlement records each message its pass was shown (`seon.turn/close-call`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :my.message/inbox]
                  [:or :my.message/inbox :seon.error/value]]}
  [database agent-id items]
  (let [handled (db/q '[:find [?id ...] :in $ ?agent-id
                        :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent]
                        [?turn :seon.turn/handled ?message] [?message :seon.message/id ?id]]
                      database agent-id)]
    (if (:seon.error/at handled)
      handled
      (into [] (remove (comp (set handled) :my.message/id)) items))))

(defn- branch-cost
  "The branch's stored cost entities (`:seon.fn.cost/entity`) that differ from
  main's (a sample digest main does not hold, or main stores none for that
  symbol and scope), as `seon.batch/cost-diff` comparisons, like with like
  only: the most self time spent first, at most `:seon.config.test.cost/top`,
  the dials of the branch's effective configuration. Main is read, for this
  comparison only, as root's home branch (`seon.task/main-head`). Nil on main itself, the branch stores none, its program
  declares no cost entities or its configuration is refused."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil :seon.cost/diff]]}
  [database]
  (let [main (task/main-head database)
        cluster (when main (db/q '[:find ?n . :where [_ :seon.config/cluster ?n]] database))
        effective (when cluster (config/effective database cluster))
        cost-keys (when (and effective (not (:seon.error/at effective)))
                    (db/q database '[:find [?k ...] :where [_ :seon.fn.cost/key ?k]]))]
    (when (and (seq cost-keys) (not (db/read-refusal? cost-keys)))
      (let [stored (db/stored-costs main cost-keys)
            unchanged? (fn [entity]
                         (let [main-entity (get stored (:seon.fn.cost/key entity))]
                           (every? #(= (first (batch/sample entity %)) (first (batch/sample main-entity %)))
                                   (filter #(batch/sample entity %) [:warm :cold]))))
            live (->> (vals (db/stored-costs database cost-keys))
                      (remove unchanged?)
                      (sort-by #(- (batch/spent %))))
            diff (batch/cost-diff (vec live) stored (:seon.config.fn.cost/min-calls effective))]
        (not-empty (into [] (take (:seon.config.test.cost/top effective)) diff))))))

(defn- agent-namespace
  "The namespace `agent-id` is in: where its latest evaluation ended (its SCI context keeps `*ns*`),
  else the namespace of the task `held`; nil with neither."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:maybe :seon.context/task]]
                  [:maybe :seon.ns/name]]}
  [database agent-id held]
  (or (when-let [eid (:db/id (db/pull database [:db/id] [:seon.agent/id agent-id]))]
        (turn/previous-ending-namespace database eid))
      (some-> (:seon.task/sym held) namespace symbol)))

(defn- branch-end-summary
  "What the agent `agent-id` did on `branch-db` toward task `sym`: its model calls and tokens and its proofs'
  counts and red proofs; an error when a read refuses."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:maybe :seon.task/sym]]
                  [:or [:map [:seon.task/calls :seon.task/calls] [:seon.task/prompt-tokens :seon.task/prompt-tokens]
                        [:seon.task/completion-tokens :seon.task/completion-tokens]
                        [:seon.task/proof-counts {:optional true} :seon.task/proof-counts]
                        [:seon.task/red-proofs {:optional true} :seon.task/red-proofs]]
                   :seon.error/value]]}
  [branch-db agent-id sym]
  (let [proven (when sym (proofs branch-db sym))
        attempts (db/q '[:find ?turn (pull ?attempt [:seon.ai.usage/prompt-tokens :seon.ai.usage/completion-tokens])
                         :in $ ?agent-id
                         :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent] [?turn :seon.turn/attempts ?attempt]]
                       branch-db agent-id)
        total #(transduce (keep (comp % second)) + 0 attempts)]
    (or (some #(when (:seon.error/at %) %) [proven attempts])
        (cond-> {:seon.task/calls (count (into #{} (map first) attempts))
                 :seon.task/prompt-tokens (total :seon.ai.usage/prompt-tokens)
                 :seon.task/completion-tokens (total :seon.ai.usage/completion-tokens)}
          proven (assoc :seon.task/proof-counts (:seon.context/counts proven)
                        :seon.task/red-proofs (into [] (comp (filter #(= :red (:seon.context/proof-status %))) (map :seon.test/sym))
                                                    (:seon.context/proof-list proven)))))))

(def ^:private proof-count-attributes
  "The agent attribute recording each proof status's count."
  {:green :seon.agent/end-green :red :seon.agent/end-red :pending :seon.agent/end-pending})

(defn- end-summary-facts
  "The agent attributes recording `outcome` (a `branch-end-summary`)."
  {:malli/schema [:=> [:cat :map] :map]}
  [outcome]
  (cond-> {:seon.agent/end-calls (:seon.task/calls outcome)
           :seon.agent/end-prompt-tokens (:seon.task/prompt-tokens outcome)
           :seon.agent/end-completion-tokens (:seon.task/completion-tokens outcome)}
    (seq (:seon.task/red-proofs outcome)) (assoc :seon.agent/end-red-proofs (set (:seon.task/red-proofs outcome)))
    true (into (keep (fn [[status attribute]] (when-let [n (get-in outcome [:seon.task/proof-counts status])] [attribute n])))
               proof-count-attributes)))

(defn- recorded-end-summary
  "The outcome recorded on agent `agent-id` when its task ended, as a `branch-end-summary`; nil when none was."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:maybe :map]]}
  [database agent-id]
  (let [row (db/pull database (into [:seon.agent/end-calls :seon.agent/end-prompt-tokens :seon.agent/end-completion-tokens
                                     :seon.agent/end-red-proofs]
                                    (vals proof-count-attributes))
                     [:seon.agent/id agent-id])
        counts (into {} (keep (fn [[status attribute]] (when-let [n (get row attribute)] [status n]))) proof-count-attributes)]
    (when (and (map? row) (contains? row :seon.agent/end-calls))
      (cond-> {:seon.task/calls (:seon.agent/end-calls row)
               :seon.task/prompt-tokens (:seon.agent/end-prompt-tokens row)
               :seon.task/completion-tokens (:seon.agent/end-completion-tokens row)}
        (seq counts) (assoc :seon.task/proof-counts counts)
        (seq counts) (assoc :seon.task/red-proofs (vec (sort (:seon.agent/end-red-proofs row))))))))

(defn end-summary-tx
  "Transaction data recording the outcome of the task `agent-id` holds, derived from its branch in `database`
  (calls, tokens, proof counts and red proofs): what the end summary needs after the branch is retired. Empty
  when the agent has no branch, no task or its evidence is unreadable, so a summary never reads zeros for evidence
  that was not there."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] :seon.store/transaction-data]}
  [database agent-id]
  (let [branch (db/q '[:find ?b . :in $ ?id :where [?a :seon.agent/id ?id] [?a :seon.agent/branch ?b]] database agent-id)
        handle (task/agent-task database agent-id)
        sym (when (string? handle) (db/q '[:find ?s . :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/sym ?s]] database handle))
        branch-db (when (keyword? branch) (db/branch-database database branch))
        outcome (when branch-db (branch-end-summary branch-db agent-id sym))]
    (if (or (nil? outcome) (:seon.error/at outcome))
      []
      [(assoc (end-summary-facts outcome) :seon.agent/id agent-id)])))

(defn ended
  "The value a task's end message `entry` (an inbox entry carrying `:seon.task/end`) stands for: the task, how it
  ended, its proofs' counts and red proofs, the agent's errors grouped by signature, its model calls and tokens,
  the merge task when it ended green with its landing (the commits it made, or the refusal it met), and the
  operations root takes next. The outcome is the one recorded when
  the task ended, else derived from the agent's branch, else unknown (`:seon.task/end-unknown`), never zeros."
  {:malli/schema [:=> [:cat :seon.db/database-value :my.message/inbox-entry] [:or :seon.task/ended :seon.error/value]]}
  [database {agent-id :my.message/from task-id :seon.message/about end :seon.task/end merge :seon.task/merge}]
  (let [branch (db/q '[:find ?b . :in $ ?id :where [?a :seon.agent/id ?id] [?a :seon.agent/branch ?b]] database agent-id)
        sym (db/q '[:find ?s . :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/sym ?s]] database task-id)
        recorded (recorded-end-summary database agent-id)
        branch-db (when (keyword? branch) (db/branch-database database branch))
        outcome (or recorded (when branch-db (branch-end-summary branch-db agent-id sym)))
        ;; the merge request is the agent's latest assignment of the task
        assignments (when merge
                      (db/q '[:find ?n ?k :in $ ?id ?h :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g]
                              [?a :seon.task/assigned ?h] [?a :seon.task/assignment ?k]
                              [(get-else $ ?a :seon.task.attempt/number 0) ?n]]
                            database agent-id task-id))
        assignment (when (set? assignments) (second (last (sort assignments))))
        landing (when merge
                  (let [row (db/pull database [:seon.task/landed :seon.task/landed-git :seon.task/refusal :seon.task/refused-tests]
                                     [:seon.task/id merge])]
                    (when (map? row) (cond-> (dissoc row :db/id)
                                       (:seon.task/refused-tests row) (update :seon.task/refused-tests set)))))]
    (or (when (:seon.error/at outcome) outcome)
        (cond-> (into {:seon.task/id task-id :seon.task/end end :seon.agent/id agent-id
                        :seon.render/operations
                        (cond-> [(list 'seon.context/model-context (list 'seon.db/branch-database (list 'seon.db/db) branch) {} agent-id)]
                          (string? assignment) (conj (list 'my.root/merge! {:seon.task/assignment assignment}))
                          sym (conj (list 'seon.task/create! {:seon.task/ns (symbol (namespace sym)) :seon.task/name (name sym)
                                                              :seon.task/basis task-id})))}
                       outcome)
          (nil? outcome) (assoc :seon.task/end-unknown true)
          sym (assoc :seon.task/sym sym)
          (keyword? branch) (assoc :seon.agent/branch branch)
          merge (assoc :seon.task/merge merge)
          (seq landing) (into landing)
          branch-db (assoc :seon.task/errors (problems/agent-errors-by-signature database agent-id branch))))))

(defn- step-start
  "Where the step of `held` begins: the form that enters the namespace owning it and, for a :prove step, the form
  that enters the namespace of each test its instructions cite (`seon.ref/names`); each must-read carries the form
  that reads its source."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.task/sym :seon.task/sym] [:seon.task/instructions {:optional true} :seon.task/instructions]]
                       :seon.task/step]
                  :seon.context/start]}
  [database {:seon.task/keys [sym instructions]} step]
  (let [owner (symbol (namespace sym))
        tests (when (and (= :prove step) instructions)
                (->> (ref/names instructions)
                     (filter #(map? (db/pull database [:db/id] [:seon.test/sym %])))
                     (into (sorted-set) (map (comp symbol namespace)))))]
    {:seon.render/operations (into [(list 'in-ns (list 'quote owner))]
                                   (map #(list 'in-ns (list 'quote %)))
                                   (disj tests owner))}))

(defn task-sections
  "The held task's sections for `agent-id` on `database`, its branch: the effective task (`seon.task/effective`) by
  handle, symbol, goal and its step's instructions; the chain it is a step of (`chain`); its
  proofs, each placed once with its resolution (its source) beside its status; its step; the latest send-back;
  and every other reference it makes, resolved. Empty when it holds none.

  ## Costs

  One assignment query and one task pull, the proofs' reach digests and latest results, the step observation,
  one send-back query, three chain queries with one `seon.cluster.source/merge-accepted` per merged step before it,
  and one resolution per distinct reference."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :seon.context/task-sections :seon.error/value]]}
  [database agent-id]
  (let [held (held-task database agent-id)]
    (if (or (nil? held) (:seon.error/at held))
      (or held {})
      (let [proven (proofs database (:seon.task/sym held))
            verdict (step-verdict database agent-id)
            sent-back (send-backs database (:seon.task/id held))
            steps (chain database (:seon.task/id held))
            proof-names (into #{} (map :seon.test/sym) (:seon.context/proof-list proven))
            reads (when-not (:seon.error/at proven) (task-references database held proof-names))
            resolution (into {} (keep #(when (proof-names (:seon.ref/name %)) [(:seon.ref/name %) %])) reads)
            others (into [] (remove #(contains? resolution (:seon.ref/name %))) reads)]
        (or (some #(when (:seon.error/at %) %) (concat [proven verdict steps] reads))
            (cond-> {:seon.context/task (select-keys held [:seon.task/id :seon.task/sym :seon.task/intent :seon.task/instructions])}
              steps (assoc :seon.context/chain steps)
              proven (assoc :seon.context/proofs
                            (update proven :seon.context/proof-list
                                    (partial mapv #(merge (dissoc (resolution (:seon.test/sym %)) :seon.ref/name) %))))
              verdict (assoc :seon.context/step
                             (assoc (step-section verdict)
                                    :seon.context/start (step-start database held (:seon.task/step verdict))))
              (seq sent-back) (assoc :seon.context/send-backs sent-back)
              (seq others) (assoc :seon.context/must-reads others)))))))

(defn current-state
  "The current-state block's data for `agent-id`: its held task's sections (`task-sections`), and its turns left with the task's status (`:done` once its step is done,
  `step-verdict`, then `:capped` with no turns left, else `:working`) and the namespace
  it is in (`agent-namespace`). Sections are
  absent when empty. `:seon.context/unread` holds the agent's undelivered messages
  no model turn of it was shown yet, oldest first, each once with its id, sender,
  instant and content. `:seon.context/cost` holds the branch's stored function costs
  that differ from main's (`branch-cost`).

  ## Costs

  The held task's sections (`task-sections`), the turn count, one pull per unread message,
  one query of the agent's handled messages, one scan of the branch's cost entities
  and one query of main's for them."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :seon.context/current-state :seon.error/value]]}
  [database agent-id]
  (let [found (held-task database agent-id)
        held (when-not (:seon.error/at found) found)
        sections (when held (task-sections database agent-id))
        left (turn/turns-left database agent-id)
        unread (let [items (message/inbox database agent-id)]
                 (if (:seon.error/at items)
                   items
                   (let [items (unshown database agent-id items)]
                     (if (:seon.error/at items)
                       items
                       ;; a task's end message is shown with the value its references stand for, derived now
                       (mapv #(if (:seon.task/end %) (assoc % :seon.task/ended (ended database %)) %) items)))))
        cost (branch-cost database)
        namespace (agent-namespace database agent-id held)
        status (get-in sections [:seon.context/step :seon.task/status])]
    (or (some #(when (:seon.error/at %) %) [found sections left unread])
        (cond-> (merge sections
                       {:seon.context/turns (cond-> {:my.agent/turns-left left}
                                              held (assoc :seon.task/status (cond (#{:done :satisfied} status) status
                                                                                  (zero? left) :capped
                                                                                  :else :working))
                                              namespace (assoc :seon.context/namespace namespace))})
          cost (assoc :seon.context/cost cost)
          (seq unread) (assoc :seon.context/unread {:seon.context/items unread
                                                    :seon.render/operations ['(my.message/inbox)]})))))

(defn current-state-ai
  "The block's sections as the agent reads them: the task by symbol and declaration, without its handle.
  The render argument carries the evaluation's world beside the block; only the sections are returned."
  {:malli/schema [:=> [:cat :seon.context/current-state] :seon.context/current-state-view]}
  [block]
  (cond-> (select-keys block [:seon.context/task :seon.context/chain :seon.context/proofs :seon.context/step :seon.context/send-backs :seon.context/must-reads :seon.context/cost :seon.context/unread :seon.context/turns])
    (:seon.context/task block) (update :seon.context/task dissoc :seon.task/id)))

;;; ---------------------------------------------------------------------------
;;; What a model pass sends
;;; ---------------------------------------------------------------------------

(def ^:private exchange-selector
  "An evaluation as `seon.repl/entity-emission` reads it, with its call row's attempts."
  '[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
    {:seon.cluster.eval/run [:seon.turn/id {:seon.turn/attempts [:db/id]}]}])

(defn- exchange-form
  "One evaluation as the REPL showed it: the form as sent and what the REPL answered."
  {:malli/schema [:=> [:cat :seon.repl/emission] [:map :seon.ai/form [:seon.ai/reply {:optional true}]]]}
  [emission]
  (let [reply (repl/reply-text emission)]
    (cond-> {:seon.ai/form (repl/input-text emission)}
      reply (assoc :seon.ai/reply reply))))

(defn- exchanges
  "The agent's own model exchanges in agent order: one per call row carrying a model attempt."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :seon.context/exchanges :seon.error/value]]}
  [database agent-id]
  (let [evaluations (seon.eval/of-agent database agent-id exchange-selector)]
    (if (:seon.error/at evaluations)
      evaluations
      (->> evaluations
           (filter #(seq (get-in % [:seon.cluster.eval/run :seon.turn/attempts])))
           (partition-by #(get-in % [:seon.cluster.eval/run :seon.turn/id]))
           (mapv (fn [run]
                   (mapv #(exchange-form (repl/entity-emission (assoc % :seon.db/db database))) run)))))))

(defn model-context
  "What one model pass sends, as shown text.

  The system message is `(help)`; then the agent's own model exchanges; then
  the current-state block, last. The one assembler the model call reads."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.sci.eval/shown-request :seon.agent/id]
                  [:or :seon.context/model-context :seon.error/value]]}
  [database request agent-id]
  (let [request (assoc request :seon.db/db database :seon.agent/id agent-id)
        history (exchanges database agent-id)
        block (current-state database agent-id)
        profile (seon.render/request-profile request)
        shown #(sci.eval/shown-result %1 %2 nil)
        system (shown (bootstrap/help-value database agent-id) request)
        ;; the root call names the producer whose declared AI render shows the block
        prompt (shown block (assoc request :seon.render.call/function 'seon.context/current-state))
        ;; help carries its own AI render: a request that cannot run it (no context or no
        ;; caps) prints the help map whole, with no renderer; that is a refusal, never the
        ;; system message
        unrendered (not (:seon.eval/renderer system))]
    ;; a request with no context or profile cannot render: that refusal is the answer, never
    ;; the printed help map as the system message
    (or (some #(when (:seon.error/at %) %) [history block profile])
        (when unrendered
          (seon.error.refusal/diagnostic
           (java.util.Date.) :seon.context/model-context `model-context
           (cond-> {:seon.error/type :seon.render/request-error
                    :seon.error/message "The help render did not run, so the system message would be the printed help map."
                    :seon.error/fix "Carry the turn's context, caps and time limit, as the model pass does."}
             (:seon.cluster.eval/diagnostics system)
             (assoc :seon.cluster.eval/diagnostics (:seon.cluster.eval/diagnostics system)))))
        {:seon.ai/system (:seon.eval/shown system)
         :seon.context/exchanges history
         :seon.ai/prompt (:seon.eval/shown prompt)})))
