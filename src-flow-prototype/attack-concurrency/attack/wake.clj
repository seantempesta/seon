(ns attack.wake
  "ATTACK 3 -- two agents messaging each other in a CYCLE, through the real
   listen!-driven wake path."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(defn cyclic-program
  "Each agent answers by messaging its partner. Deliberately non-terminating:
   the chain never ends, so the only thing that can stop it is the mechanism."
  [{:keys [agent-id]}]
  (let [partner (if (= agent-id "ping") "pong" "ping")]
    [(format "{:note \"hop\" :messages [{:to %s :body \"go\"}]}" (pr-str partner))]))

(defn section [n title] (println (format "\n--- %s. %s" n title)))

(defn -main [& [root]]
  (let [root (or root "/private/tmp/attack")]

    (section "3a" "MESSAGE CYCLE: ping <-> pong, unbounded by construction")
    (let [conn (store/fresh! (str root "/w") {:config/lease-ms 60000})
          hops (atom 0)
          idle (promise)]
      (d/transact conn {:tx-data [{:agent/id "ping" :agent/counter 0}
                                  {:agent/id "pong" :agent/counter 0}]})
      (reset! driver/claims-lost 0)
      (driver/wake! conn "c1" cyclic-program
                    (fn [done] (when (seq done) (swap! hops + (count done)))))
      ;; kick it off
      (d/transact conn {:tx-data [{:message/id "seed" :message/to [:agent/id "ping"]
                                   :message/body "go"}]})
      (deref idle 8000 :timeout)
      (Thread/sleep 3000)
      (let [msgs (d/q '[:find ?id ?b ?to :where [?m :message/id ?id] [?m :message/body ?b]
                        [?m :message/to ?a] [?a :agent/id ?to]] (d/db conn))
            runs (d/q '[:find ?id :where [?r :run/id ?id]] (d/db conn))]
        (println "    hops driven:" @hops "  lost CAS claims:" @driver/claims-lost)
        (println "    message entities in the database:" (count msgs))
        (doseq [m (sort-by first msgs)] (println "     " (pr-str m)))
        (println "    runs opened:" (count runs) (pr-str (sort (map first runs))))
        (println "    ping counter:" (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id "ping"]))
                 " pong counter:" (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id "pong"])))
        (println "    still-open runs:" (count (d/q '[:find ?r :where [?r :run/open? true]] (d/db conn))))
        (println "    => the cycle" (if (< (count runs) 4) "STOPPED" "kept going"))))

    (section "3b" "the same cycle with UNIQUE message bodies (id still collides?)")
    (let [conn (store/fresh! (str root "/w2") {:config/lease-ms 60000})]
      (d/transact conn {:tx-data [{:agent/id "ping" :agent/counter 0}
                                  {:agent/id "pong" :agent/counter 0}]})
      ;; hand-drive two full round trips so the collision is unambiguous
      (let [seed (d/q '[:find ?m . :in $ :where [?m :message/id "seed"]]
                      (:db-after (d/transact conn {:tx-data [{:message/id "seed"
                                                              :message/to [:agent/id "ping"]
                                                              :message/body "go"}]})))]
        (dotimes [round 3]
          (println (format "    round %d: waking-inbound = %s" round
                           (pr-str (driver/waking-inbound (d/db conn)))))
          (doseq [[m aid] (driver/waking-inbound (d/db conn))]
            (let [r (driver/start-run! conn {:run-id (str "run/" aid "/" m) :agent-id aid
                                             :message-eid m :sources (cyclic-program {:agent-id aid})})]
              (driver/claim! conn r "c" 60000)
              (driver/drive-run! conn r "c"))))
        (println "    seed eid" seed " messages now:"
                 (pr-str (sort (d/q '[:find ?id ?tx :where [?m :message/id ?id ?tx]] (d/db conn)))))
        (println "    runs:" (pr-str (sort (d/q '[:find [?id ...] :where [?r :run/id ?id]] (d/db conn)))))))

    (println "\nOK")
    (System/exit 0)))
