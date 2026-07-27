(ns seon.render-fixture
  "A projection owner that NOTHING requires.

  Deliberately undiscovered by `bin/test` (the runner finds
  `*_test.clj`) and deliberately un-required by every namespace in the
  tree — `rg 'seon.render-fixture' src test` must name only this file
  and `seon/render_test.clj`'s symbol literals. That absence IS the
  fixture: it is what lets the router suite prove `requiring-resolve`
  LOADS a projection's owner rather than merely finding one that some
  earlier require had already pulled in. The same technique
  `seon/schema/edn_test_fixture.clj` uses for the predicate-owner rule.")

(defn kinds-count
  "A projection: how many kinds the unit declares, as a string.
  Trivial on purpose — the suite is proving the ROUTER, and a
  projection with logic of its own would put that logic in the way."
  [unit]
  (str (count (filter (fn [[k v]]
                        (and (= "seon.render" (namespace k))
                             (qualified-symbol? v)))
                      unit))))
