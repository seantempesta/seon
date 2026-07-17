(ns seon.ai.typeahead-test
  "Focused proof for the database-value-pinned typeahead provider."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [async deftest is]]
    [clojure.string :as str]
    [seon.agent.ctx.menu :as menu]
    [seon.ai.diffusiongemma :as dg]
    [seon.ai.typeahead :as ta]
    [seon.db :as db]))

(def ^:private agent-id "typeaheadtestA")
(def ^:private database
  {:datahike/commit-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   :max-tx 42})
(def ^:private worker-options
  {:seon.ai/config-resolution
   {:seon.ai/resolved-config
    {:seon.ai/provider :diffusiongemma
     :seon.ai/base-url "http://127.0.0.1:17999"}
    :seon.ai/provenance
    {:seon.ai/provider :default :seon.ai/base-url :default}}})

(defn- json-response [status value]
  (js/Response.
   (.stringify js/JSON (clj->js value))
   #js {:status status :headers #js {"content-type" "application/json"}}))

(defn- step-response [output]
  (json-response 200 {:id "job" :status "COMPLETED" :output output}))

(defn- scripted-fetch [calls responses]
  (let [remaining (atom responses)]
    (fn [url init]
      (let [payload (-> (.parse js/JSON (aget init "body"))
                        (js->clj :keywordize-keys false)
                        (get "input"))]
        (swap! calls conj {:url url :payload payload :signal (.-signal init)}))
      (let [response (first @remaining)]
        (swap! remaining subvec 1)
        (js/Promise.resolve response)))))

(defn- with-runtime [calls responses body]
  (let [db-calls (atom 0)
        menu-requests (atom [])
        tx-requests (atom [])
        originals {:db db/db
                   :query db/query
                   :menu menu/acquire-function-menu
                   :transact db/transact!
                   :fetch dg/*fetch*}]
    (set! db/db
          (fn
            ([]
             (swap! db-calls inc)
             (js/Promise.resolve database))
            ([_]
             (swap! db-calls inc)
             (js/Promise.resolve database))))
    (set! menu/acquire-function-menu
          (fn [request]
            (swap! menu-requests conj request)
            (js/Promise.resolve
             {::menu/policy menu/default-policy
              ::menu/offers []
              ::menu/text ""})))
    (set! db/query
          (fn
            ([_] (js/Promise.resolve []))
            ([_ & _] (js/Promise.resolve []))))
    (set! db/transact!
          (fn
            ([]
             (js/Promise.resolve {:db-before database :db-after database
                                  :tx-data [] :tempids {} :tx-meta {}}))
            ([request & _]
             (swap! tx-requests conj request)
             (js/Promise.resolve {:db-before database :db-after database
                                  :tx-data [] :tempids {} :tx-meta {}}))))
    (set! dg/*fetch* (scripted-fetch calls responses))
    (-> (js/Promise.resolve (body))
        (.then (fn [value]
                 {:value value :db-calls @db-calls
                  :menu-requests @menu-requests :tx-requests @tx-requests}))
        (.finally
         (fn []
           (set! db/db (:db originals))
           (set! db/query (:query originals))
           (set! menu/acquire-function-menu (:menu originals))
           (set! db/transact! (:transact originals))
           (set! dg/*fetch* (:fetch originals)))))))

(deftest offers-and-policy-preserve-worker-wire-shapes
  (let [offers (ta/offers->wire
                [{:seon.typeahead/glyph "①"
                  :seon.typeahead/label "seon.db/query [request]"
                  :seon.typeahead/template
                  [["clamp" "(seon.db/query "] ["free" 24] ["clamp" ")"]]}])
        policy (ta/policy->wire menu/default-policy)]
    (is (= "①" (get (first offers) "glyph")))
    (is (= [["clamp" "(seon.db/query "] ["free" 24] ["clamp" ")"]]
           (get (first offers) "template")))
    (is (= 3.0 (get policy "auto_offer_margin")))
    (is (= 8 (get policy "max_rounds")))
    (is (not (contains? policy "worst_entropy_gate")))))

(deftest null-render-removes-intent-event-log-only
  (let [prompt (str ";;; ┌─ plan ─\n; PLAN the task\n;;; └─ end plan ─\n\n"
                    ";;; ┌─ function-menu ─\n; ① fn seon.db/query\n"
                    ";;; └─ end function-menu ─\n\n"
                    ";;; ┌─ transcript ─\n; masthead\n"
                    ";;; ◀ from user — the task\n(result)\n"
                    "my.agent.X=> \n;;; └─ end transcript ─")
        result (ta/null-render prompt)]
    (is (str/includes? result "; ① fn seon.db/query"))
    (is (str/includes? result "my.agent.X=>"))
    (is (not (str/includes? result "the task")))
    (is (not (str/includes? result "(result)")))))

(deftest step-projection-is-bounded-ordinary-data
  (let [projection
        (ta/step-projection
         "call" 2
         {:transition "expand" :glyph "①" :locked ["(def a 1)"]
          :forwards 4 :gen_s 1.7 :new_draft "(def b"
          :buffer_text "(def a 1)\n(def b "
          :buffer_spans [{:start 0 :end 9 :status "locked"}
                         {:start 9 :end 17 :status "resolving"}]
          :readouts {:glyph_margin 7.5 :eos_logprob_tail -6.4
                     :free_entropy_worst 0.42}})]
    (is (= :expand (:seon.typeahead/transition projection)))
    (is (= 1 (:seon.typeahead/locked-count projection)))
    (is (= 1 (:seon.typeahead/draft-tokens projection)))
    (is (= [[0 9 :locked] [9 17 :resolving]]
           (reader/read-string (:seon.typeahead/buffer-spans projection))))
    (is (not (contains? projection :seon.typeahead/agent)))))

(deftest scoped-document-keeps-active-subtree-and-root-layer
  (let [document [{:seon.typeahead/id "root" :my.plan/title "Root"
                   :my.plan/goal "Goal" :my.plan/status :open
                   :my.plan/_parent
                   [{:seon.typeahead/id "active" :my.plan/title "Active"
                     :my.plan/status :active :my.plan/expect "Outcome"
                     :my.plan/_parent
                     [{:seon.typeahead/id "child" :my.plan/title "Child"
                       :my.plan/status :open}]}]}
                  {:seon.typeahead/id "other" :my.plan/title "Other"
                   :my.plan/status :open :my.plan/description "large"}]
        scoped (ta/scoped-document document)]
    (is (= "active" (get-in scoped [0 :my.plan/_parent 0
                                     :seon.typeahead/id])))
    (is (= "child" (get-in scoped [0 :my.plan/_parent 0
                                    :my.plan/_parent 0 :seon.typeahead/id])))
    (is (= {:seon.typeahead/id "other" :my.plan/title "Other"
            :my.plan/status :open}
           (second scoped)))))

(deftest provider-captures-one-database-value-and-reuses-menu-acquisition
  (async done
    (let [calls (atom [])]
      (-> (with-runtime
           calls
           [(step-response {:transition "progress" :locked ["(def a 1)"]
                            :new_draft "(def b"})
            (step-response {:transition "done" :locked ["(def b 2)"]
                            :new_draft ""})]
           #(db/with-agent agent-id
              (fn [] ((ta/agent-adapter worker-options) "rendered prompt"))))
          (.then
           (fn [{:keys [value db-calls menu-requests tx-requests]}]
             (is (= "(def a 1)\n\n(def b 2)" (:text value)))
             (is (= 1 db-calls) "the provider acquires one current database value")
             (is (= 1 (count menu-requests)))
             (is (identical? database (::db/db (first menu-requests))))
             (is (= 2 (count tx-requests)) "one native step transaction per round")
             (is (= ["" "(def a 1)"]
                    (mapv #(get-in % [:payload "committed"]) @calls)))
             (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest provider-forwards-the-attempt-abort-signal
  (async done
    (let [calls (atom [])
          signal (.-signal (js/AbortController.))]
      (-> (with-runtime
           calls
           [(step-response {:transition "done" :locked ["(def a 1)"]
                            :new_draft ""})]
           #(db/with-agent
              agent-id
              (fn [] ((ta/agent-adapter worker-options)
                      {:seon.ai/ctx "prompt"
                       :seon.ai/abort-signal signal}))))
          (.then (fn [{:keys [value]}]
                   (is (= "(def a 1)" (:text value)))
                   (is (identical? signal (:signal (first @calls))))
                   (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest database-acquisition-error-is-a-provider-value
  (async done
    (let [original db/db]
      (set! db/db
            (fn
              ([]
               (js/Promise.resolve
                {:seon.error/message "authority unavailable"}))
              ([_]
               (js/Promise.resolve
                {:seon.error/message "authority unavailable"}))))
      (-> (js/Promise.resolve ((ta/agent-adapter) "prompt"))
          (.then (fn [result]
                   (is (= "" (:text result)))
                   (is (str/includes? (get-in result [:seon.ai/error :seon.ai/msg])
                                      "authority unavailable"))))
          (.finally (fn [] (set! db/db original)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))
