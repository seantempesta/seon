(ns seon.wave1-trace-fixes-test
  "End-to-end proofs for Wave D3 (Live DeepSeek Wave 1 trace fixes):
   1. Zero 20-line clipping on strings <= 8000 chars + `(user/expand! ... {:out? true})`.
   2. Actionable `edit-fn!` bare-ns `:no-match` hint + `:key`/`:lines`/`:chars` on `ns-form-outline`.
   3. `stress-var` and `run-fast-auto-proofs!` skipping `^:seon.flow/edge` & `:seon.gen/skip` + 65% tick margin.
   4. 1-turn reconciler wrap-up grace at `tool-streak == max-tool-turns` with unmerged code edits.
   5. Upgraded `docs/primers.md` with 0 `:broken-doc-ref` and bumped core-test tick budget."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.edit :as edit]
            [seon.code.refactor :as refactor]
            [seon.fork.calls :as fcalls]
            [seon.fork.worker-support :as wsup]
            [seon.health.contracts :as hcontracts]
            [seon.model.wire :as mwire]
            [seon.reconciler :as rec]
            [seon.render.print :as sprint]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(deftest ^{:cost/ticks 60000000} string-no-20-line-clip-and-expand-out-test
  (testing "120-line strings <= 8000 chars render verbatim without 20-line clipping, >8000 summarizes, and expand! supports {:out? true}"
    (let [s120 (str/join "\n" (map #(str "line-" % " : (defn helper-" % " [x] (+ x " % "))") (range 1 121)))
          _ (is (< (count s120) 8000))
          wired (mwire/format-eval-output {:handle (quote result/e10) :ok? true :value (pr-str s120) :max-chars 8000})
          tree (sprint/select-tree nil s120 {:max-chars 8000 :limit 20})
          huge (str/join "\n" (map #(str "line-" % "-" (apply str (repeat 80 "x"))) (range 120)))
          wired-huge (mwire/format-eval-output {:handle (quote result/e11) :ok? true :value (pr-str huge) :max-chars 8000})
          big-out (str "HEAD-OUT " (apply str (repeat 8100 "z")) " TAIL-OUT")
          wired-out (mwire/format-eval-output {:handle (quote result/e12) :ok? true :value "nil" :out big-out :max-chars 8000})]
      (is (str/includes? wired "line-1 :"))
      (is (str/includes? wired "line-120 :"))
      (is (not (str/includes? wired "Large string")) "120-line string <= 8000 chars must NOT clip at 20 lines")
      (is (= s120 (:preview tree)))
      (is (false? (:more? tree)))
      (is (= 120 (:lines tree)))
      (is (str/includes? wired-huge "Large string (120 lines"))
      (is (str/includes? wired-out "(user/expand! 'result/e12 {:out? true})")))
    ;; Live result var + Datahike fallback {:out? true}
    (with-test-store [{:keys [conn]}]
      (let [rns (create-ns (quote result))
            out-txt (str/join "\n" (map #(str "stdout-line-" %) (range 1 41)))]
        (try
          (let [v (intern rns (quote e_w1_out) :ok)]
            (alter-meta! v assoc :seon.eval/out out-txt)
            (let [r-full (sprint/expand-result @conn (quote result/e_w1_out) {:out? true})
                  r-slice (sprint/expand-result @conn (quote result/e_w1_out) {:out? true :offset 10 :limit 5})]
              (is (true? (:ok? r-full)))
              (is (true? (:out? r-full)))
              (is (= out-txt (:md r-full)))
              (is (true? (:ok? r-slice)))
              (is (str/includes? (:md r-slice) "stdout-line-11"))
              (is (str/includes? (:md r-slice) "stdout-line-15"))
              (is (not (str/includes? (:md r-slice) "stdout-line-25")))))
          (finally (ns-unmap rns (quote e_w1_out))))
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid)
                                     :seon.agent.eval/agent :repl/w1
                                     :seon.agent.eval/turn 1
                                     :seon.agent.eval/code "(println :hi)"
                                     :seon.agent.eval/value "nil"
                                     :seon.agent.eval/out "db-captured-stdout-42"
                                     :seon.agent.eval/handle (quote result/e_w1_db_out)}]})
        (let [r-db (sprint/expand-result @conn (quote result/e_w1_db_out) {:out? true :agent-id :repl/w1})]
          (is (true? (:ok? r-db)))
          (is (true? (:out? r-db)))
          (is (= "db-captured-stdout-42" (:md r-db))))))))

(deftest ^{:cost/ticks 60000000} edit-fn-bare-ns-hint-and-ns-outline-fields-test
  (testing "edit-fn! on bare ns miss includes actionable hint, and ns-form-outline includes :key, :lines, :chars"
    (with-test-store [{:keys [conn]}]
      (let [src "(ns demo.w1-outline
  \"Demo ns\")

(defn alpha
  \"Adds 1.\"
  {:malli/schema [:=> [:cat :int] :int] :cost/ticks 5000}
  [x]
  (inc x))
"]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/w1_outline.clj" src false)})
        (let [outline (refactor/ns-form-outline @conn (quote demo.w1-outline))
              f-alpha (some #(when (= (quote alpha) (:sym %)) %) (:forms outline))]
          (is (= 2 (:form-count outline)))
          (is (every? #(and (string? (:key %)) (pos-int? (:lines %)) (pos-int? (:chars %)) (integer? (:tokens %)))
                      (:forms outline)))
          (is (= "demo.w1-outline/alpha" (:key f-alpha)))
          (is (= 5 (:lines f-alpha)))
          (is (> (:chars f-alpha) 80)))
        (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :repl/w1}]
          (let [res (edit/edit-fn! (quote demo.w1-outline) "(inc x)" "(+ x 2)")]
            (is (false? (:ok? res)))
            (is (= :no-match (:error res)))
            (is (= "demo.w1-outline#ns" (:key res)))
            (is (string? (:hint res)))
            (is (str/includes? (:hint res) "ONLY the (ns ...) header"))
            (is (str/includes? (:hint res) "'ns/fn"))
            (is (str/includes? (:hint res) "user/add-fn!"))))))))

(deftest ^{:cost/ticks 80000000} flow-edge-skip-and-auto-proofs-alignment-test
  (testing "stress-var and run-fast-auto-proofs! skip ^:seon.flow/edge & :seon.gen/skip with 0 executions and flag tight :cost/ticks"
    (let [calls (atom 0)
          tns (create-ns (quote seon.w1-edge-tmp))]
      (try
        (intern tns (with-meta (quote edge-meta-fn)
                      {:seon.flow/edge true :malli/schema [:=> [:cat :int] :int]})
                (fn [x] (swap! calls inc) x))
        (intern tns (with-meta (quote skip-meta-fn)
                      {:seon.gen/skip true :malli/schema [:=> [:cat :int] :int]})
                (fn [x] (swap! calls inc) x))
        (intern tns (with-meta (quote db-edge-fn)
                      {:malli/schema [:=> [:cat :int] :int]})
                (fn [x] (swap! calls inc) x))
        (let [r1 (schema/stress-var nil (quote seon.w1-edge-tmp/edge-meta-fn) {:samples 25 :seed 42})
              r2 (schema/stress-var nil (quote seon.w1-edge-tmp/skip-meta-fn) {:samples 25 :seed 42})]
          (is (true? (:ok? r1)))
          (is (true? (:skipped? r1)))
          (is (= :flow-edge (:reason r1)))
          (is (= 0 (:ran r1)))
          (is (true? (:ok? r2)))
          (is (true? (:skipped? r2)))
          (is (= :gen-skip (:reason r2)))
          (is (= 0 @calls) "flow-edge and gen-skip vars must execute 0 times"))
        ;; DB-only :seon.code.var/flow-edge? true also skips with 0 executions
        (with-test-store [{:keys [conn cfg]}]
          (d/transact conn {:tx-data [{:seon.code.var/name (quote seon.w1-edge-tmp/db-edge-fn)
                                       :seon.code.var/ns (quote seon.w1-edge-tmp)
                                       :seon.code.var/flow-edge? true}]})
          (let [r3 (schema/stress-var @conn (quote seon.w1-edge-tmp/db-edge-fn) {:samples 25 :seed 42})]
            (is (true? (:ok? r3)))
            (is (true? (:skipped? r3)))
            (is (= :flow-edge (:reason r3)))
            (is (= 0 @calls)))
          ;; run-fast-auto-proofs! skips ^:seon.flow/edge and flags tight :cost/ticks (>65% warm budget)
          (d/transact conn {:tx-data [{:seon.agent/id :ag/w1-proof :seon.agent/branch :branch/w1-proof :seon.agent/role :worker}]})
          (store/branch! conn :main :branch/w1-proof)
          (let [bconn (store/connect cfg :branch/w1-proof)
                before-cid (d/commit-id @bconn)
                edge-calls (atom 0)]
            (try
              (let [src "(ns demo.w1-proof)
(defn ^:seon.flow/edge boom-edge! {:malli/schema [:=> [:cat :int] :int]} [x] (throw (ex-info \"must-not-run\" {:x x})))
(defn tight-fn {:malli/schema [:=> [:cat :int] :int] :cost/ticks 5} [x] (reduce + 0 (range (min 20 (Math/abs (long x))))))
"]
                (d/transact bconn {:tx-data (code/file-import-tx @bconn "src/demo/w1_proof.clj" src false)})
                (let [pns (create-ns (quote demo.w1-proof))]
                  (intern pns (with-meta (quote boom-edge!) {:seon.flow/edge true :malli/schema [:=> [:cat :int] :int]})
                          (fn [_] (swap! edge-calls inc) (throw (ex-info "must-not-run" {}))))
                  (intern pns (with-meta (quote tight-fn) {:malli/schema [:=> [:cat :int] :int] :cost/ticks 5})
                          (fn [x] (reduce + 0 (range (min 20 (Math/abs (long x)))))))
                  (let [summary (wsup/run-fast-auto-proofs! bconn :ag/w1-proof :branch/w1-proof before-cid)]
                    (is (= 0 @edge-calls) "AUTO-PROOFS must never execute ^:seon.flow/edge vars")
                    (is (some? summary))
                    (is (false? (:ok? summary)) "Tight :cost/ticks must fail AUTO-PROOFS before ready!")
                    (is (some #(str/includes? % "demo.w1-proof/tight-fn") (:failures summary))))))
              (finally
                (remove-ns (quote demo.w1-proof))
                (d/release bconn)))))
        (finally (remove-ns (quote seon.w1-edge-tmp)))))))

(deftest ^{:cost/ticks 90000000} reconciler-one-turn-wrapup-grace-test
  (testing "Reconciler grants exactly 1 wrap-up grace turn at tool-streak == max-tool-turns when branch has unmerged edits"
    (with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (concat
                                  (code/file-import-tx @conn "src/demo/w1_rec.clj"
                                                       "(ns demo.w1-rec)
(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)
" false)
                                  [{:seon.config/id :default :seon.config/max-tool-turns 2}
                                   {:seon.agent/id :ag/w1-edited :seon.agent/branch :branch/w1-edited :seon.agent/role :worker :seon.agent/auto-merge? true}
                                   {:seon.agent/id :ag/w1-clean :seon.agent/branch :branch/w1-clean :seon.agent/role :worker :seon.agent/auto-merge? true}])})
      (store/branch! conn :main :branch/w1-edited)
      (store/branch! conn :main :branch/w1-clean)
      (let [econn (store/connect cfg :branch/w1-edited)
            cconn (store/connect cfg :branch/w1-clean)
            m-task (random-uuid)]
        (try
          (d/transact conn {:tx-data [{:seon.agent.message/id m-task :seon.agent.message/seq 1
                                       :seon.agent.message/from :user :seon.agent.message/to :ag/w1-edited
                                       :seon.agent.message/type :task :seon.agent.message/body "Work"}
                                      {:seon.agent.message/id (random-uuid) :seon.agent.message/seq 2
                                       :seon.agent.message/from :user :seon.agent.message/to :ag/w1-clean
                                       :seon.agent.message/type :task :seon.agent.message/body "Work"}]})
          ;; Edited branch: 1 code edit + 2 tool turns (hits max-tool-turns = 2)
          (d/transact econn {:tx-data (concat
                                       (code/file-import-tx @econn "src/demo/w1_rec.clj"
                                                            "(ns demo.w1-rec)
(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))
" false)
                                       [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/w1-edited :seon.agent.eval/turn 1
                                         :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "c1" :seon.agent.eval/code "(+ 1 1)"
                                         :seon.agent.eval/seen-messages #{m-task}}
                                        {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/w1-edited :seon.agent.eval/turn 2
                                         :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "c2" :seon.agent.eval/code "(user/run-tests!)"}])})
          ;; Clean branch (no code edits): 2 tool turns (hits max-tool-turns = 2)
          (let [m-c (:seon.agent.message/id (first (map #(d/pull @conn (quote [:seon.agent.message/id]) (:e %))
                                                        (d/datoms @conn :avet :seon.agent.message/to :ag/w1-clean))))]
            (d/transact cconn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/w1-clean :seon.agent.eval/turn 1
                                          :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "cc1" :seon.agent.eval/code "(+ 1 1)"
                                          :seon.agent.eval/seen-messages #{m-c}}
                                         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/w1-clean :seon.agent.eval/turn 2
                                          :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "cc2" :seon.agent.eval/code "(+ 2 2)"}]}))
          ;; 1. Clean branch gets NO wrap-up nudge
          (let [sc (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/w1-clean)]
            (is (nil? (:wrapup-notify-op sc)))
            (is (false? (:needs-turn? sc)))
            (is (= :waiting (:status sc))))
          ;; 2. Edited branch gets wrap-up nudge op once via reconciler-proc
          (let [st0 (rec/reconciler-proc {:conn conn})
                [st1 out1] (rec/reconciler-proc st0 :db {:prev-commit nil :commit (d/commit-id @conn) :tx (:max-tx @conn)})
                wmsg (some #(when (= :turn-cap-wrapup (:type %)) %) (:tx out1))]
            (is (some? wmsg) "edited branch at turn cap must receive :turn-cap-wrapup nudge")
            (is (= :ag/w1-edited (:to wmsg)))
            (is (str/includes? (:body wmsg) "(user/ready!)"))
            ;; Second tick at same head-tx must NOT double-send
            (let [[_ out2] (rec/reconciler-proc st1 :exits {:agent-id :ag/w1-edited :ev :turn-idle :pid 1})]
              (is (nil? (some #(when (= :turn-cap-wrapup (:type %)) %) (:tx out2)))))
            ;; 3. Once wrap-up message lands on :main -> agent is :runnable for its 1 grace turn!
            (let [wmid (random-uuid)]
              (d/transact conn {:tx-data [{:seon.agent.message/id wmid :seon.agent.message/seq 3
                                           :seon.agent.message/from :reconciler :seon.agent.message/to :ag/w1-edited
                                           :seon.agent.message/type :turn-cap-wrapup :seon.agent.message/body (:body wmsg)}]})
              (let [sr (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/w1-edited)]
                (is (true? (:needs-turn? sr)))
                (is (= :runnable (:status sr))))
              ;; 4. If agent takes Turn 3 (seeing wrap-up) WITHOUT calling ready! -> streak is 3 (> 2), stops cleanly!
              (d/transact econn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/w1-edited :seon.agent.eval/turn 3
                                            :seon.agent.eval/call-index 0 :seon.agent.eval/call-id "c3" :seon.agent.eval/code "(+ 3 3)"
                                            :seon.agent.eval/seen-messages #{wmid}}]})
              (let [sp (rec/derive-agent-status @conn (:store @conn) {} #{} #{} #{} :ag/w1-edited)]
                (is (false? (:needs-turn? sp)) "Must grant strictly 1 wrap-up turn, not reset tool-streak")
                (is (nil? (:wrapup-notify-op sp)))
                (is (= :waiting (:status sp))))))
          (finally
            (d/release econn)
            (d/release cconn)))))))

(deftest ^{:cost/ticks 60000000} primers-content-and-zero-broken-doc-refs-test
  (testing "docs/primers.md carries Wave-1 high-ROI recipes and live DB has 0 :broken-doc-ref"
    (let [db (when fcalls/*agent-ctx* @(:conn fcalls/*agent-ctx*))
          ed (when db (:seon.code.doc/body (d/pull db (quote [:seon.code.doc/body]) [:seon.code.doc/id "docs/primers.md#editing"])))
          ct (when db (:seon.code.doc/body (d/pull db (quote [:seon.code.doc/body]) [:seon.code.doc/id "docs/primers.md#contracts"])))
          pr (when db (:seon.code.doc/body (d/pull db (quote [:seon.code.doc/body]) [:seon.code.doc/id "docs/primers.md#proofs"])))]
      (when db
        (is (str/includes? (str ed) "overwrites existing functions"))
        (is (str/includes? (str ed) "ONLY the (ns ...) header"))
        (is (str/includes? (str ed) "parallel `repl` tool calls"))
        (is (str/includes? (str ct) ">= 5000"))
        (is (str/includes? (str ct) "^:seon.flow/edge"))
        (is (str/includes? (str pr) "variadic"))
        (is (empty? (hcontracts/broken-doc-refs db))))
      (is (= 110000000 (:cost/ticks (meta (requiring-resolve (quote seon.core-test/kv-cache-prefix-stability-volatility-ordering-and-eval-preview-test)))))))))
