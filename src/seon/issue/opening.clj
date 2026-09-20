(ns seon.issue.opening
  "Candidate AI openings for a worker's issue block, one per trial rendering.

  The agent identity renderer emits these ordinary generated read forms.
  The issue schema's AI/HTML pair renders the resulting data. The dial
  that selects one: `:seon.config.render/issue-opening`, a per-agent config
  enum, read from the worker named by `:seon.issue/agent`. An issue with no
  worker, or a worker with no dial, renders `:bare` — the floor the family
  shipped with.

  Every candidate obeys the family's rulings: it LINKS rather than copies
  (function and test source are read with `doc` or `seon.db/pull`), it emits
  only reads, and every completing call it names is exact and executable.
  The forms a candidate emits are evaluated as ordinary generated reads in
  the worker's system turn, so a write here would be refused by the loop
  rather than by a reviewer.

  The trial that compares them is
  docs/prds/steward-platform/plan/issue-context-trials-2026-09-16.md."
  (:require [clojure.string :as str]
            [seon.ai :as ai]
            [seon.db :as db]
            [seon.test :as test]
            [seon.repl :as repl]))

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
  (let [row (db/pull database
                     '[:seon.issue/id :seon.issue/title :seon.issue/problem
                       {:seon.issue/agent [:seon.agent/id]}
                       {:seon.issue/detector [:seon.fn/sym]}
                       {:seon.issue/functions
                        [:seon.fn/sym {:seon.fn/ns [:seon.ns/name]}
                         {:seon.fn/file [:seon.fn.file/relative-path]}]}]
                     [:seon.issue/id issue-id])]
    (when (:seon.issue/title row)
      (cond-> {:seon.issue/id (:seon.issue/id row)
       :seon.issue/title (:seon.issue/title row)
       :seon.issue/problem (:seon.issue/problem row)
       :seon.issue/detector (get-in row [:seon.issue/detector :seon.fn/sym])
       :seon.test/syms (vec (sort (db/q '[:find [?symbol ...] :in $ ?issue-id
                                         :where [?issue :seon.issue/id ?issue-id]
                                                [?issue :seon.issue/tests ?test]
                                                [?test :seon.test/sym ?symbol]]
                                       database issue-id)))
       :seon.fn/syms (vec (sort (map :seon.fn/sym (:seon.issue/functions row))))
       :seon.ns/names (vec (sort (distinct (keep #(get-in % [:seon.fn/ns :seon.ns/name])
                                                 (:seon.issue/functions row)))))
       :seon.fn.file/relative-paths (vec (sort (distinct (keep #(get-in % [:seon.fn/file :seon.fn.file/relative-path])
                                                      (:seon.issue/functions row)))))}
        (get-in row [:seon.issue/agent :seon.agent/id])
        (assoc :seon.agent/id (get-in row [:seon.issue/agent :seon.agent/id]))))))

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

(defn- commented-form
  "One form, printed as the agent would write it, as comment lines."
  [form]
  (str/join "\n" (map #(str ";;   " %) (str/split-lines (repl/source-text form)))))

(defn- comment-lines [lines]
  (str/join "\n" (map #(if (str/blank? %) ";;" (str ";; " %)) lines)))

(defn- block [& parts]
  (str (str/join "\n" (remove str/blank? parts)) "\n"))

;;; ---------------------------------------------------------------------------
;;; The candidates
;;; ---------------------------------------------------------------------------

(defmulti ^:private render-candidate
  "One candidate rendering of an issue block, keyed by the dial."
  (fn [selected _links] selected))

(defmethod render-candidate :bare
  [_ {:seon.issue/keys [id]}]
  (block ";; My issue. The system decides completion from the condition above."
         (repl/source-text (status-form id))))

(defmethod render-candidate :plan-first
  [_ {:seon.issue/keys [id] :seon.test/keys [syms]}]
  (block (comment-lines
          ["My plan's steps carry this issue; its tests decide done."
           "The one call that proves it finished:"])
         (commented-form (check-form syms))
         (repl/source-text (status-form id))))

(defmethod render-candidate :evidence-first
  [_ {:seon.issue/keys [id problem] :seon.test/keys [syms] fn-syms :seon.fn/syms}]
  (apply block
         (concat
          [";; The red test, first: its own source and its last failure."]
          (map #(repl/source-text (test-pull-form %)) syms)
          [";; Then each function the test reaches."]
          (map #(repl/source-text (function-pull-form %)) fn-syms)
          [(comment-lines (into ["The problem, last:"] (str/split-lines (or problem ""))))
           (repl/source-text (status-form id))])))

(defmethod render-candidate :walkthrough
  [_ {:seon.issue/keys [id] :seon.test/keys [syms] fn-syms :seon.fn/syms
      paths :seon.fn.file/relative-paths}]
  (block (comment-lines ["One worked way through an issue like this one, in order."
                         "1. Read the test:"])
         (commented-form (test-pull-form (first syms)))
         (comment-lines ["2. Read each function it reaches:"])
         (commented-form (function-pull-form (first fn-syms)))
         (comment-lines ["3. Read the file around the form:"])
         (commented-form (list 'my.fs/read {:my.fs/path (first paths)}))
         (comment-lines ["4. Evaluate the corrected definition with its contract."
                         "5. Observe the exact tests:"])
         (commented-form (check-form syms))
         (comment-lines ["Every step is a call; nothing here is a summary of a call."])
         (comment-lines (map #(str "The file: " %) paths))
         ";; Start at step 1."
         (repl/source-text (test-pull-form (first syms)))
         (repl/source-text (status-form id))))

(defmethod render-candidate :questions
  [_ {:seon.issue/keys [id] :seon.test/keys [syms] fn-syms :seon.fn/syms
      ns-names :seon.ns/names}]
  (block (comment-lines ["Three questions. Answer each one with a form, not a guess."
                         "Which function throws?"])
         (commented-form (function-pull-form (first fn-syms)))
         (comment-lines ["What does the test expect?"])
         (commented-form (test-pull-form (first syms)))
         (comment-lines ["What else lives beside it?"])
         (commented-form (list 'dir (symbol (first ns-names))))
         (repl/source-text (status-form id))))

(defmethod render-candidate :namespace-picture
  [_ {:seon.issue/keys [id] ns-names :seon.ns/names}]
  (apply block
         (concat
          [";; I am the steward of these namespaces. The picture first."]
          (map #(repl/source-text (list 'dir (symbol %))) ns-names)
          [";; One of its issues is mine."
           (repl/source-text (status-form id))])))

(defmethod render-candidate :minimal-retrieval
  [_ {:seon.issue/keys [id]}]
  (block ";; My issue. The system decides completion from the condition above."
         (comment-lines ["One hop more is available, but only if I ask for it:"])
         (commented-form (list 'seon.issue.opening/context
                               {:seon.issue/id id :seon.render/distance 2}))
         (repl/source-text (status-form id))))

(defmethod render-candidate :default
  [_ link-row]
  (render-candidate default-candidate link-row))

(defn source
  "Render one issue's opening block through the candidate its worker is dialled to."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.issue/id]
                  [:or :seon.render/source :seon.error/value]]}
  [database issue-id]
  (if-let [link-row (links database issue-id)]
    (block
     (comment-lines
      (if (seq (:seon.test/syms link-row))
        [(str "Done when these exact tests pass: " (str/join ", " (:seon.test/syms link-row)) ".")
         "After each turn the system checks changed evidence and reports passed and failed tests."]
        [(str "Done when (" (:seon.issue/detector link-row)
              " (seon.db/db)) no longer names this issue's subject.")
         "The system checks the detector after each turn and reports whether the issue is still open."]))
     (render-candidate (if (seq (:seon.test/syms link-row))
                         (candidate database issue-id) :bare) link-row))
    {:seon.error/kind :seon.issue/not-found
     :seon.error/message (str "No current issue " issue-id)}))

;;; ---------------------------------------------------------------------------
;;; The retrieval call candidate :minimal-retrieval offers
;;; ---------------------------------------------------------------------------

(defn context
  "One hop further out from an issue: its functions' and tests' own rows."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
                             [:seon.issue/id :seon.issue/id]
                             [:seon.render/distance {:optional true} :seon.render/distance]]]
                  [:or :map :seon.error/value]]}
  [{database :seon.db/db issue-id :seon.issue/id}]
  (if-let [link-row (links database issue-id)]
    {:seon.issue/id issue-id
     :seon.issue/functions
     (mapv #(db/pull database
                     '[:seon.fn/sym :seon.fn/doc :seon.fn/source
                       {:seon.fn/file [:seon.fn.file/relative-path]}]
                     [:seon.fn/sym %])
           (:seon.fn/syms link-row))
     :seon.issue/tests
     (mapv #(test/recorded-result database %)
           (:seon.test/syms link-row))}
    {:seon.error/kind :seon.issue/not-found
     :seon.error/message (str "No current issue " issue-id)}))
