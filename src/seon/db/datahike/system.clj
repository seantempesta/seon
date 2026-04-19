(ns seon.db.datahike.system
  "Integrant key for the datahike flow.

   Single component: `:seon.db/flow`. Builds and starts a core.async.flow
   topology that owns every datahike connection and the tx-bus.

   Config shape (in resources/system.edn when wired in Phase 2):

     :seon.db/flow {:namespaces #ref [:seon.db/namespaces]
                    :backend    #ref [:seon.db/backend]
                    :data-root  #ref [:seon.db/data-root]
                    :namespace-schemas {...}}

   Deliberately NOT wired into `resources/system.edn` in Phase 1B --
   Phase 2 will switch `seon.db`'s public API to route through this flow
   and register the key at that point."
  (:require [integrant.core :as ig]
            [seon.db.datahike.flow :as dh-flow]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::config
                  [:map
                   [:namespaces ::dh-flow/namespaces]
                   [:backend ::dh-flow/backend]
                   [:data-root {:optional true} ::dh-flow/data-root]
                   [:namespace-schemas {:optional true} ::dh-flow/namespace-schemas]])

;;; ---------------------------------------------------------------------------
;;; Integrant key
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.db/flow
  [_ {:keys [namespaces backend data-root namespace-schemas]}]
  (log/info "Starting datahike flow"
            {:namespaces namespaces :backend backend})
  (dh-flow/build-datahike-flow!
   (cond-> {::dh-flow/namespaces (vec namespaces)
            ::dh-flow/backend backend}
     data-root         (assoc ::dh-flow/data-root data-root)
     namespace-schemas (assoc ::dh-flow/namespace-schemas namespace-schemas))))

(defmethod ig/halt-key! :seon.db/flow
  [_ state]
  (log/info "Stopping datahike flow")
  (dh-flow/stop-datahike-flow! state))

;; Flow objects are not reusable across restarts (channels bound at start),
;; so suspend/resume = full halt + init. Keeps the contract clean.

(defmethod ig/suspend-key! :seon.db/flow
  [k state]
  (ig/halt-key! k state))

(defmethod ig/resume-key :seon.db/flow
  [k opts _old-opts _old-state]
  (ig/init-key k opts))
