(ns seon.cluster.bootstrap-resume-child
  "Child JVM stopped while a generated run derives its next entry."
  (:require [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]))

(defn -main
  "Start `cluster-name`, publish the derivation boundary, and await SIGKILL."
  {:malli/schema [:=> [:cat :string :string] :nil]}
  [root cluster-name]
  (let [never (promise)]
    (with-redefs
      [bootstrap/next-entry
       (fn [_request run-id]
         (println "deriving" run-id)
         (flush)
         @never)]
      (cluster/start! {:seon.boot/root root
                       :seon.boot/cluster-name cluster-name})
      @never)))
