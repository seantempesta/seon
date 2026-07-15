(ns seon.runtime.lifecycle
  "Portable data contract for process-level runtime lifecycle transitions.

   This namespace owns no runtime state or effects beyond canonical schema
   declaration. Both the CLJS pod and the Babashka operator can require it, so
   lifecycle transport validates one closed EDN shape rather than maintaining
   platform-specific approximations."
  (:require
   [seon.db.coordinate :as coordinate]
   [seon.schema :as schema]))

(schema/register!
 ::quiesce-response
 [:or
  [:map {:closed true}
   [:seon.client/quiesced? [:= true]]
   [::coordinate/coordinate ::coordinate/coordinate]
   [:seon.client/quiesced-run-ids [:vector :string]]
   [:seon.client/completed-turn-ids [:vector :string]]
   [:seon.client/errored-turn-ids [:vector :string]]
   [:seon.agent.runtime/unhosted-ids [:vector :string]]]
  [:map {:closed true}
   [:seon.client/quiesced? [:= false]]
   [:seon.client/quiesce-error :string]]])
