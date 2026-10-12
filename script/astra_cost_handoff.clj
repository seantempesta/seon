(ns astra-cost-handoff
  "Attribution probes for the projection hand-off and squash cost bounds."
  (:require [seon.batch :as batch]
            [seon.cluster.squash :as squash]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.program :as program]))

(defn costs
  "A measured call's costs without its live result."
  {:malli/schema [:=> [:cat :seon.profile/measured-call] :map]}
  [measured]
  (select-keys measured [:seon.profile/ms :seon.profile/alloc-bytes
                         :seon.profile/calls :seon.profile/called
                         :seon.profile/ranked-by-self :seon.cost/jvm-window]))

(defn preparation
  "Measure stamping, preparation and accounting over a held database value."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:map-of :qualified-keyword :map]]}
  [database]
  (let [projection (db/carried-projection database)
        sink (batch/sink)
        transaction [[:db.fn/call #'db/cost-upserts projection sink]]]
    {::stamp (costs (profile/measure #(#'db/stamp-transaction database projection [] {:seon.batch/sink sink})))
     ::prepare (costs (profile/measure #(#'db/prepared-write projection database transaction)))
     ::account (costs (profile/measure #(db/cost-upserts database projection sink)))}))

(defn squash-costs
  "Measure the whole squash first, then its historical attribute reads."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.schema/projection]
                  [:map-of :qualified-keyword :map]]}
  [source base projection]
  (let [whole (costs (profile/measure #(squash/squash-merge source base base projection)))
        changed (db/since (db/history source) (db/basis-t base))]
    {::squash whole
     ::attributes (into {}
                        (map (fn [attribute]
                               [attribute (costs (profile/measure #(db/undecoded-datoms changed :aevt [attribute])))]))
                        (keys (program/cluster-attributes projection)))}))
