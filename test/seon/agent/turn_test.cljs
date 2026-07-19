(ns seon.agent.turn-test
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.agent.turn :as turn]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.execution :as execution]
   [seon.execution.host :as execution.host]))

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
                               'my.agent.agent-1)]
    (is (= ["(+ 1 2)"]
           (->> (:seon.repl/eval-entries program)
                (filter #(= :form (:seon.repl/kind %)))
                (mapv :seon.repl/source))))))

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
