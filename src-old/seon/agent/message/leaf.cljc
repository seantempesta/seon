(ns seon.agent.message.leaf
  "Define platform services used by the portable message boundary."
  (:refer-clojure :exclude [uuid]))

(def available? ::available?)
(def unavailable ::unavailable)
(def now ::now)
(def uuid ::uuid)
(def hop-cap ::hop-cap)
