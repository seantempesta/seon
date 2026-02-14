(ns seon.test.hello
  "Trivial namespace to verify isolated agent JVM works.
  Tests Malli schema validation and basic functionality."
  (:require [malli.core :as m]))

(def greeting-schema [:map [:name :string] [:message :string]])

(defn greet
  "Create a greeting from the isolated JVM."
  [name]
  {:name name
   :message (str "Hello from isolated JVM, " name "!")})

(defn validate-greeting
  "Validate a greeting against the schema."
  [g]
  (m/validate greeting-schema g))
