(ns seon.issue.opening
  "An issue worker's opening, as data: one per issue it works.

  `openings` is the AI face of the agent's `:seon.issue/_agent` concern: for
  each issue, what decides done (its exact tests with their recorded
  outcomes, or its detector), what the worker's dialled candidate adds, and
  the reads offered (`(my.issue/status ...)` last). The value printer prints
  it; nothing evaluates it. The dial, `:seon.config.render/issue-opening`, is
  a per-agent config enum read from the worker named by `:seon.issue/agent`;
  no worker or no dial is `:bare`. A candidate only chooses data and offered
  reads: it links rather than copies, and every call it names is exact and
  executable.

  The trial that compares them is
  docs/prds/steward-platform/plan/issue-context-trials-2026-09-16.md."
  (:require [clojure.string :as str]
            [seon.error.refusal]
            [seon.ai :as ai]
            [seon.db :as db]
            [seon.test :as test]))

(def dial
  "The per-agent config attribute that selects one candidate."
  :seon.config.render/issue-opening)

(def default-candidate
  "The rendering an issue with no dial gets: the floor."
  :bare)

;;; ---------------------------------------------------------------------------
;;; Reading the issue's own links
;;; ---------------------------------------------------------------------------

(defn- links
  "The identities this issue links, read from the database it is rendered in."
  [database issue-id]
  (let [entity (db/pull database
                     '[:seon.issue/id :seon.issue/title :seon.issue/problem
                       {:seon.issue/agent [:seon.agent/id]}
                       {:seon.issue/detector [:seon.fn/sym]}
                       {:seon.issue/functions
                        [:seon.fn/sym {:seon.fn/ns [:seon.ns/name]}
                         {:seon.fn/file [:seon.fn.file/relative-path]}]}]
                     [:seon.issue/id issue-id])]
    (when (:seon.issue/title entity)
      (cond-> {:seon.issue/id (:seon.issue/id entity)
       :seon.issue/title (:seon.issue/title entity)
       :seon.issue/problem (:seon.issue/problem entity)
       :seon.issue/detector (get-in entity [:seon.issue/detector :seon.fn/sym])
       :seon.test/syms (vec (sort (db/q '[:find [?symbol ...] :in $ ?issue-id
                                         :where [?issue :seon.issue/id ?issue-id]
                                                [?issue :seon.issue/tests ?test]
                                                [?test :seon.test/sym ?symbol]]
                                       database issue-id)))
       :seon.fn/syms (vec (sort (map :seon.fn/sym (:seon.issue/functions entity))))
       :seon.ns/names (vec (sort (distinct (keep #(get-in % [:seon.fn/ns :seon.ns/name])
                                                 (:seon.issue/functions entity)))))
       :seon.fn.file/relative-paths (vec (sort (distinct (keep #(get-in % [:seon.fn/file :seon.fn.file/relative-path])
                                                      (:seon.issue/functions entity)))))}
        (get-in entity [:seon.issue/agent :seon.agent/id])
        (assoc :seon.agent/id (get-in entity [:seon.issue/agent :seon.agent/id]))))))

(defn candidate
  "The candidate this issue's worker is dialled to, or the floor."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.issue/id]
                  :seon.config.render/issue-opening]}
  [database issue-id]
  (let [agent-id (:seon.agent/id (links database issue-id))
        overlay (when agent-id (ai/agent-overlay database agent-id))]
    (or (when (map? overlay) (get overlay dial)) default-candidate)))

;;; ---------------------------------------------------------------------------
;;; The shared vocabulary every candidate draws its forms from
;;; ---------------------------------------------------------------------------

(defn- status-form [issue-id]
  (list 'my.issue/status {:seon.issue/id issue-id}))

(defn- check-form [function-symbols]
  (list 'my.test/check {:seon.test/changed (vec function-symbols)}))

(defn- test-pull-form [test-symbol]
  (list 'seon.test/recorded-result '(seon.db/db) (list 'quote test-symbol)))

(defn- function-pull-form [function-symbol]
  (list 'seon.db/pull '(seon.db/db)
        (list 'quote [:seon.fn/sym :seon.fn/doc :seon.fn/source :seon.fn/form-span
                      {:seon.fn/file [:seon.fn.file/relative-path]}])
        [:seon.fn/sym function-symbol]))

;;; ---------------------------------------------------------------------------
;;; The opening as data: what the worker's context shows of each issue
;;; ---------------------------------------------------------------------------

(defn- outcome
  "One linked test's recorded outcome, or :unrun when none is recorded."
  {:malli/schema [:=> [:cat :seon.schema/value] [:enum :pass :fail :error :unrun]]}
  [result]
  (cond
    (or (not (map? result)) (:seon.error/at result)) :unrun
    (pos? (get result :seon.test/error-count 0)) :error
    (pos? (get result :seon.test/fail-count 0)) :fail
    :else :pass))

(declare context)

(defmulti ^:private candidate-data
  "What one dialled candidate adds to the opening: data it reads now, and the
  reads it offers in the order it recommends them."
  (fn [selected _database _links] selected))

(defmethod candidate-data :default [_ _ _] {})

(defmethod candidate-data :plan-first
  [_ _ {:seon.test/keys [syms]}]
  {:seon.render/operations [(check-form syms)]})

(defmethod candidate-data :evidence-first
  [_ database {:seon.issue/keys [id problem]}]
  (cond-> (select-keys (context {:seon.db/db database :seon.issue/id id})
                       [:seon.issue/tests :seon.issue/functions])
    ;; the problem is markdown for the reader: its lines print unescaped
    problem (assoc :seon.issue.opening/problem-lines (str/split-lines problem))))

(defmethod candidate-data :walkthrough
  [_ database {:seon.test/keys [syms] fn-syms :seon.fn/syms paths :seon.fn.file/relative-paths}]
  {:seon.issue.opening/first-test (test/recorded-result database (first syms))
   :seon.render/operations (cond-> [(test-pull-form (first syms))]
                             (seq fn-syms) (conj (function-pull-form (first fn-syms)))
                             (seq paths) (conj (list 'my.fs/read {:my.fs/path (first paths)}))
                             true (conj (check-form syms)))})

(defmethod candidate-data :questions
  [_ _ {:seon.test/keys [syms] fn-syms :seon.fn/syms ns-names :seon.ns/names}]
  {:seon.render/operations (cond-> []
                             (seq fn-syms) (conj (function-pull-form (first fn-syms)))
                             true (conj (test-pull-form (first syms)))
                             (seq ns-names) (conj (list 'dir (symbol (first ns-names)))))})

(defmethod candidate-data :namespace-picture
  [_ _ {ns-names :seon.ns/names}]
  {:seon.ns/names ns-names
   :seon.render/operations (mapv #(list 'dir (symbol %)) ns-names)})

(defmethod candidate-data :minimal-retrieval
  [_ _ {:seon.issue/keys [id]}]
  {:seon.render/operations [(list 'seon.issue.opening/context {:seon.issue/id id :seon.render/distance 2})]})


(defn opening
  "One issue's opening as data: its id and title, what decides done (its exact
  tests with their recorded outcomes, or its detector), what the worker's
  dialled candidate adds, and the status read and completing check offered."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.issue/id]
                  [:or :seon.issue.opening/view :seon.error/value]]}
  [database issue-id]
  (if-let [{:seon.test/keys [syms] :as link-entity} (links database issue-id)]
    (let [selected (if (seq syms) (candidate database issue-id) :bare)
          added (candidate-data selected database link-entity)]
      (cond-> (merge (dissoc added :seon.render/operations)
                     {:seon.issue/id issue-id
                      :seon.issue/title (:seon.issue/title link-entity)
                      :seon.render/operations (into (vec (:seon.render/operations added))
                                                    [(status-form issue-id)])})
        (seq syms) (assoc :seon.issue.opening/done-when-tests
                          (into (sorted-map) (map (juxt identity #(outcome (test/recorded-result database %)))) syms))
        (empty? syms) (assoc :seon.issue.opening/detector (:seon.issue/detector link-entity))
        (not= :bare selected) (assoc :seon.issue.opening/candidate selected)))
    (seon.error.refusal/diagnostic (java.util.Date.) :seon.issue/opening 'seon.issue.opening/opening
     {:seon.error/message (str "No current issue " issue-id)})))

(defn openings
  "The openings of every issue `agent-id` works, by issue id."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or [:vector [:or :seon.issue.opening/view :seon.error/value]] :seon.db/error-result]]}
  [database agent-id]
  (let [issues (db/q '[:find [?issue-id ...] :in $ ?agent-id
                       :where [?agent :seon.agent/id ?agent-id]
                       [?issue :seon.issue/agent ?agent]
                       [?issue :seon.issue/id ?issue-id]]
                     database agent-id)]
    (if (db/read-refusal? issues)
      issues
      (mapv #(opening database %) (sort issues)))))

(defn render-ai
  "The agent's issue openings as data (`openings`), an empty vector for an
  agent working none. Its reads are the shown unit's read evidence, so a
  recorded test result or a new assignment changes the shown read."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or [:vector [:or :seon.issue.opening/view :seon.error/value]] :seon.db/error-result]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (openings database agent-id))

(defn render-form
  "The read that finds the agent's issue openings again. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [{agent-id :seon.agent/id}]
  (list 'seon.issue.opening/openings '(seon.db/db) agent-id))

;;; ---------------------------------------------------------------------------
;;; The retrieval call candidate :minimal-retrieval offers
;;; ---------------------------------------------------------------------------

(defn context
  "One hop further out from an issue: its functions' and tests' own entities."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.render/distance {:optional true} :seon.render/distance]]]
                  [:or :map :seon.error/value]]}
  [{database :seon.db/db issue-id :seon.issue/id}]
  (if-let [link-entity (links database issue-id)]
    {:seon.issue/id issue-id
     :seon.issue/functions
     (mapv #(db/pull database
                     '[:seon.fn/sym :seon.fn/doc :seon.fn/source
                       {:seon.fn/file [:seon.fn.file/relative-path]}]
                     [:seon.fn/sym %])
           (:seon.fn/syms link-entity))
     :seon.issue/tests
     (mapv #(test/recorded-result database %)
           (:seon.test/syms link-entity))}
    (seon.error.refusal/diagnostic (java.util.Date.) :seon.issue/opening 'seon.issue.opening/context
     {:seon.error/message (str "No current issue " issue-id)})))
