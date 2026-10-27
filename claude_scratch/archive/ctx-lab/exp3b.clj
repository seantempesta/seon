;; after a few evals and writes: is the atom what a fresh pull gives, and what did each patch cost
(require 'clojure.data)
(def E3 (let [f (tree (me)) [a b] (clojure.data/diff (:tree @ctx) f)]
          {:equal? (= (:tree @ctx) f) :only-ctx a :only-db (some-> b :seon.eval/_agent keys)
           :hooks (count @hook-log) :last (take-last 6 @hook-log)
           :evals (count (:seon.eval/_agent (:tree @ctx)))}))
