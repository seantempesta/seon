(ns seon.cluster.publication-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]))

(deftest malformed-manifest-is-not-an-incremental-basis
  (is (false? (#'cluster/valid-source-manifest?
               #:seon.fn.manifest{:artifacts {0 #:seon.fn.file{:rows {1 #:seon.ns{:name nil}}}}}))))
