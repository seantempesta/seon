(ns seon.health-liveness-and-closure-test
  "Comprehensive verification of NASA closed-world liveness (`seon.health.liveness`),
   shared reconciler policy (`seon.reconciler.policy`), Flow/UI closure (`seon.health.closure`),
   single-source rules & rule audit (`seon.health.coverage.rules`), and live runtime fixes."
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.agent :as agents]
            [seon.code :as code]
            [seon.error :as error]
            [seon.health.closure :as closure]
            [seon.health.coverage.rules :as rcov]
            [seon.health.liveness :as liveness]
            [seon.reconciler :as rec]
            [seon.reconciler.policy :as policy]
            [seon.render.print.policy :as ppol]
            [seon.render.sanitize :as sanitize]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.store :as store]
            [seon.task.gate :as gate]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.merge :as tmerge]
            [seon.web :as web]
            [seon.web.actions :as actions]))

(deftest ^{:cost/ticks 60000000} nasa-liveness-model-checker-proof-and-negative-detection-test
  (testing "Live seon.reconciler.policy/decide-agent-step has 0 livelocks, 0 orphan-pauses, 0 wedged-inflight, 0 crashed-auto-merges, 0 stranded-ready"
    (let [res (liveness/check-liveness)]
      (is (true? (:ok? res)) (pr-str res))
      (is (false? (:truncated? res)))
      (is (pos? (:states-explored res)))
      (is (empty? (:livelocks res)))
      (is (empty? (:orphan-pauses res)))
      (is (empty? (:wedged-inflight res)))
      (is (empty? (:crashed-auto-merges res)))
      (is (empty? (:stranded-ready res)))))

  (testing "Model checker catches synthetic livelocks, orphan-pauses, and crashed auto-merges"
    ;; 1. Synthetic livelock: ignore bounce-halted? & turn-budget-halted? so test-failure bounces forever
    (let [buggy-livelock (fn [in]
                           (policy/decide-agent-step (assoc in :max-bounces 0 :max-total-turns 0)))
          r-live (liveness/check-liveness {:decide-fn buggy-livelock :max-states 2000})]
      (is (false? (:ok? r-live)))
      (is (seq (:livelocks r-live)) "Must detect bounce livelock SCC when bounce/turn caps are disabled"))

    ;; 2. Synthetic orphan-paused-parent trap: disable review-abort?
    (let [buggy-orphan (fn [in]
                         (assoc (policy/decide-agent-step in) :review-abort? false))
          r-orph (liveness/check-liveness {:decide-fn buggy-orphan})]
      (is (false? (:ok? r-orph)))
      (is (seq (:orphan-pauses r-orph)) "Must detect orphan paused parent when review-abort? is disabled"))

    ;; 3. Synthetic crashed auto-merge: allow cutoff-auto-merge? even when err-halted?
    (let [buggy-crash (fn [in]
                        (let [d (policy/decide-agent-step in)]
                          (if (:err-halted? d) (assoc d :cutoff-auto-merge? true) d)))
          r-crash (liveness/check-liveness {:decide-fn buggy-crash})]
      (is (false? (:ok? r-crash)))
      (is (seq (:crashed-auto-merges r-crash)) "Must detect crashed auto-merge")))

  (testing "Iterative Tarjan SCC detects 2-node cycles and self-loops"
    (is (= [] (liveness/find-livelock-sccs {:a #{:b} :b #{:c} :c #{}})))
    (is (= 1 (count (liveness/find-livelock-sccs {:a #{:b} :b #{:a}}))))
    (is (= 1 (count (liveness/find-livelock-sccs {:a #{:a}}))))))

(deftest ^{:cost/ticks 80000000} policy-cross-branch-errors-bounces-and-reviewer-release-test
  (testing "model-error-halted? compares :db/txInstant across distinct :main vs branch tx IDs and halts HTTP 400 in 1 turn"
    ;; Main unread tx (900) > branch error tx (500), but main unread instant (1000) < branch error instant (2000) -> MUST HALT!
    (is (true? (policy/model-error-halted?
                {:trailing-model-errors 3 :non-retryable-model-error? false
                 :last-err-tx 500 :last-err-inst 2000
                 :same-db? false :newest-external-unread-tx 900 :newest-external-unread-inst 1000})))
    ;; Newer external unread instant (3000 > 2000) -> clears halt cleanly!
    (is (false? (policy/model-error-halted?
                 {:trailing-model-errors 3 :non-retryable-model-error? false
                  :last-err-tx 500 :last-err-inst 2000
                  :same-db? false :newest-external-unread-tx 905 :newest-external-unread-inst 3000})))
    ;; HTTP 400 / invalid_request_error / context_length / :retryable? false halts in 1 turn!
    (is (true? (policy/non-retryable-error-str? "Model HTTP 400: invalid_request_error maximum context length")))
    (is (true? (policy/non-retryable-error-str? "Model error [model/api-error]: context_length exceeded")))
    (is (true? (:non-retryable-model-error?
                (policy/summarize-activity
                 [{:seon.agent.eval/turn 1 :seon.agent.eval/code "" :seon.agent.eval/error "bad" :retryable? false}] []))))
    (is (true? (policy/model-error-halted?
                {:trailing-model-errors 1 :non-retryable-model-error? true
                 :last-err-tx 500 :last-err-inst 2000 :same-db? false}))))

  (testing "System bounces never reset tool-streak or turns-since-external and halt worker at max-bounces, while final-turn ready! still tests & merges"
    (let [m-ext (random-uuid)
          m-b1 (random-uuid)
          m-b2 (random-uuid)
          inbox [{:seon.agent.message/id m-ext :seon.agent.message/seq 1 :seon.agent.message/type :chat}
                 {:seon.agent.message/id m-b1 :seon.agent.message/seq 2 :seon.agent.message/type :test-failure}
                 {:seon.agent.message/id m-b2 :seon.agent.message/seq 3 :seon.agent.message/type :merge-conflict}]
          evs [{:seon.agent.eval/turn 1 :seon.agent.eval/call-id "c1" :seon.agent.eval/code "(a)" :seon.agent.eval/seen-messages #{m-ext}}
               {:seon.agent.eval/turn 2 :seon.agent.eval/call-id "c2" :seon.agent.eval/code "(b)" :seon.agent.eval/seen-messages #{m-b1}}
               {:seon.agent.eval/turn 3 :seon.agent.eval/call-id "c3" :seon.agent.eval/code "(c)" :seon.agent.eval/seen-messages #{m-b2}}]
          act (policy/summarize-activity evs inbox)]
      (is (= 3 (:tool-streak act)) "System bounces must NOT reset tool-streak")
      (is (= 3 (:turns-since-external act)))
      (is (= 2 (:bounce-streak act)))
      ;; Worker halts cleanly at max-bounces
      (let [d-halt (policy/decide-agent-step (merge act {:branch :branch/w :auto-merge? true :has-unread? true :max-bounces 2}))]
        (is (true? (:bounce-halted? d-halt)))
        (is (false? (:needs-turn? d-halt))))
      ;; Final-turn ready! at max-total-turns gates needs-turn? ONLY -> still tests & merges!
      (let [d-final (policy/decide-agent-step {:branch :branch/w :auto-merge? true :raw-ready? true :ready-tx 10
                                               :head-tx 10 :tested-tx nil :gate-ok? true :same-db? true
                                               :turns-since-external 32 :max-total-turns 32})]
        (is (true? (:turn-budget-halted? d-final)))
        (is (false? (:needs-turn? d-final)))
        (is (true? (:needs-test? d-final)))
        (is (= :testing (:status d-final))))))

  (testing "Crashed reviewer releases paused parent immediately, paused reviewer is not active, prune-reconciler-sets clears :testing on :merge-conflict/:health-block, and merge stamps :merged-tx on all ready branch agents"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data [{:db/id "p1" :seon.agent/id :w/par :seon.agent/branch :branch/par :seon.agent/paused? true :seon.agent/goal "work"}
                                  {:seon.agent/id :rev/crash :seon.agent/branch :branch/rev-crash :seon.agent/parent :w/par
                                   :seon.agent/forked-from "p1" :seon.agent/merge-target "p1" :seon.agent/auto-merge? true :seon.agent/goal "review"}]})
      (store/branch! conn :main :branch/par)
      (store/branch! conn :main :branch/rev-crash)
      (let [pe (:e (first (d/datoms @conn :avet :seon.agent/id :w/par)))]
        (is (true? (gate/has-active-reviewer? @conn pe)))
        (let [rconn (store/connect cfg :branch/rev-crash)]
          (try
            (d/transact rconn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :rev/crash
                                          :seon.agent.eval/turn 1 :seon.agent.eval/code "" :seon.agent.eval/thought ""
                                          :seon.agent.eval/error "Model HTTP 400: invalid_request_error maximum context length"
                                          :seon.agent.eval/out "" :seon.agent.eval/ms 0.0 :seon.agent.eval/ticks 0 :seon.agent.eval/bytes 0}]})
            (let [st (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} #{} :rev/crash)]
              (is (true? (:err-halted? st)))
              (is (false? (:needs-turn? st)))
              (is (false? (:needs-merge? st)))
              (is (seq (:review-ops st)) "Crashed reviewer must emit abort/unpause ops")
              (is (some #(= :review-note (:type %)) (:review-ops st))))
            (finally (d/release rconn))))
        ;; Once reviewer is paused, has-active-reviewer? must be false!
        (d/transact conn {:tx-data [{:seon.agent/id :rev/crash :seon.agent/paused? true}]})
        (is (false? (gate/has-active-reviewer? @conn pe))))

      ;; review-cutoff-ops inspects inbox bounce history even when unread is empty
      (let [ma (d/pull @conn (quote [:seon.agent/id :seon.agent/merge-target :seon.agent/forked-from :seon.agent/parent]) [:seon.agent/id :rev/crash])
            ops (gate/review-cutoff-ops @conn ma {:cutoff-reached? true :gate-ok? true :unread []
                                                  :inbox [{:seon.agent.message/id (random-uuid) :seon.agent.message/seq 1
                                                           :seon.agent.message/type :test-failure :seon.agent.message/body "1 test failed"}]})]
        (is (= 2 (count ops)))
        (is (= :review-note (:type (second ops)))))

      ;; prune-reconciler-sets prunes :testing on :merge-conflict & :health-block
      (let [prune-fn @(var seon.reconciler/prune-reconciler-sets)
            _ (d/transact conn {:tx-data [{:seon.agent/id :w/p1 :seon.agent/branch :main}
                                          {:seon.agent/id :w/p2 :seon.agent/branch :main}]})
            r-mc (d/transact conn {:tx-data [{:seon.agent.message/id (random-uuid) :seon.agent.message/seq 10
                                              :seon.agent.message/from :transactor :seon.agent.message/to :w/p1
                                              :seon.agent.message/type :merge-conflict :seon.agent.message/body "conflict"}
                                             {:seon.agent.message/id (random-uuid) :seon.agent.message/seq 11
                                              :seon.agent.message/from :transactor :seon.agent.message/to :w/p2
                                              :seon.agent.message/type :health-block :seon.agent.message/body "health"}]})
            db-after (:db-after r-mc)
            tx (:max-tx db-after)
            st0 {:testing #{[:w/p1 tx] [:w/p2 tx]} :merging #{[:w/p1 tx]} :gate-notified #{} :settling #{}}
            st1 (prune-fn st0 db-after nil (:tx-data r-mc))]
        (is (empty? (:testing st1)) "Both :merge-conflict and :health-block must prune :testing")
        (is (empty? (:merging st1))))

      ;; handle-merge-op! stamps :merged-tx on ALL ready agents on the merged branch
      (store/branch! conn :main :branch/multi-ready)
      (d/transact conn {:tx-data [{:seon.agent/id :w/m-a :seon.agent/branch :branch/multi-ready :seon.agent/goal "a"}
                                  {:seon.agent/id :w/m-b :seon.agent/branch :branch/multi-ready :seon.agent/goal "b"}]})
      (let [bconn (store/connect cfg :branch/multi-ready)]
        (try
          (d/transact bconn {:tx-data (concat (code/file-import-tx @bconn "src/demo/mr_stamp.clj" "(ns demo.mr-stamp)\n(defn ok {:malli/schema [:=> [:cat] :keyword]} [] :ok)\n" false)
                                              [{:seon.agent/id :w/m-a :seon.agent/ready? true}
                                               {:seon.agent/id :w/m-b :seon.agent/ready? true}])})
          (let [btx (:max-tx @bconn)
                [_ _] (tmerge/handle-merge-op! {:conn conn :cfg cfg :commit-git? false :writes 0}
                                               {:op :merge :branch :branch/multi-ready :agent-id :w/m-a}
                                               (d/commit-id @conn))
                ma (d/pull @conn (quote [:seon.agent/ready? :seon.agent/merged-tx]) [:seon.agent/id :w/m-a])
                mb (d/pull @conn (quote [:seon.agent/ready? :seon.agent/merged-tx]) [:seon.agent/id :w/m-b])]
            (is (= btx (:seon.agent/merged-tx ma)))
            (is (= btx (:seon.agent/merged-tx mb)) "Second ready agent on merged branch must also receive :merged-tx"))
          (finally (d/release bconn)))))))

(deftest ^{:cost/ticks 180000000} flow-ui-closure-empty-form-posts-and-live-fixes-test
(testing "Live runtime fixes: :fork-refused releases :live, :status :error clears :agent-states, empty-form POSTs never crash or shift args, invoke-call! is not callable"
  ;; 1. seon.agent handles :fork-refused
  (let [fork-out (a/chan 4)
        wait-ch (a/chan 1)
        st0 (-> (agents/agents-proc {:fork-out fork-out})
                (assoc-in [:live :ag/f1] {:pid :forking :branch :branch/f1 :busy? true :last-active 1})
                (assoc-in [:eval-waiters "eval-1"] {:reply-ch wait-ch :agent-id :ag/f1 :keep-warm? true}))
        [st1 outs] (agents/agents-proc st0 :fork-ev {:ev :fork-refused :type :agent :code -1})
        wres (a/poll! wait-ch)]
    (is (not (contains? (:live st1) :ag/f1)) ":fork-refused must release :forking entry from :live")
    (is (empty? (:eval-waiters st1)))
    (is (false? (:ok? wres)))
    (is (= :fork-refused (get-in outs [:tx 0 :class])))
    (is (= :exited (get-in outs [:exits 0 :ev]))))

  ;; 2. seon.web handle-stream-msg handles :status :error without marking errored agent :active
  (with-test-store [{:keys [conn]}]
    (let [st0 {:conn conn :walk {:units {} :order []} :live-streams {:w/bad {:status :thinking :phase :thinking}}
               :agent-states {:w/bad :active} :errors [] :frames 0 :last-view-sig nil :last-agg-rate ""}
          hstream @(var seon.web/handle-stream-msg)
          st1 (hstream st0 {:agent-id :w/bad :status :error :seon.error/kind :model/api-error :message "HTTP 400"})]
      (is (nil? (get-in st1 [:agent-states :w/bad])) "Errored agent must NOT stay :active")
      (is (nil? (get-in st1 [:live-streams :w/bad])))
      (is (= 1 (count (:errors st1)))))

    ;; 3. Empty-form POST safety on chat-agent-form!, pin-ref-form!, semantic-search-form!, and invoke-call! non-callable
    (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/branch :main :seon.agent/entry? true}]})
    (let [st {:conn conn :server-in-ch nil :ui-ch nil :branch-feedback nil}]
      (doseq [empty-f [{} nil]]
        (let [r-chat (actions/invoke-call! st {:call-form (list (quote seon.web/chat-agent-form!) :agent/root) :form-fields empty-f})
              r-pin  (actions/invoke-call! st {:call-form (list (quote seon.web/pin-ref-form!) :agent/root) :form-fields empty-f})
              r-sem  (actions/invoke-call! st {:call-form (list (quote seon.web/semantic-search-form!)) :form-fields empty-f})]
          (is (true? (:ok? r-chat)) (str "chat-agent-form! failed on " (pr-str empty-f) ": " (pr-str r-chat)))
          (is (true? (:ok? r-pin))  (str "pin-ref-form! failed on " (pr-str empty-f) ": " (pr-str r-pin)))
          (is (true? (:ok? r-sem))  (str "semantic-search-form! failed on " (pr-str empty-f) ": " (pr-str r-sem)))))
      ;; seon.web/invoke-call! must NOT be ^:seon/callable!
      (let [r-inv (actions/invoke-call! st {:call-form (list (quote seon.web/invoke-call!) {}) :form-fields {}})]
        (is (false? (:ok? r-inv)))
        (is (= :seon.web/not-callable (:error r-inv))))
      ;; seon.web/send-message! must NOT be in sanitizer allowlist!
      (let [san (sanitize/sanitize-hiccup-with-report
                 [:button {:type "button" :on-click (list (quote seon.web/send-message!) :a "hi")} "x"]
                 {:allow-seon-actions? true})]
        (is (seq (:dropped san)))))))

(testing "check-flow-closure & check-ui-closure catch synthetic holes and pass clean on fixed DB"
  (with-test-store [{:keys [conn]}]
    ;; Import synthetic broken Flow + UI forms
    (let [tx (concat
              (code/file-import-tx @conn "src/seon/fork.clj"
                "(ns seon.fork)\n(defn- run-fork-parent-loop! [ev-out]\n  (a/>!! ev-out {:ev :fork-refused :type child-type :code -1}))\n" false)
              (code/file-import-tx @conn "src/seon/agent.clj"
                "(ns seon.agent)\n(defn- handle-fork-ev [st msg] (case (:ev msg) :forked [st nil] [st nil]))\n(defn- deliver-model-response [st msg] [st {:tx [{:op :transact}]}])\n" false)
              (code/file-import-tx @conn "src/seon/error.clj"
                "(ns seon.error)\n(defn model-error-outputs [msg] {:tx [{:op :transact}]})\n" false)
              (code/file-import-tx @conn "src/seon/web.clj"
                "(ns seon.web)\n(defn ^:seon/callable invoke-call! [& args] nil)\n(defn- handle-stream-msg [st msg] (case (:status msg) :done st st))\n" false)
              (code/file-import-tx @conn "src/seon/web/actions.clj"
                "(ns seon.web.actions)\n(defn invoke-call! [st m] (cond-> [] (seq (:form-fields m)) (conj (:form-fields m))))\n" false)
              (code/file-import-tx @conn "src/seon/render/sanitize.clj"
                "(ns seon.render.sanitize)\n(def ^:private allowed-seon-web-actions #{(quote seon.web/send-message!)})\n" false))]
      (d/transact conn {:tx-data tx})
      (let [rep (closure/check-closure @conn)
            classes (set (map :class (:problems rep)))]
        (is (false? (:ok? rep)))
        (is (contains? classes :unhandled-fork-ev))
        (is (contains? classes :unhandled-stream-status))
        (is (contains? classes :duplicate-model-error-tx))
        (is (contains? classes :internal-dispatcher-callable))
        (is (contains? classes :ui-unsafe-empty-form-dispatch))
        (is (contains? classes :sanitizer-dead-action)))))
  ;; When running inside live Seon branch REPL, verify full branch DB is 100% clean!
  (when-let [bdb (try (when-let [v (resolve (quote user/db))] (when (bound? v) (@v))) (catch Throwable _ nil))]
    (let [rep (closure/check-closure bdb)]
      (is (true? (:ok? rep)) (pr-str (:problems rep)))))))

(deftest ^{:cost/ticks 80000000} rule-audit-single-source-and-tail-eviction-test
  (testing "Single-source rule projections match rseed/starter-rules by namespace"
    (let [starter (rseed/starter-rules)
          by-ns (fn [ns-s] (filterv #(= ns-s (namespace (:seon.rule/id %))) starter))]
      (is (= (set (map :seon.rule/id (by-ns "rule.error"))) (set (map :seon.rule/id (error/error-rules)))))
      (is (= (set (map :seon.rule/id (by-ns "rule.preview"))) (set (map :seon.rule/id (ppol/preview-rules)))))
      (is (= (set (map :seon.rule/id (by-ns "rule.gate"))) (set (map :seon.rule/id (gate/gate-rules)))))))

  (testing "check-rule-bindings-and-refs catches synthetic holes and passes clean on fixed starter rules + docs"
    (with-test-store [{:keys [conn]}]
      (let [doc-tx (code/file-import-tx @conn "docs/primers.md" (slurp "docs/primers.md") false)
            starter (rseed/starter-rules)]
        (d/transact conn {:tx-data (concat doc-tx starter)})
        ;; Clean on fixed starter rules + fresh parse-md-sections docs!
        (let [clean (rcov/check-rule-bindings-and-refs @conn)]
          (is (true? (:ok? clean)) (pr-str (:problems clean))))
        ;; Catches synthetic $agent binding, missing doc id, and orphan $wm consumer!
        (let [bad-rules [{:seon.rule/id :rule.test/bad-in :seon.rule/stratum 20 :seon.rule/enabled? true
                          :seon.rule/query (quote [:find ?v :in $main $agent :where [$main ?v :seon.agent/id $agent]])
                          :seon.rule/assert (quote [[?v :x/y true]])}
                         {:seon.rule/id :rule.test/bad-doc :seon.rule/stratum 20 :seon.rule/enabled? true
                          :seon.rule/query (quote [:find ?d :in $branch :where [$branch ?d :seon.code.doc/id "docs/primers.md#nonexistent-slug"]])
                          :seon.rule/assert (quote [{:entity ?d :zone :workspace :priority 50}])}
                         {:seon.rule/id :rule.test/bad-wm :seon.rule/stratum 20 :seon.rule/enabled? true
                          :seon.rule/query (quote [:find ?e :in $wm :where [$wm ?e :wm/never-produced true]])
                          :seon.rule/assert (quote [[?e :x/z true]])}]
              bad (rcov/check-rule-bindings-and-refs @conn bad-rules)
              bcls (set (map :class (:problems bad)))]
          (is (false? (:ok? bad)))
          (is (contains? bcls :unknown-rule-input))
          (is (contains? bcls :missing-rule-doc-ref))
          (is (contains? bcls :orphan-wm-consumer)))
        ;; :rule.ctx/recent-eval-results bounds workspace evals to age <= 12
        (let [aid :ag/eval-window
              rec-rule (some #(when (= :rule.ctx/recent-eval-results (:seon.rule/id %)) %) starter)]
          (d/transact conn {:tx-data [{:seon.agent/id aid :seon.agent/branch :main}
                                      {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent aid :seon.agent.eval/turn 1
                                       :seon.agent.eval/code "(old)" :seon.agent.eval/value "1"}
                                      {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent aid :seon.agent.eval/turn 25
                                       :seon.agent.eval/code "(mid)" :seon.agent.eval/value "25"}
                                      {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent aid :seon.agent.eval/turn 30
                                       :seon.agent.eval/code "(new)" :seon.agent.eval/value "30"}]})
          (let [res (rules/step-rules nil {:main @conn :branch @conn} {:agent-id aid} [rec-rule] nil)
                proj-turns (into #{} (map #(:seon.agent.eval/turn (d/pull @conn (quote [:seon.agent.eval/turn]) (:entity %))))
                                 (:projections res))]
            (is (= #{25 30} proj-turns) "Turn 1 (age 29 > 12) must be excluded to prevent 568-eval fanout")))
        ;; audit-tail-eviction fits cleanly within 2,000-token budget with 0 high-pri drops
        (let [tail (rcov/audit-tail-eviction @conn)]
          (is (true? (:ok? tail)) (pr-str tail))
          (is (true? (get-in tail [:scenarios :quiet :within-budget?])))
          (is (true? (get-in tail [:scenarios :busy :within-budget?])))
          (is (empty? (get-in tail [:scenarios :busy :high-pri-dropped]))))))))
