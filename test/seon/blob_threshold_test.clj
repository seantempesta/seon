(ns seon.blob-threshold-test
  "The measured storage decision behind the shipped blob threshold."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.blob :as blob]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- admitted-result
  [value]
  (:seon.cluster.eval/result-edn
   (admit/admit
    {:seon.sci.admit/value value
     :seon.sci.admit/interrupt-fn (fn [])
     :seon.sci.admit/caps caps
     :seon.config/on-core-error :record})))

(defn- settlement
  [connection result-edn]
  (let [staged
        (run/settlement-projection
         {:seon.db/connection connection
          :seon.sci.admit/caps caps}
         {:seon.cluster.eval/result-edn result-edn})
        receipt (nth staged 0)
        stages (nth staged 2)]
    (blob/with-publication!
     connection stages #(identity receipt))))

(deftest default-keeps-the-measured-small-result-class-off-the-blob-path
  (support/with-database
    (fn [connection]
      (let [threshold
            (:seon.config.eval.result/blob-threshold (config/defaults))
            result-edn (admitted-result (apply str (repeat 420 \r)))]
        (db/transact!
         connection
         [{:seon.config.eval.result/blob-threshold threshold
           :seon.render.value/max-collection 8}])
        (is (< (count result-edn) threshold))
        (is (= {:seon.cluster.eval/result-edn result-edn
                :seon.cluster.eval/result-size (count result-edn)}
               (settlement connection result-edn)))))))

(deftest full-stored-shape-decides-an-eligible-result
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.config.eval.result/blob-threshold 4096
         :seon.render.value/max-collection 8}])
      (let [payload (apply str (repeat 4000 \r))
            window-heavy (admitted-result [payload])
            window-light (admitted-result (vec (repeat 40 payload)))
            retained (settlement connection window-heavy)
            blobbed (settlement connection window-light)]
        (testing "an equal-sized window makes blob plus envelope larger"
          (is (< 4096 (count window-heavy)))
          (is (false?
               (#'run/result-blob-smaller? window-heavy window-heavy)))
          (is (= {:seon.cluster.eval/result-edn window-heavy
                  :seon.cluster.eval/result-size (count window-heavy)}
                 retained)))
        (testing "a much smaller window plus blob beats four inline copies"
          (is (< 4096 (count window-light)))
          (is (contains? blobbed :seon.cluster.eval/result-blob))
          (is (< (count (:seon.cluster.eval/result-edn blobbed))
                 (count window-light)))
          (is (= window-light
                 (blob/get connection
                           (:seon.cluster.eval/result-blob blobbed)))))))))
