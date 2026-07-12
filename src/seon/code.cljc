(ns seon.code
  "Inert foreign-code values — the `#code` heredoc literal's data shape.

  A `#code/<lang> <<SENTINEL … SENTINEL` block in an agent transcript
  reads to a `::block` map. The value is DATA — never evaluated as
  Clojure. Consumers (`seon.agent.fs` edit functions, future graph functions)
  take `::text` verbatim; `::lang` drives rendering (fenced code block)
  and language-aware handling. This ns will also house the foreign
  program-graph functions when that arc lands."
  (:require [seon.schema :as schema]))

;; The language tag is open — any keyword (:python :ts :rust :diff …);
;; unknown languages still round-trip, they just render un-highlighted.
(schema/register! ::lang :keyword)

;; Byte-faithful payload — exactly the characters between the opener
;; line and the closing sentinel line, no normalization.
(schema/register! ::text :string)

(schema/register! ::block [:map [::lang ::lang] [::text ::text]])

(schema/register! ::value [:or :string ::block])

(defn block?
  "True when `x` is a `#code` heredoc value (a `::block` map)."
  {:malli/schema [:=> [:catn [::value-under-test :any]] :boolean]}
  [x]
  (boolean (and (map? x) (string? (::text x)) (keyword? (::lang x)))))

(defn text
  "The verbatim source text of `x` — a `::block` map or plain string."
  {:malli/schema [:=> [:catn [::value ::value]] :string]}
  [x]
  (if (string? x) x (::text x)))
