(ns seon.config-defaults-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]))

(deftest shipped-defaults-equal-explicit-manifest-compilation
  (is (map? config/defaults)
      "shipped defaults are an immutable program value, not a compiler function")
  (is (= config/defaults
         (:seon.config/effective
          (config/compile-manifest {:seon.boot/cluster-name "config-defaults-test"})))
      "the config-only projection preserves every effective shipped decision"))
