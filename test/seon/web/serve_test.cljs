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
    [goog.object :as gobj]
    [my.blob :as blob]
    [seon.agent.run :as run]
    [seon.agent.ctx :as ctx]
    [seon.ai :as ai]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.db.restore :as restore]
    [seon.eval :as seval]
    [seon.runtime.admission :as admission]
    [seon.web.router :as router]
    [seon.web.serve :as serve]))

(deftest agent-run-timeout-uses-explicit-value-or-run-policy
  (with-redefs [run/effective-deadline-ms
                (fn [request]
                  (is (= {:seon.db/db :frozen-db
                          :seon.agent/id "agent-1"}
                         request))
                  1800000)]
    (is (= 9000
           ((deref #'serve/agent-run-timeout-ms)
            :frozen-db "agent-1" 9000))
        "an explicit Inspect timeout remains an explicit experiment input")
    (is (= 1800000
           ((deref #'serve/agent-run-timeout-ms)
            :frozen-db "agent-1" nil))
        "absence derives from the database-backed run owner")))

(deftest eval-evidence-is-request-scoped-and-stably-ordered
  (let [first-at (js/Date. 1000)
        second-at (js/Date. 2000)
        outside-at (js/Date. 3000)
        final-coordinate {::coordinate/database-id
                          #uuid "00000000-0000-0000-0000-000000000001"
                          ::coordinate/branch :db
                          ::coordinate/commit-id
                          #uuid "00000000-0000-0000-0000-000000000002"
                          ::coordinate/t 30}
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
             final-coordinate
             #(get pulls %)
             10000)))))

(def ^:private evidence-coordinate
  {::coordinate/database-id #uuid "00000000-0000-0000-0000-000000000001"
   ::coordinate/branch :db
   ::coordinate/commit-id #uuid "00000000-0000-0000-0000-000000000002"
   ::coordinate/t 30})

(def ^:private evidence-operations
  [{:seon.db/read-operation :seon.db.read.operation/transact
    :seon.db/operation-position 0
    :seon.db/operation-ok? false
    :seon.db/operation-coordinate (assoc evidence-coordinate ::coordinate/t 20)
    :seon.db/read-source :seon.db.read.source/captured
    :seon.db/read-request {:seon.db/tx-data [{:my.row/id "one"}]}
    :seon.db/read-result {:seon.db/ok? false}
    :seon.db/read-replayable? false}
   {:seon.db/read-operation :seon.db.read.operation/query
    :seon.db/operation-position 1
    :seon.db/operation-ok? true
    :seon.db/operation-coordinate (assoc evidence-coordinate ::coordinate/t 21)
    :seon.db/read-source :seon.db.read.source/captured
    :seon.db/read-request {:seon.db/query '[:find (count ?e) . :where [?e]]}
    :seon.db/read-result 1
    :seon.db/read-replayable? true}])

(deftest operation-evidence-projection-is-bounded-lossless-and-fail-closed
  (let [content (pr-str evidence-operations)
        project (deref #'serve/project-operation-evidence)]
    (with-redefs [blob/get (fn [_]
                             {:my.blob/ok? true
                              :my.blob/content content
                              :my.blob/tokens 1})]
      (let [proof (project {:my.blob/hash "proof" :my.blob/tokens 1}
                           evidence-coordinate (inc (count content)))
            [tx query] (:operations proof)]
        (is (= "inline" (:status proof)))
        (is (= [0 1] (mapv :position (:operations proof))))
        (is (false? (:ok tx)) "failed transact remains failed")
        (is (true? (:coordinate_valid query)))
        (is (= {:kind "scalar" :value 1} (:result query)))
        (is (= "map" (get-in tx [:request :kind]))
            "namespaced request data uses the lossless tagged tree")))
    (with-redefs [blob/get (fn [_] (throw (js/Error. "must not read")))]
      (is (= {:status "oversized" :blob_hash "proof" :tokens 2}
             (project {:my.blob/hash "proof" :my.blob/tokens 2}
                      evidence-coordinate 4))
          "token projection stops oversized evidence before disk read"))
    (with-redefs [blob/get (constantly {:my.blob/ok? false})]
      (is (= "missing"
             (:status (project {:my.blob/hash "proof" :my.blob/tokens 1}
                               evidence-coordinate 100)))))
    (is (= {:status "missing"}
           (project {:my.blob/tokens 1} evidence-coordinate 100))
        "an invalid identity is omitted rather than fabricated")
    (with-redefs [blob/get (constantly {:my.blob/ok? true
                                        :my.blob/content "not-edn )"
                                        :my.blob/tokens 1})]
      (is (= "malformed"
             (:status (project {:my.blob/hash "proof" :my.blob/tokens 1}
                               evidence-coordinate 100)))))
    (with-redefs [blob/get (constantly {:my.blob/ok? true
                                        :my.blob/content "[] trailing"
                                        :my.blob/tokens 1})]
      (is (= "malformed"
             (:status (project {:my.blob/hash "proof" :my.blob/tokens 1}
                               evidence-coordinate 100)))
          "trailing forms cannot hide behind one valid prefix"))
    (with-redefs [blob/get (constantly {:my.blob/ok? true
                                        :my.blob/content "[]"
                                        :my.blob/tokens 2})]
      (is (= "malformed"
             (:status (project {:my.blob/hash "proof" :my.blob/tokens 1}
                               evidence-coordinate 100)))
          "blob bytes must match the final snapshot's token projection"))
    (with-redefs [blob/get (constantly {:my.blob/ok? true
                                        :my.blob/content "[12345]"
                                        :my.blob/tokens 1})]
      (is (= "oversized"
             (:status (project {:my.blob/hash "proof" :my.blob/tokens 1}
                               evidence-coordinate 4)))
          "pathological token/char mismatch remains status-only"))))

(deftest exact-transaction-origin-is-deduplicated-and-fails-closed
  (async done
    (let [!calls (atom 0)
          point (assoc evidence-coordinate ::coordinate/t 20)
          sibling (assoc point ::coordinate/commit-id
                         #uuid "00000000-0000-0000-0000-000000000099")
          validate-origin
          ((deref #'serve/coordinate-origin-validator)
           evidence-coordinate
           (fn [{:seon.db/keys [head-coordinate transaction-id]}]
             (swap! !calls inc)
             (is (= evidence-coordinate head-coordinate))
             (is (= 20 transaction-id))
             (js/Promise.resolve point)))
          proof {:status "inline"
                 :operations [{:coordinate_valid true
                               :coordinate
                               {:database_id
                                (str (::coordinate/database-id point))
                                :branch "db"
                                :commit_id
                                (str (::coordinate/commit-id point))
                                :t 20}}]}]
      (-> (js/Promise.all
            #js [(validate-origin point)
                 (validate-origin point)
                 (validate-origin sibling)])
          (.then
            (fn [validities]
              (is (= [true true false] (vec validities)))
              (is (= 1 @!calls)
                  "one final head and transaction t resolve only once")
              ((deref #'serve/require-exact-operation-origins)
               proof (constantly (js/Promise.resolve false)))))
          (.then
            (fn [rejected]
              (is (= "malformed" (:status rejected)))
              (is (not (contains? rejected :operations))
                  "wrong containing/nonancestor proof never remains inline")
              (done)))
          (.catch
            (fn [error]
              (is false (str error))
              (done)))))))

(deftest tagged-evidence-order-is-recursively-stable-and-unsupported-fails
  (let [project-value (deref #'serve/evidence-json-value)
        supported? (deref #'serve/supported-evidence-json?)
        left {:outer #{(array-map :b #{3 2 1} :a {:z 1 :y 2})}}
        right {:outer #{(array-map :a {:y 2 :z 1} :b #{1 3 2})}}
        left-json (project-value left)
        right-json (project-value right)]
    (is (= left-json right-json)
        "nested map/set construction order cannot perturb projected bytes")
    (is (true? (supported? left-json)))
    (is (false? (supported? (project-value #js {:runtime true})))
        "runtime values never become inline proof")))

(defn- model-attempt
  [ordinal t]
  {:seon.ai.attempt/ordinal ordinal
   :seon.ai.attempt/database-id (::coordinate/database-id evidence-coordinate)
   :seon.ai.attempt/branch (::coordinate/branch evidence-coordinate)
   :seon.ai.attempt/commit-id (::coordinate/commit-id evidence-coordinate)
   :seon.ai.attempt/t t
   :seon.ai.attempt/provider :deepseek
   :seon.ai.attempt/adapter :openai-compat
   :seon.ai.attempt/requested-model "small-model"
   :seon.ai.attempt/temperature 0.0
   :seon.ai.attempt/max-tokens 512
   :seon.ai.attempt/endpoint "http://127.0.0.1:8080/v1/chat/completions"
   :seon.ai.attempt/adapter-timeout-ms 30000
   :seon.ai.attempt/outer-timeout-ms 45000
   :seon.ai.attempt/stream? false
   :seon.ai.attempt/credential-class :configured-env
   :seon.ai.attempt/outcome (if (zero? ordinal) :provider-error :success)})

(deftest historical-attempt-adapter-and-turn-stream-are-rederived
  (async done
    (let [attempt (model-attempt 0 20)
          resolved {:seon.ai/provider :deepseek
                    :seon.ai/model "small-model"
                    :seon.ai/temperature 0.0
                    :seon.ai/max-tokens 512
                    :seon.ai/timeout-ms 30000
                    :seon.ai/base-url "http://127.0.0.1:8080/v1"
                    :seon.config.model-transport/endpoint-cap 2048}
          config-valid? (deref #'serve/historical-attempt-config-valid?)
          stream-valid? (deref #'serve/historical-turn-stream-valid?)]
      (with-redefs [db/at-coordinate
                    (fn
                      ([_] (js/Promise.resolve {:historical true}))
                      ([_ _] (js/Promise.resolve {:historical true})))
                    ai/resolved-config
                    (fn [_] {:seon.ai/resolved-config resolved})
                    ctx/repl-mode (constantly :batch)]
        (-> (js/Promise.all
              #js [(config-valid? :conn "agent" attempt)
                   (config-valid? :conn "agent"
                                  (assoc attempt
                                         :seon.ai.attempt/adapter :anthropic))
                   (stream-valid? :conn evidence-coordinate [attempt])
                   (stream-valid?
                     :conn evidence-coordinate
                     [(assoc attempt :seon.ai.attempt/stream? true)])])
            (.then
              (fn [validities]
                (is (= [true false true false] (vec validities))
                    "stored adapter and stream mode must match their owners")
                (done)))
            (.catch
              (fn [error]
                (is false (str error))
                (done))))))))

(deftest model-transport-evidence-is-final-snapshot-ordered-and-bounded
  (let [project (deref #'serve/project-model-transport-rows)
        attempts {101 (model-attempt 1 21)
                  100 (model-attempt 0 20)}
        proof (project [[10 "turn-a"]] [[10 101] [10 100]]
                       evidence-coordinate #(get attempts %) (constantly true)
                       10000)
        projected (:attempts (first (:turns proof)))
        foreign-proof
        (project
          [[10 "turn-a"]] [[10 100]] evidence-coordinate
          (fn [_]
            (assoc (model-attempt 0 20)
                   :seon.ai.attempt/database-id
                   #uuid "00000000-0000-0000-0000-000000000099"))
          (constantly true) 10000)
        unretained-proof
        (project [[10 "turn-a"]] [[10 100]] evidence-coordinate
                 (fn [_] (model-attempt 0 20)) (constantly false) 10000)
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
    (is (= 0.0 (:temperature (first projected)))
        "present zero sampling configuration survives projection")
    (is (false? (:transport_drift proof)))
    (is (every? true? (map :coordinate_valid projected)))
    (is (= "malformed"
           (:status (project [[10 "turn-a"]] [[10 100]] evidence-coordinate
                             (fn [_] (model-attempt 1 20)) (constantly true)
                             10000)))
        "a missing ordinal zero fails closed without projecting a row")
    (is (false? (get-in foreign-proof
                        [:turns 0 :attempts 0 :coordinate_valid]))
        "foreign attachment evidence remains explicit and rejectable")
    (is (false? (get-in unretained-proof
                        [:turns 0 :attempts 0 :coordinate_valid]))
        "an unretained historical commit fails exact coordinate validation")
    (is (true? ((deref #'serve/attempt-config-matches?)
                (model-attempt 0 20) expected-config))
        "optional thinking remains absent on both stored and resolved config")
    (is (false? ((deref #'serve/attempt-config-matches?)
                 (assoc (model-attempt 0 20)
                        :seon.ai.attempt/max-tokens 1024)
                 expected-config))
        "stored request facts cannot disagree with historical resolution")
    (is (= "oversized"
           (:status (project [[10 "turn-a"]] [[10 100]] evidence-coordinate
                             (fn [_] (model-attempt 0 20)) (constantly true)
                             4)))
        "the database-backed evidence cap governs the response")
    (is (= {:status "absent"}
           (project [[10 "turn-a"]] [] evidence-coordinate (constantly nil)
                    (constantly false) 10000))
        "absence stays distinguishable from an empty successful proof")))

(deftest historical-identity-caps-and-compatibility-config-preserve-absence
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
        "absent optional compatibility fields remain absent")))

(defn- req-with-origin
  ([origin] (req-with-origin origin nil))
  ([origin host]
   (let [h #js {}]
     (when origin (gobj/set h "origin" origin))
     (when host   (gobj/set h "host" host))
     #js {:headers h})))

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

(deftest operator-peer-identity-uses-the-socket-and-fails-closed
  (doseq [address ["127.0.0.1" "::1" "::ffff:127.0.0.1"]]
    (is (true? (serve/loopback-peer?
                 #js {:socket #js {:remoteAddress address}}))
        (str address " is a kernel-reported loopback peer")))
  (is (false? (serve/loopback-peer?
                #js {:socket #js {:remoteAddress "192.0.2.10"}})))
  (is (false? (serve/loopback-peer? #js {:headers #js {"host" "127.0.0.1"}}))
      "a forgeable Host header cannot replace missing peer evidence"))

(deftest operator-quiesce-flushes-only-after-the-lifecycle-result-resolves
  (async done
    (let [original-lookup seval/lookup-value
          resolve-result (atom nil)
          result-promise (js/Promise. (fn [resolve _reject]
                                        (reset! resolve-result resolve)))
          observed (atom {})
          response #js {:writeHead
                        (fn [status headers]
                          (swap! observed assoc
                                 ::status status
                                 ::headers (js->clj headers)))
                        :end
                        (fn [body]
                          (swap! observed assoc ::body body)
                          (is (= 200 (::status @observed)))
                          (is (= "application/edn; charset=utf-8"
                                 (get (::headers @observed) "Content-Type")))
                          (is (= {:seon.client/quiesced? true
                                  :seon.client/quiesced-run-ids []
                                  :seon.client/completed-turn-ids []
                                  :seon.client/errored-turn-ids []
                                  :seon.agent.runtime/unhosted-ids []}
                                 (reader/read-string body)))
                          (set! seval/lookup-value original-lookup)
                          (done))}]
      (set! seval/lookup-value
            (fn [symbol]
              (is (= 'seon.client/quiesce-runtime! symbol))
              (fn [] result-promise)))
      ((deref #'serve/handle-operator-quiesce!) nil response)
      (is (empty? @observed)
          "the HTTP response remains open while remote release is pending")
      (@resolve-result
       {:seon.client/quiesced? true
        :seon.client/quiesced-run-ids []
        :seon.client/completed-turn-ids []
        :seon.client/errored-turn-ids []
        :seon.agent.runtime/unhosted-ids []}))))

(defn- readiness-response
  "Resolve with the status and body written by the readiness handler."
  ([] (readiness-response nil))
  ([restore-completion-result]
   (let [!response (atom {})]
     (js/Promise.
       (fn [resolve _reject]
         ((deref #'serve/handle-readiness!)
          restore-completion-result nil
          #js {:writeHead
               (fn [status _headers]
                 (swap! !response assoc ::status status))
               :end
               (fn [body]
                 (swap! !response assoc ::body body)
                 (resolve @!response))}))))))

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
          !response (atom {})
          request #js {:method "GET" :url "/_seon/ready" :headers #js {}}
          response-promise
          (js/Promise.
            (fn [resolve _reject]
              (reset! @#'admission/!state {::admission/status :available})
              (router/handle-request
                request
                #js {:writeHead
                     (fn [status _headers]
                       (swap! !response assoc ::status status))
                     :end
                     (fn [body]
                       (swap! !response assoc ::body body)
                       (resolve @!response))})))]
      (-> response-promise
          (.then
            (fn [response]
              (is (= 200 (::status response)))
              (is (true? (::restore/executable?
                           (reader/read-string (::body response)))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (reset! @#'admission/!state prior)
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
          c {::coordinate/database-id (::restore/database-id completion)
             ::coordinate/branch :db
             ::coordinate/commit-id (::restore/forced-commit-id completion)
             ::coordinate/t 11}
          rows (mapv (fn [attr] [attr (::coordinate/t c)]) (keys completion))
          recorded {::restore/ok? true
                    ::restore/recorded? true
                    ::restore/already-completed? false
                    ::restore/completion completion
                    ::restore/completion-coordinate c}]
      (set! db/attached? (constantly true))
      (set! restore/acquire-completion!
            (fn [_]
              (js/Promise.resolve
                {::restore/current-coordinate c
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
                       {::restore/current-coordinate (update c ::coordinate/t inc)
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
