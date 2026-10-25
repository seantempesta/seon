;; Per model turn of each run: where its request's tokens went (seon.context/sent-split over what
;; the turn recorded it sent, read back by seon.context/parts-of): system, story, conversation, screen.
(require '[datahike.api :as d] '[seon.root :as c] '[seon.context :as context])
(defn breakdown [sys]
  (vec (for [[rid b] (d/q '[:find ?r ?b :where [?e :seon.run/id ?r] [?e :seon.run/branch ?b]] @(:conn sys))
             :let [db (:db @(c/branch-db sys b))]
             [n sent basis] (sort-by first (d/q '[:find ?n ?s ?b :in $ ?r :where [?ru :seon.run/id ?r] [?t :seon.turn/run ?ru] [?t :seon.turn/n ?n]
                                                  [?t :seon.turn/sent ?s] [?t :seon.turn/basis ?b]] db rid))]
         (into {:run rid :turn n} (context/sent-split (context/parts-of db sent) (set (:screen basis)))))))
