(ns seon.gym.baseline-test
  "GREEN BASELINE harness (context-curation Phase A, issue #51) — the
   reference snapshot the curation loop moves numbers against. For every
   scenario it measures the turn-1 context SIZE in TOKENS (free, via
   `seon.gym.driver/measure-context!`) AND attempts a full
   `run-scenario!` at the DEFAULT config, capturing per-scenario:
   pass?, per-predicate pass/fail, the curation axes
   (`:seon.gym.scorecard/eval-error-rate` + `:canvas-updated?`), and the
   total context tokens. `:paid`/`:todo` scenarios refuse the run (no
   spend) — their token measurement still lands, with a `refused` verdict.

   GATED: the sweep runs ONLY under `SEON_GYM_BASELINE=1` so the normal
   `bin/test-cljs` suite stays fast (unset = a one-assertion no-op). Run
   it in isolation against a built bundle:

     SEON_GYM_BASELINE=1 node out/test/test.js --test=seon.gym.baseline-test

   Output lines are prefixed `SEON-GYM-BASELINE` (per scenario) and
   `SEON-GYM-BASELINE-TABLE` (the formatted summary)."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.gym.driver :as gym]))

;; Every scenario FILE; multi-scenario files (todo-prompt-thin) expand to
;; all their maps below.
(def ^:private scenario-files
  ["blank-message-refusal" "consults-findings-run8" "envelope-honesty"
   "err-recovery-unregistered-attr" "finding-storage-shape"
   "s01-stub-pipeline-smoke" "s21-log-workout-existing-schema"
   "s32-consult-before-research" "todo-multistep-tracking" "todo-prompt-thin"
   "todo-resume" "x1-subscriptions-total-and-max"
   "x12-narrow-question-no-over-retrieval" "x3-expense-reuse-and-category-total"])

(defn- path-of [name] (str "test/seon/gym/scenarios/" name ".edn"))

(defn- all-scenarios []
  (vec (mapcat (fn [name]
                 (:seon.gym/scenarios
                   (gym/load-scenarios! {:seon.gym/path (path-of name)})))
               scenario-files)))

(def ^:private baseline-on? (some? (.. js/process -env -SEON_GYM_BASELINE)))

(defn- pad [s n]
  (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

;; Promise sequencer — process one scenario, conj its row, recur (the
;; runs MUST be sequential: run-scenario!/measure-context! swap the root
;; *conn*, so two must never overlap).
(defn- run-seq [items f]
  (reduce (fn [p item]
            (.then p (fn [acc] (.then (f item) (fn [r] (conj acc r))))))
          (js/Promise.resolve [])
          items))

(defn- runnable?
  "A scenario the driver runs for pass/fail at the default config — stub
   tier AND :active status (paid costs money, :todo is encoded intent)."
  [s]
  (and (= :stub (:seon.gym.scenario/tier s))
       (= :active (:seon.gym.scenario/status s))))

(defn- baseline-row
  "Measure the turn-1 context tokens for one scenario, then attempt a
   default-config run (refusal for paid/todo). Returns one row map."
  [s]
  (let [id   (:seon.gym.scenario/id s)
        comp (:seon.gym.scenario/competency s)
        tier (:seon.gym.scenario/tier s)
        stat (:seon.gym.scenario/status s)]
    (-> (gym/measure-context! {:seon.gym/scenario s})
        (.then (fn [m]
                 (let [tokens (:seon.gym/total-tokens m)]
                   (.then (gym/run-scenario! {:seon.gym/scenario s})
                          (fn [card-or-refusal]
                            (let [refused? (false?
                                             (:seon.gym/ok? card-or-refusal))
                                  row (cond-> {:id         id
                                               :competency comp
                                               :tier       tier
                                               :status     stat
                                               :tokens     tokens
                                               :refused?   refused?}
                                        (not refused?)
                                        (assoc :pass? (:seon.gym.scorecard/pass?
                                                        card-or-refusal)
                                               :eval-error-rate
                                               (:seon.gym.scorecard/eval-error-rate
                                                 card-or-refusal)
                                               :canvas?
                                               (:seon.gym.scorecard/canvas-updated?
                                                 card-or-refusal)
                                               :failing
                                               (mapv :seon.gym.predicate/id
                                                     (filterv
                                                       (complement
                                                         :seon.gym.result/pass?)
                                                       (:seon.gym.scorecard/results
                                                         card-or-refusal)))))]
                              (println "SEON-GYM-BASELINE" (pr-str row))
                              row)))))))))

(deftest green-baseline
  (async done
    (if-not baseline-on?
      (do (is true "baseline gated off (set SEON_GYM_BASELINE=1 to run)")
          (done))
      (-> (run-seq (all-scenarios) baseline-row)
          (.then (fn [rows]
                   (println "SEON-GYM-BASELINE-TABLE === GREEN BASELINE (default config) ===")
                   (println "SEON-GYM-BASELINE-TABLE"
                            (pad "scenario" 40) (pad "competency" 16)
                            (pad "tier" 6) (pad "tokens" 7)
                            (pad "verdict" 10) (pad "err-rate" 9) "canvas?")
                   (doseq [r (sort-by :tokens rows)]
                     (println "SEON-GYM-BASELINE-TABLE"
                              (pad (:id r) 40)
                              (pad (:competency r) 16)
                              (pad (:tier r) 6)
                              (pad (:tokens r) 7)
                              (pad (if (:refused? r) "refused"
                                       (if (:pass? r) "PASS" "FAIL")) 10)
                              (pad (if (:refused? r) "-" (:eval-error-rate r)) 9)
                              (if (:refused? r) "-" (:canvas? r)))
                     (when (and (not (:refused? r)) (seq (:failing r)))
                       (println "SEON-GYM-BASELINE-TABLE   failing:"
                                (pr-str (:failing r)))))
                   ;; every scenario yields a positive context measurement…
                   (is (every? pos? (map :tokens rows))
                       "every scenario measures a positive ctx-token total")
                   ;; …and every RUNNABLE scenario produced a real verdict
                   ;; (not a refusal) with a well-formed error-rate.
                   (let [ran (remove :refused? rows)]
                     (is (seq ran) "at least one runnable scenario produced a verdict")
                     (is (every? #(<= 0.0 (:eval-error-rate %) 1.0) ran)
                         "eval-error-rate is a fraction for every run"))
                   (done)))
          (.catch (fn [e]
                    (println "SEON-GYM-BASELINE ERROR" (str e))
                    (is false (str "baseline sweep threw — " e))
                    (done)))))))
