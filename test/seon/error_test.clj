(ns seon.error-test
  "Unit, schema, generative (seed = 42), rule-engine, and Flow/HTTP 429 surfacing tests for `seon.error` & `seon.model`."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [ring-chez.adapter :as adapter]
            [seon.agent :as agents]
            [seon.error :as error]
            [seon.model :as model]
            [seon.render :as render]
            [seon.rule :as rules]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.health :as health]))

(deftest error-tx-and-resolve-error-tx-test
  (testing "error-tx validates and builds bare and namespaced :seon.error/* entities"
    (let [e1 (error/error-tx {:kind :model/rate-limit
                              :message "HTTP 429 rate limited"
                              :source :model
                              :severity :warning
                              :agent :ag/worker-1
                              :branch :branch/worker-1
                              :data {:status 429 :retry-after-ms 3000}
                              :var 'seon.model/model-proc})
          eid (random-uuid)
          eval-id (random-uuid)
          e2 (error/error-tx {:seon.error/id eid
                              :seon.error/kind :eval/allocation
                              :seon.error/severity :fatal
                              :seon.error/source :eval
                              :seon.error/message "Budget exceeded"
                              :seon.error/status :suppressed
                              :seon.error/at 1700000000000
                              :seon.error/eval eval-id})]
      (is (uuid? (:seon.error/id e1)))
      (is (= :model/rate-limit (:seon.error/kind e1)))
      (is (= :warning (:seon.error/severity e1)))
      (is (= :model (:seon.error/source e1)))
      (is (= :open (:seon.error/status e1)))
      (is (= :ag/worker-1 (:seon.error/agent e1)))
      (is (= :branch/worker-1 (:seon.error/branch e1)))
      (is (= {:status 429 :retry-after-ms 3000} (:seon.error/data e1)))
      (is (= 'seon.model/model-proc (:seon.error/var e1)))
      (is (= :seon.error/entity (:seon/schema (meta e1))))

      (is (= eid (:seon.error/id e2)))
      (is (= :fatal (:seon.error/severity e2)))
      (is (= :suppressed (:seon.error/status e2)))
      (is (= 1700000000000 (:seon.error/at e2)))
      (is (= eval-id (:seon.error/eval e2)))

      (is (= {:seon.error/id eid :seon.error/status :resolved}
             (error/resolve-error-tx eid)))

      (is (thrown? Exception (error/error-tx {:kind :x})))
      (is (thrown? Exception (error/error-tx {:message "no kind"})))
      (is (thrown? Exception (error/error-tx {:kind :x :message "bad" :severity :bogus})))
      (is (thrown? Exception (error/error-tx "not-a-map"))))))

(deftest open-errors-lifecycle-and-filtering-test
  (testing "Live Datahike transaction, indexed :avet querying, filtering, newest-first sort, and resolution"
    (is (= [] (error/open-errors nil)))
    (with-test-store [{:keys [conn]}]
      (let [older (error/error-tx {:kind :model/timeout :source :model :agent :ag/a :branch :branch/a :at 1000 :message "First timeout"})
            newer (error/error-tx {:kind :model/rate-limit :source :model :agent :ag/a :branch :branch/a :at 2000 :message "Rate limit" :data {:retry-after-ms 4000}})
            other (error/error-tx {:kind :tool/crash :source :eval :agent :ag/b :branch :branch/b :at 3000 :message "Tool exception"})
            closed (error/error-tx {:kind :model/api-error :source :model :agent :ag/a :status :resolved :at 4000 :message "Already resolved"})]
        (d/transact conn {:tx-data [older newer other closed]})
        (let [db @conn]
          (is (= [(:seon.error/id other) (:seon.error/id newer) (:seon.error/id older)]
                 (mapv :seon.error/id (error/open-errors db))))
          (is (= [(:seon.error/id newer) (:seon.error/id older)]
                 (mapv :seon.error/id (error/open-errors db {:agent :ag/a}))))
          (is (= [(:seon.error/id newer)]
                 (mapv :seon.error/id (error/open-errors db {:agent :ag/a :source :model :kind :model/rate-limit :branch :branch/a}))))
          (is (= [(:seon.error/id other)]
                 (mapv :seon.error/id (error/open-errors db {:source :eval})))))
        (d/transact conn {:tx-data [(error/resolve-error-tx (:seon.error/id newer))]})
        (is (= [(:seon.error/id older)]
               (mapv :seon.error/id (error/open-errors @conn {:agent :ag/a}))))))))

(deftest classify-model-error-and-seed42-generative-test
  (testing "HTTP 429, quota, auth, timeout, network, and 5xx/4xx classification"
    (let [c429 (error/classify-model-error {:status 429 :headers [["Retry-After" "7"]]
                                            :body (json/write-str {:error {:message "Too many requests"}})})
          c429-frac (error/classify-model-error {:status 429 :retry-after "2.5" :body "proxy_budget_exceeded"})
          c401 (error/classify-model-error {:status 401 :body (json/write-str {:error {:message "Invalid API key"}})})
          cmissing (error/classify-model-error {:error :seon.model/missing-api-key :message "Missing DEEPSEEK_API_KEY"})
          ctimeout (error/classify-model-error {:exception (java.net.SocketTimeoutException. "Read timed out")})
          cnet (error/classify-model-error {:exception (java.net.ConnectException. "Connection refused")})
          c503 (error/classify-model-error {:status 503 :body "Service unavailable"})
          c400 (error/classify-model-error {:status 400 :body "Bad request payload"})]
      (is (= :model/rate-limit (:kind c429)))
      (is (true? (:retryable? c429)))
      (is (= 7000 (:retry-after-ms c429)))
      (is (str/includes? (:message c429) "Too many requests"))

      (is (= :model/rate-limit (:kind c429-frac)))
      (is (= 2500 (:retry-after-ms c429-frac)))

      (is (= :model/auth (:kind c401)))
      (is (false? (:retryable? c401)))
      (is (= :model/auth (:kind cmissing)))
      (is (false? (:retryable? cmissing)))

      (is (= :model/timeout (:kind ctimeout)))
      (is (true? (:retryable? ctimeout)))

      (is (= :model/api-error (:kind cnet)))
      (is (true? (:retryable? cnet)))
      (is (= :model/api-error (:kind c503)))
      (is (true? (:retryable? c503)))
      (is (= :model/api-error (:kind c400)))
      (is (false? (:retryable? c400)))))

  (testing "seed = 42 Malli generative checks on pure seon.error functions"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        (doseq [sym ['seon.error/error-tx
                     'seon.error/resolve-error-tx
                     'seon.error/classify-model-error
                     'seon.error/error-rules
                     'seon.error/model-error-response
                     'seon.error/model-error-outputs]]
          (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (true? (:ok? res)) (pr-str res))
            (is (= 100 (:ran res)) (pr-str res))))))))

(deftest render-human-model-and-schema-dispatch-test
  (testing "seon.error/render produces crisp Hiccup for :human, :model, and :agent modes"
    (let [ent (error/error-tx {:kind :model/rate-limit
                               :severity :error
                               :source :model
                               :agent :ag/alpha
                               :branch :branch/alpha
                               :message "Upstream 429 rate limited"
                               :data {:status 429 :retry-after-ms 5000}})
          h-human (error/render ent {:mode :human :tags [:tag/open-error] :why ["Rate limited"]})
          h-model (error/render ent {:mode :model})
          h-agent (error/render ent {:mode :agent})
          h-swapped (error/render {:mode :model} ent)
          txt-human (render/hiccup->text h-human)
          html-human (render/hiccup->html h-human)
          txt-model (render/hiccup->text h-model)]
      (is (vector? h-human))
      (is (= :div (first h-human)))
      (is (str/includes? html-human "tile-card"))
      (is (str/includes? html-human ":model/rate-limit"))
      (is (str/includes? txt-human "Upstream 429 rate limited"))
      (is (str/includes? txt-human "Rate limited"))
      (is (= :div (first h-model)))
      (is (str/includes? (render/hiccup->html h-model) "compact-tile"))
      (is (str/includes? txt-model "5000"))
      (is (= h-model h-agent))
      (is (= h-model h-swapped))))

  (testing "Schema registry dispatch resolves :seon.render/hiccup seon.error/render"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.schema/key :seon.error/entity
                                   :seon.schema/form [:map {:seon.db/attributes true :seon.render/hiccup 'seon.error/render}
                                                      [:seon.error/id :seon.error/id] [:seon.error/kind :seon.error/kind]
                                                      [:seon.error/severity :seon.error/severity] [:seon.error/source :seon.error/source]
                                                      [:seon.error/message :seon.error/message] [:seon.error/status :seon.error/status]]}]})
      (let [db @conn
            ent (error/error-tx {:kind :model/timeout :message "Timed out"})
            rendered (render/render-value db ent {:mode :model})]
        (is (= 'seon.error/render (render/schema-render-sym db :seon.error/entity)))
        (is (vector? rendered))
        (is (str/includes? (render/hiccup->text rendered) "Timed out"))))))

(deftest error-rules-step-rules-lifecycle-test
  (testing "Declarative error-rules surface open agent errors & 429 backpressure and retract cleanly on resolve"
    (with-test-store [{:keys [conn]}]
      (let [aid :ag/rule-err
            err-ent (error/error-tx {:kind :model/rate-limit
                                     :source :model
                                     :agent aid
                                     :message "Rate limit active"
                                     :data {:status 429 :retry-after-ms 3500}})
            _ (d/transact conn {:tx-data [err-ent]})
            db1 @conn
            eid (:e (first (d/datoms db1 :avet :seon.error/id (:seon.error/id err-ent))))
            wm0 (rules/add-facts (rules/empty-wm) [[aid :ctx/viewer aid]] {:rule :seed :stratum 0})
            rs (error/error-rules)
            s1 (rules/step-rules nil wm0 {:main db1 :branch db1} rs nil)
            wm1 (:wm s1)]
        (is (pos? (long eid)))
        (is (true? (rules/wm-scalar wm1 aid :error/has-open-error?)))
        (is (= eid (rules/wm-scalar wm1 aid :rel/open-error)))
        (is (true? (rules/wm-scalar wm1 aid :error/backpressure?)))
        (is (= :model/rate-limit (rules/wm-scalar wm1 aid :error/backpressure-kind)))
        (is (= 3500 (rules/wm-scalar wm1 aid :error/retry-after-ms)))
        (is (= 'seon.error/render (rules/wm-scalar wm1 eid :view/fn)))
        (is (= :ephemeral-tail (rules/wm-scalar wm1 eid :view/zone)))

        (let [txr (d/transact conn {:tx-data [(error/resolve-error-tx (:seon.error/id err-ent))]})
              db2 (:db-after txr)
              s2 (rules/step-rules s1 wm0 {:main db2 :branch db2} rs (:tx-data txr))
              wm2 (:wm s2)]
          (is (nil? (rules/wm-scalar wm2 aid :error/has-open-error?)))
          (is (nil? (rules/wm-scalar wm2 aid :error/backpressure?))))))))

(deftest model-proc-http-429-surfacing-and-agents-test
  (testing "model-proc surfaces HTTP 429 via real :seon.error/* :tx, :stream, ::flow/error, and :response instead of silent idle"
    (let [srv (adapter/run-server
               (fn [req]
                 (if (str/ends-with? (:uri req) "/models")
                   {:status 200 :headers {"Content-Type" "application/json"} :body (json/write-str {:data []})}
                   {:status 429
                    :headers {"Content-Type" "application/json" "Retry-After" "3"}
                    :body (json/write-str {:error {:message "rate limit exceeded"}})}))
               {:port 0 :host "127.0.0.1"})
          base-url (str "http://127.0.0.1:" (:port srv))]
      (try
        (let [desc (model/model-proc)
              _ (is (contains? (:outs desc) :tx))
              st0 (model/model-proc {:provider {:type :openai-compat
                                                :base-url base-url
                                                :api-key "sk-test"
                                                :model "test-model"}})
              [st1 req-out] (model/model-proc st0 :request {:req-id "r-429"
                                                            :agent-id :ag/model-429
                                                            :branch :branch/model-429
                                                            :user-prompt "hi"})
              _ (is (= :thinking (get-in req-out [:stream 0 :status])))
              done-msg (a/alt!! (:done-ch st1) ([v] v) (a/timeout 4000) ::timeout)
              _ (is (not= ::timeout done-msg))
              [st2 outs] (model/model-proc st1 :done done-msg)
              resp (get-in outs [:response 0 :response])
              tx-op (get-in outs [:tx 0])
              err-ent (first (:tx-data tx-op))
              stream-ev (get-in outs [:stream 0])
              flow-err (first (:clojure.core.async.flow/error outs))
              flow-rep (first (:clojure.core.async.flow/report outs))]
          (is (= 1 (:errors st2)))
          (is (= :model/rate-limit (:error resp)))
          (is (= :model/rate-limit (:seon.error/kind resp)))
          (is (= 3000 (:retry-after-ms resp)))
          (is (empty? (:tool-calls resp)))
          (is (not (str/includes? (pr-str resp) "wait-for-mail!")))

          (is (= :transact (:op tx-op)))
          (is (= :model/rate-limit (:seon.error/kind err-ent)))
          (is (= :ag/model-429 (:seon.error/agent err-ent)))
          (is (= :branch/model-429 (:seon.error/branch err-ent)))

          (is (= :error (:status stream-ev)))
          (is (= :model/rate-limit (:seon.error/kind stream-ev)))
          (is (= 3000 (:retry-after-ms stream-ev)))
          (is (= :model (:proc flow-err)))
          (is (str/includes? flow-rep "model/rate-limit"))

          (with-test-store [{:keys [conn]}]
            (d/transact conn {:tx-data (:tx-data tx-op)})
            (let [found (error/open-errors @conn {:agent :ag/model-429 :kind :model/rate-limit})]
              (is (= 1 (count found)))
              (is (= (:seon.error/id err-ent) (:seon.error/id (first found))))))

          (testing "agents/agents-proc forwards model error :tx and :exits without dropping error-entity"
            (let [fork-out (a/chan 4)
                  ast0 (assoc (agents/agents-proc {:fork-out fork-out})
                              :model-reqs {"r-429" {:pid 4242 :prefix-hash "ph"}})
                  [ast1 aouts] (agents/agents-proc ast0 :response (first (:response outs)))
                  child-msg (a/poll! fork-out)]
              (is (empty? (:model-reqs ast1)))
              (is (= :model-response (get-in child-msg [:msg :op])))
              (is (= :model/rate-limit (get-in child-msg [:msg :response :seon.error/kind])))
              (is (= :transact (get-in aouts [:tx 0 :op])))
              (is (= (:seon.error/id err-ent) (get-in aouts [:tx 0 :tx-data 0 :seon.error/id])))
              (is (= :model-error (get-in aouts [:exits 0 :ev]))))))
        (finally
          (try (adapter/stop-server srv {:drain-timeout-ms 500}) (catch Throwable _)))))))

(deftest ^{:cost/ticks 25000000} unified-error-health-and-render-value-test
  (testing "Open :seon.error/* entities surface in seon.health/open-faults and render via seon.error/render without :seon/schema metadata"
    (with-test-store [{:keys [conn]}]
      (let [ent (error/error-tx {:kind :model/rate-limit :severity :error :source :model :agent :ns/seon.model
                                 :branch :branch/demo :message "429 quota" :data {:retry-after-ms 1500 :ns 'seon.model}})]
        (d/transact conn {:tx-data [ent]})
        (let [fs (health/open-faults @conn)
              h (render/render-value @conn (with-meta ent nil) {:mode :human})
              txt (render/hiccup->text h)]
          (is (= 1 (count fs)))
          (is (= :model/rate-limit (:class (first fs))))
          (is (= :ns/seon.model (:agent (first fs))))
          (is (str/includes? txt ":model/rate-limit"))
          (is (str/includes? txt "429 quota")))
        (d/transact conn {:tx-data [(error/resolve-error-tx (:seon.error/id ent))]})
        (is (empty? (health/open-faults @conn)))))))