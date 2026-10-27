;; The accuracy check, run in an attached agent: each held-out task's related answer as the page
;; asks it (cut 0: every item scored, with its chance), and each query's relevance as search will
;; ask it; written as EDN for scoring and judging.
(let [d* datahike.api/datoms db (seon.agent/db)
      SP "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/"
      tasks (clojure.edn/read-string (slurp (str SP "acc-tasks.edn")))
      queries (clojure.edn/read-string (slurp (str SP "acc-queries.edn")))
      t-out (vec (for [{:keys [task want]} tasks :let [te (seon.db/eid db :seon.task/id task)] :when te]
                   (let [r (seon.context/related db te {:cut 0.0})
                         shown (seon.context/related db te)]
                     {:task task :want want :plan (:seon.task/plan (datahike.api/entity db te))
                      :scored (into {} (map (fn [[k v]] [(str k) v])) (:relevance r))
                      :shown (mapv str (concat (:sections shown) (:functions shown) (:schemas shown)))
                      :view (seon.context/related-view db shown)})))
      embed (fn [ts] (vec (mapcat (fn [b] (:vectors (:value (seon.agent/fx! :embed {:texts (vec b)} {})))) (partition-all 32 ts))))
      qvs (embed (mapv #(str seon.vector/query-prompt (:q %)) queries))
      program (seon.program/program-nss db)
      fn? (fn [e] (some #(contains? program (symbol (namespace (:var/name %)))) (:var/_form (datahike.api/entity db e))))
      name-of (fn [e] (let [x (datahike.api/entity db e)]
                        (or (some->> (:var/_form x) (map :var/name) sort first str) (:seon.section/address x) (some-> (:schema/key x) str))))
      q-out (vec (for [[{:keys [q want]} qv] (map vector queries qvs)]
                   (let [t0 (System/nanoTime)
                         ;; the names q holds seed the graph, as search seeds it
                         seeds (into #{} (keep (fn [m] (if (= \: (first m))
                                                         (seon.db/eid db :schema/key (keyword (subs m 1)))
                                                         (some-> (datahike.api/entity db [:var/name (symbol m)]) :var/form :db/id))))
                                     (re-seq #":?[a-z][\w.\-]*/[\w.\-?!*]+" q))
                         r (seon.context/relevance db {:qv qv :text q :seeds seeds :touched #{}} #{:function :section :schema}
                                                   (fn [k e] (or (not= :function k) (fn? e))))
                         ms (/ (- (System/nanoTime) t0) 1e6)]
                     {:q q :want want :ms ms
                      :scored (into {} (for [[_ es] r [e p] es] [(name-of e) p]))})))]
  (spit (str SP "acc-out.edn") (pr-str {:tasks t-out :queries q-out}))
  [(count t-out) (count q-out)])
