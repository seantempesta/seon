(ns seon.dev.hook-test-ns
  "Test namespace for safely experimenting with the unified dev hook.

   Use this namespace to test hook behavior without breaking real code.
   Edit functions here to trigger hook runs and observe the output."
  (:require [malli.core :as m]
            [seon.schema :as schema]))

(schema/register! ::n [:int {:description "Fixture integer slot"}])
(schema/register! ::s [:string {:description "Fixture string slot"}])

;;; ---------------------------------------------------------------------------
;;; Success Cases - These should pass all checks
;;; ---------------------------------------------------------------------------

(defn simple-add
  "Simple function without schema - should pass."
  [a b]
  (+ a b))

(defn greet
  "Simple string function."
  [name]
  (str "Hello, " name "!"))

;;; ---------------------------------------------------------------------------
;;; Generative-check skip fixtures (seon.dev.verify-test)
;;;
;;; Side-effecting fns must NEVER be generatively invoked against the live
;;; system (the 2026-06-10 bug: gen checks called registry/ensure-db! with
;;; generated keywords, creating real LMDB stores under data/sessions/).
;;; These fixtures cover all four cases of seon.dev.verify/skip-gen-check?.
;;; The counters are test instrumentation: they prove whether the gen
;;; runner actually invoked a fn.
;;; ---------------------------------------------------------------------------

(defonce side-effect-count
  ;; Incremented by the fns that gen checks must SKIP. Stays 0 unless
  ;; the skip mechanism is broken.
  (atom 0))

(defonce pure-check-count
  ;; Incremented by the pure fn that gen checks must RUN. Goes positive
  ;; when generative checking actually executes.
  (atom 0))

(defn gen-pure-inc
  "Pure computation (the counter is test instrumentation only) —
   generative checks RUN this."
  {:malli/schema [:=> [:catn [::n :int]] :int]}
  [n]
  (swap! pure-check-count inc)
  (inc n))

(defn mutate-something!
  "Side-effecting by the `!` naming convention — generative checks
   must SKIP this."
  {:malli/schema [:=> [:catn [::n :int]] :int]}
  [n]
  (swap! side-effect-count inc)
  n)

(defn covert-mutation
  "Side-effecting WITHOUT the `!` suffix — opted out of generative
   checks via :seon.dev/no-gen metadata."
  {:seon.dev/no-gen true
   :malli/schema [:=> [:catn [::n :int]] :int]}
  [n]
  (swap! side-effect-count inc)
  n)

(defn shout!
  "Pure despite the `!` name — forced back into generative checking
   via :seon.dev/gen-check metadata."
  {:seon.dev/gen-check true
   :malli/schema [:=> [:catn [::s :string]] :string]}
  [s]
  (str s "!"))

;;; ---------------------------------------------------------------------------
;;; Test Variables - Edit these to trigger hook
;;; ---------------------------------------------------------------------------

(def test-counter
  "Increment this to trigger a file change without breaking anything."
  6)

;;; ---------------------------------------------------------------------------
;;; Temporarily removed broken function to test hook behavior
;;; ---------------------------------------------------------------------------

;; Broken function commented out to test single success output

(comment
  ;; REPL experiments go here
  (simple-add 1 2)
  (greet "Claude")
  :ok)
