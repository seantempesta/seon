;; Per model turn of each run: where its request's tokens went, as the turn recorded it
;; (:seon.turn/sent-split, seon.context/request's :split): system, story, conversation, screen.
(require '[datahike.api :as d] '[seon.root :as c])
(defn breakdown [sys]
  (vec (for [[rid b] (d/q '[:find ?r ?b :where [?e :seon.run/id ?r] [?e :seon.run/branch ?b]] @(:conn sys))
             :let [db (:db @(c/branch-db sys b))]
             [n split] (sort-by first (d/q '[:find ?n ?s :in $ ?r :where [?ru :seon.run/id ?r] [?t :seon.turn/run ?ru] [?t :seon.turn/n ?n]
                                             [?t :seon.turn/sent-split ?s]] db rid))]
         (into {:run rid :turn n} split))))
