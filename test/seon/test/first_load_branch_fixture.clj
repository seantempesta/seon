(ns seon.test.first-load-branch-fixture
  "A namespace nothing requires and no request loads: a branch changing its
  contract must refuse by name before `seon.test/run` loads or arms it
  (`seon.test.first-load-arming-test`).")

(defn identity-int
  {:malli/schema [:=> [:cat :int] :int]}
  [value]
  value)
