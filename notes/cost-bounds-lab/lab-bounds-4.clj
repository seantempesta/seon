;; (2) composition on today's request: a session on the lab root's main (the real program and wiki), by d/db-with
(require 'seon.task 'seon.context 'seon.db)
(def lab-vars (vec (take 40 (keep (fn [d] (let [v (:v d)] (when (and (= "seon.ctx" (namespace v))) v))) (datahike.api/datoms lab-db :avet :var/name)))))
(defn lab-session
  "A main db holding agent b/a1's session: n evals over r runs of task demo/s citing c of seon.ctx's
  vars (each with its recorded form). Answers {:db :e}."
  [{:keys [n r c]}]
  (let [db0 lab-db
        plan (str "Change " (clojure.string/join ", " (map #(str "```" % "```") (take c lab-vars))) ".")
        db (datahike.api/db-with db0 (seon.task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "s" :seon.task/plan plan}]))
        ;; one run a transaction, as runs are made (runs sharing a transaction tie on the page's newest-run order)
        db (reduce (fn [db i] (datahike.api/db-with db [{:seon.run/id (str "demo/s#" i) :seon.run/task [:seon.task/id "demo/s"] :seon.run/agent {:seon.agent/key "b/a1"}}])) db (range r))
        db (datahike.api/db-with db (vec (for [i (range n)]
                                           {:seon.eval/id (str "e" i) :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key "b/a1"}
                                            :seon.eval/record {:code (str "(rows " i ")") :call-id (str "c-" i) :call 0 :prose (str "Read part " i ".")
                                                               :response {:handle (symbol "result" (str "e" i)) :value (vec (for [j (range 6)] {:part i :row j :name (str "row-" i "-" j)}))}
                                                               :line (str "[{:part " i " …}]") :run (str "demo/s#" (mod i (max 1 r)))}})))]
    {:db db :e (seon.db/eid db :seon.agent/key "b/a1")}))
(defn lab-plan-edit [db n] (datahike.api/with db [[:db/add [:seon.task/id "demo/s"] :seon.task/plan (str "Change ```" (first lab-vars) "``` " n " times.")]]))

;; today's world for a later turn, piece by piece: build at c0, advance to c1, the page at both
(defn lab-world-pieces [{:keys [db e]}]
  (let [tx (lab-plan-edit db 7) db1 (:db-after tx)
        r0 (seon.ctx/build db e :b)
        r1 (seon.ctx/advance r0 db db1 :b)]
    {:build (lab-m seon.ctx/build db e :b)
     :advance (lab-m seon.ctx/advance r0 db db1 :b)
     :page0 (lab-m seon.context/page db r0)
     :page1 (lab-m seon.context/page db1 r1)
     :patch (lab-m seon.ctx/patch r0 db1 (:tx-data tx) :b)
     :changes (lab-m seon.ctx/changes r0 r1)
     :entities (count (:spec (:ix r0)))}))
