(ns seon.error.refusal
  "Pure cause-chain reading shared by database and error boundaries.")

(defn refusal
  "Deepest non-empty `ex-data` in a throwable cause chain, or nil."
  {:malli/schema
   [:=> [:cat :seon.schema/value] [:or :nil :map]]}
  [throwable]
  (loop [candidate throwable
         deepest nil]
    (if (nil? candidate)
      deepest
      (let [data (ex-data candidate)]
        (recur (ex-cause candidate)
               (if (seq data) data deepest))))))
