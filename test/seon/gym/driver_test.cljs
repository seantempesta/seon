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
    [seon.ctx :as ctx]
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
   "test/seon/gym/scenarios/todo-prompt-thin.edn"])

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
  ;; The §7 item-12 rubric, verbatim, plus the U3 structural axis — a
  ;; predicate tagged outside this vocabulary must fail schema
  ;; validation at load time.
  (is (m/validate :seon.gym.axis/name :consults-findings))
  (is (m/validate :seon.gym.axis/name :stores-proactively))
  (is (m/validate :seon.gym.axis/name :context-fidelity))
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
  ;; S-01 drives the REAL run-agentic-loop! via the :scripted-replay
  ;; llm injection — message → wake → done turn → idle within 3 turns.
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
;; INTO (entity-schema decomposition + instruction rows + substrate
;; index, under :substrate-seed provenance) with the scenario's
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
                                (let [db      @conn
                                      domain  (set (warn/domain-attrs
                                                     {:seon.db/db db}))
                                      catalog (agent/schema-catalog-section
                                                {:seon.db/db db})]
                                  ;; the seeded reuse surface IS domain attrs
                                  ;; (S-21 production-bug pin: seon.* DATA
                                  ;; domains must not be blanket-hidden)
                                  (is (contains? domain :seon.workout/date))
                                  (is (contains? domain
                                                 :seon.workout/duration-minutes))
                                  ;; substrate attrs stay OUT of the reuse
                                  ;; surface (seed provenance)
                                  (is (not-any? #(= "seon.db" (namespace %))
                                                domain)
                                      "no :seon.db/* leaks into domain attrs")
                                  (is (not (contains? domain :seon.agent/id)))
                                  ;; …and the agent-facing catalog renders the
                                  ;; reuse block with the exact keywords
                                  (is (str/includes? catalog
                                                     "domain data attrs")
                                      "the reuse block renders")
                                  (is (str/includes? catalog
                                                     ":seon.workout/duration-minutes")
                                      "the established attr is in the prompt")
                                  ;; pod-boot parity: the substrate index +
                                  ;; entity-schema decomposition are present
                                  (is (str/includes? catalog
                                                     "all registered schemas")
                                      "index-schemas rows render (boot parity)")))))))
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
;;   - every relevant-ns? member of (client/substrate-ns-set) has a FULL
;;     :seon.ns/source row (the :exemplars 7-block set — gym iteration 2
;;     rendered 4/7 because the :test build never loaded
;;     seon.dev.test-preload and index-tests seeded nothing);
;;   - one :seon.test row per preload-roster deftest var;
;;   - the substrate :wake/on-message handler entity exists;
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
                                      expected (->> (client/substrate-ns-set)
                                                    (map name)
                                                    (filter ctx/relevant-ns?)
                                                    set)
                                      full-src (->> (db/query
                                                      {:seon.db/db dbv
                                                       :seon.db/query
                                                       '[:find ?nm ?src
                                                         :where
                                                         [?n :seon.ns/name ?nm]
                                                         [?n :seon.ns/source ?src]]})
                                                    (keep (fn [[nm src]]
                                                            (when (and (ctx/relevant-ns? (name nm))
                                                                       (not= (str/trim src)
                                                                             (str "(ns " (name nm) ")")))
                                                              (name nm))))
                                                    set)
                                      test-rows (count (db/query
                                                         {:seon.db/db dbv
                                                          :seon.db/query
                                                          '[:find ?t :where
                                                            [?e :seon.test/sym ?t]]}))
                                      handler   (db/query
                                                  {:seon.db/db dbv
                                                   :seon.db/query
                                                   '[:find ?h :where
                                                     [?h :seon.handler/name :wake/on-message]]})
                                      prior     (db/query
                                                  {:seon.db/db dbv
                                                   :seon.db/query
                                                   '[:find ?aid :where
                                                     [?s :seon.schema/key :seon.workout/date ?tx]
                                                     [?tx :seon.db/agent-id ?aid]
                                                     (not [?tx :seon.db/origin :substrate-seed])]})]
                                  (is (= expected full-src)
                                      "every relevant ns the boot indexes carries full source (the exemplar block set)")
                                  (is (= (count @client/!indexed-test-vars) test-rows)
                                      "one :seon.test row per pod-roster deftest var")
                                  (is (seq handler)
                                      "the substrate :wake/on-message handler entity is seeded")
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
;; seon.workout, so :fitness.workout/* forks passed vacuously).
;; ---------------------------------------------------------------------------

(deftest domain-attrs-predicate-catches-a-fork-in-any-namespace
  (async done
    (let [scenario
          {:seon.gym.scenario/id     :gymtest-attr-fork-any-namespace
           :seon.gym.scenario/doc    "Inline stub: the scripted agent forks the seeded workout shape into a DIFFERENT namespace + unit; the generic :domain-attrs no-fork predicate must fail the scorecard."
           :seon.gym.scenario/tier   :stub
           :seon.gym.scenario/status :active
           :seon.gym.scenario/llm    :scripted-replay
           :seon.gym.scenario/axes   [:reuses-schemas]
           :seon.gym.scenario/schema-registrations
           [[:seon.workout/date :string]
            [:seon.workout/type :keyword]
            [:seon.workout/duration-minutes :int]
            [:seon.workout/notes :string]]
           :seon.gym.scenario/fixtures
           [{:seon.workout/date "{{today}}"
             :seon.workout/type :strength
             :seon.workout/duration-minutes 45}]
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
             :seon.gym.predicate/expect [:every-in [":seon.workout/date"
                                                    ":seon.workout/type"
                                                    ":seon.workout/duration-minutes"
                                                    ":seon.workout/notes"]]}]}]
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
;; eyes. run-turn! persists every prompt to logs/prompts/<agent>/<turn>.txt;
;; the new kinds read those blobs from the post-run store. Falsification
;; (per the PRD): the question text passes :prompt-every-turn; text NOT
;; in any prompt fails :prompt-includes WITH the blob path in the
;; actual; a missing/unreadable blob scores RED naming the path.
;; ---------------------------------------------------------------------------

(def ^:private prompt-pred-question
  "What is the gym prompt-blob marker question?")

(defn- prompt-blob-scenario []
  {:seon.gym.scenario/id     :gymtest-prompt-blob-predicates
   :seon.gym.scenario/doc    "Inline stub (gym-upgrade U1 falsification): one scripted turn with a known prompt; :prompt-every-turn on the question text passes, :prompt-includes for absent text fails naming the blob path, :prompt-excludes on absent text passes, :turn index pins/ranges."
   :seon.gym.scenario/tier   :stub
   :seon.gym.scenario/status :active
   :seon.gym.scenario/axes   [:sees-question]
   :seon.gym.scenario/turns
   [{:seon.gym.turn/message prompt-pred-question
     :seon.gym.turn/llm-script
     ["(seon.agent/reply! {:seon.agent.message/content \"noted\"})\n"]}]
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
                                      "logs/prompts/")
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
                   (is (every? #(str/starts-with? % "logs/prompts/") pfs)))
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
          scenario
          (-> (prompt-blob-scenario)
              (assoc :seon.gym.scenario/id :gymtest-prompt-blob-missing
                     :seon.gym.scenario/fixtures
                     [{:seon.agent.turn/id          (db/new-id!)
                       :seon.agent.turn/at          (js/Date.)
                       :seon.agent.turn/status      :done
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
;; STRUCTURAL PER-TURN PROFILE (gym-upgrade PRD §2.2 / U3) — every run
;; captures one :seon.gym/turn-profile per driven turn against the
;; pre-turn db value and scores two standing structural results derived
;; from the composer's own code: layout completeness (every
;; substrate-default-ctx name in the merged layout) and cache-prefix
;; byte-stability (double-render byte-identical up to :transcript).
;; Falsification per the PRD: a section fn embedding (js/Date.) at a
;; STATIC priority flips the stability check RED; removed → green. The
;; volatile fn also bumps an atom counter — a bare (js/Date.) can
;; render IDENTICALLY on a same-millisecond double-render, which would
;; make the RED side flaky-green; the counter makes the volatility
;; deterministic (the PRD's "timestamp or counter" class).
;; ---------------------------------------------------------------------------

(defn- prefix-stability-scenario [id fn-name fixture-src]
  {:seon.gym.scenario/id     id
   :seon.gym.scenario/doc    "Inline stub (gym-upgrade U3 falsification): turn 1 installs an agent section at STATIC priority 12 (inside the cache prefix); turn 2's profile double-renders against the pre-turn db. A volatile section fn must flip the standing cache-prefix-stability result RED; a byte-stable one stays green."
   :seon.gym.scenario/tier   :stub
   :seon.gym.scenario/status :active
   :seon.gym.scenario/axes   [:context-fidelity]
   :seon.gym.scenario/fixture-sources [fixture-src]
   :seon.gym.scenario/turns
   [{:seon.gym.turn/message "Install your static section now."
     :seon.gym.turn/llm-script
     [(str "(seon.agent/add-section! {:seon.ctx/name :gymtest-static "
           ":seon.ctx/priority 12 :seon.render/ai 'cljs.user/" fn-name "})\n")]}
    {:seon.gym.turn/message "Now render a turn with it in place."
     :seon.gym.turn/llm-script
     ["(seon.agent/reply! {:seon.agent.message/content \"rendered\"})\n"]}]
   :seon.gym.scenario/predicates []})

(deftest volatile-static-section-flips-cache-prefix-stability-red
  ;; U3 falsification, RED side.
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (prefix-stability-scenario
             :gymtest-prefix-unstable "gymtest-unstable-section"
             (str "(def !gymtest-renders (atom 0))\n"
                  "(defn gymtest-unstable-section [_]\n"
                  "  (str \";; rendered at \" (js/Date.)"
                  " \" render#\" (swap! !gymtest-renders inc)))"))})
        (.then (fn [card]
                 (gym/print-scorecard! card)
                 (is (m/validate :seon.gym/scorecard card)
                     "scorecard with turn-profiles validates")
                 (let [r (result-by-id card :gym.structural/cache-prefix-stable)]
                   (is (false? (:seon.gym.result/pass? r))
                       "a volatile static-priority section is RED")
                   (is (str/includes? (:seon.gym.result/actual r)
                                      "gymtest-static")
                       (str "the actual names the volatile section — "
                            (:seon.gym.result/actual r))))
                 ;; layout completeness is an INDEPENDENT check — green.
                 (is (true? (:seon.gym.result/pass?
                              (result-by-id card
                                            :gym.structural/layout-complete))))
                 (is (false? (:seon.gym.scorecard/pass? card))
                     "the structural RED fails the card")
                 (is (false? (get-in card [:seon.gym.scorecard/axes
                                           :context-fidelity]))
                     "the declared structural axis rolls up false")
                 ;; per-turn evidence: turn 1 (pre-install) stable, turn 2
                 ;; (section in place) unstable, with the diff named.
                 (let [[p1 p2] (:seon.gym.scorecard/turn-profiles card)]
                   (is (m/validate :seon.gym/turn-profile p1))
                   (is (true? (:seon.gym.profile/prefix-stable? p1))
                       "turn 1 (before the install) is stable")
                   (is (false? (:seon.gym.profile/prefix-stable? p2))
                       "turn 2 (volatile section live) is unstable")
                   (is (some #{:gymtest-static} (:seon.render/sections p2))
                       "the installed section is in turn 2's layout")
                   (is (str/includes? (str (:seon.gym.profile/prefix-diff p2))
                                      "first diff at char")
                       "the profile carries the byte-level diff detail"))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest stable-static-section-keeps-cache-prefix-green
  ;; U3 falsification, GREEN side: the SAME scenario shape with the
  ;; volatility removed — plus the derived layout-completeness check
  ;; proven against substrate-default-ctx itself (not a hand-list).
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (prefix-stability-scenario
             :gymtest-prefix-stable "gymtest-stable-section"
             "(defn gymtest-stable-section [_] \";; a byte-stable static contribution\")")})
        (.then (fn [card]
                 (gym/print-scorecard! card)
                 (is (true? (:seon.gym.scorecard/pass? card))
                     (str "stable variant passes — failing results: "
                          (pr-str (filterv (complement :seon.gym.result/pass?)
                                           (:seon.gym.scorecard/results card)))))
                 (is (true? (:seon.gym.result/pass?
                              (result-by-id card
                                            :gym.structural/cache-prefix-stable))))
                 (is (true? (get-in card [:seon.gym.scorecard/axes
                                          :context-fidelity])))
                 ;; layout completeness DERIVED from the composer's code:
                 ;; every substrate default name is in every captured layout.
                 (let [defaults (map :seon.ctx/name (ctx/substrate-default-ctx))
                       profiles (:seon.gym.scorecard/turn-profiles card)]
                   (is (= 2 (count profiles)) "one profile per driven turn")
                   (doseq [p profiles]
                     (is (every? (set (:seon.render/sections p)) defaults)
                         "every substrate-default-ctx name is in the layout")))
                 ;; per-section char counts: substrate sections render, and
                 ;; the installed agent section shows up in turn 2's sizes.
                 (let [chars (into {} (:seon.gym.profile/section-chars
                                       (last (:seon.gym.scorecard/turn-profiles
                                              card))))]
                   (is (pos? (get chars :system 0)))
                   (is (pos? (get chars :gymtest-static 0))
                       "the installed section renders into the profile"))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

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
    ;; the same scenario with a PARAPHRASED fixture question loads fine
    (.writeFileSync fs path
                    (pr-str (scenario "A paraphrase, not the verbatim turn text.")))
    (is (seq (:seon.gym/scenarios (gym/load-scenarios! {:seon.gym/path path})))
        "the paraphrased variant loads")
    (.rmSync fs path)))

;; ---------------------------------------------------------------------------
;; CONSULT-PREDICATE ANCHORING (gym-upgrade §3.2) — the s32 consult
;; predicate is anchored on the seeded DOMAIN namespace
;; (:my\.kb\.codebase/), not the broad :my\. — falsified both ways with
;; free stub runs: an unrelated-:my.* first eval scores the anchored
;; predicate RED (while the OLD broad anchor would have scored green —
;; the defect, pinned as a green broad predicate in the same run); a
;; :my.kb.codebase/* first eval scores it green.
;; ---------------------------------------------------------------------------

(defn- consult-anchor-scenario [first-eval]
  {:seon.gym.scenario/id     :gymtest-consult-anchor
   :seon.gym.scenario/doc    "Inline stub (gym-upgrade §3.2 falsification): the anchored consult predicate must track the seeded domain namespace, not any :my.* touch."
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
     :seon.gym.predicate/pattern ":my\\.kb\\.codebase/"}
    ;; the OLD s32 anchor, kept here ONLY to pin the defect shape: it
    ;; goes green on the unrelated-:my.* eval the anchored one rejects.
    {:seon.gym.predicate/id      :broad-my-anchor-the-old-defect
     :seon.gym.predicate/kind    :first-eval-matches
     :seon.gym.predicate/axis    :consults-findings
     :seon.gym.predicate/pattern ":my\\."}]})

(deftest consult-anchor-rejects-unrelated-my-star-touches
  ;; §3.2 falsification, RED side: first eval queries only an unrelated
  ;; :my.* attr — anchored predicate RED, broad (old) predicate green.
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (consult-anchor-scenario
             "(seon.db/query {:seon.db/query '[:find ?v :where [?e :my.agent.scratch/note ?v]]})\n")})
        (.then (fn [card]
                 (gym/print-scorecard! card)
                 (let [anchored (result-by-id
                                  card :first-eval-consults-stored-findings)
                       broad    (result-by-id
                                  card :broad-my-anchor-the-old-defect)]
                   (is (false? (:seon.gym.result/pass? anchored))
                       (str "unrelated :my.* touch scores the anchored "
                            "consult predicate RED — "
                            (:seon.gym.result/actual anchored)))
                   (is (true? (:seon.gym.result/pass? broad))
                       "…while the OLD broad :my\\. anchor passes — the §3.2 defect"))
                 (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest consult-anchor-passes-on-seeded-domain-touch
  ;; §3.2 falsification, GREEN side: first eval queries the seeded
  ;; :my.kb.codebase/* rows — anchored predicate green.
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
   :seon.gym.scenario/doc    "Inline stub scenario proving judge verdict→axis wiring with a mocked judge llm. Also pins the datahike namespace/name query built-ins the S-21 fork predicate relies on."
   :seon.gym.scenario/tier   :stub
   :seon.gym.scenario/status :active
   :seon.gym.scenario/axes   [:replies-honestly :terminates]
   :seon.gym.scenario/turns
   [{:seon.gym.turn/message "What does message! return?"
     :seon.gym.turn/llm-script
     ["(seon.agent/reply! {:seon.agent.message/content \"message! returns the concise envelope {:seon.agent.message/ok? true ...}\"})\n"]}]
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
                                            "at" "hops"]]}
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
           :seon.gym.scenario/doc    "Two stub agents reply distinctly; per-agent judge ctx + args-substituted datalog are scoped correctly."
           :seon.gym.scenario/tier   :stub
           :seon.gym.scenario/status :active
           :seon.gym.scenario/axes   [:replies-honestly :terminates]
           :seon.gym.scenario/turns
           [{:seon.gym.turn/agent   :a
             :seon.gym.turn/message "alpha question"
             :seon.gym.turn/llm-script
             ["(seon.agent/reply! {:seon.agent.message/content \"ALPHA-ANSWER\"})\n"]}
            {:seon.gym.turn/agent   :b
             :seon.gym.turn/message "beta question"
             :seon.gym.turn/llm-script
             ["(seon.agent/reply! {:seon.agent.message/content \"BETA-ANSWER\"})\n"]}]
           :seon.gym.scenario/predicates
           ;; ENGINE BUG FIXED (datahike fork sha 1ae35696, deps commit
           ;; 156a53e — multi-group join corruption fix): a datalog
           ;; query joining TWO identity-attr clauses through one
           ;; message row used to IGNORE the :in ?bid binding and
           ;; return the inverse-direction (user→agent) rows. This is
           ;; the ORIGINAL double-identity-join query, restored as the
           ;; live regression pin for that fix: it must count ONLY b's
           ;; reply to the user.
           [{:seon.gym.predicate/id     :b-sent-exactly-one-user-reply
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
             :seon.gym.predicate/expect [:count 1]}
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
                                (filter #(= :b-sent-exactly-one-user-reply
                                            (:seon.gym.predicate/id %)))
                                first)]
                     (is (true? (:seon.gym.result/pass? r))
                         (str "b-scoped datalog counts only b's reply — "
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
;; Budget guards — deepseek tier and :todo scenarios REFUSE with an
;; error value; the suite can never burn money or run unencoded intent.
;; ---------------------------------------------------------------------------

(deftest deepseek-tier-refuses-without-allow-paid
  (async done
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (load-first "test/seon/gym/scenarios/consults-findings-run8.edn")})
        (.then (fn [resp]
                 (is (false? (:seon.gym/ok? resp))
                     "deepseek scenario refused without allow-paid?")
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
