(ns seon.problems
  "Everything wrong RIGHT NOW, derived from the facts that say so.

  THE PULL SIDE OF THE PUSH. `seon.error` commits a fault and mails an
  explanation; this reads the same facts back and answers \"what is
  broken?\" for whoever asks — a human at the REPL, a failing drive, and
  (when N4 lands) a prompt block and a surface. Same facts, no second
  store, no acknowledgement flag, no `seen-at`, nothing stamped when
  something is fixed. A problem stops being a problem when the facts
  stop saying so, which is the only definition that cannot go stale.

  DERIVED from one immutable database value, one live-process set, and
  one snapshot of the loaded JVM namespaces. Database families are
  queries. Process liveness and the loaded image are observable facts
  outside the database, read without storing another status value.

  A HEALTHY CLUSTER DERIVES `{}`. The value is a map keyed by FAMILY and
  an empty family is ABSENT, never an empty vector and never a
  `:healthy? true`. That does two jobs at once: `(seq (problems …))` is
  the whole question \"is anything wrong\", and no entry needs a
  `:type` discriminator because the key a family arrives under IS the
  family.

  EIGHT FAMILIES, and each one is a fact nobody has to maintain:

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
  - DEFERRED AGENTS — agents whose self-triggered work is waiting for
    an outside trigger to begin another episode.
  - UNOWNED NAMESPACES — a source-bearing program namespace with no
    agent namespace ref. Assignment makes the row disappear immediately.
  - STALE VARS — function Vars still interned in a loaded first-party
    namespace after their `[namespace name]` pair disappeared from the
    published program graph. Restarting the JVM removes them.
  - MISSING MODELS — configured model strings with no matching model
    registry row. Adding the row makes the finding disappear; its
    absence never blocks an otherwise valid provider call.

  WHAT IS DELIBERATELY NOT HERE: a stale-trigger family. \"Unanswered\"
  is derivable and already owned (`work/unanswered-triggers`); STALE is
  not, because it needs a threshold, and a threshold here would be a
  number standing in for an event we cannot observe — a trigger is
  unanswered for a perfectly healthy instant between its commit and the
  loop's next pass. When the loop publishes a pass boundary, staleness
  becomes derivable from THAT and this is where it lands.

  Crash walk: reads only. A kill loses a value nobody had
  committed; the next caller re-derives it from the same facts."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.turn :as turn]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The derivations
;;; ---------------------------------------------------------------------------

(defn- restore-error-ref
  "Restore one pulled error ref to its transaction representation."
  [pulled identity-attribute]
  (cond
    (not (map? pulled)) pulled
    (and (not= :db/id identity-attribute) (contains? pulled identity-attribute))
    [identity-attribute (get pulled identity-attribute)]
    (contains? pulled :db/id) (:db/id pulled)
    :else pulled))

(defn- restore-error-fact
  "Restore a pulled error entity to the fact shape its contract declares."
  [pulled]
  (cond-> (dissoc pulled :db/id)
    (contains? pulled :seon.error/run)
    (update :seon.error/run restore-error-ref :seon.turn/id)

    (contains? pulled :seon.error/agent)
    (update :seon.error/agent restore-error-ref :seon.agent/id)
    (:seon.error/resolved-tx pulled)
    (update :seon.error/resolved-tx restore-error-ref :db/id)
    (:seon.error/issue pulled)
    (update :seon.error/issue restore-error-ref :db/id)
    (:seon.error/regressions pulled)
    (update :seon.error/regressions #(into #{} (map (fn [r] (restore-error-ref r :db/id))) %))))

(defn- error-signatures
  "Errors ordered by the sum of their occurrence counts."
  [database]
  (->> (db/q '[:find [(pull ?error
                            [* {:seon.error/fn [:db/id :seon.fn/sym]}
                             {:seon.error/occurrences
                              [* {:seon.error.occurrence/turn [:db/id :seon.turn/id]}
                                 {:seon.error.occurrence/agent [:db/id :seon.agent/id]}]}]) ...]
                :where [?error :seon.error/signature]] database)
       (mapv (fn [row]
               (let [fact (restore-error-fact (error/latest-fact row))]
                 {:seon.error/signature (:seon.error/signature row)
                  :seon.error/kind (:seon.error/kind row)
                  :seon.problems/occurrences (reduce + 0 (map :seon.error.occurrence/count
                                                            (:seon.error/occurrences row)))
                  :seon.error/fact fact})))
       (sort-by (juxt (comp - :seon.problems/occurrences) :seon.error/signature))
       vec))

(defn- failed-runs
  [db]
  (->> (db/q '[:find ?id ?agent-id ?error
              :where
              [?run :seon.turn/id ?id]
              [?run :seon.turn/closed-tx _]
              [?occurrence :seon.error.occurrence/turn ?run]
              [?occurrence :seon.error.occurrence/message ?error]
              [?run :seon.turn/agent ?agent]
              [?agent :seon.agent/id ?agent-id]]
            db)
       (sort)
       (mapv (fn [[id agent-id message]]
               {:seon.turn/id id
                :seon.agent/id agent-id
                :seon.error/message message}))))

(defn- errored-receipts
  [db]
  (->> (db/q '[:find ?id ?run-id ?ordinal ?source ?kind ?error
              :where
              ;; PRESENCE IS THE STATE: an errored receipt is one that
              ;; carries an error — there is no status label to filter
              ;; on, and the clause below already binds it
              [?receipt :seon.cluster.eval/id ?id]
              [?receipt :seon.cluster.eval/ordinal ?ordinal]
              [?receipt :seon.error/kind ?kind]
              [?receipt :seon.cluster.eval/error ?error]
              [?receipt :seon.cluster.eval/run ?run]
              [?run :seon.turn/id ?run-id]
              [?form :seon.cluster.eval/run ?run]
              [?form :seon.cluster.eval/ordinal ?ordinal]
              [?form :seon.cluster.eval/source ?source]]
            db)
       (sort)
       (mapv (fn [[id run-id ordinal source kind error]]
               {:seon.cluster.eval/id id
                :seon.turn/id run-id
                :seon.cluster.eval/ordinal ordinal
                :seon.cluster.eval/source source
                :seon.error/kind kind
                :seon.cluster.eval/error error}))))

(defn form-problem
  "A routable red evaluation attributed to its namespace owner, or nil.
  S8 scopes routing to a goal's caused-by chain; X2 additionally excludes
  process-history failures at or after an interrupted ordinal."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.problems/form-problem-request]
                  [:maybe :seon.problems/form-problem]]}
  [db {:keys [:seon.turn/id :seon.cluster.eval/ordinal
              :seon.sci.eval/evaluation]}]
  (let [form
        (db/q '[:find (pull ?form [*]) .
               :in $ ?run-id ?ordinal
               :where
               [?run :seon.turn/id ?run-id]
               [?form :seon.cluster.eval/run ?run]
               [?form :seon.cluster.eval/ordinal ?ordinal]]
             db id ordinal)
        admitted (:seon.sci.admit/value evaluation)
        ordinary-error (:seon.cluster.eval/error evaluation)
        interrupted? (boolean (:seon.cluster.eval/interrupted-at evaluation))
        unbound? (turn/unbound-value? admitted)
        red? (or ordinary-error interrupted? unbound?)
        scoped? (turn/planner-scoped-attempt? db id)
        artifact? (and red?
                       (turn/resume-artifact? db id ordinal interrupted?))]
    (when (and scoped? red? (not artifact?))
      (let [owner-id (turn/form-owner db form)
            author-id
            (db/q '[:find ?author-id .
                   :in $ ?form
                   :where
                   [?form :seon.cluster.eval/run ?run]
                   [?run :seon.turn/agent ?author]
                   [?author :seon.agent/id ?author-id]]
                 db (:db/id form))
            kind (or (:seon.error/kind admitted)
                     (when unbound? ::unbound-var)
                     ::evaluation-failed)
            error (or ordinary-error
                      (when unbound?
                        "The admitted result contains an unbound var.")
                      "The evaluation was interrupted.")]
        {:seon.problems/id (turn/problem-id id ordinal)
         :seon.cluster.eval/id (turn/problem-id id ordinal)
         :seon.turn/id id
         :seon.cluster.eval/ordinal ordinal
         :seon.cluster.eval/source
         (:seon.cluster.eval/source form)
         :seon.agent/id owner-id
         :seon.problems/author author-id
         :seon.error/kind kind
         :seon.cluster.eval/error error}))))

(defn assignment-value
  "The E3 message value routing one problem to its derived owner.

  Nil when the derived owner is the author: assigning an agent its own
  red form carries no information, while the problem remains an
  unrouted-red settlement fact."
  {:malli/schema [:=> [:cat :seon.problems/form-problem]
                  [:maybe :my.message/message]]}
  [problem]
  (when-not (= (:seon.agent/id problem)
               (:seon.problems/author problem))
    {:my.message/to (:seon.agent/id problem)
     :my.message/about (:seon.problems/id problem)
     :my.message/content
     (str "Repair problem " (:seon.problems/id problem)
          " from run " (:seon.turn/id problem)
          ", form " (:seon.cluster.eval/ordinal problem)
          ": " (:seon.cluster.eval/error problem))}))

(defn- deferred-agents
  "Agents whose pending triggers the episode cap is deferring (F1 §7).
  A derivation over derivations — `work/episode-runs` and
  `work/deferred-triggers` — so the state vanishes the moment the next
  outside trigger's run resets the count. Nothing here reads a counter
  or a flag, because none exists."
  [db]
  (->> (db/q '[:find [?agent-id ...]
              :where [_ :seon.agent/id ?agent-id]]
            db)
       sort
       (keep (fn [agent-id]
               (let [deferred (turn/deferred-triggers db agent-id)]
                 (when (seq deferred)
                   {:seon.agent/id agent-id
                    :seon.turn.work/episode-runs
                    (turn/episode-runs db agent-id)
                    :seon.problems/deferred-count (count deferred)}))))
       vec))

(defn- unstewarded-namespaces
  "Source-bearing program namespaces with no steward.

  OWNERSHIP IS THE NAMESPACE'S OWN `:seon.ns/steward` FACT, asserted inside
  the creation transaction (`seon.cluster.agent/steward-call`) and read by
  `seon.cluster.agent/steward-of`. This used to invert
  `:seon.agent/namespace`, but that attribute is assignment, not
  ownership: it is not unique, several agents may be assigned one namespace,
  and a namespace every agent had merely been assigned still answered
  \"owned\". Absence of a steward is what nobody can fake."
  [db]
  (->> (db/q '[:find [?name ...]
              :where
              [?namespace :seon.ns/name ?name]
              [?namespace :seon.ns/source _]
              (not [?namespace :seon.schema.admission/source :core])
              (not [?namespace :seon.ns/steward _])]
            db)
       sort
       (mapv (fn [namespace-name]
               {:seon.ns/name namespace-name}))))

(defn- indexed-function-var?
  "Whether an interned Var has the static function index's shape."
  [interned-var]
  (let [metadata (meta interned-var)]
    (and (bound? interned-var)
         (seq (:arglists metadata))
         (not (:macro metadata))
         (not (:test metadata)))))

(defn- stale-vars
  "Loaded first-party function Vars absent from the published graph."
  [db]
  (let [first-party-namespaces
        (sort
         (db/q '[:find [?namespace-name ...]
                 :where
                 [?namespace :seon.ns/name ?namespace-name]
                 [?namespace :seon.ns/source _]
                 [?namespace :seon.schema.admission/source :core]]
               db))
        published-functions
        (db/q '[:find ?namespace-name ?function-symbol
                :where
                [?namespace :seon.ns/name ?namespace-name]
                [?namespace :seon.ns/source _]
                [?namespace :seon.schema.admission/source :core]
                [?function :seon.fn/ns ?namespace]
                [?function :seon.fn/sym ?function-symbol]]
              db)
        loaded-namespaces
        (into {} (map (juxt ns-name identity)) (all-ns))]
    (into []
          (comp
           (mapcat
            (fn [namespace-name]
              (when-let [loaded-namespace (get loaded-namespaces namespace-name)]
                (map (fn [[intern-name interned-var]]
                       [namespace-name intern-name interned-var])
                     (sort-by key (ns-interns loaded-namespace))))))
           (filter (fn [[_ _ interned-var]]
                     (indexed-function-var? interned-var)))
           (remove
            (fn [[namespace-name intern-name _]]
              (contains?
               published-functions
               [namespace-name
                (str (symbol (str namespace-name) (str intern-name)))])))
           (map (fn [[namespace-name intern-name _]]
                  {:seon.fn/sym
                   (str (symbol (str namespace-name) (str intern-name)))})))
          first-party-namespaces)))

(defn- missing-models
  "Configured model strings absent from the model registry."
  [db]
  (->> (db/q '[:find [?model ...]
               :where
               [_ :seon.config.ai/model ?model]
               (not [_ :seon.ai.model/id ?model])]
             db)
       sort
       (mapv (fn [model]
               {:seon.config.ai/model model}))))

;;; ---------------------------------------------------------------------------
;;; The one derivation
;;; ---------------------------------------------------------------------------

(declare ai-prose html-report
         missing-model-ai missing-model-html
         stale-var-ai stale-var-html)

(defn problems
  "Everything wrong now, as a map keyed by family. `{}` when nothing is.
  Reads one immutable `db` and one
  loaded-namespace snapshot. The same database value drives every
  query; neither process liveness nor JVM namespace state is stored.

  An empty family is ABSENT rather than an empty vector, so a healthy
  cluster derives `{}` and `(seq (problems …))` is the whole question.
  Ordering is deterministic and meaningful, not incidental: error
  signatures come worst-recurring first, and every other family sorts
  by its own identity so two derivations of one database value are the
  same value."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.problems/request]
                  [:or :seon.problems/problems :seon.error/value]]}
  [db _request]
  (if-not (or (db/carried-projection db) (schema/handed-projection))
    (db/projection-fallback 'seon.problems/problems)
    (let [signatures (error-signatures db)
        failed (failed-runs db)
        errored (errored-receipts db)
        deferred (deferred-agents db)
        unstewarded (unstewarded-namespaces db)
        stale (stale-vars db)
        missing-model-rows (missing-models db)
        found (cond-> {}
                (seq signatures) (assoc :seon.problems/error-signatures signatures)
                (seq failed) (assoc :seon.problems/failed-runs failed)
                (seq errored) (assoc :seon.problems/errored-receipts errored)
                (seq deferred) (assoc :seon.problems/deferred-agents deferred)
                (seq unstewarded)
                (assoc :seon.problems/unowned-namespaces unstewarded)
                (seq stale) (assoc :seon.problems/stale-vars stale)
                (seq missing-model-rows)
                (assoc :seon.problems/missing-models missing-model-rows))]
    found)))

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

(defn stale-var-html
  "A stale loaded Var as one problems-surface row."
  {:malli/schema
   [:=> [:cat :seon.problems/stale-var] :seon.render/hiccup]}
  [entry]
  (row "var" (:seon.fn/sym entry)))

(defn stale-var-ai
  "Steering for one loaded Var absent from the published program graph."
  {:malli/schema [:=> [:cat :seon.problems/stale-var] :string]}
  [entry]
  (str "Restart the JVM to remove stale loaded Var "
       (:seon.fn/sym entry)
       "; it is absent from the published program graph."))

(defn missing-model-html
  "A configured model missing its registry row as one surface row."
  {:malli/schema
   [:=> [:cat :seon.problems/missing-model] :seon.render/hiccup]}
  [entry]
  (row "model" (:seon.config.ai/model entry)
       "problem" "no registry row"))

(defn missing-model-ai
  "Steering for a configured model absent from the registry."
  {:malli/schema [:=> [:cat :seon.problems/missing-model] :string]}
  [entry]
  (str "Configured model " (:seon.config.ai/model entry)
       " has no :seon.ai.model/id registry row. "
       "Add its descriptor; provider calls continue unchanged."))

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
    "failed runs"
    (for [entry (:seon.problems/failed-runs found)]
      (row "run" (:seon.turn/id entry)
           "agent" (:seon.agent/id entry)
           "error" (:seon.error/message entry))))
   (family-section
    "errored forms"
    (for [entry (:seon.problems/errored-receipts found)]
      (row "run" (:seon.turn/id entry)
           "form" (:seon.cluster.eval/ordinal entry)
           "kind" (:seon.error/kind entry)
           "source" (:seon.cluster.eval/source entry)
           "error" (:seon.cluster.eval/error entry))))
   (family-section
    "deferred agents"
    (for [entry (:seon.problems/deferred-agents found)]
      (row "agent" (:seon.agent/id entry)
           "episode runs" (:seon.turn.work/episode-runs entry)
           "deferred" (:seon.problems/deferred-count entry))))
   (family-section
    "unstewarded namespaces"
    (for [entry (:seon.problems/unowned-namespaces found)]
      (row "namespace" (:seon.ns/name entry))))
   (family-section
    "stale vars"
    (map stale-var-html (:seon.problems/stale-vars found)))
   (family-section
    "missing models"
    (map missing-model-html (:seon.problems/missing-models found)))])

(defn block
  "Derive the problems block from its supplied database value."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [found (problems (:seon.db/db unit) {})]
    (if (empty? found)
      [:div {:class "seon-problems seon-problems-healthy"}
       [:span {:class "seon-problems-healthy-mark"} "◆"]
       [:span "nothing is wrong"]]
      (html-report found))))

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
          (str "Agent " (:seon.agent/id entry) " has run "
               (:seon.turn.work/episode-runs entry)
               " self-triggered runs since the last outside trigger; "
               (:seon.problems/deferred-count entry)
               " triggers are deferred until one arrives."))
        (map stale-var-ai (:seon.problems/stale-vars found))
        (map missing-model-ai (:seon.problems/missing-models found)))
       (str/join "\n")))

(defn log-report
  "The whole value as log lines, newest concern first.
  COMPOSES rather than reformats: an error signature's line is
  the latest fact's ordinary `seon.error/log-line`, so the line a
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
          (error/log-line
           (error/notice {:seon.error/fact (:seon.error/fact entry)
                          :seon.error/occurrence-count
                          (:seon.problems/occurrences entry)})))

        (for [entry (:seon.problems/failed-runs found)]
          (str "seon.problems failed-run run=" (:seon.turn/id entry)
               " agent=" (:seon.agent/id entry)
               " error=" (pr-str (:seon.error/message entry))))
        (for [entry (:seon.problems/errored-receipts found)]
          (str "seon.problems errored-receipt receipt="
               (:seon.cluster.eval/id entry)
               " run=" (:seon.turn/id entry)
               " ordinal=" (:seon.cluster.eval/ordinal entry)
               " source=" (pr-str (:seon.cluster.eval/source entry))
               " kind=" (:seon.error/kind entry)
               (when-let [message (:seon.cluster.eval/error entry)]
                 (str " error=" (pr-str message)))))
        (for [entry (:seon.problems/deferred-agents found)]
          (str "seon.problems deferred-agent agent="
               (:seon.agent/id entry)
               " episode-runs=" (:seon.turn.work/episode-runs entry)
               " deferred=" (:seon.problems/deferred-count entry)
               " (agent-sent triggers wait for an outside trigger)"))
        (for [entry (:seon.problems/unowned-namespaces found)]
          (str "seon.problems unstewarded-namespace namespace="
               (:seon.ns/name entry)))
        (for [entry (:seon.problems/stale-vars found)]
          (str "seon.problems stale-var var=" (:seon.fn/sym entry)
               " (absent from the published program graph; restart the JVM)"))
        (for [entry (:seon.problems/missing-models found)]
          (str "seon.problems missing-model model="
               (pr-str (:seon.config.ai/model entry))
               " (no :seon.ai.model/id registry row; calls continue unchanged)")))
       (str/join "\n")))
