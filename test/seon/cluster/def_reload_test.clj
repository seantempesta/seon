(ns seon.cluster.def-reload-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as source]
            [seon.id :as id]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Canonical fixture cold acquisition and namespace analysis previously measured 12 seconds in publication-delta-test."
           :seon.test/long-ms 20000}
  an-as-alias-is-no-load-edge
  (let [root (io/file "tmp" (str "alias-edge-" (id/id)))]
    (.mkdirs (io/file root "src"))
    (try
      (doseq [[file text]
              {"a.clj" "(ns sample.aliasedge.a (:require [sample.aliasedge.b :as-alias b]))\n(defn k [] ::b/x)"
               "b.clj" "(ns sample.aliasedge.b (:require [sample.aliasedge.a :as a]))\n(defn v [] (a/k))"}]
        (spit (io/file root "src" file) text))
      (support/with-database
       (fn [connection]
         (support/transacted! connection
                              (source/rows {:seon.fn/root (.getCanonicalPath root)
                                            :seon.fn/roots ["src"]}))
         (let [requires #(set (:seon.ns/requires (db/pull (db/db connection) [:seon.ns/requires]
                                                          [:seon.ns/name %])))]
           (is (not (contains? (requires 'sample.aliasedge.a) 'sample.aliasedge.b))
               "Clojure's load-lib creates an :as-alias namespace without loading it")
           (is (contains? (requires 'sample.aliasedge.b) 'sample.aliasedge.a) "a plain :as loads"))
         nil))
      (finally (support/delete-recursively! root)))))
