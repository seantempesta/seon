(ns seon.runtime.recovery.core
  "Pure lease-aware recovery disposition."
  (:require [seon.agent.run.core :as run.core]))

(defn disposition
  "Choose custody preservation, takeover, or conservative repair."
  [{:seon.agent.run/keys [claimant claim-epoch] :as run}
   claimant-id now stale-ms resumable?]
  (cond
    (not= :open (:seon.agent.run/status run)) :ignore
    (nil? claimant) (if resumable? :claim :repair)
    (= claimant claimant-id) :resume
    (and claim-epoch (run.core/expired-claim? run now stale-ms))
    (if resumable? :steal :repair)
    :else :preserve))
