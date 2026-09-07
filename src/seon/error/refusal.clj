(ns seon.error.refusal
  "Pure cause-chain reading shared by database and error boundaries.")

(defn refusal
  "Deepest classified `ex-data`, else deepest non-empty data, or nil."
  {:malli/schema
   [:=> [:cat :seon.schema/value] [:or :nil :map]]}
  [throwable]
  (loop [candidate throwable
         deepest nil
         classified nil]
    (if (nil? candidate)
      (or classified deepest)
      (let [data (ex-data candidate)]
        (recur (ex-cause candidate)
               (if (seq data) data deepest)
               (if (some? (:seon.error/kind data))
                 data
                 classified))))))
