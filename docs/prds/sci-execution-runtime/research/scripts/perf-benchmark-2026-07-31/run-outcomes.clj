(require '[datahike.api :as d])
(def conn (:seon.boot/cluster-connection (get @@(ns-resolve 'seon.cluster 'running-instances) "scale-10")))
(def rows (d/q '[:find ?o ?c ?err ?dig
                 :where [?r :seon.cluster.run/opened-at ?o]
                 [(get-else $ ?r :seon.cluster.run/closed-at false) ?c]
                 [(get-else $ ?r :seon.cluster.run/error "-") ?err]
                 [(get-else $ ?r :seon.cluster.run/plan-digest "-") ?dig]] @conn))
(doseq [[o c err dig] (sort-by first rows)]
  (println :RUN (str o) :secs (if (inst? c) (long (/ (- (inst-ms c) (inst-ms o)) 1000)) :open)
           :digest (subs (str dig) 0 (min 8 (count (str dig)))) :err (subs (str err) 0 (min 60 (count (str err))))))
