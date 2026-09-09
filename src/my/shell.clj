(ns my.shell
  "Bounded foreground argv-vector process requests."
  (:require [seon.shell :as shell]
            [clojure.test.check.generators :as gen]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))



(def stdin-generator
  (gen/one-of
   [(gen/fmap (fn [text] {:my.shell/stdin-text text}) gen/string)
    (gen/fmap (fn [octets] {:my.shell/stdin-bytes octets})
              (gen/vector (gen/choose 0 255)))
    (gen/fmap (fn [digest] {:seon.blob/digest digest})
              (gen/fmap #(apply str %)
                        (gen/vector
                         (gen/elements (seq "0123456789abcdef")) 64)))]))

(schema/register-core-predicate! 'seon.shell/stdin? shell/stdin?)
(schema/register-core-predicate! 'seon.shell/output? shell/output?)

;; Register predicates before seon.effect loads the complete population.
(require '[seon.effect :as effect])

(schema.edn/load! {})

(defn run
  "Run one foreground argv vector and return complete process evidence."
  {:malli/schema
   [:=> [:cat :my.shell/run-request]
    [:or :my.shell/run-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.shell.jvm/run}
  [request]
  (effect/request! #'run request))
