(ns seon.agent.internal
  "Framework data-manipulation internals for the agent lifecycle functions.

   This namespace is NOT whitelisted for full-source rendering — it holds
   the small shared plumbing the teaching namespaces ([[seon.agent.lifecycle]])
   lean on so their bodies stay clean and self-explaining in agent context.

   Right now that is the one shared shape the scoped functions need: the loud
   'no agent in scope' error envelope (errors are values, never a throw).")

(defn no-agent-error
  "The error envelope returned when a scope-defaulting function runs with no
   agent in the ALS scope. `verb` is the function name (string) used to build
   a guiding message that points the caller at `(seon.db/with-agent …)`.
   Errors are values — this is a value, not a throw."
  [verb]
  {:seon.db/ok? false
   :seon.db/error {:seon.error/message
                   (str verb ": no agent in scope — call inside "
                        "(seon.db/with-agent …).")}})
