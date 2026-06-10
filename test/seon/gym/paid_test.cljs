(ns seon.gym.paid-test
  "PAID deepseek-tier gym runs — the live behavioral measurements.

   GATED on the SEON_GYM_PAID env var (comma-separated scenario keys,
   or `all`): without it every test here is a no-op, so `bin/test-cljs`
   never burns money. With it, the named scenarios run against the REAL
   DeepSeek adapter (+ the DeepSeek judge for :llm-judge predicates)
   under {:seon.gym/allow-paid? true}.

   These tests assert ONLY that a validated scorecard came back — they
   deliberately do NOT assert :seon.gym.scorecard/pass?. Honest reds
   ARE the deliverable (the gym pins target behavior, catalog §6); the
   scorecard lines (`SEON-GYM SCORECARD …`, greppable via bin/gym) are
   the measurement.

   Run:  bin/gym --paid=s32,s21,s12   (or SEON_GYM_PAID=all bin/test-cljs)"
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [malli.core :as m]
    [seon.gym.driver :as gym]))

(defn- enabled? [k]
  (let [v (str (or (.. js/process -env -SEON_GYM_PAID) ""))]
    (boolean (and (seq v)
                  (or (= v "all")
                      (some #(= % (name k)) (str/split v #",")))))))

(defn- run-paid! [k path done]
  (if-not (enabled? k)
    (do (is true (str k " skipped — set SEON_GYM_PAID=" (name k)
                      " (or all) to run the paid tier"))
        (done))
    (-> (gym/run-scenario!
          {:seon.gym/scenario
           (first (:seon.gym/scenarios
                    (gym/load-scenarios! {:seon.gym/path path})))
           :seon.gym/allow-paid? true})
        (.then (fn [resp]
                 (if (false? (:seon.gym/ok? resp))
                   (is false (str path " refused — " (:seon.gym/error resp)))
                   (do (gym/print-scorecard! resp)
                       (is (m/validate :seon.gym/scorecard resp)
                           (str path " produced a valid scorecard "
                                "(pass/fail NOT asserted — honest reds "
                                "are the data)"))))
                 (done)))
        (.catch (fn [e] (is false (str path " threw — " e)) (done))))))

(deftest s32-consult-before-research-paid
  (async done
    (run-paid! :s32
               "test/seon/gym/scenarios/s32-consult-before-research.edn"
               done)))

(deftest s21-log-workout-existing-schema-paid
  (async done
    (run-paid! :s21
               "test/seon/gym/scenarios/s21-log-workout-existing-schema.edn"
               done)))

(deftest s12-run8-two-agent-consultation-paid
  (async done
    (run-paid! :s12
               "test/seon/gym/scenarios/consults-findings-run8.edn"
               done)))
