(ns my.edit
  "Edit source files only when their expected digest still matches."
  (:require [clojure.test.check.generators :as gen]
            [seon.edit :as edit]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(defn valid-form-operation?
  "Whether a form-edit request has the required replacement source.

  Takes a request value and returns a boolean. This predicate validates
  `:my.edit/form-request`; call `form` to perform the edit."
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
  "Edit one named top-level Clojure form under a digest fence.

  Takes a path, expected digest, form selector, operation, and replacement
  source when required. Returns the changed-file summary or a flat error.
  Use it for structural Clojure edits."
  {:malli/schema
   [:=> [:cat :my.edit/form-request]
    [:or :my.edit/result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'form request))

(defn exact
  "Replace exact source text under a digest fence.

  Takes a path, expected digest, old string, new string, and optional
  replace-all flag. Returns the changed-file summary or a flat error. Use it
  when the literal target text is unambiguous."
  {:malli/schema
   [:=> [:cat :my.edit/exact-request]
    [:or :my.edit/result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'exact request))

(defn lines
  "Replace one verified line window under a digest fence.

  Takes a path, expected digest, inclusive line bounds, old window, and new
  window. Returns the changed-file summary or a flat error. Use it when line
  boundaries are the clearest stable target."
  {:malli/schema
   [:=> [:cat :my.edit/lines-request]
    [:or :my.edit/result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'lines request))
