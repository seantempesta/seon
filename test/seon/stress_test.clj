(ns seon.stress-test
  "De-risking & stress-testing the core architecture:
   1. Verifies EVERY co-located multi-segment schema in `resources/seon/schemas/*.edn` compiles,
      generates deterministic `seed = 42` values, and automatically derives Datahike schema.
   2. Dynamically registers brand-new Malli entity/attribute schemas inside a live forked agent REPL,
      verifies Datahike `:db/ident`s are auto-installed, writes & AVET-queries entities on the branch,
      and 3-way merges both derived Datahike schema AND entities onto `:main`!
   3. Runs a 4-branch distributed scoped swarm (`seon.app.auth`, `seon.app.billing`, `seon.app.inventory`,
      `seon.app.orders`) sharing an agreed `:seon.app/money` contract over a constrained `max-os-procs = 2`
      COW pool (exercising `::flow/input-filter` backpressure + warm LRU eviction + scope isolation!).
   4. Verifies normal `^:seon/callable` Clojure form dispatch on `POST /call` and rejects unapproved vars."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.flow-e2e-test :refer [await-until rm-rf! shared-test-router]]
            [seon.main :as main]
            [seon.merge :as merge]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.web :as web]))

(deftest all-colocated-schemas-and-runtime-datahike-derivation-stress-test
  (let [dir (str "/tmp/seon-stress-sch-" (System/nanoTime))
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
    (try
      (testing "1. All ~90 co-located schemas in `resources/seon/schemas/*.edn` are multi-segment & seed=42 generatable"
        (let [db @(:conn sys)
              forms @schema/disk-base-forms]
          (is (>= (count forms) 80) (str "Expected >= 80 co-located schemas, got " (count forms)))
          (doseq [[k f] forms]
            (is (schema/multi-segment-schema-key? k) (str "Non-multi-segment key: " k))
            ;; Verify every non-host-predicate schema generates with seed=42!
            (let [r (schema/validate-schema-registration! db {:key k :form f})]
              (is (:ok? r) (str "Schema failed generator gate: " k))))))

      (testing "2. Agent registers brand-new storable Malli schemas at runtime -> Datahike attributes auto-derive & merge to :main!"
        (let [code (str "(user/register-schema! :seon.billing.invoice/id\n"
                        "  [:and {:seon.db/identity true :seon.db/content-id true} :qualified-keyword])\n"
                        "(user/register-schema! :seon.billing.invoice/amount\n"
                        "  [:double {:seon.db/index true :min 0.0 :max 50000.0}])\n"
                        "(user/transact! [{:seon.billing.invoice/id :inv/first :seon.billing.invoice/amount 1250.5}])\n"
                        "(:seon.billing.invoice/amount (datahike.api/pull (user/db) [:seon.billing.invoice/amount] [:seon.billing.invoice/id :inv/first]))")
              ev (main/call! sys {:op :eval :branch :branch/billing :code code})]
          (is (:ok? ev) (pr-str ev))
          (is (= "1250.5" (:value ev))))

        ;; Merge `:branch/billing` into `:main`: `:transactor` automatically installs the derived Datahike
        ;; `:db/ident`s (`:seon.billing.invoice/id` + `:seon.billing.invoice/amount`) AND merges the entity!
        (let [m (main/call! sys {:op :merge :branch :branch/billing})]
          (is (:ok? m) (pr-str m))
          (is (contains? (:touched-paths m) "resources/seon/schemas/seon.billing.edn"))
          (let [main-db @(:conn sys)
                pulled (d/pull main-db '[:seon.billing.invoice/id :seon.billing.invoice/amount]
                               [:seon.billing.invoice/id :inv/first])]
            (is (= :db.type/double (get-in main-db [:schema :seon.billing.invoice/amount :db/valueType])))
            (is (true? (get-in main-db [:schema :seon.billing.invoice/amount :db/index])))
            (is (= 1250.5 (:seon.billing.invoice/amount pulled))))))
      (finally
        (main/stop! sys)
        (rm-rf! (io/file dir))))))

(deftest distributed-scoped-swarm-multiplexing-stress-test
  (let [dir (str "/tmp/seon-stress-swarm-" (System/nanoTime))
        ;; Constrain `max-os-procs` to 2 while running 4 concurrent scoped agents!
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :port 0 :max-os-procs 2})]
    (try
      ;; Agree on shared cross-domain contract `:seon.app/money` first!
      (let [reg (main/call! sys {:op :register-schema
                                 :key :seon.app/money
                                 :form [:double {:min 0.0 :max 10000.0}]})]
        (is (:ok? reg)))

      (let [scopes [['seon.app.auth      :app/auth      "(defn token-fee {:malli/schema [:=> [:cat :seon.app/money] :seon.app/money]} [m] (* m 0.01))"]
                    ['seon.app.billing   :app/billing   "(defn with-tax  {:malli/schema [:=> [:cat :seon.app/money] :seon.app/money]} [m] (* m 1.1))"]
                    ['seon.app.inventory :app/inventory "(defn hold-cost {:malli/schema [:=> [:cat :seon.app/money] :seon.app/money]} [m] (* m 0.05))"]
                    ['seon.app.orders    :app/orders    "(defn ship-cost {:malli/schema [:=> [:cat :seon.app/money] :seon.app/money]} [m] (+ m 5.0))"]]]
        (doseq [[sc aid fn-src] scopes
                :let [test-ns (symbol (str sc "-test"))
                      fn-name (second (read-string fn-src))]]
          (main/call! sys {:op :spawn-agent
                           :id aid
                           :scope sc
                           :role :worker
                           :goal ""})
          (main/call! sys {:op :send-message
                           :from :root
                           :to aid
                           :type :task
                           :body (str "(ns " sc ")\n" fn-src "\n"
                                      "(ns " test-ns " (:require [clojure.test :refer [deftest is]] [" sc " :as sut]))\n"
                                      "(deftest " fn-name "-test (is (>= (sut/" fn-name " 100.0) 0.0)))\n"
                                      "(in-ns 'user)")}))

        ;; Wait for all 4 scoped agents to multiplex across 2 COW slots, pass `deftest` + 100x `seed=42` stress checks, and merge!
        (let [finished
              (await-until
               (fn []
                 (let [st (main/call! sys {:op :status})
                       ags (:agents st)]
                   (when (and (= 4 (count ags))
                              (every? #(= :merged (:status %)) ags))
                     st)))
               20000)]
          (is (some? finished) "All 4 concurrent scoped agents must multiplex across 2 OS slots and merge!")
          (is (every? #(nil? (:live-pid %)) (:agents finished)))

          ;; Verify hierarchical scope isolation & shared schema cross-referencing on `:main`!
          (let [billing-view (code/scope-view @(:conn sys) 'seon.app.billing)
                money-users (:used-by (:schema (main/call! sys {:op :explore-schema :key :seon.app/money})))]
            (is (= ['seon.app.billing 'seon.app.billing-test] (:namespaces billing-view)))
            (is (= [:seon.app/money] (:boundary-schemas billing-view))
                "Scoped view sees its own subtree + exact shared `:seon.app/money` boundary schema!")
            (is (= 4 (count money-users))
                "All 4 distributed functions are indexed under `:seon.app/money` via `:seon.code.var/schema-refs`!"))))
      (finally
        (main/stop! sys)
        (rm-rf! (io/file dir))))))

(deftest callable-hiccup-form-dispatch-and-security-test
  (let [dir (str "/tmp/seon-stress-ui-" (System/nanoTime))
        listener (web/start-http-listener! -1)
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :listener listener})]
    (try
      (testing "1. Normal `^:seon/callable` Clojure form switches tab and emits `datastar-patch-elements`"
        (let [q (str "form=" (web/url-enc "(seon.web/switch-tab! :schemas)"))
              resp ((:handler listener) {:request-method :post :uri "/call" :query-string q :body ""})]
          (is (= 200 (:status resp)))
          (is (str/includes? (:body resp) "event: datastar-patch-elements"))
          (is (str/includes? (:body resp) "Co-Located Multi-Segment Malli Schemas"))))

      (testing "2. Non-`^:seon/callable` Clojure vars are strictly refused with 403 structured EDN"
        (let [q (str "form=" (web/url-enc "(clojure.java.shell/sh \"ls\")"))
              resp ((:handler listener) {:request-method :post :uri "/call" :query-string q :body ""})
              data (edn/read-string (:body resp))]
          (is (= 403 (:status resp)))
          (is (= :seon.web/not-callable (:error data)))))
      (finally
        (main/stop! sys)
        ((:stop! listener))
        (rm-rf! (io/file dir))))))

(deftest host-predicates-and-core-contracts-test
  (testing "1. Pure value function contracts run 100x deterministic `seed = 42` checks within budget"
    (is (true? (schema/generatable-function-schema? (:malli/schema (meta #'schema/multi-segment-schema-key?)))))
    (is (true? (schema/generatable-function-schema? (:malli/schema (meta #'code/in-scope?)))))
    (is (not (schema/generatable-function-schema? (:malli/schema (meta #'store/point)))))
    (let [r1 (schema/stress-var nil 'seon.schema/multi-segment-schema-key? {:samples 100 :seed 42 :measure-fn code/measure-call})
          r2 (schema/stress-var nil 'seon.code/in-scope? {:samples 100 :seed 42 :measure-fn code/measure-call})]
      (is (:ok? r1) (pr-str r1))
      (is (= 100 (:ran r1)))
      (is (:ok? r2) (pr-str r2))
      (is (= 100 (:ran r2)))))

  (testing "2. `:seon.host/db` and `:seon.host/conn` enforce real Datahike runtime types under `arm-var!`"
    (schema/arm-var! #'store/point)
    (schema/arm-var! #'store/config-val)
    (schema/arm-var! #'merge/three-way)
    (let [err (try (store/point "not-a-db") nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :seon.schema/contract-violation (:error err)))
      (is (= 'seon.store/point (:var err))))
    (let [err2 (try (store/config-val "not-a-db" :seon.config/port) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :seon.schema/contract-violation (:error err2)))
      (is (= 'seon.store/config-val (:var err2))))
    (let [err3 (try (merge/three-way "not-a-conn" {} :main) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :seon.schema/contract-violation (:error err3)))
      (is (= 'seon.merge/three-way (:var err3))))))
