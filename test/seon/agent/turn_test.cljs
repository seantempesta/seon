(ns seon.agent.turn-test
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.agent.ctx.driver :as ctx.driver]
   [seon.agent.turn :as turn]
   [seon.agent.turn.core :as turn.core]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.db.protocol :as protocol]
   [seon.host.session :as host.session]
   [seon.host.session.leaf :as host.session.leaf]
   [seon.render :as render]
   [seon.schema :as schema]))

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
   :seon.ai/wire-stream? false
   :seon.ai/reply-evaluation :batch
   :seon.eval/ns 'my.agent.agent-1})

(def ^:private reply-program turn.core/reply-program)

(deftest turn-usage-schemas-are-seven-native-long-attributes
  (let [attributes
        [:seon.agent.turn.usage/prompt-tokens
         :seon.agent.turn.usage/completion-tokens
         :seon.agent.turn.usage/cached-tokens
         :seon.agent.turn.usage/input-tokens
         :seon.agent.turn.usage/output-tokens
         :seon.agent.turn.usage/cache-read-input-tokens
         :seon.agent.turn.usage/cache-creation-input-tokens]
        facets (db/malli->datahike-schema attributes)]
    (is (nil? (schema/schema-definition :seon.agent.turn/llm-usage)))
    (is (= :string (schema/schema-definition :seon.agent.turn/llm-meta)))
    (is (= (set attributes) (set (map :db/ident facets))))
    (is (every? #(= :db.type/long (:db/valueType %)) facets))
    (is (every? #(schema/valid-candidate-value? % 0) attributes))
    (is (every? #(not (schema/valid-candidate-value? % -1)) attributes))))

(deftest batch-replies-use-the-shared-ordered-program-projection
  (let [program
        (reply-program
          (str "(ns my.feature.ui (:require [my.feature.model]))\n"
               "(defn view [] (my.feature.model/value))\n"
               "(ns my.feature.model (:require [seon.schema :as schema]))\n"
               "(def before-schema 1)\n"
               "(schema/register! ::id :string)\n"
               "(def value 2)")
          :batch
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

(deftest first-form-replies-retain-the-existing-boundary
  (let [program (reply-program ";; first\n(+ 1 2)\n(+ 3 4)" :first-form
                               'my.agent.agent-1)
        forms (->> (:seon.repl/eval-entries program)
                   (filter #(= :form (:seon.repl/kind %)))
                   vec)]
    (is (= ["(+ 1 2)"] (mapv :seon.repl/source forms)))
    (is (= (str "first\n"
                "; first-form mode executed the first complete form; "
                "1 further form was not executed — resend the next form.")
           (:seon.repl/narration (first forms))))))

(deftest stream-tail-narration-counts-only-unexecuted-complete-forms
  (let [program (reply-program
                 "(+ 1 2)\n;; between\n(+ 3 4)\n(+ 5 6)\n(incomplete"
                 :first-form
                 'my.agent.agent-1)
        form (->> (:seon.repl/eval-entries program)
                  (filter #(= :form (:seon.repl/kind %)))
                  first)]
    (is (= "; first-form mode executed the first complete form; 2 further forms were not executed — resend the next form."
           (:seon.repl/narration form)))
    (is (= "(+ 1 2)" (:seon.repl/source form)))))

(deftest first-form-single-form-needs-no-tail-narration
  (let [program (reply-program "(+ 1 2)\n;; trailing thought" :first-form
                               'my.agent.agent-1)
        form (->> (:seon.repl/eval-entries program)
                  (filter #(= :form (:seon.repl/kind %)))
                  first)]
    (is (= "" (:seon.repl/narration form)))))

(deftest authored-prompt-render-uses-the-guarded-single-call-door
  (async done
    (let [original-invoke host.session.leaf/invoke-authored!
          observed (atom nil)]
      (set! host.session.leaf/invoke-authored!
            (fn [request]
              (reset! observed request)
              (js/Promise.resolve
               {:seon.execution/message host.session/result-message
                :seon.execution/result "authored block"})))
      (-> (js/Promise.resolve
           (@#'turn/invoke-authored-render!
            database "agent-1"
            {::render/function-symbol 'my.prompt/block
             ::render/arguments [{:seon.agent/id "agent-1"}]}))
          (.then
           (fn [value]
             (is (= "authored block" value))
             (is (= 'my.prompt/block
                    (::host.session.leaf/function-symbol @observed)))))
          (.catch (fn [exception] (is false (str exception))))
          (.finally
           (fn []
             (set! host.session.leaf/invoke-authored! original-invoke)
             (done)))))))

(deftest authored-guard-steering-value-remains-the-render-slot
  (async done
    (let [steering {:seon.error/message "guarded"
                    :seon.error/kind :budget
                    :seon.error/data
                    {:seon.host.guard/config-key
                     :seon.config.guard/authored-render-interpreter-step-budget}}
          door
          {::render/invoke-authored!
           (fn [_] (js/Promise.resolve steering))}
          call {:seon.execution/function-symbol 'my.hostile/render
                :seon.execution/arguments [{}]}]
      (-> (js/Promise.resolve
           (@#'turn/invoke-prompt-calls!
            database "agent-1" door [call]))
          (.then
           (fn [results]
             (is (true? (:seon.execution/ok? (first results))))
             (is (= steering (:seon.execution/value (first results))))))
          (.catch (fn [exception] (is false (str exception))))
          (.finally done)))))

(deftest prompt-is-the-database-value-pinned-child-result
  (async done
    (let [original ctx.driver/render-prompt!
          observed (atom nil)]
      (set! ctx.driver/render-prompt!
            (fn [request _]
              (reset! observed request)
              (js/Promise.resolve (assoc prompt-value :seon.db/db database))))
      (-> (turn/render-prompt "agent-1" database)
          (.then
            (fn [prompt]
              (is (= prompt-value prompt))
              (is (= {:seon.agent/id "agent-1" :seon.db/db database}
                     @observed))))
          (.catch
            (fn [exception]
              (is false (str "prompt invocation rejected: " exception))))
          (.finally
            (fn []
              (set! ctx.driver/render-prompt! original)
              (done)))))))

(deftest render-prompt-validates-every-current-reply-policy
  (async done
    (let [original db/execute-many
          policy (atom nil)
          policies [{:seon.ai/wire-stream? false
                     :seon.ai/reply-evaluation :batch}
                    {:seon.ai/wire-stream? false
                     :seon.ai/reply-evaluation :first-form}
                    {:seon.ai/wire-stream? true
                     :seon.ai/reply-evaluation :batch}
                    {:seon.ai/wire-stream? true
                     :seon.ai/reply-evaluation :first-form}]]
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               {::db/results
                [{::protocol/success? true
                  ::protocol/result
                  (merge {:seon.agent/id "agent-1"
                          :seon.render/ai (pr-str "remote prompt")}
                         @policy)}
                 {::protocol/success? true
                  ::protocol/result
                  {:seon.config/system-text "frozen system"}}
                 {::protocol/success? true
                  ::protocol/result
                  {:seon.ai/model "frozen-model"}}]})))
      (-> (reduce
           (fn [prior reply-policy]
             (.then
              prior
              (fn [_]
                (reset! policy reply-policy)
                (-> (turn/render-prompt "agent-1" database)
                    (.then
                     (fn [rendered]
                       (is (schema/valid-candidate-value?
                            ::turn/prompt-result rendered))
                       (is (= reply-policy
                              (select-keys
                               rendered
                               [:seon.ai/wire-stream?
                                :seon.ai/reply-evaluation])))
                       (is (not (contains? rendered
                                           :seon.config/repl-mode)))))))))
           (js/Promise.resolve)
           policies)
          (.catch
           (fn [exception]
             (is false (str "current reply policy rejected: " exception))))
          (.finally
           (fn []
             (set! db/execute-many original)
             (done)))))))

(deftest prompt-profile-is-forwarded-to-the-same-compiled-owner
  (async done
    (let [original ctx.driver/render-prompt!
          observed (atom nil)
          profile [{:seon.agent.ctx/name :transcript}]]
      (set! ctx.driver/render-prompt!
            (fn [request _]
              (reset! observed request)
              (js/Promise.resolve
               (assoc prompt-value :seon.db/db database
                      :seon.render/text "profile prompt"))))
      (-> (turn/render-prompt "agent-1" database profile)
          (.then
            (fn [prompt]
              (is (= "profile prompt" (:seon.render/text prompt)))
              (is (= {:seon.agent/id "agent-1"
                      :seon.agent.ctx/profile profile
                      :seon.db/db database}
                     @observed))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! ctx.driver/render-prompt! original)
              (done)))))))

(deftest prompt-rejects-a-moved-database-value-as-data
  (async done
    (let [original ctx.driver/render-prompt!
          moved (assoc database :t 43)]
      (set! ctx.driver/render-prompt!
            (fn [_ _]
              (js/Promise.resolve (assoc prompt-value :seon.db/db moved))))
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
              (set! ctx.driver/render-prompt! original)
              (done)))))))

(deftest prompt-preserves-a-child-acquisition-error
  (async done
    (let [original ctx.driver/render-prompt!
          child-error {:seon.error/message "authority failed"
                       :seon.error/kind :core-bug
                       :seon.error/data {:seon.db/results []}}]
      (set! ctx.driver/render-prompt!
            (fn [_ _]
              (js/Promise.resolve
               (assoc child-error :seon.db/db database))))
      (-> (turn/render-prompt "agent-1" database)
          (.then (fn [result] (is (= child-error result))))
          (.catch
            (fn [exception]
              (is false (str "prompt error was rejected: " exception))))
          (.finally
            (fn []
              (set! ctx.driver/render-prompt! original)
              (done)))))))

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
