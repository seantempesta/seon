(ns a4
  "Does the design's own recovery amplify a resource attack?

   drive-run! blocks inside eval/evaluate until the eval returns. A form that
   never returns (an un-overridden blocking host call) therefore never renews
   the lease. The lease goes stale, a survivor claims the run, and re-executes
   the SAME in-flight step. One poisoned form should consume one permit per
   lease period, from every claimant that touches it."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.eval :as eval]
            [flow.store :as store]))

(def PATH "/private/tmp/claude-501/-Users-sean-src-seon/ad6e7227-ef9f-4cc7-954e-ea6dbabccdff/scratchpad/flow/attack-resource/store-a4")

(defn program [{:keys [body]}]
  (case body
    "poison" ["(host/block 600000)"]
    ["(do {:facts [] :note \"healthy\"})"]))

(defn -main [& _]
  (let [conn (store/fresh! PATH {:config/compute-permits 4
                                 :config/lease-ms 1500
                                 :config/time-limit-ms 500
                                 :config/allocation-limit-bytes (* 64 1024 1024)})]
    (println "permits" (eval/available) " lease 1500ms  time-limit 500ms")
    (d/transact conn {:tx-data [{:agent/id "bad"} {:agent/id "good"}]})
    (d/transact conn {:tx-data [{:message/id "m1" :message/to [:agent/id "bad"]
                                 :message/from [:agent/id "good"] :message/body "poison"}]})
    (println "\none message to agent 'bad' whose reply is a single (host/block 600000)\n")

    (dotimes [round 6]
      (let [me (str "claimant-" round)]
        (.start (Thread/ofVirtual)
                (fn [] (try (driver/scan! conn me program) (catch Throwable _ nil))))
        (Thread/sleep 1700)
        (let [db (d/db conn)
              run (d/q '[:find ?r . :where [?r :run/id _]] db)
              {:run/keys [claimant epoch open?]} (d/pull db [:run/claimant :run/epoch :run/open?] run)]
          (println (format "round %d  claimant=%-12s epoch=%d open?=%s  PERMITS FREE=%d"
                           round claimant epoch open? (eval/available))))))

    (println "\nnow a healthy agent gets a message:")
    (d/transact conn {:tx-data [{:message/id "m2" :message/to [:agent/id "good"]
                                 :message/from [:agent/id "bad"] :message/body "hello"}]})
    (let [t0 (System/nanoTime)
          f (future (driver/scan! conn "claimant-healthy" program))
          r (deref f 6000 ::still-running)]
      (println (format "  healthy run: %s after %dms  (permits free %d)"
                       (if (= r ::still-running) "STILL BLOCKED, never started" "completed")
                       (quot (- (System/nanoTime) t0) 1000000)
                       (eval/available))))

    (println "\nreceipts the survivor would read for the poisoned run:")
    (doseq [[i o] (sort (d/q '[:find ?i ?o :where [?e :seon.eval/index ?i] [?e :seon.eval/outcome ?o]]
                             (d/db conn)))]
      (println "   index" i "->" o))
    (println "  lost CAS claims:" @driver/claims-lost)

    (println "\ndone.")
    (shutdown-agents)
    (System/exit 0)))
