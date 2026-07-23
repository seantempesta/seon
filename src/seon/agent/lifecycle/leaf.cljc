(ns seon.agent.lifecycle.leaf
  "Define platform services used by portable lifecycle entries."
  (:refer-clojure :exclude [uuid]))

(def now ::now)
(def available? ::available?)
(def unavailable ::unavailable)
(def uuid ::uuid)
