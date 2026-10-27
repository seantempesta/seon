;; For the judge: 12 held-out tasks, each kind's first five by relevance (no cut), drawn as the page
;; draws them (related-view), with each item's chance
(let [db (seon.agent/db)
      SP "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/"
      tasks (clojure.edn/read-string (slurp (str SP "acc-tasks.edn")))
      pick (take 12 (filter #(seon.db/eid db :seon.task/id (:task %)) (take-nth 4 tasks)))
      out (vec (for [{:keys [task want]} pick :let [te (seon.db/eid db :seon.task/id task)
                                                    r (seon.context/related db te {:cut 0.0})
                                                    top (fn [k] (vec (take 5 (k r))))
                                                    r5 (assoc r :sections (top :sections) :functions (top :functions) :schemas (top :schemas))]]
                 {:task task :plan (:seon.task/plan (datahike.api/entity db te)) :changed want
                  :shown (dissoc (seon.context/related-view db (dissoc r5 :hidden)) :read :more)}))]
  (spit (str SP "judge-in.edn") (with-out-str (doseq [x out] (prn x))))
  (count out))
