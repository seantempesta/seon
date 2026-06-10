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
    [malli.core :as m]
    [seon.gym.driver :as gym]))

(def ^:private scenario-files
  ["test/seon/gym/scenarios/envelope-honesty.edn"
   "test/seon/gym/scenarios/blank-message-refusal.edn"
   "test/seon/gym/scenarios/finding-storage-shape.edn"
   "test/seon/gym/scenarios/consults-findings-run8.edn"
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
