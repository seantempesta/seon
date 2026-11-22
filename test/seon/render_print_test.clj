(ns seon.render-print-test
  "Proofs for Seon's 4-Stage Unified Schema Self-Printer, Breadth-Before-Depth Selection Tree,
   Declarative `:rule.preview/*` Policy, 10k Smart Previews, `(user/expand! ...)`, and `seed = 42` Generative Checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.model.wire :as mwire]
            [seon.render :as render]
            [seon.render.print :as sprint]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]))

(defn test-custom-tile
  "Test Hiccup renderer verifying recursive `:layout` and `:mode` args."
  {:malli/schema [:=> [:cat :map :map] :any]
   :cost/ticks 50000
   :cost/bytes 32768}
  [opts val]
  [:div {:class "test-tile"}
   (str "layout=" (:layout opts) "|mode=" (:mode opts) "|id=" (or (:test.custom/id val) (:seon.error/kind val)))])

(deftest ^{:cost/ticks 80000000} stage1-recursive-subschema-and-cycle-guard-test
  (testing "Nested sub-schema and lookup-ref dispatch, depth layout escalation, and cycle guard in MD + Hiccup"
    (with-test-store [{:keys [conn]}]
      (d/transact conn
                  {:tx-data
                   [{:seon.schema/key :test.custom/id
                     :seon.schema/form '[:keyword {:seon.db/identity true}]}
                    {:seon.schema/key :test.custom/title
                     :seon.schema/form :string}
                    {:seon.schema/key :test.custom/item
                     :seon.schema/form '[:map {:seon.db/attributes true
                                                :seon.render/hiccup seon.render-print-test/test-custom-tile}
                                         [:test.custom/id :test.custom/id]
                                         [:test.custom/title :test.custom/title]]}
                    {:seon.task/id :task/printer-demo
                     :seon.task/title "Demo printer task"
                     :seon.task/done? false}]})
      (let [db @conn
            leaf {:test.custom/id :c/leaf :test.custom/title "Leaf"}
            mid {:test.custom/id :c/mid :test.custom/title "Mid" :test.custom/child leaf}
            root {:test.custom/id :c/root :test.custom/title "Root" :test.custom/child mid}
            ;; 1. Built-in task schema lookup ref & map dispatch at depth 1
            wrap {:owner "alice" :ref [:seon.task/id :task/printer-demo]}
            wt (sprint/select-tree db wrap {})
            wmd (sprint/tree->md wt {})
            wh (sprint/tree->hiccup wt {})]
        (is (= :rendered-sub (get-in (into {} (:entries wt)) [:ref :kind])))
        (is (= :compact-row (get-in (into {} (:entries wt)) [:ref :layout])))
        (is (str/includes? wmd "Demo printer task"))
        (is (str/includes? (render/hiccup->text wh) "Demo printer task"))
        ;; 2. Depth 0 -> :expanded-card, depth 1 -> :compact-row, depth 2 -> :ref-pill
        (let [t0 (sprint/select-tree db root {:render-root? true :mode :human})
              t1 (sprint/select-tree db {:nested mid} {:max-depth 2 :mode :model})
              t2 (sprint/select-tree db {:a {:b leaf}} {:max-depth 2 :mode :human})
              n1 (get-in (into {} (:entries t1)) [:nested])
              n2 (get-in (into {} (:entries (get-in (into {} (:entries t2)) [:a]))) [:b])]
          (is (= :rendered-sub (:kind t0)))
          (is (str/includes? (str (:hiccup t0)) "layout=:expanded-card|mode=:human"))
          (is (= :rendered-sub (:kind n1)))
          (is (str/includes? (str (:hiccup n1)) "layout=:compact-row|mode=:model"))
          (is (= :rendered-sub (:kind n2)))
          (is (str/includes? (str (:hiccup n2)) "layout=:ref-pill|mode=:human")))
        ;; 3. Cycle guard prevents infinite recursion on self-referential entities
        (let [cyc (assoc leaf :test.custom/child leaf)
              ct (sprint/select-tree db cyc {:render-root? false})
              ce (into {} (:entries ct))
              cmd (sprint/tree->md ct {})]
          (is (= :cycle-ref (get-in ce [:test.custom/child :kind])))
          (is (str/includes? cmd "↺ ref [:test.custom/id :c/leaf]")))))))

(deftest ^{:cost/ticks 80000000} stage2-policy-and-error-grouping-test
  (testing "Declarative :rule.preview/* rules derive print policy and group > 10 :seon.error/entity collections"
    (with-test-store [{:keys [conn]}]
      (let [db @conn
            pol (sprint/derive-print-policy db)
            errs (vec (for [i (range 100)]
                        {:seon.error/kind (case (mod i 3) 0 :missing-contract 1 :eval-fault :parse-error)
                         :seon.error/at (+ 5000 i)
                         :seon.error/message (str "msg-" i)
                         :seon.error/source :eval}))
            et (sprint/select-tree db errs {})
            emd (sprint/tree->md et {})
            eh (sprint/tree->hiccup et {})]
        (is (= {:expanded-card :expanded-card :compact-row :compact-row :ref-pill :ref-pill}
               {:expanded-card (get-in pol [:depth-layouts 0])
                :compact-row (get-in pol [:depth-layouts 1])
                :ref-pill (get-in pol [:depth-layouts :deep])}))
        (is (= 20 (:collection-limit pol)))
        (is (true? (:errors-by-kind? pol)))
        (is (= :grouped-errors (:kind et)))
        (is (= 100 (:total et)))
        (is (= 100 (reduce + (vals (:groups et)))))
        (is (= 5 (count (:items et))))
        (is (= 95 (:remaining et)))
        (is (str/includes? emd "Grouped errors (100 :seon.error/entity"))
        (is (str/includes? emd "user/expand!"))
        (is (str/includes? (render/hiccup->text eh) "100 errors"))
        ;; Overriding rule (:enabled? false) disables error grouping cleanly via step-rules
        (let [no-grp (mapv #(if (= :rule.preview/errors-by-kind (:seon.rule/id %))
                              (assoc % :seon.rule/enabled? false) %)
                           (sprint/preview-rules))
              et2 (sprint/select-tree db errs {:rules no-grp})]
          (is (= :vector (:kind et2)))
          (is (= 20 (count (:items et2)))))))))

(deftest ^{:cost/ticks 60000000} stage3-breadth-before-depth-and-10k-test
  (testing "Breadth-before-depth keeps late siblings alive and 10k collections print < 50ms with zero clipping"
    (with-test-store [{:keys [conn]}]
      (let [db @conn
            bfs-val {:huge (vec (range 10000)) :important "must-see" :status :green :tail {:note "kept"}}
            t (sprint/select-tree db bfs-val {:budget 400})
            md (sprint/tree->md t {})
            hc (sprint/tree->hiccup t {})]
        (is (str/includes? md "must-see"))
        (is (str/includes? md ":green"))
        (is (str/includes? md "kept"))
        (is (str/includes? md "(user/expand! 'result/eN"))
        (is (str/includes? (render/hiccup->text hc) "must-see"))
        (doseq [[label coll] [["vec" (vec (range 10000))]
                              ["map" (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 10000)))]
                              ["set" (set (range 10000))]]]
          (let [t0 (System/nanoTime)
                desc (sprint/describe-value db coll {})
                out-md (sprint/tree->md (:tree desc) {})
                ms (/ (- (System/nanoTime) t0) 1e6)]
            (testing label
              (is (< ms 50.0))
              (is (= 10000 (:total desc)))
              (is (true? (:more? desc)))
              (is (= 9980 (:remaining desc)))
              (is (= 20 (count (:preview desc))))
              (is (str/includes? (:expand-hint desc) "(user/expand! 'result/eN"))
              (is (str/includes? out-md "10000")))))
        ;; Wire output preserves unescaped short code & bounded large map/string invariants
        (let [code-out (mwire/format-eval-output {:handle 'result/e2 :ok? true :value (pr-str "(defn f [x]\n  (inc x))")})
              big-str (str/join "\n" (map #(str "line-" %) (range 120)))
              str-out (mwire/format-eval-output {:handle 'result/e8 :ok? true :value (pr-str big-str)})]
          (is (str/includes? code-out "(defn f [x]\n  (inc x))"))
          (is (not (str/includes? code-out "\\n")))
          (is (< (count str-out) 8000))
          (is (str/includes? str-out "Large string (120 lines"))
          (is (str/includes? str-out "result/e8")))))))

(deftest ^{:cost/ticks 80000000} expand-result-and-helpers-test
  (testing "expand-result slices result/e<n>, drills nested :path, filters/reverses, queries DB patterns, and installs user/expand!"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data [{:seon.task/id :task/exp-1 :seon.task/title "T1" :seon.task/done? false}
                                  {:seon.task/id :task/exp-2 :seon.task/title "T2" :seon.task/done? false}]})
      (let [rns (create-ns 'result)
            tmp-ns (create-ns 'seon.test-tmp-expand)]
        (try
          (intern rns 'e_test_99 {:items (vec (range 100)) :meta {:ok? true}})
          (let [db @conn
                s1 (sprint/expand-result db 'result/e_test_99 {:path [:items] :offset 20 :limit 10})
                s2 (sprint/expand-result db 'result/e_test_99 {:path [:items] :filter even? :offset 5 :limit 4})
                s3 (sprint/expand-result db 'result/e_test_99 {:path [:items] :reverse? true :offset 0 :limit 3})
                sdb (sprint/expand-result db [nil :seon.task/id nil] {:offset 0 :limit 1})]
            (is (:ok? s1))
            (is (= (vec (range 20 30)) (:preview s1)))
            (is (= 100 (:total s1)))
            (is (true? (:more? s1)))
            (is (= [10 12 14 16] (:preview s2)))
            (is (= [99 98 97] (:preview s3)))
            (is (:ok? sdb))
            (is (= :db-slice (:kind sdb)))
            (is (= 1 (count (:preview sdb))))
            (is (>= (:total sdb) 2))
            (is (str/includes? (:expand-hint sdb) "[nil :seon.task/id nil]"))
            ;; install-user-helpers!
            (sprint/install-user-helpers! tmp-ns (fn [] {:db db :branch-db db}))
            (let [v (ns-resolve tmp-ns 'expand!)
                  md-out (@v 'result/e_test_99 {:path [:items] :offset 20 :limit 3})
                  raw-out (@v 'result/e_test_99 {:path [:items] :offset 20 :limit 3 :raw? true})]
              (is (string? (:doc (meta v))))
              (is (map? (:seon.eval/budget-override (meta v))))
              (is (str/includes? md-out "showing 21..23"))
              (is (= [20 21 22] (:preview raw-out)))))
          (finally
            (ns-unmap rns 'e_test_99)
            (remove-ns 'seon.test-tmp-expand)))
        ;; Datahike fallback when live `result` var is not in memory (stateless CLI / post-restart)
        (d/transact conn {:tx-data [{:seon.agent.eval/id (random-uuid)
                                     :seon.agent.eval/agent :repl/test
                                     :seon.agent.eval/turn 1
                                     :seon.agent.eval/handle 'result/e_db_only
                                     :seon.agent.eval/code "(vec (range 50))"
                                     :seon.agent.eval/value (pr-str (vec (range 50)))
                                     :seon.agent.eval/out ""
                                     :seon.agent.eval/ticks 100
                                     :seon.agent.eval/bytes 1024
                                     :seon.agent.eval/ms 1.0}]})
        (let [db-fb (sprint/expand-result @conn 'result/e_db_only {:offset 10 :limit 5 :agent-id :repl/test})]
          (is (:ok? db-fb))
          (is (= [10 11 12 13 14] (:preview db-fb)))
          (is (= 50 (:total db-fb))))))))

(deftest ^{:cost/ticks 60000000} multi-facet-and-db-handles-test
  (testing "Multi-facet entity maps surface ALL facets, and DB/Conn handles & lookup refs summarize cleanly"
    (with-test-store [{:keys [conn]}]
      (d/transact conn
                  {:tx-data
                   [{:seon.schema/key :test.facet/id
                     :seon.schema/form '[:keyword {:seon.db/identity true}]}
                    {:seon.schema/key :test.facet/a
                     :seon.schema/form :string}
                    {:seon.schema/key :test.facet/b
                     :seon.schema/form :int}
                    {:seon.schema/key :test.facet/alpha
                     :seon.schema/form '[:map {:seon.db/attributes true}
                                         [:test.facet/id :test.facet/id] [:test.facet/a :test.facet/a]]}
                    {:seon.schema/key :test.facet/beta
                     :seon.schema/form '[:map {:seon.db/attributes true}
                                         [:test.facet/id :test.facet/id] [:test.facet/b :test.facet/b]]}]})
      (let [db @conn
            dual {:test.facet/id :f/1 :test.facet/a "hi" :test.facet/b 42}
            desc (sprint/describe-value db dual {})
            md (sprint/format-value-md db dual {})
            db-desc (sprint/describe-value db db {})
            conn-desc (sprint/describe-value db conn {})
            ref-desc (sprint/describe-value db [:test.facet/id :f/1] {})]
        (is (= #{:test.facet/alpha :test.facet/beta} (set (:facets desc))))
        (is (str/includes? md "+facets"))
        (is (= :datahike/db (:kind db-desc)))
        (is (= :datahike/conn (:kind conn-desc)))
        (is (= :lookup-ref (:kind ref-desc)))
        (is (contains? #{:test.facet/alpha :test.facet/beta} (:schema ref-desc)))))))

(deftest ^{:cost/ticks 80000000} generative-seed42-part1-test
  (testing "Pure public fns in expand, policy, and schema pass 100-sample seed=42 Malli generative checks"
    (doseq [sym '[seon.render.print.expand/real-vec?
                  seon.render.print.expand/norm-opts
                  seon.render.print.expand/expand-form-data
                  seon.render.print.expand/expand-hint-str
                  seon.render.print.expand/drill-path
                  seon.render.print.expand/slice-value
                  seon.render.print.policy/preview-rules
                  seon.render.print.policy/compute-print-policy
                  seon.render.print.schema/valid-ix?
                  seon.render.print.schema/handle-info
                  seon.render.print.schema/fingerprint-map
                  seon.render.print.schema/entity-cycle-id
                  seon.render.print.schema/detect-elem-info]]
      (let [r (schema/stress-var nil sym {:samples 100 :seed 42})]
        (is (:ok? r) (str sym " failed generative stress-var: " (pr-str r)))))))

(deftest ^{:cost/ticks 80000000} generative-seed42-part2-test
  (testing "Pure public fns in select, emit, and print pass 100-sample seed=42 Malli generative checks"
    (doseq [sym '[seon.render.print.select/select-with-index
                  seon.render.print.emit/hiccup->md
                  seon.render.print/preview-rules
                  seon.render.print/select-with-index
                  seon.render.print/describe-with-index
                  seon.render.print/slice-value]]
      (let [r (schema/stress-var nil sym {:samples 100 :seed 42})]
        (is (:ok? r) (str sym " failed generative stress-var: " (pr-str r)))))))
