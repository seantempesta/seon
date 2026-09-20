(ns ^{:seon.ns/context-relevant? true} my.edit
  "Edit source files only when their expected digest still matches."
  (:require [clojure.test.check.generators :as gen]
            [seon.edit :as edit]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))


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
   'seon.edit/valid-form-operation? edit/valid-form-operation?))

;; The predicate must exist before seon.effect loads the schema population,
;; whose :my.edit/form-request declaration resolves this Var.
(require '[seon.effect :as effect])

(schema.edn/load! {})

(defn form!
  "Edit one named top-level Clojure form after checking its digest.

  Returns :my.edit/path, :my.edit/changed?, before/after digests and byte
  counts, plus :my.edit/source-window and its line bounds. A stale digest
  or ambiguous target returns a flat error without editing.

  Example:
  (let [file (my.fs/write! {:my.fs/path \"example.clj\"
                            :my.fs/content {:my.fs/text \"(def example 1)\"}
                            :my.fs/precondition {:my.fs/expected-absence? true}})]
    (my.edit/form! {:my.edit/path \"example.clj\"
                    :my.edit/expected-digest (:my.fs/after-digest file)
                    :my.edit/form {:my.edit.form/head 'def :my.edit.form/name 'example}
                    :my.edit/operation :replace
                    :my.edit/source \"(def example 2)\"}))"
  {:malli/schema
   [:=> [:cat :my.edit/form-request]
    [:or :my.edit/result :my.edit/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'form! request))

(defn exact!
  "Replace exact source text after checking the file digest.

  Returns :my.edit/path, :my.edit/changed?, before/after digests and byte
  counts, plus :my.edit/source-window and its line bounds. A stale digest
  or ambiguous target returns a flat error without editing.

  Example:
  (let [file (my.fs/write! {:my.fs/path \"example.clj\"
                            :my.fs/content {:my.fs/text \"(def example 1)\"}
                            :my.fs/precondition {:my.fs/expected-absence? true}})]
    (my.edit/exact! {:my.edit/path \"example.clj\"
                    :my.edit/expected-digest (:my.fs/after-digest file)
                     :my.edit/old-string \"(def example 1)\"
                     :my.edit/new-string \"(def example 2)\"}))"
  {:malli/schema
   [:=> [:cat :my.edit/exact-request]
    [:or :my.edit/result :my.edit/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'exact! request))

(defn lines!
  "Replace an inclusive line window after checking its text and digest.

  Returns :my.edit/path, :my.edit/changed?, before/after digests and byte
  counts, plus :my.edit/source-window and its line bounds. A stale digest
  or ambiguous target returns a flat error without editing.

  Example:
  (let [file (my.fs/write! {:my.fs/path \"example.clj\"
                            :my.fs/content {:my.fs/text \"(def example 1)\"}
                            :my.fs/precondition {:my.fs/expected-absence? true}})]
    (my.edit/lines! {:my.edit/path \"example.clj\"
                    :my.edit/expected-digest (:my.fs/after-digest file)
                     :my.edit/from-line 1 :my.edit/to-line 1
                     :my.edit/old-window \"(def example 1)\"
                     :my.edit/new-window \"(def example 2)\"}))"
  {:malli/schema
   [:=> [:cat :my.edit/lines-request]
    [:or :my.edit/result :my.edit/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.edit.jvm/edit}
  [request]
  (effect/request! #'lines! request))
