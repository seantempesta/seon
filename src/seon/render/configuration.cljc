(ns seon.render.configuration
  "Portable reads from the immutable configuration value supplied to renders.

   Configuration acquisition and normalization remain with `seon.config`.
   Render code receives the resulting ordinary map and reads only named facts
   through [[value]], so neither the JVM nor the pod render path observes an
   ambient process singleton."
  (:require
    [seon.config.resolve :as config.resolve]))

(defn value
  "Read `k` from `configuration`, using `default` only when it is absent."
  [configuration k default]
  (get configuration k default))

(def cluster-config-id config.resolve/cluster-config-id)
(def cluster-config-lookup-ref config.resolve/cluster-config-lookup-ref)

(defn namespaces-policy [configuration]
  {:seon.config/always (or (:seon.config/always configuration) #{})})

(defn default-run-policy []
  (config.resolve/default-run-policy))

(defn message-render-cap [configuration]
  (value configuration :seon.config.render/message-cap 4000))

(defn eval-render-cap [configuration]
  (value configuration :seon.config.render/eval-cap 1500))

(defn result-body-render-cap [configuration]
  (value configuration :seon.config.render/result-body-cap 16384))

(defn render-fn-token-cap [configuration]
  (value configuration :seon.config.render/render-fn-token-cap 2000))

(defn host-timezone [configuration]
  (value configuration :seon.config.render-context/host-timezone "UTC"))

(defn file-fingerprint
  "Return the configured content fingerprint for `path`, when present."
  [configuration path]
  (some (fn [fingerprint]
          (when (= path
                   (:seon.config.render-context/file-path fingerprint))
            (:seon.config.render-context/sha-256 fingerprint)))
        (:seon.config.render-context/file-fingerprints configuration)))
