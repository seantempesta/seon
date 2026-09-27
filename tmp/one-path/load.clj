;; Load the one-path implementation into the running JVM (new namespaces only;
;; no existing namespace is touched). Order follows the requires.
(doseq [f ["src/seon/sci/forgive.clj" "src/seon/batch.clj" "src/seon/sci/batch.clj"
           "src/seon/sci/arm.clj" "src/seon/turn/world.clj" "src/seon/turn/gate.clj" "src/seon/turn/batch.clj" "src/seon/sci/observe.clj"]]
  (load-file (str "/Users/sean/src/seon/tmp/one-path/" f)))
