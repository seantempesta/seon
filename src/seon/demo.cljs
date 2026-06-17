(ns seon.demo
  "Demo core ns — the always-on fixture for third-party build-time override.
   `greet-loudly` calls `greeting` through the late-bound global, so a
   third-party override of `greeting` flows through to existing callers.")
(defn greeting
  "Overridable demo greeting."
  {:malli/schema [:=> [:cat] :string]}
  [] "hello from core")
(defn greet-loudly
  "Caller that must reflect an override of `greeting` (late binding)."
  {:malli/schema [:=> [:cat] :string]}
  [] (str (greeting) "!"))
