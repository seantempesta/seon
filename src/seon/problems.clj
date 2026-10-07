(ns seon.problems
  "Everything wrong RIGHT NOW, derived from the facts that say so.

  THE PULL SIDE OF THE PUSH. `seon.error` commits a fault and mails an
  explanation; this reads the same facts back and answers \"what is
  broken?\" for whoever asks — a human at the REPL, a failing drive, and
  (when N4 lands) a prompt block and a surface. Same facts, no second
  store, no acknowledgement flag, no `seen-at`. A problem stops being a
  problem when the facts stop saying so, which is the only definition
  that cannot go stale. The one fact written when a fault is fixed is
  its `:seon.error/resolved-tx` (`resolve!`), kept for fixes away from the
  failing function; `open-faults` derives the still-open signatures from
  `seon.error/resolution`, which also reads a changed definition digest.

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

  SEVEN FAMILIES, and each one is a fact nobody has to maintain:

  - ERROR SIGNATURES — every committed `:seon.error` fact, grouped by
    signature. Grouping is the point: a hundred errors of one signature
    is ONE problem that recurred a hundred times, and listing it a
    hundred times would bury the other three. The latest occurrence
    rides along in full, so a digger needs no second lookup.
  - WEDGED RUNS — a run held by a process that is not in the live set.
    No clock: liveness is observable, and a deadline standing in for it
    is the tuned constant the standing ruling bans.
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
  (:require [seon.db :as db]
            [seon.turn :as turn]
            [seon.cluster.wake :as wake]
            [seon.error :as error]
            [seon.config :as config]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.render.test :as test-render]
            [seon.test :as test]
            [seon.test.runner :as test.runner]
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
    (update :seon.error/resolved-tx restore-error-ref :db/id)))

(defn- error-signatures
  "Errors ordered by the sum of their occurrence counts; a resolved one carries its `seon.error/resolution`.

  Read through the error's declared observation selector
  ([[seon.error/observation-selector]]), never `*`: a wildcard pull depends on
  every attribute, so any commit would re-derive it; named attributes let a
  reader key this family by their revisions.

  ## Costs

  Armed, default 2026-09-28 (47 signatures): 163 ms, of which the pull of 541
  declared attributes is 133 ms; the `*` pull it replaced was 4.6 ms but
  depended on every commit."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:vector :seon.problems/error-signature] :seon.db/error-result]]}
  [database]
  (let [errors (db/q '[:find [?error ...] :where [?error :seon.error/signature]] database)
        rows (if (db/read-refusal? errors)
               errors
               (db/pull-many database (error/observation-selector (db/carried-projection database)) errors))]
    (if (db/read-refusal? rows)
      rows
      (->> rows
           (mapv (fn [row]
                   (let [fact (restore-error-fact (error/latest-fact row))
                         resolution (error/resolution database (:seon.error/signature row))]
                     (cond->
                      {:seon.error/signature (:seon.error/signature row)
                       :seon.error/at (:seon.error/at fact)
                       :seon.error/layer (:seon.error/layer fact)
                       :seon.error/operation (:seon.error/operation fact)
                       :seon.problems/occurrences (reduce + 0 (map :seon.error.occurrence/count
                                                                 (:seon.error/occurrences row)))
                       :seon.error/fact fact}
                       ;; who the fault counts against (the blame rule), when known
                       (:seon.error/producer row) (assoc :seon.error/producer (:seon.error/producer row))
                       (:seon.error/resolved-by resolution) (assoc :seon.error/resolution resolution)))))
           (sort-by (juxt (comp - :seon.problems/occurrences) :seon.error/signature))
           vec))))

(defn open-faults
  "Every fault `seon.error/resolution` leaves open at `database`: the error
  signatures (`problems`'s family) with no resolution, worst-recurring first.
  With `signature`, only that fault. A fault resolves when its function's
  definition digest changed after its latest occurrence, or by `resolve!`; a
  later occurrence re-opens it, and an unreadable resolution keeps it open.
  This is the one reader of open faults; the health report ranks them.

  ## Examples

      (seon.problems/open-faults (seon.db/db))"
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value]
                   [:or [:vector :seon.problems/error-signature] :seon.db/error-result]]
                  [:=> [:cat :seon.db/database-value :seon.error/signature]
                   [:or [:vector :seon.problems/error-signature] :seon.db/error-result]]]}
  ([database]
   (let [entries (error-signatures database)]
     (if (db/read-refusal? entries)
       entries
       (filterv #(not (:seon.error/resolved-by (:seon.error/resolution %))) entries))))
  ([database signature]
   (let [entries (open-faults database)]
     (if (db/read-refusal? entries)
       entries
       (filterv #(= signature (:seon.error/signature %)) entries)))))

(defn resolve!
  "Record that the fault `signature` names is fixed away from its failing function.
  A fix at that function resolves it without this (`seon.error/resolution`).
  Its signature is open again only when a later occurrence is recorded.
  Called by whoever fixed it, never inferred from a turn. Answers the
  resolved fault (its signature, operation and resolving transaction), or the
  writer's refusal (an unrecorded signature)."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.error/signature]
                  [:or :seon.problems/resolved :seon.db/transaction-refused-error]]}
  [connection signature]
  (let [report (db/transact! connection [[:db/add [:seon.error/signature signature]
                                          :seon.error/resolved-tx "datomic.tx"]])]
    (if (:db-after report)
      (db/pull (:db-after report) [:seon.error/signature :seon.error/operation :seon.error/resolved-tx]
               [:seon.error/signature signature])
      report)))

(defn- errored-receipts
  [db]
  (->> (db/q '[:find ?id ?run-id ?ordinal ?source ?error ?at
              :where
              ;; PRESENCE IS THE STATE: an errored receipt is one that
              ;; carries an error — there is no status label to filter
              ;; on, and the clause below already binds it
              [?receipt :seon.cluster.eval/id ?id]
              [?receipt :seon.cluster.eval/ordinal ?ordinal]
              [?receipt :seon.cluster.eval/error ?error]
              [?receipt :seon.cluster.eval/at ?at]
              [?receipt :seon.cluster.eval/run ?run]
              [?run :seon.turn/id ?run-id]
              [?form :seon.cluster.eval/run ?run]
              [?form :seon.cluster.eval/ordinal ?ordinal]
              [?form :seon.cluster.eval/source ?source]]
            db)
       (sort)
       (mapv (fn [[id run-id ordinal source error at]]
               {:seon.cluster.eval/id id
                :seon.turn/id run-id
                :seon.cluster.eval/ordinal ordinal
                :seon.cluster.eval/source source
                :seon.cluster.eval/error error
                :seon.cluster.eval/at at}))))

(defn- deferred-agents
  "Agents whose pending triggers the episode cap is deferring (F1 §7).
  A derivation over derivations — `turn/episode-runs` and
  `turn/deferred-triggers` — so the state vanishes the moment the next
  outside trigger's run resets the count. Nothing here reads a counter
  or a flag, because none exists.

  # Costs

  Unarmed, default 2026-09-27: 2 ms, against 114 ms when every agent ever
  created (123) was asked. A deferred trigger is a message wake, so only
  agents a message addresses through a turn-opening wake attribute are asked."
  {:seon/tasks #{'seon.problems/a-status-observation-costs-half-a-gigabyte}}
  [db]
  (->> (db/q '[:find [?agent-id ...]
              :in $ [?attribute ...]
              :where [?message :seon.message/id _]
                     [?message ?attribute ?agent]
                     [?agent :seon.agent/id ?agent-id]]
            db (vec (wake/turn-opening-attributes db)))
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
                (symbol (str namespace-name) (str intern-name))])))
           (map (fn [[namespace-name intern-name _]]
                  {:seon.fn/sym
                   (symbol (str namespace-name) (str intern-name))})))
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

(defn- failed-tests
  "Current failed executions from admitted member facts, with each test whose
  latest execution recorded no outcome kept as its own typed unknown. Only
  symbols that are test rows at `database` count: a deleted test's member
  evidence is history, not a current failure. A red counts only while its
  recorded reach digest equals the program's current one (an outdated red is
  unknown evidence, never a current failure, and never reuses as green).

  ## Costs

  `reach-digests` for the reds only, through the runner's reach cache: 40 ms
  warm and 750 ms cold for the 102 reds on default 2026-10-06."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.test/latest-results :seon.test/execution-error :seon.db/invalid-read-error]]}
  [database]
  (let [symbols (db/q '[:find [?symbol ...]
                        :where [_ :seon.test/sym ?symbol]
                               [_ :seon.test.member/symbol ?symbol]] database)
        results (if (:seon.error/at symbols) symbols
                    (test.runner/latest-results database symbols))]
    (if (:seon.error/at results) results
        (let [red? #(and (not (:seon.error/at %))
                         (or (pos? (:seon.test/fail-count %)) (pos? (:seon.test/error-count %))))
              reds (filterv red? results)
              current (when (seq reds) (test.runner/reach-digests database (mapv :seon.test/sym reds)))]
          (into (filterv :seon.error/at results)
                (filter #(or (:seon.error/at current)
                             (= (:seon.test.member/reach-digest %) (get current (:seon.test/sym %)))))
                reds)))))

;;; ---------------------------------------------------------------------------
;;; The one derivation
;;; ---------------------------------------------------------------------------

(declare ai-prose html-report
         missing-model-ai missing-model-html
         stale-var-ai stale-var-html)

(def families
  "Each family's key and the Var deriving it from one database value, in the
  order `problems` lists them. A family's derivation reads only the database
  (and, for stale Vars, the loaded namespaces), so a reader may retain each by
  what it reads (`seon.health`)."
  [[:seon.problems/error-signatures #'error-signatures]
   [:seon.problems/failed-tests #'failed-tests]
   [:seon.problems/errored-receipts #'errored-receipts]
   [:seon.problems/deferred-agents #'deferred-agents]
   [:seon.problems/unowned-namespaces #'unstewarded-namespaces]
   [:seon.problems/stale-vars #'stale-vars]
   [:seon.problems/missing-models #'missing-models]])

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
  same value.

  `through` derives each family, `(through family derivation)`, where
  `derivation` takes the database: the default calls it; the health report retains each
  family by the revisions of the attributes it read."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.problems/request]
                   [:or :seon.problems/problems :seon.test/execution-error :seon.db/invalid-read-error]]
                  [:=> [:cat :seon.db/database-value :seon.problems/request [:fn clojure.core/ifn?]]
                   [:or :seon.problems/problems :seon.test/execution-error :seon.db/invalid-read-error]]]}
  ([db request] (problems db request (fn [_family derivation] (derivation db))))
  ([_db _request through]
   (let [derived (into {} (map (fn [[family derivation]] [family (through family derivation)])) families)
         tests (:seon.problems/failed-tests derived)
         ;; Unknown test evidence makes its own family unknown, never the rest.
         [unknown failed-tests] (if (:seon.error/at tests) [[tests] []]
                                    (mapv vec ((juxt filter remove) :seon.error/at tests)))
         ;; Any other family's refusal is the answer: a partial map would read as healthy families.
         refused (some (fn [[family rows]] (when (and (not= family :seon.problems/failed-tests) (map? rows)) rows))
                       derived)]
     (or refused
      (into (cond-> {}
             (seq unknown) (assoc :seon.problems/failed-tests-unknown unknown)
             (seq failed-tests) (assoc :seon.problems/failed-tests failed-tests))
            (keep (fn [[family rows]] (when (and (not= family :seon.problems/failed-tests) (seq rows)) [family rows])))
            derived)))))

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

(defn- resolution-text
  "Why a fault reads resolved, so a reader can check a false resolution; nil while open."
  {:malli/schema [:=> [:cat [:maybe :seon.error/resolution]] [:maybe :string]]}
  [{:seon.error/keys [digest-then digest-now resolved-tx producer] :as resolution}]
  ;; `seon.error/resolution` names the definition by `:seon.error/producer`
  ;; since 7781d115c; `:seon.instrument/fn` is only the refuser, never here.
  (cond digest-now (str "by change to " producer " (" (subs digest-then 0 8) "→" (subs digest-now 0 8) ")")
        resolution (str "by resolve! in transaction " resolved-tx)))

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
       "problem" "no registry entity"))

(defn missing-model-ai
  "Steering for a configured model absent from the registry."
  {:malli/schema [:=> [:cat :seon.problems/missing-model] :string]}
  [entry]
  (str "Configured model " (:seon.config.ai/model entry)
       " has no :seon.ai.model/id registry row. "
       "Add its descriptor; provider calls continue unchanged."))

(defn html-report
  "`:seon.render/html` — everything wrong now, as a surface.

  THE SECOND PROJECTION OF ONE VALUE, and deliberately not a second
  derivation: `problems` already answered what is wrong and `ai-prose`
  says it as steering; this says it in hiccup. Adding it was one key on the value and one function, with no
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

  A healthy cluster renders an empty report: the health page states the
  health, so no family needs a cheerful empty state."
  {:malli/schema [:function
                  [:=> [:cat :seon.problems/problems] :seon.render/hiccup]
                  [:=> [:cat :seon.problems/problems [:maybe :seon.db/database-value]] :seon.render/hiccup]]}
  ([found] (html-report found nil))
  ([found database]
  [:div {:class "seon-problems"}
   (family-section "failed tests"
     (for [entry (:seon.problems/failed-tests found)]
       [:li (test-render/render-html (cond-> {:seon.render/value entry}
                                        database (assoc :seon.db/db database)))]))
   (family-section "test evidence unknown"
     (for [entry (:seon.problems/failed-tests-unknown found)]
       (row "error" (:seon.error/message entry)
            "offending" (render.value/core-text (:seon.error/offending entry)))))
   (family-section
    "errors"
    (for [entry (:seon.problems/error-signatures found)]
      (row "seen" (:seon.problems/occurrences entry)
           "signature" (:seon.error/signature entry)
           "latest" (:seon.error/message (:seon.error/fact entry))
           "resolved" (resolution-text (:seon.error/resolution entry)))))

   (family-section
    "errored forms"
    (for [entry (:seon.problems/errored-receipts found)]
      (row "run" (:seon.turn/id entry)
           "form" (:seon.cluster.eval/ordinal entry)
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
    (map missing-model-html (:seon.problems/missing-models found)))]))

(defn ai-prose
  "`:seon.render/ai` — concise steering, derived from the families.
  Failed plan forms keep their lines; a deferred agent gains the
  episode-cap line (F1 §7), present exactly while the derivation says
  so and gone the moment an outside trigger resets it.

  BOUNDED ONCE, BY THE VALUE RENDERER. Each family's lines stay under the
  family's own key and index, so the one presentation bound
  (`seon.render.value/render-ai` under the request's render profile) keeps
  whole lines and its elision names the omitted count, the family path and
  the requery form over `problems`. 649 failed tests were 313,589 characters
  unbounded (default, 2026-09-24)."
  {:malli/schema [:=> [:cat :seon.problems/problems] :string]}
  [found]
  (let [lines
        {:seon.problems/failed-tests
         (for [entry (:seon.problems/failed-tests found)]
           (str "Test " (:seon.test/sym entry) "\n" (test/failure-message entry)))
         :seon.problems/failed-tests-unknown
         (for [entry (:seon.problems/failed-tests-unknown found)]
           (select-keys entry [:seon.error/message :seon.error/offending]))
         :seon.problems/errored-receipts
         (for [entry (:seon.problems/errored-receipts found)]
           (str "Form " (:seon.cluster.eval/ordinal entry)
                " failed during evaluation: "
                (:seon.cluster.eval/error entry)
                ". The run did not retry it. Inspect receipt "
                (:seon.cluster.eval/id entry)
                " and revise the remaining plan from current facts."))
         :seon.problems/deferred-agents
         (for [entry (:seon.problems/deferred-agents found)]
           (str "Agent " (:seon.agent/id entry) " has run "
                (:seon.turn.work/episode-runs entry)
                " self-triggered runs since the last outside trigger; "
                (:seon.problems/deferred-count entry)
                " triggers are deferred until one arrives."))
         :seon.problems/stale-vars (map stale-var-ai (:seon.problems/stale-vars found))
         :seon.problems/missing-models (map missing-model-ai (:seon.problems/missing-models found))}
        profile (or (:seon.render/profile found)
                    (render/agent-render-profile config/defaults))]
    (render.value/render-ai
     {:seon.render/value (into {} (keep (fn [[family rows]] (when (seq rows) [family (vec rows)]))) lines)
      :seon.render.value/root `ai-prose
      :seon.render/profile (assoc profile :seon.print/requery-id '(seon.problems/problems (seon.db/db) {}))})))
