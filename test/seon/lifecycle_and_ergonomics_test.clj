(ns seon.lifecycle-and-ergonomics-test
  "End-to-end proofs for Wave C3: agent lifecycle state machine (reconciler pruning, gate-blocked wakeups,
   post-ready! chat replies, merge-conflict/health-block notifications, mechanical commit hook,
   call subtask auto-close & branch mirroring) and REPL ergonomics (AUTO-PROOFS failures, stdout head+tail,
   expand! unwrap & no double-truncation, write-time :examples arity rail, *-test auto-ns, frame-scoped
   error hints, schema search/grep, explicit explore! errors, run-tests! :main-pass?, scoped health <20 MB)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.edit :as edit]
            [seon.code.parse :as parse]
            [seon.fork.calls :as fcalls]
            [seon.fork.helpers :as fhelpers]
            [seon.model.wire :as mwire]
            [seon.reconciler :as rec]
            [seon.render :as render]
            [seon.render.entities.work :as rwork]
            [seon.render.print :as sprint]
            [seon.render.tails :as rtails]
            [seon.repl :as repl]
            [seon.store :as store]
            [seon.task.gate :as gate]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.calls :as tcalls]
            [seon.transactor.merge :as tmerge]))

(deftest ^{:cost/ticks 120000000} reconciler-pruning-gate-wakeup-and-post-ready-reply-test
  (testing "Skip store/head for :main/nil, prune :testing/:merging/:settling, wake on :gate-blocked, and allow 1 post-ready! chat prose turn"
    (with-test-store [{:keys [conn cfg]}]
      ;; 1. Skip store/head when branch is :main or nil
      (d/transact conn {:tx-data [{:seon.agent/id :ag/main-only :seon.agent/branch :main :seon.agent/role :worker}
                                  {:seon.agent/id :ag/gate-w :seon.agent/branch :branch/gate-w :seon.agent/role :worker
                                   :seon.agent/auto-merge? true :seon.agent/task :task/need-proof}
                                  {:seon.task/id :task/need-proof :seon.task/title "Proof task"
                                   :seon.task/done? false :seon.task/tests #{'demo.missing-test/alpha-test}}
                                  {:seon.agent/id :ag/chat-w :seon.agent/branch :branch/chat-w :seon.agent/role :worker
                                   :seon.agent/auto-merge? true}
                                  {:seon.agent/id :ag/task-w :seon.agent/branch :branch/task-w :seon.agent/role :worker
                                   :seon.agent/auto-merge? true}]})
      (let [head-calls (volatile! [])]
        (with-redefs [store/head (fn [s b] (vswap! head-calls conj b) nil)]
          (let [st (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/main-only)]
            (is (= :waiting (:status st)))
            (is (empty? @head-calls) "store/head must never be called for :main"))))

      ;; 2. Gate-blocked (:test-missing) emits :gate-blocked wake-up message once per [aid head-tx] & prunes sets
      (store/branch! conn :main :branch/gate-w)
      (let [bconn (store/connect cfg :branch/gate-w)]
        (try
          (d/transact bconn {:tx-data [{:seon.agent/id :ag/gate-w :seon.agent/ready? true}]})
          (let [tx1 (:max-tx @bconn)
                _ (d/transact conn {:tx-data [{:seon.agent/id :ag/gate-w :seon.agent/tested-tx tx1}]})
                st0 (assoc (rec/reconciler-proc {:conn conn})
                           :testing #{[:ag/gate-w tx1] [:ag/gate-w 1]}
                           :merging #{[:ag/gate-w 1]})
                [st1 out1] (rec/reconciler-proc st0 :db {:prev-commit nil :commit (d/commit-id @conn) :tx (:max-tx @conn)})
                gmsg (some #(when (= :gate-blocked (:type %)) %) (:tx out1))]
            (is (some? gmsg) "reconciler must emit :gate-blocked wakeup message")
            (is (= :reconciler (:from gmsg)))
            (is (= :ag/gate-w (:to gmsg)))
            (is (str/includes? (:body gmsg) "demo.missing-test/alpha-test"))
            (is (contains? (:gate-notified st1) [:ag/gate-w tx1]))
            (is (empty? (:testing st1)) ":testing must prune tested-tx == tx and stale old tx")
            (is (empty? (:merging st1)) ":merging must prune stale old tx")
            ;; Second tick at same head-tx must NOT double-notify
            (let [[_ out2] (rec/reconciler-proc st1 :exits {:agent-id :ag/gate-w :ev :turn-idle :pid 1})]
              (is (nil? (some #(when (= :gate-blocked (:type %)) %) (:tx out2)))))
            ;; Advancing branch tx prunes :gate-notified and :merging
            (d/transact bconn {:tx-data [{:seon.agent/id :ag/gate-w :seon.agent/waiting? false}]})
            (let [[st3 _] (rec/reconciler-proc (assoc st1 :merging #{[:ag/gate-w tx1]})
                                               :exits {:agent-id :ag/gate-w :ev :branch-moved})]
              (is (empty? (:merging st3)) "head-tx advance must prune :merging")
              (is (empty? (:gate-notified st3)) "head-tx advance must prune :gate-notified"))
            ;; Settled/delivered call pruning in :settling
            (let [cid (random-uuid)
                  _ (d/transact conn {:tx-data [{:seon.agent.call/id cid :seon.agent.call/caller :ag/main-only
                                                 :seon.agent.call/callee :ag/gate-w :seon.agent.call/prompt "p"
                                                 :seon.agent.call/status :resolved :seon.agent.call/delivered? true}]})
                  [st4 _] (rec/reconciler-proc (assoc st1 :settling #{[:deliver cid] [:incoming cid] [:nudge cid]})
                                               :db {:prev-commit nil :commit (d/commit-id @conn) :tx (:max-tx @conn)})]
              (is (empty? (:settling st4)) ":settling must prune settled and delivered calls")))
          (finally (d/release bconn))))

      ;; 3. Post-(user/ready!) final prose turn for :from :user :type :chat, while :type :task merges directly
      (store/branch! conn :main :branch/chat-w)
      (store/branch! conn :main :branch/task-w)
      (let [m-chat (random-uuid)
            m-task (random-uuid)
            _ (d/transact conn {:tx-data [{:seon.agent.message/id m-chat :seon.agent.message/seq 1
                                           :seon.agent.message/from :user :seon.agent.message/to :ag/chat-w
                                           :seon.agent.message/type :chat :seon.agent.message/body "Fix X"}
                                          {:seon.agent.message/id m-task :seon.agent.message/seq 2
                                           :seon.agent.message/from :user :seon.agent.message/to :ag/task-w
                                           :seon.agent.message/type :task :seon.agent.message/body "Headless task"}]})
            cconn (store/connect cfg :branch/chat-w)
            tconn (store/connect cfg :branch/task-w)]
        (try
          ;; Chat worker edits + calls ready! (tool call, zero prose yet) -> gets 1 final prose turn
          (d/transact cconn {:tx-data [{:seon.agent/id :ag/chat-w :seon.agent/ready? true :seon.agent/waiting? false}]})
          (d/transact cconn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/chat-w
                                        :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                        :seon.agent.eval/call-id "tc-1" :seon.agent.eval/code "(user/ready!)"
                                        :seon.agent.eval/seen-messages #{m-chat}}]})
          (let [v1 (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/chat-w)]
            (is (= :runnable (:status v1)) "chat worker with 0 prose after ready! must get 1 reply turn")
            (is (true? (:needs-turn? v1)))
            (is (false? (:needs-test? v1))))
          ;; After emitting final prose reply -> transitions straight to :testing (ready! preserved!)
          (d/transact cconn {:tx-data [{:seon.agent/id :ag/chat-w :seon.agent/waiting? true}
                                       {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/chat-w
                                        :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0
                                        :seon.agent.eval/code "" :seon.agent.eval/thought "Done! Fixed X."}]})
          (let [v2 (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/chat-w)]
            (is (= :testing (:status v2)))
            (is (false? (:needs-turn? v2)))
            (is (true? (:needs-test? v2))))
          ;; Headless :type :task worker with ready! does NOT block waiting for prose
          (d/transact tconn {:tx-data [{:seon.agent/id :ag/task-w :seon.agent/ready? true :seon.agent/waiting? false}
                                       {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/task-w
                                        :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                        :seon.agent.eval/call-id "tc-t1" :seon.agent.eval/code "(user/ready!)"
                                        :seon.agent.eval/seen-messages #{m-task}}]})
          (let [vt (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/task-w)]
            (is (= :testing (:status vt)))
            (is (false? (:needs-turn? vt)))
            (is (true? (:needs-test? vt))))
          (finally
            (d/release cconn)
            (d/release tconn)))))))

(deftest ^{:cost/ticks 120000000} merge-conflict-notification-and-call-subtask-lifecycle-test
  (testing "handle-merge-op! conflict clears :ready? false & sends :merge-conflict; :answer-call auto-closes read-only subtasks & :create-call mirrors task to branch"
    (with-test-store [{:keys [conn cfg]}]
      ;; 1. Merge conflict clears :seon.agent/ready? false on branch and sends :merge-conflict message
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/c.clj"
                                                      "(ns demo.c)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))\n" false)})
      (d/transact conn {:tx-data [{:seon.agent/id :ag/conf :seon.agent/branch :branch/conf :seon.agent/role :worker}]})
      (store/branch! conn :main :branch/conf)
      ;; Divergent edit on :main and :branch/conf on same form
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/c.clj"
                                                      "(ns demo.c)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))\n" false)})
      (let [bconn (store/connect cfg :branch/conf)]
        (try
          (d/transact bconn {:tx-data (conj (code/file-import-tx @bconn "src/demo/c.clj"
                                                                 "(ns demo.c)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 3))\n" false)
                                            {:seon.agent/id :ag/conf :seon.agent/ready? true})})
          (let [[st' out] (tmerge/handle-merge-op! {:conn conn :cfg cfg :commit-git? false :writes 0 :msg-seq 0}
                                                   {:op :merge :branch :branch/conf :agent-id :ag/conf}
                                                   (d/commit-id @conn))
                msgs (->> (d/datoms @conn :avet :seon.agent.message/to :ag/conf)
                          (map #(d/pull @conn '[:seon.agent.message/from :seon.agent.message/type :seon.agent.message/body] (:e %)))
                          vec)
                b-ready (:seon.agent/ready? (d/pull (store/head conn :branch/conf) '[:seon.agent/ready?] [:seon.agent/id :ag/conf]))]
            (is (false? b-ready) "conflict must clear :seon.agent/ready? false on branch")
            (is (= 1 (count msgs)))
            (is (= :transactor (:seon.agent.message/from (first msgs))))
            (is (= :merge-conflict (:seon.agent.message/type (first msgs))))
            (is (seq (:db out)) "conflict must emit :db notice so reconciler wakes agent")
            (is (= 1 (:writes st'))))
          (finally (d/release bconn))))

      ;; 2. Call subtask auto-close on read-only research call + :create-call mirroring onto existing callee branch
      (d/transact conn {:tx-data [{:seon.task/id :template/subtask-research :seon.task/title "Research" :seon.task/template? true :seon.task/done? false}
                                  {:seon.task/id :task/coord-parent :seon.task/title "Coord Task" :seon.task/done? false}
                                  {:seon.agent/id :ag/coord :seon.agent/branch :main :seon.agent/role :coordinator :seon.agent/task :task/coord-parent}
                                  {:seon.agent/id :ag/res :seon.agent/branch :branch/res :seon.agent/role :worker}]})
      (store/branch! conn :main :branch/res)
      (let [replies (volatile! [])
            helpers {:reply! (fn [_ v] (vswap! replies conj v)) :db-notice tmerge/db-notice :focus-tx (fn [_] nil)}
            st0 {:conn conn :cfg cfg :msg-seq 10 :writes 0}
            [st1 _] (tcalls/handle-call-op! st0 {:op :create-call :caller :ag/coord :callee :ag/res
                                                 :prompt "Audit module" :role :research}
                                            (d/commit-id @conn) helpers)
            c-rep (first @replies)
            cid (:call-id c-rep)
            sub-id (:task c-rep)]
        (is (true? (:ok? c-rep)))
        (is (qualified-keyword? sub-id))
        ;; Verify :seon.agent/task mirrored onto existing callee branch DB!
        (is (= sub-id (:seon.agent/task (d/pull (store/head conn :branch/res) '[:seon.agent/task] [:seon.agent/id :ag/res]))))
        ;; Parent coordinator is blocked by :rule.gate/task-needs-open before answer
        (is (false? (:ok? (gate/evaluate-agent-gate @conn @conn :ag/coord))))
        ;; Answer call with 0 code edits -> subtask auto-closes (:seon.task/done? true) + coordinator gate unblocks!
        (tcalls/handle-call-op! st1 {:op :answer-call :call-id cid :result {:summary "All clear, 0 issues"}}
                                (d/commit-id @conn) helpers)
        (let [sub-ent (d/pull @conn '[:seon.task/done? :seon.task/summary] [:seon.task/id sub-id])]
          (is (true? (:seon.task/done? sub-ent)) "read-only call subtask must auto-close on :answer-call")
          (is (= "All clear, 0 issues" (:seon.task/summary sub-ent)))
          (is (true? (:ok? (gate/evaluate-agent-gate @conn @conn :ag/coord))) "coordinator gate must unblock"))))))

(deftest ^{:cost/ticks 90000000} tails-wire-cliff-and-expand-unwrap-test
  (testing "AUTO-PROOFS FAIL shows :failures, format-eval-output has no 8001->2000 cliff, and expand! unwraps without double truncation"
    ;; 1. AUTO-PROOFS FAIL renders up to 3 lines of :failures
    (let [res (rtails/render-branch-status-tail
               {:seon.tail/branch-status
                {:branch :branch/demo :max-tx 42 :branch-edited ["demo/f"]
                 :last-proofs {:tx 42 :ok? false :ran 4 :passed 1 :failed 3 :ms 9.5
                               :failures ["demo-test/t1: expected 1 got 2"
                                          "demo/f [gen]: output invalid"
                                          "demo-test/t3: boom"
                                          "demo-test/t4: fourth should be capped"]}}})
          txt (render/hiccup->text (:seon.ui/hiccup res))]
      (is (str/includes? txt "AUTO-PROOFS (tx 42): FAIL (1/4 in 9.5 ms)"))
      (is (str/includes? txt "demo-test/t1: expected 1 got 2"))
      (is (str/includes? txt "demo/f [gen]: output invalid"))
      (is (str/includes? txt "demo-test/t3: boom"))
      (is (not (str/includes? txt "fourth should be capped"))))

    ;; 2. format-eval-output has NO 8001 -> 2000 stdout cliff (head+tail preview ~7600 chars + handle pointer)
    (let [big-out (str "HEAD-MARK " (apply str (repeat 7980 \x)) " TAIL-MARK!")
          _ (is (= 8001 (count big-out)))
          formatted (mwire/format-eval-output {:handle 'result/e77 :ok? true :value "nil" :out big-out :max-chars 8000})]
      (is (> (count formatted) 7000) "8001-char stdout must NOT collapse to 2000 chars")
      (is (str/includes? formatted "HEAD-MARK"))
      (is (str/includes? formatted "TAIL-MARK!"))
      (is (str/includes? formatted "(:seon.eval/out (meta #'result/e77))")))

    ;; 3. expand! bypasses double-truncation and unwraps chained/self handles cleanly
    (let [rns (create-ns 'result)
          big-str (str/join "\n" (map #(str "line-" % " payload") (range 1 55)))]
      (try
        (intern rns 'e_c3_str big-str)
        (let [exp1 (sprint/expand-result nil 'result/e_c3_str)
              md1 (:md exp1)
              wired (mwire/format-eval-output {:handle 'result/e_c3_wire :ok? true :value (pr-str md1)
                                               :code "(user/expand! 'result/e_c3_str)"})]
          (is (true? (:ok? exp1)))
          (is (false? (:more? exp1)))
          (is (str/includes? md1 "line-1 payload"))
          (is (str/includes? md1 "line-54 payload"))
          (is (str/includes? wired "line-54 payload") "format-eval-output must not re-truncate expand! output")
          (is (not (str/includes? wired "result/e_c3_wire")) "must not emit self-handle truncation"))
        ;; Chained handle unwrap: e_c3_chain -> e_c3_orig
        (intern rns 'e_c3_orig {:items (vec (range 40))})
        (let [v-chain (intern rns 'e_c3_chain "Vector (40 items; showing 1..5)")]
          (alter-meta! v-chain assoc :seon.eval/code "(user/expand! 'result/e_c3_orig {:path [:items] :offset 0 :limit 5})")
          (let [unwrapped (sprint/expand-result nil 'result/e_c3_chain {:path [:items] :offset 10 :limit 4})]
            (is (true? (:ok? unwrapped)))
            (is (= 'result/e_c3_orig (:handle unwrapped)))
            (is (= [10 11 12 13] (:preview unwrapped)))))
        ;; Self-handle terminates cleanly without recursing or pointing to itself
        (intern rns 'e_c3_self 'result/e_c3_self)
        (let [self-res (sprint/expand-result nil 'result/e_c3_self)]
          (is (true? (:ok? self-res)))
          (is (true? (:self-handle? self-res))))
        (finally
          (ns-unmap rns 'e_c3_str)
          (ns-unmap rns 'e_c3_orig)
          (ns-unmap rns 'e_c3_chain)
          (ns-unmap rns 'e_c3_self))))))

(deftest ^{:cost/ticks 150000000} write-time-rails-search-grep-explore-and-scoped-health-test
  (testing ":examples arity rail, *-test auto-ns, enrich-eval-error, search/grep schemas, explore! errors, run-tests! :main-pass?, work.clj guidance, and scoped health < 20 MB"
    (with-test-store [{:keys [conn cfg]}]
      ;; 1. Write-time :examples arity validation in add-fn! and set-meta! + auto-seed *-test ns
      (d/transact conn {:tx-data [{:seon.agent/id :ag/ergo :seon.agent/branch :branch/ergo :seon.agent/role :worker
                                   :seon.agent/scope 'demo.ergo}]})
      (store/branch! conn :main :branch/ergo)
      (let [bconn (store/connect cfg :branch/ergo)]
        (try
          (binding [fcalls/*agent-ctx* {:conn bconn :branch :branch/ergo :agent-id :ag/ergo}]
            ;; Rejects [[[]]] and [[[[]] :ok]] for 0-arg fn; accepts [[[] :ok]]
            (let [bad1 (edit/add-fn! 'demo.ergo "(defn z {:malli/schema [:=> {:examples [[[]]]} [:cat] :keyword]} [] :ok)" {:eval? false})
                  bad2 (edit/add-fn! 'demo.ergo "(defn z {:malli/schema [:=> {:examples [[[[]] :ok]]} [:cat] :keyword]} [] :ok)" {:eval? false})
                  good (edit/add-fn! 'demo.ergo "(defn z \"Zebra doc token\" {:malli/schema [:=> {:examples [[[] :ok]]} [:cat] :keyword]} [] :ok)" {:eval? false})]
              (is (false? (:ok? bad1)))
              (is (= :invalid-malli-examples (:error bad1)))
              (is (false? (:ok? bad2)))
              (is (= :malli-examples-arity-mismatch (:error bad2)))
              (is (true? (:ok? good))))
            ;; set-meta! also rejects bad :examples
            (let [sm-bad (edit/set-meta! 'demo.ergo/z {:malli/schema [:=> {:examples [[[]]]} [:cat] :keyword]} {:eval? false})]
              (is (false? (:ok? sm-bad))))
            ;; add-fn! on brand-new *-test namespace auto-seeds clean (ns ... (:require [clojure.test :refer [deftest is testing]]))
            (let [tn (edit/add-fn! 'demo.brand-new-test "(deftest auto-seeded-test (is (= 2 (+ 1 1))))")
                  ns-src (:seon.code.form/src (d/pull @bconn '[:seon.code.form/src] [:seon.code.form/key "demo.brand-new-test#ns"]))]
              (is (true? (:ok? tn)))
              (is (true? (:seeded-ns? tn)))
              (is (str/includes? (str ns-src) "[clojure.test :refer [deftest is testing]]")))
            ;; user/search indexes :seon.schema/{key,doc,form} and :seon.code.var/doc; grep-src falls back to :seon.schema/*
            (d/transact bconn {:tx-data [{:seon.schema/key :seon.ergo/alpha
                                          :seon.schema/form [:map [:seon.ergo/unique-field :string]]
                                          :seon.schema/doc "Quantum flamingo schema doc"}]})
            (let [s-key (repl/search "seon.ergo/alpha")
                  s-doc (repl/search "quantum flamingo")
                  s-frm (repl/search "unique-field")
                  s-vdoc (repl/search "zebra doc token")
                  g-sch (repl/grep-src ":seon.ergo/alpha")]
              (is (some #{:seon.ergo/alpha} (:schemas s-key)))
              (is (some #{:seon.ergo/alpha} (:schemas s-doc)))
              (is (some #{:seon.ergo/alpha} (:schemas s-frm)))
              (is (some #{'demo.ergo/z} (:vars s-vdoc)))
              (is (some #(= :seon.ergo/alpha (:schema %)) g-sch)))
            ;; resolve-explore-ref & explore! return explicit {:error :entity-not-found :ref ...}
            (let [ex-miss (fhelpers/explore! :nonexistent/ref-xyz)]
              (is (= {:error :entity-not-found :ref :nonexistent/ref-xyz} ex-miss)))
            ;; run-tests! surfaces :main-pass? false on pre-existing :main failure + concise :failure-lines
            (let [_ (edit/add-fn! 'demo.brand-new-test "(deftest failing-on-main-test (is (= 1 2)))")
                  _ (d/transact conn {:tx-data [{:seon.test/digest "dg-main-fail-1" :seon.test/var 'demo.brand-new-test/failing-on-main-test
                                                 :seon.test/kind :deftest :seon.test/pass? false :seon.test/ms 1.0 :seon.test/report "pre-existing fail"}]})
                  tr (repl/run-tests! 'demo.brand-new-test/failing-on-main-test)]
              (is (false? (:ok? tr)))
              (is (false? (:main-pass? (first (:failures tr)))))
              (is (seq (:failure-lines tr)))
              (is (str/includes? (first (:failure-lines tr)) "PRE-EXISTING FAIL ON :main")))
            ;; Scoped (repl/health) stays strictly < 20 MB!
            (let [m (code/measure-call #(repl/health))
                  mb (/ (double (:bytes m)) 1048576.0)]
              (is (true? (:scoped? (:val m))))
              (is (< mb 20.0) (str "Scoped health exceeded 20 MB: " mb " MB"))))
          (finally
            (d/release bconn)
            (remove-ns 'demo.brand-new-test)))))

    ;; 2. enrich-eval-error scopes Usage: strictly to failing symbol & hints 2-arg (d/datoms db :aevt)
    (let [hidx {"db" {:arglists "([])" :doc "Branch db."}
                "callers" {:arglists "([sym & more])" :doc "Find callers."}}
          e-unrel (parse/enrich-eval-error "(let [db (user/db)] (inc 1 2))"
                                           "Wrong number of args (2) passed to: clojure.core/inc" hidx)
          e-match (parse/enrich-eval-error "(user/callers 'a 'b)"
                                           "Wrong number of args (2) passed to: user/callers" hidx)
          e-aevt  (parse/enrich-eval-error "(d/datoms (user/db) :aevt)"
                                           "Eval exceeded allocation budget (limit 268435456 bytes)." hidx)]
      (is (not (str/includes? e-unrel "Usage: user/db")) "must not attach Usage: for unrelated helper")
      (is (str/includes? e-match "Usage: user/callers"))
      (is (str/includes? e-aevt "Pass the attribute as 3rd arg: (d/datoms db :aevt :attr/name)")))

    ;; 3. work.clj guidance clarifies with-test-store is ONLY inside deftest bodies
    (let [txt (render/hiccup->text (:seon.ui/hiccup (rwork/render-ctx-snapshot {:seon.ctx/snapshot {:seon.agent/id :ag/w :seon.agent/branch :branch/w :seon.agent/role :worker}})))]
      (is (str/includes? txt "never wrap"))
      (is (str/includes? txt "(seon.test-fixture/with-test-store [{:keys [conn]}] ...)")))))
