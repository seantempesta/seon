(ns seon.fn.signature
  "Reserved owner for the unfinished P12 source-signature join.")

(defn function-signatures
  "Report that the P12 source-signature join is not implemented."
  {:malli/schema [:=> [:cat :map] :seon.error/value]}
  [_request]
  {:seon.error/kind :seon.error/not-yet
   :seon.error/message
   "P12 source-signature joining is not implemented or verified."})
