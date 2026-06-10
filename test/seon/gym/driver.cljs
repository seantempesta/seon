(ns seon.gym.driver
  "AGENT-GYM scenario harness — PRD §7 item 12 (the testing methodology).

   Scenarios are EDN DATA: a question (or several), optional fixtures
   (tx-data seeded before the run), and PASS-PREDICATES — datalog
   queries against the post-run store plus transcript checks — that a
   driver evaluates MECHANICALLY. Section-by-section context iteration
   becomes QUANTIFIED: every defect class from live runs 3–7 is encoded
   as a permanent regression predicate, and the scorecard is keyed
   (scenario × git sha) so a context/prompt change shows up as a moved
   number, not an anecdote.

   Isolation: every run boots a FRESH agent on a SCRATCH `:memory` conn
   via `seon.client/open-agent-conn!` (the tests' isolated path) — the
   live cluster store is untouchable by construction. The root
   `seon.db/*conn*` is swapped for the duration of the run and restored
   in a `finally`; schema-registry keys minted during the run (by
   fixtures OR by the agent's own `register!` evals) are removed after.

   Budget tiers:
     :stub     — FREE. The scenario scripts the LLM responses
                 (`:seon.gym.turn/llm-script`, one text per turn) and
                 the driver drives ONE `run-turn!` per script entry —
                 deliberately NOT the trigger-driven loop, because the
                 stub self-wake bug (PRD §4) burns trigger-driven stub
                 loops to the turn cap. Deterministic plumbing checks.
     :deepseek — costs real money. The driver wires
                 `seon.ai.deepseek/agent-adapter` through
                 `run-agentic-loop!` (awaits the loop's own
                 termination = idle), but REFUSES to run unless the
                 caller passes `:seon.gym/allow-paid? true` AND
                 DEEPSEEK_API_KEY is set. Behavioral scenarios.

   Rubric axes (the vocabulary every predicate tags itself with):
     sees-question · searches-first · models-work-directed ·
     reuses-schemas · consults-findings · reuses-functions ·
     writes-tests · replies-honestly · terminates · stores-proactively

   Run a scenario from a REPL:

     (require 'seon.gym.driver)
     (-> (seon.gym.driver/load-scenarios!
           {:seon.gym/path \"test/seon/gym/scenarios/envelope-honesty.edn\"})
         :seon.gym/scenarios first
         (as-> s (seon.gym.driver/run-scenario! {:seon.gym/scenario s}))
         (.then seon.gym.driver/print-scorecard!))"
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.ai.deepseek :as deepseek]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.schema :as schema]))

;; ===========================================================================
;; Schemas — scenario, predicate, result, scorecard. Registered once,
;; referenced everywhere (shared-shape rule). Keyword namespaces are
;; multi-segment data namespaces under :seon.gym.* (same convention as
;; the taught :kb.finding/*).
;; ===========================================================================

;; --- rubric -----------------------------------------------------------------
(schema/register! :seon.gym.axis/name
  [:enum :sees-question :searches-first :models-work-directed
   :reuses-schemas :consults-findings :reuses-functions
   :writes-tests :replies-honestly :terminates :stores-proactively])

;; --- turns ------------------------------------------------------------------
(schema/register! :seon.gym.turn/message :string)
;; One scripted LLM response text per driven turn. Stub tier only.
(schema/register! :seon.gym.turn/llm-script [:vector :string])
(schema/register! :seon.gym/turn
  [:map
   [:seon.gym.turn/message :seon.gym.turn/message]
   [:seon.gym.turn/llm-script {:optional true} :seon.gym.turn/llm-script]])

;; --- predicates ---------------------------------------------------------------
(schema/register! :seon.gym.predicate/id   :keyword)
(schema/register! :seon.gym.predicate/kind
  [:enum :datalog :transcript-includes :transcript-excludes
   :first-eval-matches])
(schema/register! :seon.gym.predicate/axis :seon.gym.axis/name)
;; Datalog query/args are datahike's domain — third-party boundary,
;; :any allowed (same stance as :seon.db/query-request).
(schema/register! :seon.gym.predicate/query [:vector :any])
(schema/register! :seon.gym.predicate/args  [:vector :any])
(schema/register! :seon.gym.predicate/expect
  [:or
   [:enum :non-empty :empty]
   [:tuple [:= :count] :int]
   [:tuple [:= :some-includes] :string]])
(schema/register! :seon.gym.predicate/text    :string)
(schema/register! :seon.gym.predicate/pattern :string)
(schema/register! :seon.gym/predicate
  [:map
   [:seon.gym.predicate/id      :seon.gym.predicate/id]
   [:seon.gym.predicate/kind    :seon.gym.predicate/kind]
   [:seon.gym.predicate/axis    :seon.gym.predicate/axis]
   [:seon.gym.predicate/query   {:optional true} :seon.gym.predicate/query]
   [:seon.gym.predicate/args    {:optional true} :seon.gym.predicate/args]
   [:seon.gym.predicate/expect  {:optional true} :seon.gym.predicate/expect]
   [:seon.gym.predicate/text    {:optional true} :seon.gym.predicate/text]
   [:seon.gym.predicate/pattern {:optional true} :seon.gym.predicate/pattern]])

;; --- scenario -----------------------------------------------------------------
(schema/register! :seon.gym.scenario/id     :keyword)
(schema/register! :seon.gym.scenario/doc    :string)
(schema/register! :seon.gym.scenario/tier   [:enum :stub :deepseek])
(schema/register! :seon.gym.scenario/status [:enum :active :todo])
(schema/register! :seon.gym.scenario/axes   [:vector :seon.gym.axis/name])
;; A Malli schema FORM is malli's open domain — third-party boundary.
(schema/register! :seon.gym/malli-form [:or :keyword [:vector :any]])
(schema/register! :seon.gym.scenario/schema-registrations
  [:vector [:tuple :keyword :seon.gym/malli-form]])
(schema/register! :seon.gym.scenario/fixtures :seon.db/tx-data)
(schema/register! :seon.gym.scenario/turns [:vector :seon.gym/turn])
(schema/register! :seon.gym.scenario/predicates
  [:vector :seon.gym/predicate])
(schema/register! :seon.gym/scenario
  [:map
   [:seon.gym.scenario/id     :seon.gym.scenario/id]
   [:seon.gym.scenario/doc    :seon.gym.scenario/doc]
   [:seon.gym.scenario/tier   :seon.gym.scenario/tier]
   [:seon.gym.scenario/status :seon.gym.scenario/status]
   [:seon.gym.scenario/axes   :seon.gym.scenario/axes]
   [:seon.gym.scenario/schema-registrations {:optional true}
    :seon.gym.scenario/schema-registrations]
   [:seon.gym.scenario/fixtures {:optional true} :seon.gym.scenario/fixtures]
   [:seon.gym.scenario/turns      :seon.gym.scenario/turns]
   [:seon.gym.scenario/predicates :seon.gym.scenario/predicates]])

;; --- results + scorecard --------------------------------------------------------
(schema/register! :seon.gym.result/pass?  :boolean)
(schema/register! :seon.gym.result/actual :string)
(schema/register! :seon.gym/result
  [:map
   [:seon.gym.predicate/id   :seon.gym.predicate/id]
   [:seon.gym.predicate/axis :seon.gym.predicate/axis]
   [:seon.gym.result/pass?   :seon.gym.result/pass?]
   [:seon.gym.result/actual  :seon.gym.result/actual]])
(schema/register! :seon.gym.scorecard/scenario :keyword)
(schema/register! :seon.gym.scorecard/git-sha  :string)
(schema/register! :seon.gym.scorecard/tier     :seon.gym.scenario/tier)
(schema/register! :seon.gym.scorecard/at       :inst)
(schema/register! :seon.gym.scorecard/agent-id :seon.db/id)
(schema/register! :seon.gym.scorecard/pass?    :boolean)
(schema/register! :seon.gym.scorecard/axes
  [:map-of :seon.gym.axis/name :boolean])
(schema/register! :seon.gym.scorecard/results
  [:vector :seon.gym/result])
(schema/register! :seon.gym/scorecard
  [:map
   [:seon.gym.scorecard/scenario :seon.gym.scorecard/scenario]
   [:seon.gym.scorecard/git-sha  :seon.gym.scorecard/git-sha]
   [:seon.gym.scorecard/tier     :seon.gym.scorecard/tier]
   [:seon.gym.scorecard/at       :seon.gym.scorecard/at]
   [:seon.gym.scorecard/agent-id :seon.gym.scorecard/agent-id]
   [:seon.gym.scorecard/pass?    :seon.gym.scorecard/pass?]
   [:seon.gym.scorecard/axes     :seon.gym.scorecard/axes]
   [:seon.gym.scorecard/results  :seon.gym.scorecard/results]])

;; --- request/response shapes ------------------------------------------------
(schema/register! :seon.gym/path :string)
(schema/register! :seon.gym/scenarios [:vector :seon.gym/scenario])
(schema/register! :seon.gym/load-request  [:map [:seon.gym/path :seon.gym/path]])
(schema/register! :seon.gym/load-response [:map [:seon.gym/scenarios :seon.gym/scenarios]])
(schema/register! :seon.gym/allow-paid? :boolean)
(schema/register! :seon.gym/ok? :boolean)
(schema/register! :seon.gym/error :string)
(schema/register! :seon.gym/refusal
  [:map [:seon.gym/ok? [:= false]] [:seon.gym/error :seon.gym/error]])
(schema/register! :seon.gym/run-request
  [:map
   [:seon.gym/scenario :seon.gym/scenario]
   [:seon.gym/allow-paid? {:optional true} :seon.gym/allow-paid?]])
(schema/register! :seon.gym/run-response
  [:or :seon.gym/scorecard :seon.gym/refusal])

;; ===========================================================================
;; Scenario loading
;; ===========================================================================

(defn load-scenarios!
  "Read one scenario EDN file (a single scenario map OR a vector of
   them) and validate every scenario against `:seon.gym/scenario`.
   Invalid EDN fails LOUD with the Malli explain — a scenario that
   doesn't parse must never silently score."
  {:malli/schema [:=> [:cat :seon.gym/load-request] :seon.gym/load-response]}
  [{path :seon.gym/path}]
  (let [fs        (js/require "node:fs")
        data      (reader/read-string (.readFileSync fs path "utf8"))
        scenarios (if (map? data) [data] (vec data))]
    (doseq [s scenarios]
      (when-not (m/validate :seon.gym/scenario s)
        (throw (ex-info (str "gym: invalid scenario EDN — " path)
                        {:seon.gym/path    path
                         :seon.gym/explain (pr-str (m/explain :seon.gym/scenario s))}))))
    {:seon.gym/scenarios scenarios}))

;; ===========================================================================
;; Predicate evaluation — mechanical, no judgment calls.
;; ===========================================================================

(defn- truncate-actual [s]
  (let [s (str s)]
    (if (> (count s) 500) (str (subs s 0 500) " …(truncated)") s)))

(defn- expect-pass?
  "Does the datalog result set satisfy the predicate's `:expect`?"
  [expect rows]
  (cond
    (= :non-empty expect) (boolean (seq rows))
    (= :empty expect)     (empty? rows)
    (and (vector? expect) (= :count (first expect)))
    (= (second expect) (count rows))
    (and (vector? expect) (= :some-includes (first expect)))
    (boolean (some (fn [row]
                     (some #(str/includes? (str %) (second expect)) row))
                   rows))
    :else false))

(defn- first-eval-source
  "Source text of the run's chronologically FIRST eval (by :seon.eval/at),
   or nil when no eval ran. The scratch conn holds exactly one agent, so
   no agent filter is needed."
  [dbv]
  (->> (db/query {:seon.db/query '[:find ?at ?src
                                   :where
                                   [?e :seon.eval/at ?at]
                                   [?e :seon.eval/source ?src]]
                  :seon.db/db dbv})
       (sort-by #(.getTime ^js (first %)))
       first
       second))

(defn- eval-predicate
  "Evaluate ONE predicate against the post-run db value + rendered
   transcript. Returns a `:seon.gym/result` map — pass/fail plus the
   ACTUAL observation (so a failing scorecard explains itself)."
  [dbv transcript {:seon.gym.predicate/keys [id kind axis query args expect
                                             text pattern]}]
  (let [[pass? actual]
        (case kind
          :datalog
          (let [rows (vec (db/query (cond-> {:seon.db/query query
                                             :seon.db/db    dbv}
                                      args (assoc :seon.db/args args))))]
            [(expect-pass? expect rows)
             (str "rows=" (pr-str rows) " expect=" (pr-str expect))])

          :transcript-includes
          [(str/includes? transcript text)
           (str "transcript " (count transcript) " chars; looked for " (pr-str text))]

          :transcript-excludes
          [(not (str/includes? transcript text))
           (str "transcript " (count transcript) " chars; must NOT contain " (pr-str text))]

          :first-eval-matches
          (let [src (first-eval-source dbv)]
            [(boolean (and src (re-find (js/RegExp. pattern) src)))
             (str "first eval source: " (pr-str src))]))]
    {:seon.gym.predicate/id   id
     :seon.gym.predicate/axis axis
     :seon.gym.result/pass?   (boolean pass?)
     :seon.gym.result/actual  (truncate-actual actual)}))

(defn- axes-rollup
  "Per-axis verdict: an axis passes iff EVERY predicate tagged with it
   passes. Axes declared on the scenario but exercised by no predicate
   report true (vacuous — the scenario doc should say why)."
  [axes results]
  (into {}
        (map (fn [axis]
               [axis (every? :seon.gym.result/pass?
                             (filter #(= axis (:seon.gym.predicate/axis %))
                                     results))]))
        axes))

;; ===========================================================================
;; The run
;; ===========================================================================

(defn- git-sha []
  (try
    (let [cp (js/require "node:child_process")]
      (str/trim (str (.execSync cp "git rev-parse --short HEAD"))))
    (catch :default _ "unknown")))

(defn- scripted-llm
  "Stub-tier llm-fn: resolves with exactly the scripted response text.
   One scripted text = one driven turn (see ns docstring on why the
   driver does NOT use the trigger loop for stubs)."
  [text]
  (fn [_ctx] (js/Promise.resolve {:text text})))

(defn ^:async ^:private send-user-message!
  "Land the scenario question as a real user message (the same
   `message!` entry point POST /chat uses). Returns the message id —
   the turn's `:seon.turn/woken-by` anchor. Fails loud on a non-ok
   envelope: a scenario whose question never landed must not score."
  [agent-id text]
  (let [env (await (agent/message!
                     {:seon.message/content text
                      :seon.message/from    agent/user-ref
                      :seon.message/to      [[:seon.agent/id agent-id]]}))]
    (when-not (:seon.message/ok? env)
      (throw (ex-info "gym: user message! failed" env)))
    (:seon.message/id env)))

(defn ^:async ^:private drive-stub-turns!
  "Drive one `run-turn!` per scripted LLM response — woken by `mid`."
  [agent-id compile-state mid scripts]
  (loop [scripts scripts]
    (when-let [[text & more] (seq scripts)]
      (await (db/with-agent agent-id
               (fn []
                 (agent/run-turn!
                   {:seon.agent/id            agent-id
                    :seon.agent/llm-fn        (scripted-llm text)
                    :seon.agent/compile-state compile-state
                    :seon.turn/woken-by       [:seon.message/id mid]}))))
      (recur more))))

(defn ^:async ^:private drive-deepseek-loop!
  "Behavioral tier: the REAL adapter through `run-agentic-loop!`. The
   loop's own stop policies (zero forms / error / cap) are the
   awaits-idle signal — when the promise resolves, the agent is idle."
  [agent-id compile-state mid]
  (await (db/with-agent agent-id
           (fn []
             (agent/run-agentic-loop!
               {:seon.agent/id            agent-id
                :seon.agent/llm-fn        (deepseek/agent-adapter)
                :seon.agent/compile-state compile-state
                :seon.turn/woken-by       [:seon.message/id mid]})))))

(defn ^:async run-scenario!
  "Run ONE scenario end-to-end on a scratch `:memory` conn and return a
   Promise of the scorecard (or a refusal map — errors are values):

     - :todo scenarios refuse (encoded intent, not yet runnable).
     - :deepseek scenarios refuse unless `:seon.gym/allow-paid? true`
       AND DEEPSEEK_API_KEY is set — the suite must never burn money.

   Pipeline: open scratch conn → swap the root `seon.db/*conn*`
   (restored in finally) → ensure bootstrap compile-state → seed the
   user entity + scenario registrations/fixtures → create the agent →
   per gym-turn: land the user message, drive turns per tier →
   evaluate every predicate against the post-run db + transcript →
   validated scorecard keyed (scenario × git sha)."
  {:malli/schema [:=> [:cat :seon.gym/run-request] :seon.gym/run-response]}
  [{scenario    :seon.gym/scenario
    allow-paid? :seon.gym/allow-paid?}]
  (let [{:seon.gym.scenario/keys [id tier status axes schema-registrations
                                  fixtures turns predicates]} scenario]
    (cond
      (= :todo status)
      {:seon.gym/ok? false
       :seon.gym/error (str "scenario " id " is :todo — encoded intent, "
                            "not yet runnable (see its :doc)")}

      (and (= :deepseek tier)
           (not (and allow-paid?
                     (.. js/process -env -DEEPSEEK_API_KEY))))
      {:seon.gym/ok? false
       :seon.gym/error (str "scenario " id " is :deepseek tier — costs real "
                            "money. Pass {:seon.gym/allow-paid? true} with "
                            "DEEPSEEK_API_KEY set to run it.")}

      :else
      (let [prev-conn    db/*conn*
            keys-before  (schema/current-keys)]
        (try
          (let [conn          (await (client/open-agent-conn!))
                _             (set! db/*conn* conn)
                compile-state (await (repl/ensure-bootstrap!))
                agent-id      (db/new-id!)]
            ;; Scenario-declared schema registrations (for fixtures that
            ;; use non-substrate attrs), then the user entity + fixtures.
            (doseq [[k v] schema-registrations] (schema/register! k v))
            (let [env (await (db/transact!
                               {:seon.db/tx-data
                                (into [{:seon.user/id "user"}] fixtures)}))]
              (when-not (:seon.db/ok? env)
                (throw (ex-info "gym: fixture transact failed" env))))
            (await (seval/setup-agent-ns! compile-state
                                          (agent/home-ns agent-id)
                                          agent-id))
            (await (agent/create! {:seon.agent/id agent-id}))
            ;; Drive every gym turn.
            (doseq [{:seon.gym.turn/keys [message llm-script]} turns]
              (let [mid (await (send-user-message! agent-id message))]
                (if (= :stub tier)
                  (await (drive-stub-turns! agent-id compile-state mid
                                            llm-script))
                  (await (drive-deepseek-loop! agent-id compile-state mid)))))
            ;; Mechanical scoring against the post-run store + transcript.
            (let [dbv        @conn
                  transcript (agent/transcript-section
                               {:seon.db/db dbv :seon.agent/id agent-id})
                  results    (mapv #(eval-predicate dbv transcript %)
                                   predicates)
                  card       {:seon.gym.scorecard/scenario id
                              :seon.gym.scorecard/git-sha  (git-sha)
                              :seon.gym.scorecard/tier     tier
                              :seon.gym.scorecard/at       (js/Date.)
                              :seon.gym.scorecard/agent-id agent-id
                              :seon.gym.scorecard/pass?
                              (every? :seon.gym.result/pass? results)
                              :seon.gym.scorecard/axes
                              (axes-rollup axes results)
                              :seon.gym.scorecard/results  results}]
              (when-not (m/validate :seon.gym/scorecard card)
                (throw (ex-info "gym: emitted scorecard fails its own schema"
                                {:seon.gym/explain
                                 (pr-str (m/explain :seon.gym/scorecard card))})))
              card))
          (finally
            ;; Restore the root conn + drop every schema key minted during
            ;; the run (scenario registrations AND agent-eval register!s) so
            ;; one scenario can't leak shapes into the next.
            (set! db/*conn* prev-conn)
            (let [minted (remove keys-before (schema/current-keys))]
              (when (seq minted)
                (swap! schema/*schemas #(apply dissoc % minted))))))))))

(defn print-scorecard!
  "Print the scorecard as one greppable line (`bin/gym` surfaces these
   from the suite output) and return it unchanged."
  {:malli/schema [:=> [:cat :seon.gym/scorecard] :seon.gym/scorecard]}
  [card]
  (println "SEON-GYM SCORECARD" (pr-str card))
  card)
