(ns seon.gym.database-memory-drive-test
  "TEMPORARY k=2 measurement harness for the DATABASE-MEMORY competency
   drive (2026-06-29). Drives THREE memory-discipline scenarios twice
   each against the real DeepSeek adapter + judge and dumps the FULL
   per-run scorecards (all predicate :actual blobs — the agent's real
   store/consult evals + stored rows) to tmp/ for observation.

   Gated on SEON_GYM_PAID containing `dbmem` (or `all`). Costs real
   money. Set SEON_AI_PROVIDER=deepseek so the world-parity ai/sync!
   steers the drive + judge onto DeepSeek.

   Run:  SEON_GYM_PAID=dbmem SEON_AI_PROVIDER=deepseek bin/test-cljs

   This ns is a measurement instrument, not a permanent regression — it
   prints `SEON-GYM DBMEM-PASSK` lines and writes
   tmp/dbmem-<scenario>.edn. Delete after the drive is recorded."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.gym.driver :as gym]))

(defn- gate []
  (str (or (.. js/process -env -SEON_GYM_PAID) "")))

(defn- enabled?
  "True when SEON_GYM_PAID names this scenario `token` (or `dbmem` =
   all three, or `all`). Per-scenario tokens let each paid scenario run
   in its OWN fresh process — the schema-registry pollution where the
   first paid scenario re-registers :seon.agent.message/to card-one and
   the finally-reap (drops only NEWLY-minted keys) never restores it,
   so every LATER paid scenario fails to land its user message."
  [token]
  (let [g    (gate)
        toks (set (str/split g #","))]
    (boolean (and (seq g)
                  (or (= g "all")
                      (toks "dbmem")
                      (toks token))))))

(defn- force-deepseek! []
  ;; ai-test mutates/deletes SEON_AI_* in its finally blocks — re-assert
  ;; before every drive so the world-parity ai/sync! steers DeepSeek.
  (aset (.. js/process -env) "SEON_AI_PROVIDER" "deepseek"))

(def ^:private scenarios
  [[:finding-storage-shape
    "test/seon/gym/scenarios/finding-storage-shape.edn"]
   [:s32-consult-before-research
    "test/seon/gym/scenarios/s32-consult-before-research.edn"]
   [:s12-run8-two-agent-consultation
    "test/seon/gym/scenarios/consults-findings-run8.edn"]])

(def ^:private k 2)

(defn- pass-summary
  "Per-scenario pass^k roll: mechanical pass-rate + judge-pass-rate +
   per-predicate pass tallies across the k cards."
  [id cards]
  (let [mech-passes  (count (filter :seon.gym.scorecard/pass? cards))
        judge-passes (count (filter :seon.gym.scorecard/judge-pass? cards))
        per-pred     (->> cards
                          (mapcat :seon.gym.scorecard/results)
                          (group-by :seon.gym.predicate/id)
                          (into (sorted-map)
                                (map (fn [[pid rs]]
                                       [pid (str (count (filter :seon.gym.result/pass? rs))
                                                 "/" (count rs))]))))
        err-rates    (mapv :seon.gym.scorecard/eval-error-rate cards)]
    {:seon.gym.dbmem/scenario        id
     :seon.gym.dbmem/k               (count cards)
     :seon.gym.dbmem/mech-pass-rate  (/ (double mech-passes) (count cards))
     :seon.gym.dbmem/judge-pass-rate (/ (double judge-passes) (count cards))
     :seon.gym.dbmem/per-predicate   per-pred
     :seon.gym.dbmem/eval-error-rates err-rates}))

(defn- ^:async drive-k! [path]
  ;; Sequential k runs (run-scenario! swaps the root conn — never overlap).
  (loop [i k acc []]
    (if (pos? i)
      (do (force-deepseek!)
          (let [card (await (gym/run-scenario!
                              {:seon.gym/scenario
                               (first (:seon.gym/scenarios
                                        (gym/load-scenarios! {:seon.gym/path path})))
                               :seon.gym/allow-paid? true}))]
            (recur (dec i) (conj acc card))))
      acc)))

(defn- run-scenario-k! [id path done]
  (-> (drive-k! path)
      (.then (fn [cards]
               (let [summary (pass-summary id cards)]
                 (.writeFileSync (js/require "node:fs")
                                 (str "tmp/dbmem-" (name id) ".edn")
                                 (pr-str {:seon.gym.dbmem/summary summary
                                          :seon.gym.dbmem/cards   cards}))
                 (println "SEON-GYM DBMEM-PASSK" (pr-str summary))
                 (doseq [c cards] (gym/print-scorecard! c))
                 (is (= k (count cards))
                     (str (name id) " produced " k " scorecards"))
                 (done))))
      (.catch (fn [e]
                (is false (str path " threw — " e))
                (done)))))

(deftest finding-storage-shape-k2
  (async done
    (if-not (enabled? "finding-storage-shape")
      (do (is true "dbmem skipped — set SEON_GYM_PAID=finding-storage-shape") (done))
      (run-scenario-k! :finding-storage-shape
                       "test/seon/gym/scenarios/finding-storage-shape.edn"
                       done))))

(deftest s32-consult-before-research-k2
  (async done
    (if-not (enabled? "s32-consult-before-research")
      (do (is true "dbmem skipped — set SEON_GYM_PAID=s32-consult-before-research") (done))
      (run-scenario-k! :s32-consult-before-research
                       "test/seon/gym/scenarios/s32-consult-before-research.edn"
                       done))))

(deftest s12-run8-two-agent-consultation-k2
  (async done
    (if-not (enabled? "s12-run8-two-agent-consultation")
      (do (is true "dbmem skipped — set SEON_GYM_PAID=s12-run8-two-agent-consultation") (done))
      (run-scenario-k! :s12-run8-two-agent-consultation
                       "test/seon/gym/scenarios/consults-findings-run8.edn"
                       done))))
