(ns seon.dev.hook-test-ns
  "Test namespace for safely experimenting with the unified dev hook.

   Use this namespace to test hook behavior without breaking real code.
   Edit functions here to trigger hook runs and observe the output."
  (:require [malli.core :as m]))

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
