(ns seon.dev.hook-test-ns
  "Test namespace for safely exercising dev-hook verification behavior."
  (:require [malli.core :as m]
            [seon.schema :as schema]))

(schema/register! ::n [:int {:description "Fixture integer slot"}])
(schema/register! ::s [:string {:description "Fixture string slot"}])

(defn simple-add
  "Simple function without schema."
  [a b]
  (+ a b))

(defn greet
  "Simple string function."
  [name]
  (str "Hello, " name "!"))

;;; Generative-check skip fixtures (seon.dev.verify-test).

(defonce side-effect-count
  (atom 0))

(defonce pure-check-count
  (atom 0))

(defn gen-pure-inc
  "Pure computation fixture for generative checks."
  {:malli/schema [:=> [:catn [::n :int]] :int]}
  [n]
  (swap! pure-check-count inc)
  (inc n))

(defn mutate-something!
  "Side-effecting fixture skipped by the `!` naming convention."
  {:malli/schema [:=> [:catn [::n :int]] :int]}
  [n]
  (swap! side-effect-count inc)
  n)

(defn covert-mutation
  "Side-effecting fixture skipped by explicit metadata."
  {:seon.dev/no-gen true
   :malli/schema [:=> [:catn [::n :int]] :int]}
  [n]
  (swap! side-effect-count inc)
  n)

(defn shout!
  "Pure fixture forced back into generative checking."
  {:seon.dev/gen-check true
   :malli/schema [:=> [:catn [::s :string]] :string]}
  [s]
  (str s "!"))

(def test-counter
  "Increment this in tests when a stable file value is useful."
  6)
