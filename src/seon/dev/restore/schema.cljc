(ns seon.dev.restore.schema
  "Portable restore identity schemas shared by operator and pod."
  (:require [seon.schema :as schema]))

;; Keys remain owned by `seon.dev.restore`; this namespace only makes their
;; data contract available to both CLJ operator code and the CLJS pod.
(schema/register! :seon.dev.restore/intent-id
                  [:or [:string {:min 14 :max 14}]
                   [:and :string [:re "^[a-z][a-z0-9]{11}$"]]])
(schema/register! :seon.dev.restore/digest [:re "[0-9a-f]{64}"])
(schema/register! :seon.dev.restore/plan-digest :seon.dev.restore/digest)
(schema/register! :seon.dev.restore/reachable-hash-digest
                  :seon.dev.restore/digest)
(schema/register! :seon.dev.restore/consumer-generations
                  [:map-of {:min 1} :qualified-keyword :uuid])
(schema/register!
 :seon.dev.restore/startup-identity
 [:map {:closed true}
  [:seon.dev.restore/intent-id :seon.dev.restore/intent-id]
  [:seon.dev.restore/plan-digest :seon.dev.restore/plan-digest]
  [:seon.dev.restore/reachable-hash-digest
   :seon.dev.restore/reachable-hash-digest]
  [:seon.dev.restore/consumer-generations
   :seon.dev.restore/consumer-generations]])
