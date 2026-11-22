(ns seon.render-tile-walk-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.render.walk :as rwalk]
            [seon.test-fixture :refer [with-test-store]]
            [seon.web :as web]))

(defn- timed-step
  [walk db tx-datoms st]
  (let [t0 (System/nanoTime)
        res (render/step-walk walk db tx-datoms (ui/page-unit-specs st))
        ms (/ (double (- (System/nanoTime) t0)) 1e6)]
    (assoc res :ms ms)))

(deftest ^{:cost/ticks 80000000} incremental-tile-walk-skip-dom-ids-and-frame-budget-test
  (testing "Decomposed :workspace and :debug units preserve all DOM IDs, skip untouched cards on single-entity edits, and render well within 40 ms"
    (with-test-store [{:keys [conn]}]
      (d/transact
       conn
       {:tx-data
        [{:seon.code.doc/id "docs/d1.md#one" :seon.code.doc/path "docs/d1.md"
          :seon.code.doc/heading "Doc One" :seon.code.doc/body "Alpha doc body"}
         {:seon.code.doc/id "docs/d2.md#two" :seon.code.doc/path "docs/d2.md"
          :seon.code.doc/heading "Doc Two" :seon.code.doc/body "Beta doc body"}
         {:seon.task/id :task/t1 :seon.task/title "Task One"
          :seon.task/summary "Primary task summary" :seon.task/done? false}
         {:seon.task/id :task/t2 :seon.task/title "Task Two"
          :seon.task/summary "Unrelated task summary" :seon.task/done? false}
         {:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/role :coordinator
          :seon.agent/task :task/t1
          :seon.agent/pinned [[:seon.task/id :task/t1] [:seon.code.doc/id "docs/d1.md#one"]]}
         {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
          :seon.user/view-mode :workspace :seon.user/sidebar-open? true}]})

      (let [st {:boot-seq 1 :conn conn :agent-states {:agent/root :idle}
                :live-streams {} :errors [] :walk-summary []}
            ws-cold (timed-step (render/empty-walk) @conn nil st)
            ws-walk (:walk ws-cold)
            ws-shell (#'web/page-shell ws-walk @conn)]
        (is (< (:ms ws-cold) 40.0) (str "Cold :workspace render took " (:ms ws-cold) " ms (>= 40 ms)"))
        (is (some #{"seon-view-unit"} (:order ws-walk)))
        (doseq [id ["seon-app" "seon-header" "seon-sidebar" "seon-view-unit"
                    "ws-left-unit" "ws-right-head-unit" "seon-stream-unit" "seon-composer-unit"]]
          (is (str/includes? ws-shell (str "id=\"" id "\"")) (str "Missing Workspace DOM ID #" id)))
        (is (str/includes? ws-shell "Task One"))
        (is (str/includes? ws-shell "Doc One"))

        (let [tx-d2 (:tx-data (d/transact conn {:tx-data [{:seon.code.doc/id "docs/d2.md#two"
                                                            :seon.code.doc/body "Beta doc edited"}]}))
              ws-d2 (timed-step ws-walk @conn tx-d2 st)
              patch-str (str/join "\n" (:patches ws-d2))]
          (is (pos? (:skipped ws-d2)))
          (is (< (:ms ws-d2) 40.0))
          (is (not (str/includes? patch-str "id=\"seon-view-unit\"")))

          (let [tx-t2 (:tx-data (d/transact conn {:tx-data [{:seon.task/id :task/t2
                                                              :seon.task/summary "Unrelated task edited"}]}))
                ws-t2 (timed-step (:walk ws-d2) @conn tx-t2 st)
                p-t2 (str/join "\n" (:patches ws-t2))]
            (is (pos? (:skipped ws-t2)))
            (is (not (str/includes? p-t2 "id=\"seon-view-unit\"")))

            (let [tx-t1 (:tx-data (d/transact conn {:tx-data [{:seon.task/id :task/t1
                                                                :seon.task/summary "Primary task v2"}]}))
                  ws-t1 (timed-step (:walk ws-t2) @conn tx-t1 st)
                  p-t1 (str/join "\n" (:patches ws-t1))]
              (is (pos? (:skipped ws-t1)))
              (is (str/includes? p-t1 "id=\"seon-view-unit\""))
              (is (str/includes? p-t1 "Primary task v2"))
              (is (not (str/includes? p-t1 "id=\"seon-header\"")))

              (let [ws-stream (timed-step (:walk ws-t1) @conn []
                                          (assoc st :live-streams {:agent/root {:status :token :phase :writing
                                                                                :started-ms 1000 :last-ms 1500
                                                                                :step-chars 64 :chars 64 :text "Working"}}))]
                (is (= 1 (:rerendered ws-stream)))
                (is (= (dec (count (:order (:walk ws-t1)))) (:skipped ws-stream)))
                (is (str/includes? (first (:patches ws-stream)) "id=\"seon-stream-unit\""))
                (is (< (:ms ws-stream) 5.0))

                (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/view-mode :debug}]})
                (let [dbg-cold (timed-step (:walk ws-stream) @conn nil st)
                      dbg-walk (:walk dbg-cold)
                      dbg-shell (#'web/page-shell dbg-walk @conn)
                      eff-dbg (rwalk/effective-patches (:walk ws-stream) dbg-walk (:patches dbg-cold) @conn)
                      dbg-warm (timed-step dbg-walk @conn [] st)
                      tx-d2b (:tx-data (d/transact conn {:tx-data [{:seon.code.doc/id "docs/d2.md#two"
                                                                   :seon.code.doc/body "Beta doc v3"}]}))
                      dbg-unrelated (timed-step (:walk dbg-warm) @conn tx-d2b st)]
                  (is (< (:ms dbg-cold) 40.0) (str "Cold :debug render took " (:ms dbg-cold) " ms (>= 40 ms)"))
                  (is (< (:ms dbg-warm) 5.0) (str "Warm :debug render took " (:ms dbg-warm) " ms (>= 5 ms)"))
                  (is (< (:ms dbg-unrelated) 5.0))
                  (is (some #(str/includes? % "id=\"seon-view-unit\"") eff-dbg))
                  (doseq [id ["seon-app" "seon-header" "seon-sidebar" "seon-view-unit"
                              "dbg-feedback-unit" "dbg-wire-unit" "dbg-ctx-unit"
                              "dbg-rules-unit" "dbg-rule-trace" "dbg-versions-unit"
                              "dbg-health-unit" "dbg-branches-unit" "dbg-schema-unit"
                              "dbg-tests-unit" "dbg-drill-unit"]]
                    (is (str/includes? dbg-shell (str "id=\"" id "\"")) (str "Missing Debug DOM ID #" id)))
                  (is (= 0 (:rerendered dbg-warm)))
                  (is (= (count (:order dbg-walk)) (:skipped dbg-warm)))
                  (is (= 0 (:rerendered dbg-unrelated)))
                  (is (= (count (:order dbg-walk)) (:skipped dbg-unrelated))))))))))))
