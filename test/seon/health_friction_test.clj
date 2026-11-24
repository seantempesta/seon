(ns seon.health-friction-test
  "Unit, synthetic trace friction, allocation budget, dedup, renderer, and seed=42 generative tests for `seon.health.friction`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.health.friction :as friction]
            [seon.render.entities.diagnostics :as rdiag]
            [seon.render.fit :as rfit]
            [seon.render.text :as rtext]
            [seon.schema :as schema]
            [seon.store :as st]
            [seon.test-fixture :refer [with-test-store]]
            [seon.fork.calls :as fcalls]))

(defn- tx-branch! [cfg branch tx-data]
  (let [bc (st/connect cfg branch)]
    (try
      (d/transact bc {:tx-data tx-data})
      (finally
        (d/release bc)))))

(defn- eval-tx [aid turn ci handle opts]
  (merge {:seon.agent.eval/id (random-uuid)
          :seon.agent.eval/agent aid
          :seon.agent.eval/turn turn
          :seon.agent.eval/call-index ci
          :seon.agent.eval/handle handle
          :seon.agent.eval/code "(+ 1 2)"
          :seon.agent.eval/ms 12.0
          :seon.agent.eval/bytes 1048576
          :seon.agent.eval/prompt-tokens 5000
          :seon.agent.eval/completion-tokens 200
          :seon.agent.eval/cache-hit-tokens 4500
          :seon.agent.eval/cache-miss-tokens 500
          :seon.agent.eval/prefix-hash "ph-a"}
         opts))

(deftest synthetic-trace-friction-detection-test
  (testing "Detects livelock, real OOM vs large non-error eval, rogue-fs vs safe fs-write, :no-match, CST error, prefix bust, upstream errors, test failures, and delegation"
    (with-test-store [{:keys [conn cfg]}]
      (st/branch! conn :main :branch/calm)
      (st/branch! conn :main :branch/hot)
      (d/transact conn
        {:tx-data [{:seon.agent/id :worker/calm :seon.agent/branch :branch/calm :seon.agent/merged-tx 100}
                   {:seon.agent/id :worker/hot :seon.agent/branch :branch/hot}
                   {:seon.error/id (random-uuid) :seon.error/source :model :seon.error/agent :worker/hot :seon.error/kind :model/http-500 :seon.error/severity :error :seon.error/status :open :seon.error/message "500"}
                   {:seon.error/id (random-uuid) :seon.error/source :model :seon.error/agent :worker/hot :seon.error/kind :model/timeout :seon.error/severity :error :seon.error/status :open :seon.error/message "timeout"}
                   {:seon.agent.message/id (random-uuid) :seon.agent.message/seq 1 :seon.agent.message/from :system :seon.agent.message/to :worker/hot :seon.agent.message/type :test-failure :seon.agent.message/body "fail"}
                   {:seon.agent.call/id (random-uuid) :seon.agent.call/caller :worker/hot :seon.agent.call/callee :worker/sub :seon.agent.call/status :resolved}]})
      ;; Calm agent: high KV%, large successful eval (not OOM!), safe user/fs-write (not rogue!)
      (tx-branch! cfg :branch/calm
        [(eval-tx :worker/calm 1 0 'result/e1 {:seon.agent.eval/code "(user/fs-write \"a.txt\" \"ok\")" :seon.agent.eval/bytes 300000000 :seon.agent.eval/ms 100.0})
         (eval-tx :worker/calm 2 0 'result/e2 {:seon.agent.eval/code "(+ 2 3)" :seon.agent.eval/bytes 2000000})])
      ;; Hot agent: turn overrun (>4), empty turn, OOM, repeated error, rogue spit, :no-match, CST error, prefix bust, low KV%
      (tx-branch! cfg :branch/hot
        [(eval-tx :worker/hot 1 0 'result/e1 {:seon.agent.eval/code "   " :seon.agent.eval/cache-hit-tokens 500 :seon.agent.eval/cache-miss-tokens 4500 :seon.agent.eval/prefix-hash "ph-1"})
         (eval-tx :worker/hot 2 0 'result/e2 {:seon.agent.eval/code "(spit \"src/bad.clj\" \"x\")" :seon.agent.eval/prefix-hash "ph-2" :seon.agent.eval/cache-hit-tokens 200 :seon.agent.eval/cache-miss-tokens 4800})
         (eval-tx :worker/hot 3 0 'result/e3 {:seon.agent.eval/code "(user/edit-fn! 'a/b \"x\" \"y\")" :seon.agent.eval/value "{:ok? false :error :no-match}" :seon.agent.eval/prefix-hash "ph-2"})
         (eval-tx :worker/hot 4 0 'result/e4 {:seon.agent.eval/code "(user/edit-fn! 'a/b \"x\" \"z\")" :seon.agent.eval/value "{:ok? false :error :no-match}" :seon.agent.eval/prefix-hash "ph-2"})
         (eval-tx :worker/hot 5 0 'result/e5 {:seon.agent.eval/error "Eval exceeded allocation budget (limit 268435456 bytes)." :seon.agent.eval/bytes 270000000 :seon.agent.eval/prefix-hash "ph-2"})
         (eval-tx :worker/hot 6 0 'result/e6 {:seon.agent.eval/error "reader syntax error: unmatched delimiter 42" :seon.agent.eval/prefix-hash "ph-2"})
         (eval-tx :worker/hot 6 1 'result/e7 {:seon.agent.eval/error "reader syntax error: unmatched delimiter 99" :seon.agent.eval/prefix-hash "ph-2"})])
      (let [mdb (st/head conn :main)
            calm (friction/agent-friction-summary conn mdb :worker/calm {:max-tool-turns 4})
            hot (friction/agent-friction-summary conn mdb :worker/hot {:max-tool-turns 4})
            rep (friction/friction-report conn mdb {:max-tool-turns 4 :include-renderers? false :include-static? false})
            fkinds (set (map :kind (:findings rep)))]
        (is (= :calm (:level calm)))
        (is (= 0 (:ooms calm)) "Large non-error eval is NOT counted as OOM")
        (is (= 0 (:rogue-fs-count calm)) "user/fs-write is NOT flagged as rogue FS")
        (is (= 90.0 (:kv-cache-hit-pct calm)))
        (is (true? (:merged? calm)))
        (is (= :hot (:level hot)))
        (is (true? (:turn-overrun? hot)))
        (is (= 1 (:empty-code-turns hot)))
        (is (= 1 (:ooms hot)))
        (is (= 1 (:rogue-fs-count hot)))
        (is (= 2 (:no-match-count hot)))
        (is (= 2 (:cst-errors hot)))
        (is (= 1 (:repeated-errors hot)))
        (is (= 2 (:prefix-hash-count hot)))
        (is (>= (:prefix-busts hot) 1))
        (is (= 2 (:upstream-errors hot)))
        (is (= 1 (:test-failure-messages hot)))
        (is (= 1 (:delegated-calls hot)))
        (is (= {:seon/schema :seon.health/friction} (meta rep)))
        (is (:ok? (schema/validate-against mdb :seon.health/friction rep)))
        (is (= 2 (:agents-scanned rep)))
        (is (every? fkinds [:turn-overrun :alloc-budget-overrun :rogue-fs :prefix-cache-bust :low-cache-hit :no-match-churn :upstream-error-spike]))))))

(deftest allocation-budget-and-lru-cache-test
  (testing "friction-report runs in < 50 ms and < 30 MB on a synthetic store and caches hits by commit sig"
    (with-test-store [{:keys [conn cfg]}]
      (st/branch! conn :main :branch/perf)
      (d/transact conn {:tx-data [{:seon.agent/id :worker/perf :seon.agent/branch :branch/perf}]})
      (tx-branch! cfg :branch/perf
        (mapv #(eval-tx :worker/perf % 0 (symbol "result" (str "e" %)) {}) (range 1 21)))
      (let [mdb (st/head conn :main)
            m1 (code/measure-call #(friction/friction-report conn mdb {:include-renderers? false :include-static? false}))
            m2 (code/measure-call #(friction/friction-report conn mdb {:include-renderers? false :include-static? false}))]
        (is (< (:bytes m1) 31457280) (str "Cold bytes >= 30 MB: " (:bytes m1)))
        (is (< (:ms m1) 50.0) (str "Cold ms >= 50 ms: " (:ms m1)))
        (is (= 1 (:agents-scanned (:val m1))))
        (is (= 20 (get-in (:val m1) [:totals :evals])))
        (is (= (:val m1) (:val m2)) "Warm LRU cache hit returns identical report")))))

(deftest propose-tasks-tx-dedup-test
  (testing "propose-tasks-tx delegates to mine-hygiene-tasks-tx and skips already-existing :seon.task/id records"
    (with-test-store [{:keys [conn]}]
      (let [f1 {:id :hygiene/trace-oom-worker-a :kind :alloc-budget-overrun :scope 'seon.health
                :title "OOM 1x :worker/a" :summary "OOM 1x :worker/a" :curated [[:seon.agent/id :worker/a]] :impact 95}
            f2 {:id :hygiene/trace-rogue-fs-worker-b :kind :rogue-fs :scope 'seon.fork.helpers
                :title "Rogue 1x :worker/b" :summary "Rogue 1x :worker/b" :curated [[:seon.agent/id :worker/b]] :impact 94}]
        (d/transact conn {:tx-data [{:seon.task/id (:id f1) :seon.task/title "Existing task" :seon.task/done? false}]})
        (let [txs (friction/propose-tasks-tx @conn {:findings [f1 f2]} {:limit 5})]
          (is (= 1 (count txs)))
          (is (= (:id f2) (:seon.task/id (first txs))))
          (is (false? (:seon.task/done? (first txs)))))))))

(deftest friction-renderers-discovery-and-seed42-stress-test
  (testing "Dual-footprint friction renderers produce distinct widths & text, auto-discover cleanly, and pass seed=42 stress-var"
    (with-test-store [{:keys [conn]}]
      (let [sample (second (schema/sample-schema @conn :seon.health/friction))
            chip (:seon.ui/hiccup (rdiag/render-friction-chip {:seon.health/friction sample}))
            card (:seon.ui/hiccup (rdiag/render-friction-card {:seon.health/friction sample}))
            chip-w (:data-min-width (second chip))
            card-w (:data-min-width (second card))
            chip-txt (rtext/hiccup->text chip)
            card-txt (rtext/hiccup->text card)
            synth-src "(ns seon.render.entities.diagnostics)
(defn render-friction-chip {:malli/schema [:=> [:cat [:map [:seon.health/friction :seon.health/friction]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:span {:data-min-width 180} \"c\"]})
(defn render-friction-card {:malli/schema [:=> [:cat [:map [:seon.health/friction :seon.health/friction]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] {:seon.ui/hiccup [:div {:data-min-width 440} \"card\"]})"]
        (is (= 180 chip-w))
        (is (= 440 card-w))
        (is (> (count card-txt) (* 2 (count chip-txt))))
        (is (str/includes? chip-txt "friction"))
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/render/entities/diagnostics.clj" synth-src false)})
        (let [disc (set (rfit/discover-renderers @conn :seon.health/friction))]
          (is (contains? disc 'seon.render.entities.diagnostics/render-friction-chip))
          (is (contains? disc 'seon.render.entities.diagnostics/render-friction-card)))
        (doseq [sym ['seon.health.friction/score-agent-friction
                     'seon.render.entities.diagnostics/render-friction-chip
                     'seon.render.entities.diagnostics/render-friction-card]]
          (let [res (schema/stress-var @conn sym)]
            (is (:ok? res) (str "seed=42 stress-var failed for " sym ": " (pr-str res)))))))))

(deftest r-rog-no-lookbehind-oom-and-repl-friction-ergonomics-test
  (testing "r-rog has zero negative-lookbehind OOM on 5,000-char code (< 20 ms / < 10 MB) and matches fs/write & jolt.fs/write accurately"
    (let [r-rog @#'friction/r-rog
          pad (apply str (repeat 250 "(let [x (+ 1 2)] x) "))
          big-clean (str "(do " pad " (my-fs/write \"a\" \"b\") (user/fs-write \"c\" \"d\"))")
          big-rog-1 (str "(do " pad " (fs/write \"a\" \"b\"))")
          big-rog-2 (str "(do " pad " (jolt.fs/write \"a\" \"b\"))")
          m-clean (code/measure-call #(re-find r-rog big-clean))
          m-rog1 (code/measure-call #(re-find r-rog big-rog-1))
          m-rog2 (code/measure-call #(re-find r-rog big-rog-2))]
      (is (>= (count big-clean) 5000))
      (is (nil? (:val m-clean)) "my-fs/write and user/fs-write must not match r-rog")
      (is (some? (:val m-rog1)) "(fs/write ...) must match r-rog")
      (is (some? (:val m-rog2)) "(jolt.fs/write ...) must match r-rog")
      (is (some? (re-find r-rog "fs/write")) "start-of-string fs/write must match r-rog")
      (is (some? (re-find r-rog "jolt.fs/write")) "start-of-string jolt.fs/write must match r-rog")
      (doseq [[lbl m] [["clean" m-clean] ["rog1" m-rog1] ["rog2" m-rog2]]]
        (is (< (:ms m) 20.0) (str lbl " took " (:ms m) " ms (>= 20 ms)"))
        (is (< (:bytes m) 10485760) (str lbl " allocated " (:bytes m) " B (>= 10 MB)")))))

  (testing "repl-friction accepts keyword :agent/root, {:agent :agent/root}, and {:agents [:agent/root]}"
    (with-test-store [{:keys [conn cfg]}]
      (st/branch! conn :main :branch/root-erg)
      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/branch :branch/root-erg}]})
      (let [pad (apply str (repeat 250 "(let [x (+ 1 2)] x) "))
            big-code (str "(do " pad " (user/fs-write \"ok.txt\" \"hi\"))")]
        (tx-branch! cfg :branch/root-erg
          [(eval-tx :agent/root 1 0 'result/e1 {:seon.agent.eval/code big-code})])
        (let [mdb (st/head conn :main)
              m-an (code/measure-call #(friction/analyze-agent-friction (st/head conn :branch/root-erg) :agent/root {:main-db mdb}))
              rep-map (friction/friction-report conn mdb {:agent :agent/root :include-renderers? false :include-static? false})
              [rep-kw rep-opt] (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/root}]
                                 [(friction/repl-friction :agent/root)
                                  (friction/repl-friction {:agent :agent/root})])]
          (is (< (:ms m-an) 20.0) (str "analyze-agent-friction 5k code took " (:ms m-an) " ms"))
          (is (< (:bytes m-an) 10485760) (str "analyze-agent-friction 5k code allocated " (:bytes m-an) " B"))
          (is (= 0 (:rogue-fs-count (:val m-an))))
          (is (= [:agent/root] (mapv :agent (:agents rep-map))))
          (is (= [:agent/root] (mapv :agent (:agents rep-kw))))
          (is (= [:agent/root] (mapv :agent (:agents rep-opt))))
          (is (= {:seon/schema :seon.health/friction} (meta rep-kw)))
          (is (:ok? (schema/validate-against mdb :seon.health/friction rep-kw))))))))