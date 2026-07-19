(ns seon.client.schema
  "Define portable process-launch schemas for `seon.client`.

   These closed data shapes cross the operator and pod boundary; process
   orchestration and lifecycle behavior remain in `seon.client`."
  (:require [seon.schema :as schema]))

(schema/register! :seon.client/autonomous? :boolean)
(schema/register!
 :seon.client/launch-capability
 [:map {:closed true}
  [:seon.client/autonomous? :seon.client/autonomous?]])
