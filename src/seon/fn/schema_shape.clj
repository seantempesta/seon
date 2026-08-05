(ns seon.fn.schema-shape
  "Reserved owner for the unfinished P12 schema-shape graph.")

(def normalization-revision
  "The planned P12 normalization revision, not yet implemented."
  "malli-80138076960e7820523b4cb932c5b5d1936d4e7f/p12-v1")

(defn normalized-form
  "Report that P12 schema normalization is not implemented."
  {:malli/schema
   [:=> [:cat :seon.schema/value :map] :seon.error/value]}
  [_compiled _predicate-functions]
  {:seon.error/kind :seon.error/not-yet
   :seon.error/message
   "P12 schema normalization is not implemented or verified."})

(defn fingerprint
  "Report that P12 schema fingerprinting is not implemented."
  {:malli/schema
   [:=> [:cat :seon.schema/value] :seon.error/value]}
  [_form]
  {:seon.error/kind :seon.error/not-yet
   :seon.error/message
   "P12 schema fingerprinting is not implemented or verified."})

(defn shape-row
  "Report that P12 schema-shape facts are not implemented."
  {:malli/schema
   [:=> [:cat :seon.schema/value :map] :seon.error/value]}
  [_compiled _predicate-functions]
  {:seon.error/kind :seon.error/not-yet
   :seon.error/message
   "P12 schema-shape facts are not implemented or verified."})

(defn row-form
  "Report that P12 shape reconstruction is not implemented."
  {:malli/schema [:=> [:cat :map] :seon.error/value]}
  [_row]
  {:seon.error/kind :seon.error/not-yet
   :seon.error/message
   "P12 shape reconstruction is not implemented or verified."})

(defn assert-consistent!
  "Report that P12 collision checking is not implemented."
  {:malli/schema
   [:=> [:cat [:sequential :map]] :seon.error/value]}
  [_rows]
  {:seon.error/kind :seon.error/not-yet
   :seon.error/message
   "P12 schema collision checking is not implemented or verified."})
