(ns seon.gym.driver-test
  "Tests for the AGENT-GYM driver itself (PRD §7 item 12).

   The stub-tier scenarios run END-TO-END here — real scratch `:memory`
   conn, real bootstrap compile-state, real eval pipeline — so the gym
   regression predicates (envelope honesty, blank-message refusal,
   finding storage shape) execute on every `bin/test-cljs`.

   Also pins the harness's own honesty: a deliberately-broken predicate
   must produce a FAILING scorecard that names the failing predicate and
   carries the actual observation — a gym that can't fail is worthless.

   Deepseek-tier scenarios are validated (shape + refusal guard) but
   NEVER run here — they cost real money and need
   {:seon.gym/allow-paid? true} + DEEPSEEK_API_KEY."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.agent.ctx.namespaces :as ctx-namespaces]
    [seon.db :as db]
    [seon.gym.driver :as gym]
    [seon.schema :as schema]
    [seon.warn :as warn]))

(def ^:private scenario-files
  ["test/seon/gym/scenarios/s01-stub-pipeline-smoke.edn"
   "test/seon/gym/scenarios/envelope-honesty.edn"
   "test/seon/gym/scenarios/blank-message-refusal.edn"
   "test/seon/gym/scenarios/finding-storage-shape.edn"
   "test/seon/gym/scenarios/consults-findings-run8.edn"
   "test/seon/gym/scenarios/s32-consult-before-research.edn"
   "test/seon/gym/scenarios/s21-log-workout-existing-schema.edn"
   "test/seon/gym/scenarios/todo-prompt-thin.edn"
   "test/seon/gym/scenarios/todo-multistep-tracking.edn"
   "test/seon/gym/scenarios/x1-subscriptions-total-and-max.edn"
   "test/seon/gym/scenarios/x3-expense-reuse-and-category-total.edn"
   "test/seon/gym/scenarios/x12-narrow-question-no-over-retrieval.edn"])

(defn- load-first [path]
  (first (:seon.gym/scenarios (gym/load-scenarios! {:seon.gym/path path}))))

;; ---------------------------------------------------------------------------
;; Every scenario file loads and validates against :seon.gym/scenario.
;; ---------------------------------------------------------------------------

(deftest all-scenario-files-load-and-validate
  (doseq [path scenario-files]
    (let [{:seon.gym/keys [scenarios]} (gym/load-scenarios! {:seon.gym/path path})]
      (is (seq scenarios) (str path " contains at least one scenario"))
      (doseq [s scenarios]
        (is (m/validate :seon.gym/scenario s)
            (str path " — " (:seon.gym.scenario/id s) " validates"))))))

(deftest rubric-axes-are-the-prd-vocabulary
  ;; The §7 item-12 rubric, verbatim — agent BEHAVIOR only. A predicate
  ;; tagged outside this vocabulary must fail schema validation at load
  ;; time. (:context-fidelity was REMOVED with the structural gates,
  ;; user r2 2026-06-11 — the gym tests the agent, not the layout.)
  (is (m/validate :seon.gym.axis/name :consults-findings))
  (is (m/validate :seon.gym.axis/name :stores-proactively))
  (is (not (m/validate :seon.gym.axis/name :context-fidelity)))
  (is (not (m/validate :seon.gym.axis/name :made-up-axis))))

;; ---------------------------------------------------------------------------
;; Stub-tier scenarios run end-to-end on scratch :memory conns.
;; ---------------------------------------------------------------------------

(defn- run-and-expect-pass! [path done]
  (-> (gym/run-scenario!
        {:seon.gym/scenario (load-first path)})
      (.then (fn [card]
               (gym/print-scorecard! card)
               (is (m/validate :seon.gym/scorecard card)
                   "emitted scorecard validates")
               (is (:seon.gym.scorecard/pass? card)
                   (str path " passes — failing results: "
                        (pr-str (filterv (complement :seon.gym.result/pass?)
                                         (:seon.gym.scorecard/results card)))))
               (is (every? true? (vals (:seon.gym.scorecard/axes card)))
                   "every rubric axis rolls up true")
               (done)))
      (.catch (fn [e] (is false (str path " threw — " e)) (done)))))

(deftest s01-stub-pipeline-smoke-scenario-passes
  ;; S-01 drives the REAL seon.agent.loop/run-loop! via the
  ;; :scripted-replay llm injection — message → run → done turn → idle.
  (async done
    (run-and-expect-pass!
      "test/seon/gym/scenarios/s01-stub-pipeline-smoke.edn" done)))

(deftest envelope-honesty-scenario-passes
  (async done
    (run-and-expect-pass! "test/seon/gym/scenarios/envelope-honesty.edn" done)))

(deftest blank-message-refusal-scenario-passes
  (async done
    (run-and-expect-pass! "test/seon/gym/scenarios/blank-message-refusal.edn" done)))

(deftest finding-storage-shape-scenario-passes
  (async done
    (run-and-expect-pass! "test/seon/gym/scenarios/finding-storage-shape.edn" done)))

;; ---------------------------------------------------------------------------
;; HONESTY — the scorecard must report failures, not paper over them.
;; A deliberately-broken predicate (expects the bogus datoms that the
;; envelope contract guarantees never land) must FAIL the scorecard and
;; name itself with the actual observation.
;; ---------------------------------------------------------------------------

(deftest broken-predicate-fails-the-scorecard-honestly
  (async done
    (let [scenario (-> (load-first "test/seon/gym/scenarios/envelope-honesty.edn")
                       (update :seon.gym.scenario/predicates conj
                               {:seon.gym.predicate/id     :deliberately-broken
                                :seon.gym.predicate/kind   :datalog
                                :seon.gym.predicate/axis   :replies-honestly
                                :seon.gym.predicate/query  '[:find ?e
                                                             :where
                                                             [?e :gymtest.bogus/attr ?v]]
                                :seon.gym.predicate/expect :non-empty}))]
      (-> (gym/run-scenario! {:seon.gym/scenario scenario})
          (.then (fn [card]
                   (gym/print-scorecard! card)
                   (is (false? (:seon.gym.scorecard/pass? card))
                       "scorecard reports the failure — no false pass")
                   (is (false? (get-in card [:seon.gym.scorecard/axes
                                             :replies-honestly]))
                       "the broken predicate's axis rolls up false")
                   (let [r (->> (:seon.gym.scorecard/results card)
                                (filter #(= :deliberately-broken
                                            (:seon.gym.predicate/id %)))
                                first)]
                     (is (some? r) "the failing predicate is named in results")
                     (is (false? (:seon.gym.result/pass? r)))
                     (is (seq (:seon.gym.result/actual r))
                         "the failing result carries the actual observation"))
                   ;; the OTHER predicates still pass — one bad predicate
                   ;; doesn't poison the rest of the mechanical evaluation.
                   (is (every? :seon.gym.result/pass?
                               (remove #(= :deliberately-broken
                                           (:seon.gym.predicate/id %))
                                       (:seon.gym.scorecard/results card))))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; WORLD-PARITY — the gym scratch world must be THE WORLD A POD BOOTS
;; INTO (entity-schema decomposition + instruction rows + core
;; index, under :core-seed provenance) with the scenario's
;; prior-agent layer on top (tee-shaped :seon.schema rows + fixtures,
;; agent provenance). Iteration 1 ran without the decomposition /
;; index-schemas, so gym prompts differed from real pod prompts and the
;; S-32 catalog bug hid for a whole sweep.
;; ---------------------------------------------------------------------------

(deftest seeded-world-matches-a-pod-boot-and-surfaces-domain-attrs
  (async done
    (let [keys-before (schema/current-keys)
          scenario    (load-first
                        "test/seon/gym/scenarios/s21-log-workout-existing-schema.edn")]
      (-> (client/open-agent-conn!)
          (.then (fn [conn]
                   (-> (gym/seed-scenario-world!
                         {:seon.db/conn conn :seon.gym/scenario scenario})
                       (.then (fn [_]
                                (let [db        @conn
                                      domain    (set (warn/domain-attrs
                                                       {:seon.db/db db}))
                                      inventory (db/store-inventory
                                                  {:seon.db/db db})
                                      kinds     (set (map :seon.db/kind
                                                          (:seon.db/kinds
                                                            inventory)))]
                                  ;; the seeded reuse surface IS domain attrs
                                  ;; (S-21 production-bug pin: agent DATA
                                  ;; domains discriminate by PROVENANCE,
                                  ;; never by keyword-namespace blankets;
                                  ;; fixture domain renamed :seon.workout →
                                  ;; :my.workout 2026-06-11 — agent data is
                                  ;; my.*)
                                  (is (contains? domain :my.workout/date))
                                  (is (contains? domain
                                                 :my.workout/duration-minutes))
                                  ;; core attrs stay OUT of the reuse
                                  ;; surface (seed provenance)
                                  (is (not-any? #(= "seon.db" (namespace %))
                                                domain)
                                      "no :seon.db/* leaks into domain attrs")
                                  (is (not (contains? domain :seon.agent/id)))
                                  ;; …and the agent-facing consult surface
                                  ;; (context-v4: the catalogs died; the
                                  ;; store-inventory eval is the consult
                                  ;; surface) renders kinds for this world.
                                  ;; Re-pinned for A3's datom-derived
                                  ;; inventory: kinds = attr namespaces
                                  ;; WITH ROWS. The seeded world has no
                                  ;; :seon.agent datoms (boot-seed! only,
                                  ;; no start-agent!) — the fixture's
                                  ;; identity-less :my.workout being
                                  ;; VISIBLE here is the S-21 win this
                                  ;; rework exists for.
                                  (is (seq kinds)
                                      "store-inventory returns kinds for the seeded world")
                                  (is (contains? kinds :my.workout)
                                      "identity-less seeded domain kinds are inventoried")))))))
          (.then (fn [_]
                   (let [minted (remove keys-before (schema/current-keys))]
                     (when (seq minted)
                       (swap! @#'schema/*schemas #(apply dissoc % minted))))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; WORLD-PARITY (deep audit 2026-06-10) — the seeded world must carry the
;; SAME row-classes a live pod boot produces, derived from THE SAME
;; sources of truth the boot indexers use (so a future boot change moves
;; this test, not just live behavior):
;;   - every relevant-ns? member of (client/core-ns-set) has a FULL
;;     :seon.ns/source row (the :exemplars 7-block set — gym iteration 2
;;     rendered 4/7 because the :test build never loaded
;;     seon.dev.test-preload and index-tests seeded nothing);
;;   - one :seon.test row per preload-roster deftest var;
;;   - the scenario's prior-agent layer carries agent provenance
;;     (agent-id + non-seed origin — the context-model classifier's
;;     exact predicate).
;; ---------------------------------------------------------------------------

(deftest seeded-world-carries-the-pod-boot-roster-exemplars-and-provenance
  (async done
    (let [keys-before (schema/current-keys)
          scenario    (load-first
                        "test/seon/gym/scenarios/s21-log-workout-existing-schema.edn")]
      (is (pos? (count @client/!indexed-test-vars))
          "the gym build carries the pod's preload deftest roster")
      (-> (client/open-agent-conn!)
          (.then (fn [conn]
                   (-> (gym/seed-scenario-world!
                         {:seon.db/conn conn :seon.gym/scenario scenario})
                       (.then (fn [_]
                                (let [dbv      @conn
                                      expected (->> (client/core-ns-set)
                                                    (map name)
                                                    (filter ctx-namespaces/full-source-ns?)
                                                    set)
                                      full-src (->> (db/query
                                                      {:seon.db/db dbv
                                                       :seon.db/query
                                                       '[:find ?nm ?src
                                                         :where
                                                         [?n :seon.ns/name ?nm]
                                                         [?n :seon.ns/source ?src]]})
                                                    (keep (fn [[nm src]]
                                                            (when (and (ctx-namespaces/full-source-ns? (name nm))
                                                                       (not= (str/trim src)
                                                                             (str "(ns " (name nm) ")")))
                                                              (name nm))))
                                                    set)
                                      test-rows (count (db/query
                                                         {:seon.db/db dbv
                                                          :seon.db/query
                                                          '[:find ?t :where
                                                            [?e :seon.test/sym ?t]]}))
                                      prior     (db/query
                                                  {:seon.db/db dbv
                                                   :seon.db/query
                                                   '[:find ?aid :where
                                                     [?s :seon.schema/key :my.workout/date ?tx]
                                                     [?tx :seon.db/agent-id ?aid]
                                                     (not [?tx :seon.db/origin :core-seed])]})]
                                  (is (= expected full-src)
                                      "every full-source ns the boot indexes carries real file text (my.* only)")
                                  (is (= (count @client/!indexed-test-vars) test-rows)
                                      "one :seon.test row per pod-roster deftest var")
                                  ;; (The carved FSM arms the wake trigger as a
                                  ;; runtime tx-listener at the client boot
                                  ;; path — there is no longer a seeded
                                  ;; :seon.handler/* entity to assert on.)
                                  (is (seq prior)
                                      "the scenario layer carries prior-agent provenance (agent-id + non-seed origin)")))))))
          (.then (fn [_]
                   (let [minted (remove keys-before (schema/current-keys))]
                     (when (seq minted)
                       (swap! @#'schema/*schemas #(apply dissoc % minted))))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; GENERIC FORK DETECTION — the :domain-attrs predicate kind must catch
;; an attr fork in ANY namespace (the old S-21 predicate only checked
;; my.workout, so :fitness.workout/* forks passed vacuously).
;; ---------------------------------------------------------------------------

(deftest domain-attrs-predicate-catches-a-fork-in-any-namespace
  (async done
    (let [scenario
          {:seon.gym.scenario/id     :gymtest-attr-fork-any-namespace
           :seon.gym.scenario/competency :db-memory
           :seon.gym.scenario/doc    "Inline stub: the scripted agent forks the seeded workout shape into a DIFFERENT namespace + unit; the generic :domain-attrs no-fork predicate must fail the scorecard."
           :seon.gym.scenario/tier   :stub
           :seon.gym.scenario/status :active
           :seon.gym.scenario/llm    :scripted-replay
           :seon.gym.scenario/axes   [:reuses-schemas]
           :seon.gym.scenario/schema-registrations
           [[:my.workout/date :string]
            [:my.workout/type :keyword]
            [:my.workout/duration-minutes :int]
            [:my.workout/notes :string]]
           :seon.gym.scenario/fixtures
           [{:my.workout/date "{{today}}"
             :my.workout/type :strength
             :my.workout/duration-minutes 45}]
           :seon.gym.scenario/turns
           [{:seon.gym.turn/message "I ran this morning — 24 minutes."
             :seon.gym.turn/llm-script
             [(str ";; deliberately fork into another namespace + unit\n"
                   "(seon.schema/register! :fitness.workout/duration-seconds :int)\n"
                   "(seon.db/transact! {:seon.db/tx-data "
                   "[{:fitness.workout/duration-seconds 1440}]})\n")]}]
           :seon.gym.scenario/predicates
           [{:seon.gym.predicate/id     :no-attr-fork-anywhere
             :seon.gym.predicate/kind   :domain-attrs
             :seon.gym.predicate/axis   :reuses-schemas
             :seon.gym.predicate/expect [:every-in [":my.workout/date"
                                                    ":my.workout/type"
                                                    ":my.workout/duration-minutes"
                                                    ":my.workout/notes"]]}]}]
      (-> (gym/run-scenario! {:seon.gym/scenario scenario})
          (.then (fn [card]
                   (gym/print-scorecard! card)
                   (is (false? (:seon.gym.scorecard/pass? card))
                       "the fork fails the scorecard")
                   (let [r (->> (:seon.gym.scorecard/results card)
                                (filter #(= :no-attr-fork-anywhere
                                            (:seon.gym.predicate/id %)))
                                first)]
                     (is (false? (:seon.gym.result/pass? r)))
                     (is (str/includes? (:seon.gym.result/actual r)
                                        ":fitness.workout/duration-seconds")
                         "the actual names the forked attr"))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; PROMPT-BLOB PREDICATES (gym-upgrade PRD §2.1 / U1) — the referee's
;; eyes. run-turn! persists every prompt (via seon.debug capture, forced
;; ON for gym runs) to <debug-dir>/<agent>/<turn-idx>-<turn>/prompt.txt;
;; the new kinds read those blobs from the post-run store. Falsification
;; (per the PRD): the question text passes :prompt-every-turn; text NOT
;; in any prompt fails :prompt-includes WITH the blob path in the
;; actual; a missing/unreadable blob scores RED naming the path.
;; ---------------------------------------------------------------------------

(def ^:private prompt-pred-question
  "What is the gym prompt-blob marker question?")

(defn- prompt-blob-scenario []
  {:seon.gym.scenario/id     :gymtest-prompt-blob-predicates
   :seon.gym.scenario/competency :honesty
   :seon.gym.scenario/doc    "Inline stub (gym-upgrade U1 falsification): one scripted turn with a known prompt; :prompt-every-turn on the question text passes, :prompt-includes for absent text fails naming the blob path, :prompt-excludes on absent text passes, :turn index pins/ranges."
   :seon.gym.scenario/tier   :stub
   :seon.gym.scenario/status :active
   :seon.gym.scenario/axes   [:sees-question]
   :seon.gym.scenario/turns
   [{:seon.gym.turn/message prompt-pred-question
     :seon.gym.turn/llm-script
     ["(message/user \"noted\")\n"]}]
   :seon.gym.scenario/predicates
   [{:seon.gym.predicate/id   :question-in-every-prompt
     :seon.gym.predicate/kind :prompt-every-turn
     :seon.gym.predicate/axis :sees-question
     :seon.gym.predicate/text prompt-pred-question}
    {:seon.gym.predicate/id   :absent-text-fails-naming-the-blob
     :seon.gym.predicate/kind :prompt-includes
     :seon.gym.predicate/axis :sees-question
     :seon.gym.predicate/text "GYM-XYZZY-NEVER-IN-ANY-PROMPT"}
    {:seon.gym.predicate/id   :absent-text-excludes-green
     :seon.gym.predicate/kind :prompt-excludes
     :seon.gym.predicate/axis :sees-question
     :seon.gym.predicate/text "GYM-XYZZY-NEVER-IN-ANY-PROMPT"}
    {:seon.gym.predicate/id   :turn-zero-pinned-includes-question
     :seon.gym.predicate/kind :prompt-includes
     :seon.gym.predicate/axis :sees-question
     :seon.gym.predicate/turn 0
     :seon.gym.predicate/text prompt-pred-question}
    {:seon.gym.predicate/id   :turn-index-out-of-range-is-red
     :seon.gym.predicate/kind :prompt-includes
     :seon.gym.predicate/axis :sees-question
     :seon.gym.predicate/turn 5
     :seon.gym.predicate/text prompt-pred-question}]})

(defn- result-by-id [card id]
  (->> (:seon.gym.scorecard/results card)
       (filter #(= id (:seon.gym.predicate/id %)))
       first))

(deftest prompt-blob-predicates-see-what-the-agent-saw
  (async done
    (-> (gym/run-scenario! {:seon.gym/scenario (prompt-blob-scenario)})
        (.then (fn [card]
                 (gym/print-scorecard! card)
                 (is (m/validate :seon.gym/scorecard card)
                     "scorecard with prompt predicates validates")
                 ;; FALSIFICATION 1 — the known prompt carries the
                 ;; question on every turn.
                 (let [r (result-by-id card :question-in-every-prompt)]
                   (is (true? (:seon.gym.result/pass? r))
                       (str ":prompt-every-turn on the question passes — "
                            (:seon.gym.result/actual r))))
                 ;; FALSIFICATION 2 — absent text fails WITH the blob
                 ;; path in the actual.
                 (let [r (result-by-id card :absent-text-fails-naming-the-blob)]
                   (is (false? (:seon.gym.result/pass? r))
                       "text not in any prompt fails :prompt-includes")
                   (is (str/includes? (:seon.gym.result/actual r)
                                      "logs/turns/")
                       (str "the failing actual names the blob path — "
                            (:seon.gym.result/actual r))))
                 ;; :prompt-excludes is the same observation, inverted.
                 (is (true? (:seon.gym.result/pass?
                              (result-by-id card :absent-text-excludes-green))))
                 ;; :turn pinning — index 0 hits the one real turn;
                 ;; index 5 is out of range and must be RED, not vacuous.
                 (is (true? (:seon.gym.result/pass?
                              (result-by-id
                                card :turn-zero-pinned-includes-question))))
                 (let [r (result-by-id card :turn-index-out-of-range-is-red)]
                   (is (false? (:seon.gym.result/pass? r)))
                   (is (str/includes? (:seon.gym.result/actual r)
                                      "out of range")))
                 ;; one deliberately-failing predicate → card fails.
                 (is (false? (:seon.gym.scorecard/pass? card)))
                 ;; §6.6 evidence: the card carries the run's blob paths.
                 (let [pfs (:seon.gym.scorecard/prompt-files card)]
                   (is (seq pfs) "scorecard carries prompt-file evidence")
                   (is (every? #(str/starts-with? % "logs/turns/") pfs)))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest prompt-predicate-missing-blob-scores-red-naming-the-path
  ;; FALSIFICATION 3 — a turn whose :seon.agent.turn/prompt-file points
  ;; at a file that does not exist (seeded as a fixture turn, exactly
  ;; what a lost/unwritten blob looks like post-run) must score RED
  ;; naming the path — NEVER a silent pass, even though the run's REAL
  ;; turn prompt does contain the asserted text.
  (async done
    (let [phantom (str "logs/prompts/gym-missing/" (db/new-id!) ".txt")
          run-id  (db/new-id!)
          scenario
          (-> (prompt-blob-scenario)
              (assoc :seon.gym.scenario/id :gymtest-prompt-blob-missing
                     :seon.gym.scenario/fixtures
                     ;; A seeded CAUSED run + a turn stamped to it — prompt
                     ;; predicates range over caused-run turns only (the
                     ;; bootstrap turn 0's run has no cause and renders no
                     ;; prompt, so it is deliberately out of scope). The
                     ;; cause is the boot-seeded human (:seon.user/id "user"
                     ;; lands before fixtures); the run map comes first so
                     ;; the turn's lookup-ref resolves in-tx.
                     [{:seon.agent.run/id    run-id
                       :seon.agent.run/cause [:seon.user/id "user"]}
                      {:seon.agent.turn/id          (db/new-id!)
                       :seon.agent.turn/at          (js/Date.)
                       :seon.agent.turn/status      :done
                       :seon.agent.turn/run         [:seon.agent.run/id run-id]
                       :seon.agent.turn/prompt-file phantom}]
                     :seon.gym.scenario/predicates
                     [{:seon.gym.predicate/id   :every-turn-red-on-missing-blob
                       :seon.gym.predicate/kind :prompt-every-turn
                       :seon.gym.predicate/axis :sees-question
                       :seon.gym.predicate/text prompt-pred-question}]))]
      (-> (gym/run-scenario! {:seon.gym/scenario scenario})
          (.then (fn [card]
                   (gym/print-scorecard! card)
                   (let [r (result-by-id card :every-turn-red-on-missing-blob)]
                     (is (false? (:seon.gym.result/pass? r))
                         "a missing blob is RED, never a silent pass")
                     (is (str/includes? (:seon.gym.result/actual r) phantom)
                         (str "the RED actual names the missing path — "
                              (:seon.gym.result/actual r))))
                   (is (false? (:seon.gym.scorecard/pass? card)))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; PER-TURN CONTEXT TELEMETRY — informational only, NEVER gates pass?
;; (user r2, 2026-06-11: the former structural gates broke the gym on
;; every context change and were removed). Each driven turn's scorecard
;; profile records what context was loaded — context BLOCK names in
;; render order + per-block TOKEN estimates — so "what db state/context
;; did the agent see" stays answerable without coupling the verdict to
;; layout.
;; ---------------------------------------------------------------------------

(deftest turn-profiles-record-context-telemetry-without-gating
  (async done
    (let [scenario
          {:seon.gym.scenario/id     :gymtest-context-telemetry
           :seon.gym.scenario/competency :planning
           :seon.gym.scenario/doc    "Inline stub: an agent installs a custom context block; turn-profiles record blocks + token estimates as evidence, and the verdict comes ONLY from the scenario's predicates."
           :seon.gym.scenario/tier   :stub
           :seon.gym.scenario/status :active
           :seon.gym.scenario/axes   [:terminates]
           :seon.gym.scenario/fixture-sources
           ["(defn gymtest-telemetry-section [_] \";; a custom agent contribution\")"]
           :seon.gym.scenario/turns
           [{:seon.gym.turn/message "Install your section now."
             :seon.gym.turn/llm-script
             [(str "(seon.agent.ctx/install! {:seon.agent.ctx/name :gymtest-static "
                   ":seon.agent.ctx/priority 12 "
                   ":seon.render/ai 'cljs.user/gymtest-telemetry-section})\n")]}
            {:seon.gym.turn/message "Now render a turn with it in place."
             :seon.gym.turn/llm-script
             ["(message/user \"rendered\")\n"]}]
           :seon.gym.scenario/predicates
           [{:seon.gym.predicate/id     :turn-closes-done
             :seon.gym.predicate/kind   :datalog
             :seon.gym.predicate/axis   :terminates
             :seon.gym.predicate/query  '[:find ?t :where
                                          [?t :seon.agent.turn/status :done]]
             :seon.gym.predicate/expect :non-empty}]}]
      (-> (gym/run-scenario! {:seon.gym/scenario scenario})
          (.then (fn [card]
                   (gym/print-scorecard! card)
                   (is (m/validate :seon.gym/scorecard card)
                       "scorecard with turn-profiles validates")
                   (is (true? (:seon.gym.scorecard/pass? card))
                       (str "verdict comes only from the scenario's own "
                            "predicates — failing results: "
                            (pr-str (filterv (complement :seon.gym.result/pass?)
                                             (:seon.gym.scorecard/results card)))))
                   ;; telemetry NEVER injects results: every result id is a
                   ;; scenario-declared predicate, nothing auto-appended.
                   (is (= [:turn-closes-done]
                          (mapv :seon.gym.predicate/id
                                (:seon.gym.scorecard/results card)))
                       "no standing/structural results ride the scorecard")
                   ;; ...but the evidence is all there.
                   (let [profiles (:seon.gym.scorecard/turn-profiles card)]
                     (is (= 2 (count profiles)) "one profile per driven turn")
                     (doseq [p profiles]
                       (is (m/validate :seon.gym/turn-profile p)))
                     (let [toks (into {} (:seon.gym.profile/block-tokens
                                          (last profiles)))]
                       (is (pos? (get toks :namespaces 0))
                           "core blocks render into the profile")
                       (is (pos? (get toks :gymtest-static 0))
                           "the installed block renders into the profile"))
                     (is (some #{:gymtest-static}
                               (:seon.gym.profile/blocks (last profiles)))
                         "turn 2's layout records the installed block"))
                   ;; prompt-file evidence: what the agent saw, per turn.
                   (is (seq (:seon.gym.scorecard/prompt-files card))
                       "scorecard carries prompt-blob paths")
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; CONFIG-AWARE LOADOUT — a run names a seon.config profile/manifest; the
;; driver steers SEON_PROFILE/SEON_CONFIG so the REAL seed paths
;; (boot-seed! + create! → seed-default-ctx! → resolve-loadout) seed the
;; gym agents' :seon.agent/ctx from THAT loadout. The resulting context
;; SIZE lands in the turn-profile block-tokens (the A/B lever). Here:
;; :minimal drops the always-on :skill/repl body → a smaller, observably
;; different seeded context, with zero gym-local seeding logic.
;; ---------------------------------------------------------------------------

(defn- first-profile-blocks [card]
  (set (:seon.gym.profile/blocks
        (first (:seon.gym.scorecard/turn-profiles card)))))

(defn- first-profile-tokens [card]
  (reduce + 0 (map second
                   (:seon.gym.profile/block-tokens
                    (first (:seon.gym.scorecard/turn-profiles card))))))

(deftest config-profile-shapes-the-seeded-context
  (async done
    (let [s (load-first "test/seon/gym/scenarios/s01-stub-pipeline-smoke.edn")]
      (-> (.then (gym/run-scenario! {:seon.gym/scenario s})
                 (fn [full]
                   (.then (gym/run-scenario!
                            {:seon.gym/scenario s
                             :seon.gym/config
                             {:seon.gym.config/profile :minimal}})
                          (fn [lean] [full lean]))))
          (.then (fn [[full lean]]
                   (is (contains? (first-profile-blocks full) :skill/repl)
                       "default loadout seeds the always-on :skill/repl body")
                   (is (not (contains? (first-profile-blocks lean) :skill/repl))
                       ":minimal profile drops :skill/repl from the seeded ctx")
                   (is (contains? (first-profile-blocks lean) :namespaces)
                       ":minimal keeps the load-bearing :namespaces block")
                   (is (< (first-profile-tokens lean)
                          (first-profile-tokens full))
                       (str "lean context is smaller in tokens — full="
                            (first-profile-tokens full) " lean="
                            (first-profile-tokens lean)))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; SELF-BAIT LOAD CHECK (gym-upgrade §3.4) — a scenario whose fixture
;; values contain a turn message verbatim must FAIL TO LOAD with the
;; named error: any predicate keyed on question text could otherwise
;; pass by string coincidence (the s32 class).
;; ---------------------------------------------------------------------------

(deftest self-baited-scenario-fails-to-load
  (let [fs       (js/require "node:fs")
        path     "tmp/gymtest-self-bait.edn"
        q        "What is the self-bait marker question for the load check?"
        scenario (fn [fixture-q]
                   {:seon.gym.scenario/id     :gymtest-self-bait
                    :seon.gym.scenario/competency :db-memory
                    :seon.gym.scenario/doc    "Deliberately self-baited (gym-upgrade §3.4 falsification): the fixture's question IS the turn message."
                    :seon.gym.scenario/tier   :stub
                    :seon.gym.scenario/status :active
                    :seon.gym.scenario/axes   [:sees-question]
                    :seon.gym.scenario/fixtures
                    [{:my.kb.codebase/claim    "an answer"
                      :my.kb.codebase/question fixture-q}]
                    :seon.gym.scenario/turns
                    [{:seon.gym.turn/message q}]
                    :seon.gym.scenario/predicates []})]
    (.mkdirSync fs "tmp" #js {:recursive true})
    (.writeFileSync fs path (pr-str (scenario q)))
    (let [err (try (gym/load-scenarios! {:seon.gym/path path})
                   nil
                   (catch :default e e))]
      (is (some? err) "the self-baited scenario must not load")
      (is (str/includes? (str (ex-message err)) "SELF-BAIT")
          (str "the load failure names the rule — " (ex-message err)))
      (is (= 0 (:seon.gym.run/fixture-index (ex-data err)))
          "the load failure names the offending fixture"))
    ;; WHITESPACE-NORMALIZED matching (user decision 2026-06-11 — the
    ;; s32 salience contamination lived across a docstring line break):
    ;; the same bait SPLIT BY A LINE BREAK must also fail to load.
    (.writeFileSync fs path
                    (pr-str (scenario (str/replace q "marker question"
                                                   "marker\n   question"))))
    (let [err (try (gym/load-scenarios! {:seon.gym/path path})
                   nil
                   (catch :default e e))]
      (is (some? err) "the line-break-split self-bait must not load")
      (is (str/includes? (str (ex-message err)) "SELF-BAIT")
          (str "the normalized check names the rule — " (ex-message err))))
    ;; the same scenario with a PARAPHRASED fixture question loads fine
    (.writeFileSync fs path
                    (pr-str (scenario "A paraphrase, not the verbatim turn text.")))
    (is (seq (:seon.gym/scenarios (gym/load-scenarios! {:seon.gym/path path})))
        "the paraphrased variant loads")
    (.rmSync fs path)))

;; ---------------------------------------------------------------------------
;; CONSULT-PREDICATE ANCHORING — WIDENED (user decision 2026-06-11,
;; same behavior-not-vocabulary logic as the fix-everything PRD §2
;; provenance widening): "consulted first" = the first message-driven
;; eval's SOURCE contains a seon.db READ op
;; (query/pull/entity/store-inventory) — ANY store read counts, not
;; just :my.kb-anchored spellings. Falsified both ways with free stub
;; runs: a first eval that never reads the store scores the anchor
;; RED; a store read against ANY attr (even an unrelated :my.* one the
;; retired domain-namespace anchor rejected) scores it green — the old
;; vocabulary anchor is pinned here as the retired defect.
;; ---------------------------------------------------------------------------

(defn- consult-anchor-scenario [first-eval]
  {:seon.gym.scenario/id     :gymtest-consult-anchor
   :seon.gym.scenario/competency :db-memory
   :seon.gym.scenario/doc    "Inline stub (consult-anchor widening, 2026-06-11 falsification): the anchored consult predicate must track STORE READS (behavior), not attr vocabulary."
   :seon.gym.scenario/tier   :stub
   :seon.gym.scenario/status :active
   :seon.gym.scenario/axes   [:consults-findings]
   :seon.gym.scenario/schema-registrations
   [[:my.kb.codebase/claim :string]
    [:my.kb.codebase/question :string]]
   :seon.gym.scenario/fixtures
   [{:my.kb.codebase/claim    "the stored answer"
     :my.kb.codebase/question "a paraphrased stored question"}]
   :seon.gym.scenario/turns
   [{:seon.gym.turn/message "Where is the stored answer recorded?"
     :seon.gym.turn/llm-script [first-eval]}]
   :seon.gym.scenario/predicates
   [{:seon.gym.predicate/id      :first-eval-consults-stored-findings
     :seon.gym.predicate/kind    :first-eval-matches
     :seon.gym.predicate/axis    :consults-findings
     :seon.gym.predicate/pattern "seon\\.db/(query|pull|entity|store-inventory)"}
    ;; the RETIRED domain-vocabulary anchor, kept ONLY to pin the
    ;; defect the widening fixed: it scores RED on a legitimate store
    ;; read that happens to touch a different attr spelling.
    {:seon.gym.predicate/id      :domain-vocab-anchor-the-old-defect
     :seon.gym.predicate/kind    :first-eval-matches
     :seon.gym.predicate/axis    :consults-findings
     :seon.gym.predicate/pattern ":my\\.kb\\.codebase/"}]})

(deftest consult-anchor-rejects-a-first-eval-that-never-reads-the-store
  ;; Widening falsification, RED side: the first eval is pure
  ;; computation — no seon.db read op anywhere in its source — so the
  ;; structural anchor scores RED.
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (consult-anchor-scenario
             "(str \"thinking out loud — no store read here\")\n")})
        (.then (fn [card]
                 (gym/print-scorecard! card)
                 (let [anchored (result-by-id
                                  card :first-eval-consults-stored-findings)]
                   (is (false? (:seon.gym.result/pass? anchored))
                       (str "a no-store-read first eval scores the consult "
                            "anchor RED — "
                            (:seon.gym.result/actual anchored))))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest consult-anchor-passes-on-any-store-read
  ;; Widening falsification, GREEN side: the first eval queries the
  ;; store through an attr OUTSIDE the seeded :my.kb.codebase domain —
  ;; under the 2026-06-11 decision that IS a consult (the agent went
  ;; to the store first; the judge measures whether it paid off). The
  ;; retired vocabulary anchor scores RED on the same eval — the
  ;; punished-naming-preference defect, pinned.
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (consult-anchor-scenario
             "(seon.db/query {:seon.db/query '[:find ?v :where [?e :my.agent.scratch/note ?v]]})\n")})
        (.then (fn [card]
                 (gym/print-scorecard! card)
                 (let [anchored (result-by-id
                                  card :first-eval-consults-stored-findings)
                       vocab    (result-by-id
                                  card :domain-vocab-anchor-the-old-defect)]
                   (is (true? (:seon.gym.result/pass? anchored))
                       (str "any store-read first eval scores the widened "
                            "anchor green — "
                            (:seon.gym.result/actual anchored)))
                   (is (false? (:seon.gym.result/pass? vocab))
                       "…while the RETIRED :my.kb.codebase vocabulary anchor scores it RED — the punished-vocabulary defect"))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest consult-anchor-passes-on-seeded-domain-touch
  ;; GREEN side, seeded-domain spelling: a :my.kb.codebase/* query is
  ;; still a store read — both anchors green.
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (consult-anchor-scenario
             "(seon.db/query {:seon.db/query '[:find ?c :where [?e :my.kb.codebase/claim ?c]]})\n")})
        (.then (fn [card]
                 (gym/print-scorecard! card)
                 (let [anchored (result-by-id
                                  card :first-eval-consults-stored-findings)]
                   (is (true? (:seon.gym.result/pass? anchored))
                       (str "a :my.kb.codebase/* consult scores green — "
                            (:seon.gym.result/actual anchored))))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; LLM-JUDGE wiring — proven with a MOCKED judge llm-fn (zero spend).
;; The judge verdict lands on the SEPARATE judge axis of the scorecard:
;; mechanical pass?/axes/results never mix with judge-pass?/judge-results,
;; so "behaved right, answered wrong" stays a distinct failure signature.
;; ---------------------------------------------------------------------------

(defn- judge-wiring-scenario []
  {:seon.gym.scenario/id     :judge-wiring-mock
   :seon.gym.scenario/competency :honesty
   :seon.gym.scenario/doc    "Inline stub scenario proving judge verdict→axis wiring with a mocked judge llm. Also pins the datahike namespace/name query built-ins the S-21 fork predicate relies on."
   :seon.gym.scenario/tier   :stub
   :seon.gym.scenario/status :active
   :seon.gym.scenario/axes   [:replies-honestly :terminates]
   :seon.gym.scenario/turns
   [{:seon.gym.turn/message "What does message! return?"
     :seon.gym.turn/llm-script
     ["(message/user \"message! returns the concise envelope {:seon.agent.message/ok? true ...}\")\n"]}]
   :seon.gym.scenario/predicates
   [{:seon.gym.predicate/id     :turn-closes-done
     :seon.gym.predicate/kind   :datalog
     :seon.gym.predicate/axis   :terminates
     :seon.gym.predicate/query  '[:find ?t :where [?t :seon.agent.turn/status :done]]
     :seon.gym.predicate/expect :non-empty}
    ;; live proof for the datalog built-ins S-21's fork predicate uses
    {:seon.gym.predicate/id     :namespace-name-builtins-resolve
     :seon.gym.predicate/kind   :datalog
     :seon.gym.predicate/axis   :terminates
     :seon.gym.predicate/query  '[:find ?n
                                  :where
                                  [?m :seon.agent.message/content _]
                                  [?m ?a _]
                                  [(namespace ?a) ?ns]
                                  [(= ?ns "seon.agent.message")]
                                  [(name ?a) ?n]]
     :seon.gym.predicate/expect [:every-in ["id" "from" "to" "content"
                                            "at" "hops" "origin"]]}
    {:seon.gym.predicate/id        :judge-mock
     :seon.gym.predicate/kind      :llm-judge
     :seon.gym.predicate/axis      :replies-honestly
     :seon.gym.predicate/rubric    "Reply must state the concise envelope."
     :seon.gym.predicate/reference "message! returns {:seon.agent.message/ok? true ...} — never the raw tx-report."}]})

(deftest llm-judge-verdict-wiring-with-mocked-judge
  (async done
    (let [!ctx     (atom nil)
          judge-fn (fn [ctx]
                     (reset! !ctx ctx)
                     (js/Promise.resolve
                       {:text "{\"pass\": true, \"score\": 88, \"justification\": \"states the concise envelope keys; consistent with the reference facts\"}"}))]
      (-> (gym/run-scenario! {:seon.gym/scenario (judge-wiring-scenario)
                              :seon.gym/judge-fn judge-fn})
          (.then (fn [card]
                   (gym/print-scorecard! card)
                   (is (m/validate :seon.gym/scorecard card)
                       "scorecard with judge results validates")
                   (is (true? (:seon.gym.scorecard/pass? card))
                       "mechanical predicates pass")
                   (is (true? (:seon.gym.scorecard/judge-pass? card))
                       "judge verdict rolls up on its own axis")
                   (let [jr (first (:seon.gym.scorecard/judge-results card))]
                     (is (= :judge-mock (:seon.gym.predicate/id jr)))
                     (is (true? (:seon.gym.judge/pass? jr)))
                     (is (= 88 (:seon.gym.judge/score jr)))
                     (is (str/includes? (:seon.gym.judge/justification jr)
                                        "reference facts")
                         "verbatim justification preserved"))
                   ;; judge predicates never leak into mechanical results
                   (is (not-any? #(= :judge-mock (:seon.gym.predicate/id %))
                                 (:seon.gym.scorecard/results card)))
                   ;; the judge saw question + reply + rubric + reference
                   (is (str/includes? @!ctx "What does message! return?"))
                   (is (str/includes? @!ctx "concise envelope"))
                   (is (str/includes? @!ctx "== Rubric =="))
                   (is (str/includes? @!ctx "== Reference facts"))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest llm-judge-fail-stays-on-its-own-axis
  ;; "Behaved right, answered wrong": mechanical pass? true while the
  ;; judge fails — the two verdicts must never merge.
  (async done
    (let [judge-fn (fn [_ctx]
                     (js/Promise.resolve
                       {:text "{\"pass\": false, \"score\": 20, \"justification\": \"reply contradicts the reference facts\"}"}))]
      (-> (gym/run-scenario! {:seon.gym/scenario (judge-wiring-scenario)
                              :seon.gym/judge-fn judge-fn})
          (.then (fn [card]
                   (is (true? (:seon.gym.scorecard/pass? card))
                       "mechanical axis still passes")
                   (is (false? (:seon.gym.scorecard/judge-pass? card))
                       "judge axis fails independently")
                   (let [jr (first (:seon.gym.scorecard/judge-results card))]
                     (is (false? (:seon.gym.judge/pass? jr)))
                     (is (= 20 (:seon.gym.judge/score jr))))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest two-agent-judge-extracts-each-agents-own-reply
  ;; Multi-agent sequencing + per-agent judge scoping: agent :a and :b
  ;; each reply! with a distinct text; each judge ctx must carry THAT
  ;; agent's reply (and question), and the args-substituted datalog
  ;; (:seon.gym.agent/b) must count ONLY b's reply.
  (async done
    (let [!ctxs    (atom [])
          judge-fn (fn [ctx]
                     (swap! !ctxs conj ctx)
                     (js/Promise.resolve
                       {:text "{\"pass\": true, \"score\": 100, \"justification\": \"ok\"}"}))
          scenario
          {:seon.gym.scenario/id     :two-agent-judge-wiring
           :seon.gym.scenario/competency :honesty
           :seon.gym.scenario/doc    "Two stub agents reply distinctly; per-agent judge ctx + args-substituted datalog are scoped correctly."
           :seon.gym.scenario/tier   :stub
           :seon.gym.scenario/status :active
           :seon.gym.scenario/axes   [:replies-honestly :terminates]
           :seon.gym.scenario/turns
           [{:seon.gym.turn/agent   :a
             :seon.gym.turn/message "alpha question"
             :seon.gym.turn/llm-script
             ["(message/user \"ALPHA-ANSWER\")\n"]}
            {:seon.gym.turn/agent   :b
             :seon.gym.turn/message "beta question"
             :seon.gym.turn/llm-script
             ["(message/user \"BETA-ANSWER\")\n"]}]
           :seon.gym.scenario/predicates
           ;; ENGINE BUG FIXED (datahike fork sha 1ae35696, deps commit
           ;; 156a53e — multi-group join corruption fix): a datalog
           ;; query joining TWO identity-attr clauses through one
           ;; message row used to IGNORE the :in ?bid binding and
           ;; return the inverse-direction (user→agent) rows. This is
           ;; the ORIGINAL double-identity-join query, restored as the
           ;; live regression pin for that fix: it must count b's
           ;; messages TO the user. Under the carved FSM that is TWO:
           ;; the bootstrap-turn 0 greeting (message/user "Hi — I'm up…")
           ;; PLUS the BETA-ANSWER reply. The buggy inverse direction
           ;; (user→b) is the single beta question — so [:count 2] still
           ;; distinguishes the correct binding (2) from the engine bug (1).
           [{:seon.gym.predicate/id     :b-sent-greeting-and-reply-to-user
             :seon.gym.predicate/kind   :datalog
             :seon.gym.predicate/axis   :replies-honestly
             :seon.gym.predicate/args   [:seon.gym.agent/b]
             :seon.gym.predicate/query  '[:find ?m ?c
                                          :in $ ?bid
                                          :where
                                          [?ag :seon.agent/id ?bid]
                                          [?m :seon.agent.message/from ?ag]
                                          [?m :seon.agent.message/to ?u]
                                          [?u :seon.user/id "user"]
                                          [?m :seon.agent.message/content ?c]]
             :seon.gym.predicate/expect [:count 2]}
            {:seon.gym.predicate/id        :judge-a
             :seon.gym.predicate/kind      :llm-judge
             :seon.gym.predicate/agent     :a
             :seon.gym.predicate/axis      :replies-honestly
             :seon.gym.predicate/rubric    "r-a"
             :seon.gym.predicate/reference "f-a"}
            {:seon.gym.predicate/id        :judge-b
             :seon.gym.predicate/kind      :llm-judge
             :seon.gym.predicate/agent     :b
             :seon.gym.predicate/axis      :replies-honestly
             :seon.gym.predicate/rubric    "r-b"
             :seon.gym.predicate/reference "f-b"}]}]
      (-> (gym/run-scenario! {:seon.gym/scenario scenario
                              :seon.gym/judge-fn judge-fn})
          (.then (fn [card]
                   (gym/print-scorecard! card)
                   (let [r (->> (:seon.gym.scorecard/results card)
                                (filter #(= :b-sent-greeting-and-reply-to-user
                                            (:seon.gym.predicate/id %)))
                                first)]
                     (is (true? (:seon.gym.result/pass? r))
                         (str "b-scoped datalog counts b's greeting + reply — "
                              (:seon.gym.result/actual r))))
                   (let [[ctx-a ctx-b] @!ctxs]
                     (is (str/includes? ctx-a "alpha question"))
                     (is (str/includes? ctx-a "ALPHA-ANSWER")
                         "judge :a sees a's reply")
                     (is (not (str/includes? ctx-a "BETA-ANSWER"))
                         "judge :a does NOT see b's reply")
                     (is (str/includes? ctx-b "beta question"))
                     (is (str/includes? ctx-b "BETA-ANSWER")
                         "judge :b sees b's reply")
                     (is (not (str/includes? ctx-b "ALPHA-ANSWER"))
                         "judge :b does NOT see a's reply"))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest llm-judge-without-judge-fn-records-explicit-skip
  ;; No injected judge + no allow-paid? → the verdict is an EXPLICIT
  ;; fail naming the guard — never a silent pass, never a crash.
  (async done
    (-> (gym/run-scenario! {:seon.gym/scenario (judge-wiring-scenario)})
        (.then (fn [card]
                 (is (false? (:seon.gym.scorecard/judge-pass? card)))
                 (is (str/includes?
                       (-> card :seon.gym.scorecard/judge-results first
                           :seon.gym.judge/justification)
                       "SKIPPED"))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; JUDGE CALIBRATION wiring (free, mocked). Before trusting the judge as
;; the PRIMARY signal, the harness proves it DISCRIMINATES good from bad
;; for the same rubric+reference. These tests prove the calibration
;; primitive's wiring with a mock; the real DeepSeek discrimination
;; evidence is the paid-tier calibration (seon.gym.paid-test).
;; ---------------------------------------------------------------------------

(def ^:private calib-base
  {:seon.gym.calib/question  "Where does seon validate a value's type at transact?"
   :seon.gym.calib/rubric    "PASS only if the reply names per-value Malli validation in src/seon/db/internal.cljs and the {:seon.db/ok? false ...} error VALUE. FAIL on a fabricated file or a thrown-to-caller claim."
   :seon.gym.calib/reference "validate-entity-values! in src/seon/db/internal.cljs Malli-validates each value; seon.db/transact! catches and returns {:seon.db/ok? false :seon.db/error ...} — the caller's promise resolves, never throws."
   :seon.gym.calib/good-reply "seon.db/transact! runs validate-entity-values! (src/seon/db/internal.cljs), which Malli-checks every value; a non-conforming value comes back as the VALUE {:seon.db/ok? false :seon.db/error ...} — the caller never sees a throw."
   :seon.gym.calib/bad-reply  "transact! throws a Java SchemaException from src/seon/validation/core.clj straight to the caller, who must wrap it in try/catch."})

(deftest judge-calibration-discriminates-good-from-bad-with-mock
  ;; A discriminating mock (PASS the good reply, FAIL the bad) → the
  ;; calibration reports discriminates? true and the two verdicts split.
  (async done
    (let [judge-fn (fn [ctx]
                     (js/Promise.resolve
                       {:text (if (str/includes? ctx "never sees a throw")
                                "{\"pass\": true, \"score\": 92, \"justification\": \"names internal.cljs + the error value\"}"
                                "{\"pass\": false, \"score\": 8, \"justification\": \"fabricated file + caller-facing throw\"}")}))]
      (-> (gym/calibrate-judge! (assoc calib-base :seon.gym/judge-fn judge-fn))
          (.then (fn [resp]
                   (is (m/validate :seon.gym.calib/response resp))
                   (is (true? (:seon.gym.calib/discriminates? resp))
                       "good PASS ∧ bad FAIL = a discriminating judge")
                   (is (true? (get-in resp [:seon.gym.calib/good
                                            :seon.gym.judge/pass?])))
                   (is (false? (get-in resp [:seon.gym.calib/bad
                                             :seon.gym.judge/pass?])))
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest judge-calibration-flags-a-nondiscriminating-judge
  ;; FALSIFICATION: a rubber-stamp judge that PASSES even the fabricated
  ;; reply is NOT a trustworthy signal — discriminates? must be false.
  (async done
    (let [yes-judge (fn [_ctx]
                      (js/Promise.resolve
                        {:text "{\"pass\": true, \"score\": 75, \"justification\": \"looks fine\"}"}))]
      (-> (gym/calibrate-judge! (assoc calib-base :seon.gym/judge-fn yes-judge))
          (.then (fn [resp]
                   (is (false? (:seon.gym.calib/discriminates? resp))
                       "a judge that passes the BAD reply fails calibration")
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest judge-calibration-without-judge-is-an-explicit-skip
  ;; No judge-fn + no allow-paid? → both verdicts are the explicit SKIP
  ;; fail, so discriminates? is false (never a silent pass).
  (async done
    (-> (gym/calibrate-judge! calib-base)
        (.then (fn [resp]
                 (is (false? (:seon.gym.calib/discriminates? resp)))
                 (is (str/includes? (get-in resp [:seon.gym.calib/good
                                                  :seon.gym.judge/justification])
                                    "SKIPPED"))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; CURATION AXES (context-curation Phase A) — eval-error-rate + canvas.
;; Every scorecard carries :seon.gym.scorecard/eval-error-rate (failed
;; RUN-DRIVEN evals ÷ total) and :seon.gym.scorecard/canvas-updated?
;; (did the primary agent set its own :seon.render.live-tile/content);
;; the :eval-error-rate / :canvas-updated predicate kinds assert them.
;; ---------------------------------------------------------------------------

(deftest curation-axes-eval-error-rate-and-canvas-scored
  ;; One turn: the agent drives its OWN canvas (sets live-tile content on
  ;; itself) THEN makes a failing eval — so canvas-updated? is true and
  ;; eval-error-rate is in (0,1). Both predicate kinds + both scorecard
  ;; fields exercised.
  (async done
    (let [scenario
          {:seon.gym.scenario/id         :gymtest-curation-axes
           :seon.gym.scenario/competency :error-recovery
           :seon.gym.scenario/doc        "Inline stub: drive the canvas, then one failing eval — eval-error-rate in (0,1), canvas-updated? true."
           :seon.gym.scenario/tier       :stub
           :seon.gym.scenario/status     :active
           :seon.gym.scenario/axes       [:drives-canvas :makes-few-errors]
           :seon.gym.scenario/turns
           [{:seon.gym.turn/message "drive your canvas, then slip up"
             :seon.gym.turn/llm-script
             [(str "(seon.db/transact! {:seon.db/tx-data "
                   "[{:seon.agent/id (seon.db/current-agent-id) "
                   ":seon.render.live-tile/content [:div \"hi\"]}]})\n"
                   "(this-symbol-does-not-exist-xyzzy)\n")]}]
           :seon.gym.scenario/predicates
           [{:seon.gym.predicate/id   :drove-its-canvas
             :seon.gym.predicate/kind :canvas-updated
             :seon.gym.predicate/axis :drives-canvas}
            {:seon.gym.predicate/id             :error-rate-under-threshold
             :seon.gym.predicate/kind           :eval-error-rate
             :seon.gym.predicate/axis           :makes-few-errors
             :seon.gym.predicate/max-error-rate 0.9}]}]
      (-> (gym/run-scenario! {:seon.gym/scenario scenario})
          (.then (fn [card]
                   (gym/print-scorecard! card)
                   (is (m/validate :seon.gym/scorecard card)
                       "scorecard with curation fields validates")
                   (let [rate (:seon.gym.scorecard/eval-error-rate card)]
                     (is (number? rate))
                     (is (< 0.0 rate 1.0)
                         (str "one of two run-driven evals failed → "
                              "rate in (0,1) — " rate)))
                   (is (true? (:seon.gym.scorecard/canvas-updated? card))
                       "the agent drove its own canvas")
                   (is (true? (:seon.gym.result/pass?
                                (result-by-id card :drove-its-canvas)))
                       "canvas-updated predicate passes")
                   (is (true? (:seon.gym.result/pass?
                                (result-by-id card :error-rate-under-threshold)))
                       "eval-error-rate predicate passes under a 0.9 threshold")
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest curation-axes-error-value-is-not-a-failed-eval-and-no-canvas
  ;; envelope-honesty's bogus transact resolves to an error VALUE — the
  ;; eval itself is :seon.eval/ok? true — so eval-error-rate stays 0.0,
  ;; and the agent never drove its canvas. Pins error-as-value ≠
  ;; failed-eval and the absent-canvas default.
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (load-first "test/seon/gym/scenarios/envelope-honesty.edn")})
        (.then (fn [card]
                 (is (zero? (:seon.gym.scorecard/eval-error-rate card))
                     (str "an error VALUE is not a failed eval — rate "
                          (:seon.gym.scorecard/eval-error-rate card)))
                 (is (false? (:seon.gym.scorecard/canvas-updated? card))
                     "the agent never set its own live-tile content")
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; COMPETENCY BATTERY — run-competency-battery! runs ONLY the scenarios
;; tagged with the requested :seon.gym.scenario/competency, in order.
;; ---------------------------------------------------------------------------

(deftest competency-battery-runs-only-matching-scenarios
  (async done
    (let [mk (fn [id comp]
               {:seon.gym.scenario/id         id
                :seon.gym.scenario/competency comp
                :seon.gym.scenario/doc        "battery member"
                :seon.gym.scenario/tier       :stub
                :seon.gym.scenario/status     :active
                :seon.gym.scenario/llm        :scripted-replay
                :seon.gym.scenario/axes       [:terminates]
                :seon.gym.scenario/turns
                [{:seon.gym.turn/message "ping"
                  :seon.gym.turn/llm-script
                  ["(message/user \"pong\")\n(wait \"done\")\n"]}]
                :seon.gym.scenario/predicates
                [{:seon.gym.predicate/id     :turn-closes-done
                  :seon.gym.predicate/kind   :datalog
                  :seon.gym.predicate/axis   :terminates
                  :seon.gym.predicate/query  '[:find ?t :where
                                               [?t :seon.agent.turn/status :done]]
                  :seon.gym.predicate/expect :non-empty}]})
          scenarios [(mk :batt-honesty-1 :honesty)
                     (mk :batt-planning-1 :planning)
                     (mk :batt-honesty-2 :honesty)]]
      (-> (gym/run-competency-battery!
            {:seon.gym/scenarios  scenarios
             :seon.gym/competency :honesty})
          (.then (fn [cards]
                   (is (= 2 (count cards))
                       "only the two :honesty members ran")
                   (is (= #{:batt-honesty-1 :batt-honesty-2}
                          (set (map :seon.gym.scorecard/scenario cards)))
                       "the battery ran exactly the tagged scenarios")
                   (is (every? :seon.gym.scorecard/pass? cards)
                       "each battery member passed")
                   (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ---------------------------------------------------------------------------
;; Budget guards — paid tier and :todo scenarios REFUSE with an
;; error value; the suite can never burn money or run unencoded intent.
;; ---------------------------------------------------------------------------

(deftest paid-tier-refuses-without-allow-paid
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (load-first "test/seon/gym/scenarios/consults-findings-run8.edn")})
        (.then (fn [resp]
                 (is (false? (:seon.gym/ok? resp))
                     "paid scenario refused without allow-paid?")
                 (is (re-find #"costs real money" (str (:seon.gym/error resp)))
                     "the refusal explains the budget guard")
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest todo-scenarios-refuse-to-run
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (load-first "test/seon/gym/scenarios/todo-prompt-thin.edn")})
        (.then (fn [resp]
                 (is (false? (:seon.gym/ok? resp)) ":todo scenario refused")
                 (is (re-find #":todo" (str (:seon.gym/error resp))))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
