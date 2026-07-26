(ns seon.host.guard
  "Own the portable policy guard around one SCI invocation."
  (:refer-clojure :exclude [reset!])
  (:require [sci.interrupt :as interrupt]
            [seon.error :as error]
            [seon.schema :as schema]))

(schema/register! ::holder 'some?)
(schema/register! ::interpreter-step-budget [:int {:min 0}])
(schema/register! ::mode [:enum :count :enforce])
(schema/register! ::config-key :qualified-keyword)
(schema/register! ::invocation-class :keyword)
(schema/register! ::arm-deadline! 'fn?)
(schema/register! ::evaluate! 'fn?)
(schema/register! ::interpreter-steps-used [:int {:min 0}])
(schema/register! ::initial-interpreter-step-budget [:int {:min 0}])
(schema/register! ::interpreter-steps-remaining :int)
(schema/register! ::policy-kind [:enum :budget :timeout :agent])
(schema/register!
 ::policy
 [:map {:closed true}
  [::interpreter-step-budget ::interpreter-step-budget]
  [::mode ::mode]
  [::invocation-class ::invocation-class]
  [::interpreter-step-budget-config-key ::config-key]
  [::deadline-config-key ::config-key]
  [::output-config-key ::config-key]])
(schema/register!
 ::call-request
 [:map {:closed true}
  [::holder ::holder]
  [::policy ::policy]
  [::evaluate! ::evaluate!]
  [::arm-deadline! {:optional true} ::arm-deadline!]])

(def ^:private remaining-index 0)
(def ^:private initial-index 1)
(def ^:private enforce-index 2)
(def ^:private interrupted-index 0)
(def ^:private invocation-class-index 1)
(def ^:private interpreter-step-budget-config-key-index 2)
(def ^:private deadline-config-key-index 3)
(def ^:private output-config-key-index 4)
(def ^:private fired-policy-kind-index 5)
(def ^:private policy-reported-index 6)
(def ^:private carrier-fairness-entry-mask 0xffff)
(def ^:private carrier-fairness-park-nanos 1000)

(declare check-arrays! unreported-policy-kind)

(defn holder
  "Create one stable interpreter-step counter for a retained SCI context."
  {:malli/schema [:=> [:cat] ::holder]}
  []
  (let [interpreter-step-counter (long-array 3)
        control-cell (object-array 7)
        holder {::interpreter-step-counter interpreter-step-counter
                ::control-cell control-cell}]
    (assoc holder
           ::check!
           (fn []
             (check-arrays! holder
                            interpreter-step-counter
                            control-cell)))))

(defn reset!
  "Reset a retained context's holder for one invocation."
  {:malli/schema [:=> [:catn [::holder ::holder] [::policy ::policy]]
                  ::holder]}
  [{::keys [^longs interpreter-step-counter ^objects control-cell] :as holder}
   {::keys [interpreter-step-budget mode invocation-class
            interpreter-step-budget-config-key deadline-config-key
            output-config-key]}]
  (aset interpreter-step-counter remaining-index interpreter-step-budget)
  (aset interpreter-step-counter initial-index interpreter-step-budget)
  (aset interpreter-step-counter enforce-index (if (= :enforce mode) 1 0))
  (aset control-cell interrupted-index nil)
  (aset control-cell invocation-class-index invocation-class)
  (aset control-cell interpreter-step-budget-config-key-index
        interpreter-step-budget-config-key)
  (aset control-cell deadline-config-key-index deadline-config-key)
  (aset control-cell output-config-key-index output-config-key)
  (aset control-cell fired-policy-kind-index nil)
  (aset control-cell policy-reported-index nil)
  holder)

(defn install-interrupted!
  "Install the platform leaf's current-invocation interrupt predicate."
  {:malli/schema [:=> [:catn [::holder ::holder]
                             [::interrupted? [:or :nil 'fn?]]]
                  ::holder]}
  [{::keys [^objects control-cell] :as holder} interrupted?]
  (aset control-cell interrupted-index interrupted?)
  holder)

(defn interpreter-steps-used
  "The number of SCI safepoints charged in the current invocation."
  {:malli/schema [:=> [:cat ::holder] ::interpreter-steps-used]}
  [{::keys [^longs interpreter-step-counter]}]
  (max 0 (- (aget interpreter-step-counter initial-index)
            (aget interpreter-step-counter remaining-index))))

(defn- policy-config-key
  [control-cell kind]
  (aget control-cell
        (case kind
          :budget interpreter-step-budget-config-key-index
          :timeout deadline-config-key-index
          output-config-key-index)))

(defn- policy-message
  [kind config-key interpreter-steps-used]
  (case kind
    :budget
    (str "This evaluation exceeded its interpreter-step budget (`" config-key
         "`) after " interpreter-steps-used
         " interpreter steps. Split the work into smaller evaluations or "
         "reduce the input.")

    :timeout
    (str "`" config-key "` stopped this evaluation at its deadline after "
         interpreter-steps-used
         " interpreter steps. Split the work into smaller evaluations or "
         "reduce the input.")

    (str "`" config-key "` stopped this evaluation after its output cap "
         "fired at " interpreter-steps-used
         " interpreter steps. Reduce the input or print a "
         "smaller result.")))

(defn- policy-data
  [{::keys [^longs interpreter-step-counter ^objects control-cell] :as holder}
   kind]
  (let [config-key (policy-config-key control-cell kind)]
    (cond-> {::policy-kind kind
             ::interpreter-steps-used (interpreter-steps-used holder)
             ::initial-interpreter-step-budget
             (aget interpreter-step-counter initial-index)
             ::interpreter-steps-remaining
             (aget interpreter-step-counter remaining-index)
             ::invocation-class (aget control-cell invocation-class-index)
             ::config-key config-key}
      true
      (assoc :seon.error.sci/class :interrupt))))

(defn policy-error!
  "Record and return one flat policy steering error value."
  {:malli/schema [:=> [:catn [::holder ::holder]
                             [::policy-kind ::policy-kind]]
                  :map]}
  [holder kind]
  (aset ^objects (::control-cell holder) policy-reported-index true)
  (let [{::keys [config-key interpreter-steps-used] :as data}
        (policy-data holder kind)
        message (policy-message kind config-key interpreter-steps-used)
        throwable (ex-info message
                           (assoc data :seon.error/kind kind))]
    (error/record! {:seon.error/raw throwable
                    :seon.error/fault :agent})
    {:seon.error/message message
     :seon.error/kind kind
     :seon.error/data (dissoc data ::policy-kind)}))

(defn stop!
  "Stop the current SCI invocation through its existing interrupt marker."
  {:malli/schema [:=> [:catn [::holder ::holder]
                             [::policy-kind ::policy-kind]]
                  :any]}
  [holder kind]
  (aset ^objects (::control-cell holder) fired-policy-kind-index kind)
  (let [{::keys [config-key interpreter-steps-used] :as data}
        (policy-data holder kind)]
    (interrupt/interrupt!
     (policy-message kind config-key interpreter-steps-used)
     (assoc data :seon.error/kind kind))))

(defn- policy-throwable-data
  [throwable]
  (some (fn [cause]
          (let [data (ex-data cause)]
            (when (::policy-kind data) data)))
        (take-while some? (iterate ex-cause throwable))))

(defn steering-error!
  "Record and return the flat policy error carried by `throwable`."
  {:malli/schema [:=> [:catn [::holder ::holder] [::throwable :any]]
                  [:or :nil :map]]}
  [holder throwable]
  (when-let [policy-kind
             (or (::policy-kind (policy-throwable-data throwable))
                 (unreported-policy-kind holder))]
    (aset ^objects (::control-cell holder) policy-reported-index true)
    (let [{::keys [config-key interpreter-steps-used] :as data}
          (policy-data holder policy-kind)
          message
          (policy-message policy-kind config-key interpreter-steps-used)]
      (error/record! {:seon.error/raw throwable
                      :seon.error/fault :agent})
      {:seon.error/message message
       :seon.error/kind policy-kind
       :seon.error/data (dissoc data ::policy-kind)})))

(defn- check-arrays!
  [holder ^longs interpreter-step-counter ^objects control-cell]
  (let [interpreter-steps-remaining
        (unchecked-dec (aget interpreter-step-counter remaining-index))]
    (aset interpreter-step-counter remaining-index interpreter-steps-remaining)
    (when (and (= 1 (aget interpreter-step-counter enforce-index))
               (neg? interpreter-steps-remaining))
      (stop! holder :budget))
    #?(:clj
       (when (zero?
              (bit-and
               (unchecked-subtract
                (aget interpreter-step-counter initial-index)
                interpreter-steps-remaining)
               carrier-fairness-entry-mask))
         (java.util.concurrent.locks.LockSupport/parkNanos
          carrier-fairness-park-nanos))))
  (when-let [interrupted? (aget control-cell interrupted-index)]
    (when (interrupted?)
      (stop! holder :timeout)))
  nil)

(defn check!
  "Charge one SCI safepoint, then check the platform interrupt predicate."
  {:malli/schema [:=> [:cat ::holder] :nil]}
  [{::keys [check!]}]
  (check!))

(defn interrupt-fn
  "Return the SCI safepoint closure for one retained context holder."
  {:malli/schema [:=> [:cat ::holder] 'fn?]}
  [{::keys [check!]}]
  check!)

(defn- unreported-policy-kind
  [{::keys [^objects control-cell]}]
  (when-not (aget control-cell policy-reported-index)
    (aget control-cell fired-policy-kind-index)))

(defn call!
  "Reset, arm, and execute one SCI invocation through the policy door.

   A dependency may catch SCI's interrupt marker and return normally. The
   holder retains that policy trip, so the door still returns the canonical
   flat steering value instead of letting the dependency downgrade it."
  {:malli/schema [:=> [:cat ::call-request] :any]}
  [{::keys [holder policy evaluate! arm-deadline!]}]
  (reset! holder policy)
  (let [disarm! #?(:clj (when arm-deadline! (arm-deadline! holder))
                   :cljs nil)]
    (try
      (let [value (evaluate!)]
        (if-let [kind (unreported-policy-kind holder)]
          (policy-error! holder kind)
          value))
      (finally
        (when disarm! (disarm!))
        (install-interrupted! holder nil)))))
