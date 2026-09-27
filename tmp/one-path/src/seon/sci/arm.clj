(ns seon.sci.arm
  "Arming at the write: each observed definition's contract is armed on the
  exact Var written, under the batch's arming projection, before the next
  expression runs. The write observer stages (`seon.sci.observe`) call it."
  (:require [sci.core :as sci]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]))

(defn- var-symbol
  {:malli/schema [:=> [:cat :any] [:maybe :qualified-symbol]]}
  [v]
  (let [m (meta v)]
    (when (and (:ns m) (:name m))
      (symbol (str (:ns m)) (str (:name m))))))

(defn arming-projection
  "The projection a definition in this batch arms under: the working value's,
  or, when the batch's delta holds registrations not yet in it, the working
  projection with those declarations (Malli recompiles only what changed)."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value] :seon.schema/projection]}
  [{delta :seon.sci.batch/delta} working]
  (let [base (db/carried-projection working)
        forms (some-> delta :seon.schema.delta/candidate-forms deref)]
    (if (or (nil? forms) (= forms (:seon.schema.projection/forms base)))
      base
      (#'schema/projection-with-declarations
       `arming-projection base forms
       (get base :seon.schema.projection/function-contracts {})
       (assoc (schema/population-options base)
              :seon.schema/schema-admissions
              (into (get base :seon.schema.projection/schema-admissions {})
                    (keep (fn [[k f]] (when (not= f (get (:seon.schema.projection/forms base) k ::absent))
                                        [k {:seon.schema.admission/source :agent}])))
                    forms))))))

(defn- refusing-root
  {:malli/schema [:=> [:cat :qualified-symbol :seon.error/throwable] ifn?]}
  [sym t]
  (let [refusal (ex-info (str sym " is unavailable: its contract did not arm: " (ex-message t))
                         {:seon.error/member :seon.fn/spec :seon.fn/sym sym} t)]
    (fn [& _] (throw refusal))))

(defn arm!
  "Arm the observed Var's contract at its write, under the batch's arming
  projection, and mark it the installed definition of its name in this
  context: the live Var is the evaluated definition, so a call by name (the
  install gate's generated calls, a render) never installs it again from a
  stored row. A contract that cannot arm leaves a root that refuses by name;
  the definition still answers its Var. Answers the write with what arming
  did."
  {:malli/schema [:=> [:cat :map :seon.db/database-value :map] :map]}
  [{ctx :seon.sci.eval/ctx :as request} working {v :var :as write}]
  (let [sym (var-symbol v)
        contract (:malli/schema (meta v))
        function? (fn? @v)]
    (when (and sym function?)
      (kernel/mark-installed! ctx sym))
    (if-not (and sym contract function?)
      write
      (try
        (#'eval/install-function-contract!
         ctx {:seon.fn/sym sym :seon.fn/spec (pr-str contract) :seon.sci.eval/sci-var v}
         (arming-projection request working) working)
        (assoc write :seon.batch/armed true)
        (catch Throwable t
          (sci/alter-var-root v (constantly (refusing-root sym t)))
          (assoc write :seon.batch/arm-error (ex-message t)))))))

(defn write-event
  "A producer write as the batch records it: the Var's symbol, origin and kind."
  {:malli/schema [:=> [:cat :map] :seon.batch/write]}
  [{:keys [op var origin kind ns sym root] :as w}]
  (case op
    :bind (cond-> {:seon.batch/op :bind :seon.batch/sym (var-symbol var)
                   :seon.batch/kind (or kind :root) :seon.batch/var var}
            origin (assoc :seon.batch/origin origin)
            (:sci.impl/member-of (meta var)) (assoc :seon.batch/member-of (:sci.impl/member-of (meta var)))
            (contains? w :seon.batch/arm-error) (assoc :seon.batch/arm-error (:seon.batch/arm-error w))
            (fn? root) (assoc :seon.batch/fn? true))
    :unmap {:seon.batch/op :unmap :seon.batch/sym (symbol (str ns) (str sym))}
    :remove-ns {:seon.batch/op :remove-ns :seon.batch/ns ns}
    :namespace {:seon.batch/op :namespace :seon.batch/ns ns}))

(defn changed-namespaces
  "Namespaces whose SCI namespace map is not the one it was before: every
  alias, refer, import or intern made by the form, and each namespace made."
  {:malli/schema [:=> [:cat :map :map] [:set :symbol]]}
  [before after]
  (into #{} (keep (fn [[n m]] (when-not (identical? m (get before n)) n))) after))
