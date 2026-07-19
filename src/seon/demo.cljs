(ns seon.demo
  "Provide the namespace fixture for downstream build overrides.

   The fixture exercises late-bound definitions so downstream builds can
   replace behavior without changing callers. It is demonstration data, not an
   application service.")
(defn greeting
  "Overridable demo greeting."
  {:malli/schema [:=> [:cat] :string]}
  [] "hello from core")
(defn greet-loudly
  "Caller that must reflect an override of `greeting` (late binding)."
  {:malli/schema [:=> [:cat] :string]}
  [] (str (greeting) "!"))
