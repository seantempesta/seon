(ns seon.problems
  "The one read of every problem Seon recorded (session D2, ../notes/archive/option3-D2-problems.md):
  main's db value in, one report out, by class. Root and the owner mine tasks from it by
  hand: each row carries what a task-file declaration needs (its producer and owning
  namespace, its evidence as data, the proofs that reproduce it) and the tasks that already
  answer it.

  Its cost follows the problems, never the program: each class is read through an index
  that holds only problem rows (aevt of an attribute only a problem carries, avet of an
  indexed status), then one pull per row. Nothing walks the tasks, attempts or vars.

  Classes:
    :platform :eval :agent :effect  recorded errors (seon.error), counted by signature
    :red :cost                      an attempt's red assertions, its cost proofs' verdicts
    :merge                          conflicts, unmerged keys, behind, a refused merge
    :dead                           an attempt whose agent died or was lost
    :error                          an attempt the turn loop ended on an error (the model)
    :waste                          forms charged to the producer of what was shown
    :stuck                          a task whose failures spent its budget: its root cause and
                                    the released tasks it blocks (seon.reconcile/stuck-tx)
    :task-conflict                  task content a branch and main both changed, differently,
                                    recorded at its attempt's end (seon.task/carry-tx)
    :failed                         a task marked failed (my.task/fail!, root or a check): its
                                    reason and counterexample; open while it is held"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [seon.merge :as pm] [seon.reconcile :as reconcile]))

(def counted-classes [:platform :eval :agent :effect])
(def ^:private death-statuses "Attempt statuses of a death (:seon.attempt/status)." (:seon.status/failed (contract/properties :seon.attempt/status)))
(def attempt-classes [:red :cost :merge :dead :error :waste :task-conflict])

(defn- es
  "The entities carrying attribute a (its aevt index: only the entities that have it)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] [:vector :int]]}
  [db a] (mapv :e (d/datoms db :aevt a)))

(defn answers
  "The tasks on db whose evidence names a problem: {signature [task-id]} and
  {attempt-id [task-id]} under :signature and :from. Reads only the tasks that carry
  evidence (aevt :seon.task/evidence)."
  {:malli/schema [:=> [:cat :seon.host/db] [:map [:signature :map] [:from :map]]]}
  [db]
  (reduce (fn [acc {:keys [e v]}]
            (let [ev v
                  id (:seon.task/id (d/pull db [:seon.task/id] e))]
              (cond-> acc
                (:signature ev) (update-in [:signature (:signature ev)] (fnil conj []) id)
                (:from ev) (update-in [:from (:from ev)] (fnil conj []) id))))
          {:signature {} :from {}} (d/datoms db :aevt :seon.task/evidence)))

;; ---- recorded errors ------------------------------------------------------------------

(defn- error-row
  "One recorded error (entity e) as a row."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :seon.problems/row]}
  [db ans e]
  (let [r (d/pull db '[*] e)
        s (:seon.error/signature r)
        op (:seon.error/operation r)
        ex (:seon.error/example r)]
    (cond-> {:class (:seon.error/class r) :signature s :type (:seon.error/type r) :operation op :message (:seon.error/message r)
             :count (:seon.error/count r) :agents (:seon.error/agents r 0)
             :first-ms (:seon.error/first-ms r) :last-ms (:seon.error/last-ms r)
             :last-agent (:seon.error/last-agent r) :last-task (:seon.error/last-task r) :example ex
             :producer op :owner-ns (some-> op namespace symbol)
             :evidence (cond-> {:kind :error :signature s :count (:seon.error/count r) :agents (:seon.error/agents r 0) :example ex}
                         (:seon.error/form r) (assoc :form (:seon.error/form r)))
             :proofs [] :answered-by (get-in ans [:signature s] []) :at-ms (:seon.error/last-ms r)}
      (:seon.error/form r) (assoc :form (:seon.error/form r)))))

;; ---- attempts ------------------------------------------------------------------------

(def ^:private attempt-pull
  "What a problem row reads of an attempt: one pull (its conflicts are read only when it has
  them)."
  [:seon.attempt/id :seon.attempt/status :seon.attempt/outcome :seon.attempt/reason :seon.attempt/end-ms :seon.attempt/agent
   :seon.attempt/unmerged :seon.attempt/failures :seon.attempt/costs :seon.attempt/error :seon.attempt/waste :seon.attempt/task-conflicts
   {:seon.attempt/task [:db/id :seon.task/id :seon.task/ns :seon.task/proofs]}
   {:seon.attempt/conflicts [:seon.merge/key :seon.merge/kind :seon.merge/base :seon.merge/ours :seon.merge/theirs]}])

(defn- task-done?
  "Has task te an attempt finished (`seon.reconcile/finished`)? One avet seek, then its attempts."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db te]
  (boolean (some #(reconcile/finished (:seon.attempt/status (d/pull db [:seon.attempt/status] (:e %))))
                 (d/datoms db :avet :seon.attempt/task te))))

;; ---- stuck and failed tasks ------------------------------------------------------------

(defn- failed-row
  "Task entity e, which carries a failure, as a row: open while the task is held for root."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :seon.problems/row]}
  [db ans e]
  (let [t (d/pull db [:seon.task/id :seon.task/ns :seon.task/failure :seon.task/execution :seon.task/proofs] e)
        {:keys [attempt] :as f} (:seon.task/failure t)
        end-ms (some-> attempt (as-> a (d/pull db [:seon.attempt/end-ms] [:seon.attempt/id a])) :seon.attempt/end-ms)]
    (merge (select-keys f [:reason :counterexample])
           {:task (:seon.task/id t) :attempt attempt :open? (= :held (:seon.task/execution t))
            :owner-ns (:seon.task/ns t) :producer nil :proofs (vec (sort-by str (:seon.task/proofs t)))
            :evidence (assoc f :kind :failed) :answered-by (get-in ans [:from attempt] []) :at-ms end-ms})))

(defn- stuck-row
  "Task entity e, which carries a stuck fact, as a row: open while it is not done."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] :seon.problems/row]}
  [db ans e]
  (let [t (d/pull db [:seon.task/id :seon.task/ns :seon.task/stuck :seon.task/proofs] e)
        {:keys [attempt cause blocks] :as s} (:seon.task/stuck t)
        end-ms (some-> attempt (as-> a (d/pull db [:seon.attempt/end-ms] [:seon.attempt/id a])) :seon.attempt/end-ms)]
    {:task (:seon.task/id t) :attempt attempt :cause cause :blocks blocks :open? (not (task-done? db e))
     :owner-ns (:seon.task/ns t) :producer nil :proofs (vec (sort-by str (:seon.task/proofs t)))
     :evidence (assoc s :kind :stuck) :answered-by (get-in ans [:from attempt] []) :at-ms end-ms}))

(defn- attempt-rows
  "The rows of attempt entity e, by class."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] [:map-of :keyword [:vector :seon.problems/row]]]}
  [db ans e]
  (let [p (d/pull db attempt-pull e)
        aid (:seon.attempt/id p)
        te (get-in p [:seon.attempt/task :db/id])
        status (:seon.attempt/status p)
        conflicts (mapv pm/conflict-value (:seon.attempt/conflicts p))
        unmerged (vec (sort (:seon.attempt/unmerged p)))
        base {:attempt aid :task (get-in p [:seon.attempt/task :seon.task/id]) :agent (:seon.attempt/agent p) :status status
              :outcome (:seon.attempt/outcome p) :reason (:seon.attempt/reason p) :end-ms (:seon.attempt/end-ms p)
              :open? (not (task-done? db te)) :owner-ns (get-in p [:seon.attempt/task :seon.task/ns]) :producer nil :proofs []
              :answered-by (get-in ans [:from aid] []) :at-ms (:seon.attempt/end-ms p)}
        ;; a red proof is this attempt's problem when the task itself states it: an inherited
        ;; proof was green on main when the task started (its needs were done), so red now it
        ;; is a regression, the attempt's refusal reason; another task's proof not green yet is
        ;; an allowed red. Both stay in the attempt's facts as evidence, never problem rows.
        own (set (get-in p [:seon.attempt/task :seon.task/proofs]))
        failures (select-keys (:seon.attempt/failures p) own)
        costs (select-keys (:seon.attempt/costs p) own)
        err (:seon.attempt/error p)
        waste (:seon.attempt/waste p)
        merge? (or (seq conflicts) (seq unmerged) (= :behind status) (= :merge-failed (:outcome base))
                   (some-> err :seon.error/type namespace #{"seon.merge" "seon.writer"}))]
    (cond-> {}
      (seq failures)
      (assoc :red (vec (for [[test asserts] (sort-by (comp str key) failures)]
                         (assoc base :test test :assertions asserts :proofs [test]
                                :evidence {:from aid :red [test] :failures {test asserts}}))))
      (seq (remove (fn [[_ {v :verdict}]] (and (:ok? v) (empty? (:under v)))) costs))
      (assoc :cost (vec (for [[test {v :verdict :as c}] (sort-by (comp str key) costs) :when (not (and (:ok? v) (empty? (:under v))))]
                          (assoc base :test test :verdict v :rows (:rows c) :proofs [test] :evidence {:from aid :red [test] :cost {test c}}))))
      merge?
      (assoc :merge [(assoc base :conflicts (count conflicts) :unmerged unmerged :error err
                            :producer (:seon.error/operation err)
                            :evidence (cond-> {:from aid :conflicts conflicts :unmerged unmerged
                                               :red []}
                                        err (assoc :error err)))])
      (seq (:seon.attempt/task-conflicts p))
      (assoc :task-conflict (vec (for [c (:seon.attempt/task-conflicts p)]
                                   (assoc base :task (:task c) :attr (:attr c) :evidence (assoc c :from aid)))))
      (death-statuses status)
      (assoc :dead [(assoc base :error err :producer (:seon.error/operation err) :evidence (cond-> {:from aid} err (assoc :error err)))])
      (and err (not merge?) (not (death-statuses status)))
      (assoc :error [(assoc base :error err :producer (:seon.error/operation err) :evidence {:from aid :error err})])
      (seq waste)
      (assoc :waste [(assoc base :waste waste :evidence {:from aid :waste waste})]))))

(defn report
  "Every problem recorded on main db, by class: {:t :classes {class {:n :rows}}}. Counted
  classes' :n is their occurrences and their rows run by count; attempt classes' :n is
  their rows, newest first. With an observer's fold (seon.error/fold), a recorded error's
  count is the true one: a repeat of a written signature is counted in the fold and not
  yet on main."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :seon.problems/report]
                  [:=> [:cat :seon.host/db :seon.error/fold] :seon.problems/report]]}
  ([db] (report db {}))
  ([db fold]
  (let [ans (answers db)
        live (into {} (map (fn [{:keys [signature base] n :count}] [signature (+ (or base 0) n)])) (vals fold))
        errors (group-by :class (map #(let [r (error-row db ans %)] (update r :count max (get live (:signature r) 0)))
                                     (es db :seon.error/signature)))
        attempts (into (sorted-set)
                       (concat (mapcat #(es db %) [:seon.attempt/failures :seon.attempt/costs :seon.attempt/error :seon.attempt/waste
                                                   :seon.attempt/conflicts :seon.attempt/unmerged :seon.attempt/task-conflicts])
                               (mapcat (fn [s] (map :e (d/datoms db :avet :seon.attempt/status s))) (conj death-statuses :behind))))
        by-class (apply merge-with into (map #(attempt-rows db ans %) attempts))
        stuck (mapv #(stuck-row db ans %) (es db :seon.task/stuck))
        failed (mapv #(failed-row db ans %) (es db :seon.task/failure))]
    ^{:seon.render/schema :seon.problems/report}
    {:t (:max-tx db)
     :classes (merge (into {} (for [c counted-classes :let [rows (vec (sort-by (comp - :count) (get errors c)))]]
                                [c {:n (reduce + 0 (map :count rows)) :rows rows}]))
                     (into {} (for [c attempt-classes :let [rows (vec (sort-by #(- (or (:end-ms %) 0)) (get by-class c)))]]
                                [c {:n (count rows) :rows rows}]))
                     {:stuck {:n (count stuck) :rows (vec (sort-by #(- (or (:at-ms %) 0)) stuck))}
                      :failed {:n (count failed) :rows (vec (sort-by #(- (or (:at-ms %) 0)) failed))}})})))
