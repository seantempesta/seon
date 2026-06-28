(ns seon.gym.measure-test
  "Context-size SWEEP harness — the free probe of the context-improvement
   loop. For every scenario it seeds the world + boots a fresh agent and
   measures the turn-1 context SIZE in TOKENS (via
   `seon.gym.driver/measure-context!`), WITHOUT spending on the LLM, then
   prints a greppable table. The A/B arm re-measures a memory + a planning
   scenario under `:default` (full) vs `:minimal` vs a lean
   manifest that drops `:live-tile` but keeps `:namespaces`.

   GATED: the heavy sweep runs ONLY under `SEON_GYM_MEASURE=1` so the
   normal `bin/test-cljs` suite stays fast (unset = a one-assertion
   no-op). Run the sweep in isolation against a built bundle:

     SEON_GYM_MEASURE=1 node out/test/test.js --test=seon.gym.measure-test

   Output lines are prefixed `SEON-GYM-MEASURE` (per scenario/arm) and
   `SEON-GYM-MEASURE-TABLE` (the formatted summary)."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.gym.driver :as gym]))

(def ^:private all-scenarios
  ["blank-message-refusal" "consults-findings-run8" "envelope-honesty"
   "err-recovery-unregistered-attr" "finding-storage-shape"
   "s01-stub-pipeline-smoke" "s21-log-workout-existing-schema"
   "s32-consult-before-research" "todo-multistep-tracking" "todo-prompt-thin"
   "todo-resume" "x1-subscriptions-total-and-max"
   "x12-narrow-question-no-over-retrieval" "x3-expense-reuse-and-category-total"])

(defn- path-of [name] (str "test/seon/gym/scenarios/" name ".edn"))

(defn- load-first [path]
  (first (:seon.gym/scenarios (gym/load-scenarios! {:seon.gym/path path}))))

(def ^:private measure-on? (some? (.. js/process -env -SEON_GYM_MEASURE)))

(defn- pad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

;; Promise sequencer — measure one item, conj its result, recur (avoids
;; flooding the event loop by running all seedings in parallel).
(defn- measure-seq [items measure-one]
  (reduce (fn [p item]
            (.then p (fn [acc]
                       (.then (measure-one item)
                              (fn [r] (conj acc r))))))
          (js/Promise.resolve [])
          items))

(deftest context-size-sweep
  (async done
    (if-not measure-on?
      (do (is true "measure sweep gated off (set SEON_GYM_MEASURE=1 to run)")
          (done))
      (let [measure-default
            (fn [name]
              (.then (gym/measure-context!
                       {:seon.gym/scenario (load-first (path-of name))})
                     (fn [r]
                       (println "SEON-GYM-MEASURE" name ":default"
                                "total=" (:seon.gym/total-tokens r)
                                "blocks=" (pr-str (:seon.gym.profile/blocks
                                                   (:seon.gym/turn-profile r))))
                       (assoc r :name name))))
            ab-arms
            [["x3-expense-reuse-and-category-total" "memory"]
             ["finding-storage-shape" "memory"]
             ["todo-multistep-tracking" "planning"]]
            measure-arm
            (fn [[name kind]]
              (let [s (load-first (path-of name))
                    run (fn [label cfg]
                          (.then (gym/measure-context!
                                   (cond-> {:seon.gym/scenario s}
                                     cfg (assoc :seon.gym/config cfg)))
                                 (fn [r]
                                   (println "SEON-GYM-MEASURE-AB" name kind label
                                            "total=" (:seon.gym/total-tokens r)
                                            "blocks=" (pr-str
                                                       (:seon.gym.profile/blocks
                                                        (:seon.gym/turn-profile r))))
                                   {:label label :total (:seon.gym/total-tokens r)})))]
                (.then (run "default" nil)
                       (fn [a]
                         (.then (run "minimal"
                                     {:seon.gym.config/profile :minimal})
                                (fn [b]
                                  (.then (run "lean-no-live-tile"
                                              {:seon.gym.config/path
                                               "test/seon/gym/configs/lean-no-live-tile.edn"})
                                         (fn [c]
                                           {:name name :kind kind :arms [a b c]}))))))))]
        (-> (measure-seq all-scenarios measure-default)
            (.then (fn [base]
                     (println "SEON-GYM-MEASURE-TABLE === BASELINE (turn-1 ctx tokens, :default) ===")
                     (doseq [r (sort-by :seon.gym/total-tokens base)]
                       (println "SEON-GYM-MEASURE-TABLE"
                                (pad (:name r) 42)
                                (pad (:seon.gym/total-tokens r) 7)))
                     (is (every? pos? (map :seon.gym/total-tokens base))
                         "every scenario yields a positive ctx-token measurement")
                     base))
            (.then (fn [_] (measure-seq ab-arms measure-arm)))
            (.then (fn [ab]
                     (println "SEON-GYM-MEASURE-TABLE === A/B (ctx tokens by config) ===")
                     (doseq [{:keys [name kind arms]} ab]
                       (let [byl (into {} (map (juxt :label :total)) arms)
                             d (get byl "default") m (get byl "minimal")
                             l (get byl "lean-no-live-tile")]
                         (println "SEON-GYM-MEASURE-TABLE" (pad name 38) kind
                                  "default=" (pad d 7) "minimal=" (pad m 7)
                                  "lean=" (pad l 7)
                                  "Δmin=" (- d m) "Δlean=" (- d l))
                         (is (<= m d) (str name " :minimal not larger than default"))
                         (is (<= l d) (str name " lean not larger than default"))))
                     (done)))
            (.catch (fn [e]
                      (println "SEON-GYM-MEASURE ERROR" (str e))
                      (is false (str "sweep threw — " e))
                      (done))))))))
