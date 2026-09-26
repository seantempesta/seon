;; Prototype for docs/research/agent-platform/eval-feedback-design-2026-09-26.md §4.
;; Run: bb -cp reference-code/malli/src tmp/eval-feedback-design-opus/findings.clj
;;
;; The claim under test: every misuse rule is a declared Malli bound over one
;; measured cost value, and the findings are exactly `m/explain`'s errors — no
;; rule-specific code, no schema- or symbol-specific branch. Changing a rule is
;; changing data (the bound), never this function.
(require '[malli.core :as m])

;; The declared bounds (in Seon: a config fact holding this schema form).
;; Each entry is [measure {:level ... :fix ...} bound]. `:level` and `:fix`
;; are properties of the declaration, read back from the explain error's
;; schema, so the checker never names a measure.
(def declared-bounds
  [:map
   ;; The agent is judged on its OWN time only: wall time mixes in Seon's
   ;; acquisition/admission/render, and blaming the agent for those is the
   ;; defect today's explain-slow text has.
   [:seon.eval/own-ms {:optional true :level :loud :owner :agent
                       :fix "Over one second in your own code: find what the work is proportional to and remove it."}
    [:<= 1000]]
   [:seon.eval/seon-ms {:optional true :level :loud :owner :seon
                        :fix "Platform overhead: recorded as a fault for root; not your defect."}
    [:<= 250]]
   [:seon.eval/unshown-tokens {:optional true :level :note :owner :agent
                               :fix "Most of the value was not shown: select what you need instead of returning it whole."}
    [:<= 2000]]
   [:seon.eval/unbounded-reads {:optional true :level :loud :owner :agent
                                :fix "A query read every attribute of the store: bind the attribute."}
    [:= 0]]
   [:seon.eval/unrecorded-defs {:optional true :level :note :owner :agent
                                :fix "These defs live only in your context; make a contracted defn or transact data to keep them."}
    [:= 0]]
   [:seon.eval/allocated-mb {:optional true :level :loud :owner :agent
                             :fix "Allocation is out of proportion to the result: realize less."}
    [:<= 512]]
   [:seon.eval/out-tokens {:optional true :level :note :owner :agent
                           :fix "Printed output is re-read every turn: return a value instead of printing."}
    [:<= 500]]])

(defn findings
  "Every declared bound the cost value exceeds, as data.

  Each finding names the measure, the observed value, the bound form, and the
  declaration's own level, owner and fix."
  [bounds cost]
  (into []
        (map (fn [{:keys [in value schema]}]
               (let [entry (some #(when (= (first %) (first in)) %) (m/children (m/schema bounds)))]
                 (merge {:seon.eval.finding/measure (first in)
                         :seon.eval.finding/observed value
                         :seon.eval.finding/bound (m/form schema)}
                        (update-keys (select-keys (second entry) [:level :owner :fix])
                                     #(keyword "seon.eval.finding" (name %)))))))
        (:errors (m/explain bounds cost))))

;; Measured on default (pid 59671 / 70054, 2026-09-26), see the design doc §2.
(def samples
  {"(inc 1) first on a new branch" {:seon.eval/wall-ms 1307 :seon.eval/seon-ms 1305 :seon.eval/own-ms 2}
   "(inc 1) warm"                  {:seon.eval/wall-ms 229 :seon.eval/seon-ms 227 :seon.eval/own-ms 2}
   "(vec (range 3000))"            {:seon.eval/wall-ms 711 :seon.eval/seon-ms 690 :seon.eval/own-ms 21
                                    :seon.eval/value-tokens 4650 :seon.eval/shown-tokens 30
                                    :seon.eval/unshown-tokens 4620}
   "(d/q '[:find ?e ?a ?v :where [?e ?a ?v]] db)" {:seon.eval/wall-ms 400 :seon.eval/unbounded-reads 1}})

(doseq [[form cost] samples]
  (let [t0 (System/nanoTime)
        found (findings declared-bounds cost)
        us (quot (- (System/nanoTime) t0) 1000)]
    (println ";;" form "->" (count found) "finding(s) in" us "µs")
    (doseq [f found] (prn f))))

;; Steady-state cost of the checker (compiled once, as the owner would hold it).
(let [validator (m/explainer declared-bounds)
      cost (samples "(vec (range 3000))")
      n 20000
      t0 (System/nanoTime)]
  (dotimes [_ n] (validator cost))
  (println ";; compiled explainer:" (format "%.2f" (/ (double (- (System/nanoTime) t0)) n 1000)) "µs per evaluation (bb, interpreted)"))
