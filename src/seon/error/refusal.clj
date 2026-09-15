(ns seon.error.refusal
  "Pure cause-chain reading shared by database and error boundaries.")

(defn refusal
  "Deepest classified `ex-data`, retaining its exception message when absent;
  else deepest non-empty data, or nil."
  {:malli/schema
   [:=> [:cat [:maybe :seon.error/throwable]] [:or :nil :map]]}
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
                 (assoc data :seon.error/message
                        (or (:seon.error/message data)
                            (not-empty (ex-message candidate))
                            (str (:seon.error/kind data))))
                 classified))))))
