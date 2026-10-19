(ns ^{:seon/babashka true} seon.schema.handed
  "The declaration population and projection handed to the operation running now.

  A schema operation gets its registry the way Malli's do: handed in, never
  found by name. These are the one dynamic default, read by `seon.schema` and by
  `seon.error.refusal`, which `seon.schema` requires, so neither names the other
  at run time. No requires: the operator loads it under babashka.

  `seon.schema.edn` binds the root of `packaged` when it loads, before any
  operation exists (the shipped configuration compiles at namespace load). An
  operation binds its own `*projection*`, `*projection-state*` or
  `*packaged-forms*`.")

(def ^:dynamic *projection*
  "The immutable projection handed to this operation, or nil."
  nil)

(def ^:dynamic *projection-state*
  "The advanceable projection state (a deref-able place holding
  `:seon.schema/projection`) handed to this operation, or nil."
  nil)

(def ^:dynamic *packaged-forms*
  "The declaration population `{schema-key form}` an operation binds, or nil."
  nil)

(def packaged
  "The packaged population, bound once when `seon.schema.edn` loads:
  `::forms`, the resources' `{schema-key form}`, `::projection`, a delay of
  the declaration projection over them, and `::lookup`, the `seon.cache` lookup. It answers where no operation handed a
  population or projection (a bare thread, the MCP thread, namespace load).
  Nil where `seon.schema.edn` is not loaded (the operator under babashka)."
  nil)

(defn projection
  "The projection handed to the operation running now, or nil."
  {:malli/schema [:=> [:cat] [:maybe :seon.schema/projection]]}
  []
  (or *projection*
      (some-> *projection-state* deref :seon.schema/projection)))

(defn call-with-forms
  "Call `f` with `forms` as this operation's declaration population."
  {:malli/schema [:=> [:cat :map [:fn clojure.core/ifn?]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [forms f]
  (binding [*packaged-forms* forms]
    (f)))
