(ns seon.blob-threshold-test
  "Settlement stores what admission handed it — no window, no second copy.

  The blob threshold decides where the REPLY and stored def values live. It
  no longer touches an evaluation's value: admission already decided that
  under the one storage bound, so a value is stored faithfully or it is
  missing, and settlement is not allowed to make a third answer out of it."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.blob :as blob]
            [seon.turn :as turn]
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
  [connection evaluation]
  (let [staged
        (turn/settlement-projection
         (support/cluster-handle
          {:seon.db/connection connection
           :seon.sci.admit/caps caps})
         evaluation)
        receipt (nth staged 0)
        stages (nth staged 2)]
    (blob/with-publication!
     connection stages #(identity receipt))))

(deftest settlement-stores-the-admitted-node-unchanged-at-every-size
  (support/with-database
    (fn [connection]
      (let [threshold
            (:seon.config.eval.result/blob-threshold (config/defaults))]
        (db/transact!
         connection
         [{:seon.config.eval.result/blob-threshold threshold}])
        (testing "a result under the inline threshold"
          (let [result-edn (admitted-result (apply str (repeat 420 \r)))]
            (is (< (count result-edn) threshold))
            (is (= {:seon.cluster.eval/result-edn result-edn}
                   (settlement connection
                               {:seon.cluster.eval/result-edn result-edn})))))
        (testing "a result far past it keeps EVERY byte and stages nothing"
          ;; The class this kills: the old seam replaced the stored node with
          ;; a PAGE of itself whose root face was an ordinary vector, so
          ;; nothing downstream could tell a complete value from a window.
          (let [result-edn (admitted-result (vec (repeat 40 (apply str (repeat 4000 \r)))))]
            (is (< threshold (count result-edn)))
            (let [settled (settlement connection
                                      {:seon.cluster.eval/result-edn result-edn})]
              (is (= {:seon.cluster.eval/result-edn result-edn} settled))
              (is (not (contains? settled :seon.cluster.eval/result-blob)))
              (is (not (contains? settled :seon.eval/size))))))))))

(deftest settlement-carries-a-missing-marker-and-stores-no-node
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.config.eval.result/blob-threshold 4096}])
      (is (= {:seon.eval/missing :over-bound :seon.eval/size 8388608}
             (settlement connection
                         {:seon.eval/missing :over-bound
                          :seon.eval/size 8388608}))
          "the reason and the bytes it reached, and no value beside them"))))
