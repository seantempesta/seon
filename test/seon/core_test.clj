(ns seon.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.scheme :as scm]
            [seon.code :as code]
            [seon.code.refactor]
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
  (let [emph (deref (requiring-resolve 'seon.render.ui/render-emphasis))
        inline (requiring-resolve 'seon.render.ui/render-inline-prose)]
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
            (is (seq (d/datoms (d/history @conn) :avet :seon.health.fault/id fid)))))))))

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
      (let [tpath "resources/seon/tasks/seon.demo.edn"
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
          (is (= 250 (:seon.config/fast-test-ms parsed))))))))

(deftest ^{:cost/ticks 40000000} lossless-namespace-move-vars-refactor-test
  (testing "seon.code.refactor/move-vars-tx checks unresolved deps, promotes defn-, re-exports public fns preserving fn->qualified-sym & ^:seon/callable, and shrinks source ns"
    (seon.test-fixture/with-test-store [{:keys [conn]}]
      (let [src "(ns seon.demo.big\n  \"Big ns\"\n  (:require [clojure.string :as str]))\n\n(defn- helper [x]\n  (str/upper-case (str x)))\n\n(defn ^:seon/callable greet [x]\n  (str \"Hi \" (helper x)))\n\n(defn entry [x]\n  (greet x))\n"]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/big.clj" src false)})
        ;; 1. Moving `greet` WITHOUT its local dep `helper` is caught upfront before touching DB!
        (let [bad (seon.code.refactor/move-vars-tx @conn {:from 'seon.demo.big :to 'seon.demo.big.sub :vars '[greet]})]
          (is (not (:ok? bad)))
          (is (= :unresolved-from-deps (:error bad)))
          (is (= ['seon.demo.big/helper] (:missing-deps bad))))
        ;; 2. Moving `[helper greet]` succeeds atomically, promotes `helper`, re-exports `^:seon/callable greet` as defn wrapper, and compiles cleanly!
        (let [res (seon.code.refactor/apply-move-vars! conn {:from 'seon.demo.big :to 'seon.demo.big.sub :alias 'sub :vars '[helper greet]})
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
