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
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.run :as run]
    [seon.ai :as ai]
    [seon.db :as db]
    [seon.db.branch :as branch]
    [seon.db.restore :as restore]
    [seon.derive :as derive]
    [seon.error :as error]
    [seon.execution :as execution]
    [seon.execution.host :as execution-host]
    [seon.reactive :as reactive]
    [seon.render.value :as render.value]
    [seon.render.system :as system]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]
    [seon.web.debug :as debug]
    [seon.web.router :as router]
    [seon.web.serve :as serve]
    [seon.web.value :as web-value]))

(def ^:private database
  {:db-name "test"
   :t 30
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(def ^:private value-limits
  {:seon.config.render/value-max-path-segments 32
   :seon.config.render/value-max-path-bytes 4096
   :seon.config.render/value-max-realized-items 1024
   :seon.config.render/value-max-depth 6
   :seon.config.render/value-max-string 80
   :seon.config.render/value-shape-sample 8
   :seon.render.value/page-size 8})

(defn- value-ring-request [agent-id query]
  {:request-method :get
   :uri (str "/agent/" agent-id "/value")
   :query-string query
   :path-params {:id agent-id}
   :seon.http/request
   (js/Request. (str "http://127.0.0.1/agent/" agent-id "/value?" query))})

(deftest value-path-codec-is-canonical-and-closed
  (let [frame (deref #'serve/raw-value-query)
        parse (deref #'serve/configured-value-request)
        accepted ["[]"
                  "[nil true false 0 -2 1.5 \"λ\" :a :a/b x x/y]"]
        refused ["[+1]" "[01]" "[1.0]" "[1e0]" "[-0]" "[-0.0]"
                 "[##NaN]" "[##Inf]" "[[]]" "[{}]" "[#{}]" "[\\a]"
                 " [1]" "[1] " "[1]," "[1] ; comment" "[1] :tail"
                 "^:x [1]" "[#uuid \"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"]"
                 "[#unknown/value 1]"]]
    (doseq [path accepted]
      (let [query (str "eval=e-1&path=" (js/encodeURIComponent path))
            request (parse (frame (value-ring-request "a" query)) value-limits)
            segments (:seon.render.value/path request)
            wire (execution/decode-message
                   (execution/encode-message {:seon.web.serve/path segments}))]
        (is (not (:seon.error/message request)) path)
        (is (= path (pr-str segments)) path)
        (is (= segments (:seon.web.serve/path wire)) path)
        (doseq [segment segments]
          (is (= :found
                 (get {segment :found}
                      (first (filter #(= segment %) (:seon.web.serve/path wire)))))
              (str "Transit retains lookup equality for " (pr-str segment))))))
    (doseq [path refused]
      (let [query (str "eval=e-1&path=" (js/encodeURIComponent path))
            request (parse (frame (value-ring-request "a" query)) value-limits)]
        (is (= :user-input (:seon.error/kind request)) path)))
    (doseq [query ["eval=e-1&path=[]&path=[]"
                   "eval=e-1&path=[]&%70ath=[]"
                   "eval=e-1&entity=1"
                   "path=[]"
                   "eval=e-1&unknown=x"
                   "eval=e-1&path=%ZZ"]]
      (is (= :user-input
             (:seon.error/kind
               (parse (frame (value-ring-request "a" query)) value-limits)))
          query))))

(deftest strict-value-path-reader-ignores-global-tag-parsers
  (let [called (atom 0)
        frame (deref #'serve/raw-value-query)
        parse (deref #'serve/configured-value-request)]
    (reader/register-tag-parser! 'hostile/value
                                 (fn [_] (swap! called inc) :invoked))
    (try
      (let [path "[#hostile/value 1]"
            request (parse
                     (frame (value-ring-request
                              "a" (str "eval=e&path="
                                       (js/encodeURIComponent path))))
                     value-limits)]
        (is (= :user-input (:seon.error/kind request)))
        (is (zero? @called)))
      (finally
        (reader/deregister-tag-parser! 'hostile/value)))))

(deftest absolute-value-framing-refuses-before-database-acquisition
  (async done
    (let [original-db db/db
          acquisitions (atom 0)]
      (set! db/db
            (fn
              ([] (swap! acquisitions inc) (js/Promise.resolve database))
              ([_] (swap! acquisitions inc) (js/Promise.resolve database))))
      (let [queries [(str "eval=e&path=" (apply str (repeat 33000 "x")))
                     "eval=e&entity=1"
                     "path=[]"
                     "eval="
                     "eval=e&eval=e"
                     "eval=e&%70ath=[]"
                     "eval=e&path=%ZZ"]]
        (-> (js/Promise.all
              (clj->js
                (mapv #(serve/value! (value-ring-request "a" %)) queries)))
          (.then (fn [responses]
                   (is (every? #(= 400 (.-status %))
                               (array-seq responses)))
                   (is (zero? @acquisitions))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (set! db/db original-db) (done))))))))

(deftest configured-value-refusal-does-no-domain-or-sampler-work
  (async done
    (let [original-db db/db
          original-entity db/entity
          original-query db/query
          original-sample execution-host/sample-value!
          acquisitions (atom 0)
          policy-acquisitions (atom 0)
          domain-work (atom 0)
          samples (atom 0)]
      (set! db/db (fn ([] (swap! acquisitions inc) (js/Promise.resolve database))
                    ([_] (swap! acquisitions inc) (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([ref] (js/Promise.resolve nil))
              ([database-value ref]
               (if (= [:seon.config/id "cluster"] ref)
                 (do (swap! policy-acquisitions inc)
                     (js/Promise.resolve
                       {:seon.config/id "cluster"
                        :seon.config.render/value-max-path-segments 1}))
                 (do (swap! domain-work inc) (js/Promise.resolve nil))))))
      (set! db/query
            (fn [_] (swap! domain-work inc) (js/Promise.resolve nil)))
      (set! execution-host/sample-value!
            (fn [_ _ _] (swap! samples inc) (js/Promise.resolve nil)))
      (-> (serve/value!
            (value-ring-request "a" "eval=e-1&path=%5B:a%20:b%5D"))
          (.then
            (fn [response]
              (is (= 400 (.-status response)))
              (is (= 1 @acquisitions))
              (is (= 1 @policy-acquisitions))
              (is (zero? @domain-work))
              (is (zero? @samples))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! db/db original-db)
              (set! db/entity original-entity)
              (set! db/query original-query)
              (set! execution-host/sample-value! original-sample)
              (done)))))))

(deftest missing-and-cross-agent-evals-are-uniform-and-send-zero
  (async done
    (let [original-db db/db
          original-entity db/entity
          original-query db/query
          original-sample execution-host/sample-value!
          samples (atom 0)]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([ref] (js/Promise.resolve nil))
              ([_ ref]
               (if (= [:seon.config/id "cluster"] ref)
                 (js/Promise.resolve {:seon.config/id "cluster"})
                 (js/Promise.resolve nil)))))
      (set! db/query (fn [_] (js/Promise.resolve nil)))
      (set! execution-host/sample-value!
            (fn [_ _ _] (swap! samples inc) (js/Promise.resolve nil)))
      (-> (js/Promise.all
            #js [(serve/value! (value-ring-request "a" "eval=missing"))
                 (serve/value! (value-ring-request "b" "eval=owned-by-a"))])
          (.then
            (fn [responses]
              (let [[missing cross] (array-seq responses)]
                (is (= [404 404] [(.-status missing) (.-status cross)]))
                (is (= "no-store" (.get (.-headers missing) "cache-control")))
              (is (nil? (.get (.-headers missing) "access-control-allow-origin")))
                (-> (js/Promise.all #js [(.text missing) (.text cross)])
                    (.then (fn [bodies]
                             (is (= (aget bodies 0) (aget bodies 1)))
                             (is (zero? @samples))))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! db/db original-db)
              (set! db/entity original-entity)
              (set! db/query original-query)
              (set! execution-host/sample-value! original-sample)
              (done)))))))

(deftest eval-value-statuses-and-admitted-sampler-request-are-exact
  (async done
    (let [original-db db/db
          original-entity db/entity
          original-query db/query
          original-sample execution-host/sample-value!
          authorization-requests (atom [])
          sampler-calls (atom [])
          projection {:seon.render.value/path []
                      :seon.render.value/offset 0
                      :seon.render.value/page-size 8
                      :seon.render.value/summary "vector 8 items"
                      :seon.render.value/truncated? true
                      :seon.render.value/more? true
                      :seon.render.value/tree
                      {:seon.render.value/kind :vector
                       :seon.render.value/shown (vec (range 8))
                       :seon.render.value/elided :more}
                      :seon.render.value/schemas []}
          result-for
          (fn [eval-id request]
            (cond
              (= 16 (:seon.render.value/offset request))
              {:seon.render.value/ok? true
               :seon.render.value/availability :unavailable
               :seon.render.value/projection projection
               :seon.render.value/recompute? true}

              (= eval-id "available")
              {:seon.render.value/ok? true
               :seon.render.value/availability :available
               :seon.render.value/projection projection}

              (= eval-id "policy")
              {:seon.render.value/ok? false
               :seon/error {:seon.error/message
                            render.value/sampling-policy-refusal-message
                            :seon.error/kind :agent}}

              :else
              {:seon.render.value/ok? false
               :seon/error {:seon.error/message "transport failed"
                            :seon.error/kind :core-bug}}))]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([ref] (js/Promise.resolve nil))
              ([_ ref]
               (js/Promise.resolve
                 (when (= [:seon.config/id "cluster"] ref)
                   {:seon.config/id "cluster"})))))
      (set! db/query
            (fn [request]
              (swap! authorization-requests conj request)
              (js/Promise.resolve 101)))
      (set! execution-host/sample-value!
            (fn [agent-id eval-id request]
              (swap! sampler-calls conj [agent-id eval-id request])
              (js/Promise.resolve (result-for eval-id request))))
      (let [requests
            [(assoc (value-ring-request "a" "eval=available")
                    :seon.http/request
                    (js/Request. "http://127.0.0.1/agent/a/value?eval=available"
                                  #js {:headers #js {"Origin" "https://evil.test"}}))
             (value-ring-request "a" "eval=available&offset=16")
             (value-ring-request "a" "eval=available")
             (value-ring-request "a" "eval=available&offset=8")
             (value-ring-request "a" "eval=core")
             (value-ring-request "a" "eval=policy")]]
        (-> (js/Promise.all (clj->js (mapv serve/value! requests)))
            (.then
              (fn [responses]
                (let [[hostile-available unavailable absent-available page-two
                       core policy]
                      (array-seq responses)]
                  (is (= [200 200 200 200 503 400]
                         (mapv #(.-status %)
                               [hostile-available unavailable absent-available
                                page-two core policy])))
                  (doseq [response [hostile-available unavailable
                                    absent-available page-two core policy]]
                    (is (= "no-store"
                           (.get (.-headers response) "cache-control")))
                    (is (nil? (.get (.-headers response)
                                    "access-control-allow-origin"))))
                  (is (= 6 (count @sampler-calls)))
                  (is (= 6 (count @authorization-requests)))
                  (is (= [["available" "a"] ["available" "a"]
                          ["available" "a"] ["available" "a"]
                          ["core" "a"] ["policy" "a"]]
                         (mapv ::db/args @authorization-requests)))
                  (is (every? #(= "a" (first %)) @sampler-calls))
                  (is (every? #(= []
                                  (get-in % [2 :seon.render.value/path]))
                              @sampler-calls))
                  (is (= "application/edn; charset=utf-8"
                         (.get (.-headers core) "content-type")))
                  (is (= "application/edn; charset=utf-8"
                         (.get (.-headers policy) "content-type")))
                  (-> (js/Promise.all
                        #js [(.text hostile-available)
                             (.text unavailable)
                             (.text absent-available)
                             (.text page-two)])
                      (.then (fn [bodies]
                               (let [root-id
                                     (fn [body]
                                       (second
                                         (re-find #"id=\"(seon-value-[^\"]+)\""
                                                  body)))]
                                 (is (= (aget bodies 0) (aget bodies 2)))
                                 (is (every? string?
                                             (map root-id (array-seq bodies))))
                                 (is (apply =
                                            (map root-id
                                                 (array-seq bodies)))))))))))
            (.catch (fn [error] (is false (str error))))
            (.finally
              (fn []
                (set! db/db original-db)
                (set! db/entity original-entity)
                (set! db/query original-query)
                (set! execution-host/sample-value! original-sample)
                (done))))))))

(deftest entity-absence-is-uniform-and-never-sends-to-execution
  (async done
    (let [original-db db/db
          original-entity db/entity
          original-sample execution-host/sample-value!
          samples (atom 0)]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([ref] (js/Promise.resolve nil))
              ([_ ref]
               (js/Promise.resolve
                 (when (= [:seon.config/id "cluster"] ref)
                   {:seon.config/id "cluster"})))))
      (set! execution-host/sample-value!
            (fn [_ _ _] (swap! samples inc) (js/Promise.resolve nil)))
      (-> (js/Promise.all
            #js [(serve/value! (value-ring-request "a" "entity=42"))
                 (serve/value! (value-ring-request "root" "entity=42"))])
          (.then
            (fn [responses]
              (let [[non-root missing] (array-seq responses)]
                (is (= [404 404] [(.-status non-root) (.-status missing)]))
                (is (zero? @samples))
                (-> (js/Promise.all #js [(.text non-root) (.text missing)])
                    (.then (fn [bodies]
                             (is (= (aget bodies 0) (aget bodies 1)))))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! db/db original-db)
              (set! db/entity original-entity)
              (set! execution-host/sample-value! original-sample)
              (done)))))))

(deftest domain-read-failures-are-bounded-503-not-absence
  (async done
    (let [original-db db/db
          original-entity db/entity
          original-query db/query
          original-sample execution-host/sample-value!
          samples (atom 0)
          failure {:seon.error/message "large internal database failure"
                   :seon.error/kind :core-bug}]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([ref] (js/Promise.resolve nil))
              ([_ ref]
               (js/Promise.resolve
                 (if (= [:seon.config/id "cluster"] ref)
                   {:seon.config/id "cluster"}
                   failure)))))
      (set! db/query (fn [_] (js/Promise.resolve failure)))
      (set! execution-host/sample-value!
            (fn [_ _ _] (swap! samples inc) (js/Promise.resolve nil)))
      (-> (js/Promise.all
            #js [(serve/value! (value-ring-request "a" "eval=e"))
                 (serve/value! (value-ring-request "root" "entity=42"))])
          (.then
            (fn [responses]
              (let [[auth-failure entity-failure] (array-seq responses)]
                (is (= [503 503]
                       [(.-status auth-failure) (.-status entity-failure)]))
                (is (zero? @samples))
                (-> (js/Promise.all
                      #js [(.text auth-failure) (.text entity-failure)])
                    (.then
                      (fn [bodies]
                        (doseq [body (array-seq bodies)]
                          (is (< (count body) 256))
                          (is (= :core-bug
                                 (:seon.error/kind
                                   (reader/read-string body)))))))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! db/db original-db)
              (set! db/entity original-entity)
              (set! db/query original-query)
              (set! execution-host/sample-value! original-sample)
              (done)))))))

(deftest root-entity-value-uses-one-database-value-and-zero-host-sends
  (async done
    (let [original-db db/db
          original-entity db/entity
          original-query db/query
          original-sample execution-host/sample-value!
          original-current schema/current-projection
          seen-databases (atom [])
          seen-queries (atom [])
          samples (atom 0)]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([ref] (js/Promise.resolve nil))
              ([database-value ref]
               (swap! seen-databases conj database-value)
               (js/Promise.resolve
                 (if (= [:seon.config/id "cluster"] ref)
                   {:seon.config/id "cluster"}
                   {:db/id ref :my/value 42})))))
      (set! db/query
            (fn [{database-value ::db/db query ::db/query}]
              (swap! seen-databases conj database-value)
              (swap! seen-queries conj query)
              (js/Promise.resolve #{})))
      (set! execution-host/sample-value!
            (fn [_ _ _] (swap! samples inc) (js/Promise.resolve nil)))
      (set! schema/current-projection
            (fn [] (throw (js/Error. "ambient projection forbidden"))))
      (-> (serve/value! (value-ring-request "root" "entity=42"))
          (.then
            (fn [response]
              (is (= 200 (.-status response)))
              (is (= "text/html; charset=utf-8"
                     (.get (.-headers response) "content-type")))
              (is (= "no-store" (.get (.-headers response) "cache-control")))
              (is (nil? (.get (.-headers response) "access-control-allow-origin")))
              (is (zero? @samples))
              (is (every? #(identical? database %) @seen-databases))
              (is (= 2 (count @seen-queries)))
              (is (some #(some #{:seon.schema/key} (flatten %)) @seen-queries))
              (is (some #(some #{:seon.fn/sym} (flatten %)) @seen-queries))
              (-> (.text response)
                  (.then
                    (fn [body]
                      (is (str/starts-with? body "<div"))
                      (is (re-find #"id=\"seon-value-[^\"]+\"" body)))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! db/db original-db)
              (set! db/entity original-entity)
              (set! db/query original-query)
              (set! execution-host/sample-value! original-sample)
              (set! schema/current-projection original-current)
              (done)))))))

(deftest explicit-agent-task-persists-input-before-hosting-the-durable-agent
  (async done
    (let [run-task (deref #'serve/run-agent-task!)
          original-db db/db
          original-query db/query
          original-resume lifecycle/resume!
          original-message agent/message!
          calls (atom [])]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/query (fn [_] (js/Promise.resolve 42)))
      (set! lifecycle/resume!
            (fn [request]
              (swap! calls conj [:resume request])
              (js/Promise.resolve
               {:seon.agent/id "root"
                ::lifecycle/resumed? false
                ::lifecycle/error "host refused"})))
      (set! agent/message!
            (fn [request]
              (swap! calls conj [:message request])
              (js/Promise.resolve {})))
      (-> (run-task "root" "work" 1000)
          (.then
           (fn [result]
             (is (= {:error "host refused"} result))
             (is (= [[:message
                      {:seon.agent.message/from agent/user-ref
                       :seon.agent.message/to [[:seon.agent/id "root"]]
                       :seon.agent.message/content "work"}]
                     [:resume {:seon.agent/id "root"}]]
                    @calls))))
          (.catch
           (fn [error]
             (is false (str "task hosting rejected: " error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/query original-query)
             (set! lifecycle/resume! original-resume)
             (set! agent/message! original-message)
             (done)))))))

(deftest terminal-fault-door-persists-sync-and-async-core-faults
  (async done
    (let [door (deref #'serve/through-terminal-fault-door)
          batches (atom [])]
      (error/set-db-hooks!
       {:seon.error/transact!
        (fn [tx-data]
          (swap! batches conj tx-data)
          (js/Promise.resolve {:seon.db/ok? true}))
        :seon.error/branch-head (constantly nil)})
      (->
       (error/expecting-core-fault!
        #(js/Promise.all
          #js [(door "async handler failed"
                     (fn []
                       (js/Promise.reject
                        (js/Error. "injected async core fault"))))
               (door "sync handler failed"
                     (fn []
                       (throw (js/Error. "injected sync core fault"))))]))
          (.then
           (fn [responses]
             (let [faults (filter #(= :core (:seon.error/fault %))
                                  (mapcat identity @batches))]
               (is (= 2 (count faults))
                   "the one door persists one datom per caught failure")
               (is (= #{"injected async core fault"
                        "injected sync core fault"}
                      (set (map :seon.error/message faults))))
               (is (every? #(= 500 (.-status %)) (array-seq responses)))
               (js/Promise.all
                (into-array (map #(.json %) (array-seq responses)))))))
          (.then
           (fn [bodies]
             (is (= #{"injected async core fault"
                      "injected sync core fault"}
                    (into #{}
                          (map #(aget % "seon.error/message"))
                          (array-seq bodies))))
             (is (= #{"core-bug"}
                    (into #{}
                          (map #(aget % "seon.error/kind"))
                          (array-seq bodies))))))
          (.catch (fn [exception]
                    (is false (str "terminal fault door rejected: "
                                   exception))))
          (.finally
           (fn []
             (error/set-db-hooks! {})
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
          original-entity db/entity
          original-query db/query
          original-fleet system/acquire-fleet-summary
          original-sample execution-host/sample-value!
          request (atom nil)
          entity-reads (atom [])
          query-reads (atom [])
          child-sends (atom 0)]
      (set! db/index-page
            (fn index-page-stub
              ([value]
               (reset! request value)
               (js/Promise.resolve
                {:datahike.index-page/datoms
                 [[2 :demo/name "two" 2 true]
                  [1 :demo/name "one" 2 true]
                  [2 :demo/enabled true 2 true]]}))
              ([_database _options]
               (js/Promise.reject
                (js/Error. "database view must use the map request")))))
      (set! db/entity
            (fn entity-stub
              ([entity-request]
               (entity-stub (::db/db entity-request) (::db/ref entity-request)))
              ([value entity-id]
                (swap! entity-reads conj [value entity-id])
                (js/Promise.resolve
                 (case entity-id
                   [:seon.config/id "cluster"]
                   (assoc value-limits :seon.config/id "cluster")
                   2 {:db/id 2 :demo/name "two"
                      :demo/items (nth (iterate vector (vec (range 20))) 8)}
                   1 {:db/id 1 :demo/name "one"
                      :demo/items (nth (iterate vector (vec (range 20))) 8)})))))
      (set! db/query
            (fn [query-request]
              (swap! query-reads conj query-request)
              (js/Promise.resolve [])))
      (set! execution-host/sample-value!
            (fn [& _]
              (swap! child-sends inc)
              (js/Promise.reject
               (js/Error. "entity values must remain parent-owned"))))
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
             (is (= [[database [:seon.config/id "cluster"]]
                     [database 2]
                     [database 1]]
                    @entity-reads)
                 "the bounded page selects two stable distinct entity reads")
             (is (= 2 (count @query-reads)))
             (is (every? #(identical? database (::db/db %)) @query-reads)
                 "schema projection uses the feed-supplied immutable db")
             (is (zero? @child-sends))
             (let [markup (pr-str element)
                   root-ids
                   (mapv #(get-in % [3 1 :id])
                         (drop 2 (last element)))]
               (is (< (str/index-of markup "entity 2")
                      (str/index-of markup "entity 1"))
                   "first-seen entity order is stable")
               (is (= 2 (count root-ids)))
               (is (= 2 (count (set root-ids)))
                   "each honest entity selector owns a distinct root")
               (is (str/includes? markup "entity=2"))
               (is (str/includes? markup "entity=1"))
               (is (not (str/includes? markup "[:pre")))
               (is (str/includes? markup ":data-agent-count 2"))
               (is (str/includes? markup ":data-running-agents 1"))
               (reset! entity-reads [])
               (reset! query-reads [])
               (-> ((deref #'debug/render-data!) database nil)
                   (.then
                    (fn [rerendered]
                      (is (= root-ids
                             (mapv #(get-in % [3 1 :id])
                                   (drop 2 (last rerendered))))
                          "logical request roots survive feed rerenders")
                      (is (zero? @child-sends))))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/index-page original)
             (set! db/entity original-entity)
             (set! db/query original-query)
             (set! execution-host/sample-value! original-sample)
             (set! system/acquire-fleet-summary original-fleet)
             (done)))))))

(deftest database-and-debug-shells-leave-the-header-to-the-feed-morph
  (let [markup ((deref #'debug/page-html) "data" "/data/feed" "loading")]
    (is (= 1 (count (re-seq #"id=\"app-view\"" markup))))
    (is (not (str/includes? markup "id=\"system-header\"")))))

(deftest value-render-input-acquisition-refuses-database-errors
  (async done
    (let [original-entity db/entity
          original-query db/query]
      (set! db/entity
            (fn entity-failure
              ([_request]
               (js/Promise.resolve
                {:seon.error/message "configuration read failed"}))
              ([_database _entity-id]
               (js/Promise.resolve
                {:seon.error/message "configuration read failed"}))))
      (-> (web-value/policy! database)
          (.then (fn [_] (is false "policy failure must reject")))
          (.catch
           (fn [error]
             (is (= "configuration unavailable" (.-message error)))
             (set! db/query
                   (fn [_]
                     (js/Promise.resolve
                      {:seon.error/message "program read failed"})))
             (web-value/program-projection! database)))
          (.then (fn [_] (is false "projection failure must reject")))
          (.catch
           (fn [error]
             (is (= "program projection unavailable" (.-message error)))))
          (.finally
           (fn []
             (set! db/entity original-entity)
             (set! db/query original-query)
             (done)))))))

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

(deftest agent-run-settlement-is-commit-driven-and-released
  (async done
    (let [original-capture db/with-read-evidence
          original-query db/query
          original-state derive/derive-state
          original-observe reactive/observe!
          original-unobserve reactive/unobserve!
          injected-at (js/Date.now)
          committed-database (assoc database :t 31)
          observed (atom nil)
          notifications (atom [])
          registrations (atom #{})
          released (atom nil)]
      (set! db/with-read-evidence
            (fn [f]
              (-> (js/Promise.resolve nil)
                  (.then (fn [_] (f)))
                  (.then (fn [value]
                           {::db/value value ::db/read-evidence []})))))
      (set! db/query
            (fn [{database-value ::db/db query ::db/query}]
              (let [settled? (= 31 (:t database-value))]
                (js/Promise.resolve
                 (if (str/includes? (pr-str query) "?status")
                   [[(if settled? :done :running)]]
                   [[(js/Date. (if settled? injected-at 0))]])))))
      (set! derive/derive-state
            (fn [database-value _]
              (js/Promise.resolve
               (if (= 31 (:t database-value)) :idle :running))))
      (set! reactive/observe!
            (fn [request]
              (reset! observed request)
              (swap! registrations conj
                     [(::reactive/key request)
                      (::reactive/consumer-key request)])
              (-> ((::reactive/compute request) database)
                  (.then (fn [value]
                           (swap! notifications conj (::db/value value))
                           ((::reactive/notify request) (::db/value value))))
                  (.then
                   (fn [_]
                     ((::reactive/compute request) committed-database)))
                  (.then (fn [value]
                           (swap! notifications conj (::db/value value))
                           ((::reactive/notify request) (::db/value value))))
                  (.then (constantly (::reactive/consumer-key request))))))
      (set! reactive/unobserve!
            (fn [request]
              (reset! released request)
              (swap! registrations disj
                     [(::reactive/key request)
                      (::reactive/consumer-key request)])
              (js/Promise.resolve true)))
      (-> (js/Promise.resolve
           ((deref #'serve/await-agent-task-settlement!)
            database "agent-1" injected-at 1000))
          (.then
           (fn [result]
             (is (= [:running :idle]
                    (mapv :seon.web.serve/agent-state @notifications)))
             (is (= :idle (:seon.web.serve/agent-state result)))
             (is (true? (:seon.web.serve/turns-settled? result)))
             (is (= [::serve/agent-task-settlement "agent-1" injected-at]
                    (::reactive/key @observed)))
             (is (identical? database (::reactive/db @observed))
                 "settlement observes the exact acquired database value")
             (is (= (select-keys @observed
                                 [::reactive/key ::reactive/consumer-key])
                    @released)
                 "the request registration is structurally released")
             (is (empty? @registrations)
                 "no request-scoped reactive consumer remains")))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/with-read-evidence original-capture)
             (set! db/query original-query)
             (set! derive/derive-state original-state)
             (set! reactive/observe! original-observe)
             (set! reactive/unobserve! original-unobserve)
             (done)))))))

(deftest agent-run-settlement-timeout-releases-and-supersedes
  (async done
    (let [original-db db/db
          original-current run/current-run
          original-close run/close-run!
          original-observe reactive/observe!
          original-unobserve reactive/unobserve!
          injected-at (js/Date.now)
          released (atom 0)
          closed (atom nil)]
      (set! reactive/observe!
            (fn [request]
              (js/Promise.resolve (::reactive/consumer-key request))))
      (set! reactive/unobserve!
            (fn [_]
              (swap! released inc)
              (js/Promise.resolve true)))
      (set! run/current-run
            (fn [_]
              (js/Promise.resolve {:seon.agent.run/id "run-1"
                                   :seon.agent.run/claim-epoch 7})))
      (set! run/close-run!
            (fn [request]
              (reset! closed request)
              (js/Promise.resolve {})))
      (set! db/db
            (fn
              ([] (js/Promise.resolve
                   {:seon.error/message "stop before final projection"}))
              ([_] (db/db))))
      (-> (js/Promise.resolve
           ((deref #'serve/await-agent-task-settlement!)
            database "agent-1" injected-at 10))
          (.then
           (fn [settlement]
             (is (true? (:seon.web.serve/timed-out? settlement)))
             (is (= 1 @released))
             (js/Promise.resolve
              ((deref #'serve/finish-agent-task!)
               database "agent-1" injected-at 10 true))))
          (.then
           (fn [result]
             (is (= {:seon.agent.run/id "run-1"
                     :seon.agent.run/claim-epoch 7
                     :seon.agent.run/closed-reason :superseded}
                    @closed))
             (is (= {:error "stop before final projection"} result))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! run/current-run original-current)
             (set! run/close-run! original-close)
             (set! reactive/observe! original-observe)
             (set! reactive/unobserve! original-unobserve)
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
  {:seon.ai.attempt/id (str "a0000000000" ordinal)
   :seon.ai.attempt/ordinal ordinal
   :seon.ai.attempt/config-digest
   "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :seon.ai.attempt/deadline-at (js/Date. 45000)
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
   :seon.ai.attempt/reply-evaluation :batch
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
                (case (:seon.db/ref request)
                  [:seon.config/id "cluster"]
                  {:seon.config/repl-mode :stream
                   :seon.ai/wire-stream? true
                   :seon.ai/reply-evaluation :first-form}

                  [:seon.agent/id "agent-1"]
                  {:seon.ai/wire-stream? false
                   :seon.ai/reply-evaluation :batch}

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
    (is (every?
         #(schema/valid-candidate-value? :seon.ai.attempt/entity %)
         (vals attempts))
        (pr-str
         (mapv #(schema/explain-candidate-value :seon.ai.attempt/entity %)
               (vals attempts))))
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

(deftest final-agent-evidence-pulls-a-valid-config-singleton
  (is (= (into [:seon.config/id] (ai/model-transport-pull-pattern))
         @#'serve/final-cluster-pull-pattern))
  (is (= (into [:seon.config/id] (ai/config-pull-pattern))
         @#'serve/historical-cluster-pull-pattern)))

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

(deftest product-evidence-can-query-the-history-database-value
  (async done
    (let [database {:db-name "proof" :t 42 :as-of nil :since nil
                    :history false :datahike/commit-id (random-uuid)}
          !request (atom nil)
          original-db db/db
          original-query db/query]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/query
            (fn [request]
              (reset! !request request)
              (js/Promise.resolve #{["my.tax/rate" 10 true]})))
      (-> (serve/product-evidence
            {::db/query '[:find ?sym ?tx ?added
                          :where [?f :seon.fn/sym ?sym ?tx ?added]]
             ::db/history? true})
          (.then
            (fn [result]
              (is (true? (:history (::db/db @!request))))
              (is (nil? (::db/history? @!request)))
              (is (true? (get-in result [:seon.db/db :history])))
              (is (= [["my.tax/rate" 10 true]]
                     (:seon.db/result result)))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (set! db/db original-db)
                      (set! db/query original-query)
                      (done)))))))

(deftest operator-processes-reads-the-parent-host-on-demand
  (async done
    (let [original-processes execution-host/processes
          handler (deref #'serve/handle-operator-processes!)]
      (set! execution-host/processes
            (fn [] [{:seon.execution/agent-id "root"
                     :seon.execution.host/pid 41001
                     :seon.execution.host/artifact-digest
                     (apply str (repeat 64 "a"))
                     :seon.execution.host/ready? true
                     :seon.execution.host/retiring? false
                     :seon.execution.host/stdout-tail ""
                     :seon.execution.host/stderr-tail ""}]))
      (-> (js/Promise.resolve (handler nil nil))
          (.then (fn [response]
                   (is (= 200 (.-status response)))
                   (.json response)))
          (.then (fn [body]
                   (is (= 41001
                          (aget body "seon.execution.host/processes" 0
                                "seon.execution.host/pid")))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (set! execution-host/processes original-processes)
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
