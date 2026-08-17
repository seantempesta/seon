(ns seon.sci.fork-isolation-test
  "The isolation law agents rely on to work simultaneously.

  Every agent turn runs in a `sci/fork` of the cluster's one acquired
  base ctx (`reference-code/sci/src/sci/core.cljc` `fork`: the env is
  copied into a NEW atom, so new and redefined vars in a fork are
  invisible outside it). These are the class regressions for the two
  halves of that guarantee: sibling forks of ONE base are mutually
  invisible, and the shared base is never mutated by any fork."
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.test-support :as test-support]))

(deftest sibling-forks-of-one-base-are-mutually-invisible
  ;; One name defined in two sibling forks of the same base holds two
  ;; independent values; a fork taken afterwards from the same base
  ;; sees neither — proving the base itself was never written.
  (test-support/with-database
    (fn [connection]
      (let [fork-a (test-support/fork-cluster-ctx connection)
            fork-b (test-support/fork-cluster-ctx connection)]
        (sci/eval-string* fork-a "(def sibling-private :a)")
        (sci/eval-string* fork-b "(def sibling-private :b)")
        (is (= :a (sci/eval-string* fork-a "sibling-private")))
        (is (= :b (sci/eval-string* fork-b "sibling-private"))
            "one name, two sibling forks, two independent values")
        (let [fork-c (test-support/fork-cluster-ctx connection)]
          (is (thrown? Throwable
                       (sci/eval-string* fork-c "sibling-private"))
              "a later fork of the same base sees neither sibling's def"))))))
