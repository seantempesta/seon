(ns seon.soul)

(defmacro soul-md
  "Slurps repo-root SOUL.md at COMPILE time (JVM). The agent's identity
   is baked into the compiled CLJS — editing SOUL.md requires a recompile."
  []
  (slurp "SOUL.md"))
