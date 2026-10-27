;; For the judges: every held-out task not judged yet, and the 20 queries, each kind's first five by
;; relevance (no cut) as the page draws them, with each item's chance
(let [db (seon.agent/db)
      SP "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/"
      tasks (clojure.edn/read-string (slurp (str SP "acc-tasks.edn")))
      done (set (take 12 (filter #(seon.db/eid db :seon.task/id (:task %)) (take-nth 4 tasks))))
      cut (seon.config/value :seon.config.render/relevance-summary)
      view (fn [r] (let [top (fn [k] (vec (take 5 (filter #(>= (get (:relevance r) % 0) cut) (k r)))))]
                     (dissoc (seon.context/related-view db (assoc (dissoc r :hidden) :doc (set (filter #(>= (get (:relevance r) % 0) (seon.config/value :seon.config.render/relevance-doc)) (keys (:relevance r)))) :sections (top :sections) :functions (top :functions) :schemas (top :schemas))) :read :more)))
      ts (vec (for [{:keys [task want] :as t} [] :let [te (seon.db/eid db :seon.task/id task)] :when te]
                {:task task :plan (:seon.task/plan (datahike.api/entity db te)) :changed want
                 :shown (view (seon.context/related db te {:cut 0.0}))}))
      queries (clojure.edn/read-string (slurp (str SP "acc-commits.edn")))
      program (seon.program/program-nss db)
      fn? (fn [e] (some #(contains? program (symbol (namespace (:var/name %)))) (:var/_form (datahike.api/entity db e))))
      qs (vec (for [{:keys [q want]} queries]
                (let [qv (first (:vectors (:value (seon.agent/fx! :embed {:texts [(str seon.vector/query-prompt q)]} {}))))
                      seeds (into #{} (keep (fn [m] (if (= \: (first m)) (seon.db/eid db :schema/key (keyword (subs m 1)))
                                                         (some-> (datahike.api/entity db [:var/name (symbol m)]) :var/form :db/id))))
                                  (re-seq #":?[a-z][\w.\-]*/[\w.\-?!*]+" q))
                      r (seon.context/relevance db {:qv qv :text q :seeds seeds :touched #{}} #{:function :section :schema}
                                                (fn [k e] (or (not= :function k) (fn? e))))
                      nm (fn [k e] (let [x (datahike.api/entity db e)]
                                     (case k :function (some->> (:var/_form x) (map :var/name) sort first) :section (:seon.section/address x) :schema (:schema/key x))))
                      rel (into {} (for [[k es] r [e p] es] [(nm k e) p]))]
                  {:task (str "merge: " q) :plan q :changed want
                   :shown (view {:sections (vec (keep #(nm :section (first %)) (:section r))) :functions (vec (keep #(nm :function (first %)) (:function r)))
                                 :schemas (vec (keep #(nm :schema (first %)) (:schema r))) :relevance rel})})))
      all (into ts qs)]
  (doseq [[i part] (map-indexed vector (partition-all 23 all))]
    (spit (str SP "judge-in-c" i ".edn") (with-out-str (doseq [x part] (prn x)))))
  [(count ts) (count qs)])
