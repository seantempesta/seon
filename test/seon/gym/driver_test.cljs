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
    [seon.gym.driver :as gym]))

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
  ;; The §7 item-12 rubric, verbatim — a predicate tagged outside this
  ;; vocabulary must fail schema validation at load time.
  (is (m/validate :seon.gym.axis/name :consults-findings))
  (is (m/validate :seon.gym.axis/name :stores-proactively))
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
     ["(seon.agent/reply! {:seon.message/content \"message! returns the concise envelope {:seon.message/ok? true ...}\"})\n"]}]
   :seon.gym.scenario/predicates
   [{:seon.gym.predicate/id     :turn-closes-done
     :seon.gym.predicate/kind   :datalog
     :seon.gym.predicate/axis   :terminates
     :seon.gym.predicate/query  '[:find ?t :where [?t :seon.turn/status :done]]
     :seon.gym.predicate/expect :non-empty}
    ;; live proof for the datalog built-ins S-21's fork predicate uses
    {:seon.gym.predicate/id     :namespace-name-builtins-resolve
     :seon.gym.predicate/kind   :datalog
     :seon.gym.predicate/axis   :terminates
     :seon.gym.predicate/query  '[:find ?n
                                  :where
                                  [?m :seon.message/content _]
                                  [?m ?a _]
                                  [(namespace ?a) ?ns]
                                  [(= ?ns "seon.message")]
                                  [(name ?a) ?n]]
     :seon.gym.predicate/expect [:every-in ["id" "from" "to" "content"
                                            "at" "hops"]]}
    {:seon.gym.predicate/id        :judge-mock
     :seon.gym.predicate/kind      :llm-judge
     :seon.gym.predicate/axis      :replies-honestly
     :seon.gym.predicate/rubric    "Reply must state the concise envelope."
     :seon.gym.predicate/reference "message! returns {:seon.message/ok? true ...} — never the raw tx-report."}]})

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
             ["(seon.agent/reply! {:seon.message/content \"ALPHA-ANSWER\"})\n"]}
            {:seon.gym.turn/agent   :b
             :seon.gym.turn/message "beta question"
             :seon.gym.turn/llm-script
             ["(seon.agent/reply! {:seon.message/content \"BETA-ANSWER\"})\n"]}]
           :seon.gym.scenario/predicates
           ;; PINNED ENGINE BUG (datahike-cljs, observed 2026-06-10):
           ;; a datalog query joining TWO identity-attr clauses through
           ;; one message row —
           ;;   [?ag :seon.agent/id ?bid] [?m :seon.message/from ?ag]
           ;;   [?m :seon.message/to ?u]  [?u :seon.user/id "user"]
           ;; — IGNORES the :in ?bid binding and returns the
           ;; inverse-direction (user→agent) rows, regardless of clause
           ;; order. Single-identity-join queries bind correctly. Gym
           ;; predicates therefore use from-agent + hops>0 ("a message
           ;; the agent sent onward") instead of the user-entity join.
           [{:seon.gym.predicate/id     :b-sent-exactly-one-user-reply
             :seon.gym.predicate/kind   :datalog
             :seon.gym.predicate/axis   :replies-honestly
             :seon.gym.predicate/args   [:seon.gym.agent/b]
             :seon.gym.predicate/query  '[:find ?m ?c
                                          :in $ ?bid
                                          :where
                                          [?ag :seon.agent/id ?bid]
                                          [?m :seon.message/from ?ag]
                                          [?m :seon.message/hops ?h]
                                          [(pos? ?h)]
                                          [?m :seon.message/content ?c]]
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
