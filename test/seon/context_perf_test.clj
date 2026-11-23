(ns seon.context-perf-test
  "Regression and performance proofs for Wave C2 O(|Delta|) context engine algorithms:
   - `seon.rule/edited-form-eids` via `d/merge-base` + `d/changes` (0 `:main`-drift false forms)
   - `seon.ctx.ground/branch-form-drift` concurrent conflict detection even when `branch-tx > main-tx`
   - `:rule.ctx/failing-reach-tests` via `:seon.test.run/failed-digests` (< 10 ms, 0 ghost failures)
   - `:rule.ctx/error-neighborhood` dynamic error zoom-in & 2,000-token `:ephemeral-tail` Ladder cap
   - `fix-matches-scope?`, `strip-template-footer`, archived eval-touch filtering, test-ns require ranking,
     and `context_lab` `wire-request-cache` reuse."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.ctx :as ctx]
            [seon.ctx.ground :as ground]
            [seon.error :as serr]
            [seon.render.links :as rlinks]
            [seon.render.surface.context-lab :as clab]
            [seon.render.wire :as wire]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(deftest ^{:cost/ticks 90000000} edited-form-eids-and-concurrent-drift-test
  (testing "edited-form-eids returns ONLY branch-edited forms (0 :main-drift floods) and branch-form-drift catches concurrent edits even when bt > mt"
    (with-test-store [{:keys [conn cfg]}]
      (let [v1 "(ns demo.drift)\n(defn f1 [x] x)\n(defn f2 [x] x)\n(defn shared [x] x)\n"]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/drift.clj" v1 false)})
        (store/branch! conn :main :branch/untouched)
        (store/branch! conn :main :branch/feat)
        ;; Advance :main AFTER branch cut: edit f1 + shared + add main-only
        (let [v-main "(ns demo.drift)\n(defn f1 [x] (inc x))\n(defn f2 [x] x)\n(defn shared [x] (+ x 10))\n(defn main-only [x] (* x 2))\n"]
          (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/drift.clj" v-main false)}))
        ;; On :branch/feat (AFTER :main advanced, so branch-tx > main-tx!), edit f2 + shared
        (let [uconn (store/connect cfg :branch/untouched)
              fconn (store/connect cfg :branch/feat)]
          (try
            (let [v-feat "(ns demo.drift)\n(defn f1 [x] x)\n(defn f2 [x] (dec x))\n(defn shared [x] (+ x 99))\n"]
              (d/transact fconn {:tx-data (code/file-import-tx @fconn "src/demo/drift.clj" v-feat false)}))
            (let [mdb @conn udb @uconn fdb @fconn
                  u-eids (rules/edited-form-eids mdb udb)
                  f-eids (rules/edited-form-eids mdb fdb)
                  f-keys (into #{} (map #(:v (first (d/datoms fdb :eavt % :seon.code.form/key)))) f-eids)
                  drift (ground/branch-form-drift mdb fdb f-eids)]
              (is (= [] u-eids) "Untouched branch must report 0 edited forms despite :main advancing")
              (is (= #{"demo.drift/f2" "demo.drift/shared"} f-keys)
                  "Edited branch must return ONLY branch-edited forms, never :main-only or :main-drifted f1")
              (is (= ["demo.drift/shared"] (:drifted drift))
                  "Concurrent edit on demo.drift/shared must be detected as drifted even when branch-tx > main-tx"))
            (finally
              (d/release uconn)
              (d/release fconn))))))))

(deftest ^{:cost/ticks 90000000} failing-reach-tests-failed-digests-and-no-ghosts-test
  (testing ":rule.ctx/failing-reach-tests reads :seon.test.run/failed-digests directly (< 10 ms), never resurrects historical failures, and feeds :rule.ctx/primer-proofs"
    (with-test-store [{:keys [conn]}]
      (let [src "(ns demo.ft)\n(defn dead-fn [x] x)\n(defn live-fn [x] (inc x))\n"]
        (d/transact conn {:tx-data (concat
                                    (code/file-import-tx @conn "src/demo/ft.clj" src false)
                                    [{:seon.code.doc/id "docs/primers.md#proofs" :seon.code.doc/path "docs/primers.md"
                                      :seon.code.doc/order 1 :seon.code.doc/heading "Proofs" :seon.code.doc/body "Run tests."}
                                     {:seon.agent/id :ag/ft :seon.agent/branch :main :seon.agent/role :worker}])})
        (let [db0 @conn
              dead-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.ft/dead-fn)))
              live-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.ft/live-fn)))
              big-rep (apply str (repeat 200 "GHOST-FAILURE-LINE\n"))
              ghosts (for [i (range 20)]
                       {:seon.test/digest (str "dg-ghost-" i) :seon.test/var 'demo.ft/dead-fn
                        :seon.test/var-ref dead-eid :seon.test/kind :deftest
                        :seon.test/pass? false :seon.test/ms 1.0 :seon.test/report big-rep})]
          (d/transact conn {:tx-data (conj (vec ghosts)
                                           {:seon.test/digest "dg-live-1" :seon.test/var 'demo.ft/live-fn
                                            :seon.test/var-ref live-eid :seon.test/kind :deftest
                                            :seon.test/pass? false :seon.test/ms 2.0 :seon.test/report "real live fail"}
                                           {:seon.test.run/branch :main :seon.test.run/pass? false
                                            :seon.test.run/failed-digests #{"dg-live-1"}
                                            :seon.test.run/ran 1 :seon.test.run/cached 20})})
          (let [db1 @conn
                rs (filterv #(#{:rule.ctx/failing-reach-tests :rule.ctx/primer-proofs} (:seon.rule/id %))
                            (rules/load-rules db1 (rseed/starter-rules)))
                t0 (System/nanoTime)
                res (rules/fire-rules {:main db1 :branch db1} :ag/ft rs :wire)
                ms (/ (- (System/nanoTime) t0) 1e6)
                live-tv (:e (first (d/datoms db1 :avet :seon.test/digest "dg-live-1")))
                fr-run (get-in res [:runs :rule.ctx/failing-reach-tests])
                pr-run (get-in res [:runs :rule.ctx/primer-proofs])]
            (is (= 1 (:fired fr-run)) "Only the 1 live failed-digest must fire; 20 ghost failures must NOT resurrect")
            (is (true? (rules/wm-scalar (:wm res) live-tv :ctx.branch/failing-test?)))
            (is (pos? (:fired pr-run)) ":rule.ctx/primer-proofs must fire from :ctx.branch/failing-test? fact")
            (is (< ms 10.0) (str "failing-reach-tests exceeded 10 ms: " ms " ms")))
          ;; Green run clears failing-reach-tests completely
          (d/transact conn {:tx-data [[:db/retract [:seon.test.run/branch :main] :seon.test.run/failed-digests "dg-live-1"]
                                      {:seon.test.run/branch :main :seon.test.run/pass? true :seon.test.run/ran 1 :seon.test.run/cached 20}]})
          (let [db2 @conn
                fr (some #(when (= :rule.ctx/failing-reach-tests (:seon.rule/id %)) %) (rules/load-rules db2 (rseed/starter-rules)))
                res2 (rules/fire-rules {:main db2 :branch db2} :ag/ft [fr] :wire)]
            (is (= 0 (get-in res2 [:runs :rule.ctx/failing-reach-tests :fired])))))))))

(deftest ^{:cost/ticks 90000000} fix-scope-grounding-archived-filter-and-test-requires-test
  (testing "fix-matches-scope? blocks 'seon flood, strip-template-footer cleans boilerplate, archived eval-touches filter out, and links ranks <scope>-*test up to 6"
    ;; 1. fix-matches-scope?
    (is (false? (ground/fix-matches-scope? 'seon :demo/err "Fixed seon.foo/bar" 'seon.foo-test/t1)))
    (is (false? (ground/fix-matches-scope? nil :demo/err "Fixed seon.foo/bar" 'seon.foo-test/t1)))
    (is (true? (ground/fix-matches-scope? 'seon.foo :demo/err "Fixed seon.foo/bar" 'seon.foo-test/t1)))
    (is (false? (ground/fix-matches-scope? 'seon.other :demo/err "Fixed seon.foo/bar" 'seon.foo-test/t1)))
    (is (true? (ground/fix-matches-scope? 'seon :demo/lmdb-leak "Fixed lmdb" 't1 "Investigate :demo/lmdb-leak")))
    (is (false? (ground/fix-matches-scope? 'seon :demo/lmdb-leak "Fixed lmdb" 't1 "Interactive demo tour")))

    ;; 2. strip-template-footer cleans both --- footers and direct boilerplate plans
    (is (= "Fix parser bug" (ground/strip-template-footer "Fix parser bug\n\n---\n1. **Discover first:** Inspect `:seon.schema/key`")))
    (is (= "" (ground/strip-template-footer "1. **Discover first:** Inspect `@seon.ctx/ctx`, schemas (`:seon.schema/key`), callers (`user/callers`).")))

    ;; 3. Archived agent filtered from :signal/eval-touched & links test-ns requires > 2 with <scope>-*test first
    (with-test-store [{:keys [conn]}]
      (let [src-lib "(ns demo.pkg.core)\n(defn run-it [x] (inc x))\n"
            mk-test (fn [tns] (str "(ns " tns " (:require [clojure.test :refer [deftest is]] [demo.pkg.core :as c]))\n(deftest t1 (is (= 2 (c/run-it 1))))\n"))]
        (d/transact conn {:tx-data (concat
                                    (code/file-import-tx @conn "src/demo/pkg/core.clj" src-lib false)
                                    (code/file-import-tx @conn "test/aaa/t1_test.clj" (mk-test "aaa.t1-test") false)
                                    (code/file-import-tx @conn "test/bbb/t2_test.clj" (mk-test "bbb.t2-test") false)
                                    (code/file-import-tx @conn "test/ccc/t3_test.clj" (mk-test "ccc.t3-test") false)
                                    (code/file-import-tx @conn "test/demo/pkg/core_test.clj" (mk-test "demo.pkg.core-test") false)
                                    [{:seon.agent/id :ag/viewer :seon.agent/branch :main :seon.agent/role :worker :seon.agent/scope 'demo.pkg.core}
                                     {:seon.agent/id :ag/old :seon.agent/branch :main :seon.agent/role :worker :seon.agent/archived? true}])})
        (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn)})
        (let [db0 @conn
              old-eid (:e (first (d/datoms db0 :avet :seon.agent/id :ag/old)))
              var-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.pkg.core/run-it)))
              _ (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/viewer
                                             :seon.agent.eval/turn 1 :seon.agent.eval/code "(demo.pkg.core/run-it 1)"
                                             :seon.agent.eval/touched [old-eid var-eid]}]})
              db1 @conn
              cands (:candidates (ctx/gather-agent-candidates db1 db1 :ag/viewer {:target :wire :use-cache? false :semantic? false}))
              c-eids (into #{} (map :entity) cands)
              ns-ent (d/pull db1 '[*] [:seon.code.ns/name 'demo.pkg.core])
              ns-ep (rlinks/enrich-ns-params db1 db1 ns-ent {:seon.agent/id :ag/viewer :seon.agent/scope 'demo.pkg.core})
              treqs (:seon.ui/ns-test-reqs ns-ep)]
          (is (contains? c-eids var-eid) "Active touched var must stay in candidates")
          (is (not (contains? c-eids old-eid)) "Archived agent in :seon.agent.eval/touched must be filtered out")
          (is (= 4 (count treqs)) (str "Expected all 4 reaching test namespaces (no take 2 truncation), got: " treqs))
          (is (str/starts-with? (first treqs) "demo.pkg.core-test") "Direct <ns>-test must rank first"))))))

(deftest ^{:cost/ticks 90000000} error-neighborhood-tail-cap-and-context-lab-cache-test
  (testing ":rule.ctx/error-neighborhood fires on eval/error refs, :ephemeral-tail caps <= 2000 tok, and context_lab reuses wire-request-cache"
    (with-test-store [{:keys [conn]}]
      (let [reg! (fn [k form doc]
                   (let [{:keys [dh-decls entity]} (schema/validate-schema-registration! @conn {:key k :form form :doc doc})]
                     (d/transact conn {:tx-data (vec (concat dh-decls [entity]))})))]
        (reg! :seon.demo.err/id [:and {:seon.db/identity true} :keyword] "Demo err id")
        (reg! :seon.demo.err/widget [:map {:seon.db/attributes true} [:seon.demo.err/id :seon.demo.err/id]] "Demo err widget"))
      (let [src "(ns demo.err)\n(defn broken-fn [x] x)\n(defn err-var-fn [x] (inc x))\n"
            huge-report (apply str (repeat 400 "0123456789abcdef0123456789abcdef\n"))]
        (d/transact conn {:tx-data (concat
                                    (code/file-import-tx @conn "src/demo/err.clj" src false)
                                    [{:seon.task/id :task/err-z :seon.task/title "Fix demo.err" :seon.task/scope 'demo.err :seon.task/done? false}
                                     {:seon.agent/id :ag/err :seon.agent/branch :main :seon.agent/role :worker
                                      :seon.agent/scope 'demo.err :seon.agent/task :task/err-z}
                                     (serr/error-tx {:kind :demo/var-fault :severity :error :source :eval
                                                     :agent :ag/err :var 'demo.err/err-var-fn :message "Fault in err-var-fn"})
                                     {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :ag/err
                                      :seon.agent.eval/turn 1 :seon.agent.eval/handle 'result/e1
                                      :seon.agent.eval/code "(demo.err/broken-fn {:seon.demo.err/id :a})"
                                      :seon.agent.eval/error "Contract violation in demo.err/broken-fn on :seon.demo.err/widget"}])})
        (let [db0 @conn
              b-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.err/broken-fn)))
              o-eid (:e (first (d/datoms db0 :avet :seon.code.var/name 'demo.err/err-var-fn)))
              s-eid (:e (first (d/datoms db0 :avet :seon.schema/key :seon.demo.err/widget)))
              en-rule (some #(when (= :rule.ctx/error-neighborhood (:seon.rule/id %)) %) (rules/load-rules db0 (rseed/starter-rules)))
              r-wire (rules/fire-rules {:main db0 :branch db0} :ag/err [en-rule] :wire)
              r-cv (rules/fire-rules {:main db0 :branch db0} :ag/err [en-rule] :canvas)
              p-eids (into #{} (map :entity) (:projections r-wire))]
          (is (contains? p-eids b-eid) "Eval error var must zoom into tail")
          (is (contains? p-eids s-eid) "Eval error schema must zoom into tail")
          (is (contains? p-eids o-eid) "Open :seon.error/var must zoom into tail")
          (is (every? #(= :ephemeral-tail (:zone %)) (:projections r-wire)))
          (is (empty? (:projections r-cv)) ":rule.ctx/error-neighborhood must be :target :wire only")

          ;; Add 3 huge failing tests so tail would blow past 2,000 tokens without cap
          (d/transact conn {:tx-data [{:seon.test/digest "dg-huge-1" :seon.test/var 'demo.err/broken-fn
                                       :seon.test/var-ref b-eid :seon.test/kind :deftest :seon.test/pass? false
                                       :seon.test/ms 1.0 :seon.test/report huge-report}
                                      {:seon.test/digest "dg-huge-2" :seon.test/var 'demo.err/err-var-fn
                                       :seon.test/var-ref o-eid :seon.test/kind :deftest :seon.test/pass? false
                                       :seon.test/ms 1.0 :seon.test/report huge-report}
                                      {:seon.test.run/branch :main :seon.test.run/pass? false
                                       :seon.test.run/failed-digests #{"dg-huge-1" "dg-huge-2"}
                                       :seon.test.run/ran 2 :seon.test.run/cached 0}]})
          (let [db1 @conn
                ag (d/pull db1 '[*] [:seon.agent/id :ag/err])
                w (wire/build-live-wire-request db1 db1 :ag/err {:conn conn :vision? false})
                lm (clab/build-context-lab-model db1 db1 :ag/err ag :main {:conn conn :vision? false :lab-prefs {}})]
            (is (<= (long (get-in w [:fit-trace :tail-tokens] 0)) 2000)
                (str "Ephemeral tail exceeded 2000-token cap: " (get-in w [:fit-trace :tail-tokens])))
            (is (identical? (:messages w) (:messages lm))
                "context_lab on default prefs must reuse cached wire-request-cache result directly")
            (is (some? (:explained lm)))))))

    ;; Seed=42 Malli generative checks on pure ground helpers
    (doseq [sym '[seon.ctx.ground/fix-matches-scope?
                  seon.ctx.ground/strip-template-footer]]
      (let [res (schema/stress-var nil sym {:samples 100 :seed 42})]
        (is (:ok? res) (str sym " failed seed=42 check: " (pr-str res)))))))
