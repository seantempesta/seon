(ns seon.agent.turn-test
  (:require
   [cljs.test :refer [async deftest is]]
   [my.blob :as blob]
   [my.plan :as plan]
   [seon.agent.turn :as turn]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.execution :as execution]
   [seon.execution.host :as execution.host]
   [seon.repl.internal :as repl-internal]))

(def database
  {:db-name "turn-test"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(def resolution
  {:seon.ai/resolved-config {:seon.ai/provider :deepseek}
   :seon.ai/provenance {:seon.ai/provider :default}})

(def prompt-value
  {:seon.render/text "remote prompt"
   :seon.ai/system-prompt "frozen system"
   :seon.ai/config-resolution resolution
   :seon.config/repl-mode :batch
   :seon.eval/ns 'my.agent.agent-1})

(def ^:private reply-program (deref #'turn/reply-program))
(defn- ask-and-eval-reply-promise
  [& arguments]
  (apply (deref #'turn/ask-and-eval-reply!) arguments))

(deftest llm-attempt-fallback-reads-the-config-owner
  (with-redefs [config/llm-attempt-timeout-ms (constantly 3210)]
    (is (= 3210
           (@#'turn/effective-llm-attempt-timeout-ms {})))
    (is (= 99
           (@#'turn/effective-llm-attempt-timeout-ms
            {:seon.ai/agent-attempt-timeout-ms 99})))))

(deftest batch-replies-use-the-shared-ordered-program-projection
  (let [program
        (reply-program
          (str "(ns my.feature.ui (:require [my.feature.model]))\n"
               "(defn view [] (my.feature.model/value))\n"
               "(ns my.feature.model (:require [seon.schema :as schema]))\n"
               "(def before-schema 1)\n"
               "(schema/register! ::id :string)\n"
               "(def value 2)")
          false
          'my.agent.agent-1)]
    (is (= ['my.agent.agent-1 'my.feature.model 'my.feature.ui]
           (:seon.repl/namespace-order program)))
    (is (= [[:namespace "(ns my.feature.model (:require [seon.schema :as schema]))"]
            [:schema "(schema/register! ::id :string)"]
            [:form "(def before-schema 1)"]
            [:form "(def value 2)"]
            [:namespace "(ns my.feature.ui (:require [my.feature.model]))"]
            [:form "(defn view [] (my.feature.model/value))"]]
           (->> (:seon.repl/eval-entries program)
                (filter :seon.repl/phase)
                (mapv (juxt :seon.repl/phase :seon.repl/source)))))))

(deftest stream-replies-retain-the-existing-first-form-boundary
  (let [program (reply-program ";; first\n(+ 1 2)\n(+ 3 4)" true
                               'my.agent.agent-1)
        forms (->> (:seon.repl/eval-entries program)
                   (filter #(= :form (:seon.repl/kind %)))
                   vec)]
    (is (= ["(+ 1 2)"] (mapv :seon.repl/source forms)))
    (is (= (str "first\n"
                "; stream mode executed the first complete form; "
                "1 further form was not executed — resend the next form.")
           (:seon.repl/narration (first forms))))))

(deftest stream-tail-narration-counts-only-unexecuted-complete-forms
  (let [program (reply-program
                 "(+ 1 2)\n;; between\n(+ 3 4)\n(+ 5 6)\n(incomplete"
                 true
                 'my.agent.agent-1)
        form (->> (:seon.repl/eval-entries program)
                  (filter #(= :form (:seon.repl/kind %)))
                  first)]
    (is (= "; stream mode executed the first complete form; 2 further forms were not executed — resend the next form."
           (:seon.repl/narration form)))
    (is (= "(+ 1 2)" (:seon.repl/source form)))))

(deftest stream-single-form-needs-no-tail-narration
  (let [program (reply-program "(+ 1 2)\n;; trailing thought" true
                               'my.agent.agent-1)
        form (->> (:seon.repl/eval-entries program)
                  (filter #(= :form (:seon.repl/kind %)))
                  first)]
    (is (= "" (:seon.repl/narration form)))))

(deftest planner-handoff-publishes-the-identical-program-and-eval-batch-once
  (async done
    (let [original-put blob/put!
          original-parse repl-internal/parse-program
          original-eval turn/eval-parsed!
          original-publish plan/publish-generated-program!
          parse-calls (atom 0)
          published (atom nil)
          program {:seon.repl/eval-entries
                   [{:seon.repl/kind :form :seon.repl/source "(+ 1 2)"}]
                   :seon.repl/namespaces []
                   :seon.repl/namespace-order []
                   :seon.repl/errors []}
          batch {:seon.eval/ids ["eval-1"]
                 :seon.eval/n-ok 1
                 :seon.eval/n-fail 0}]
      (set! blob/put!
            (fn [_]
              (js/Promise.resolve
               {:my.blob/ok? false :my.blob/error "test capture omitted"})))
      (set! repl-internal/parse-program
            (fn [& _]
              (swap! parse-calls inc)
              program))
      (set! turn/eval-parsed!
            (fn [_ _ parsed _ _ _]
              (is (identical? (:seon.repl/eval-entries program) parsed))
              (js/Promise.resolve batch)))
      (set! plan/publish-generated-program!
            (fn [request]
              (reset! published request)
              (js/Promise.resolve
               {:my.plan/ok? true
                :my.plan/root "root"
                :my.plan/diff
                {:my.plan/added 0 :my.plan/dropped 0 :my.plan/updated 0}
                :my.plan/namespace-ids {}
                :seon.eval/ids (:seon.eval/ids batch)})))
      (-> (ask-and-eval-reply-promise
           {:text "ignored"} "planner" "turn-1" "run-1" false
           'my.agent.planner database)
          (.then
           (fn [result]
             (is (= 1 (:seon.agent/eval-count result)))
             (is (= 1 @parse-calls))
             (is (identical? program (:my.plan/program @published)))
             (is (identical? batch (:my.plan/eval-batch @published)))
             (is (= database (::db/db @published)))
             (is (= "run-1" (:seon.agent.run/id @published)))
             (is (= "turn-1" (:seon.agent.turn/id @published)))))
          (.finally
           (fn []
             (set! blob/put! original-put)
             (set! repl-internal/parse-program original-parse)
             (set! turn/eval-parsed! original-eval)
             (set! plan/publish-generated-program! original-publish)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest planner-publication-failure-closes-the-existing-turn-as-error
  (async done
    (let [original-put blob/put!
          original-parse repl-internal/parse-program
          original-eval turn/eval-parsed!
          original-publish plan/publish-generated-program!
          original-allocate db.id/allocate!
          original-transact db/transact!
          original-context db/with-tx-context
          transactions (atom [])
          program {:seon.repl/eval-entries []
                   :seon.repl/namespaces []
                   :seon.repl/namespace-order []
                   :seon.repl/errors []}
          batch {:seon.eval/ids []
                 :seon.eval/n-ok 0
                 :seon.eval/n-fail 0}]
      (set! blob/put!
            (fn [_]
              (js/Promise.resolve
               {:my.blob/ok? false :my.blob/error "test capture omitted"})))
      (set! repl-internal/parse-program (fn [& _] program))
      (set! turn/eval-parsed! (fn [& _] (js/Promise.resolve batch)))
      (set! plan/publish-generated-program!
            (fn [_]
              (js/Promise.resolve
               {:my.plan/ok? false
                :my.plan/root "root"
                :my.plan/error "namespace DAG publication failed"})))
      (set! db.id/allocate!
            (fn [request]
              (let [ids {::turn/turn-allocation "turn-failed"}]
                (js/Promise.resolve
                 {::db.id/ids ids
                  ::db/tx-data
                  (::db/tx-data
                   ((::db.id/transaction-builder request) ids))}))))
      (set! db/transact!
            (fn [& [request]]
              (swap! transactions conj request)
              (js/Promise.resolve
               {:db-before database
                :db-after (update database :t inc)
                :tx-data (::db/tx-data request)
                :tempids {}
                :tx-meta {}})))
      (set! db/with-tx-context (fn [_ thunk] (thunk)))
      (-> (turn/open-turn!
           {:seon.agent/id "planner"
            :seon.agent.run/id-of-run "run-1"
            ::db/db database
            :seon.agent.turn/prompt-text "planner prompt"}
           (fn [turn-id]
             (ask-and-eval-reply-promise
              {:text "ignored"} "planner" turn-id "run-1" false
              'my.agent.planner database)))
          (.then (fn [_] (is false "publication failure must reject the turn body")))
          (.catch
           (fn [error]
             (is (= "namespace DAG publication failed" (.-message error)))
             (is (= [{:seon.agent.turn/id "turn-failed"
                      :seon.agent.turn/status :error
                      :seon.agent.turn/error "namespace DAG publication failed"}]
                    (::db/tx-data (last @transactions))))))
          (.finally
           (fn []
             (set! blob/put! original-put)
             (set! repl-internal/parse-program original-parse)
             (set! turn/eval-parsed! original-eval)
             (set! plan/publish-generated-program! original-publish)
             (set! db.id/allocate! original-allocate)
             (set! db/transact! original-transact)
             (set! db/with-tx-context original-context)
             (done)))))))

(deftest prompt-is-the-database-value-pinned-child-result
  (async done
    (let [original execution.host/invoke-compiled!
          observed (atom nil)]
      (set! execution.host/invoke-compiled!
            (fn [database agent-id function-symbol arguments]
              (reset! observed [database agent-id function-symbol arguments])
              (js/Promise.resolve
                {::execution/message execution/result-message
                 :seon.db/db database
                 ::execution/result prompt-value})))
      (-> (turn/render-prompt "agent-1" database)
          (.then
            (fn [prompt]
              (is (= prompt-value prompt))
              (is (= [database "agent-1"
                      'seon.execution.runtime/render-prompt!
                      [{:seon.agent/id "agent-1"}]]
                     @observed))))
          (.catch
            (fn [exception]
              (is false (str "prompt invocation rejected: " exception))))
          (.finally
            (fn []
              (set! execution.host/invoke-compiled! original)
              (done)))))))

(deftest prompt-profile-is-forwarded-to-the-same-compiled-owner
  (async done
    (let [original execution.host/invoke-compiled!
          observed (atom nil)
          profile [{:seon.agent.ctx/name :transcript}]]
      (set! execution.host/invoke-compiled!
            (fn [database agent-id function-symbol arguments]
              (reset! observed [database agent-id function-symbol arguments])
              (js/Promise.resolve
                {::execution/message execution/result-message
                 :seon.db/db database
                 ::execution/result (assoc prompt-value
                                           :seon.render/text "profile prompt")})))
      (-> (turn/render-prompt "agent-1" database profile)
          (.then
            (fn [prompt]
              (is (= "profile prompt" (:seon.render/text prompt)))
              (is (= [database "agent-1"
                      'seon.execution.runtime/render-prompt!
                      [{:seon.agent/id "agent-1"
                        :seon.agent.ctx/profile profile}]]
                     @observed))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! execution.host/invoke-compiled! original)
              (done)))))))

(deftest prompt-rejects-a-moved-database-value-as-data
  (async done
    (let [original execution.host/invoke-compiled!
          moved (assoc database :t 43)]
      (set! execution.host/invoke-compiled!
            (fn [_ _ _ _]
              (js/Promise.resolve
                {::execution/message execution/result-message
                 :seon.db/db moved
                 ::execution/result prompt-value})))
      (-> (turn/render-prompt "agent-1" database)
          (.then
            (fn [result]
              (is (= :core-bug (:seon.error/kind result)))
              (is (= database
                     (get-in result
                             [:seon.error/data
                              :seon.db/expected-db])))))
          (.catch
            (fn [exception]
              (is false (str "database mismatch rejected: " exception))))
          (.finally
            (fn []
              (set! execution.host/invoke-compiled! original)
              (done)))))))

(deftest prompt-preserves-a-child-acquisition-error
  (async done
    (let [original execution.host/invoke-compiled!
          child-error {:seon.error/message "authority failed"
                       :seon.error/kind :core-bug
                       :seon.error/data {:seon.db/results []}}]
      (set! execution.host/invoke-compiled!
            (fn [database _ _ _]
              (js/Promise.resolve
                {::execution/message execution/result-message
                 :seon.db/db database
                 ::execution/result child-error})))
      (-> (turn/render-prompt "agent-1" database)
          (.then (fn [result] (is (= child-error result))))
          (.catch
            (fn [exception]
              (is false (str "prompt error was rejected: " exception))))
          (.finally
            (fn []
              (set! execution.host/invoke-compiled! original)
              (done)))))))

(deftest parsed-reply-uses-the-same-agent-child-and-database-value
  (async done
    (let [original execution.host/invoke-compiled!
          observed (atom nil)
          parsed [{:seon.repl/kind :form
                   :seon.repl/source "(+ 1 2)"}]]
      (set! execution.host/invoke-compiled!
            (fn [database agent-id function-symbol arguments run-fence]
              (reset! observed [database agent-id function-symbol arguments
                                run-fence])
              (js/Promise.resolve
               {::execution/message execution/result-message
                :seon.db/db database
                ::execution/result {:seon.eval/n-ok 1
                                    :seon.eval/n-fail 0
                                    :seon.eval/ids ["eval-1"]}})))
      (-> (js/Promise.resolve
           (turn/eval-parsed! "agent-1" database parsed 'my.agent.agent-1
                              "turn-1" "run-1"))
          (.then
           (fn [result]
             (is (= {:seon.eval/n-ok 1
                     :seon.eval/n-fail 0
                     :seon.eval/ids ["eval-1"]}
                    result))
              (is (= [database "agent-1"
                      'seon.execution.runtime/eval-batch!
                      [{:seon.eval/parsed parsed
                        :seon.eval/starting-ns 'my.agent.agent-1
                        :seon.agent.turn/id-of-turn "turn-1"
                        :seon.agent.run/id-of-run "run-1"}]
                      {:seon.agent.run/id "run-1"}]
                     @observed))))
          (.catch
           (fn [error]
             (is false (str "eval invocation rejected: " error))))
          (.finally
           (fn []
             (set! execution.host/invoke-compiled! original)
             (done)))))))

(deftest retired-child-eval-error-preserves-the-recovery-signal
  (async done
    (let [original execution.host/invoke-compiled!]
      (set! execution.host/invoke-compiled!
            (fn [database _agent-id _function-symbol _arguments _run-fence]
              (js/Promise.resolve
               {::execution/message execution/error-message
                :seon.db/db database
                ::execution/error
                {:seon.error/message "The invocation timed out."
                 :seon.error/kind :agent
                 :seon.error/data {::execution/child-retired? true}}})))
      (-> (turn/eval-parsed!
           "agent-1" database [] 'my.agent.agent-1 "turn-1" "run-1")
          (.then
           (fn [result]
             (is (= "The invocation timed out."
                    (:seon.error/message result)))
             (is (true? (get-in result [:seon.error/data
                                        ::execution/child-retired?])))))
          (.catch (fn [error]
                    (is false (str "eval invocation rejected: " error))))
          (.finally
           (fn []
             (set! execution.host/invoke-compiled! original)
             (done)))))))

(deftest orchestration-wrapper-preserves-retired-child-recovery-evidence
  (let [inner
        (ex-info
         "The execution child exited before returning a result."
         {:seon.agent.turn/id "turn-crashed"
          ::execution/child-retired? true
          :seon.error/data
          {::execution/child-retired? true
           :seon.execution.host/pid 812
           :seon.execution.host/stderr-tail "native loop"}})
        wrapper
        (ex-info ":malli.core/invalid-output"
                 {:seon.error/kind :seon.error.kind/malli-instrument-output}
                 inner)
        result (@#'turn/turn-failure wrapper)]
    (is (= :error (:seon.agent.turn/status result)))
    (is (= "turn-crashed" (:seon.agent.turn/id result)))
    (is (true? (::execution/child-retired? result)))
    (is (true? (get-in result [:seon.error/data
                               ::execution/child-retired?])))
    (is (= 812 (get-in result [:seon.error/data
                               :seon.execution.host/pid])))
    (is (= "native loop"
           (get-in result [:seon.error/data
                           :seon.execution.host/stderr-tail])))))

(deftest open-turn-stores-the-basis-transaction-and-consumes-native-results
  (async done
    (let [original-allocate db.id/allocate!
          original-transact db/transact!
          original-with-context db/with-tx-context
          observed-allocation (atom nil)
          observed-close (atom nil)]
      (set! db.id/allocate!
            (fn [request]
              (reset! observed-allocation request)
              (let [turn-id "turn-native"
                    built ((::db.id/transaction-builder request)
                           {::turn/turn-allocation turn-id})]
                (js/Promise.resolve
                 {:db-before database
                  :db-after (assoc database :t 43)
                  :tx-data (:seon.db/tx-data built)
                  :tempids {}
                  :tx-meta {}
                  ::db.id/ids {::turn/turn-allocation turn-id}
                  ::db.id/eids {::turn/turn-allocation 101}}))))
      (set! db/transact!
            (fn [& [request]]
              (reset! observed-close request)
              (js/Promise.resolve
               {:db-before (assoc database :t 43)
                :db-after (assoc database :t 44)
                :tx-data (:seon.db/tx-data request)
                :tempids {}
                :tx-meta {}})))
      (set! db/with-tx-context
            (fn [_context thunk] (thunk)))
      (-> (turn/open-turn!
           {:seon.agent/id "agent-1"
            :seon.db/db database
            :seon.agent.turn/prompt-text "frozen prompt"}
           (fn [turn-id]
             (js/Promise.resolve
              {:seon.agent.turn/id turn-id
               :seon.agent/eval-count 0})))
          (.then
           (fn [result]
             (let [open-row
                   (first (:seon.db/tx-data
                           ((::db.id/transaction-builder @observed-allocation)
                            {::turn/turn-allocation "turn-native"})))]
               (is (= "turn-native" (:seon.agent.turn/id result)))
               (is (= database (:seon.db/db @observed-allocation)))
               (is (= 42 (:seon.agent.turn/rendered-tx open-row)))
               (is (not (contains? result :seon.db/ok?)))
               (is (= :done
                      (get-in @observed-close
                              [:seon.db/tx-data 0
                               :seon.agent.turn/status]))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db.id/allocate! original-allocate)
             (set! db/transact! original-transact)
             (set! db/with-tx-context original-with-context)
             (done)))))))

(deftest run-turn-pins-the-final-pull-to-the-close-transaction
  ;; I7 falsifier (frozen-turn-inputs acceptance 3): the final turn pull
  ;; consumes the close transaction's returned database value. A head move
  ;; landing between close and pull must not alter the returned map.
  (async done
    (let [original-render turn/render-prompt
          original-put blob/put!
          original-eval turn/eval-parsed!
          original-allocate db.id/allocate!
          original-transact db/transact!
          original-context db/with-tx-context
          original-agent db/with-agent
          original-pull db/pull
          !t (atom 42)
          last-db-after (atom nil)
          pulled (atom nil)]
      (set! turn/render-prompt
            ;; multi-arity like the real fn — the in-ns call site compiles
            ;; to direct arity-4 dispatch
            (fn stub-render
              ([_ _] (js/Promise.resolve prompt-value))
              ([_ _ _] (js/Promise.resolve prompt-value))
              ([_ _ _ _] (js/Promise.resolve prompt-value))))
      (set! blob/put!
            (fn [_]
              (js/Promise.resolve
               {:my.blob/ok? false :my.blob/error "test capture omitted"})))
      (set! turn/eval-parsed!
            (fn [& _]
              (js/Promise.resolve
               {:seon.eval/ids [] :seon.eval/n-ok 0 :seon.eval/n-fail 0})))
      (set! db.id/allocate!
            (fn [request]
              (js/Promise.resolve
               {:db-before (assoc database :t @!t)
                :db-after (assoc database :t (swap! !t inc))
                :tx-data (:seon.db/tx-data
                          ((::db.id/transaction-builder request)
                           {::turn/turn-allocation "turn-pinned"}))
                :tempids {}
                :tx-meta {}
                ::db.id/ids {::turn/turn-allocation "turn-pinned"}
                ::db.id/eids {::turn/turn-allocation 101}})))
      (set! db/transact!
            (fn [& [request]]
              (let [db-after (assoc database :t (inc @!t))]
                (swap! !t inc)
                (reset! last-db-after db-after)
                (js/Promise.resolve
                 {:db-before (assoc database :t (dec (:t db-after)))
                  :db-after db-after
                  :tx-data (:seon.db/tx-data request)
                  :tempids {}
                  :tx-meta {}}))))
      (set! db/with-tx-context (fn [_context thunk] (thunk)))
      (set! db/with-agent (fn [_id thunk] (thunk)))
      (set! db/pull
            (fn stub-pull
              ([request]
               (reset! pulled request)
               ;; a concurrent transaction lands between close and pull
               (swap! !t inc)
               (js/Promise.resolve
                {:seon.agent.turn/id (second (:seon.db/ref request))
                 :seon.agent.turn/status :done}))
              ([selector entity-id]
               (stub-pull {:seon.db/pull-pattern selector
                           :seon.db/ref entity-id}))
              ([database-value selector entity-id]
               (stub-pull {:seon.db/db database-value
                           :seon.db/pull-pattern selector
                           :seon.db/ref entity-id}))))
      (-> (turn/run-turn!
           {:seon.agent/id "agent-1"
            :seon.agent/llm-fn (fn [_] (js/Promise.resolve {:text ""}))
            :seon.db/db database})
          (.then
           (fn [result]
             (let [close-db (:seon.db/db @pulled)]
               (is (= "turn-pinned" (:seon.agent.turn/id result)))
               (is (= :done (:seon.agent.turn/status result)))
               (is (= 0 (:seon.agent/eval-count result)))
               (is (some? close-db)
                   "the final pull carries an explicit pinned value")
               (is (= @last-db-after close-db)
                   "the pinned value is the CLOSE transaction's db-after"))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! turn/render-prompt original-render)
             (set! blob/put! original-put)
             (set! turn/eval-parsed! original-eval)
             (set! db.id/allocate! original-allocate)
             (set! db/transact! original-transact)
             (set! db/with-tx-context original-context)
             (set! db/with-agent original-agent)
             (set! db/pull original-pull)
             (done)))))))

(deftest run-turn-without-a-pinned-database-value-fails-loudly
  ;; I8 falsifier: the unpinned door is closed — a missing :seon.db/db is a
  ;; :core-bug error value, zero prompt renders, zero provider calls.
  (async done
    (let [original-render turn/render-prompt
          renders (atom 0)
          llm-calls (atom 0)]
      (set! turn/render-prompt
            (fn stub-render
              ([_ _] (swap! renders inc) (js/Promise.resolve prompt-value))
              ([_ _ _] (swap! renders inc) (js/Promise.resolve prompt-value))
              ([_ _ _ _]
               (swap! renders inc)
               (js/Promise.resolve prompt-value))))
      (-> (turn/run-turn!
           {:seon.agent/id "agent-1"
            :seon.agent/llm-fn (fn [_]
                                 (swap! llm-calls inc)
                                 (js/Promise.resolve {:text ""}))})
          (.then
           (fn [result]
             (is (= :error (:seon.agent.turn/status result)))
             (is (= :core-bug (:seon.error/kind result)))
             (is (string? (:seon.error/data result)))
             (is (zero? @renders) "no prompt render without a pinned value")
             (is (zero? @llm-calls) "no provider call without a pinned value")))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! turn/render-prompt original-render)
             (done)))))))

(deftest open-turn-propagates-a-direct-allocation-error
  (async done
    (let [original db.id/allocate!
          failure {:seon.error/message "writer unavailable"
                   :seon.error/kind :core-bug
                   :seon.error/data {:seon.db/request "turn"}}]
      (set! db.id/allocate! (fn [_] (js/Promise.resolve failure)))
      (-> (turn/open-turn!
           {:seon.agent/id "agent-1"
            :seon.db/db database
            :seon.agent.turn/prompt-text "never runs"}
           (fn [_] (is false "body must not run")))
          (.then (fn [result] (is (= failure result))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db.id/allocate! original)
             (done)))))))
