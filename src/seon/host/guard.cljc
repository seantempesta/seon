(ns seon.host.guard
  "Own the portable policy guard around one SCI invocation."
  (:refer-clojure :exclude [reset!])
  (:require [sci.interrupt :as interrupt]
            [seon.error :as error]
            [seon.schema :as schema]))

(schema/register! ::holder 'some?)
(schema/register! ::fuel [:int {:min 0}])
(schema/register! ::mode [:enum :count :enforce])
(schema/register! ::config-key :qualified-keyword)
(schema/register! ::invocation-class :keyword)
(schema/register! ::arm-deadline! 'fn?)
(schema/register! ::evaluate! 'fn?)
(schema/register! ::steps-used [:int {:min 0}])
(schema/register! ::initial-fuel [:int {:min 0}])
(schema/register! ::remaining-fuel :int)
(schema/register! ::policy-kind [:enum :budget :timeout :agent])
(schema/register!
 ::policy
 [:map {:closed true}
  [::fuel ::fuel]
  [::mode ::mode]
  [::invocation-class ::invocation-class]
  [::fuel-config-key ::config-key]
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
(def ^:private fuel-config-key-index 2)
(def ^:private deadline-config-key-index 3)
(def ^:private output-config-key-index 4)

(declare check-holder!)

(defn holder
  "Create one stable mutable fuel holder for a retained SCI context."
  {:malli/schema [:=> [:cat] ::holder]}
  []
  (let [holder {::fuel-cell (long-array 3)
                ::control-cell (object-array 5)}]
    (assoc holder ::check! (fn [] (check-holder! holder)))))

(defn reset!
  "Reset a retained context's holder for one invocation."
  {:malli/schema [:=> [:catn [::holder ::holder] [::policy ::policy]]
                  ::holder]}
  [{::keys [^longs fuel-cell ^objects control-cell] :as holder}
   {::keys [fuel mode invocation-class fuel-config-key deadline-config-key
            output-config-key]}]
  (aset fuel-cell remaining-index fuel)
  (aset fuel-cell initial-index fuel)
  (aset fuel-cell enforce-index (if (= :enforce mode) 1 0))
  (aset control-cell interrupted-index nil)
  (aset control-cell invocation-class-index invocation-class)
  (aset control-cell fuel-config-key-index fuel-config-key)
  (aset control-cell deadline-config-key-index deadline-config-key)
  (aset control-cell output-config-key-index output-config-key)
  holder)

(defn install-interrupted!
  "Install the platform leaf's current-invocation interrupt predicate."
  {:malli/schema [:=> [:catn [::holder ::holder]
                             [::interrupted? [:or :nil 'fn?]]]
                  ::holder]}
  [{::keys [^objects control-cell] :as holder} interrupted?]
  (aset control-cell interrupted-index interrupted?)
  holder)

(defn steps-used
  "The number of SCI safepoints charged in the current invocation."
  {:malli/schema [:=> [:cat ::holder] ::steps-used]}
  [{::keys [^longs fuel-cell]}]
  (max 0 (- (aget fuel-cell initial-index)
            (aget fuel-cell remaining-index))))

(defn- policy-config-key
  [control-cell kind]
  (aget control-cell
        (case kind
          :budget fuel-config-key-index
          :timeout deadline-config-key-index
          output-config-key-index)))

(defn- policy-message
  [kind config-key used]
  (case kind
    :budget
    (str "`" config-key "` stopped this evaluation after " used
         " guarded steps. Split the work into smaller evaluations or reduce "
         "the input.")

    :timeout
    (str "`" config-key "` stopped this evaluation at its deadline after "
         used " guarded steps. Split the work into smaller evaluations or "
         "reduce the input.")

    (str "`" config-key "` stopped this evaluation after its output cap "
         "fired at " used " guarded steps. Reduce the input or print a "
         "smaller result.")))

(defn- policy-data
  [{::keys [^longs fuel-cell ^objects control-cell] :as holder} kind]
  (let [config-key (policy-config-key control-cell kind)]
    (cond-> {::policy-kind kind
             ::steps-used (steps-used holder)
             ::initial-fuel (aget fuel-cell initial-index)
             ::remaining-fuel (aget fuel-cell remaining-index)
             ::invocation-class (aget control-cell invocation-class-index)
             ::config-key config-key}
      (contains? #{:budget :timeout} kind)
      (assoc :seon.error.sci/class :interrupt))))

(defn policy-error!
  "Record and return one flat policy steering error value."
  {:malli/schema [:=> [:catn [::holder ::holder]
                             [::policy-kind ::policy-kind]]
                  :map]}
  [holder kind]
  (let [{::keys [config-key steps-used] :as data}
        (policy-data holder kind)
        message (policy-message kind config-key steps-used)
        throwable (ex-info message
                           (assoc data :seon.error/kind kind))]
    (error/record! {:seon.error/raw throwable
                    :seon.error/fault :agent})
    {:seon.error/message message
     :seon.error/kind kind
     :seon.error/data (dissoc data ::policy-kind)}))

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
  (when-let [{::keys [policy-kind]} (policy-throwable-data throwable)]
    (let [{::keys [config-key steps-used] :as data}
          (policy-data holder policy-kind)
          message (policy-message policy-kind config-key steps-used)]
      (error/record! {:seon.error/raw throwable
                      :seon.error/fault :agent})
      {:seon.error/message message
       :seon.error/kind policy-kind
       :seon.error/data (dissoc data ::policy-kind)})))

(defn- check-holder!
  [{::keys [^longs fuel-cell ^objects control-cell] :as holder}]
  (let [remaining (unchecked-dec (aget fuel-cell remaining-index))]
    (aset fuel-cell remaining-index remaining)
    (when (and (= 1 (aget fuel-cell enforce-index))
               (neg? remaining))
      (let [data (policy-data holder :budget)]
        (interrupt/interrupt!
         (policy-message :budget (::config-key data) (::steps-used data))
         (assoc data :seon.error/kind :budget)))))
  (when-let [interrupted? (aget control-cell interrupted-index)]
    (when (interrupted?)
      (let [data (policy-data holder :timeout)]
        (interrupt/interrupt!
         (policy-message :timeout (::config-key data) (::steps-used data))
         (assoc data :seon.error/kind :timeout)))))
  nil)

(defn check!
  "Charge one SCI safepoint, then check the platform interrupt predicate."
  {:malli/schema [:=> [:cat ::holder] :nil]}
  [holder]
  (check-holder! holder))

(defn interrupt-fn
  "Return the SCI safepoint closure for one retained context holder."
  {:malli/schema [:=> [:cat ::holder] 'fn?]}
  [{::keys [check!]}]
  check!)

(defn call!
  "Reset, arm, and execute one SCI invocation through the policy door."
  {:malli/schema [:=> [:cat ::call-request] :any]}
  [{::keys [holder policy evaluate! arm-deadline!]}]
  (reset! holder policy)
  (let [disarm! #?(:clj (when arm-deadline! (arm-deadline! holder))
                   :cljs nil)]
    (try
      (evaluate!)
      (finally
        (when disarm! (disarm!))
        (install-interrupted! holder nil)))))
