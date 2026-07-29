(ns generate-code-v0-drive
  "LIVE PROOF: generate-code v0 on the local model, over one scratch cluster.

  The plan's §5. A human-shaped goal reaches the planner as an ORDINARY
  MESSAGE; the planner's turn is the whole-program attempt; its forms
  freeze carrying the namespace each was written under; every red form
  becomes a problem addressed to that namespace's OWNER; and plan
  settlement is a derivation that does not care what any agent claimed.

  WHAT IS INJECTED AND WHY. Two forms are prepended at the
  PROVIDER-RESPONSE boundary — after the model answers, before the
  splitter sees the text — so the splitter, the freeze, the evaluator
  and the receipt path all handle them exactly as they handle the
  model's own. They call functions that do not exist, one per target
  namespace, which makes the routing observable regardless of whether
  Qwen writes good Clojure on this particular night. Whatever the model
  itself got right or wrong is reported SEPARATELY, as evidence about
  the model, never as evidence about the design.

  Run (Ollama serving qwen3.5:35b-a3b-coding-nvfp4 on 127.0.0.1:11434):
    clojure -M:dev -e \\
      '(load-file \"docs/prds/sci-execution-runtime/research/scripts/generate-code-v0-drive-2026-07-29.clj\")'"
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.work :as work]
            [seon.config :as config]))

(defn- stamp [& parts]
  (println (str "[" (.toString (java.time.LocalTime/now)) "] " (apply str parts)))
  (flush))

(def ^:private !milestones (atom []))

(defn- await-fact
  "Watch until `probe` is truthy, or give up and RECORD the miss.
  A miss never aborts the drive: the evidence of what did happen is the
  most valuable thing a failed proof produces, and a throw here would
  discard it. The exit code reads the milestone ledger at the end."
  [label connection probe attempts]
  (loop [n 0]
    (let [value (probe @connection)]
      (cond
        value (do (stamp "OK   " label)
                  (swap! !milestones conj [label true])
                  value)
        (>= n attempts)
        (do (stamp "MISS " label)
            (swap! !milestones conj [label false])
            nil)
        :else (do (Thread/sleep 1000) (recur (inc n)))))))

;;; ---------------------------------------------------------------------------
;;; The local provider, as CONFIG FACTS
;;; ---------------------------------------------------------------------------

;;; The manifest is the mechanism; injecting it is the workaround.
;;; `cluster/start!` applies `(config/defaults)` and `:seon.boot/overrides`
;;; is closed with no manifest key, so a cluster cannot yet be booted
;;; against a selected manifest at all (filed:
;;; boot-cannot-select-a-config-manifest.md). Overriding the defaults
;;; function makes these ordinary DATABASE FACTS through the ordinary
;;; `config/apply!` reconcile, and the loop handle derives its targets
;;; from them the ordinary way — no second provider path anywhere.
(def local-dials
  {:seon.config.ai/endpoint "http://127.0.0.1:11434/v1/chat/completions"
   :seon.config.ai/model "qwen3.5:35b-a3b-coding-nvfp4"
   :seon.config.ai/no-auth true
   :seon.config.ai/timeout-ms 600000})

;;; ---------------------------------------------------------------------------
;;; The staged failure, injected at the provider-response boundary
;;; ---------------------------------------------------------------------------

(def injected-forms
  (str ";; contract checks the owners are responsible for\n"
       "(ns my.gen.alpha)\n"
       "(alpha-contract-check)\n"
       "(ns my.gen.beta)\n"
       "(beta-contract-check)\n"))

(def ^:private !residue (atom []))

(defn- record-residue!
  [agent-id text]
  (swap! !residue conj {:agent agent-id :text text}))

(defn- prompt-agent
  [prompt]
  (some (fn [agent-id]
          (when (str/includes? prompt (str "You are agent " agent-id))
            agent-id))
        ["planner" "alpha" "beta" "root"]))

(defn wrap-complete
  "The real provider call, with the staged forms prepended for the planner."
  [real]
  (fn [request]
    (let [completion (real request)
          agent-id (prompt-agent (:seon.ai/prompt request))]
      (if-let [text (:seon.ai/text completion)]
        (do (record-residue! agent-id text)
            (if (= "planner" agent-id)
              (assoc completion :seon.ai/text (str injected-forms text))
              completion))
        (do (stamp "PROVIDER FAILURE for " agent-id ": " (pr-str completion))
            completion)))))

;;; ---------------------------------------------------------------------------
;;; Queries — every milestone is a fact
;;; ---------------------------------------------------------------------------

(defn- first-run
  [db agent-id]
  (->> (d/q '[:find ?id ?opened
              :in $ ?agent-id
              :where
              [?run :seon.cluster.run/id ?id]
              [?run :seon.cluster.run/opened-at ?opened]
              [?run :seon.cluster.run/agent ?agent]
              [?agent :seon.cluster.agent/id ?agent-id]]
            db agent-id)
       (sort-by (comp inst-ms second))
       ffirst))

(defn- forms
  [db run-id]
  (->> (d/q '[:find ?ordinal ?source ?namespace-name ?error
              :in $ ?run-id
              :where
              [?run :seon.cluster.run/id ?run-id]
              [?form :seon.cluster.run.form/run ?run]
              [?form :seon.cluster.run.form/ordinal ?ordinal]
              [?form :seon.cluster.run.form/source ?source]
              [(get-else $ ?form :seon.cluster.run.form/ns 0) ?namespace]
              [(get-else $ ?namespace :seon.ns/name 'ABSENT) ?namespace-name]
              [?receipt :seon.cluster.eval/run ?run]
              [?receipt :seon.cluster.eval/ordinal ?ordinal]
              [(get-else $ ?receipt :seon.cluster.eval/error "-") ?error]]
            db run-id)
       (sort-by first)
       vec))

(defn- routed-messages
  [db]
  (->> (d/q '[:find ?problem-id ?from-id ?to-id ?reason
              :where
              [?m :seon.cluster.message/about ?about]
              [?about :seon.problems/id ?problem-id]
              [?m :seon.cluster.message/from ?from]
              [?from :seon.cluster.agent/id ?from-id]
              [?m :seon.cluster.message/to ?to]
              [?to :seon.cluster.agent/id ?to-id]
              [(get-else $ ?m :my.message/reason "-") ?reason]]
            db)
       sort
       vec))

;;; ---------------------------------------------------------------------------
;;; The drive
;;; ---------------------------------------------------------------------------

(def root-dir "tmp/generate-code-v0/clusters")

(stamp "booting scratch cluster generate-code-v0 (never the default)")
(let [dir (java.io.File. root-dir)]
  (when (.exists dir)
    (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))

(def shipped-defaults config/defaults)

(def instance
  (with-redefs [config/defaults
                (fn [] (merge (shipped-defaults) local-dials))]
    (cluster/start! {:seon.boot/cluster-name "generate-code-v0"
                     :seon.boot/root root-dir})))

(def connection (:seon.boot/cluster-connection instance))

(def outcome
  (try
    (stamp "provider dials as FACTS: "
           (pr-str (select-keys (config/effective @connection
                                                  "generate-code-v0")
                                (keys local-dials))))

    ;; THE CAST, through the one formal creation path
    (d/transact connection
                (into []
                      (mapcat (fn [[agent-id namespace-name]]
                                (agent/creation-tx
                                 {:seon.cluster.agent/id agent-id
                                  :seon.ns/name namespace-name})))
                      [["planner" 'my.gen.planner]
                       ["alpha" 'my.gen.alpha]
                       ["beta" 'my.gen.beta]]))
    (stamp "planner, alpha and beta exist, each owning its namespace")

    (with-redefs [ai/complete (wrap-complete ai/complete)]
      ;; ONE goal, as an ordinary message. Everything after it is the
      ;; system's own doing.
      (d/transact
       connection
       [{:seon.cluster.message/id "goal-1"
         :seon.cluster.message/to [:seon.cluster.agent/id "planner"]
         :seon.cluster.message/from [:seon.cluster.agent/id "root"]
         :seon.cluster.message/content
         (str "Write a small widget program across two namespaces. "
              "my.gen.alpha owns the arithmetic: a function that totals "
              "a sequence of widget counts. my.gen.beta owns the label: a "
              "function that renders a total as a sentence. Write the "
              "code with an (ns …) form before each namespace's "
              "functions, then pause with my.run/wait — the namespace "
              "owners will answer anything you could not finish.")
         :seon.cluster.message/at (java.util.Date.)}])
      (stamp "TRIGGER — root asked the planner for a two-namespace program")

      (def planner-run
        ^{:doc "the attempt every later query is about"}
        (await-fact "the planner froze a plan" connection
                    (fn [db]
                      (when-let [run-id (first-run db "planner")]
                        (when (d/q '[:find ?d . :in $ ?run-id :where
                                     [?run :seon.cluster.run/id ?run-id]
                                     [?run :seon.cluster.run/plan-digest ?d]]
                                   db run-id)
                          run-id)))
                    600))

      (await-fact "both owners were assigned their own red form" connection
                  (fn [db]
                    (= #{"alpha" "beta"}
                       (set (map (fn [[_ _ to _]] to)
                                 (routed-messages db)))))
                  600)

      (await-fact "an owner declined, naming the problem" connection
                  (fn [db]
                    (seq (filter (fn [[_ _ _ reason]] (not= "-" reason))
                                 (routed-messages db))))
                  900))

    ;; ------------------------------------------------------------------
    ;; THE EVIDENCE
    ;; ------------------------------------------------------------------
    (let [db @connection]
      (println)
      (stamp "=== THE PLANNER'S FROZEN PLAN (ordinal, ns, source, error) ===")
      (doseq [[ordinal source namespace-name error] (forms db planner-run)]
        (println (format "  %2d  %-16s %-58s %s"
                         ordinal
                         (str namespace-name)
                         (str/replace (subs source 0 (min 58 (count source)))
                                      #"\s+" " ")
                         (str/replace (subs error 0 (min 60 (count error)))
                                      #"\s+" " "))))
      (println)
      (stamp "=== ROUTED PROBLEMS (problem, from, to, decline reason) ===")
      (doseq [row (routed-messages db)]
        (println "  " (pr-str row)))
      (println)
      (stamp "=== PLAN SETTLEMENT (derived, transacts nothing) ===")
      (let [before (:max-tx db)
            settlement (work/plan-settlement db planner-run)
            after (:max-tx @connection)]
        (pp/pprint (update settlement :seon.cluster.work/forms
                           (fn [rows]
                             (mapv #(select-keys
                                     % [:seon.cluster.run.form/ordinal
                                        :seon.cluster.agent/id
                                        :seon.cluster.work/form-state])
                                   rows))))
        (stamp "max-tx before/after the derivation: " before " / " after))
      (println)
      (stamp "=== RUNS AND RECEIPTS PER AGENT ===")
      (doseq [agent-id ["planner" "alpha" "beta"]]
        (when-let [run-id (first-run db agent-id)]
          (println " " agent-id run-id)
          (doseq [[ordinal source _ error] (forms db run-id)]
            (println (format "      %2d %-56s %s" ordinal
                             (str/replace (subs source 0 (min 56 (count source)))
                                          #"\s+" " ")
                             (str/replace (subs error 0 (min 40 (count error)))
                                          #"\s+" " "))))))
      (println)
      (stamp "=== ERROR FACTS ===")
      (doseq [row (d/q '[:find ?kind ?message :where
                         [?e :seon.error/kind ?kind]
                         [?e :seon.error/message ?message]] db)]
        (println "  " (pr-str row)))
      (println)
      (stamp "=== MODEL RESIDUE (evidence about Qwen, not about the design) ===")
      (doseq [{:keys [agent text]} @!residue]
        (println (str "  --- " agent " ---"))
        (println (str "  " (str/replace text #"\n" "\n  "))))
      (println)
      (stamp "=== MILESTONES ===")
      (doseq [[label ok?] @!milestones]
        (println (format "  %-4s %s" (if ok? "OK" "MISS") label))))
    (if (every? second @!milestones) :complete :incomplete)
    (catch Throwable failure
      (stamp "DRIVE FAILED: " (ex-message failure))
      (stamp "runs so far: "
             (pr-str (d/q '[:find ?agent-id ?run-id :where
                            [?run :seon.cluster.run/id ?run-id]
                            [?run :seon.cluster.run/agent ?a]
                            [?a :seon.cluster.agent/id ?agent-id]]
                          @connection)))
      (stamp "errors so far: "
             (pr-str (d/q '[:find ?kind ?message :where
                            [?e :seon.error/kind ?kind]
                            [?e :seon.error/message ?message]]
                          @connection)))
      (stamp "residue so far: " (pr-str @!residue))
      failure)
    (finally
      (stamp "TEARDOWN")
      (cluster/stop! instance))))

(if (= :complete outcome)
  (do (stamp "GENERATE-CODE v0 DRIVE COMPLETE") (System/exit 0))
  (System/exit 1))
