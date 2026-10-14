(ns seon.sci.arm
  "Arming at the write: each observed definition's contract is armed on the
  exact Var written, under the batch's arming projection, before the next
  expression runs. The write observer stages (`seon.sci.observe`) call it."
  (:require [sci.core :as sci]
            [sci.ctx-store]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]))

(defn- var-symbol
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "A host Var or an SCI Var: both name themselves by :ns and :name metadata; anything else names nothing." :gen/elements [nil false 0 "" :k [] {}]}]] [:maybe :qualified-symbol]]}
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

(defn- reaches?
  "Whether `function-symbol`'s contract in `projection` names `predicate`:
  inline, or in the form of a schema key it depends on, transitively."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol :qualified-symbol] :boolean]}
  [projection function-symbol predicate]
  (let [forms (:seon.schema.projection/forms projection)
        dependencies (:seon.schema.projection/schema-dependencies projection {})
        names? (fn [form] (contains? (#'schema/predicate-symbols-in form) predicate))]
    (or (names? (get-in projection [:seon.schema.projection/function-contracts function-symbol]))
        (loop [frontier (get-in projection [:seon.schema.projection/function-dependencies function-symbol] #{})
               seen #{}]
          (cond
            (empty? frontier) false
            (some #(names? (get forms %)) frontier) true
            :else (let [seen (into seen frontier)]
                    (recur (into #{} (comp (mapcat #(get dependencies % #{})) (remove seen)) frontier)
                           seen)))))))

(defn- rearm-dependents!
  "Re-arm each function `ctx` installed whose contract reaches `predicate` and
  whose wrapper compiled with another binding of it than `ctx` now calls:
  the context's own Var when it holds `predicate` apart, else `projection`'s.
  Only the transition re-arms: a later redefinition of a held-apart Var is the
  same binding. Answers the re-armed symbols."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol :seon.schema/projection :seon.db/database-value]
                  [:vector :qualified-symbol]]}
  [ctx predicate projection working]
  ;; the installed functions are few; only one compiled with another binding
  ;; of `predicate` asks whether its contract reaches it
  (if-not (contains? (#'schema/population-predicate-symbols
                      (:seon.schema.projection/forms projection)
                      (get projection :seon.schema.projection/function-contracts {}))
                     predicate)
    []
    (let [current (or (kernel/held-apart-var ctx predicate)
                      (get (schema/predicate-functions-in projection) predicate))
          contracts (:seon.schema.projection/function-contracts projection)]
      (into []
            (keep (fn [dependent]
                    (let [compiled-with (some-> (sci/resolve ctx dependent) deref meta
                                                ::instrument/predicate-functions)]
                      (when (and compiled-with
                                 (not (identical? current (get compiled-with predicate)))
                                 (reaches? projection dependent predicate))
                        (#'eval/arm-contract! ctx dependent (get contracts dependent) nil projection working)
                        dependent))))
            (disj @(::kernel/installed-functions ctx) predicate)))))

(defn arm!
  "Arm the observed Var's contract at its write, under the batch's arming
  projection, and mark it the installed definition of its name in this
  context: the live Var is the evaluated definition, so a call by name (the
  install gate's generated calls, a render) never installs it again from a
  stored row. A contract that cannot arm leaves the function callable as written, with the
  cause beside the write as `:seon.batch/arm-error`. Answers the write with what arming
  did. It reads the Var as `ctx` binds it. The base's callers of a function the
  write defines apart from the base's Var are re-interpreted onto it
  (`seon.sci.eval/reinterpret-inherited-callers!`), and the callers and tests
  `ctx` still runs compiled are bound to run interpreted (`seon.sci.eval/interpret-affected!`),
  so the forms after the write call the definition through them. When the
  write is a predicate `ctx` now holds apart, the installed functions whose
  contract reaches it re-arm on it (`rearm-dependents!`); a failure there is
  the write's `:seon.batch/arm-error` too."
  {:malli/schema [:=> [:cat :map :seon.db/database-value :map] :map]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world :as request} working {v :var :as write}]
  (sci.ctx-store/with-ctx ctx
    (let [sym (var-symbol v)
          contract (:malli/schema (meta v))
          function? (fn? @v)
          projection (delay (arming-projection request working))]
      (when (and sym function?)
        (kernel/mark-installed! ctx sym))
      (cond-> (if-not (and sym contract function?)
                write
                (try
                  ;; the evaluated contract as the Var holds it, its
                  ;; predicates the callables its symbols named
                  (#'eval/arm-contract! ctx sym contract nil @projection working)
                  (assoc write :seon.batch/armed true)
                  (catch Throwable t
                    (assoc write :seon.batch/arm-error (ex-message t)))))
        (and sym function?)
        (as-> armed
              (try
                (eval/reinterpret-inherited-callers! ctx working sym v)
                (eval/interpret-affected! ctx working [sym])
                (rearm-dependents! ctx sym @projection working)
                armed
                (catch Throwable t
                  (update armed :seon.batch/arm-error #(or % (ex-message t))))))))))

(defn write-event
  "A producer write as the batch records it: the Var's symbol, origin and kind.
  A definition SCI could not locate (made through `eval`) has the origin
  `{:reason :no-source-position}`; it is admitted, never refused."
  {:malli/schema [:=> [:cat :map] :seon.batch/write]}
  [{op :op the-var :var origin :origin kind :kind namespace-symbol :ns sym :sym root :root :as w}]
  (case op
    :bind (cond-> {:seon.batch/op :bind :seon.batch/kind (or kind :root) :seon.batch/var the-var}
            (var-symbol the-var) (assoc :seon.batch/sym (var-symbol the-var))
            origin (assoc :seon.batch/origin (if (:line origin) origin {:reason :no-source-position}))
            (:sci.impl/member-of (meta the-var)) (assoc :seon.batch/member-of (:sci.impl/member-of (meta the-var)))
            (contains? w :seon.batch/arm-error) (assoc :seon.batch/arm-error (:seon.batch/arm-error w))
            (fn? root) (assoc :seon.batch/fn? true))
    :unmap {:seon.batch/op :unmap :seon.batch/sym (symbol (str namespace-symbol) (str sym))}
    :remove-ns {:seon.batch/op :remove-ns :seon.batch/ns namespace-symbol}
    :namespace {:seon.batch/op :namespace :seon.batch/ns namespace-symbol}))

(defn changed-namespaces
  "Namespaces whose resolver state changed: aliases, refers, imports,
  requires or types, or whose named fixtures did (`seon.sci.lookup/agent-record-fixtures`). SCI's cached namespace object and private interns are
  not declarations. Admitted definitions contribute their namespace through
  `seon.turn.batch/derive-form`, independently of this resolver observation."
  {:malli/schema [:=> [:cat [:map-of :symbol :map] [:map-of :symbol :map]] [:set :symbol]]}
  [before after]
  (let [resolver-keys [:aliases :imports :refers :refer :required-namespaces :types
                       :seon.ns/fixtures]]
    (into #{}
          (keep (fn [[n m]]
                  (let [previous (get before n)]
                    (when (and (not (identical? m previous))
                               (not= (select-keys m resolver-keys)
                                     (select-keys previous resolver-keys)))
                      n))))
          after)))
