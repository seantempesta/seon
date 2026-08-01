(ns inst-collection-probe
  (:require [seon.sci.admit :as admit]))

(defn run-probe
  "Exercise an object that satisfies both Inst and Collection."
  [& _]
  (let [value (proxy [java.util.AbstractCollection clojure.core.Inst] []
                (iterator [] (.iterator ^Iterable [1 2]))
                (size [] 2)
                (inst-ms* [] 43))
        caps {:seon.config.eval.result/max-depth 8
              :seon.config.eval.result/max-collection 16
              :seon.config.eval.result/max-string 256
              :seon.config.eval.result/max-nodes 256}
        admitted (admit/admit {:seon.sci.admit/value value
                               :seon.sci.admit/interrupt-fn (constantly nil)
                               :seon.sci.admit/caps caps
                               :seon.config/on-core-error :record})]
    (prn {:audit/inst? (inst? value)
          :audit/java-collection?
          (instance? java.util.Collection value)
          :audit/projected (:seon.sci.admit/value admitted)
          :audit/result-edn (:seon.cluster.eval/result-edn admitted)})))

(apply run-probe *command-line-args*)
