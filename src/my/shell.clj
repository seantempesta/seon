(ns ^{:seon.ns/context-relevant? true} my.shell
  "Bounded foreground argv-vector process requests."
  (:refer-clojure :exclude [run!])
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

(defn run!
  "Run an argv vector in the supplied working directory.

  Supply :my.shell/argv and :my.shell/cwd. No shell expansion occurs unless
  the executable is a shell. Returns :my.shell/exit, :my.shell/stdout and
  :my.shell/stderr with the argv and cwd. Optional :my.shell/stdin supplies
  text, bytes or a blob.

  Example:
  (my.shell/run! {:my.shell/argv [\"printf\" \"%s\" \"Verified.\"] :my.shell/cwd \".\"})"
  {:malli/schema
   [:=> [:cat :my.shell/run-request]
    [:or :my.shell/run-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.shell.jvm/run}
  [request]
  (effect/request! #'run! request))
