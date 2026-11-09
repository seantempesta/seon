(ns seon.edn
  "The EDN text codec: the text a value is printed as where text is wanted (a form's
  contract, a schema's source, a stable sort key) and the value read back. A stored value
  is plain data (`:db.type/data`, docs/storage.md#plain-values), not this text. It requires no Seon namespace, so seon.error, seon.config and
  seon.contract (which load first) use it like every other caller; its two functions carry
  their schemas as metadata, armed at boot."
  (:require [clojure.edn :as edn]))

(defn encode
  "`v` as EDN text that reads back: no print limits, no metadata."
  {:malli/schema [:=> [:cat :any] :string]}
  [v] (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str v)))

(defn decode
  "The value `encode` printed."
  {:malli/schema [:=> [:cat :string] :any]}
  [s] (edn/read-string s))
