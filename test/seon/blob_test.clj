(ns seon.blob-test
  "Content-addressed blob behavior on Seon's real file store."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest utf8-content-round-trips-by-its-digest
  (let [root (str "tmp/blob-test/" (random-uuid))
        opened (store/open-store! {:seon.store/dir (str root "/store")})]
    (try
      (registry/branch! {:seon.store/store opened
                         :seon.cluster.registry/from :db
                         :seon.store/branch :blob-test})
      (let [connection (store/open-branch! opened :blob-test)]
        (try
          (let [content "naïve λ result \n {:rows [1 2 3]}"
                digest (blob/put! connection content)]
            (is (= (schema/sha-256 [(.getBytes content "UTF-8")]) digest))
            (is (= content (blob/get connection digest)))
            (is (= digest (blob/put! connection content))
                "the content address is stable and an existing blob is not rewritten"))
          (finally
            (d/release connection))))
      (finally
        (store/release-store! opened)
        (support/delete-recursively! (io/file root))))))

(deftest utf8-content-round-trips-through-the-memory-backend
  (support/with-database
    {::support/fresh-store? true}
    (fn [connection]
      (let [content "memory-backed naïve λ result"
            digest (blob/put! connection content)]
        (is (= content (blob/get connection digest)))
        (is (= digest (blob/put! connection content)))))))
