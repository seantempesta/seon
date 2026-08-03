(ns my.background
  "Start, inspect, or await the one durable capability-effect receipt."
  (:refer-clojure :exclude [await])
  (:require [my.run :as run]
            [seon.db :as db]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn- invalid-call
  []
  {:seon.error/kind ::invalid-call
   :seon.error/message
   "background needs exactly one direct capability call with one request value."
   :seon.error/data {}})

(defmacro background
  "Open one direct declared capability call and return its receipt ref."
  [& calls]
  (let [call (first calls)]
    (if (and (= 1 (count calls))
             (seq? call)
             (= 2 (count call))
             (symbol? (first call)))
      (let [[owner request] call]
        (list 'seon.effect/request!
              (list 'var owner)
              request
              {:seon.effect/background? true}))
      (invalid-call))))

(defn poll
  "Return a bounded descriptor derived from one effect receipt."
  {:malli/schema
   [:=> [:cat :my.background/result]
    [:or :my.background/descriptor :seon.error/value]]}
  [result-ref]
  (if-not (and (vector? result-ref)
               (= 2 (count result-ref))
               (= :seon.effect/id (first result-ref))
               (string? (second result-ref)))
    {:seon.error/kind ::invalid-result
     :seon.error/message "poll needs a :seon.effect/id lookup ref."
     :seon.error/data {:my.background/result result-ref}}
    (if-let [receipt
             (db/pull (db/db db/*conn*)
                      [:seon.effect/id
                       :seon.effect/request-edn
                       :seon.effect/result-edn
                       :seon.effect/result-blob
                       :seon.effect/result-size
                       :seon.effect/duration-ms
                       :seon.effect/interrupted-at]
                      result-ref)]
      (cond-> (select-keys receipt
                          [:seon.effect/id :seon.effect/request-edn])
        (:seon.effect/result-edn receipt)
        (merge (select-keys receipt
                            [:seon.effect/result-edn
                             :seon.effect/result-blob
                             :seon.effect/result-size
                             :seon.effect/duration-ms]))

        (:seon.effect/interrupted-at receipt)
        (assoc :seon.effect/interrupted-at
               (:seon.effect/interrupted-at receipt)))
      {:seon.error/kind ::missing-result
       :seon.error/message "The background effect receipt does not exist."
       :seon.error/data {:my.background/result result-ref}})))

(defn await
  "Return a wait disposition while pending, otherwise the poll descriptor."
  {:malli/schema
   [:=> [:cat :my.background/result :my.run/note]
    [:or :my.background/descriptor :my.run/wait :seon.error/value]]}
  [result-ref note]
  (let [descriptor (poll result-ref)]
    (if (or (:seon.error/kind descriptor)
            (:seon.effect/result-edn descriptor)
            (:seon.effect/interrupted-at descriptor))
      descriptor
      (let [wait-value (run/wait note)]
        (if (:seon.error/kind wait-value)
          wait-value
          (assoc wait-value :my.background/result result-ref))))))
