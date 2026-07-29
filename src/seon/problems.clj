(ns seon.problems
  "Everything wrong RIGHT NOW, derived from the facts that say so.

  THE PULL SIDE OF THE PUSH. `seon.error` commits a fault and mails an
  explanation; this reads the same facts back and answers \"what is
  broken?\" for whoever asks — a human at the REPL, a failing drive, and
  (when N4 lands) a prompt block and a surface. Same facts, no second
  store, no acknowledgement flag, no `seen-at`, nothing stamped when
  something is fixed. A problem stops being a problem when the facts
  stop saying so, which is the only definition that cannot go stale.

  PURE, over a database value and one set. Everything it needs is a
  query except which processes are alive, and that is an observable
  fact about the operating system rather than something the database
  can know — so it is a parameter, by the same name
  `run/recover-tx` already uses.

  A HEALTHY CLUSTER DERIVES `{}`. The value is a map keyed by FAMILY and
  an empty family is ABSENT, never an empty vector and never a
  `:healthy? true`. That does two jobs at once: `(seq (problems …))` is
  the whole question \"is anything wrong\", and no entry needs a
  `:type` discriminator because the key a family arrives under IS the
  family.

  SIX FAMILIES, and each one is a fact nobody has to maintain:

  - ERROR SIGNATURES — every committed `:seon.error` fact, grouped by
    signature. Grouping is the point: a hundred errors of one signature
    is ONE problem that recurred a hundred times, and listing it a
    hundred times would bury the other three. The latest occurrence
    rides along in full, so the log projection composes the ordinary
    per-fact line and a digger needs no second lookup.
  - WEDGED RUNS — a run held by a process that is not in the live set.
    No clock: liveness is observable, and a deadline standing in for it
    is the tuned constant the standing ruling bans.
  - FAILED RUNS — a run that closed carrying WHY. The agent reads this
    in its next prompt; a human reading problems sees the same fact
    from the other side.
  - ERRORED RECEIPTS — one form of one plan that errored. An agent's
    own mistake is NOT a core fault and never becomes an error fact,
    but a plan that keeps erroring is something a human wants to see.
    The distinction survives into the value instead of being flattened.
  - UNOWNED NAMESPACES — a source-bearing program namespace with no
    agent namespace ref. Assignment makes the row disappear immediately.

  WHAT IS DELIBERATELY NOT HERE: a stale-trigger family. \"Unanswered\"
  is derivable and already owned (`work/unanswered-triggers`); STALE is
  not, because it needs a threshold, and a threshold here would be a
  number standing in for an event we cannot observe — a trigger is
  unanswered for a perfectly healthy instant between its commit and the
  loop's next pass. When the loop publishes a pass boundary, staleness
  becomes derivable from THAT and this is where it lands.

  Crash walk: pure, reads only. A kill loses a value nobody had
  committed; the next caller re-derives it from the same facts."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster.work :as work]
            [seon.error :as error]
            [seon.render :as render]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/problems.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The four derivations
;;; ---------------------------------------------------------------------------

(defn- error-signatures
  "Every committed error, grouped by signature, worst-recurring first."
  [db]
  (->> (d/q '[:find [(pull ?error [*]) ...]
              :where [?error :seon.error/signature _]]
            db)
       (group-by :seon.error/signature)
       (mapv (fn [[signature facts]]
               (let [latest (last (sort-by (comp inst-ms :seon.error/at) facts))]
                 {:seon.error/signature signature
                  :seon.error/kind (:seon.error/kind latest)
                  :seon.problems/occurrences (count facts)
                  ;; the entity id is Datahike's, not ours; a projected
                  ;; fact carrying it would not validate as one
                  :seon.error/fact (dissoc latest :db/id)})))
       (sort-by (juxt (comp - :seon.problems/occurrences)
                      :seon.error/signature))
       vec))

(defn- wedged-runs
  "Runs held by a process that is not alive. Open runs only: a closed
  run held by a dead process is finished work, not stuck work."
  [db live-processes]
  (->> (d/q '[:find ?id ?agent-id ?process
              :where
              [?run :seon.cluster.run/id ?id]
              [?run :seon.cluster.run/process ?process]
              (not [?run :seon.cluster.run/closed-at _])
              [?run :seon.cluster.run/agent ?agent]
              [?agent :seon.cluster.agent/id ?agent-id]]
            db)
       (remove (fn [[_ _ process]] (contains? live-processes process)))
       (sort)
       (mapv (fn [[id agent-id process]]
               {:seon.cluster.run/id id
                :seon.cluster.agent/id agent-id
                :seon.cluster.run/process process}))))

(defn- failed-runs
  [db]
  (->> (d/q '[:find ?id ?agent-id ?error
              :where
              [?run :seon.cluster.run/id ?id]
              [?run :seon.cluster.run/error ?error]
              [?run :seon.cluster.run/agent ?agent]
              [?agent :seon.cluster.agent/id ?agent-id]]
            db)
       (sort)
       (mapv (fn [[id agent-id message]]
               {:seon.cluster.run/id id
                :seon.cluster.agent/id agent-id
                :seon.cluster.run/error message}))))

(defn- errored-receipts
  [db]
  (->> (d/q '[:find ?id ?run-id ?ordinal ?source ?kind ?error
              :where
              ;; PRESENCE IS THE STATE: an errored receipt is one that
              ;; carries an error — there is no status label to filter
              ;; on, and the clause below already binds it
              [?receipt :seon.cluster.eval/id ?id]
              [?receipt :seon.cluster.eval/ordinal ?ordinal]
              [?receipt :seon.error/kind ?kind]
              [?receipt :seon.cluster.eval/error ?error]
              [?receipt :seon.cluster.eval/run ?run]
              [?run :seon.cluster.run/id ?run-id]
              [?form :seon.cluster.run.form/run ?run]
              [?form :seon.cluster.run.form/ordinal ?ordinal]
              [?form :seon.cluster.run.form/source ?source]]
            db)
       (sort)
       (mapv (fn [[id run-id ordinal source kind error]]
               {:seon.cluster.eval/id id
                :seon.cluster.run/id run-id
                :seon.cluster.eval/ordinal ordinal
                :seon.cluster.run.form/source source
                :seon.error/kind kind
                :seon.cluster.eval/error error}))))

(defn form-problem
  "A routable red evaluation attributed to its namespace owner, or nil.
  S8 scopes routing to a goal's caused-by chain; X2 additionally excludes
  process-history failures at or after an interrupted ordinal."
  {:malli/schema [:=> [:cat :any :seon.problems/form-problem-request]
                  [:maybe :seon.problems/form-problem]]}
  [db {:keys [:seon.cluster.run/id :seon.cluster.run.form/ordinal
              :seon.sci.eval/evaluation]}]
  (let [form
        (d/q '[:find (pull ?form [*]) .
               :in $ ?run-id ?ordinal
               :where
               [?run :seon.cluster.run/id ?run-id]
               [?form :seon.cluster.run.form/run ?run]
               [?form :seon.cluster.run.form/ordinal ?ordinal]]
             db id ordinal)
        admitted (:seon.sci.admit/value evaluation)
        ordinary-error (:seon.cluster.eval/error evaluation)
        interrupted? (boolean (:seon.cluster.eval/interrupted-at evaluation))
        unbound? (work/unbound-value? admitted)
        red? (or ordinary-error interrupted? unbound?)
        scoped? (work/planner-scoped-attempt? db id)
        artifact? (and red?
                       (work/resume-artifact? db id ordinal interrupted?))]
    (when (and scoped? red? (not artifact?))
      (let [owner-id (work/form-owner db form)
            author-id
            (d/q '[:find ?author-id .
                   :in $ ?form
                   :where
                   [?form :seon.cluster.run.form/run ?run]
                   [?run :seon.cluster.run/agent ?author]
                   [?author :seon.cluster.agent/id ?author-id]]
                 db (:db/id form))
            kind (or (:seon.error/kind admitted)
                     (when unbound? ::unbound-var)
                     ::evaluation-failed)
            error (or ordinary-error
                      (when unbound?
                        "The admitted result contains an unbound var.")
                      "The evaluation was interrupted.")]
        {:seon.problems/id (work/problem-id id ordinal)
         :seon.cluster.eval/id (pr-str [id ordinal])
         :seon.cluster.run/id id
         :seon.cluster.run.form/ordinal ordinal
         :seon.cluster.run.form/source
         (:seon.cluster.run.form/source form)
         :seon.cluster.agent/id owner-id
         :seon.problems/author author-id
         :seon.error/kind kind
         :seon.cluster.eval/error error}))))

(defn assignment-value
  "The E3 message value routing one problem to its derived owner."
  {:malli/schema [:=> [:cat :seon.problems/form-problem]
                  :my.message/message]}
  [problem]
  {:my.message/to (:seon.cluster.agent/id problem)
   :my.message/about (:seon.problems/id problem)
   :my.message/content
   (str "Repair problem " (:seon.problems/id problem)
        " from run " (:seon.cluster.run/id problem)
        ", form " (:seon.cluster.run.form/ordinal problem)
        ": " (:seon.cluster.eval/error problem))})

(defn- deferred-agents
  "Agents whose pending triggers the episode cap is deferring (F1 §7).
  A derivation over derivations — `work/episode-runs` and
  `work/deferred-triggers` — so the state vanishes the moment the next
  outside trigger's run resets the count. Nothing here reads a counter
  or a flag, because none exists."
  [db]
  (->> (d/q '[:find [?agent-id ...]
              :where [_ :seon.cluster.agent/id ?agent-id]]
            db)
       sort
       (keep (fn [agent-id]
               (let [deferred (work/deferred-triggers db agent-id)]
                 (when (seq deferred)
                   {:seon.cluster.agent/id agent-id
                    :seon.cluster.work/episode-runs
                    (work/episode-runs db agent-id)
                    :seon.problems/deferred-count (count deferred)}))))
       vec))

(defn- unowned-namespaces
  "Source-bearing program namespaces with no assigned agent."
  [db]
  (->> (d/q '[:find [?name ...]
              :where
              [?namespace :seon.ns/name ?name]
              [?namespace :seon.ns/source _]
              (not [_ :seon.cluster.agent/namespace ?namespace])]
            db)
       sort
       (mapv (fn [namespace-name]
               {:seon.ns/name namespace-name}))))

;;; ---------------------------------------------------------------------------
;;; The one derivation
;;; ---------------------------------------------------------------------------

(declare ai-prose html-report)

(defn problems
  "Everything wrong now, as a map keyed by family. `{}` when nothing is.
  Pure over `db` plus `:seon.cluster.run/live-processes`, the same set
  by the same name `run/recover-tx` takes — liveness is the one thing a
  database cannot know about the operating system.

  An empty family is ABSENT rather than an empty vector, so a healthy
  cluster derives `{}` and `(seq (problems …))` is the whole question.
  When anything IS wrong the value also carries `:seon.render/log`, so
  it routes through the one projection router like any other unit; a
  `{}` declares no projection, because there is nothing to say.

  Ordering is deterministic and meaningful, not incidental: error
  signatures come worst-recurring first, and every other family sorts
  by its own identity so two derivations of one database value are the
  same value."
  {:malli/schema [:=> [:cat :any :seon.problems/request]
                  :seon.problems/problems]}
  [db {:keys [:seon.cluster.run/live-processes]}]
  (let [signatures (error-signatures db)
        wedged (wedged-runs db live-processes)
        failed (failed-runs db)
        errored (errored-receipts db)
        deferred (deferred-agents db)
        unowned (unowned-namespaces db)
        found (cond-> {}
                (seq signatures) (assoc :seon.problems/error-signatures signatures)
                (seq wedged) (assoc :seon.problems/wedged-runs wedged)
                (seq failed) (assoc :seon.problems/failed-runs failed)
                (seq errored) (assoc :seon.problems/errored-receipts errored)
                (seq deferred) (assoc :seon.problems/deferred-agents deferred)
                (seq unowned)
                (assoc :seon.problems/unowned-namespaces unowned))]
    (cond-> found
      (seq found) (assoc :seon.render/log `log-report)
      (seq found) (assoc :seon.render/html `html-report)
      (or (seq errored) (seq deferred)) (assoc :seon.render/ai `ai-prose))))

;;; ---------------------------------------------------------------------------
;;; The html projection — the problems PAGE
;;; ---------------------------------------------------------------------------

(defn- family-section
  [title rows]
  (when (seq rows)
    [:section {:class "seon-problems-family"}
     [:h2 {:class "seon-problems-family-title"} title]
     [:ul {:class "seon-problems-rows"} rows]]))

(defn- row
  "One problem, as one line. Same grammar for every family: what it is,
  then the identifiers a digger needs, in a fixed order so the eye can
  scan a column rather than read a sentence."
  [& parts]
  [:li {:class "seon-problems-row"}
   (for [[label value] (partition 2 parts)
         :when (some? value)]
     [:span {:class "seon-problems-field"}
      [:span {:class "seon-problems-label"} label]
      [:span {:class "seon-problems-value"} (str value)]])])

(defn html-report
  "`:seon.render/html` — everything wrong now, as a surface.

  THE THIRD PROJECTION OF ONE VALUE, and deliberately not a third
  derivation: `problems` already answered what is wrong, `log-report`
  says it in lines and `ai-prose` says it as steering; this says it in
  hiccup. Adding it was one key on the value and one function, with no
  router change and no registration — which is the whole claim the open
  kind set makes, tested here on the first consumer outside the error
  family.

  GROUPED BY FAMILY, worst first, because the ordering `problems`
  already computed is the ordering a reader wants: an error signature
  that recurred a hundred times leads, and its recurrence count is on
  the row rather than implied by a hundred rows. That is the one place
  the html twin must NOT diverge from the ai twin — the quarry's
  transcript coalesced repeated failures for the agent and not for the
  human, so a thrash burst was one line in the prompt and a hundred in
  the page.

  A healthy cluster never reaches here: `problems` declares no
  projection for `{}`, so there is nothing to render and no cheerful
  empty state to maintain. The BLOCK renders the healthy case, because
  only the block knows a surface has to occupy space either way."
  {:malli/schema [:=> [:cat :seon.problems/problems] :seon.render/hiccup]}
  [found]
  [:div {:class "seon-problems"}
   (family-section
    "errors"
    (for [entry (:seon.problems/error-signatures found)]
      (row "kind" (:seon.error/kind entry)
           "seen" (:seon.problems/occurrences entry)
           "signature" (:seon.error/signature entry)
           "latest" (:seon.error/message (:seon.error/fact entry)))))
   (family-section
    "wedged runs"
    (for [entry (:seon.problems/wedged-runs found)]
      (row "run" (:seon.cluster.run/id entry)
           "agent" (:seon.cluster.agent/id entry)
           "held by" (str (:seon.cluster.run/process entry) " (not alive)"))))
   (family-section
    "failed runs"
    (for [entry (:seon.problems/failed-runs found)]
      (row "run" (:seon.cluster.run/id entry)
           "agent" (:seon.cluster.agent/id entry)
           "error" (:seon.cluster.run/error entry))))
   (family-section
    "errored forms"
    (for [entry (:seon.problems/errored-receipts found)]
      (row "run" (:seon.cluster.run/id entry)
           "form" (:seon.cluster.eval/ordinal entry)
           "kind" (:seon.error/kind entry)
           "source" (:seon.cluster.run.form/source entry)
           "error" (:seon.cluster.eval/error entry))))
   (family-section
    "deferred agents"
    (for [entry (:seon.problems/deferred-agents found)]
      (row "agent" (:seon.cluster.agent/id entry)
           "episode runs" (:seon.cluster.work/episode-runs entry)
           "deferred" (:seon.problems/deferred-count entry))))
   (family-section
    "unowned namespaces"
    (for [entry (:seon.problems/unowned-namespaces found)]
      (row "namespace" (:seon.ns/name entry))))])

(defn block
  "The problems BLOCK's html render: derive, then project.

  The unit a block projection receives carries the exact immutable
  database value, so this derives `problems` at that value and renders
  it — which is what makes the surface a pure function of the database
  and a reconnect a repaint.

  `:seon.cluster.run/live-processes` must ride on the unit. It is the
  one input a database cannot answer, `problems` already takes it by
  that name, and defaulting it here would be the worst kind of quiet
  lie: an absent set makes every held run wedged, so a default of `#{}`
  would invent problems and a default of \"assume alive\" would hide
  them. Absent gets a legible card instead.

  The HEALTHY case is rendered here rather than in `html-report`,
  because only a block knows its surface has to occupy space whether or
  not there is anything to say."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (if-not (contains? unit :seon.cluster.run/live-processes)
    [:div {:class "seon-error-card"}
     [:span {:class "seon-error-card-message"}
      (str "This block needs :seon.cluster.run/live-processes on the unit; "
           "which processes are alive is the one thing the database "
           "cannot answer.")]]
    (let [found (problems (:seon.db/db unit)
                          (select-keys unit [:seon.cluster.run/live-processes]))]
      (if (empty? found)
        [:div {:class "seon-problems seon-problems-healthy"}
         [:span {:class "seon-problems-healthy-mark"} "◆"]
         [:span "nothing is wrong"]]
        (html-report found)))))

(defn ai-prose
  "`:seon.render/ai` — concise steering, derived from the families.
  Failed plan forms keep their lines; a deferred agent gains the
  episode-cap line (F1 §7), present exactly while the derivation says
  so and gone the moment an outside trigger resets it."
  {:malli/schema [:=> [:cat :seon.problems/problems] :string]}
  [found]
  (->> (concat
        (for [entry (:seon.problems/errored-receipts found)]
          (str "Form " (:seon.cluster.eval/ordinal entry)
               " failed during evaluation: "
               (:seon.cluster.eval/error entry)
               ". The run did not retry it. Inspect receipt "
               (:seon.cluster.eval/id entry)
               " and revise the remaining plan from current facts."))
        (for [entry (:seon.problems/deferred-agents found)]
          (str "Agent " (:seon.cluster.agent/id entry) " has run "
               (:seon.cluster.work/episode-runs entry)
               " self-triggered runs since the last outside trigger; "
               (:seon.problems/deferred-count entry)
               " triggers are deferred until one arrives.")))
       (str/join "\n")))

(defn log-report
  "`:seon.render/log` — the whole value as lines, newest concern first.
  COMPOSES rather than reformats: an error signature's line is
  the latest fact's notice through `seon.render/render`, so the line a
  digger sees in `problems` is byte-identical to the one the fault path
  emitted. The other three families have no per-fact owner, so their
  lines are built here, in the same `key=value` grammar.

  Returns \"\" for a healthy cluster — nothing wrong is nothing to say,
  and a cheerful \"no problems\" line is noise in a log that only exists
  to be grepped."
  {:malli/schema [:=> [:cat :seon.problems/problems] :string]}
  [found]
  (->> (concat
        (for [entry (:seon.problems/error-signatures found)]
          (:seon.render/output
           (render/render
            {:seon.render/unit
             (error/notice {:seon.error/fact (:seon.error/fact entry)
                            :seon.error/occurrences
                            (:seon.problems/occurrences entry)})
             :seon.render/kind :seon.render/log})))
        (for [entry (:seon.problems/wedged-runs found)]
          (str "seon.problems wedged-run run=" (:seon.cluster.run/id entry)
               " agent=" (:seon.cluster.agent/id entry)
               " held-by=" (:seon.cluster.run/process entry)
               " (that process is not alive)"))
        (for [entry (:seon.problems/failed-runs found)]
          (str "seon.problems failed-run run=" (:seon.cluster.run/id entry)
               " agent=" (:seon.cluster.agent/id entry)
               " error=" (pr-str (:seon.cluster.run/error entry))))
        (for [entry (:seon.problems/errored-receipts found)]
          (str "seon.problems errored-receipt receipt="
               (:seon.cluster.eval/id entry)
               " run=" (:seon.cluster.run/id entry)
               " ordinal=" (:seon.cluster.eval/ordinal entry)
               " source=" (pr-str (:seon.cluster.run.form/source entry))
               " kind=" (:seon.error/kind entry)
               (when-let [message (:seon.cluster.eval/error entry)]
                 (str " error=" (pr-str message)))))
        (for [entry (:seon.problems/deferred-agents found)]
          (str "seon.problems deferred-agent agent="
               (:seon.cluster.agent/id entry)
               " episode-runs=" (:seon.cluster.work/episode-runs entry)
               " deferred=" (:seon.problems/deferred-count entry)
               " (agent-sent triggers wait for an outside trigger)"))
        (for [entry (:seon.problems/unowned-namespaces found)]
          (str "seon.problems unowned-namespace namespace="
               (:seon.ns/name entry))))
       (str/join "\n")))
