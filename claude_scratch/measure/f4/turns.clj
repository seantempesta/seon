;; F4: the recorded turns of a design run, as the model sent and read them, to a file per run.
(require '[datahike.api :as d] '[seon.db :as pdb] '[seon.context :as context] '[clojure.string :as str])
(defn dump [conn branch run out]
  (let [c (d/connect (assoc (:config @conn) :branch branch))
        db @c
        ts (sort (d/q '[:find ?n ?e :in $ ?r :where [?ru :seon.run/id ?r] [?e :seon.turn/run ?ru] [?e :seon.turn/origin :model] [?e :seon.turn/n ?n]] db run))
        msgs (fn [e] (into [] (mapcat second) (context/parts-of db (:seon.turn/sent (d/pull db [:seon.turn/sent] e)))))
        last-e (second (last ts))
        sent (msgs last-e)]
    (spit out (str/join "\n\n" (for [[i m] (map-indexed vector sent)]
                                 (str "=== " i " " (:role m) "\n" (:content m)
                                      (str/join "" (for [t (:tool-calls m)] (str "\n>>> " (:code t))))))))
    [(count ts) (count sent)]))

(defn dump-at [conn branch run n out]
  (let [db @(d/connect (assoc (:config @conn) :branch branch))
        e (ffirst (d/q '[:find ?e :in $ ?r ?n :where [?ru :seon.run/id ?r] [?e :seon.turn/run ?ru] [?e :seon.turn/origin :model] [?e :seon.turn/n ?n]] db run n))
        sent (into [] (mapcat second) (context/parts-of db (:seon.turn/sent (d/pull db [:seon.turn/sent] e))))]
    (spit out (str/join "\n\n" (for [[i m] (map-indexed vector sent)]
                                 (str "=== " i " " (:role m) "\n" (:content m)
                                      (str/join "" (for [t (:tool-calls m)] (str "\n>>> " (:code t))))))))
    (count sent)))
