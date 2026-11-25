(ns seon.health-liveness-and-closure-test
  "Comprehensive verification of finite-state liveness (`seon.health.liveness`),
   shared reconciler policy (`seon.reconciler.policy`), cache/diff/contract invariants,
   single-source rule coverage (`seon.health.coverage`), and live runtime fixes."
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.agent :as agents]
            [seon.code :as code]
            [seon.ctx.diff :as diff]
            [seon.error :as error]
            [seon.health :as health]
            [seon.health.contracts :as contracts]
            [seon.health.coverage :as cov]
            [seon.health.efficiency :as eff]
            [seon.health.liveness :as liveness]
            [seon.reconciler :as rec]
            [seon.reconciler.policy :as policy]
            [seon.render.fit :as fit]
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
     [{:seon.agent.eval/turn 1 :seon.agent.eval/code "" :seon.agent.eval/error "Model error: :retryable? false"}] []))))
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

    (with-test-store [{:keys [conn]}]
      (let [st0 {:conn conn :walk {:units {} :order []} :live-streams {:w/bad {:status :thinking :phase :thinking}}
                 :agent-states {:w/bad :active} :errors [] :frames 0 :last-view-sig nil :last-agg-rate ""}
            hstream @(var seon.web/handle-stream-msg)
            st1 (hstream st0 {:agent-id :w/bad :status :error :seon.error/kind :model/api-error :message "HTTP 400"})]
        (is (nil? (get-in st1 [:agent-states :w/bad])) "Errored agent must NOT stay :active")
        (is (nil? (get-in st1 [:live-streams :w/bad])))
        (is (= 1 (count (:errors st1)))))

      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/branch :main :seon.agent/entry? true}]})
      (let [st {:conn conn :server-in-ch nil :ui-ch nil :branch-feedback nil}]
        (doseq [empty-f [{} nil]]
          (let [r-chat (actions/invoke-call! st {:call-form (list (quote seon.web/chat-agent-form!) :agent/root) :form-fields empty-f})
                r-pin  (actions/invoke-call! st {:call-form (list (quote seon.web/pin-ref-form!) :agent/root) :form-fields empty-f})
                r-sem  (actions/invoke-call! st {:call-form (list (quote seon.web/semantic-search-form!)) :form-fields empty-f})]
            (is (true? (:ok? r-chat)) (str "chat-agent-form! failed on " (pr-str empty-f) ": " (pr-str r-chat)))
            (is (true? (:ok? r-pin))  (str "pin-ref-form! failed on " (pr-str empty-f) ": " (pr-str r-pin)))
            (is (true? (:ok? r-sem))  (str "semantic-search-form! failed on " (pr-str empty-f) ": " (pr-str r-sem)))))
        (let [r-inv (actions/invoke-call! st {:call-form (list (quote seon.web/invoke-call!) {}) :form-fields {}})]
          (is (false? (:ok? r-inv)))
          (is (= :seon.web/not-callable (:error r-inv))))
        (let [san (sanitize/sanitize-hiccup-with-report
                   [:button {:type "button" :on-click (list (quote seon.web/send-message!) :a "hi")} "x"]
                   {:allow-seon-actions? true})]
          (is (seq (:dropped san)))))))

  (testing "ancestor-within?, ordered branch-changes, dirty heavy-eval re-check, liveness branch guard, and unified contract checkers"
    (with-test-store [{:keys [conn cfg]}]
      (let [c0 (store/live-cid @conn)]
        (store/branch! conn :main :branch/sib-a)
        (store/branch! conn :main :branch/sib-b)
        (let [ca (store/connect cfg :branch/sib-a)
              cb (store/connect cfg :branch/sib-b)]
          (try
            (d/transact ca {:tx-data [{:seon.agent/id :ag/a :seon.agent/branch :branch/sib-a :seon.agent/goal "a"}]})
            (let [ca1 (store/live-cid @ca)]
              (d/transact ca {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/a
                                         :seon.agent.eval/turn 1 :seon.agent.eval/code "(heavy)" :seon.agent.eval/bytes 200000000}]})
              (d/transact cb {:tx-data [{:seon.agent/id :ag/b :seon.agent/branch :branch/sib-b :seon.agent/goal "b"}]})
              (let [ca2 (store/live-cid @ca)
                    cb1 (store/live-cid @cb)
                    chs (diff/branch-changes @conn @ca)]
                (is (true? (diff/ancestor-within? @ca c0 ca2 16)))
                (is (true? (diff/ancestor-within? @ca ca1 ca2 16)))
                (is (false? (diff/ancestor-within? @ca cb1 ca2 16)) "Unrelated sibling commit must return false without d/changes throw")
                (is (= chs (vec (sort-by (fn [dt] [(long (:tx dt)) (long (:e dt)) (str (:a dt))]) chs))) "branch-changes preserves tx ordering")
                (is (empty? (filter #(= :heavy-eval (:class %)) (eff/eval-efficiency-warnings @ca))) "Heavy eval suppressed before code edit")
                (d/transact ca {:tx-data (code/file-import-tx @ca "src/seon/demo/dirty_eval.clj" "(ns seon.demo.dirty-eval)
(defn f {:malli/schema [:=> [:cat] :int]} [] 1)
" false)})
                (is (= 1 (count (filter #(= :heavy-eval (:class %)) (eff/eval-efficiency-warnings @ca))))
                    "Code edit after suppressed heavy eval must invalidate eval-eff anchor and surface :heavy-eval")
                (is (empty? (health/liveness-violations @ca)))))
            (finally (d/release ca) (d/release cb)))))

      (d/transact conn {:tx-data (concat
                                  (code/file-import-tx @conn "src/seon/demo/c_par.clj"
                                    "(ns seon.demo.c-par)
(defn ^:seon.flow/edge io-op [] :ok)
(defn bad-pure \"See `seon.demo.c-par/missing-var`.\" {:malli/schema [:=> [:cat :int] :any]} [x] (io-op))
" false)
                                  [{:seon.schema/key :seon.demo.c-par/ent
                                    :seon.schema/form [:map {:seon.render/hiccup (quote seon.demo.c-par/gone-renderer)} [:seon.demo.c-par/id :keyword]]}])})
      (let [db @conn
            bs-full (contracts/broken-symbol-refs db)
            bs-scoped (contracts/broken-symbol-refs db nil {:target-syms [(quote seon.demo.c-par/gone-renderer)]})
            bd-full (contracts/broken-doc-refs db)
            bd-nss (contracts/broken-doc-refs db nil nil {:target-nss [(quote seon.demo.c-par)]})
            bd-syms (contracts/broken-doc-refs db nil nil {:target-syms [(quote seon.demo.c-par/missing-var)]})
            im-full (contracts/impure-generatable-fns db)
            im-nss (contracts/impure-generatable-fns db nil nil {:target-nss [(quote seon.demo.c-par)]})
            im-edges (contracts/impure-generatable-fns db nil nil {:edge-syms [(quote seon.demo.c-par/io-op)]})]
        (is (= (mapv :subject bs-full) (mapv :subject bs-scoped)))
        (is (= (mapv :subject bd-full) (mapv :subject bd-nss)))
        (is (= (mapv :subject bd-full) (mapv :subject bd-syms)))
        (is (= (mapv :subject im-full) (mapv :subject im-nss)))
        (is (= (mapv :subject im-full) (mapv :subject im-edges)))))))

(defn- mk-tail-cand [id schema pri boost data]
  (cond-> {:entity id :schema schema :zone :ephemeral-tail :priority pri :source :rule :data data}
    boost (assoc :rank/boost boost :params {:rank/boost boost})))

(defn- sample-tail-scenarios [db]
  (let [doc-pull (fn [did]
                   (or (when (and db (contains? (:schema db) :seon.code.doc/id)
                                  (first (d/datoms db :avet :seon.code.doc/id did)))
                         (dissoc (d/pull db (quote [:seon.code.doc/id :seon.code.doc/path :seon.code.doc/heading :seon.code.doc/body])
                                         [:seon.code.doc/id did]) :db/id))
                       {:seon.code.doc/id did :seon.code.doc/path "docs/primers.md"
                        :seon.code.doc/heading "## Primer" :seon.code.doc/body (apply str (repeat 40 "Keep edits small and verified. "))}))
        eval1 {:seon.agent.eval/handle (quote result/e10) :seon.agent.eval/turn 10
               :seon.agent.eval/code "(user/health)" :seon.agent.eval/value "{:ok? true}" :seon.agent.eval/ms 12.0}
        eval2 {:seon.agent.eval/handle (quote result/e9) :seon.agent.eval/turn 9
               :seon.agent.eval/code "(user/edit-fn! (quote seon.web/invoke-call!) ...)" :seon.agent.eval/value "{:ok? true}" :seon.agent.eval/ms 25.0}
        eval3 {:seon.agent.eval/handle (quote result/e8) :seon.agent.eval/turn 8
               :seon.agent.eval/code "(user/run-tests! (quote seon.error-test))" :seon.agent.eval/value "{:ok? false :failed 1}" :seon.agent.eval/ms 140.0}
        err-ent {:seon.error/id (random-uuid) :seon.error/kind :model/api-error :seon.error/severity :error
                 :seon.error/source :model :seon.error/status :open :seon.error/message "HTTP 400 context overflow"}
        fix-ent {:seon.fix/id :fix/context-overflow :seon.fix/error-class :model/api-error
                 :seon.fix/symptom "Repeated 400 retry" :seon.fix/root-cause "Missing non-retryable 400 check"
                 :seon.fix/change-summary "Halt on 400 in 1 turn"}
        test-ent {:seon.test/digest "dg-fail-1" :seon.test/var (quote seon.reconciler-test/status-derivation-test)
                  :seon.test/kind :deftest :seon.test/pass? false :seon.test/ms 18.0 :seon.test/report "expected :waiting got :runnable"}
        var-ent (fn [i] {:seon.code.var/name (symbol "seon.reconciler" (str "helper-" i))
                         :seon.code.var/ns (quote seon.reconciler)
                         :seon.code.var/doc "Pure reconciler policy helper."
                         :seon.code.var/arglists (quote ([state]))})]
    {:quiet [(mk-tail-cand :tail/eval-10 :seon.agent.eval/entity 90 nil eval1)
             (mk-tail-cand :tail/doc-proofs :seon.code.doc/entity 89 nil (doc-pull "docs/primers.md#proofs"))]
     :busy [(mk-tail-cand :tail/open-err :seon.error/entity 99 0.25 err-ent)
            (mk-tail-cand :tail/fix :seon.fix/entity 98 nil fix-ent)
            (mk-tail-cand :tail/fail-test :seon.test/entity 97 0.20 test-ent)
            (mk-tail-cand :tail/branch-health :seon.code.var/entity 96 0.25 (var-ent 0))
            (mk-tail-cand :tail/doc-history :seon.code.doc/entity 95 nil (doc-pull "docs/primers.md#history-and-time-travel"))
            (mk-tail-cand :tail/doc-contracts :seon.code.doc/entity 92 nil (doc-pull "docs/primers.md#contracts"))
            (mk-tail-cand :tail/eval-10 :seon.agent.eval/entity 90 nil eval1)
            (mk-tail-cand :tail/eval-9 :seon.agent.eval/entity 90 nil eval2)
            (mk-tail-cand :tail/eval-8 :seon.agent.eval/entity 90 nil eval3)
            (mk-tail-cand :tail/frontier-1 :seon.code.var/entity 76 nil (var-ent 1))
            (mk-tail-cand :tail/frontier-2 :seon.code.var/entity 76 nil (var-ent 2))
            (mk-tail-cand :tail/frontier-3 :seon.code.var/entity 76 nil (var-ent 3))]}))

(defn- audit-tail-eviction
  ([db] (audit-tail-eviction db nil))
  ([db opts]
   (let [budget (long (or (:tail-token-budget opts) 2000))
         scenarios (or (:scenarios opts) (sample-tail-scenarios db))
         fit-opts {:target :wire :strategy :depth-first :token-budget budget
                   :main-db db :fallback-renderers fit/fallback-entity-renderers}
         run-one (fn [items]
                   (let [pri-by-e (into {} (map (juxt :entity :priority)) items)
                         res (fit/fit-projections db items fit-opts)
                         sel (:selected res)
                         drp (:dropped res)
                         hi-dropped (filterv #(>= (long (or (get pri-by-e (:entity %)) 0)) 96) drp)]
                     {:candidates (count items) :selected (count sel) :dropped (count drp)
                      :tokens (long (:tokens res 0)) :budget budget
                      :within-budget? (<= (long (:tokens res 0)) budget)
                      :high-pri-dropped (mapv :entity hi-dropped)}))
         res-map (into {} (map (fn [[k items]] [k (run-one items)])) scenarios)]
     {:ok? (every? (fn [[_ r]] (and (:within-budget? r) (empty? (:high-pri-dropped r)))) res-map)
      :tail-budget budget :scenarios res-map})))

(deftest ^{:cost/ticks 80000000} rule-audit-single-source-and-tail-eviction-test
  (testing "Single-source rule projections match rseed/starter-rules by namespace"
    (let [starter (rseed/starter-rules)
          by-ns (fn [ns-s] (filterv #(= ns-s (namespace (:seon.rule/id %))) starter))]
      (is (= (set (map :seon.rule/id (by-ns "rule.error"))) (set (map :seon.rule/id (error/error-rules)))))
      (is (= (set (map :seon.rule/id (by-ns "rule.preview"))) (set (map :seon.rule/id (ppol/preview-rules)))))
      (is (= (set (map :seon.rule/id (by-ns "rule.gate"))) (set (map :seon.rule/id (gate/gate-rules)))))))

  (testing "seon.health.coverage/detect-rule-holes catches synthetic holes and passes clean on fixed starter rules + docs"
    (with-test-store [{:keys [conn]}]
      (let [doc-tx (code/file-import-tx @conn "docs/primers.md" (slurp "docs/primers.md") false)
            live-tx (code/file-import-tx @conn "src/seon/demo/cov_helper.clj" "(ns seon.demo.cov-helper)
(defn ok? {:malli/schema [:=> [:cat :any] :boolean]} [x] (some? x))
" false)
            starter (rseed/starter-rules)]
        (d/transact conn {:tx-data (concat doc-tx live-tx starter)})
        (let [atlas (cov/rule-atlas @conn)
              clean-holes (cov/detect-rule-holes @conn atlas)]
          (is (empty? clean-holes) (pr-str clean-holes)))
        (let [bad-rules [{:seon.rule/id :rule.test/bad-in :seon.rule/stratum 20 :seon.rule/enabled? true
                          :seon.rule/query (quote [:find ?v :in $main $agent :where [$main ?v :seon.agent/id $agent]])
                          :seon.rule/assert (quote [[?v :x/y true]])}
                         {:seon.rule/id :rule.test/bad-doc :seon.rule/stratum 20 :seon.rule/enabled? true
                          :seon.rule/query (quote [:find ?d :in $branch :where [$branch ?d :seon.code.doc/id "docs/primers.md#nonexistent-slug"]])
                          :seon.rule/assert (quote [{:entity ?d :zone :workspace :priority 50}])}
                         {:seon.rule/id :rule.test/bad-wm :seon.rule/stratum 20 :seon.rule/enabled? true
                          :seon.rule/query (quote [:find ?e :in $wm :where [$wm ?e :wm/never-produced true]])
                          :seon.rule/assert (quote [[?e :x/z true]])}
                         {:seon.rule/id :rule.test/bad-where-fn :seon.rule/stratum 20 :seon.rule/enabled? true
                          :seon.rule/query (quote [:find ?e :in $branch :where [$branch ?e :seon.task/id ?t] [(seon.missing.ns/no-such-fn ?t)]])
                          :seon.rule/assert (quote [[?e :x/w true]])}]
              bad-holes (cov/detect-rule-holes @conn bad-rules)
              bcls (set (map :class bad-holes))]
          (is (contains? bcls :unknown-rule-input))
          (is (contains? bcls :missing-rule-doc-ref))
          (is (contains? bcls :orphan-wm-consumer))
          (is (contains? bcls :dead-rule-fn)))
        (let [aid :ag/eval-window
            err-rule (some #(when (= :rule.ctx/open-errors (:seon.rule/id %)) %) starter)]
        (d/transact conn {:tx-data [{:seon.agent/id aid :seon.agent/branch :main}
                                    (error/error-tx {:kind :demo/audit-err :severity :error :source :eval
                                                     :agent aid :message "Audit open error"})]})
        (let [res (rules/step-rules nil {:main @conn :branch @conn} {:agent-id aid} [err-rule] nil)]
          (is (pos? (count (:projections res))) ":rule.ctx/open-errors must project open agent errors cleanly")))
        (let [tail (audit-tail-eviction @conn)]
          (is (true? (:ok? tail)) (pr-str tail))
          (is (true? (get-in tail [:scenarios :quiet :within-budget?])))
          (is (true? (get-in tail [:scenarios :busy :within-budget?])))
          (is (empty? (get-in tail [:scenarios :busy :high-pri-dropped]))))))))