(ns seon.client.schema
  "Portable schemas for the process launch capability owned by `seon.client`."
  (:require [seon.schema :as schema]))

(schema/register! :seon.client/autonomous? :boolean)
(schema/register!
 :seon.client/launch-capability
 [:map {:closed true}
  [:seon.client/autonomous? :seon.client/autonomous?]])
