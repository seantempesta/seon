(ns seon.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.scheme :as scm]
            [seon.code :as code]
            [seon.merge :as merge]
            [seon.schema :as schema]
            [seon.server :as server]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(def ticks-fn (scm/eval-string "(lambda () (jolt-ticks))"))

(deftest lossless-clj-and-md-roundtrip-plus-structured-var-facts-test
  (with-test-store [{:keys [conn]}]
    (let [sample (str ";; Leading file header comment\n"
                      "(ns demo.math\n"
                      "  \"Docstring for math ns.\"\n"
                      "  (:require [clojure.string :as str]))\n\n"
                      ";; Keep this comment byte-for-byte!\n"
                      "(defn add\n"
                      "  \"Adds x and y.\"\n"
                      "  {:malli/schema [:=> [:cat :seon/tick-budget :int] :int]\n"
                      "   :cost/ticks 50000\n"
                      "   :cost/big-o \"O(1)\"}\n"
                      "  [x y]\n"
                      "  (+ x y))\n\n"
                      "(defn loud [s]\n"
                      "  {:tag ::local-kw :sum (add 1000 2) :s (str/upper-case s)})\n"
                      ";; Trailing comment\n")
          md-sample (str "# Architecture\n\nIntro paragraph.\n\n## Flow Graph\n\nDetails on procs.\n")
          _ (d/transact conn {:tx-data (code/file-import-tx nil "src/demo/math.clj" sample false)})
          _ (d/transact conn {:tx-data (code/file-import-tx nil "docs/arch.md" md-sample false)})]

      (testing "1. `.clj` and `.md` import -> render are 100% byte-for-byte identical"
        (is (= sample (code/render-path @conn "src/demo/math.clj")))
        (is (= md-sample (code/render-path @conn "docs/arch.md"))))

      (testing "2. Structured `:var/*` and `:doc/*` facts are indexed in Datahike (0 live parsing!)"
        (let [add-fact (server/explore-var @conn 'demo.math/add)
              sch-info (server/explore-schema @conn :seon/tick-budget)]
          (is (= "Adds x and y." (:var/doc add-fact)))
          (is (= 50000 (:var/ticks-budget add-fact)))
          (is (= "O(1)" (:var/big-o add-fact)))
          ;; 1 AVET seek finds callers of `demo.math/add` and functions using `:seon/tick-budget`!
          (is (= ['demo.math/loud] (:callers add-fact)))
          (is (= ['demo.math/add] (:used-by sch-info)))
          (is (= 5 (count (:samples sch-info))))))

      (testing "3. Editing comments and docstrings does NOT move semantic `:form/hash`"
        (let [h1 (:form/hash (d/pull @conn '[:form/hash] [:form/key "demo.math/add"]))
              edited (str/replace sample "\"Adds x and y.\"" "\"Updated docs!\" ;; extra comment")
              _ (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/math.clj" edited false)})
              h2 (:form/hash (d/pull @conn '[:form/hash] [:form/key "demo.math/add"]))]
          (is (= h1 h2)))))))

(deftest malli-write-rail-generator-gate-and-instrumentation-test
  (with-test-store [{:keys [conn]}]
    (testing "1. Malformed datoms are rejected at the write seam"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Schema rail rejected"
           (d/transact conn {:tx-data [{:task/id :unqualified-bad-id :task/title "Bad"}]}))))

    (testing "2. Schema registration rejects un-generatable schemas without `:examples`, and accepts valid ones"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"no working generator"
           (schema/validate-schema-registration! @conn {:key :bad/fn-pred :form ":seon.host/fn"})))
      (let [ok (schema/validate-schema-registration!
                @conn {:key :order/discount :form "[:double {:min 0.0 :max 1.0}]"})]
        (is (:ok? ok))
        (d/transact conn {:tx-data [(:entity ok)]})))

    (testing "3. Contracted `defn` is automatically instrumented with humanized error + valid example args"
      (let [res (code/eval-forms
                 @conn
                 {:agent-id :ag/dev :turn 1}
                 (str "(ns demo.contract)\n"
                      "(defn apply-disc {:malli/schema [:=> [:cat [:double {:min 0.0}] :order/discount] [:double {:min 0.0}]]}\n"
                      "  [price rate] (* price (- 1.0 rate)))\n"
                      "(apply-disc 100.0 0.25)"))
            _ (is (:ok? res))
            _ (is (= "75.0" (:value-str res)))
            bad (code/eval-forms @conn {:agent-id :ag/dev :turn 2} "(demo.contract/apply-disc -10.0 2.5)")]
        (is (false? (:ok? bad)))
        (is (str/includes? (:error bad) "Schema contract violation in demo.contract/apply-disc"))
        (is (str/includes? (:error bad) "Valid example args:"))))))

(deftest runaway-eval-tick-budget-and-multi-tool-error-isolation-test
  (with-test-store [{:keys [conn]}]
    ;; Override `:config/eval-ticks` directly on `:ag/limited` entity (Aero config inheritance!)
    (d/transact conn {:tx-data [{:agent/id :ag/limited
                                 :agent/branch :main
                                 :agent/role :worker
                                 :agent/goal "test"
                                 :config/eval-ticks 5000000}]})
    (testing "1. Agent entity overrides `:system/root` config in 1 lookup"
      (is (= 5000000 (store/config-val @conn :ag/limited :config/eval-ticks)))
      (is (= 100000000 (store/config-val @conn :ag/other :config/eval-ticks))))

    (testing "2. Runaway `(try (loop [] (recur)) (catch Throwable _))` cannot swallow tick interruption!"
      (let [r (code/eval-forms
               @conn
               {:agent-id :ag/limited :turn 1}
               "(try (loop [i 0] (recur (inc i))) (catch Throwable _ :swallowed))")]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "Eval exceeded tick budget"))))

    (testing "3. Multi-tool `eval-tool-calls` continues past a failed tool call and commits healthy siblings!"
      (let [batch (code/eval-tool-calls
                   conn
                   {:agent-id :ag/limited :turn 2}
                   [{:id "tc-1" :code "(ns demo.multi) (def a 10) a"}
                    {:id "tc-2" :code "(/ 1 0)"}
                    {:id "tc-3" :code "(+ demo.multi/a 32)"}])]
        (is (false? (:ok? batch)))
        (is (= [true false true] (mapv :ok? (:results batch))))
        (is (= "10" (:value (first (:results batch)))))
        (is (some? (:error (second (:results batch)))))
        (is (= "42" (:value (nth (:results batch) 2))))))))

(deftest generic-schema-driven-three-way-merge-test
  (with-test-store [{:keys [conn cfg]}]
    (let [src-v0 "(ns demo.app)\n\n(defn alpha [x] (+ x 1))\n\n(defn beta [x] (* x 2))\n"
          _ (d/transact conn {:tx-data (concat (code/file-import-tx nil "src/demo/app.clj" src-v0 false)
                                               [{:task/id :demo/t1
                                                 :task/title "Original"
                                                 :task/plan "Initial plan"
                                                 :task/tests ['demo.app/t0]
                                                 :task/done? false}])})
          _ (store/branch! conn :main :branch/a)
          _ (store/branch! conn :main :branch/b)
          ca (store/connect cfg :branch/a)
          cb (store/connect cfg :branch/b)]
      (try
        ;; Branch A edits `alpha` + `:task/plan` + adds test `t1` + registers a new `:schema/key`!
        (d/transact ca {:tx-data [{:form/key "demo.app/alpha"
                                   :form/ns 'demo.app
                                   :form/order 1
                                   :form/lead "\n\n"
                                   :form/src "(defn alpha [x] (+ x 100))"
                                   :form/hash "aaaaaaaaaaaaaaaa"
                                   :form/deps ['demo.app/#ns]
                                   :form/test? false}
                                  {:task/id :demo/t1
                                   :task/plan "Refined plan from A"}
                                  [:db/add [:task/id :demo/t1] :task/tests 'demo.app/t-from-a]
                                  {:schema/key :demo/score :schema/form "[:int {:min 0 :max 100}]"}]})
        ;; Branch B edits `beta` + `:task/title` + adds test `t-from-b` on the SAME `:task/id`!
        (d/transact cb {:tx-data [{:form/key "demo.app/beta"
                                   :form/ns 'demo.app
                                   :form/order 2
                                   :form/lead "\n\n"
                                   :form/src "(defn beta [x] (* x 999))"
                                   :form/hash "bbbbbbbbbbbbbbbb"
                                   :form/deps ['demo.app/#ns]
                                   :form/test? false}
                                  {:task/id :demo/t1
                                   :task/title "Updated Title from B"}
                                  [:db/add [:task/id :demo/t1] :task/tests 'demo.app/t-from-b]]})

        ;; Merge A -> :main, then B -> :main: ZERO conflicts (fine-grained scalar + cardinality/many set merge!)
        (let [plan-a (merge/three-way conn cfg :branch/a)]
          (is (:ok? plan-a))
          (d/transact conn {:tx-data (:tx-data plan-a)}))
        (let [plan-b (merge/three-way conn cfg :branch/b)]
          (is (:ok? plan-b))
          (is (empty? (:conflicts plan-b)))
          (d/transact conn {:tx-data (:tx-data plan-b)}))

        (let [merged-file (code/render-path @conn "src/demo/app.clj")
              merged-task (d/pull @conn '[:task/title :task/plan :task/tests] [:task/id :demo/t1])]
          (is (str/includes? merged-file "(defn alpha [x] (+ x 100))"))
          (is (str/includes? merged-file "(defn beta [x] (* x 999))"))
          (is (= "Updated Title from B" (:task/title merged-task)))
          (is (= "Refined plan from A" (:task/plan merged-task)))
          (is (= #{'demo.app/t0 'demo.app/t-from-a 'demo.app/t-from-b} (set (:task/tests merged-task))))
          (is (= "[:int {:min 0 :max 100}]" (:schema/form (d/pull @conn '[:schema/form] [:schema/key :demo/score])))))

        ;; Conflicting edit to the SAME scalar attribute (`:form/src` on `demo.app/alpha`) is caught!
        (d/transact cb {:tx-data [{:form/key "demo.app/alpha"
                                   :form/src "(defn alpha [x] (- x 50))"
                                   :form/hash "cccccccccccccccc"}]})
        (let [plan-c (merge/three-way conn cfg :branch/b)]
          (is (false? (:ok? plan-c)))
          (is (some #(= [:form/key "demo.app/alpha"] (:entity %)) (:conflicts plan-c))))
        (finally
          (d/release ca)
          (d/release cb))))))

(defn- seed-scale-forms! [conn n]
  (doseq [batch (partition-all 200 (range n))]
    (d/transact
     conn
     {:tx-data
      (vec
       (for [i batch
             :let [nsym (symbol (str "scale.m" (quot i 25)))
                   fsym (str "f" i)]]
         {:form/key (str nsym "/" fsym)
          :form/ns nsym
          :form/order (long (mod i 25))
          :form/lead "\n"
          :form/src (str "(defn " fsym " [x] (+ x " i "))")
          :form/hash (format "%016x" (+ 500000 i))
          :form/deps []
          :form/test? false}))})))

(defn- measure-delta-ops-ticks [n]
  (with-test-store [{:keys [conn cfg]}]
    (seed-scale-forms! conn n)
    (let [g0 (code/build-reach-graph @conn)
          _ (store/branch! conn :main :branch/edit)
          bc (store/connect cfg :branch/edit)]
      (try
        (d/transact bc {:tx-data [{:form/key "scale.m0/f0"
                                   :form/ns 'scale.m0
                                   :form/order 0
                                   :form/lead "\n"
                                   :form/src "(defn f0 [x] (* x 777))"
                                   :form/hash "fedcba9876543210"
                                   :form/deps []
                                   :form/test? false}]})
        (let [k0 (ticks-fn)
              _ (code/update-reach-graph g0 bc)
              _ (merge/three-way conn cfg :branch/edit)
              dk (- (ticks-fn) k0)]
          dk)
        (finally
          (d/release bc))))))

(deftest ^{:cost/ticks 500000000} scale-invariance-rail-test
  (testing "O(|Delta|) rail: 10x larger DB (N=100 vs N=1,000) must cost < 1.6x ticks for a 1-form edit!"
    (measure-delta-ops-ticks 25)
    (let [ticks-100  (measure-delta-ops-ticks 100)
          ticks-1000 (measure-delta-ops-ticks 1000)
          ratio (/ (double ticks-1000) (double (max 1 ticks-100)))]
      (is (< ratio 1.6)
          (str "Detected O(N) regression! ticks-100=" ticks-100 " ticks-1000=" ticks-1000 " ratio=" ratio)))))
