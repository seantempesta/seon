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
                      "(ns seon.demo.math\n"
                      "  \"Docstring for math ns.\"\n"
                      "  (:require [clojure.string :as str]))\n\n"
                      ";; Keep this comment byte-for-byte!\n"
                      "(defn add\n"
                      "  \"Adds x and y.\"\n"
                      "  {:malli/schema [:=> [:cat :seon.code.var/ticks-budget :int] :int]\n"
                      "   :cost/ticks 50000\n"
                      "   :cost/big-o \"O(1)\"}\n"
                      "  [x y]\n"
                      "  (+ x y))\n\n"
                      "(defn loud [s]\n"
                      "  {:tag ::local-kw :sum (add 1000 2) :s (str/upper-case s)})\n"
                      ";; Trailing comment\n")
          md-sample (str "# Architecture\n\nIntro paragraph.\n\n## Flow Graph\n\nDetails on procs.\n")
          _ (d/transact conn {:tx-data (code/file-import-tx nil "src/seon/demo/math.clj" sample false)})
          _ (d/transact conn {:tx-data (code/file-import-tx nil "docs/arch.md" md-sample false)})]

      (testing "1. `.clj` and `.md` import -> render are 100% byte-for-byte identical"
        (is (= sample (code/render-path @conn "src/seon/demo/math.clj")))
        (is (= md-sample (code/render-path @conn "docs/arch.md"))))

      (testing "2. Structured `:seon.code.var/*` facts store native `:db.type/data` (no stringified EDN!)"
        (let [add-fact (server/explore-var @conn 'seon.demo.math/add)
              sch-info (server/explore-schema @conn :seon.code.var/ticks-budget)]
          (is (= "Adds x and y." (:seon.code.var/doc add-fact)))
          (is (= '[[x y]] (:seon.code.var/arglists add-fact)) "Arglists stored natively via `:db.type/data`!")
          (is (vector? (:seon.code.var/schema add-fact)) "Malli contract stored natively via `:db.type/data`!")
          (is (= 50000 (:seon.code.var/ticks-budget add-fact)))
          (is (= ['seon.demo.math/loud] (:callers add-fact)))
          (is (= ['seon.demo.math/add] (:used-by sch-info)))
          (is (= :db.type/long (get-in sch-info [:datahike-attribute :db/valueType])))))

      (testing "3. Editing comments and docstrings does NOT move semantic `:seon.code.form/hash`"
        (let [h1 (:seon.code.form/hash (d/pull @conn '[:seon.code.form/hash] [:seon.code.form/key "seon.demo.math/add"]))
              edited (str/replace sample "\"Adds x and y.\"" "\"Updated docs!\" ;; extra comment")
              _ (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/math.clj" edited false)})
              h2 (:seon.code.form/hash (d/pull @conn '[:seon.code.form/hash] [:seon.code.form/key "seon.demo.math/add"]))]
          (is (= h1 h2)))))))

(deftest malli-write-rail-multi-segment-gate-and-instrumentation-test
  (with-test-store [{:keys [conn]}]
    (testing "1. Malformed datoms are rejected at the write seam by the live Malli registry"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Schema rail rejected"
           (d/transact conn {:tx-data [{:seon.task/id :unqualified-bad-id :seon.task/title "Bad"}]}))))

    (testing "2. Shallow `one/thing` schema keys and un-generatable schemas without `:examples` are rejected"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"multi-segment namespace"
           (schema/validate-schema-registration! @conn {:key :shallow/discount :form "[:double {:min 0.0}]"})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"no working generator"
           (schema/validate-schema-registration! @conn {:key :seon.bad/fn-pred :form ":seon.host/fn"})))
      (let [ok (schema/validate-schema-registration!
                @conn {:key :seon.order/discount :form [:double {:min 0.0 :max 1.0}]})]
        (is (:ok? ok))
        (d/transact conn {:tx-data [(:entity ok)]})))

    (testing "3. Contracted `defn` is automatically instrumented (~50 ns/call) with humanized error + example args"
      (let [res (code/eval-forms
                 @conn
                 {:agent-id :ag/dev :turn 1}
                 (str "(ns seon.demo.contract)\n"
                      "(defn apply-disc {:malli/schema [:=> [:cat [:double {:min 0.0}] :seon.order/discount] [:double {:min 0.0}]]}\n"
                      "  [price rate] (* price (- 1.0 rate)))\n"
                      "(apply-disc 100.0 0.25)"))
            _ (is (:ok? res))
            _ (is (= "75.0" (:value-str res)))
            bad (code/eval-forms @conn {:agent-id :ag/dev :turn 2} "(seon.demo.contract/apply-disc -10.0 2.5)")]
        (is (false? (:ok? bad)))
        (is (str/includes? (:error bad) "Schema contract violation in seon.demo.contract/apply-disc"))
        (is (str/includes? (:error bad) "Valid example args:"))))))

(deftest runaway-eval-tick-budget-and-multi-tool-error-isolation-test
  (with-test-store [{:keys [conn]}]
    (d/transact conn {:tx-data [{:seon.agent/id :ag/limited
                                 :seon.agent/branch :main
                                 :seon.agent/role :worker
                                 :seon.agent/goal "test"
                                 :seon.config/eval-ticks 5000000}]})
    (testing "1. Agent entity overrides `:seon.config/root` in 1 lookup"
      (is (= 5000000 (store/config-val @conn :ag/limited :seon.config/eval-ticks)))
      (is (= 100000000 (store/config-val @conn :ag/other :seon.config/eval-ticks))))

    (testing "2. Runaway `(try (loop [] (recur)) (catch Throwable _))` cannot swallow tick interruption"
      (let [r (code/eval-forms
               @conn
               {:agent-id :ag/limited :turn 1}
               "(try (loop [i 0] (recur (inc i))) (catch Throwable _ :swallowed))")]
        (is (false? (:ok? r)))
        (is (str/includes? (:error r) "Eval exceeded tick budget"))))

    (testing "3. Multi-tool `eval-tool-calls` continues past a failed tool call and commits healthy siblings"
      (let [batch (code/eval-tool-calls
                   conn
                   {:agent-id :ag/limited :turn 2}
                   [{:id "tc-1" :code "(ns seon.demo.multi) (def a 10) a"}
                    {:id "tc-2" :code "(/ 1 0)"}
                    {:id "tc-3" :code "(+ seon.demo.multi/a 32)"}])]
        (is (false? (:ok? batch)))
        (is (= [true false true] (mapv :ok? (:results batch))))
        (is (= "10" (:value (first (:results batch)))))
        (is (some? (:error (second (:results batch)))))
        (is (= "42" (:value (nth (:results batch) 2))))))))

(deftest generic-malli-driven-three-way-merge-test
  (with-test-store [{:keys [conn cfg]}]
    (let [src-v0 "(ns seon.demo.app)\n\n(defn alpha [x] (+ x 1))\n\n(defn beta [x] (* x 2))\n"
          _ (d/transact conn {:tx-data (concat (code/file-import-tx nil "src/seon/demo/app.clj" src-v0 false)
                                               [{:seon.task/id :seon.demo/t1
                                                 :seon.task/title "Original"
                                                 :seon.task/plan "Initial plan"
                                                 :seon.task/tests #{'seon.demo.app/t0}
                                                 :seon.task/done? false}])})
          _ (store/branch! conn :main :branch/a)
          _ (store/branch! conn :main :branch/b)
          ca (store/connect cfg :branch/a)
          cb (store/connect cfg :branch/b)]
      (try
        (d/transact ca {:tx-data [{:seon.code.form/key "seon.demo.app/alpha"
                                   :seon.code.form/ns 'seon.demo.app
                                   :seon.code.form/order 1
                                   :seon.code.form/lead "\n\n"
                                   :seon.code.form/src "(defn alpha [x] (+ x 100))"
                                   :seon.code.form/hash "aaaaaaaaaaaaaaaa"
                                   :seon.code.form/deps #{'seon.demo.app/#ns}
                                   :seon.code.form/test? false}
                                  {:seon.task/id :seon.demo/t1
                                   :seon.task/plan "Refined plan from A"}
                                  [:db/add [:seon.task/id :seon.demo/t1] :seon.task/tests 'seon.demo.app/t-from-a]
                                  {:seon.schema/key :seon.demo/score :seon.schema/form [:int {:min 0 :max 100}]}]})
        (d/transact cb {:tx-data [{:seon.code.form/key "seon.demo.app/beta"
                                   :seon.code.form/ns 'seon.demo.app
                                   :seon.code.form/order 2
                                   :seon.code.form/lead "\n\n"
                                   :seon.code.form/src "(defn beta [x] (* x 999))"
                                   :seon.code.form/hash "bbbbbbbbbbbbbbbb"
                                   :seon.code.form/deps #{'seon.demo.app/#ns}
                                   :seon.code.form/test? false}
                                  {:seon.task/id :seon.demo/t1
                                   :seon.task/title "Updated Title from B"}
                                  [:db/add [:seon.task/id :seon.demo/t1] :seon.task/tests 'seon.demo.app/t-from-b]]})

        (let [plan-a (merge/three-way conn cfg :branch/a)]
          (is (:ok? plan-a))
          (d/transact conn {:tx-data (:tx-data plan-a)}))
        (let [plan-b (merge/three-way conn cfg :branch/b)]
          (is (:ok? plan-b))
          (is (empty? (:conflicts plan-b)))
          (d/transact conn {:tx-data (:tx-data plan-b)}))

        (let [merged-file (code/render-path @conn "src/seon/demo/app.clj")
              merged-task (d/pull @conn '[:seon.task/title :seon.task/plan :seon.task/tests] [:seon.task/id :seon.demo/t1])]
          (is (str/includes? merged-file "(defn alpha [x] (+ x 100))"))
          (is (str/includes? merged-file "(defn beta [x] (* x 999))"))
          (is (= "Updated Title from B" (:seon.task/title merged-task)))
          (is (= "Refined plan from A" (:seon.task/plan merged-task)))
          (is (= #{'seon.demo.app/t0 'seon.demo.app/t-from-a 'seon.demo.app/t-from-b}
                 (set (:seon.task/tests merged-task))))
          (is (= [:int {:min 0 :max 100}]
                 (:seon.schema/form (d/pull @conn '[:seon.schema/form] [:seon.schema/key :seon.demo/score])))))

        (d/transact cb {:tx-data [{:seon.code.form/key "seon.demo.app/alpha"
                                   :seon.code.form/src "(defn alpha [x] (- x 50))"
                                   :seon.code.form/hash "cccccccccccccccc"}]})
        (let [plan-c (merge/three-way conn cfg :branch/b)]
          (is (false? (:ok? plan-c)))
          (is (some #(= [:seon.code.form/key "seon.demo.app/alpha"] (:entity %)) (:conflicts plan-c))))
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
             :let [nsym (symbol (str "seon.scale.m" (quot i 25)))
                   fsym (str "f" i)]]
         {:seon.code.form/key (str nsym "/" fsym)
          :seon.code.form/ns nsym
          :seon.code.form/order (long (mod i 25))
          :seon.code.form/lead "\n"
          :seon.code.form/src (str "(defn " fsym " [x] (+ x " i "))")
          :seon.code.form/hash (format "%016x" (+ 500000 i))
          :seon.code.form/test? false}))})))

(defn- measure-delta-ops-ticks [n]
  (with-test-store [{:keys [conn cfg]}]
    (seed-scale-forms! conn n)
    (let [g0 (code/build-reach-graph @conn)
          _ (store/branch! conn :main :branch/edit)
          bc (store/connect cfg :branch/edit)]
      (try
        (d/transact bc {:tx-data [{:seon.code.form/key "seon.scale.m0/f0"
                                   :seon.code.form/ns 'seon.scale.m0
                                   :seon.code.form/order 0
                                   :seon.code.form/lead "\n"
                                   :seon.code.form/src "(defn f0 [x] (* x 777))"
                                   :seon.code.form/hash "fedcba9876543210"
                                   :seon.code.form/test? false}]})
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

(deftest ^{:cost/ticks 50000000} repl-scratch-evals-do-not-pollute-source-forms-test
  (testing "Interactive REPL scratch queries inside a non-user ns never persist as `#expr#` source forms"
    (with-test-store [{:keys [conn]}]
      (let [r1 (code/eval-forms @conn {:agent-id :ag/dev :turn 1}
                                "(ns seon.demo.scratch)\n(defn real-fn [x] (inc x))")
            _ (d/transact conn {:tx-data (:tx-data r1)})
            r2 (code/eval-forms @conn {:agent-id :ag/dev :turn 2 :default-ns 'seon.demo.scratch}
                                "(let [a (atom 1)] (+ @a (real-fn 2)))\n(require 'clojure.string)\n[(real-fn 10)]")
            _ (d/transact conn {:tx-data (:tx-data r2)})
            keys (set (map :seon.code.form/key (code/ns-forms @conn 'seon.demo.scratch)))]
        (is (:ok? r1))
        (is (:ok? r2))
        (is (= "[11]" (:value-str r2)))
        (is (= #{"seon.demo.scratch#ns" "seon.demo.scratch/real-fn"} keys))))))

(deftest strikethrough-and-inline-emphasis-test
  (let [emph @#'rui/render-emphasis]
    (testing "render-emphasis: ~~strikethrough~~ -> [:del ...] while **bold** and *italic* still work"
      (is (= ["plain"] (emph "plain")))
      (is (= [[:del "crossed out"]] (emph "~~crossed out~~")))
      (is (= [[:strong "bold"]] (emph "**bold**")))
      (is (= [[:em "italic"]] (emph "*italic*")))
      (is (= ["a " [:strong "bold"] " and " [:em "italic"] " and " [:del "strike"] " end"]
             (emph "a **bold** and *italic* and ~~strike~~ end")))))
  (testing "render-inline-prose: code spans survive alongside strikethrough"
    (is (= [:span [:strong "b"] " " [:code {:class "inline-code"} "c"] " " [:del "s"] " " [:em "i"]]
           (rui/render-inline-prose "**b** `c` ~~s~~ *i*")))))
