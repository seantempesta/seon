(defn- stored
  "Every entity of `database` carrying `identity`, pulled by `attributes`:
  empty while `identity` or the first attribute is not installed (a store
  that has not adopted the producer's schema yet)."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword [:vector :qualified-keyword]]
                  [:or [:vector :map] :seon.db/error-result]]}
  [database identity-attribute attributes]
  (if-not (every? #(get (:schema database) %) [identity-attribute (first attributes)])
    []
    (let [pulled (db/q {:find [[(list 'pull '?e (into [identity-attribute] attributes)) '...]]
                        :where [['?e (first attributes)]]} database)]
      (if (db/read-refusal? pulled) pulled (vec pulled)))))
