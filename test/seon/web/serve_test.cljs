(ns seon.web.serve-test
  "The pod HTTP dispatch — the CSRF / same-origin guard on state-changing POSTs.

   Loopback BINDING is not a security boundary: a page on any site the human
   visits can `no-cors` POST to 127.0.0.1. The browser attaches an `Origin`
   header on such cross-site requests, so the dispatch refuses any POST whose
   Origin is present and NOT loopback. Absent Origin (curl / the agent /
   non-browser) is allowed; the pod's own loopback UI is allowed."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [goog.object :as gobj]
    [seon.agent :as agent]
    [seon.agent.debug :as agent-debug]
    [seon.agent.runtime :as agent-runtime]
    [seon.agent.run :as run]
    [seon.ai :as ai]
    [seon.db :as db]
    [seon.db.branch :as branch]
    [seon.db.restore :as restore]
    [seon.eval :as seval]
    [seon.render.system :as system]
    [seon.runtime.admission :as admission]
    [seon.web.debug :as debug]
    [seon.web.router :as router]
    [seon.web.serve :as serve]))

(def ^:private database
  {:db-name "test"
   :t 30
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(deftest explicit-agent-task-hosts-the-durable-agent-before-intake
  (async done
    (let [run-task (deref #'serve/run-agent-task!)
          original-db db/db
          original-query db/query
          original-resume agent-runtime/resume!
          original-message agent/message!
          calls (atom [])]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/query (fn [_] (js/Promise.resolve 42)))
      (set! agent-runtime/resume!
            (fn [request]
              (swap! calls conj [:resume request])
              (js/Promise.resolve
               {:seon.agent/id "root"
                ::agent-runtime/resumed? false
                ::agent-runtime/error "host refused"})))
      (set! agent/message!
            (fn [request]
              (swap! calls conj [:message request])
              (js/Promise.resolve {})))
      (-> (run-task "root" "work" 1000)
          (.then
           (fn [result]
             (is (= {:error "host refused"} result))
             (is (= [[:resume {:seon.agent/id "root"}]] @calls))))
          (.catch
           (fn [error]
             (is false (str "task hosting rejected: " error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/query original-query)
             (set! agent-runtime/resume! original-resume)
             (set! agent/message! original-message)
             (done)))))))

(deftest agent-creation-form-preserves-lifecycle-data
  (let [parse (deref #'serve/agent-creation-request)]
    (is (= {:seon.agent/namespace 'my.tax
            :seon.agent/purpose "maintain tax records"
            :seon.agent.message/content "Review the latest return"}
           (parse {"namespace" "  my.tax  "
                   "purpose" "  maintain tax records  "
                   "message" "  Review the latest return  "})))
    (is (= {}
           (parse {"namespace" " " "purpose" "" "message" "  "})))
    (doseq [invalid ["my.tax/worker" "(my.tax)" "my.tax other" ":my.tax"]]
      (is (= :user-input
             (:seon.error/kind (parse {"namespace" invalid})))
          (str "refuses invalid namespace field " (pr-str invalid))))))

(defn- agent-creation-request [body]
  (js/Request.
   "http://127.0.0.1/agents"
   #js {:method "POST"
        :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
        :body body}))

(deftest agents-post-selects-start-or-atomic-delegation
  (async done
    (let [original-start agent/start!
          original-delegate agent/delegate!
          original-available admission/available?
          calls (atom [])]
      (set! admission/available? (constantly true))
      (set! agent/start!
            (fn [request]
              (swap! calls conj [:start (db/current-agent-id) request])
              (js/Promise.resolve {:seon.agent/id "idle-child"})))
      (set! agent/delegate!
            (fn [request]
              (swap! calls conj [:delegate (db/current-agent-id) request])
              (js/Promise.resolve {:seon.agent/id "tax-resident"})))
      (-> (serve/create-agent!
           {:seon.http/request
            (agent-creation-request
             "namespace=my.idle&purpose=wait")})
          (.then
           (fn [response]
             (is (= 200 (.-status response)))
             (.text response)))
          (.then
           (fn [body]
             (is (= "idle-child" body))
             (serve/create-agent!
              {:seon.http/request
               (agent-creation-request
                "namespace=my.tax&purpose=taxes&message=Review+the+return")})))
          (.then
           (fn [response]
             (is (= 200 (.-status response)))
             (.text response)))
          (.then
           (fn [body]
             (is (= "tax-resident" body))
             (is (= [[:start "root"
                      {:seon.agent/namespace 'my.idle
                       :seon.agent/purpose "wait"}]
                     [:delegate "root"
                      {:seon.agent/namespace 'my.tax
                       :seon.agent/purpose "taxes"
                       :seon.agent.message/content "Review the return"}]]
                    @calls))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! agent/start! original-start)
             (set! agent/delegate! original-delegate)
             (set! admission/available? original-available)
             (done)))))))

(deftest agents-post-refuses-an-invalid-namespace-before-lifecycle
  (async done
    (let [original-start agent/start!
          original-delegate agent/delegate!
          original-available admission/available?
          calls (atom 0)
          called (fn [_]
                   (swap! calls inc)
                   (js/Promise.resolve {:seon.agent/id "unexpected"}))]
      (set! admission/available? (constantly true))
      (set! agent/start! called)
      (set! agent/delegate! called)
      (-> (serve/create-agent!
           {:seon.http/request
            (agent-creation-request "namespace=my.tax%2Fworker")})
          (.then
           (fn [response]
             (is (= 422 (.-status response)))
             (.text response)))
          (.then
           (fn [body]
             (is (str/includes? body "valid unqualified ClojureScript symbol"))
             (is (zero? @calls))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! agent/start! original-start)
             (set! agent/delegate! original-delegate)
             (set! admission/available? original-available)
             (done)))))))

(deftest database-view-uses-the-public-index-page-fields
  (async done
    (let [original db/index-page
          original-fleet system/acquire-fleet-summary
          request (atom nil)]
      (set! db/index-page
            (fn index-page-stub
              ([value]
               (reset! request value)
               (js/Promise.resolve
                {:datahike.index-page/datoms
                 [[1 :seon.agent/id "root" 2 true]]}))
              ([_database _options]
               (js/Promise.reject
                (js/Error. "database view must use the map request")))))
      (set! system/acquire-fleet-summary
            (fn [value]
              (is (identical? database value))
              (js/Promise.resolve
               [{:seon.agent/id "root" ::system/state :idle}
                {:seon.agent/id "worker" ::system/state :running}])))
      (-> (js/Promise.resolve nil)
          (.then (fn [] ((deref #'debug/render-data!) database nil)))
          (.then
           (fn [element]
             (is (= {::db/db database
                     ::db/index :aevt
                     ::db/direction :forward
                     ::db/limit 50}
                    @request))
             (is (str/includes? (pr-str element) ":seon.agent/id")
                 "the view consumes Datahike's index-page datoms field")
             (is (str/includes? (pr-str element) ":data-agent-count 2"))
             (is (str/includes? (pr-str element) ":data-running-agents 1"))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/index-page original)
             (set! system/acquire-fleet-summary original-fleet)
             (done)))))))

(deftest database-and-debug-shells-leave-the-header-to-the-feed-morph
  (let [markup ((deref #'debug/page-html) "data" "/data/feed" "loading")]
    (is (= 1 (count (re-seq #"id=\"app-view\"" markup))))
    (is (not (str/includes? markup "id=\"system-header\"")))))

(deftest agent-run-timeout-uses-explicit-value-or-run-policy
  (async done
    (let [original run/effective-deadline-ms]
      (set! run/effective-deadline-ms
            (fn [request]
              (is (= {:seon.db/db :frozen-db
                      :seon.agent/id "agent-1"}
                     request))
              (js/Promise.resolve 1800000)))
      (-> (js/Promise.all
           #js [((deref #'serve/agent-run-timeout-ms)
                 :frozen-db "agent-1" 9000)
                ((deref #'serve/agent-run-timeout-ms)
                 :frozen-db "agent-1" nil)])
          (.then
           (fn [timeouts]
             (is (= [9000 1800000] (vec timeouts)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! run/effective-deadline-ms original)
             (done)))))))

(deftest agent-run-waits-for-terminal-turn-recording
  (async done
    (let [original db/query
          requests (atom [])
          responses (atom [[[:done] [:running]] [[:done] [:interrupted]]])]
      (set! db/query
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve (ffirst (swap-vals! responses rest)))))
      (-> (js/Promise.all
           #js [((deref #'serve/task-turns-settled?)
                 database "agent-1" 1000)
                ((deref #'serve/task-turns-settled?)
                 database "agent-1" 1000)])
          (.then
           (fn [settled]
             (is (= [false true] (vec settled)))
             (is (every? #(identical? database (::db/db %)) @requests))
             (is (every? #(and (= "agent-1" (first (::db/args %)))
                               (= 1000 (.getTime (second (::db/args %)))))
                         @requests))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/query original)
             (done)))))))

(deftest eval-evidence-is-request-scoped-and-stably-ordered
  (let [first-at (js/Date. 1000)
        second-at (js/Date. 2000)
        outside-at (js/Date. 3000)
        pulls {100 {:seon.eval/source "(first)"
                    :seon.eval/ok? true}
               101 {:seon.eval/source "(second)"
                    :seon.eval/ok? false
                    :seon.eval/narration "kept as bounded data"}
               102 {:seon.eval/source "(outside)"
                    :seon.eval/ok? true}}]
    (is (= [{:eval_id "eval-a" :turn_id "turn-a" :eval_transaction 20
             :at "1970-01-01T00:00:01.000Z"
             :ok true
             :source "(first)"}
            {:eval_id "eval-b" :turn_id "turn-b" :eval_transaction 21
             :at "1970-01-01T00:00:02.000Z"
             :ok false
             :source "(second)"
             :narration "kept as bounded data"}]
           ((deref #'serve/project-eval-evidence)
            [[102 12 "turn-c" "eval-c" outside-at 22]
             [101 11 "turn-b" "eval-b" second-at 21]
             [100 10 "turn-a" "eval-a" first-at 20]]
            #{10 11}
            #(get pulls %))))))

(deftest turn-evidence-reuses-one-database-value-and-native-transaction
  (async done
    (let [original agent-debug/turn
          requests (atom [])]
      (set! agent-debug/turn
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {:seon.agent.debug/ok? true
                :seon.agent.turn/rendered-tx
                (if (= "turn-a" (:seon.agent.turn/id request)) 20 21)})))
      (-> (js/Promise.resolve nil)
          (.then
           (fn ^:async run []
             (let [rows
                   (await
                    ((deref #'serve/turn-evidence)
                     database ["turn-a" "turn-b"]))]
               (is (= ["turn-a" "turn-b"] (mapv :turn_id rows)))
               (is (= [20 21] (mapv :rendered_transaction rows)))
               (is (not-any? #(contains? % :rendered_coordinate) rows))
               (is (every? #(identical? database (:seon.db/db %))
                           @requests)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! agent-debug/turn original)
             (done)))))))

(deftest missing-rendered-transaction-does-not-hide-the-turn
  (async done
    (let [original db/pull-many
          at (js/Date. 1000)
          rows [[10 "turn-a" at 1] [11 "turn-b" at 1]]]
      (set! db/pull-many
            (fn
              ([request]
               (is (identical? database (:seon.db/db request)))
               (js/Promise.resolve
                [{:seon.agent.turn/rendered-tx {:db/id 20}} {}]))
              ([_selector _entity-ids]
               (js/Promise.reject
                (js/Error. "unexpected positional pull-many")))
              ([_database _selector _entity-ids]
               (js/Promise.reject
                (js/Error. "unexpected positional pull-many")))))
      (-> (js/Promise.resolve nil)
          (.then
           (fn ^:async run []
             (let [actual
                   (await
                    ((deref #'serve/turn-rows-with-rendered-tx)
                     database rows))]
               (is (= 2 (count actual)))
               (is (= 20 (nth (first actual) 4)))
               (is (nil? (nth (second actual) 4))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/pull-many original)
             (done)))))))

(defn- model-attempt [ordinal]
  {:seon.ai.attempt/ordinal ordinal
   :seon.ai.attempt/provider :deepseek
   :seon.ai.attempt/adapter :openai-compat
   :seon.ai.attempt/requested-model "small-model"
   :seon.ai.attempt/temperature 0.0
   :seon.ai.attempt/max-tokens 512
   :seon.ai.attempt/endpoint
   "http://127.0.0.1:8080/v1/chat/completions"
   :seon.ai.attempt/adapter-timeout-ms 30000
   :seon.ai.attempt/outer-timeout-ms 45000
   :seon.ai.attempt/stream? false
   :seon.ai.attempt/credential-class :configured-env
   :seon.ai.attempt/outcome
   (if (zero? ordinal) :provider-error :success)})

(deftest historical-attempt-validation-uses-the-turns-database-value
  (async done
    (let [original-pull db/pull
          original-resolve ai/resolved-config-from-rows
          requests (atom [])
          resolved {:seon.ai/provider :deepseek
                    :seon.ai/model "small-model"
                    :seon.ai/temperature 0.0
                    :seon.ai/max-tokens 512
                    :seon.ai/timeout-ms 30000
                    :seon.ai/base-url "http://127.0.0.1:8080/v1"
                    :seon.config.model-transport/endpoint-cap 2048
                    :seon.config.model-transport/response-identity-cap 128}]
      (set! db/pull
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                (if (= [:seon.config/id "cluster"] (:seon.db/ref request))
                  {:seon.config/repl-mode :batch}
                  {})))
              ([_selector _entity-id]
               (js/Promise.reject
                (js/Error. "unexpected positional pull")))
              ([_database _selector _entity-id]
               (js/Promise.reject
                (js/Error. "unexpected positional pull")))))
      (set! ai/resolved-config-from-rows
            (fn [_ _] {:seon.ai/resolved-config resolved}))
      (-> (js/Promise.all
           #js [((deref #'serve/historical-turn-valid?)
                 database "agent-1" 20 [(model-attempt 0)])
                ((deref #'serve/historical-turn-valid?)
                 database "agent-1" nil [(model-attempt 0)])])
          (.then
           (fn [validities]
             (is (= [true false] (vec validities)))
             (is (= 3 (count @requests)))
             (is (every? #(= (assoc database :as-of 20 :since nil)
                              (:seon.db/db %))
                         @requests))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/pull original-pull)
             (set! ai/resolved-config-from-rows original-resolve)
             (done)))))))

(deftest historical-attempt-validation-preserves-database-errors
  (async done
    (let [original db/pull
          database-error {:seon.error/message "historical database unavailable"
                          :seon.error/kind :core-bug}
          pull-stub (fn [_] (js/Promise.resolve database-error))]
      (set! (.-cljs$core$IFn$_invoke$arity$1 pull-stub) pull-stub)
      (set! db/pull pull-stub)
      (-> (js/Promise.resolve
           ((deref #'serve/historical-turn-valid?)
            database "agent-1" 20 [(model-attempt 0)]))
          (.then (fn [result] (is (= database-error result))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/pull original)
             (done)))))))

(deftest model-transport-projection-is-ordered-bounded-and-fail-closed
  (let [project (deref #'serve/project-model-transport-rows)
        attempts {101 (model-attempt 1)
                  100 (model-attempt 0)}
        proof (project [[10 "turn-a"]] [[10 101] [10 100]]
                       #(get attempts %) (constantly true))
        projected (:attempts (first (:turns proof)))
        expected-config
        {:seon.ai.attempt/provider :deepseek
         :seon.ai.attempt/requested-model "small-model"
         :seon.ai.attempt/temperature 0.0
         :seon.ai.attempt/max-tokens 512
         :seon.ai.attempt/endpoint
         "http://127.0.0.1:8080/v1/chat/completions"
         :seon.ai.attempt/adapter-timeout-ms 30000}]
    (is (= "inline" (:status proof)))
    (is (= [0 1] (mapv :ordinal projected)))
    (is (= 0.0 (:temperature (first projected))))
    (is (false? (:transport_drift proof)))
    (is (every? true? (map :historical_config_valid projected)))
    (is (= {:status "malformed"
            :invalid_turns
            [{:turn_id "turn-a"
              :attempt_ordinals_valid false
              :attempt_rows_valid true
              :historical_config_valid true}]}
           (project [[10 "turn-a"]] [[10 100]]
                    (fn [_] (model-attempt 1))
                    (constantly true)))
        "a missing ordinal zero fails closed with bounded evidence")
    (is (= "malformed"
           (:status
            (project [[10 "turn-a"]] [[10 100]]
                     #(get attempts %) (constantly false))))
        "a failed historical reconstruction fails closed")
    (is (true? ((deref #'serve/attempt-config-matches?)
                (model-attempt 0) expected-config)))
    (is (false? ((deref #'serve/attempt-config-matches?)
                 (assoc (model-attempt 0)
                        :seon.ai.attempt/max-tokens 1024)
                 expected-config)))
    (is (= {:status "absent"}
           (project [[10 "turn-a"]] []
                    (constantly nil) (constantly false))))))

(deftest historical-identity-caps-and-config-preserve-absence
  (let [identity-valid? (deref #'serve/response-identity-valid?)
        project-config (deref #'serve/model-config-json)
        cap-config {:seon.config.model-transport/response-identity-cap 4}]
    (is (true? (identity-valid? {} {})))
    (is (false? (identity-valid?
                  {:seon.ai.attempt/response-model "m"} {}))
        "identity evidence is impossible when the historical cap is absent")
    (is (true? (identity-valid?
                 {:seon.ai.attempt/response-model "1234"} cap-config)))
    (is (false? (identity-valid?
                  {:seon.ai.attempt/response-model "12345"} cap-config)))
    (is (false? (identity-valid?
                  {:seon.ai.attempt/evidence-error "12345"} cap-config))
        "bounded generic evidence errors obey the same historical cap")
    (is (= {:provider "deepseek"
            :temperature 0.0
            :thinking false}
           (project-config {:seon.ai/provider :deepseek
                            :seon.ai/temperature 0.0
                            :seon.ai/thinking false}))
        "present zero and false-like values never disappear by truthiness")
    (is (= {:provider "deepseek"}
           (project-config {:seon.ai/provider :deepseek}))
        "absent optional fields remain absent")))

(deftest model-transport-evidence-needs-no-render-cap
  (async done
    (let [pull db/pull]
      (set! db/pull
            (fn
              ([value]
               (js/Promise.reject
                (js/Error. (str "unexpected pull " value))))
              ([_pattern _ref]
               (js/Promise.reject (js/Error. "unexpected positional pull")))
              ([_database _pattern _ref]
               (js/Promise.reject (js/Error. "unexpected legacy pull")))))
      (-> (js/Promise.resolve
           (@#'serve/project-model-transport-evidence
            database "agent-a" []))
          (.then
           (fn [result]
             (is (= {:status "absent"} result))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/pull pull)
             (done)))))))

(defn- req-with-origin
  ([origin] (req-with-origin origin nil))
  ([origin host]
   (js/Request.
    "http://127.0.0.1:7890/"
    #js {:headers (clj->js
                   (cond-> {}
                     origin (assoc "origin" origin)
                     host (assoc "host" host)))})))

(deftest same-origin-allows-loopback-and-absent-refuses-cross-site
  (testing "absent Origin (curl / the agent / non-browser) is allowed"
    (is (true? (serve/same-origin? (req-with-origin nil)))))
  (testing "the pod's own loopback UI origins are allowed (no Host → loopback fallback)"
    (is (true? (serve/same-origin? (req-with-origin "http://127.0.0.1:7890"))))
    (is (true? (serve/same-origin? (req-with-origin "http://localhost:7890"))))
    (is (true? (serve/same-origin? (req-with-origin "http://[::1]:7890")))))
  (testing "a genuine same-origin request behind a non-loopback front is allowed (Host matches)"
    (is (true? (serve/same-origin? (req-with-origin "https://pod.example" "pod.example")))))
  (testing "a cross-site Origin (any internet page the human visits) is refused"
    (is (false? (serve/same-origin? (req-with-origin "https://evil.example.com"))))
    (is (false? (serve/same-origin? (req-with-origin "http://attacker.test:80"))))
    (testing "even when a Host header is present but does NOT match the Origin"
      (is (false? (serve/same-origin? (req-with-origin "https://evil.example.com" "127.0.0.1:7890")))))))

(deftest operator-peer-identity-uses-bun-request-ip-and-fails-closed
  (doseq [address ["127.0.0.1" "::1" "::ffff:127.0.0.1"]]
    (is (true? (serve/loopback-peer? #js {}
                                      #js {:requestIP (fn [_] #js {:address address})}))
        (str address " is a kernel-reported loopback peer")))
  (is (false? (serve/loopback-peer? #js {}
                                     #js {:requestIP (fn [_] #js {:address "192.0.2.10"})})))
  (is (false? (serve/loopback-peer? #js {} nil))
      "a forgeable Host header cannot replace missing peer evidence"))

(defn- readiness-response
  "Resolve with readiness response data."
  ([] (readiness-response nil))
  ([restore-completion-result]
   (-> ((deref #'serve/handle-readiness!) restore-completion-result nil nil)
       (.then (fn [response]
                (-> (.text response)
                    (.then (fn [body]
                             {::status (.-status response) ::body body}))))))))

(deftest readiness-tracks-admission-after-startup
  (async done
    (let [prior (admission/state)]
      (-> (js/Promise.resolve nil)
          (.then
            (fn ^:async run []
              (reset! @#'admission/!state
                      {::admission/status :available
                       ::admission/generation 17})
              (let [response (await (readiness-response))
                    body (reader/read-string (::body response))]
                (is (= 200 (::status response)))
                (is (true? (::restore/executable? body))))
              (reset! @#'admission/!state
                      {::admission/status :unavailable
                       ::admission/generation 17
                       ::admission/reason "injected publication failure"})
              (let [response (await (readiness-response))
                    body (reader/read-string (::body response))]
                (is (= 503 (::status response)))
                (is (= :unavailable (::admission/status body)))
                (is (false? (::restore/executable? body))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (reset! @#'admission/!state prior)
              (done)))))))

(deftest ordinary-readiness-dispatches-through-the-installed-router
  (async done
    (let [prior (admission/state)
          request (js/Request. "http://127.0.0.1/_seon/ready")
          _ (reset! @#'admission/!state {::admission/status :available})
          response-promise (js/Promise.resolve (router/handle-request request nil))]
      (-> response-promise
          (.then (fn [response]
                   (is (= 200 (.-status response)))
                   (.text response)))
          (.then (fn [body]
                   (is (true? (::restore/executable? (reader/read-string body))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (reset! @#'admission/!state prior)
              (done)))))))

(deftest product-evidence-uses-one-database-value-and-namespaced-result
  (async done
    (let [commit-id (random-uuid)
          database {:db-name "proof"
                    :t 42 :as-of nil :since nil :history false
                    :datahike/commit-id commit-id}
          !requests (atom [])
          original-db db/db
          original-query db/query]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/query (fn ([request]
                        (swap! !requests conj request)
                        (js/Promise.resolve #{[:my.taxes 2]}))
                       ([query & args]
                        (swap! !requests conj {::db/query query ::db/args args})
                        (js/Promise.resolve #{[:my.taxes 2]}))))
      (-> (serve/product-evidence
             {::db/query '[:find ?namespace ?count
                           :where [?agent :seon.agent/namespace ?namespace]]
              ::db/args []})
            (.then
             (fn [result]
               (is (true? (:seon.db/ok? result)))
               (is (= database (::db/db (first @!requests))))
               (is (= [[":my.taxes" 2]] (:seon.db/result result)))
               (is (= {:db_name "proof"
                       :t 42 :as_of nil :since nil :history false
                       :commit_id (str commit-id)}
                      (:seon.db/db result)))
               (is (= {"seon.db/ok?" true
                       "seon.db/db"
                       {"db_name" "proof"
                        "t" 42 "as_of" nil "since" nil "history" false
                        "commit_id" (str commit-id)}
                       "seon.db/result" [[":my.taxes" 2]]}
                      (#'serve/product-evidence-json-value result)))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (set! db/db original-db)
                      (set! db/query original-query)
                      (done)))))))

(deftest restore-readiness-serves-only-the-exact-closed-completion-head
  (async done
    (let [prior-admission (admission/state)
          original-attached? db/attached?
          original-acquire restore/acquire-completion!
          completion-claim
          {::restore/plan-digest (apply str (repeat 64 "a"))
           ::restore/db-name :default
           ::restore/database-id
           #uuid "11111111-1111-4111-8111-111111111111"
           ::restore/from-branch :db
           ::restore/from-commit-id
           #uuid "22222222-2222-4222-8222-222222222222"
           ::restore/from-t 10
           ::restore/to-branch :retained
           ::restore/to-commit-id
           #uuid "33333333-3333-4333-8333-333333333333"
           ::restore/to-t 8
           ::restore/forced-commit-id
           #uuid "44444444-4444-4444-8444-444444444444"
           ::restore/undo-branch :undo
           ::restore/target-branch :target}
          completion (assoc completion-claim ::restore/id "restore00001")
          c {::branch/store-id (::restore/database-id completion)
             ::branch/name :db
             ::branch/commit-id (::restore/forced-commit-id completion)
             ::branch/basis-t 11}
          database {:db-name "default"
                    :store-id (branch/connection-id c)
                    :t (::branch/basis-t c)
                    :as-of nil
                    :since nil
                    :history false
                    :datahike/commit-id (::branch/commit-id c)}
          rows (mapv (fn [attr] [attr (::branch/basis-t c)]) (keys completion))
          recorded {::restore/ok? true
                    ::restore/recorded? true
                    ::restore/already-completed? false
                    ::restore/completion completion
                    ::restore/completion-branch-head c}]
      (set! db/attached? (constantly true))
      (set! restore/acquire-completion!
            (fn [_]
              (js/Promise.resolve
                {::restore/current-db database
                 ::restore/installed-schema {}
                 ::restore/completion completion
                 ::restore/publication-rows rows})))
      (reset! @#'admission/!state {::admission/status :publishing})
      (-> (js/Promise.resolve nil)
          (.then
           (fn ^:async run []
             (let [response (await (readiness-response recorded))]
               (is (= 200 (::status response))))
             (set! db/attached? (constantly false))
             (let [response (await (readiness-response recorded))]
               (is (= 503 (::status response))))
             (set! db/attached? (constantly true))
             (set! restore/acquire-completion!
                   (fn [_]
                     (js/Promise.resolve
                       {::restore/current-db
                        (-> database
                            (update :t inc)
                            (assoc :datahike/commit-id (random-uuid)))
                        ::restore/installed-schema {}
                        ::restore/completion completion
                        ::restore/publication-rows rows})))
             (let [response (await (readiness-response recorded))]
               (is (= 503 (::status response))))))
          (.catch (fn [error]
                    (is false (str "restore readiness endpoint threw " error))))
          (.finally
           (fn []
             (set! db/attached? original-attached?)
             (set! restore/acquire-completion! original-acquire)
             (reset! @#'admission/!state prior-admission)
             (done)))))))
