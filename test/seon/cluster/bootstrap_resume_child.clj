(ns seon.cluster.bootstrap-resume-child
  "Child JVM stopped while a generated run derives its next entry."
  (:require [seon.cluster :as cluster]
            [seon.turn]))

(def ^:private prefix-entries
  "Entries the child lets the run derive and settle before it parks.

  The drill's subject is a generated PREFIX resuming, so the child must leave
  one behind: it pauses at a LATER derivation, not at the first."
  2)

(defn -main
  "Start `cluster-name`, publish the derivation boundary, and await SIGKILL.

  The boundary is `seon.turn/generate-turn` (`src/seon/turn.clj:4851`), the one
  production seam that derives a generated run's next entry: `6aca09cce`
  (2026-09-09, \"Use the system-turn generator for seeded agent openings\")
  replaced its call to `seon.bootstrap/next-entry` with the shared system-turn
  source generator, leaving that function with no caller in `src/`. Redefining
  it here published no boundary at all, and the drill's parent waited out its
  60 s bound instead of killing a deriving child."
  {:malli/schema [:=> [:cat :string :string] :nil]}
  [root cluster-name]
  (let [never (promise)
        derived (atom 0)
        generate (var-get #'seon.turn/generate-turn)]
    (with-redefs
      [seon.turn/generate-turn
       (fn [request]
         (if (<= (swap! derived inc) prefix-entries)
           (generate request)
           (do (println "deriving"
                        (:seon.turn/id (:seon.turn.loop/work request)))
               (flush)
               @never)))]
      (cluster/start! {:seon.boot/root root
                       :seon.boot/cluster-name cluster-name})
      @never)))
