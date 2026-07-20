(ns seon.execution.b2-driver
  "B2-EXPERIMENTAL production-anchoring A/B measurement driver.

   Spawns the PRODUCTION execution host against an explicit execution
   artifact (the normal self-host child or the sci child) on a BRANCH
   database, creates one real agent, opens one real run, and drives real
   turns through seon.agent.turn/run-turn! with a scripted llm-fn (the
   turn/eval path is the measurement subject, not the LLM). Samples
   `vmmap` on the child pid at named phases and emits an EDN result file.

   Mirrors the maintained :execution-integration-client driver pattern.
   Harness code (tmp/sci-probe/exec-src); never a production entrypoint."
  (:require
   [cljs.reader :as reader]
   [seon.agent :as agent]
   [seon.agent.run :as run]
   [seon.agent.turn :as turn]
   [seon.db :as db]
   [seon.db.branch :as db.branch]
   [seon.db.protocol :as protocol]
   [seon.error :as error]
   [seon.execution :as execution]
   [seon.execution.host :as host]
   [seon.execution.runtime]
   [seon.launch :as launch]
   [seon.runtime.admission :as admission]))

;;; ------------------------------------------------------------------
;;; Launch descriptor for one explicit artifact on the branch database
;;; ------------------------------------------------------------------

(defn- descriptor
  [{:b2/keys [socket-path database-name database-path connection-id
              execution-output execution-build-id execution-digest label]}]
  (let [base
        (launch/default-descriptor
         {::launch/cluster-dir (str "tmp/b2-" label)
          ::launch/artifact-flavor :seon.dev.artifact.flavor/default
          ::launch/client-build-id "client"
          ::launch/execution-build-id execution-build-id
          ::launch/execution-output execution-output
          ::launch/request-socket-path socket-path
          ::launch/writer-repl-port-file (str "tmp/b2-" label ".writer.port")
          ::launch/process-dir (str "tmp/b2-" label "-processes")
          ::launch/log-dir (str "logs/b2-" label)
          ::launch/http-port 0
          ::launch/http-port-file (str "tmp/b2-" label ".http.port")})
        branch-descriptor
        (assoc base ::launch/database
               (cond-> (assoc (::launch/database base)
                              ::protocol/database-name database-name
                              ::protocol/backend :file)
                 database-path
                 (assoc ::protocol/database-path database-path)
                 connection-id
                 (assoc ::db.branch/connection-id connection-id)))]
    (launch/with-execution-artifact
     {::launch/descriptor branch-descriptor
      ::launch/execution-build-id execution-build-id
      ::launch/execution-output execution-output
      ::launch/execution-digest execution-digest})))

;;; ------------------------------------------------------------------
;;; vmmap phase sampling
;;; ------------------------------------------------------------------

(defn- child-pid []
  (some-> (host/processes) first :seon.execution.host/pid))

(defn- write-file! [path text]
  (let [fs (js/require "node:fs")]
    (.mkdirSync fs (.dirname (js/require "node:path") path) #js {:recursive true})
    (.writeFileSync fs path text)))

(defn- vmmap! [out-dir label phase]
  (when-let [pid (child-pid)]
    (let [result (js/Bun.spawnSync
                  #js ["vmmap" "--summary" (str pid)]
                  #js {:stdout "pipe" :stderr "pipe"})
          text (str (.-stdout result))]
      (write-file! (str out-dir "/" label "-" phase ".vmmap") text)
      {:b2/phase phase :b2/pid pid
       :b2/footprint
       (some->> (re-find #"(?m)^Physical footprint:\s+(\S+)" text) second)
       :b2/footprint-peak
       (some->> (re-find #"(?m)^Physical footprint \(peak\):\s+(\S+)" text)
                second)})))

(defn- sleep [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

;;; ------------------------------------------------------------------
;;; The scripted turn workload (identical for both artifacts)
;;; ------------------------------------------------------------------

(def ^:private burst-turn-index 10)

(defn- burst-reply []
  (str ";; heavy eval burst: 100 defns + 100 calls + a large data hold\n"
       (apply str
              (for [i (range 100)]
                (str "(defn burst-fn-" i " [x] (+ x " i "))\n")))
       (apply str
              (for [i (range 100)]
                (str "(burst-fn-" i " " i ")\n")))
       "(def burst-data (vec (range 50000)))\n"
       "(count burst-data)\n"))

(defn- reply-for-turn
  "One scripted model reply per turn: my.plan-shaped work, schema'd facts
   with db round-trips, defn + reuse, and one heavy burst turn."
  [turn-index]
  (cond
    (= :final-gc turn-index)
    ";; settle\n(js/Bun.gc true)\n(+ 1 2)\n"

    (= 1 turn-index)
    (str ";; plan the work and persist first facts\n"
         "(defn b2-double [x] (* 2 x))\n"
         "(b2-double 21)\n"
         "(db/transact! {:seon.db/tx-data [{:seon.agent.message/id \"b2-fact-seed\" :seon.agent.message/content \"b2 seed fact\"}]})\n")

    (= burst-turn-index turn-index)
    (burst-reply)

    (= (inc burst-turn-index) turn-index)
    (str ";; force gc after the burst\n"
         "(js/Bun.gc true)\n"
         "(burst-fn-42 1)\n")

    (odd? turn-index)
    (str ";; define + reuse across turns\n"
         "(defn b2-step-" turn-index " [x] (+ (b2-double x) " turn-index "))\n"
         "(b2-step-" turn-index " " turn-index ")\n"
         "(reduce + (map b2-double (range 1000)))\n")

    :else
    (str ";; db round-trip turn\n"
         "(db/query {:seon.db/query '[:find (count ?a) . :where [?a :seon.agent/id]]})\n"
         "(->> (range 500) (map inc) (filter odd?) count)\n")))

;;; ------------------------------------------------------------------
;;; Drive
;;; ------------------------------------------------------------------

(defn- emit! [value] (println (pr-str value)))

(defn- ^:async admit!
  "The driver process needs its own admission (message/turn writes gate on
   it) — the same production prepare/admit pair the child boot runs."
  []
  (let [prepared (await
                  (error/with-configuration
                   {:seon.config/on-core-error :gate}
                   #(admission/prepare-committed!
                     {:seon.runtime.admission/record-failures? false
                      :seon.runtime.admission/instrument? false})))
        publication (await (admission/admit-prepared! prepared))]
    (when-not (:seon.runtime.admission/published? publication)
      (throw (ex-info "B2 driver admission failed."
                      {:b2/publication publication})))
    publication))

(defn- ^:async run-drive!
  [{:b2/keys [label out-dir turns] :as options}]
  (let [phases (volatile! [])
        turn-timings (volatile! [])
        phase! (fn [phase]
                 (when-let [sample (vmmap! out-dir label phase)]
                   (vswap! phases conj sample))
                 (emit! {:b2/phase-done phase}))]
    (host/configure!
     {::host/launch-descriptor (descriptor options)
      ::host/javascript-runtime js/process.execPath
      ::host/ready-timeout-ms 60000
      ::host/idle-timeout-ms 600000
      ::host/cancel-grace-ms 1000})
    (let [created (await (agent/mint!
                          {:seon.agent/purpose (str "B2 " label " drive")}))
          _ (when (:seon.error/message created)
              (throw (ex-info "agent create failed" created)))
          agent-id (:seon.agent/id created)
          ;; The cluster config may select repl-mode :stream (first-form-only
          ;; turns); the drive measures full batches, so pin the agent dial.
          mode-set (await (db/transact!
                           {:seon.db/tx-data
                            [{:seon.agent/id agent-id
                              :seon.config/repl-mode :batch}]}))
          _ (when (:seon.error/message mode-set)
              (throw (ex-info "repl-mode pin failed" mode-set)))
          opened (await (run/open-run!
                         {:seon.agent/id agent-id
                          :seon.agent.run/trigger :message
                          :seon.agent.run/turn-limit 1000}))
          _ (when (:seon.error/message opened)
              (throw (ex-info "open-run! failed" opened)))
          run-id (:seon.agent.run/id opened)
          ;; P1 — child boot + session + admission + engine init + home ns:
          ;; one empty eval batch spawns the child and initializes its
          ;; engine without evaluating any form.
          database (await (db/db))
          warm (await
                (host/invoke-compiled!
                 database agent-id 'seon.execution.runtime/eval-batch!
                 [{:seon.eval/parsed []
                   :seon.eval/starting-ns (symbol (str "my.agent." agent-id))
                   :seon.agent.turn/id-of-turn (str "b2-warm-" label)}]))
          _ (when (= execution/error-message (::execution/message warm))
              (throw (ex-info "warm eval-batch failed"
                              {:b2/response warm})))
          _ (phase! "p1-boot-session-admission-engine")
          ;; P2 — first prompt render (production anchoring: full context).
          prompt (await (turn/render-prompt agent-id (await (db/db)) [] run-id))
          _ (when (:seon.error/message prompt)
              (throw (ex-info "render-prompt failed" prompt)))
          _ (phase! "p2-first-prompt")
          !turn (volatile! 0)
          llm-fn (fn [_request]
                   (js/Promise.resolve
                    {:text (reply-for-turn @!turn)
                     :seon.ai/adapter :stub}))
          drive-turn!
          (fn ^:async drive-turn! [turn-index]
            (vswap! !turn (constantly turn-index))
            (let [started (js/Date.now)
                  result (await
                          (turn/run-turn!
                           {:seon.agent/id agent-id
                            :seon.agent/llm-fn llm-fn
                            :seon.agent.run/id run-id
                            :seon.db/db (await (db/db))}))
                  wall (- (js/Date.now) started)]
              (vswap! turn-timings conj
                      {:b2/turn turn-index
                       :b2/wall-ms wall
                       :b2/status (or (:seon.agent.turn/status result) :done)
                       :b2/eval-count (:seon.agent/eval-count result)
                       :b2/evals
                       (mapv (fn [row]
                               {:b2/source (subs (str (:seon.eval/source row))
                                                 0
                                                 (min 60 (count (str (:seon.eval/source row)))))
                                :b2/ok? (:seon.eval/ok? row)
                                :b2/result (some-> (:seon.eval/result-edn row)
                                                   str
                                                   (subs 0 (min 80 (count (str (:seon.eval/result-edn row))))))
                                :b2/error (some-> (:seon.eval/error row)
                                                  str
                                                  (subs 0 (min 120 (count (str (:seon.eval/error row))))))})
                             (:seon.agent.turn/evals result))
                       :b2/error (:seon.error/data result)})
              result))]
      ;; P3 — first real turn.
      (await (drive-turn! 1))
      (phase! "p3-first-eval-turn")
      ;; Turns up to the burst.
      (doseq [turn-index (range 2 (inc burst-turn-index))]
        (await (drive-turn! turn-index)))
      ;; Burst done at burst-turn-index; gc turn follows, then 60s settle.
      (await (drive-turn! (inc burst-turn-index)))
      (await (sleep 60000))
      (phase! "p4-post-burst-gc-60s")
      ;; Remaining turns to the requested total, then one final gc turn.
      (doseq [turn-index (range (+ 2 burst-turn-index) (inc turns))]
        (await (drive-turn! turn-index)))
      (await (drive-turn! :final-gc))
      (await (sleep 5000))
      (phase! "p5-after-all-turns")
      ;; Leave no open run on the branch database.
      (await (run/close-run! {:seon.agent.run/id run-id
                              :seon.agent.run/closed-reason :completed}))
      (let [result {:b2/label label
                    :b2/agent-id agent-id
                    :b2/run-id run-id
                    :b2/turns turns
                    :b2/phases @phases
                    :b2/turn-timings @turn-timings}]
        (write-file! (str out-dir "/" label "-result.edn") (pr-str result))
        (emit! result)
        result))))

(defn -main
  "Run one labeled A/B drive from Shadow's command-line entrypoint."
  [& [options-edn]]
  (let [options (reader/read-string options-edn)]
    (-> (db/open-session!
         (cond-> {:seon.db/socket-path (:b2/socket-path options)
                  :seon.db/database-name (:b2/database-name options)
                  :seon.db/backend :file
                  :seon.db/database-advanced? false}
           (:b2/database-path options)
           (assoc :seon.db/database-path (:b2/database-path options))
           (:b2/connection-id options)
           (assoc :seon.db/connection-id (:b2/connection-id options))))
        (.then (fn [_] (admit!)))
        (.then (fn [_] (run-drive! options)))
        (.then (fn [_]
                 (host/stop!)
                 (db/close-session!)
                 (js/setTimeout #(.exit js/process 0) 1500)))
        (.catch (fn [exception]
                  (emit! {:b2/failed? true
                          :seon.error/message (or (some-> exception .-message)
                                                  (str exception))
                          :seon.error/data (ex-data exception)})
                  (host/stop!)
                  (db/close-session!)
                  (js/setTimeout #(.exit js/process 1) 1500))))))
