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
            [seon.fn :as fn]
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
  "A proof's status from its result at its current identity (`seon.test.runner/current-results`; nil: none)."
  {:malli/schema [:=> [:cat [:or :nil :seon.test/result :seon.test/result-unknown]] :seon.context/proof-status]}
  [result]
  (cond
    (or (nil? result) (:seon.error/at result)) :pending
    (and (pos? (:seon.test/pass-count result))
         (= 0 (:seon.test/fail-count result) (:seon.test/error-count result))) :green
    :else :red))

(defn- head-entries
  "Each of `names` `database` holds, by symbol, with its status at its current reach judging `task` (nil: none)
  and, when red, its failing assertions; the reads' refusal when one refuses."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.test/sym] [:maybe :seon.task/sym]]
                  [:or :seon.context/head :seon.error/value]]}
  [database names task]
  (let [names (into [] (filter #(seq (db/datoms database :avet :seon.test/sym %))) (sort names))
        by-sym (if (seq names) (runner/current-results database names task) {})]
    (or (when (:seon.error/at by-sym) by-sym)
        (into {} (for [s names
                       :let [result (get by-sym s)
                             status (proof-status result)
                             failures (when (= :red status) (proof-failures result))]]
                   [s (cond-> {:seon.context/proof-status status}
                        failures (assoc :seon.context/proof-failures failures))])))))

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
      (let [head (head-entries database (set names) sym)]
        (or (when (:seon.error/at head) head)
            (let [entries (mapv #(merge {:seon.test/sym % :seon.context/proof-status :pending} (head %)) names)]
              {:seon.context/counts (frequencies (map :seon.context/proof-status entries))
               :seon.context/proof-list entries}))))))

(defn step-status
  "The status of a step from its `observation` (`step-observation`): for a chain's task the done rule
  (`seon.cluster.source/done-refusals`) over the agent's branch and the judged input, for any other task the
  preview of its merge (`seon.cluster.source/results-verdict`, until TASK-CHAIN S3e deletes that model); `:done`
  exactly when it refuses nothing, else `:working`, with its refusals; `:working` with none while the branch has
  recorded no run to judge. Reads only."
  {:malli/schema [:=> [:cat :seon.context/step-observation] :seon.context/step-status]}
  [{candidate :seon.context/candidate main :seon.context/main chained? :seon.context/chained judged :seon.source/judged}]
  (let [refusals (when judged
                   (if chained? (source/done-refusals candidate judged) (source/results-verdict candidate main judged)))]
    {:seon.task/status (if (and judged (empty? refusals)) :done :working) :seon.source/refusals (vec refusals)}))

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
  names and paths its effective plan and instructions cite and its `proofs`. The tasks it needs are its chain's
  steps before it (`chain`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.context/task [:set :seon.test/sym]]
                  [:vector [:or :seon.ref/resolved :seon.ref/problem :seon.error/value]]]}
  [database {:seon.task/keys [plan instructions]} proofs]
  (ref/references database {:seon.ref/text (cond-> plan instructions (str "\n\n" instructions))
                            :seon.ref/names proofs}))

(def ^:private merged-families
  "The key each declaration family a merge moved is shown under, by its identity attribute."
  {:seon.fn/sym :seon.context/functions :seon.schema/key :seon.context/schemas
   :seon.test/sym :seon.context/tests :seon.task/sym :seon.context/tasks})

(defn- by-family
  "The declaration `identities` shown on `database` by family, in name order, each name one level
  (`seon.ref/brief`): its summary and the form that reads it, never its content; a name `database` no longer
  declares shows as its problem. Families are absent when empty."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set] [:or :seon.context/merged :seon.error/value]]}
  [database identities]
  (let [shown (reduce (fn [shown [attribute value]]
                        (if-let [family (merged-families attribute)]
                          (update shown family (fnil conj []) (ref/brief database value))
                          shown))
                      {} (sort-by (comp str second) identities))]
    (or (some #(when (:seon.error/at %) %) (mapcat val shown)) shown)))

(defn- merged
  "What main's merge `commit` moved (`seon.cluster.source/merge-accepted`), shown on `database` by family
  (`by-family`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/commit-id] [:or :seon.context/merged :seon.error/value]]}
  [database commit]
  (let [accepted (source/merge-accepted (db/branch-connection database (get-in database [:config :branch])) commit)]
    (if (:seon.error/at accepted)
      accepted
      (by-family database (:seon.program/identities accepted)))))

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
        (cond-> {:seon.task/sym sym}
          (:seon.task/step task) (assoc :seon.task/step (:seon.task/step task))
          latest (assoc :seon.task/outcome :merged)
          (seq moved) (assoc :seon.context/merged moved)))))

(defn- chain
  "The chain the held task `id` is a task of, on `database`: the tasks before it and after it along
  `:seon.task/next`, in chain order, those before as `step-before` shows them, those after each with its kind and
  instructions; nil when nothing links to it or from it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle] [:or :nil :seon.context/chain :seon.error/value]]}
  [database id]
  (let [previous (fn [handle] (db/q '[:find ?p . :in $ ?id :where [?t :seon.task/id ?id] [?e :seon.task/next ?t] [?e :seon.task/id ?p]]
                                    database handle))
        ;; walk back to the first task, then forward along next (`seon.task/chain-handles`)
        first-handle (loop [handle id]
                       (let [p (previous handle)]
                         (cond (map? p) p
                               (string? p) (recur p)
                               :else handle)))
        handles (when (string? first-handle) (task/chain-handles database first-handle))
        [before [_ & after]] (split-with #(not= id %) handles)
        sym-of #(:seon.task/sym (db/pull database [:seon.task/sym] [:seon.task/id %]))
        before (mapv #(step-before database (sym-of %)) before)
        after (mapv #(db/pull database [:seon.task/sym :seon.task/step :seon.task/instructions] [:seon.task/id %]) after)]
    (or (when (map? first-handle) first-handle)
        (some #(when (:seon.error/at %) %) (concat before after))
        (when (or (seq before) (seq after))
          (cond-> {}
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

(defn- change
  "The declaration identities the agent `agent-id`'s branch `database` changed since its task's merge base: the
  one its attempt row holds (a relaunched attempt keeps its predecessor's, so the change is the task's across
  attempts), for a row holding none the commit the branch forked at (`seon.turn/fork-commit`); empty on main."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :seon.program/identity-set :seon.error/value]]}
  [database agent-id]
  (let [held (db/q '[:find ?m . :in $ ?id :where [?g :seon.agent/id ?id] [?a :seon.task/agent ?g] [?a :seon.task/merge-base ?m]]
                   database agent-id)
        commit (if (uuid? held) held (turn/fork-commit database))
        base (when commit (db/commit-database database commit))]
    (cond (:seon.error/at held) held
          (nil? base) #{}
          :else (try (program/changed-identities (db/basis-t base) database source/merge-bound)
                     (finally (source/release! [base]))))))

(defn current-results
  "The recorded result of each of `tests` that `database` holds at its current reach digest there judging `task`
  (nil: none), the identity the runner records it under (`seon.test.runner/current-results`): its newest completed
  execution at that identity; a test absent there, unrun at that reach or with no completed outcome
  has none, so a task-reading test's result recorded with no task, or for another task, answers for none of
  `task`'s."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.test/sym] [:maybe :seon.task/sym]]
                  [:or :seon.source/results :seon.error/value]]}
  [database tests task]
  (let [names (into [] (filter #(seq (db/datoms database :avet :seon.test/sym %))) (sort tests))
        current (if (seq names) (runner/current-results database names task) {})]
    (if (:seon.error/at current)
      current
      (into [] (comp (map current) (filter #(and % (not (:seon.error/at %)) (:seon.test/sym %)))) names))))

(defn step-observation
  "The observation the held task `sym`'s step is judged by on `database`, the agent's branch: its obligations
  over its assigned basis (`seon.task/assigned-obligations`), whether a chain links it (`:seon.task/next`), its
  product (the proofs of the task after it and, for a Prove step, the tests its successors are tagged with), the
  branch as the candidate, main's head (`seon.task/main-head`; the branch itself on main) and the judgment's input:
  its proofs, the tests reaching the functions it changed since its merge base (`change`, `seon.fn/gate-sets`),
  their results at their current reach on the branch (`current-results`) and the newest run they came from. A
  chain's task adds the tests the branch defines or changes, passes its product as `:seon.source/next-proofs` and
  the tests main holds green as `:seon.source/green-on-target` (`seon.cluster.source/done-refusals`); any other
  joins its product to its proofs and passes the reds main holds as `:seon.source/red-on-target`
  (`seon.cluster.source/results-verdict`). A branch that recorded no run has nothing to judge, so it carries no
  judgment input. The head shows each judged test's status."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.task/sym]
                  [:or :seon.context/step-observation :seon.error/value]]}
  [database agent-id sym]
  (let [owed (task/assigned-obligations database sym)
        main (or (task/main-head database) database)
        following (when-not (:seon.error/at owed)
                    (db/pull database [{:seon.task/next [:seon.task/proofs]}] [:seon.task/sym sym]))
        ;; a task linked by next is a chain's, judged by the done rule; any other by its merge's preview until
        ;; TASK-CHAIN S3e deletes the subject/needs model
        previous (db/q '[:find ?p . :in $ ?s :where [?t :seon.task/sym ?s] [?p :seon.task/next ?t]] database sym)
        chained? (or (some? (:seon.task/next following)) (some? previous))
        product (cond-> (set (get-in following [:seon.task/next :seon.task/proofs]))
                  (= :prove (:seon.task/step owed)) (into (:seon.task/product owed)))
        delta (when-not (:seon.error/at owed) (change database agent-id))
        of (fn [attribute] (into #{} (keep (fn [[a s]] (when (= attribute a) s))) (when (set? delta) delta)))
        fns (of :seon.fn/sym)
        reached (if (seq fns) (fn/gate-sets {:seon.db/db database :seon.fn/seeds fns}) [])
        proofs (set (:seon.task/proofs owed))
        members (when (sequential? reached)
                  (cond-> (-> proofs (into product) (into reached)) chained? (into (of :seon.test/sym))))
        results (when members (current-results database members sym))
        held (when (vector? results) (if (identical? main database) results (current-results main members nil)))
        on-target (when (vector? held)
                    (if chained?
                      (into #{} (comp (remove runner/red-result?) (filter #(pos? (:seon.test/pass-count % 0))) (map :seon.test/sym)) held)
                      (let [reds (into #{} (comp (filter runner/red-result?) (map :seon.test/sym)) results)]
                        (into #{} (comp (filter runner/red-result?) (map :seon.test/sym) (filter reds)) held))))
        head (when members (head-entries database members sym))
        ;; the run the refusals name: the newest its results came from, else the newest the branch holds
        newest (fn [runs] (some->> (seq runs) (apply max-key second) first))
        run-id (when (vector? results)
                 (or (newest (keep #(when-let [[_ id] (:seon.test/run %)] [id (:seon.test/run-basis-t %)]) results))
                     (newest (db/q '[:find ?id ?t :where [?r :seon.test.run/id ?id] [?r :seon.test.run/basis-t ?t]] database))))
        judged (when run-id
                 (if chained?
                   (cond-> {:seon.source/members members :seon.source/results results :seon.task/proofs proofs}
                     (seq product) (assoc :seon.source/next-proofs product)
                     (seq on-target) (assoc :seon.source/green-on-target on-target))
                   (cond-> {:seon.source/members members :seon.source/results results :seon.task/proofs (into proofs product)}
                     (seq product) (assoc :seon.task/product product)
                     (seq on-target) (assoc :seon.source/red-on-target on-target))))]
    (or (some #(when (:seon.error/at %) %) [owed following previous delta reached results held head])
        (cond-> (assoc owed :seon.task/product product :seon.context/chained chained?
                       :seon.context/head head :seon.context/candidate database :seon.context/main main)
          (set? delta) (assoc :seon.program/identities delta)
          judged (assoc :seon.source/judged (assoc judged :seon.program/identities delta :seon.test.run/id run-id
                                                   :seon.test.run/branch (get-in database [:config :branch])))))))

(defn step-verdict
  "The held task's step on `database`, the agent's branch, judged by `step-status` over `step-observation`: the
  observation with its status; nil when the agent holds no task."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :seon.context/step-verdict :seon.error/value]]}
  [database agent-id]
  (let [held (held-task database agent-id)
        observation (when (and held (not (:seon.error/at held)))
                      (step-observation database agent-id (:seon.task/sym held)))]
    (cond (:seon.error/at held) held
          (:seon.error/at observation) observation
          observation (merge observation (step-status observation)))))

(defn step-section
  "The step section of `verdict` (`step-verdict`), as the agent reads it: the step's kind and status, a working
  step's done refusals (`seon.cluster.source/done-refusals`, as data), then each other test they name red or
  unknown, and the product it owes the task after it (that task's proofs), each with its status and, when red,
  its failing assertions. A proof is listed under the proofs section only, so each test shows once. Lists are
  absent when empty."
  {:malli/schema [:=> [:cat :seon.context/step-verdict] :seon.context/step]}
  [{step :seon.task/step own :seon.task/proofs product :seon.task/product head :seon.context/head status :seon.task/status
    refusals :seon.source/refusals}]
  (let [entries (fn [tests] (mapv #(merge {:seon.test/sym % :seon.context/proof-status :pending} (head %)) (sort tests)))
        shown-proofs (set own)
        owed (remove shown-proofs product)
        red (into #{} (comp (mapcat :seon.source/red-tests) (map :seon.test/sym) (remove (into shown-proofs product))) refusals)]
    (cond-> {:seon.task/status status}
      step (assoc :seon.task/step step)
      (seq refusals) (assoc :seon.source/refusals refusals)
      (seq red) (assoc :seon.context/red-tests (entries red))
      (seq owed) (assoc :seon.context/product-tests (entries owed)))))

(defn task-done?
  "True when the step of the task `agent-id` holds is done on `database`, the agent's branch (`step-verdict`): its
  done rule refuses nothing (`seon.cluster.source/done-refusals`), so the turn loop opens no model turn for it."
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

(defn- model-usage
  "The model calls agent `agent-id` made on `database` (its turns that called a model) and their tokens."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or [:map [:seon.task/calls :seon.task/calls] [:seon.task/prompt-tokens :seon.task/prompt-tokens]
                        [:seon.task/completion-tokens :seon.task/completion-tokens]]
                   :seon.error/value]]}
  [database agent-id]
  (let [attempts (db/q '[:find ?turn (pull ?attempt [:seon.ai.usage/prompt-tokens :seon.ai.usage/completion-tokens])
                         :in $ ?agent-id
                         :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent] [?turn :seon.turn/attempts ?attempt]]
                       database agent-id)
        total #(transduce (keep (comp % second)) + 0 attempts)]
    (if (:seon.error/at attempts)
      attempts
      {:seon.task/calls (count (into #{} (map first) attempts))
       :seon.task/prompt-tokens (total :seon.ai.usage/prompt-tokens)
       :seon.task/completion-tokens (total :seon.ai.usage/completion-tokens)})))

(defn- last-step
  "The last step agent `agent-id` took on `database`: the note of the `my.turn/wait` its last form returned, else
  the prose it wrote with that form, else that form's source; nil when it evaluated nothing."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:maybe :seon.context/last-step] :seon.error/value]]}
  [database agent-id]
  (let [evaluations (seon.eval/of-agent database agent-id [:seon.cluster.eval/comment :seon.cluster.eval/source :seon.eval/shown])]
    (if (:seon.error/at evaluations)
      evaluations
      (when-let [{shown :seon.eval/shown prose :seon.cluster.eval/comment source :seon.cluster.eval/source} (peek evaluations)]
        (let [value (some-> shown repl/shown-value)]
          (or (when (and (map? value) (= :wait (:my.turn/disposition value))) (:my.turn/note value))
              (not-empty prose)
              source))))))

(defn- earlier-attempts
  "The attempts of task `handle` on `database` other than agent `agent-id`'s, by number, one entry each: its
  number, end, outcome and reason when it holds them, the model calls its agent made (`model-usage`) and its last
  step (`last-step`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/handle :seon.agent/id] [:or :seon.context/attempts :seon.error/value]]}
  [database handle agent-id]
  (let [rows (db/q '[:find [(pull ?a [:seon.task.attempt/number :seon.task.attempt/end :seon.task/outcome :seon.task/reason
                                      {:seon.task/agent [:seon.agent/id]}]) ...]
                     :in $ ?h :where [?t :seon.task/id ?h] [?a :seon.task/attempt-of ?t]]
                   database handle)]
    (if (:seon.error/at rows)
      rows
      (let [shown (into [] (comp (remove #(= agent-id (get-in % [:seon.task/agent :seon.agent/id])))
                                 (map (fn [row]
                                        (let [earlier (get-in row [:seon.task/agent :seon.agent/id])
                                              usage (model-usage database earlier)
                                              step (last-step database earlier)]
                                          (or (some #(when (:seon.error/at %) %) [usage step])
                                              (cond-> (assoc (dissoc row :seon.task/agent :db/id) :seon.task/calls (:seon.task/calls usage))
                                                step (assoc :seon.context/last-step step)))))))
                        (sort-by :seon.task.attempt/number rows))]
        (or (some #(when (:seon.error/at %) %) shown) shown)))))

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
        usage (model-usage branch-db agent-id)]
    (or (some #(when (:seon.error/at %) %) [proven usage])
        (cond-> usage
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
  "Where the step of `held` begins: the form that enters the namespace owning it and the form that enters the
  namespace of each test its instructions cite (`seon.ref/names`); each must-read carries the form that reads its
  source."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.task/sym :seon.task/sym] [:seon.task/instructions {:optional true} :seon.task/instructions]]]
                  :seon.context/start]}
  [database {:seon.task/keys [sym instructions]}]
  (let [owner (symbol (namespace sym))
        tests (when instructions
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
  the task's earlier attempts, one entry each (`earlier-attempts`); every declaration the branch changed since its
  merge base, by family, each name with the form that reads it (`by-family`), so a rerun sees its earlier work and
  its scratch; and every other reference it makes, resolved. Empty when it holds none.

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
            earlier (earlier-attempts database (:seon.task/id held) agent-id)
            changes (when (and verdict (not (:seon.error/at verdict)) (seq (:seon.program/identities verdict)))
                      (by-family database (:seon.program/identities verdict)))
            proof-names (into #{} (map :seon.test/sym) (:seon.context/proof-list proven))
            reads (when-not (:seon.error/at proven) (task-references database held proof-names))
            resolution (into {} (keep #(when (proof-names (:seon.ref/name %)) [(:seon.ref/name %) %])) reads)
            others (into [] (remove #(contains? resolution (:seon.ref/name %))) reads)]
        (or (some #(when (:seon.error/at %) %) (concat [proven verdict steps earlier changes] reads))
            (cond-> {:seon.context/task (select-keys held [:seon.task/id :seon.task/sym :seon.task/plan :seon.task/instructions])}
              steps (assoc :seon.context/chain steps)
              proven (assoc :seon.context/proofs
                            (update proven :seon.context/proof-list
                                    (partial mapv #(merge (dissoc (resolution (:seon.test/sym %)) :seon.ref/name :seon.render/ai :seon.render/html) %))))
              verdict (assoc :seon.context/step
                             (assoc (step-section verdict)
                                    :seon.context/start (step-start database held)))
              (seq sent-back) (assoc :seon.context/send-backs sent-back)
              (seq earlier) (assoc :seon.context/attempts earlier)
              (seq changes) (assoc :seon.context/changes changes)
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
                                              held (assoc :seon.task/status (cond (= :done status) :done
                                                                                  (zero? left) :capped
                                                                                  :else :working))
                                              namespace (assoc :seon.context/namespace namespace))})
          cost (assoc :seon.context/cost cost)
          (seq unread) (assoc :seon.context/unread {:seon.context/items unread
                                                    :seon.render/operations ['(my.message/inbox)]})))))

(defn- must-read-ai
  "One must-read as the agent reads it: a value at the level its citation chose as it is, or an entity through the
  render it declares (`seon.render/render-call`), beside its name, level and the references its content makes; a
  problem as it is."
  {:malli/schema [:=> [:cat :seon.render/unit [:or :seon.ref/resolved :seon.ref/problem]] :seon.context/must-read-view]}
  [unit entry]
  (if-not (:seon.render/ai entry)
    entry
    (let [shown (seon.render/render-call (-> unit
                                             (dissoc :seon.render/distance)
                                             (assoc :seon.render/value entry :seon.render/output :seon.render/ai)))
          kept (select-keys entry [:seon.ref/name :seon.ref/level :seon.ref/references])]
      (if (map? shown) (merge shown kept) (assoc kept :seon.context/rendered shown)))))

(defn current-state-ai
  "The block's sections as the agent reads them: the task by symbol and declaration, without its handle, and each
  must-read through its own declared render (`must-read-ai`).
  The render argument carries the evaluation's world beside the block; only the sections are returned."
  {:malli/schema [:=> [:cat :seon.context/current-state] :seon.context/current-state-view]}
  [block]
  (cond-> (select-keys block [:seon.context/task :seon.context/chain :seon.context/proofs :seon.context/step :seon.context/send-backs :seon.context/attempts :seon.context/changes :seon.context/must-reads :seon.context/cost :seon.context/unread :seon.context/turns])
    (:seon.context/task block) (update :seon.context/task dissoc :seon.task/id)
    (:seon.context/must-reads block) (update :seon.context/must-reads (partial mapv #(must-read-ai block %)))))

(def ^:private attempt-sections
  "The block's sections that hold for the whole attempt: the task, its chain, its earlier attempts, its send-backs and
  its must-reads. The model pass lays them before the history, so they join the provider's cached prefix."
  [:seon.context/task :seon.context/chain :seon.context/attempts :seon.context/send-backs :seon.context/must-reads])

(defn block-views
  "The block's AI view (`current-state-ai`) in two parts: the attempt's sections (`attempt-sections`), the same at
  every turn of the attempt, and the sections each turn changes (proofs, step, changes, cost, unread, turns)."
  {:malli/schema [:=> [:cat :seon.context/current-state] [:tuple :seon.context/attempt-view :seon.context/current-state-view]]}
  [unit]
  (let [view (current-state-ai unit)]
    [(select-keys view attempt-sections) (apply dissoc view attempt-sections)]))

;;; ---------------------------------------------------------------------------
;;; What a model pass sends
;;; ---------------------------------------------------------------------------

(def ^:private exchange-selector
  "An evaluation as `seon.repl/entity-emission` reads it, with its call row's attempts."
  '[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
    {:seon.cluster.eval/run [:seon.turn/id {:seon.turn/attempts [:db/id]}]}])

(defn- exchange-form
  "One evaluation as the REPL showed it: the form as sent and what the REPL answered; a unit older than the newest
  exchange answers without the turn's meta (`seon.repl/reply-text`)."
  {:malli/schema [:=> [:cat :seon.repl/emission :boolean] [:map :seon.ai/form [:seon.ai/reply {:optional true}]]]}
  [emission newest?]
  (let [reply (repl/reply-text emission newest?)]
    (cond-> {:seon.ai/form (repl/input-text emission)}
      reply (assoc :seon.ai/reply reply))))

(defn- exchanges
  "The agent's own model exchanges in agent order: one per call row carrying a model attempt; only the newest
  exchange's replies keep the turn's meta (`exchange-form`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :seon.context/exchanges :seon.error/value]]}
  [database agent-id]
  (let [evaluations (seon.eval/of-agent database agent-id exchange-selector)]
    (if (:seon.error/at evaluations)
      evaluations
      (let [runs (->> evaluations
                      (filter #(seq (get-in % [:seon.cluster.eval/run :seon.turn/attempts])))
                      (partition-by #(get-in % [:seon.cluster.eval/run :seon.turn/id]))
                      vec)
            newest (dec (count runs))]
        (into [] (map-indexed (fn [index run]
                                (mapv #(exchange-form (repl/entity-emission (assoc % :seon.db/db database)) (= index newest))
                                      run)))
              runs)))))

(defn model-context
  "What one model pass sends, as shown text.

  The system message is `(help)` then the block's attempt sections (`block-views`), the same at every turn of the
  attempt, so the provider caches them with the history after them; then the agent's own model exchanges; then the
  block's turn sections, last. The one assembler the model call reads."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.sci.eval/shown-request :seon.agent/id]
                  [:or :seon.context/model-context :seon.error/value]]}
  [database request agent-id]
  (let [request (assoc request :seon.db/db database :seon.agent/id agent-id)
        history (exchanges database agent-id)
        block (current-state database agent-id)
        profile (seon.render/request-profile request)
        shown #(sci.eval/shown-result %1 %2 nil)
        system (shown (bootstrap/help-value database) request)
        [head tail] (when-not (:seon.error/at block) (block-views (merge block request)))
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
        {:seon.ai/system (cond-> (:seon.eval/shown system)
                           (seq head) (str "\n\n" (:seon.eval/shown (shown head request))))
         :seon.context/exchanges history
         :seon.ai/prompt (:seon.eval/shown (shown tail request))})))
