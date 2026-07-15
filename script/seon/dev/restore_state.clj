(ns seon.dev.restore-state
  "Fsync-durable publication of the portable immutable restore intent."
  (:require [malli.core :as m]
            [seon.dev.restore :as restore]
            [seon.dev.state :as state]
            [seon.launch :as launch]
            [seon.schema :as schema]))

(schema/register! ::cluster-dir ::launch/cluster-dir)
(schema/register! ::published? :boolean)
(schema/register!
 ::publication-request
 [:map {:closed true}
  [::cluster-dir ::cluster-dir]
  [::restore/intent ::restore/intent]])
(schema/register!
 ::publication-result
 [:map {:closed true}
  [::restore/intent-path ::restore/intent-path]
  [::restore/intent ::restore/intent]
  [::published? ::published?]])

(defn read-intent!
  "Read and validate the canonical retained restore intent."
  {:malli/schema [:=> [:cat ::cluster-dir] ::restore/intent]}
  [cluster-dir]
  (let [path (restore/intent-path cluster-dir)
        intent (state/read-edn path)]
    (when-not intent
      (throw (ex-info "No retained restore intent exists."
                      {::restore/intent-path path})))
    (restore/validate-intent intent)))

(defn publish-intent!
  "Durably publish one immutable restore intent."
  {:malli/schema [:=> [:cat ::publication-request] ::publication-result]}
  [{::keys [cluster-dir] intent ::restore/intent :as request}]
  (when-not (m/validate ::publication-request request)
    (throw (ex-info "The restore intent publication request is invalid."
                    {:seon.dev.restore-state/explanation
                     (m/explain ::publication-request request)})))
  (let [intent (restore/validate-intent intent)
        path (restore/intent-path cluster-dir)
        retained (state/read-edn path)]
    (when (and retained (not= intent (restore/validate-intent retained)))
      (throw (ex-info "Another immutable restore intent is already retained."
                      {::restore/intent-path path
                       ::restore/intent-id (::restore/intent-id retained)})))
    (when-not retained
      (state/write-edn! path intent))
    {::restore/intent-path path
     ::restore/intent intent
     ::published? (nil? retained)}))
