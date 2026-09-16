(deftest delimiter-repair-is-span-local-and-precedes-intent
  (testing "repair is one bounded, honest, idempotent pass"
    (let [repair (deref (ns-resolve 'seon.turn 'repair-source))
          broken "(defn ^{:malli/schema [:=> [:cat :int] :int]} repaired [x]\n  (+ x 1)"
          fixed (repair broken 'my.agents.agent-a 10000)]
      (is (= "(+ 1 2)" (repair "(+ 1 2)" 'my.agents.agent-a 10000))
          "unchanged valid input is rejected as a repair")
      (is (= "{:a 1 :b}" (repair "{:a 1 :b}" 'my.agents.agent-a 10000))
          "still-invalid output is rejected")
      (is (= fixed (repair fixed 'my.agents.agent-a 10000))
          "accepted repair is idempotent")))
  (testing "one missing defn closer is fixed, evaluated, and stored as the transcript form"
    (with-cluster
      (fn [cluster]
        (let [connection (:seon.db/connection cluster)
              original
              (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} repaired [x]\n  (+ x 1)\n"
                   "(+ 40 2)\n"
                   "(repaired 2)\n"
                   "(+ 1 1)\n"
                   "(+ 2 2)\n"
                   "(seon.run/complete \"fixed\")")
              eval-nanos (atom 0)
              reply-arrived (atom nil)
              read-sources reply/sources
              evaluate sci.eval/evaluate]
          (turn/turn
           {:seon.turn.loop/cluster cluster
            :seon.turn.work/next
            (turn/next-agent-work @connection (request connection))}
           now)
          (let [call-work (turn/next-agent-work @connection
                                                (request connection))
                started (System/nanoTime)]
            (with-redefs [ai/complete (fn [_] {:seon.ai/text original})
                          reply/sources
                          (fn [& arguments]
                            (reset! reply-arrived (System/nanoTime))
                            (apply read-sources arguments))
                          sci.eval/evaluate
                          (fn [request]
                            (let [started (System/nanoTime)
                                  result (evaluate request)]
                              (swap! eval-nanos + (- (System/nanoTime) started))
                              result))]
              (turn/turn
               {:seon.turn.loop/cluster cluster
                :seon.turn.work/next call-work}
               now))
            (let [elapsed (- (System/nanoTime) (or @reply-arrived started))
                  bookkeeping-ms (/ (double (- elapsed @eval-nanos)) 1000000.0)
                  evaluations (agent-evaluations @connection)
                  sources (mapv :seon.cluster.eval/source evaluations)
                  rendered
                  (transcript/render-ai
                   {:seon.db/db @connection
                    :seon.agent/id "agent-a"
                    :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic
                    :seon.sci.admit/caps (:seon.sci.admit/caps cluster)})]
              (is (= 6 (count evaluations)))
              (is (= "(defn ^{:malli/schema [:=> [:cat :int] :int]} repaired [x]\n  (+ x 1))\n" (first sources)))
              (is (= "(+ 40 2)" (second sources))
                  "the adjacent good form remains byte-identical")
              (is (= ["42" "3" "2" "4"]
                     (mapv :seon.eval/shown (subvec evaluations 1 5))))
              (is (= original
                     (db/q '[:find ?reply .
                             :where [?turn :seon.turn/reply ?reply]
                                    [?turn :seon.turn/attempts _]]
                           @connection))
                  "raw intent provenance remains the original reply")
              (is (str/includes? rendered
                                 "(defn ^{:malli/schema [:=> [:cat :int] :int]} repaired [x]\n  (+ x 1))"))
              (is (< bookkeeping-ms 300.0)
                  (str "six-form bookkeeping took " bookkeeping-ms " ms"))))))))
  (testing "indent mode repairs a mismatched closer type"
    (with-cluster
      (fn [cluster]
        (let [connection (:seon.db/connection cluster)
              source "(let [x 1)\n  x)\n(seon.run/complete \"fixed\")"]
          (with-redefs [ai/complete (fn [_] {:seon.ai/text source})]
            (drive! cluster 6))
          (let [evaluations (agent-evaluations @connection)]
            (is (= "(let [x 1]\n  x)\n"
                   (:seon.cluster.eval/source (first evaluations))))
            (is (= "1" (:seon.eval/shown (first evaluations)))))))))
  (testing "an odd map stays an error and the following form still settles"
    (with-cluster
      (fn [cluster]
        (let [connection (:seon.db/connection cluster)
              source "{:a 1 :b}\n(+ 20 22)\n(seon.run/complete \"continued\")"]
          (with-redefs [ai/complete (fn [_] {:seon.ai/text source})]
            (drive! cluster 6))
          (let [evaluations (agent-evaluations @connection)]
            (is (= "{:a 1 :b}\n"
                   (:seon.cluster.eval/source (first evaluations))))
            (is (= :seon.sci.reader/unreadable
                   (:seon.error/kind (first evaluations))))
            (is (= "42" (:seon.eval/shown (second evaluations))))))))))

