(ns seon.cluster.store-child
  "Child JVM used only by the store flock standing proof.

  Opens the store THROUGH THE CONTRACT UNDER TEST (open-store!), so the
  cross-process refusal the parent asserts is the real mechanism, not a
  reenactment. Publishes readiness, then waits to be killed — the
  parent proves a live holder refuses a second open, and that the OS
  releases a dead holder's flock."
  (:require [seon.cluster.store :as store])
  (:import [java.nio.file Files Path StandardOpenOption]))

(defn -main
  "Open `store-dir` under the flock, publish readiness, wait for kill."
  {:malli/schema [:=> [:cat :string :string] :nil]}
  [store-dir ready-path]
  (store/open-store! store-dir)
  (Files/writeString
   (Path/of ready-path (make-array String 0))
   "held"
   (into-array StandardOpenOption
               [StandardOpenOption/CREATE
                StandardOpenOption/TRUNCATE_EXISTING
                StandardOpenOption/WRITE]))
  ;; An actual SIGKILL from the parent ends this process while holding.
  (Thread/sleep Long/MAX_VALUE))
