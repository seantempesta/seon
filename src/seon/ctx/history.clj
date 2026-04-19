(ns seon.ctx.history
  "Pure diff utilities for ctx history.

   Computes deltas between map states, applies them forward,
   and reverses them for undo. All functions are pure — no atoms,
   no IO, no side effects.

   A delta is a map with two keys:
     :seon.ctx.history/added     - keys that were added or changed (new values)
     :seon.ctx.history/retracted - keys that were removed or changed (old values)

   Applying a delta forward replays the change.
   Reversing a delta swaps added/retracted for undo."
  (:require [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::added
                  [:map-of {:description "Keys added or changed (key -> new value)"}
                   :keyword :any])

(schema/register! ::retracted
                  [:map-of {:description "Keys removed or changed (key -> old value)"}
                   :keyword :any])

(schema/register! ::delta
                  [:map {:description "A state delta between two maps"}
                   [::added {:optional true} ::added]
                   [::retracted {:optional true} ::retracted]])

(schema/register! ::state
                  [:map-of {:description "A ctx state map"}
                   :keyword :any])

(schema/register! ::before
                  [:map-of {:description "The old state map"}
                   :keyword :any])

(schema/register! ::after
                  [:map-of {:description "The new state map"}
                   :keyword :any])

(schema/register! ::map-diff-request
                  [:map {:description "Request for map-diff"}
                   [::before ::before]
                   [::after ::after]])

(schema/register! ::apply-delta-request
                  [:map {:description "Request for apply-delta"}
                   [::state ::state]
                   [::delta ::delta]])

(schema/register! ::reverse-delta-request
                  [:map {:description "Request for reverse-delta"}
                   [::delta ::delta]])

(schema/register! ::empty-delta-request
                  [:map {:description "Request for empty-delta?"}
                   [::delta ::delta]])

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- map-diff* [before after]
  (let [all-keys (into (set (keys before)) (keys after))
        added (persistent!
               (reduce (fn [acc k]
                         (let [v-after (get after k ::not-found)]
                           (if (and (not= v-after ::not-found)
                                    (not= v-after (get before k ::not-found)))
                             (assoc! acc k v-after)
                             acc)))
                       (transient {})
                       all-keys))
        retracted (persistent!
                   (reduce (fn [acc k]
                             (let [v-before (get before k ::not-found)]
                               (if (and (not= v-before ::not-found)
                                        (not= v-before (get after k ::not-found)))
                                 (assoc! acc k v-before)
                                 acc)))
                           (transient {})
                           all-keys))]
    (cond-> {}
      (seq added) (assoc ::added added)
      (seq retracted) (assoc ::retracted retracted))))

(defn- apply-delta* [state added retracted]
  (let [removed-keys (when retracted
                       (remove #(contains? added %) (keys retracted)))]
    (cond-> state
      (seq added) (merge added)
      (seq removed-keys) (#(apply dissoc % removed-keys)))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn map-diff
  "Compute the delta between two maps.

   Returns a delta that, when applied to `before`, produces `after`.

   Request keys:
     ::before - Required. The old state map
     ::after  - Required. The new state map

   Response keys:
     ::added     - Keys present in after with different or new values
     ::retracted - Keys present in before but absent or changed in after

   Example:
     (map-diff {::before {:a 1 :b 2} ::after {:a 1 :b 3 :c 4}})
     ;; => {::added {:b 3 :c 4} ::retracted {:b 2}}"
  {:malli/schema [:=> [:cat ::map-diff-request] ::delta]}
  [{::keys [before after]}]
  (map-diff* before after))

(defn apply-delta
  "Apply a delta to a state map, producing the next state.

   Adds all ::added keys, removes all ::retracted keys that are not
   also in ::added (retracted keys represent old values; if a key
   appears in both added and retracted, the added value wins because
   it was a change, not a removal).

   Request keys:
     ::state - Required. The current state map
     ::delta - Required. The delta to apply

   Returns:
     ::state - The new state map

   Example:
     (apply-delta {::state {:a 1 :b 2}
                   ::delta {::added {:b 3 :c 4} ::retracted {:b 2}}})
     ;; => {:a 1 :b 3 :c 4}"
  {:malli/schema [:=> [:cat ::apply-delta-request] ::state]}
  [{::keys [state delta]}]
  (let [{::keys [added retracted]} delta]
    (apply-delta* state added retracted)))

(defn reverse-delta
  "Reverse a delta for undo (going backward in history).

   Swaps ::added and ::retracted so applying the reversed delta
   undoes the original change.

   Request keys:
     ::delta - Required. The delta to reverse

   Returns:
     ::delta - The reversed delta

   Example:
     (reverse-delta {::delta {::added {:b 3} ::retracted {:b 2}}})
     ;; => {::added {:b 2} ::retracted {:b 3}}"
  {:malli/schema [:=> [:cat ::reverse-delta-request] ::delta]}
  [{::keys [delta]}]
  (let [{::keys [added retracted]} delta]
    (cond-> {}
      (seq retracted) (assoc ::added retracted)
      (seq added) (assoc ::retracted added))))

(defn empty-delta?
  "Returns true if the delta represents no change.

   Request keys:
     ::delta - Required. The delta to check

   Returns:
     boolean

   Example:
     (empty-delta? {::delta {}})        ;; => true
     (empty-delta? {::delta {::added {:a 1}}}) ;; => false"
  {:malli/schema [:=> [:cat ::empty-delta-request] :boolean]}
  [{::keys [delta]}]
  (let [{::keys [added retracted]} delta]
    (and (empty? added) (empty? retracted))))
