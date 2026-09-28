(ns seon.cluster.boot-timeline-test
  "Boot stamps each layer it publishes with its cumulative milliseconds."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.boot :as boot]
            [seon.schema :as schema]
            [seon.operator.runtime :as runtime]))

(deftest each-published-boot-layer-carries-its-cumulative-milliseconds
  ;; D16: boot published only a total ready-ms, so a 25-39 s boot could not
  ;; be split by layer. The live instance's own layers are the fixture: every
  ;; layer the running instance holds is stamped once, in acquisition order,
  ;; and a republication adding nothing changes nothing.
  (let [live (get @runtime/running-instances "default")
        listening (select-keys live [:seon.boot/config :seon.boot/advertisement
                                     :seon.boot/prepl-server])
        stepped (reduce (fn [[prior at] layer]
                          [(boot/boot-timeline prior (assoc prior layer (get live layer)) at) (+ at 100)])
                        [(boot/boot-timeline nil listening 1) 101]
                        (remove #{:seon.boot/prepl-server} boot/boot-layers))
        timeline (:seon.boot/layers (first stepped))]
    (is (= boot/boot-layers (mapv :seon.boot/layer timeline)))
    (is (apply <= (map :seon.boot/at-ms timeline)))
    (is (identical? (first stepped) (boot/boot-timeline (first stepped) (first stepped) 9999)))
    (is ((schema/projection-validator (schema/handed-projection) :seon.boot/instance) (first stepped)))))
