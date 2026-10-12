(ns seon.central-round
  "Run the compiled tier on an isolated running root.
  clojure -Sdeps '{:paths [\"script\" \"src\" \"resources\"]}' -M -m seon.central-round ROOT OUT [BATCH] [SYMBOLS-EDN]
  Each request uses the process selected at round start and awaits actual body
  exit under the tests' declared bound. Unfinished execution and unknown transport
  outcomes are recorded and end the round. Output is shown text from the value printer."
  (:require [clojure.edn :as edn]
            [seon.operator :as operator]
            [seon.error.refusal :as refusal]
            [seon.print :as print]
            [seon.render.value :as value]))

(def test-symbols-query
  '[:find [?s ...] :where [?e :seon.test/sym ?s] (not [?e :seon.test/fixture])])

(defn test-symbols-form
  "Select test declarations that are not fixture material."
  {:malli/schema [:=> [:cat] [:sequential :seon.schema/value]]}
  []
  `(let [handle# (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
         symbols# (seon.db/q '~test-symbols-query
                             (seon.db/db (:seon.db/connection handle#)))]
     (if (vector? symbols#) (vec (sort symbols#)) symbols#)))

(defn batch-form
  "A request reusing stored results and awaiting new bodies' actual exit."
  {:malli/schema [:function
                  [:=> [:cat [:sequential :symbol]] [:sequential :seon.schema/value]]
                  [:=> [:cat [:sequential :symbol] [:sequential :seon.schema/value]]
                   [:sequential :seon.schema/value]]]}
  ([symbols]
   (batch-form symbols '(:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))))
  ([symbols execution-form]
   `(let [handle# ~execution-form
          connection# (:seon.db/connection handle#)
          bound# (seon.test/declared-bound-ms (seon.db/db connection#) '~(set symbols))]
      (seon.db/with-branch
       {:seon.db/connection connection#}
       #(seon.test/run {:seon.test/execution handle# :seon.test/recording-connection connection#
                        :seon.test/policy :named :seon.test/identities '~(set symbols)
                        :seon.test/include-long? true
                        :seon.test/check-time-limit-ms bound#})))))

(defn run-round!
  "Save each complete answer; end the round on unknown execution."
  {:malli/schema [:=> [:cat [:=> [:cat [:sequential :symbol]] :map]
                       [:=> [:cat :map] :seon.schema/value] [:sequential [:sequential :symbol]]] :nil]}
  [submit! record! parts]
  (loop [[part & remaining] parts]
    (when part
      (let [answer (submit! part)]
        (record! {:seon.central-round/symbols (vec part) :seon.central-round/answer answer})
        (when (and (contains? answer :seon.test/results)
                   (empty? (:seon.test/unfinished answer))
                   (empty? (:seon.test/pending answer))
                   (not (:seon.test/recording-refusal answer)))
          (recur remaining)))))
  nil)

(defn -main
  "Run the selected declarations and save their answers through the value printer."
  {:malli/schema [:=> [:cat :string :string [:* :string]] :nil]}
  [root out & [batch symbols-file]]
  (let [advertisement (operator/advertisement root nil)
        record! #(spit out (str (value/core-text
                                (assoc % :seon.central-round/process advertisement)) "\n") :append true)
        bound (operator/operation-bound-ms {:seon.operator/command :check})
        live-value! #(operator/prepl-value! advertisement
                                            (print/form-text %) bound nil bound)]
    (try
      (let [symbols (if symbols-file (edn/read-string (slurp symbols-file))
                        (live-value! (test-symbols-form)))]
        (if (vector? symbols)
          (run-round! #(live-value! (batch-form %)) record!
                      (partition-all (Long/parseLong (or batch "25")) symbols))
          (record! {:seon.central-round/answer symbols})))
      (catch Throwable failure
        (record! {:seon.central-round/unknown true
                  :seon.central-round/error
                  (refusal/error :seon.operator/operation-error
                                 {:seon.error/operation 'seon.central-round/-main
                                  :seon.error/message "Central round ended without an observed terminal answer."
                                  :seon.operator/disposition :client-failed
                                  :seon.error/offending {:seon.operator/root root}
                                  :seon.error/throwable failure})}))))
  nil)
