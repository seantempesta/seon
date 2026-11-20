(ns seon.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.scheme :as scm]
            [seon.code :as code]
            [seon.code.edit]
            [seon.code.refactor]
            [seon.merge :as merge]
            [seon.schema :as schema]
            [seon.server :as server]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]
            [seon.main]
            [seon.code.cst]
            [seon.repl]
            [seon.model.wire :as mwire]
            [seon.render.tail :as rtail]))

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
    (let [src-v0 "(ns seon.demo.app)\n\n(defn alpha {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))\n\n(defn beta {:malli/schema [:=> [:cat :int] :int]} [x] (* x 2))\n"
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
  (let [emph (deref (requiring-resolve 'seon.render.ui/render-emphasis))
        inline (requiring-resolve 'seon.render.markdown/render-inline-prose)]
    (testing "render-emphasis: ~~strikethrough~~ -> [:del ...] while **bold** and *italic* still work"
      (is (= ["plain"] (emph "plain")))
      (is (= [[:del "crossed out"]] (emph "~~crossed out~~")))
      (is (= [[:strong "bold"]] (emph "**bold**")))
      (is (= [[:em "italic"]] (emph "*italic*")))
      (is (= ["a " [:strong "bold"] " and " [:em "italic"] " and " [:del "strike"] " end"]
             (emph "a **bold** and *italic* and ~~strike~~ end"))))
    (testing "render-inline-prose: code spans survive alongside strikethrough"
      (is (= [:span [:strong "b"] " " [:code {:class "inline-code"} "c"] " " [:del "s"] " " [:em "i"]]
             (inline "**b** `c` ~~s~~ *i*"))))))

(deftest ^{:cost/ticks 80000000} context-self-improvement-and-session-compaction-test
  (testing "Datahike retraction compaction trims active evals while preserving d/history, d/as-of, seen-messages, monotonic turns & code/doc/schema/test context signals"
    (with-test-store [{:keys [conn]}]
      (let [build-wire (requiring-resolve 'seon.render.ui/build-live-wire-request)
            msg-id (random-uuid)]
        (d/transact conn {:tx-data (concat (code/file-import-tx @conn "src/seon/demo/a.clj"
                                                               "(ns seon.demo.a \"Demo A subsystem\")\n(defn alpha \"Reads an agent id.\" {:malli/schema [:=> [:cat :keyword] :map]} [x] {:seon.agent/id x})" false)
                                           (code/file-import-tx @conn "src/seon/demo/b.clj"
                                                               "(ns seon.demo.b \"Demo B subsystem\")\n(defn beta [y] (inc y))" false)
                                           (code/file-import-tx @conn "test/seon/demo_test.clj"
                                                               "(ns seon.demo-test (:require [clojure.test :refer [deftest is]]))\n(deftest alpha-smoke-test (let [f (requiring-resolve 'seon.demo.a/alpha)] (is (map? (f :a)))))" false)
                                           (code/file-import-tx @conn "docs/demo.md"
                                                               "# Demo Architecture\n\n## Alpha Flow\nDetails on `seon.demo.a` invariants.\n" false)
                                           [{:seon.agent/id :ag/compact
                                             :seon.agent/branch :main
                                             :seon.agent/role :worker
                                             :seon.agent/scope 'seon.demo.a
                                             :seon.agent/goal "Test compaction"}
                                            {:seon.agent.message/id msg-id
                                             :seon.agent.message/seq 1
                                             :seon.agent.message/from :user/default
                                             :seon.agent.message/to :ag/compact
                                             :seon.agent.message/type :prompt
                                             :seon.agent.message/body "Inspect demo A"}]
                                           (for [t (range 1 7)]
                                             (cond-> {:seon.agent.eval/id (random-uuid)
                                                      :seon.agent.eval/agent :ag/compact
                                                      :seon.agent.eval/turn (long t)
                                                      :seon.agent.eval/call-index 0
                                                      :seon.agent.eval/call-id (str "c-" t)
                                                      :seon.agent.eval/code (str "(+ " t " 1)")
                                                      :seon.agent.eval/thought (apply str (repeat 40 (str "verbose turn " t " ")))
                                                      :seon.agent.eval/value (str (inc t))
                                                      :seon.agent.eval/ms 1.0
                                                      :seon.agent.eval/ticks 100}
                                               (= t 1) (assoc :seon.agent.eval/seen-messages #{msg-id}))))})
        ;; 1. Verify reaching-tests follows (requiring-resolve 'seon.demo.a/alpha)!
        (is (= ['seon.demo-test/alpha-smoke-test] (code/reaching-tests @conn 'seon.demo.a)))
        (let [pre-tx (:max-tx @conn)
              w-before (build-wire @conn @conn :ag/compact {})
              old-eids (->> (d/datoms @conn :avet :seon.agent.eval/agent :ag/compact)
                            (map #(d/pull @conn '[:db/id :seon.agent.eval/turn] (:e %)))
                            (filter #(<= (:seon.agent.eval/turn %) 5))
                            (mapv #(vector :db/retractEntity (:db/id %))))]
          (is (= 6 (count (:evals w-before))))
          (is (empty? (:unread w-before)))
          (is (= 5 (count old-eids)))
          ;; 2. Actually retract turns 1..5 in Datahike + record durable memory & self-tuned extra namespace!
          (d/transact conn {:tx-data (into [{:seon.agent/id :ag/compact
                                             :seon.agent/memory ["Alpha owns :seon.agent/id lookups"]
                                             :seon.agent/context-prefs {:extra-namespaces ['seon.demo.b] :recent-data-limit 2}}]
                                           old-eids)})
          (let [w-after (build-wire @conn @conn :ag/compact {})
                open-text (str/join "\n" (map :content (:messages w-after)))
                hist (store/agent-historical-evals @conn :ag/compact)
                stats (store/agent-eval-history-stats @conn :ag/compact)
                asof-count (count (d/datoms (d/as-of @conn pre-tx) :avet :seon.agent.eval/agent :ag/compact))]
            ;; Active DB now holds only turn 6 (true Datahike retraction, zero archive flags!)
            (is (= 1 (count (:evals w-after))))
            ;; Already-seen message on retracted turn 1 does NOT replay as unread!
            (is (empty? (:unread w-after)))
            (is (contains? (store/agent-seen-message-ids @conn :ag/compact) msg-id))
            ;; Full history survives natively via d/history & d/as-of + monotonic turn stats!
            (is (= 6 (count hist)))
            (is (= 5 (count (filter :seon.agent.eval/retracted-tx hist))))
            (is (= 6 asof-count))
            (is (= {:eval-count 6 :max-turn 6} stats))
            ;; Wire tokens shrank sharply!
            (is (< (:total-tokens w-after) (:total-tokens w-before)))
            ;; Durable memory, extra ns, :malli/schema, covering tests, linked docs & hygiene appear in <walk-context>!
            (is (str/includes? open-text "Alpha owns :seon.agent/id lookups"))
            (is (str/includes? open-text "seon.demo.b"))
            (is (str/includes? open-text "seon.demo-test/alpha-smoke-test"))
            (is (str/includes? open-text "docs/demo.md#alpha-flow"))
            (is (str/includes? open-text ":=> [:cat :keyword] :map"))))))))

(clojure.test/deftest hiccup-fragment-and-del-text-test
  (let [html (requiring-resolve 'seon.render/hiccup->html)
        txt (requiring-resolve 'seon.render/hiccup->text)]
    (testing "hiccup->html serializes a plain vector-of-elements fragment"
      (is (= "<p>a</p><p>b</p>" (html [[:p "a"] [:p "b"]])))
      (is (= "<div><h2>T</h2><p>x</p></div>" (html [:div [:h2 "T"] [:p "x"]]))))
    (testing "hiccup->text treats [:del ...] as an inline emphasis tag (space-separated, not dropped)"
      (is (= "x gone y" (txt [:p "x " [:del "gone"] " y"])))
      (is (= "a b c" (txt [:span [:strong "a"] [:del "b"] [:em "c"]])))
      (is (= "gone" (txt [:del "gone"]))))))

(deftest ^{:cost/ticks 80000000} health-efficiency-alerts-and-scope-routing-test
  (testing "Health detects nested DB scans, slow/regressed tests, low KV-cache hit & live perf faults, routing each to its owning agent scope without breaking static KV-cache prefix"
    (with-test-store [{:keys [conn]}]
      (let [health-report (requiring-resolve 'seon.health/report)
            build-wire (requiring-resolve 'seon.render.ui/build-live-wire-request)
            fid (random-uuid)]
        (d/transact conn {:tx-data (concat
                                    (code/file-import-tx @conn "src/seon/demo/bad.clj"
                                                         "(ns seon.demo.bad \"Bad scan demo\" (:require [datahike.api :as d]))\n(defn ok-outer [db] (doseq [dt (d/datoms db :aevt :seon.agent/id)] (:e dt)))\n(defn bad-nested [db attrs] (mapcat (fn [a] (d/datoms db :aevt a)) attrs))"
                                                         false)
                                    (code/file-import-tx @conn "src/seon/other/clean.clj"
                                                         "(ns seon.other.clean \"Clean peer\")\n(defn fine [x] x)"
                                                         false)
                                    [{:seon.agent/id :ns/seon.demo
                                      :seon.agent/branch :main
                                      :seon.agent/role :worker
                                      :seon.agent/scope 'seon.demo
                                      :seon.agent/goal "Own seon.demo"}
                                     {:seon.agent/id :ns/seon.other
                                      :seon.agent/branch :main
                                      :seon.agent/role :worker
                                      :seon.agent/scope 'seon.other
                                      :seon.agent/goal "Own seon.other"}
                                     ;; Test verdict 1 (fast baseline)
                                     {:seon.test/digest "0000000000000001"
                                      :seon.test/var 'seon.demo/perf-test
                                      :seon.test/kind :deftest
                                      :seon.test/pass? true
                                      :seon.test/ms 12.0
                                      :seon.test/ticks 2000000
                                      :seon.test/bytes 4000000
                                      :seon.test/report "PASS"}
                                     ;; Low KV-cache hit evals + heavy eval on :ns/seon.demo
                                     {:seon.agent.eval/id (random-uuid)
                                      :seon.agent.eval/agent :ns/seon.demo
                                      :seon.agent.eval/turn 1
                                      :seon.agent.eval/handle 'result/e1
                                      :seon.agent.eval/code "(+ 1 1)"
                                      :seon.agent.eval/bytes 150000000
                                      :seon.agent.eval/ticks 1000
                                      :seon.agent.eval/cache-hit-tokens 400
                                      :seon.agent.eval/cache-miss-tokens 2000}
                                     {:seon.agent.eval/id (random-uuid)
                                      :seon.agent.eval/agent :ns/seon.demo
                                      :seon.agent.eval/turn 2
                                      :seon.agent.eval/handle 'result/e2
                                      :seon.agent.eval/code "(+ 2 2)"
                                      :seon.agent.eval/bytes 1000
                                      :seon.agent.eval/ticks 1000
                                      :seon.agent.eval/cache-hit-tokens 500
                                      :seon.agent.eval/cache-miss-tokens 2000}])})
        ;; Second tx for regressed + slow verdict so its :tx is strictly newer
        (d/transact conn {:tx-data [{:seon.test/digest "0000000000000002"
                                     :seon.test/var 'seon.demo/perf-test
                                     :seon.test/kind :deftest
                                     :seon.test/pass? true
                                     :seon.test/ms 1400.0
                                     :seon.test/ticks 12000000
                                     :seon.test/bytes 45000000
                                     :seon.test/report "PASS"}]})
        (let [rep (health-report @conn)
              classes (set (map :class (:problems rep)))
              nested-subs (set (keep #(when (= :nested-db-scan (:class %)) (:subject %)) (:problems rep)))]
          ;; All advisories are :warning -> system stays :ok? true!
          (is (:ok? rep))
          (is (= #{"seon.demo.bad/bad-nested"} nested-subs))
          (is (contains? classes :slow-test))
          (is (contains? classes :cost-regression)))
        ;; Verify scope routing AND static KV-cache prefix stability when a live perf fault arrives & retracts!
        (let [static-prefix (fn [w] (str (:content (first (:messages w))) "\n" (:content (second (:messages w)))))
              w-demo-1 (build-wire @conn @conn :ns/seon.demo {})
              w-other  (build-wire @conn @conn :ns/seon.other {})
              prefix-1 (static-prefix w-demo-1)
              demo-act-1 (:activity-text w-demo-1)
              other-all (str/join "\n" (map :content (:messages w-other)))]
          ;; :ns/seon.demo gets static nested-db-scan in <walk-context> AND live test/cache/heavy alerts in tail!
          (is (str/includes? prefix-1 "seon.demo.bad/bad-nested"))
          (is (str/includes? demo-act-1 ":slow-test"))
          (is (str/includes? demo-act-1 ":cost-regression"))
          (is (str/includes? demo-act-1 ":low-cache-hit"))
          (is (str/includes? demo-act-1 ":heavy-eval"))
          ;; Unrelated peer :ns/seon.other sees NONE of seon.demo's alerts!
          (is (not (str/includes? other-all "seon.demo.bad/bad-nested")))
          (is (not (str/includes? other-all "seon.demo/perf-test")))
          ;; Transact a live runtime perf outlier owned by seon.demo -> appears in tail, static prefix unchanged!
          (d/transact conn {:tx-data [{:seon.health.fault/id fid
                                       :seon.health.fault/class :slow-render
                                       :seon.health.fault/severity :warning
                                       :seon.health.fault/ns 'seon.demo.bad
                                       :seon.health.fault/subject "seon.demo.bad/render"
                                       :seon.health.fault/message "Slow render 65ms"
                                       :seon.health.fault/resolved? false}]})
          (let [w-demo-2 (build-wire @conn @conn :ns/seon.demo {})]
            (is (= prefix-1 (static-prefix w-demo-2)))
            (is (str/includes? (:activity-text w-demo-2) ":slow-render")))
          ;; Auto-retract clears it cleanly while retaining it in Datahike d/history!
          (d/transact conn {:tx-data [[:db/retractEntity [:seon.health.fault/id fid]]]})
          (let [w-demo-3 (build-wire @conn @conn :ns/seon.demo {})]
            (is (not (str/includes? (:activity-text w-demo-3) ":slow-render")))
            (is (seq (d/datoms (d/history @conn) :avet :seon.health.fault/id fid))))
          ;; Failing test on an active branch surfaces as :failing-test error, and immediately clears
          ;; (invalidating report-cache-key) once all agents on that branch are archived!
          (d/transact conn {:tx-data [{:seon.agent/id :tmp/failer
                                       :seon.agent/branch :branch/tmp-fail
                                       :seon.agent/role :worker
                                       :seon.agent/goal "Tmp failer"}
                                      {:seon.test/digest "0000000000000003"
                                       :seon.test/var 'seon.demo/broken-test
                                       :seon.test/kind :deftest
                                       :seon.test/pass? false
                                       :seon.test/ms 5.0
                                       :seon.test/report "FAIL: expected true"}
                                      {:seon.test.run/branch :branch/tmp-fail
                                       :seon.test.run/pass? false
                                       :seon.test.run/failed-digests ["0000000000000003"]}]})
          (let [rep-active (health-report @conn)]
            (is (false? (:ok? rep-active)))
            (is (= 1 (get-in rep-active [:counts :failing-test]))))
          (d/transact conn {:tx-data [{:seon.agent/id :tmp/failer
                                       :seon.agent/archived? true}]})
          (let [rep-archived (health-report @conn)]
            (is (true? (:ok? rep-archived)))
            (is (nil? (get-in rep-archived [:counts :failing-test])))))))))

(deftest ^{:cost/ticks 60000000} repl-namespace-split-and-scratch-def-isolation-test
  (testing "Evaluating (ns ...) updates :seon.code.ns/requires cleanly and scratch (def x (user/...)) never pollutes source forms"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/split/core.clj"
                                                      "(ns seon.split.core \"Parent\")\n(defn helper [x] (inc x))\n(defn entry [x] (helper x))"
                                                      false)})
      ;; 1. Create child namespace in-REPL & update parent (ns ...) to require it!
      (let [r1 (code/eval-forms @conn {:agent-id :ag/split :turn 1}
                                "(ns seon.split.child \"Child\")\n(defn helper [x] (inc x))")
            _ (is (:ok? r1))
            _ (d/transact conn {:tx-data (:tx-data r1)})
            _ (intern (create-ns 'user) 'dummy-helper (constantly :ok))
            r2 (code/eval-forms @conn {:agent-id :ag/split :turn 2}
                                "(ns seon.split.core \"Parent updated\" (:require [seon.split.child :as child]))\n(defn entry [x] (child/helper x))\n(def scratch-db (user/dummy-helper))")
            _ (is (:ok? r2))
            _ (d/transact conn {:tx-data (:tx-data r2)})
            ns-ent (d/pull @conn '[:seon.code.ns/doc :seon.code.ns/requires] [:seon.code.ns/name 'seon.split.core])
            form-keys (set (map :seon.code.form/key (code/ns-forms @conn 'seon.split.core)))]
        (is (= "Parent updated" (:seon.code.ns/doc ns-ent)))
        (is (contains? (set (:seon.code.ns/requires ns-ent)) 'seon.split.child))
        ;; Scratch (def scratch-db (user/db)) did NOT persist into seon.split.core!
        (is (not (contains? form-keys "seon.split.core/scratch-db")))
        (is (contains? form-keys "seon.split.core/entry"))))))

(deftest declarative-files-roundtrip-and-runtime-preservation-test
  (seon.test-fixture/with-test-store [{:keys [conn]}]
    (let [extra-schemas {:seon.agent/entry? [:boolean {:seon.db/index true}]
                         :seon.agent/label :string
                         :seon.agent/hero-title :string
                         :seon.agent/read-branch :keyword
                         :seon.agent/router? :boolean
                         :seon.agent/seed-path :string
                         :seon.config/auto-switch-over? :boolean
                         :seon.agent/entity-extra
                         [:map {:seon.db/attributes true}
                          [:seon.agent/entry? {:optional true} :seon.agent/entry?]
                          [:seon.agent/label {:optional true} :seon.agent/label]
                          [:seon.agent/hero-title {:optional true} :seon.agent/hero-title]
                          [:seon.agent/read-branch {:optional true} :seon.agent/read-branch]
                          [:seon.agent/router? {:optional true} :seon.agent/router?]
                          [:seon.agent/seed-path {:optional true} :seon.agent/seed-path]
                          [:seon.config/auto-switch-over? {:optional true} :seon.config/auto-switch-over?]]}
          reg-info (seon.schema/registry-for-db @conn extra-schemas)
          new-dh (remove #(contains? (:schema @conn) (:db/ident %)) (:declarations reg-info))]
      (when (seq new-dh)
        (d/transact conn {:tx-data (vec new-dh)})))

    (testing "1. Tasks file imports into :seon.task/* and renders back losslessly"
      (let [tpath "resources/seon/tasks.edn"
            src "[{:seon.task/id :seon.demo/split-ui :seon.task/scope seon.demo :seon.task/title \"Split UI\" :seon.task/plan \"Extract tiles\" :seon.task/done? false}]"
            tx (code/file-import-tx @conn tpath src false)]
        (d/transact conn {:tx-data tx})
        (let [pulled (d/pull @conn '[:seon.task/id :seon.task/title :seon.task/done?] [:seon.task/id :seon.demo/split-ui])
              rendered (code/render-path @conn tpath)
              reparsed (clojure.edn/read-string rendered)]
          (is (= "Split UI" (:seon.task/title pulled)))
          (is (= 1 (count reparsed)))
          (is (= :seon.demo/split-ui (:seon.task/id (first reparsed))))
          (is (= tpath (merge/entity->disk-path @conn @conn :seon.task/id :seon.demo/split-ui pulled))))))

    (testing "2. Agent seed file imports durable data, renders back, and NEVER clobbers live runtime state on re-import"
      (let [apath "resources/seon/agents/custom-root.edn"
            src "[{:seon.agent/id :agent/custom-root :seon.agent/entry? true :seon.agent/label \"Home\" :seon.agent/hero-title \"Ask anything\" :seon.agent/branch :branch/custom-root :seon.agent/read-branch :main :seon.agent/router? true :seon.agent/role :coordinator :seon.agent/scope seon :seon.agent/focus [:seon.code.ns/name seon] :seon.agent/goal \"Route work\" :seon.agent/system-prompt \"Farm work to specialists.\" :seon.agent/render-fn seon.render.ui/agent-canvas :seon.agent/waiting? true :seon.agent/auto-merge? false}]"
            tx1 (code/file-import-tx @conn apath src false)]
        (d/transact conn {:tx-data tx1})
        (d/transact conn {:tx-data [{:seon.agent/id :agent/custom-root
                                     :seon.agent/waiting? false
                                     :seon.agent/memory ["Remembered fact"]
                                     :seon.agent/canvas {:title "Live Chart"}}]})
        (let [src2 (str/replace src "Route work" "Route work v2")
              tx2 (code/file-import-tx @conn apath src2 false)]
          (d/transact conn {:tx-data tx2})
          (let [ag (d/pull @conn '[*] [:seon.agent/id :agent/custom-root])
                rendered (code/render-path @conn apath)
                reparsed (clojure.edn/read-string rendered)]
            (is (= "Route work v2" (:seon.agent/goal ag)))
            (is (= false (:seon.agent/waiting? ag)))
            (is (= ["Remembered fact"] (:seon.agent/memory ag)))
            (is (= {:title "Live Chart"} (:seon.agent/canvas ag)))
            (is (= apath (merge/entity->disk-path @conn @conn :seon.agent/id :agent/custom-root ag)))
            (is (= "Home" (:seon.agent/label (first reparsed))))
            (is (= [:seon.code.ns/name 'seon] (:seon.agent/focus (first reparsed))))
            (is (not (contains? (first reparsed) :seon.agent/canvas)))))))

    (testing "3. Config preserves Aero text when unchanged and renders updated EDN when changed in DB"
      (let [cpath "resources/seon/config/default.edn"
            aero-txt "{:seon.config/fast-test-ms #long #or [#env SEON_FAST_TEST_MS 150]}"
            tx (code/file-import-tx @conn cpath aero-txt false)]
        (d/transact conn {:tx-data tx})
        (is (= aero-txt (code/render-path @conn cpath)))
        (d/transact conn {:tx-data [{:seon.config/key :seon.config/root :seon.config/fast-test-ms 250}]})
        (let [rendered (code/render-path @conn cpath)
              parsed (clojure.edn/read-string rendered)]
          (is (not= aero-txt rendered))
          (is (= 250 (:seon.config/fast-test-ms parsed)))
          ;; Verify blind-delete refusal when render-path returns nil without explicit :deleted-paths
          (is (thrown-with-msg? Throwable #"Refusing blind deletion"
                (seon.git/write-and-commit-main! @conn seon.store/checkout-root nil
                  #{"src/nonexistent/ghost.clj"} "should refuse" nil #{}))))))))

(deftest ^{:cost/ticks 40000000} lossless-namespace-move-vars-refactor-test
  (testing "seon.code.refactor/move-vars-tx checks unresolved deps, supports :include-deps? & :dry-run?, promotes defn-, re-exports public fns preserving fn->qualified-sym & ^:seon/callable, and shrinks source ns"
    (seon.test-fixture/with-test-store [{:keys [conn]}]
      (let [src "(ns seon.demo.big\n  \"Big ns\"\n  (:require [clojure.string :as str]))\n\n(defn- helper [x]\n  (str/upper-case (str x)))\n\n(defn ^:seon/callable greet [x]\n  (str \"Hi \" (helper x)))\n\n(defn entry [x]\n  (greet x))\n"]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/big.clj" src false)})
        ;; 1. Without :include-deps?, moving `greet` without `helper` is caught upfront!
        (let [bad (seon.code.refactor/move-vars-tx @conn {:from 'seon.demo.big :to 'seon.demo.big.sub :vars '[greet]})]
          (is (not (:ok? bad)))
          (is (= :unresolved-from-deps (:error bad)))
          (is (= ['seon.demo.big/helper] (:missing-deps bad))))
        ;; 2. With :include-deps? true & :dry-run? true, previews transitive closure with 0 mutations!
        (let [dry (seon.code.refactor/apply-move-vars! conn {:from 'seon.demo.big :to 'seon.demo.big.sub :alias 'sub :vars '[greet] :include-deps? true :dry-run? true})]
          (is (:ok? dry))
          (is (:dry-run? dry))
          (is (= ['helper 'greet] (:moved dry)))
          (is (empty? (code/ns-forms @conn 'seon.demo.big.sub))))
        ;; 3. Applying with :include-deps? true moves both atomically, promotes `helper`, re-exports `^:seon/callable greet`, and compiles cleanly!
        (let [res (seon.code.refactor/apply-move-vars! conn {:from 'seon.demo.big :to 'seon.demo.big.sub :alias 'sub :vars '[greet] :include-deps? true})
              outline (seon.code.refactor/ns-form-outline @conn 'seon.demo.big)
              from-rendered (code/render-path @conn "src/seon/demo/big.clj")
              to-rendered (code/render-path @conn "src/seon/demo/big/sub.clj")]
          (is (:ok? res) (pr-str res))
          (is (empty? (:load-errors res)))
          (is (= ['greet] (:re-exported res)))
          (is (str/includes? to-rendered "(defn helper"))
          (is (str/includes? from-rendered "[seon.demo.big.sub :as sub]"))
          (is (str/includes? from-rendered "(defn ^:seon/callable greet [& args] (apply sub/greet args))"))
          (is (= "Hi WORLD" ((resolve 'seon.demo.big/entry) "world")))
          (is (true? (:seon/callable (meta (resolve 'seon.demo.big/greet)))))
          (is (= 'seon.demo.big/greet ((requiring-resolve 'seon.render/fn->qualified-sym) @(resolve 'seon.demo.big/greet))))
          (is (< (:total-tokens outline) (quot (count src) 4))))))))

(deftest activity-fold-progressive-disclosure-test
  (testing "render-activity-fold renders a connected step list (eval text + ms only, no leading handle pill) with separate expandable Thinking/Note/Code/Result drawers, and hiccup supports :style maps + compile-css"
    (let [long-reason (str "First sentence explains why we delegate cleanly. " (apply str (repeat 40 "More detailed chain-of-thought reasoning about internals. ")))
          long-code (str/join "\n" (for [i (range 30)] (str "(def line-" i " " i ")")))
          long-val (pr-str (into {} (for [i (range 40)] [(keyword (str "k" i)) (str "value-" i)])))
          evs [{:seon.agent.eval/handle 'result/e1
                :seon.agent.eval/reasoning "Short crisp thought."
                :seon.agent.eval/thought "Narrated step note."
                :seon.agent.eval/intent "Run quick check"
                :seon.agent.eval/code "(+ 1 2)"
                :seon.agent.eval/value "3"
                :seon.agent.eval/ms 1.2}
               {:seon.agent.eval/handle 'result/e2
                :seon.agent.eval/reasoning long-reason
                :seon.agent.eval/intent "Build large canvas"
                :seon.agent.eval/code long-code
                :seon.agent.eval/value long-val
                :seon.agent.eval/ms 14.5}]
          h (seon.render.markdown/render-activity-fold evs "cv")
          html (seon.render/hiccup->html h)]
      (is (str/includes? html "Thinking &amp; actions · 2 steps"))
      (is (str/includes? html "class=\"activity-list\""))
      (is (str/includes? html "class=\"step-title\">Run quick check</span>"))
      (is (str/includes? html "class=\"step-ms\">1.2 ms</span>"))
      ;; Handle pill is inside the expanded step-drawer, NOT in step-summary
      (let [sum-idx (.indexOf html "class=\"step-summary\"")
            draw-idx (.indexOf html "class=\"step-drawer\"")
            h-idx (.indexOf html "result/e1")]
        (is (< sum-idx draw-idx h-idx) "Technical result/e1 handle must live inside .step-drawer, not on .step-summary!"))
      ;; Separate expandable drawers exist inside step-drawer
      (is (str/includes? html "id=\"cv-step-0-reason\""))
      (is (str/includes? html "id=\"cv-step-0-note\""))
      (is (str/includes? html "id=\"cv-step-0-code\""))
      (is (str/includes? html "id=\"cv-step-0-val\""))
      (is (str/includes? html "id=\"cv-step-1-reason\""))
      (is (str/includes? html "First sentence explains why we delegate cleanly."))
      (is (str/includes? html "id=\"cv-step-1-code-full\""))
      (is (str/includes? html "Show full code ("))
      (is (str/includes? html "id=\"cv-step-1-val-full\""))
      (is (str/includes? html "Show full result ("))
      ;; Style map serialization in hiccup->html + pure Clojure compile-css
      (is (= "<div style=\"color:red;font-size:20px;margin-top:0\">hi</div>"
             (seon.render/hiccup->html [:div {:style {:color "red" :font-size "20px" :margin-top 0}} "hi"])))
      (is (str/includes? (seon.web.styles/compile-css [[".demo" {:color "red" :padding "4px"}]])
                         ".demo{color:red;padding:4px}")))))

(deftest structural-edit-fn-and-unused-requires-test
  (testing "seon.code.cst/patch-form-source matches structurally ignoring tabs/spaces/newlines/comments, handles sibling slices, reader macros, ambiguity, and syntax errors"
    (let [fn-src "(defn compute\n  \"Doc\"\n  [{:keys [a b]}]\n  ;; keep top comment\n  (let [x (+ a\n             ;; inline comment\n             1)\n        y (* b 2)\n        z #(assoc % ::local/val (+ x y))]\n    (cond\n      (zero? x) :zero\n      (pos? x)  (z {:ok true})\n      :else     :neg)))"
          r1 (seon.code.cst/patch-form-source fn-src "(+ a\t1)" "(+ a 10)")
          r2 (seon.code.cst/patch-form-source fn-src "y (* b\n\t2)" "y (* b 3)\nextra 99")
          r3 (seon.code.cst/patch-form-source fn-src "#(assoc % ::local/val (+ x y))" "#(assoc % ::local/val (* x y))")
          r-bad (seon.code.cst/patch-form-source fn-src "(if (zero? x)" "(if (pos? x)")
          r-amb (seon.code.cst/patch-form-source "(defn dup [x] [(+ x 1) (+ x 1)])" "(+ x 1)" "(+ x 2)")
          r-occ (seon.code.cst/patch-form-source "(defn dup [x] [(+ x 1) (+ x 1)])" "(+ x 1)" "(+ x 2)" {:occurrence 2})]
      (is (:ok? r1))
      (is (str/includes? (:src r1) ";; keep top comment"))
      (is (str/includes? (:src r1) "(+ a 10)"))
      (is (:ok? r2))
      (is (str/includes? (:src r2) "        y (* b 3)\n        extra 99"))
      (is (:ok? r3))
      (is (str/includes? (:src r3) "(* x y)"))
      (is (= :invalid-clojure-syntax (:error r-bad)))
      (is (= :ambiguous-match (:error r-amb)))
      (is (= 2 (:count r-amb)))
      (is (= "(defn dup [x] [(+ x 1) (+ x 2)])" (:src r-occ)))))
  (testing "seon.code.cst/prune-unused-requires-src strips unused aliases while keeping used aliases, refers, and bare side-effect requires"
    (let [sample "(ns demo.clean\n  (:require [clojure.string :as str]\n            [clojure.set :as set]\n            [jolt.host]\n            [demo.util :as u :refer [helper]]))\n\n(defn f [x]\n  \"mentions set/union only in docstring\"\n  (helper (str/trim x)))\n"
          pruned (seon.code.cst/prune-unused-requires-src sample)]
      (is (:ok? pruned))
      (is (:changed? pruned))
      (is (= ["clojure.set"] (:removed pruned)))
      (is (= ["clojure.string" "jolt.host" "demo.util"] (:kept pruned)))
      (is (not (str/includes? (:src pruned) "clojure.set")))
      (is (str/includes? (:src pruned) "[jolt.host]"))
      (let [src "(defn f [a k] (get @a k))"
            quoted-old (binding [*print-meta* true] (pr-str '(get @a k)))
            res (seon.code.cst/patch-form-source src quoted-old "(get @a k :missing)")]
        (is (:ok? res) (pr-str res))
        (is (str/includes? (:src res) ":missing")))
      (let [legacy (atom {:old 1})]
        (is (= 42 (seon.store/lru-get! legacy 2 :a (fn [] 42))))
        (is (= false (seon.store/lru-get! legacy 2 :b (fn [] false))))
        (is (= 99 (seon.store/lru-get! legacy 2 :c (fn [] 99))))
        (is (= 2 (count @legacy))))
      (let [ns-s "(ns demo.x\n  (:require [clojure.string :as str]))"
            r-add (seon.code.cst/add-require-src ns-s '[seon.store :as store])
            r-dup (seon.code.cst/add-require-src (:src r-add) '[seon.store :as store])
            reqs  (seon.code.cst/parse-ns-requires (:src r-add))
            fn-doc "(defn greet\n  \"Says hi.\"\n  ([x] (greet x \"!\"))\n  ([x p] (str x p)))"
            fn-hint "(defn trim-it [^String s]\n  (str/trim s))"
            m1 (seon.code.cst/set-defn-meta-src fn-doc {:malli/schema [:=> [:cat :string] :string] :cost/ticks 500000})
            m2 (seon.code.cst/set-defn-meta-src (:src m1) {:cost/bytes 1048576})
            m3 (seon.code.cst/set-defn-meta-src fn-hint {:malli/schema [:=> [:cat :string] :string]})
            stripped (seon.code.cst/strip-reader-meta (with-meta '(+ 1 2) {:line 10 :column 3 :private true}))]
        (is (:changed? r-add))
        (is (not (:changed? r-dup)))
        (is (= ["clojure.string" "seon.store"] (mapv :lib reqs)))
        (is (= :inserted (:action m1)))
        (is (< (.indexOf ^String (:src m1) "Says hi.") (.indexOf ^String (:src m1) ":malli/schema"))
            "attr-map must ALWAYS be inserted AFTER docstring!")
        (is (= :merged (:action m2)))
        (is (str/includes? (:src m2) ":cost/bytes 1048576"))
        (is (str/includes? (:src m3) "[^String s]"))
        (is (not (str/includes? (:src m3) "#:malli")) "Must emit standard {:malli/schema ...}, never #:malli{...}")
        (is (= {:private true} (meta stripped)))
        (is (string? (:doc (seon.repl/help 'edit-fn!))))))))

(deftest tail-renders-contract-gate-and-autofix-test
  (testing "1. Data-driven :seon.agent/tail-renders + :seon.config/viewport-screenshot? gating"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data [{:seon.agent/id :test/coord
                                   :seon.agent/branch :main
                                   :seon.agent/role :coordinator
                                   :seon.agent/scope 'seon
                                   :seon.agent/tail-renders ['seon.render.tail/system-health
                                                             'seon.render.tail/live-viewport]
                                   :seon.config/viewport-screenshot? true}
                                  {:seon.agent/id :test/worker
                                   :seon.agent/branch :branch/tw
                                   :seon.agent/role :worker
                                   :seon.agent/scope 'seon.demo
                                   :seon.config/viewport-screenshot? false}]})
      (store/branch! conn :main :branch/tw)
      (let [cw (store/connect cfg :branch/tw)]
        (try
          (d/transact cw {:tx-data (code/file-import-tx @cw "src/seon/demo/u.clj"
                                                        "(ns seon.demo.u (:require [clojure.set :as set]))\n(defn uncontracted [x] (inc x))\n"
                                                        false)})
          (let [fake-shot {:route "/" :width 1280 :height 820 :mime "image/jpeg" :base64 "AAAA"}
                w-coord (seon.render.wire/build-live-wire-request @conn @conn :test/coord {:viewport-screenshot fake-shot :vision? true :conn conn})
                w-work  (seon.render.wire/build-live-wire-request @conn @cw :test/worker {:viewport-screenshot fake-shot :vision? true :conn conn})
                plan-bad (merge/three-way conn cfg :branch/tw)]
            (is (= ["system-health" "live-viewport"] (mapv :tag (:tail-blocks w-coord))))
            (is (some? (:ephemeral-screenshot w-coord)) "Coordinator with viewport-screenshot? true receives screenshot")
            (is (= ["branch-status" "scoped-health"] (mapv :tag (:tail-blocks w-work))) "Worker with viewport-screenshot? false omits live-viewport")
            (is (nil? (:ephemeral-screenshot w-work)) "0 image tokens when viewport-screenshot? is false")
            (is (str/includes? (str (:activity-text w-work)) "missing-contract") "branch-status surfaces merge-blocking :missing-contract")
            (is (str/includes? (str (:activity-text w-work)) "unused-require") "scoped-health surfaces auto-fixable :unused-require")
            (is (false? (:ok? plan-bad)) "Uncontracted public defn MUST block merge!")
            (is (some #(= :missing-contract (:reason %)) (:conflicts plan-bad))))
          (d/transact cw {:tx-data (code/file-import-tx @cw "src/seon/demo/u.clj"
                                                        "(ns seon.demo.u)\n(defn uncontracted {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                                                        false)})
          (let [plan-ok (merge/three-way conn cfg :branch/tw)]
            (is (:ok? plan-ok) (str "Contracted public defn MUST merge cleanly: " (pr-str (:conflicts plan-ok)))))
          (finally
            (d/release cw))))))

  (testing "2. Programmatic :unused-require health check carries executable :fix descriptor"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/req.clj"
                                                      "(ns seon.demo.req (:require [clojure.set :as set] [clojure.string :as str]))\n(defn f {:malli/schema [:=> [:cat :string] :string]} [s] (str/trim s))\n"
                                                      false)})
      (let [rep (seon.health/report @conn)
            ur (some #(when (and (= :unused-require (:class %)) (= 'seon.demo.req (:ns %))) %) (:problems rep))]
        (is (some? ur))
        (is (= ['clojure.set] (:unused ur)))
        (is (= {:fn 'seon.code.edit/prune-requires! :args ['seon.demo.req]} (:fix ur)))))))

(deftest auto-fix-on-merge-broken-refs-examples-and-cli-test
  (testing "1. Merge automatically prunes unused :require AND auto-reorders non-cyclic forward refs"
    (with-test-store [{:keys [conn cfg]}]
      (store/branch! conn :main :branch/af)
      (let [cb (store/connect cfg :branch/af)]
        (try
          (d/transact cb {:tx-data (code/file-import-tx
                                    @cb "src/seon/demo/af.clj"
                                    "(ns seon.demo.af (:require [clojure.set :as set] [clojure.string :as str]))\n\n(defn caller {:malli/schema [:=> [:cat :string] :string]} [s] (helper s))\n\n(defn helper {:malli/schema [:=> [:cat :string] :string]} [s] (str/trim s))\n"
                                    false)})
          (let [plan (merge/three-way conn cfg :branch/af)
                af-classes (set (map :class (:auto-fixed plan)))]
            (is (:ok? plan) (str "Auto-fix should resolve unused require + non-cyclic order: " (pr-str (:conflicts plan))))
            (is (contains? af-classes :unused-require) "Unused clojure.set auto-pruned on merge")
            (is (contains? af-classes :used-before-defined) "Non-cyclic forward ref auto-reordered on merge")
            (d/transact conn {:tx-data (:tx-data plan)})
            (let [rendered (code/render-path @conn "src/seon/demo/af.clj")]
              (is (not (str/includes? rendered "clojure.set")))
              (is (< (str/index-of rendered "(defn helper") (str/index-of rendered "(defn caller")))))
          (finally
            (d/release cb))))))

  (testing "2. Referential integrity gate blocks broken :seon.agent/tail-renders symbols on merge"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (code/file-import-tx
                                  @conn "src/seon/demo/ok.clj"
                                  "(ns seon.demo.ok)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                                  false)})
      (store/branch! conn :main :branch/bad-ref)
      (let [cb (store/connect cfg :branch/bad-ref)]
        (try
          (d/transact cb {:tx-data [{:seon.agent/id :test/bad-tail
                                     :seon.agent/branch :branch/bad-ref
                                     :seon.agent/role :worker
                                     :seon.agent/tail-renders ['seon.nonexistent/missing-tail-fn]}]})
          (let [plan (merge/three-way conn cfg :branch/bad-ref)]
            (is (false? (:ok? plan)))
            (is (some #(= :broken-symbol-ref (:reason %)) (:conflicts plan))))
          (finally
            (d/release cb))))))

  (testing "3. Malli-native :examples + :cost/ms checked deterministically in schema/stress-var"
    (let [ok-res (schema/stress-var nil 'seon.main/parse-cli-options {:samples 10 :seed 42 :measure-fn code/measure-call})
          bad-cli (seon.main/parse-cli-options ["--unknown-flag"])
          good-cli (seon.main/parse-cli-options ["--port" "0" "--headless" "--seed-store" "/tmp/s" "--model-base-url" "http://127.0.0.1:8888" "--auto-switch-over" "false"])]
      (is (:ok? ok-res) (str "parse-cli-options examples + seed=42 samples must pass: " (pr-str ok-res)))
      (is (seq (:errors bad-cli)))
      (is (nil? (:errors good-cli)))
      (is (= {:port 0 :headless true :seed-store "/tmp/s" :model-base-url "http://127.0.0.1:8888" :auto-switch-over false}
             (:options good-cli))))))

(deftest ^{:cost/ms 2500} enforcements-doc-refs-impure-gen-malli-examples-and-nudge-test
  (testing "Per-var IR refs, :broken-doc-ref, :impure-generatable-fn, Malli-native :examples, and call :protocol-nudge"
    (with-test-store [{:keys [conn cfg]}]
      (let [br :branch/enf-test]
        (store/branch! conn :main br)
        ;; 1. Multi-form eval keeps per-var IR refs isolated (no smearing across siblings)
        ;;    and catches a broken docstring ref + an impure generatable fn!
        (let [bconn (store/connect cfg br)]
          (try
            (let [ev (code/eval-forms @bconn
                                      {:agent-id :ag/enf :turn 1 :default-ns 'seon.enf.demo}
                                      (str "(ns seon.enf.demo)\n"
                                           "(defn ^:seon.flow/edge do-io! \"Performs IO.\" {:malli/schema [:=> [:cat :seon.host/atom] :any]} [a] (swap! a inc))\n"
                                           "(defn pure-fn \"Calls `seon.enf.demo/missing-var`.\" {:malli/schema [:=> {:examples [[[2] 4]]} [:cat :int] :int]} [x] (* 2 x))\n"
                                           "(defn bad-gen \"Accidentally impure.\" {:malli/schema [:=> [:cat :int] :any]} [x] (do-io! (atom x)))"))]
              (is (:ok? ev))
              (d/transact bconn {:tx-data (:tx-data ev)})
              (let [bdb @bconn
                    drefs (seon.health/broken-doc-refs bdb)
                    imps  (seon.health/impure-generatable-fns bdb)]
                (is (some #(= "seon.enf.demo/pure-fn->seon.enf.demo/missing-var" (:subject %)) drefs)
                    "broken-doc-refs catches missing backticked var in docstring")
                (is (not-any? #(= 'seon.enf.demo/pure-fn (:var %)) imps)
                    "per-var IR ref isolation keeps pure-fn free of sibling edge refs")
                (is (some #(= 'seon.enf.demo/bad-gen (:var %)) imps)
                    "impure-generatable-fns catches generatable pure schema calling ^:seon.flow/edge var")
                ;; Malli-native :examples property executes in stress-var
                (let [sr (schema/stress-var bdb 'seon.enf.demo/pure-fn {:samples 20 :seed 42})]
                  (is (:ok? sr))
                  (is (= 21 (:ran sr))))))
            (finally
              (d/release bconn))))

        ;; 2. Reconciler emits 1-time :protocol-nudge before :fail-call when callee stops without answering schemed call
        (let [mid (random-uuid)
              cid (random-uuid)
              aid :ag/callee-nudge
              abr :branch/callee-nudge]
          (store/branch! conn :main abr)
          (d/transact conn {:tx-data [{:seon.agent/id aid :seon.agent/branch abr :seon.agent/role :worker :seon.agent/goal "Answer call"}
                                      {:seon.agent.message/id mid :seon.agent.message/seq 1 :seon.agent.message/from :agent/root
                                       :seon.agent.message/to aid :seon.agent.message/type :call :seon.agent.message/body "Compute result"}
                                      {:seon.agent.call/id cid :seon.agent.call/caller :agent/root :seon.agent.call/callee aid
                                       :seon.agent.call/message-id mid :seon.agent.call/prompt "Compute result"
                                       :seon.agent.call/result-schema :seon.health/report :seon.agent.call/status :pending}]})
          (let [bconn (store/connect cfg abr)]
            (try
              (d/transact bconn {:tx-data [{:seon.agent/id aid :seon.agent/waiting? true}
                                           {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent aid :seon.agent.eval/turn 1
                                            :seon.agent.eval/handle 'result/e1 :seon.agent.eval/code "" :seon.agent.eval/thought "The answer is 42"
                                            :seon.agent.eval/seen-messages #{mid}}]})
              (let [st1 (seon.reconciler/derive-agent-status @conn (:store @conn) {} #{} #{} #{} aid)
                    ops1 (:auto-settle-ops st1)]
                (is (= 1 (count ops1)))
                (is (= :send-message (:op (first ops1))))
                (is (= :protocol-nudge (:type (first ops1))))
                ;; Once nudged and seen without answering, second pass cleanly fires :fail-call
                (let [nmid (random-uuid)]
                  (d/transact conn {:tx-data [{:seon.agent.message/id nmid :seon.agent.message/seq 2 :seon.agent.message/from :system
                                               :seon.agent.message/to aid :seon.agent.message/type :protocol-nudge
                                               :seon.agent.message/body (:body (first ops1))}]})
                  (d/transact bconn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent aid :seon.agent.eval/turn 2
                                                :seon.agent.eval/handle 'result/e2 :seon.agent.eval/code "" :seon.agent.eval/thought "Still prose"
                                                :seon.agent.eval/seen-messages #{nmid}}]})
                  (let [st2 (seon.reconciler/derive-agent-status @conn (:store @conn) {} #{} #{} #{} aid)]
                    (is (= :fail-call (:op (first (:auto-settle-ops st2)))))
                    (is (true? (get-in (first (:auto-settle-ops st2)) [:error :nudged?]))))))
              (finally
                (d/release bconn)))))))))

(deftest ^{:cost/ms 2500} budget-override-archive-and-unified-examples-test
  (testing "Declarative :seon.eval/budget-override lifts eval-forms limits for annotated vars only"
    (with-test-store [{:keys [conn cfg]}]
      (let [br :branch/budget-ov]
        (store/branch! conn :main br)
        (let [bconn (store/connect cfg br)]
          (try
            ;; Define a var with explicit :seon.eval/budget-override and a normal var without one
            (let [ev0 (code/eval-forms @bconn {:agent-id :ag/ov :turn 1 :default-ns 'seon.ov.demo}
                                       (str "(ns seon.ov.demo)\n"
                                            "(defn normal-work \"Allocates a small vector.\" {:malli/schema [:=> [:cat :int] :int]} [n] (count (vec (range n))))\n"
                                            "(defn boosted-work \"Explicit variable-budget work.\" {:malli/schema [:=> [:cat :int] :int] :seon.eval/budget-override {:ticks 50000000 :bytes 67108864}} [n] (count (vec (range n))))"))]
              (is (:ok? ev0))
              (d/transact bconn {:tx-data (:tx-data ev0)})
              ;; Tight 1000-tick cap blocks normal-work, while boosted-work runs cleanly via :seon.eval/budget-override!
              (let [tight-normal (code/eval-forms @bconn {:agent-id :ag/ov :turn 2 :default-ns 'seon.ov.demo :tick-budget 5000 :byte-budget 65536}
                                                  "(normal-work 2000)")
                    tight-boosted (code/eval-forms @bconn {:agent-id :ag/ov :turn 3 :default-ns 'seon.ov.demo :tick-budget 5000 :byte-budget 65536}
                                                   "(boosted-work 2000)")]
                (is (not (:ok? tight-normal)) "Normal unannotated var stays strictly bounded")
                (is (:ok? tight-boosted) (str "Annotated :seon.eval/budget-override var succeeds: " (:error tight-boosted)))
                (is (= 2000 (:value tight-boosted)))))
            (finally
              (d/release bconn)))))))

  (testing ":seon.agent/archived? hides archived agents from reconciler, sidebar, and status snapshot"
    (with-test-store [{:keys [conn cfg]}]
      (store/branch! conn :main :branch/arch-a)
      (store/branch! conn :main :branch/live-a)
      (d/transact conn {:tx-data [{:seon.agent/id :ag/archived :seon.agent/branch :branch/arch-a
                                   :seon.agent/role :worker :seon.agent/goal "Done" :seon.agent/archived? true}
                                  {:seon.agent/id :ag/live :seon.agent/branch :branch/live-a
                                   :seon.agent/role :worker :seon.agent/goal "Active"}]})
      (let [db @conn
            snap (server/system-status-snapshot conn cfg nil)]
        (is (nil? (seon.reconciler/derive-agent-status db (:store db) {} #{} #{} :ag/archived)))
        (is (some? (seon.reconciler/derive-agent-status db (:store db) {} #{} #{} :ag/live)))
        (is (= [:ag/live] (mapv :id (:agents snap))))
        (is (= 1 (:archived-agents snap)))
        (is (not (some #{:branch/arch-a} (:branches snap))))
        (is (= 1 (:archived-branches snap)))))))

(deftest ^{:cost/ticks 80000000} kv-cache-prefix-stability-volatility-ordering-and-eval-preview-test
  (testing "1. Static <walk-context> & :prefix-hash remain byte-identical across branch edits + renderable evals, and refresh cleanly on memory update"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (concat
                                  (code/file-import-tx @conn "src/seon/demo/kv.clj"
                                                       "(ns seon.demo.kv \"KV cache demo\")\n(defn step {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                                                       false)
                                  [{:seon.agent/id :test/kv-w
                                    :seon.agent/branch :branch/kv-w
                                    :seon.agent/role :worker
                                    :seon.agent/scope 'seon.demo.kv
                                    :seon.agent/goal "Refactor kv cleanly"}])})
      (store/branch! conn :main :branch/kv-w)
      (let [cw (store/connect cfg :branch/kv-w)]
        (try
          (let [w1 (seon.render.wire/build-live-wire-request @conn @cw :test/kv-w {:conn conn})
                h1 (:prefix-hash w1)]
            (is (and (string? h1) (= 16 (count h1))))
            (d/transact cw {:tx-data (concat
                                      (code/file-import-tx @cw "src/seon/demo/kv.clj"
                                                           "(ns seon.demo.kv \"KV cache demo\")\n(defn step {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))\n(defn extra {:malli/schema [:=> [:cat :int] :int]} [x] (* x 3))\n"
                                                           false)
                                      [{:seon.agent.eval/id (random-uuid)
                                        :seon.agent.eval/agent :test/kv-w
                                        :seon.agent.eval/turn 1
                                        :seon.agent.eval/call-index 0
                                        :seon.agent.eval/handle 'result/e1
                                        :seon.agent.eval/result-schema :seon.health/report
                                        :seon.agent.eval/code "(user/health)"
                                        :seon.agent.eval/value "{:ok? true :problems []}"}])})
            (let [w2 (seon.render.wire/build-live-wire-request @conn @cw :test/kv-w {:conn conn})]
              (is (= h1 (:prefix-hash w2)) "Branch code edits & renderable evals MUST NOT bust static :prefix-hash!")
              (is (not (some #(= :eval (:source %)) (:tiles w2))) "Mutable :eval tiles must stay out of static <walk-context>!")
              (is (str/includes? (str (:activity-text w2)) "seon.demo.kv/extra") "Ephemeral <branch-status> tail still sees live edits!"))
            (d/transact cw {:tx-data [{:seon.agent/id :test/kv-w :seon.agent/memory ["Remembered invariant"]}]})
            (let [w3 (seon.render.wire/build-live-wire-request @conn @cw :test/kv-w {:conn conn})]
              (is (not= h1 (:prefix-hash w3)))
              (is (str/includes? (:content (second (:messages w3))) "Remembered invariant"))))
          (finally (d/release cw))))))

  (testing "2. :prefix-cache-bust fires on consecutive prefix-hash change or hit drop, never across compaction gaps"
    (with-test-store [{:keys [conn]}]
      (when-not (contains? (:schema @conn) :seon.agent.eval/prefix-hash)
        (d/transact conn {:tx-data [{:db/ident :seon.agent.eval/prefix-hash
                                     :db/valueType :db.type/string
                                     :db/cardinality :db.cardinality/one}]}))
      (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/bust :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/code "1" :seon.agent.eval/prefix-hash "aaaaaaaaaaaaaaaa" :seon.agent.eval/cache-hit-tokens 4000}
                                  {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/bust :seon.agent.eval/turn 2 :seon.agent.eval/call-index 0 :seon.agent.eval/code "2" :seon.agent.eval/prefix-hash "bbbbbbbbbbbbbbbb" :seon.agent.eval/cache-hit-tokens 1800}
                                  {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/gap :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/code "1" :seon.agent.eval/prefix-hash "aaaaaaaaaaaaaaaa"}
                                  {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/gap :seon.agent.eval/turn 5 :seon.agent.eval/call-index 0 :seon.agent.eval/code "5" :seon.agent.eval/prefix-hash "cccccccccccccccc"}]})
      (let [w-bust (seon.health.efficiency/eval-efficiency-warnings @conn :ag/bust)
            w-gap  (seon.health.efficiency/eval-efficiency-warnings @conn :ag/gap)]
        (is (some #(= :prefix-cache-bust (:class %)) w-bust))
        (is (not (some #(= :prefix-cache-bust (:class %)) w-gap))))))

  (testing "3. Empirical tail-block volatility ordering puts stable blocks before volatile blocks"
    (let [vol-n (atom 0)
          tns (create-ns 'seon.test.vol)
          _ (intern tns 'volatile-blk (fn [_] {:tag "vol" :text (str "tick-" (swap! vol-n inc))}))
          _ (intern tns 'stable-blk (fn [_] {:tag "stb" :text "always-same"}))
          fake-db {:config {:store {:id (random-uuid)}}}
          ctx {:main-db fake-db
               :branch-db fake-db
               :agent {:seon.agent/id :ag/vol
                       :seon.agent/tail-renders ['seon.test.vol/volatile-blk 'seon.test.vol/stable-blk]}}]
      (let [r1 (rtail/render-tail-blocks ctx)
            r2 (rtail/render-tail-blocks ctx)
            stats (rtail/volatility-stats fake-db)]
        (is (= ["vol" "stb"] (mapv :tag (:blocks r1))) "First render preserves declared order")
        (is (= ["stb" "vol"] (mapv :tag (:blocks r2))) "After observing transition, stable block moves ahead of volatile block!")
        (is (= 1.0 (get-in stats ['seon.test.vol/volatile-blk :rate])))
        (is (= 0.0 (get-in stats ['seon.test.vol/stable-blk :rate]))))))

  (testing "4. format-eval-output avoids double-escaping and summarizes huge values structurally via result/e<n>"
    (let [src-out (mwire/format-eval-output {:handle 'result/e2 :ok? true :ms 2.0 :ticks 4000 :value (pr-str "(defn f [x]\n  (inc x))")})
          big-map (into {} (map (fn [i] [(keyword (str "k" i)) (apply str (repeat 250 "x"))]) (range 50)))
          map-out (mwire/format-eval-output {:handle 'result/e7 :ok? true :ms 5.0 :value (pr-str big-map)})
          big-str (str/join "\n" (map #(str "line-" % "-" (apply str (repeat 90 "a"))) (range 120)))
          str-out (mwire/format-eval-output {:handle 'result/e8 :ok? true :ms 3.0 :value (pr-str big-str)})]
      (is (str/includes? src-out "(defn f [x]\n  (inc x))"))
      (is (not (str/includes? src-out "\\n")) "Clojure string values must render unescaped without double pr-str!")
      (is (< (count map-out) 6500))
      (is (str/includes? map-out "Large map (50 keys"))
      (is (str/includes? map-out "result/e7"))
      (is (< (count str-out) 8000))
      (is (str/includes? str-out "Large string (120 lines"))
      (is (str/includes? str-out "result/e8")))))

(deftest ^{:cost/ticks 60000000} ui-sidebar-ordering-datastar-morph-and-stream-walk-stability-test
  (testing "Sidebar sorts deterministically by immutable creation tx, native Datastar data-preserve-attr=open works on details, sticky-scroll-js is targeted without MutationObserver, and step-walk skips sidebar+view on stream ticks"
    (with-test-store [{:keys [conn]}]
      (let [sidebar-unit (requiring-resolve 'seon.render.ui/sidebar-unit)
            stream-unit (requiring-resolve 'seon.render.ui/stream-unit)
            page-specs (requiring-resolve 'seon.render.ui/page-unit-specs)
            default-canvas (requiring-resolve 'seon.render.canvas/default-canvas)
            agent-ws (requiring-resolve 'seon.render.canvas/agent-workspace)
            act-fold (requiring-resolve 'seon.render.markdown/render-activity-fold)
            hiccup->html (requiring-resolve 'seon.render/hiccup->html)
            empty-walk (requiring-resolve 'seon.render/empty-walk)
            step-walk (requiring-resolve 'seon.render/step-walk)
            full-css (requiring-resolve 'seon.web.styles/full-css)
            scroll-js @(requiring-resolve 'seon.web.css/sticky-scroll-js)]
        ;; Create agents in separate txs so creation entity-tx is strictly increasing
        (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/role :coordinator}]})
        (d/transact conn {:tx-data [{:seon.agent/id :work/alpha :seon.agent/parent :agent/root :seon.agent/branch :branch/alpha :seon.agent/role :worker}]})
        (d/transact conn {:tx-data [{:seon.agent/id :work/beta :seon.agent/parent :agent/root :seon.agent/branch :branch/beta :seon.agent/role :worker}]})
        (d/transact conn {:tx-data [{:seon.agent/id :work/gamma :seon.agent/parent :agent/root :seon.agent/branch :branch/gamma :seon.agent/role :worker}]})
        (d/transact conn {:tx-data [{:seon.user/id :user/default
                                     :seon.user/viewing [:seon.agent/id :agent/root]
                                     :seon.user/view-mode :canvas
                                     :seon.user/sidebar-open? true
                                     :seon.user/expanded-agents #{:agent/root}}]})

        ;; 1. Deterministic creation-order sidebar (entry first, then newest entity-tx descending)
        (let [order-of (fn [html]
                         (let [ids ["nav-ag-root" "nav-ag-work~gamma" "nav-ag-work~beta" "nav-ag-work~alpha"]]
                           (mapv #(.indexOf ^String html ^String %) ids)))
              h0 (hiccup->html (sidebar-unit @conn {:agent-states {}}))
              h1 (hiccup->html (sidebar-unit @conn {:agent-states {:work/alpha :active}}))
              h2 (hiccup->html (sidebar-unit @conn {:agent-states {:work/beta :active :work/gamma :active}}))]
          (is (apply < (order-of h0)) (str "Expected root > gamma > beta > alpha order, got indices " (order-of h0)))
          (is (apply < (order-of h1)) "Flipping oldest agent :active MUST NOT reorder sidebar!")
          (is (apply < (order-of h2)) "Concurrent active agents MUST NOT swap sidebar positions!")
          (is (str/includes? h1 "dot pulsing"))
          (is (str/includes? h1 ">live</span>"))
          ;; Updating mutable agent attrs (merged-tx / goal) must NOT alter creation entity-tx order
          (d/transact conn {:tx-data [{:seon.agent/id :work/alpha :seon.agent/goal "Updated goal" :seon.agent/merged-tx 999999999}]})
          (let [h3 (hiccup->html (sidebar-unit @conn {:agent-states {:work/alpha :active}}))]
            (is (apply < (order-of h3)) "Mutable agent updates must NOT reorder sidebar!")))

        ;; 2. Native Datastar data-preserve-attr="open" on <details> + opt-out support
        (let [d-plain (hiccup->html [:details {:id "d-plain"} [:summary "S"] "B"])
              d-optout (hiccup->html [:details {:id "d-out" :data-preserve-attr false} [:summary "S"] "B"])
              d-fold (hiccup->html (act-fold [{:seon.agent.eval/intent "Check" :seon.agent.eval/code "(+ 1 1)"}] "cv"))
              h-side (hiccup->html (sidebar-unit @conn {:agent-states {}}))]
          (is (str/includes? d-plain "data-preserve-attr=\"open\""))
          (is (not (str/includes? d-optout "data-preserve-attr")))
          (is (str/includes? d-fold "data-preserve-attr=\"open\""))
          (is (str/includes? h-side "data-preserve-attr=\"open\"")))

        ;; 3. Stream dock stable IDs, signals, zero inline display:none, and Canvas/Workspace view-unit metadata
        (let [sh (hiccup->html (stream-unit nil {:live-streams {:work/beta {:status :reasoning :reasoning "Plan" :text ""}
                                                                :work/alpha {:status :token :reasoning "" :text "Answer"}}}))
              ch (hiccup->html (default-canvas {:seon.agent/id :agent/root :seon.agent/branch :main}))
              wh (hiccup->html (agent-ws @conn {:conn conn :viewing-id :agent/root}))]
          (is (< (.indexOf ^String sh "stream-work~alpha") (.indexOf ^String sh "stream-work~beta")))
          (is (str/includes? sh "id=\"stream-work~beta-reasoning\""))
          (is (str/includes? sh "id=\"stream-work~alpha-prose\""))
          (is (str/includes? sh "$showThinking"))
          (is (str/includes? sh "$streamCollapsed"))
          (is (not (str/includes? sh "display:none")))
          (is (str/includes? ch "data-agent=\":agent/root\""))
          (is (str/includes? ch "data-view-mode=\"canvas\""))
          (is (str/includes? wh "data-agent=\":agent/root\""))
          (is (str/includes? wh "data-view-mode=\"workspace\"")))

        ;; 4. Scroll JS & modern CSS stability contracts
        (let [css (full-css)]
          (is (str/includes? scroll-js ".stage-scroll"))
          (is (str/includes? scroll-js ".ws-timeline"))
          (is (str/includes? scroll-js ".live-reasoning"))
          (is (str/includes? scroll-js ".live-prose"))
          (is (str/includes? scroll-js ".stream-dock"))
          (is (str/includes? scroll-js "datastar-fetch"))
          (is (str/includes? scroll-js "WeakMap"))
          (is (not (str/includes? scroll-js "MutationObserver")))
          (is (not (str/includes? scroll-js "openDetails")))
          (is (str/includes? css "overscroll-behavior:contain"))
          (is (str/includes? css "overflow-anchor:auto"))
          (is (str/includes? css "scrollbar-gutter:stable"))
          (is (str/includes? css "contain:layout style paint"))
          (is (str/includes? css "details::details-content"))
          (is (str/includes? css ".main-col:has(.stream-dock.active) .stage-scroll")))

        ;; 5. Incremental step-walk skips #seon-sidebar, #seon-view-unit, #seon-header, #seon-composer-unit & #seon-css on stream ticks (0 DB seeks!)
        (let [db @conn
              st0 {:boot-seq 1 :conn conn :agent-states {:work/alpha :active :work/beta :active}
                   :live-streams {} :branch-renders {} :errors []}
              w0 (:walk (step-walk (empty-walk) db nil (page-specs st0)))
              st1 (assoc st0 :live-streams {:work/alpha {:status :token :text "hi"}
                                            :work/beta {:status :reasoning :reasoning "thinking"}})
              step1 (step-walk w0 db [] (page-specs st1))
              st2 (assoc st0 :live-streams {:work/alpha {:status :token :text "hi there"}
                                            :work/beta {:status :reasoning :reasoning "thinking more"}})
              step2 (step-walk (:walk step1) db [] (page-specs st2))]
          (is (= 1 (:rerendered step1)))
          (is (= 5 (:skipped step1)))
          (is (= 0 (get-in step1 [:walk :units "seon-stream-unit" :reads-count])))
          (is (= 1 (:rerendered step2)))
          (is (= 5 (:skipped step2)))
          (is (= 1 (count (:patches step2))))
          (is (str/includes? (first (:patches step2)) "seon-stream-unit"))
          (is (not (str/includes? (first (:patches step2)) "seon-sidebar")))
          (is (not (str/includes? (first (:patches step2)) "seon-view-unit")))
          ;; Flipping a background agent's active state when sidebar is open re-renders ONLY #seon-sidebar (NOT #seon-header or #seon-view-unit!)
          (let [st3 (assoc st2 :agent-states {:work/alpha :active})
                step3 (step-walk (:walk step2) db [] (page-specs st3))]
            (is (= 1 (:rerendered step3)) "Background worker state flip must re-render ONLY #seon-sidebar!")
            (is (= 5 (:skipped step3)))
            (is (str/includes? (first (:patches step3)) "seon-sidebar"))))

        ;; 6. Closed sidebar does minimal reads and ignores background agent state flips
        (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/sidebar-open? false}]})
        (let [db @conn
              st0 {:boot-seq 1 :conn conn :agent-states {} :live-streams {} :branch-renders {} :errors []}
              w0 (:walk (step-walk (empty-walk) db nil (page-specs st0)))
              step-bg (step-walk w0 db [] (page-specs (assoc st0 :agent-states {:work/alpha :active})))]
          (is (= 0 (:rerendered step-bg)) "When sidebar is closed, background agent state flip must do ZERO rerenders!")
          (is (= 6 (:skipped step-bg))))))))

(deftest ^{:cost/ticks 60000000} self-healing-eval-errors-batch-tools-and-learning-metrics-test
  (testing "enrich-eval-error attaches class-eliminating hints for OOM, arity, fuzzy var, and private-var errors"
    (let [hidx {"callers" {:arglists "([sym] [sym & more])" :doc "Direct callers of one or more vars."}
                "grep-src" {:arglists "([pat] [pat opts])" :doc "Cached multi-pattern grep."}
                "edit-fn!" {:arglists "([target edits] [target old new])" :doc "Structural patch."}}
          oom-q (code/enrich-eval-error "(d/q '[:find ?e :where [?e :a _]] (user/db))"
                                        "Eval exceeded allocation budget (limit 536870912 bytes)." hidx)
          oom-g (code/enrich-eval-error "(doseq [p [\"a\" \"b\"]] (user/grep-src p))"
                                        "Eval exceeded allocation budget (limit 536870912 bytes)." hidx)
          oom-e (code/enrich-eval-error "[(user/edit-fn! 'a/b 1 2) (user/edit-fn! 'a/b 3 4)]"
                                        "Eval exceeded allocation budget (limit 536870912 bytes)." hidx)
          arity (code/enrich-eval-error "(user/callers)" "Wrong number of args (0) passed to: user/callers" hidx)
          fuzzy (code/enrich-eval-error "(user/grep-source \"x\")" "No such var: user/grep-source" hidx)
          priv  (code/enrich-eval-error "(seon.health/report-cache)" "var: #'seon.health/report-cache is not public" hidx)]
      (is (str/includes? oom-q "d/datoms"))
      (is (str/includes? oom-g "vector of patterns"))
      (is (str/includes? oom-e "[[old1 new1]"))
      (is (str/includes? arity "Usage: user/callers"))
      (is (str/includes? fuzzy "Did you mean: user/grep-src"))
      (is (str/includes? priv "private vars"))))
  (testing "Sliding-window agent-learning-metrics & classify-eval-error compute anti-gaming scorecard"
    (let [classify (requiring-resolve 'seon.health.efficiency/classify-eval-error)
          metrics-fn (requiring-resolve 'seon.health.efficiency/agent-learning-metrics)]
      (is (= :alloc-budget-overrun (classify "Eval exceeded allocation budget (limit 536870912 bytes).")))
      (is (= :wrong-arity (classify "Wrong number of args (2) passed to: foo")))
      (is (= :unresolved-var (classify "No such var: user/nope")))
      (with-test-store [{:keys [conn]}]
        (d/transact conn {:tx-data [{:seon.agent/id :w/good :seon.agent/branch :main :seon.agent/role :worker
                                     :seon.agent/goal "Good" :seon.agent/merged-tx 100}
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/good :seon.agent.eval/turn 1
                                     :seon.agent.eval/handle 'result/e1 :seon.agent.eval/code "(user/grep-src [\"a\" \"b\"])"
                                     :seon.agent.eval/bytes 4194304 :seon.agent.eval/cache-hit-tokens 4000 :seon.agent.eval/cache-miss-tokens 200}
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/good :seon.agent.eval/turn 2
                                     :seon.agent.eval/handle 'result/e2 :seon.agent.eval/code "(user/edit-fn! 'seon.x/f [[1 2]])"
                                     :seon.agent.eval/bytes 8388608 :seon.agent.eval/cache-hit-tokens 4200 :seon.agent.eval/cache-miss-tokens 200}
                                    {:seon.agent/id :w/fail :seon.agent/branch :main :seon.agent/role :worker
                                     :seon.agent/goal "Fail"}
                                    {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :w/fail :seon.agent.eval/turn 1
                                     :seon.agent.eval/handle 'result/e1 :seon.agent.eval/code "(user/diff)"
                                     :seon.agent.eval/error "Eval exceeded allocation budget (limit 268435456 bytes)."
                                     :seon.agent.eval/bytes 268435456}]})
        (let [m (metrics-fn conn @conn {:window 5})]
          (is (= 2 (:attempted m)))
          (is (= 1 (:merged m)))
          (is (= 50 (:merge-rate-pct m)))
          (is (= 3.0 (:effective-turns-per-merged m)))
          (is (= 1 (get-in m [:error-classes :alloc-budget-overrun])))
          (is (>= (:kv-cache-hit-pct m) 90)))))))