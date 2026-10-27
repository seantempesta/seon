;; Row 36 measured (task: printer-eval): what one new evaluation sends to an agent's open page.
;; The corpus's evaluation records (their :form, :shown, :fns), 100 of them on the page, then one
;; more: the patch the render proc sends now (the new row's unit, prepended), against what the
;; history unit sent before (2026-10-23, the whole list: every row's HTML again, as one fragment).
;; Run: HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/units36.clj
(require '[seon.edn :as edn] '[seon.render.proc :as p] '[seon.render.ui :as ui] '[seon.web.html :as h] '[clojure.string :as str])

(def records (vec (for [c (edn/decode (slurp "notes/printer-eval/corpus.edn")) :when (= :eval-record (:kind c))] (:value c))))
(def evals (fn [n] (mapv #(assoc (nth records (mod % (count records))) :id %) (range n))))
(def view0 {:agents {:a1 {:branch :agent-1 :status :idle :task "t1" :evals []}}
            :writer {:tx-count 1 :recent []} :fork-server {:status :up} :stats {} :store {} :boot {}})
(defn answer [st view]
  (#'p/render-proc (assoc st :pending {1 :state} :main-tx 1) :answers {:tag 1 :r view}))
(def st0 {:rid 1 :pending {} :watch #{:a1} :waiting {} :kf-wanted #{} :new-events [] :events [] :views {} :zoom {} :merges 0 :main {:t 1}})
(defn patch [out] (first (filter #(and (= :patch (:op %)) (= :a1 (:view %))) (:to-web out))))
(defn bytes-of [pt] (reduce + (map (comp count second) (concat (:frags pt) (:prepends pt) (:appends pt)))))

(let [[st1 _] (answer st0 (assoc-in view0 [:agents :a1 :evals] (evals 100)))
      [_ out] (answer st1 (assoc-in view0 [:agents :a1 :evals] (evals 101)))
      now (patch out)
      ;; before: the history unit's input was every row, so its fragment was the whole list
      rows (for [r (rseq (evals 101))] (h/html (:seon.render/html (ui/history-row r {}))))
      before (h/html [:ol {:id "z-history" :class "history"} (map h/raw (take 100 rows)) [:li {:class "more muted"} "+1 more: older evaluations"]])]
  (println "one new evaluation on a page of 100: the history sends" (reduce + (map (comp count second) (:prepends now))) "bytes now (its row, prepended),"
           (count before) "before (the whole list); the patch's other fragments, the same before and now:" (mapv first (:frags now))))
