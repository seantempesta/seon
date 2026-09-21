(ns seon.adoption-rows-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.fn :as seon.fn]
            [seon.program :as program]))

(deftest adoption-identities-carry-no-nil-member
  ;; A scalar publication's rows carry the file-digest row and any analyzer
  ;; findings alongside declarations. A derivation that rosters identity
  ;; attributes per call reads those rows as nil, and the adoption record then
  ;; refuses as a `[:set :seon.db/ref]` whose member is `#{nil}`.
  (let [path (.getCanonicalPath (io/file "src/seon/cluster.clj"))
        rows (:seon.fn.file/rows
              (seon.fn/build-artifact
               {:seon.fn/source-path path
                :seon.fn.file/first-party-functions []}))
        attributes (into #{} (map #(first (program/row-identity %))) rows)
        identities (#'cluster/adoption-identities
                    (into [] (keep program/row-identity) rows))]
    (is (contains? attributes :seon.fn.file/relative-path)
        "the file-digest row is part of a published file's rows")
    (is (every? some? identities))
    (is (not (contains? (set identities) nil))
        "the transacted set carries no nil member")
    (is (every? #(contains? #{:seon.ns/name :seon.fn/sym :seon.schema/key
                              :seon.test/sym}
                            (first %))
                identities)
        (pr-str (into #{} (map first) identities)))
    (is (some #(= :seon.ns/name (first %)) identities)
        "declarations are still recorded")))
