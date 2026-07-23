(ns seon.render.configuration
  "Portable reads from the immutable configuration value supplied to renders.

   Configuration acquisition and normalization remain with `seon.config`.
   Render code receives the resulting ordinary map and reads only named facts
   through [[value]], so neither the JVM nor the pod render path observes an
   ambient process singleton.")

(defn value
  "Read `k` from `configuration`, using `default` only when it is absent."
  [configuration k default]
  (get configuration k default))
