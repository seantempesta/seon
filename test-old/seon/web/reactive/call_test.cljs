(ns seon.web.reactive.call-test
  "The agent-call capability, validation, and transaction boundary.

   (b) The capability gate performs one query at one immutable database value.
       An agent-authored function resolves + is allowed regardless of its
       application namespace or original author; `fs`/core symbols are refused
       because they have no agent-authored source transaction.

   (c) A granted call captures one immutable database value and acknowledges
       only after its interaction fact commits."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [async deftest is]]
    [seon.agent.interaction :as interaction]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.runtime.admission :as admission]
    [seon.web.reactive.call :as call]
    [seon.web.reactive.transform :as transform]))

;; A valid 14-char id (`:seon.db/id` is [:string {:min 14 :max 14}]).
(def ^:private agent-id "tst-2606260000")
(def ^:private granted-sym 'my.orders/set-purpose!)

(def ^:private database
  {:db-name "test"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

;; ---------------------------------------------------------------------------
;; (b) Capability gate — pure, no bootstrap.
;; ---------------------------------------------------------------------------

(deftest capability-gate-allows-granted-refuses-everything-else
  (async done
    (let [original-query db/query
          requests (atom [])
          ghost 'my.orders/not-a-real-fn]
      (set! db/query
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
               (when (= (str granted-sym)
                         (second (:seon.db/args request)))
                  {:seon.fn/source-fingerprint
                   (apply str (repeat 64 "a"))
                   :seon.fn/spec "[:=> [:cat :string] :string]"})))
              ([query-form & inputs]
               (js/Promise.reject
                (ex-info "unexpected positional query"
                         {:seon.db/query query-form
                          :seon.db/args inputs})))))
      (-> (js/Promise.all
           (clj->js
            [(call/capability-check database agent-id granted-sym)
             (call/capability-check database agent-id 'fs/readFileSync)
             (call/capability-check database agent-id 'seon.client/start-agent!)
             (call/capability-check database agent-id ghost)
             (call/capability-check database "someone-else9" granted-sym)]))
          (.then
           (fn [results]
             (let [[granted fs core missing shared]
                   (vec (array-seq results))
                   query (:seon.db/query (first @requests))]
               (is (= agent-id (::call/agent-id granted)))
               (is (= granted-sym (::call/handler granted)))
               (is (= database (:seon.db/db (first @requests))))
               (is (= [agent-id (str granted-sym)]
                      (:seon.db/args (first @requests))))
               (is (some #{'[?function :seon.fn/source _ ?source-tx]} query))
               (is (some #{'[(get-else $ ?function
                               :seon.fn/private? false) ?private]} query))
               (is (some #{'[(= false ?private)]} query))
               (is (some #{'[?source-tx :seon.db/user ?author]} query))
               (is (some #{'[?author :seon.agent/id _]} query))
               (is (some #{'[?process :seon.db.process/id
                             :seon.db.process/repl]} query))
               (is (some #{'(not [?agent :seon.agent/terminated-at _])}
                         query))
               (is (some? (::call/refused fs)))
               (is (some? (::call/refused core)))
               (is (some? (::call/refused missing)))
               (is (= "someone-else9" (::call/agent-id shared))
                   "a different live route agent can use shared authored code")
               (is (= 5 (count @requests))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/query original-query)
             (done)))))))

(deftest invalid-interaction-is-flat-error-and-never-allocates
  (async done
    (let [original-allocate db.id/allocate!
          allocations (atom 0)
          capability
          {::call/agent-id agent-id
           ::call/handler granted-sym
           ::call/handler-source-fingerprint (apply str (repeat 64 "b"))
           ::call/handler-spec "[:=> [:cat :int] :int]"}]
      (set! db.id/allocate!
            (fn [_]
              (swap! allocations inc)
              (js/Promise.resolve {})))
      (-> (call/submit! database capability ["not-an-int"])
          (.then
           (fn [result]
             (is (false? (::call/ok? result)))
             (is (= :user-input
                    (:seon.error/kind (::call/error result))))
             (is (string? (:seon.error/message (::call/error result))))
             (is (zero? @allocations)
                 "schema refusal precedes identity allocation and transact")))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db.id/allocate! original-allocate)
             (done)))))))

(deftest successful-submission-has-no-deadline-projection
  (async done
    (let [original-validate interaction/validate-request
          original-open interaction/open-tx-data
          original-without db/without-agent
          original-allocate db.id/allocate!
          opened (atom nil)
          capability
          {::call/agent-id agent-id
           ::call/handler granted-sym
           ::call/handler-source-fingerprint (apply str (repeat 64 "b"))
           ::call/handler-spec "[:=> [:cat :int] :int]"}]
      (set! interaction/validate-request identity)
      (set! interaction/open-tx-data
            (fn [request]
              (reset! opened request)
              [{:seon.agent.interaction/id
                (:seon.agent.interaction/id request)}]))
      (set! db/without-agent (fn [body] (body)))
      (set! db.id/allocate!
            (fn [request]
              ((::db.id/transaction-builder request)
               {::call/interaction-id "interaction-a"
                ::call/run-id "run-a"})
              (js/Promise.resolve
               {::db.id/ids
                {::call/interaction-id "interaction-a"
                 ::call/run-id "run-a"}})))
      (-> (call/submit! database capability [1])
          (.then
           (fn [result]
             (is (true? (::call/ok? result)))
             (is (= "interaction-a" (::call/interaction-id result)))
             (is (= "run-a" (:seon.agent.run/id @opened)))
             (is (inst? (:seon.agent.interaction/requested-at @opened)))
             (is (not (contains? @opened :seon.agent.run/deadline)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! interaction/validate-request original-validate)
             (set! interaction/open-tx-data original-open)
             (set! db/without-agent original-without)
             (set! db.id/allocate! original-allocate)
             (done)))))))

;; ---------------------------------------------------------------------------
;; (d) HTTP boundary — agent-call injection is refused and malformed args end.
;; The PoC sends a transit value that decodes to a list.
;; With the old synthesize-and-eval path, the list spliced in as code and ran
;; (`(js/require "child_process")` etc.). Now the ?args= decode is DATA-ONLY
;; and submission only commits data — the injected expression can never run.
;; ---------------------------------------------------------------------------

(defn- call-req
  "A Ring request carrying a WHATWG Request with fn and optional args."
  ([fn-sym args-str]
   (call-req fn-sym args-str nil))
  ([fn-sym args-str options]
   (let [base (str "/agent/" agent-id "/call?fn="
                   (js/encodeURIComponent (str fn-sym)))]
     {:seon.http/request
      (js/Request.
       (str "http://seon.test"
            (if args-str
              (str base "&args=" (js/encodeURIComponent args-str))
              base))
       (or options #js {:method "POST"}))
      :path-params {:id agent-id}})))

(deftest datastar-success-is-an-empty-acknowledgement
  (let [req (js/Request. "http://seon.test/agent/id/call"
                         #js {:headers #js {"datastar-request" "true"}})
        response (@#'call/success-response req "interaction-1")]
    (is (= 204 (.-status response)))
    (is (nil? (.-body response))
        "the live feed, not a duplicate body, updates UI")))

(deftest unavailable-runtime-refuses-before-capability-or-body-work
  (async done
    (reset! @#'admission/!state
            {::admission/status :unavailable
             ::admission/reason "test publication failure"})
    (-> (call/handle! (call-req granted-sym nil))
        (.then
         (fn [response]
           (is (= 503 (.-status response)))
           (.text response)))
        (.then
         (fn [body]
           (is (str/includes? body "Runtime program generation"))))
        (.catch (fn [error] (is false (str error))))
        (.finally
         (fn []
           (reset! @#'admission/!state
                   {::admission/status :available
                    ::admission/generation 0})
           (done))))))

(deftest database-failure-is-unavailable-not-a-capability-refusal
  (async done
    (let [original-db db/db
          original-capability call/capability-check
          checks (atom 0)]
      (set! db/db
            (fn
              ([] (js/Promise.resolve
                   {:seon.error/message "authority unavailable"}))
              ([_] (js/Promise.resolve
                    {:seon.error/message "authority unavailable"}))))
      (set! call/capability-check
            (fn [& _] (swap! checks inc)))
      (-> (call/handle! (call-req granted-sym
                                  (transform/encode-args [])))
          (.then
           (fn [response]
             (is (= 503 (.-status response)))
             (is (zero? @checks))
             (.text response)))
          (.then
           (fn [body]
             (is (str/includes? body "authority unavailable"))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! call/capability-check original-capability)
             (done)))))))

(deftest successful-http-call-reuses-the-acquired-database-value
  (async done
    (let [original-db db/db
          original-capability call/capability-check
          original-submit call/submit!
          observed (atom [])]
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! call/capability-check
            (fn [database-value route-agent-id _]
              (is (= agent-id route-agent-id))
              (swap! observed conj database-value)
              (js/Promise.resolve
               {::call/agent-id agent-id
                ::call/handler granted-sym
                ::call/handler-source-fingerprint
                (apply str (repeat 64 "a"))
                ::call/handler-spec
                "[:=> [:cat :string] :string]"})))
      (set! call/submit!
            (fn [database-value _ _]
              (swap! observed conj database-value)
              (js/Promise.resolve
               {::call/ok? true ::call/interaction-id "interaction-1"})))
      (-> (call/handle! (call-req granted-sym
                                  (transform/encode-args ["value"])))
          (.then
           (fn [response]
             (is (= 200 (.-status response)))
             (is (= 2 (count @observed)))
             (is (every? #(identical? database %) @observed))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! call/capability-check original-capability)
             (set! call/submit! original-submit)
             (done)))))))

(deftest call-refuses-injected-list-arg-and-never-submits
  ;; A granted fn (capability passes) called with the list-shaped ?args=. The
  ;; data-only whitelist refuses it with a 422 "bad args" before submission, so
  ;; the injected expression never becomes a database fact.
  (async done
    (let [payload "[[\"~#list\",[\"~$js/require\",\"child_process\"]]]"
          invocations (atom 0)
          original-db db/db
          original-capability call/capability-check
          original-submit call/submit!]
      (set! db/db (fn
                    ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! call/capability-check
            (fn [_ _ _]
              (js/Promise.resolve
               {::call/agent-id agent-id
                ::call/handler granted-sym
                ::call/handler-source-fingerprint
                (apply str (repeat 64 "a"))
                ::call/handler-spec "[:=> [:cat :string] :string]"})))
      (set! call/submit! (fn [& _] (swap! invocations inc)))
      (-> (call/handle! (call-req granted-sym payload))
          (.then
           (fn [response]
             (is (= 422 (.-status response)))
             (is (zero? @invocations))
             (.text response)))
          (.then (fn [body] (is (str/includes? body "bad args"))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! call/capability-check original-capability)
             (set! call/submit! original-submit)
             (done)))))))

(deftest call-malformed-args-writes-response-not-hang
  ;; Garbage ?args= (not valid transit) → a written 422, not an uncaught
  ;; rejection / hung request. handle! resolves with the response.
  (async done
    (let [original-db db/db
          original-capability call/capability-check]
      (set! db/db (fn
                    ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! call/capability-check
            (fn [_ _ _] (js/Promise.resolve {::call/agent-id agent-id})))
      (-> (call/handle! (call-req granted-sym "not-valid-transit-%%%"))
          (.then
           (fn [response]
             (is (= 422 (.-status response)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! call/capability-check original-capability)
             (done)))))))
