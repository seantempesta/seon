(ns seon.blob-settlement-test
  "The terminal result seam over a real file-backed branch."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.blob :as blob]
            [seon.cluster.run :as run]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.test-support :as support]))

(deftest oversized-results-become-a-blob-and-a-presentation-window
  (let [root (str "tmp/blob-settlement-test/" (random-uuid))
        opened (store/open-store! {:seon.store/dir (str root "/store")})]
    (try
      (db/transact!
       (:seon.store/connection-object opened)
       [{:db/ident :seon.config.eval.result/blob-threshold
         :db/valueType :db.type/long
         :db/cardinality :db.cardinality/one}
        {:db/ident :seon.render.value/max-collection
         :db/valueType :db.type/long
         :db/cardinality :db.cardinality/one}])
      (registry/branch! {:seon.store/store opened
                         :seon.cluster.registry/from :db
                         :seon.store/branch :settlement-test})
      (let [connection (store/open-branch! opened :settlement-test)]
        (try
          (db/transact! connection
                      [{:seon.config.eval.result/blob-threshold 65536
                        :seon.render.value/max-collection 3}])
          (let [caps (config/result-caps (config/defaults))
                full (pr-str (vec (range 20000)))
                large-projection
                (run/settlement-projection
                 {:seon.db/connection connection
                  :seon.sci.admit/caps caps}
                 {:seon.cluster.eval/result-edn full})
                large (nth large-projection 0)
                small (first
                       (run/settlement-projection
                        {:seon.db/connection connection
                         :seon.sci.admit/caps caps}
                        {:seon.cluster.eval/result-edn "42"}))
                large
                (blob/with-publication!
                 connection (nth large-projection 2) #(identity large))]
            (is (= (count full) (:seon.cluster.eval/result-size large)))
            (is (< (count (:seon.cluster.eval/result-edn large)) (count full)))
            (is (= [0 1 :seon.print/elided]
                   (mapv (fn [node]
                           (or (:seon.print/value node)
                               (:seon.print/face node)))
                         (:seon.print/items
                          (read-string
                           (:seon.cluster.eval/result-edn large))))))
            (is (= full
                   (blob/get connection
                             (:seon.cluster.eval/result-blob large))))
            (is (= {:seon.cluster.eval/result-edn "42"
                    :seon.cluster.eval/result-size 2}
                   small)))
          (finally
            (d/release connection))))
      (finally
        (store/release-store! opened)
        (support/delete-recursively! (io/file root))))))
