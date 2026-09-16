; Evaluate each form separately through MCP JVM mode on default.
; This uses the existing in-process runner and never starts a test JVM.
(comment
  (#'seon.test/with-test-loader
   #(doseq [n '[seon.program-test seon.fn-test seon.turn-test seon.sci.eval-test]]
      (require n :reload)))

  (def s1-selected-tests
    (let [database (seon.db/db (seon.operator/connection "default"))
          namespaces #{"seon.program-test" "seon.fn-test"
                       "seon.turn-test" "seon.sci.eval-test"}]
      (sort
       (into #{}
             (comp (mapcat #(seon.fn/tests-reaching database %))
                   (filter #(namespaces (namespace (symbol %)))))
             ["seon.fn/source-rows" "seon.fn/analyze-forms"
              "seon.sci.eval/evaluate" "seon.program/declaration-row"]))))

  (def s1-verification-results (atom []))
  (def s1-verification
    (future
      (doseq [test-symbol s1-selected-tests]
        (let [connection (seon.operator/connection "default")
              database (seon.db/db connection)]
          (swap! s1-verification-results conj
                 (seon.test/run
                  (#'seon.test/resolve-test (symbol test-symbol)) connection
                  {:seon.db/db database
                   :seon.test.run/provenance (seon.test.runner/provenance database)
                   :seon.test/remaining-ms 240000}))))
      :complete))

  {:complete? (realized? s1-verification)
   :results (mapv #(select-keys % [:seon.test/sym :seon.test/pass-count
                                  :seon.test/fail-count :seon.test/error-count
                                  :seon.error/kind])
                  @s1-verification-results)}

  ; Re-run corrected or drifted results serially after loading saved tests.
  ; Keep every attempt in the evidence; never suppress drift detection.
  (def s1-rerun-results (atom []))
  (def s1-reruns
    (future
      @s1-verification
      (#'seon.test/with-test-loader
       #(doseq [n '[seon.program-test seon.fn-test seon.turn-test seon.sci.eval-test]]
          (require n :reload)))
      (doseq [test-symbol
              (sort (conj (into #{}
                                (keep #(when (or (:seon.error/kind %)
                                                 (pos? (+ (:seon.test/fail-count % 0)
                                                          (:seon.test/error-count % 0))))
                                         (:seon.test/sym %)))
                                @s1-verification-results)
                          "seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities"))]
        (let [connection (seon.operator/connection "default")
              database (seon.db/db connection)]
          (swap! s1-rerun-results conj
                 (seon.test/run
                  (#'seon.test/resolve-test (symbol test-symbol)) connection
                  {:seon.db/db database
                   :seon.test.run/provenance (seon.test.runner/provenance database)
                   :seon.test/remaining-ms 600000}))))
      :complete))

  ; Submit only after the preceding Juniper turn is closed.
  ; The final serial pass used this same runner form for the remaining reds
  ; and the saved reload regression, after reloading the four test namespaces.
  (def s1-final-results (atom []))
  (def s1-final-runs
    (future
      @s1-reruns
      (#'seon.test/with-test-loader
       #(doseq [n '[seon.program-test seon.fn-test seon.turn-test seon.sci.eval-test]]
          (require n :reload)))
      (let [latest (into {} (map (juxt :seon.test/sym identity))
                         (concat @s1-verification-results @s1-rerun-results))
            selected (sort (conj (into #{}
                                       (keep (fn [[sym result]]
                                               (when (or (:seon.error/kind result)
                                                         (pos? (+ (:seon.test/fail-count result 0)
                                                                  (:seon.test/error-count result 0))))
                                                 sym))) latest)
                                 "seon.sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads"))]
        (doseq [sym selected]
          (let [connection (seon.operator/connection "default")
                database (seon.db/db connection)]
            (swap! s1-final-results conj
                   (seon.test/run (#'seon.test/resolve-test (symbol sym)) connection
                                  {:seon.db/db database
                                   :seon.test.run/provenance (seon.test.runner/provenance database)
                                   :seon.test/remaining-ms 600000})))))
      :complete))

  ; Submit only after the preceding Juniper turn is closed.
  (def s1-live-turn
    (let [instance (get @seon.operator.runtime/running-instances "default")]
      (seon.turn/virtual-turn!
       {:seon.turn.loop/cluster (:seon.turn.loop/cluster instance)
        :seon.agent/routing (:seon.agent/routing instance)
        :seon.agent/id "juniper"
        :seon.cluster.reply/text
        "(ns my.agents.juniper.s1)\n(defn value {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n(defn caller {:malli/schema [:=> [:cat :int] :int]} [x] (value x))\n(clojure.test/deftest caller-test (clojure.test/is (= 3 (caller 2))))\n(in-ns 'my.agents.juniper)\n(my.turn/wait {:my.turn/note \"S1 call-edge proof complete.\"})"})))

  (let [database (seon.db/db (seon.operator/connection "default"))]
    {:turn (seon.db/pull database [:seon.turn/id :seon.turn/closed-tx]
                         [:seon.turn/id (:seon.turn/id s1-live-turn)])
     :caller (seon.db/pull database
                           '[:seon.fn/sym {:seon.fn/calls [:seon.fn/sym]}]
                           [:seon.fn/sym "my.agents.juniper.s1/caller"])
     :tests (seon.fn/tests-reaching database "my.agents.juniper.s1/value")}))
