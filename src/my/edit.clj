(ns my.edit
  "Digest-fenced structural and exact source editing requests."
  (:require [clojure.test.check.generators :as gen]
            [seon.edit :as edit]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(defn valid-form-operation?
  "True when operation and replacement-source presence form one valid arm."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [request]
  (and (map? request)
       (case (:my.edit/operation request)
         (:replace :insert-before :insert-after)
         (and (contains? request :my.edit/source)
              (string? (:my.edit/source request))
              (edit/single-form? (:my.edit/source request)))

         :delete (not (contains? request :my.edit/source))
         false)))

(def ^:private digest-generator
  (gen/return (apply str (repeat 64 "0"))))

(def form-request-generator
  (gen/let [path (gen/such-that seq gen/string-alphanumeric)
            digest digest-generator
            operation (gen/elements
                       [:replace :insert-before :insert-after :delete])]
    (cond-> {:my.edit/path path
             :my.edit/expected-digest digest
             :my.edit/form {:my.edit.form/head 'defn
                            :my.edit.form/name 'generated}
             :my.edit/operation operation}
      (not= :delete operation)
      (assoc :my.edit/source "(defn generated [] nil)"))))

(defonce ^:private _form-operation-predicate
  (schema/register-core-predicate!
   'my.edit/valid-form-operation? valid-form-operation?))

;; The predicate must exist before seon.effect loads the schema population,
;; whose :my.edit/form-request declaration resolves this Var.
(require '[seon.effect :as effect])

(schema.edn/load! {})

(defn form
  "Edit one unambiguous named top-level Clojure form."
  {:malli/schema
   [:=> [:cat :my.edit/form-request]
    [:or :my.edit/result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'form request))

(defn exact
  "Replace one exact string occurrence, or all occurrences when explicit."
  {:malli/schema
   [:=> [:cat :my.edit/exact-request]
    [:or :my.edit/result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'exact request))

(defn lines
  "Replace an exact, digest-fenced one-based inclusive line window."
  {:malli/schema
   [:=> [:cat :my.edit/lines-request]
    [:or :my.edit/result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'lines request))
