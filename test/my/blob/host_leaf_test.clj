(ns my.blob.host-leaf-test
  "Localized JVM blob archive contract tests."
  (:require
   [clojure.test :refer [deftest is]]
   [my.blob :as blob]
   [my.blob.core :as core]
   [my.blob.host :as host])
  (:import
   (java.nio.file Files LinkOption Path)
   (java.nio.file.attribute FileAttribute)))

(deftest real-archive-put-get-and-stat-use-one-hash-identity
  (let [root
        (Files/createTempDirectory
         "seon-u8-blob-" (make-array FileAttribute 0))
        projections (atom {})
        database
        {:db-name "u8-blob"
         :t 536870913
         :as-of nil
         :since nil
         :history false
         :datahike/commit-id
         #uuid "10000000-0000-4000-8000-000000000008"}
        platform-leaf
        (host/services
         {::host/current-db! (constantly database)
          ::host/transact!
          (fn [{:seon.db/keys [tx-data]}]
            (let [row (first tx-data)]
              (swap! projections assoc (:my.blob/hash row) row)
              {:seon.db/ok? true}))
          ::host/query!
          (fn [{:seon.db/keys [args]}]
            (when-let [row (get @projections (first args))]
              [(:my.blob/tokens row)
               (or (:my.blob/media row) :my.blob.media/absent)
               (:my.blob/at row)]))})
        functions (blob/bind-leaf platform-leaf)
        put! (get functions 'put!)
        get-blob (get functions 'get)
        stat (get functions 'stat)
        content "u8 blob bytes\n"]
    (try
      ((:my.blob/configure-storage-view! platform-leaf)
       {:my.blob/writable-dir (str root)
        :my.blob/read-only-dirs []})
      (let [first-result (put! {:my.blob/content content
                                :my.blob/media :markdown})
            second-result (put! {:my.blob/content content
                                 :my.blob/media :markdown})
            hash (:my.blob/hash first-result)]
        (is (:my.blob/ok? first-result))
        (is (= (core/sha256 content) hash))
        (is (= hash (:my.blob/hash second-result)))
        (is (= 1 (count @projections)))
        (is (= content
               (:my.blob/content
                (get-blob {:my.blob/hash hash}))))
        (is (true?
             (:my.blob/exists?
              (stat {:my.blob/hash hash :seon.db/db database}))))
        (let [path (.resolve (.resolve ^Path root (subs hash 0 2)) hash)]
          (is (Files/exists path (make-array LinkOption 0)))
          (Files/deleteIfExists path)
          (Files/deleteIfExists (.getParent path))))
      (finally
        (Files/deleteIfExists root)))))
