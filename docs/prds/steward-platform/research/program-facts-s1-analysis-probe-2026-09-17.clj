;; Read-only JVM probe of the existing analysis and row-construction owners.
;; Invoke through MCP on default; no database write or test harness.
(require 'seon.fn.analyzer 'seon.fn 'seon.program)

(let [source (str "(ns sample.s1 (:require [seon.schema :as schema] "
                  "[clojure.test :refer [deftest is]]))\n"
                  "(schema/register! :sample.s1/value :int)\n"
                  "(defn value {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
                  "(defn caller {:malli/schema [:=> [:cat :int] :int]} [x] (value x))\n"
                  "(deftest example (is (= 1 (caller 1))))")
      analysis (with-in-str source
                 (seon.fn.analyzer/analyze {:seon.fn.analyzer/paths ["-"]}))
      starts (into [0] (keep-indexed (fn [i c] (when (= c \newline) (inc i)))) source)
      context {:text source :line-starts starts :seon.fn/byte-line-starts starts}
      rows (get (#'seon.fn/analysis-rows-by-file
                 analysis (#'seon.fn/first-party-function-symbols analysis)
                 {"<stdin>" context} #{:sample.s1/value})
                "<stdin>")]
  {:seon.program/identities (mapv seon.program/row-identity rows)
   :seon.program/schema-rows (filterv :seon.schema/key rows)
   :seon.program/rows
   (mapv #(select-keys % [:seon.ns/name :seon.fn/sym :seon.test/sym
                         :seon.schema/key :seon.fn/file :seon.fn/form-span
                         :seon.fn/calls]) rows)})
