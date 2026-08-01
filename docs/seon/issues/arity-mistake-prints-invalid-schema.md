---
type: issue
status: open
tags: [issue, sci, eval, error, context]
---

# A wrong-arity agent call surfaces as `:malli.core/invalid-schema`

Observed live (2026-08-01, default cluster, real door probe):

```clojure
;; through seon.sci.eval/evaluate in my.agents.tally
(my.message/send)
;; => #:seon.error{:kind :seon.sci.eval/evaluation-failed,
;;                 :message ":malli.core/invalid-schema", …}
```

The instrumented `:function` schema has no matching arity, and malli's
schema-selection failure leaks out as the agent-facing message. An
arity mistake is the single most trained-on repair signal a model has;
`Execution error: :malli.core/invalid-schema` teaches nothing and
reads like a system fault rather than the agent's own mistake.

Expected: the flat error value reads like Clojure's own arity report —
`Wrong number of args (0) passed to: my.message/send` — with the real
arglists near it. Owner: the instrument/admission seam
(`seon.instrument` / `seon.sci.eval`), where the malli
`:function`-schema miss must be translated before it becomes the
agent-visible `:seon.error/message`.

Acceptance: the same probe returns a `:seon.error` value whose message
names the fn and the wrong arg count; the REPL-session context renders
it as a legible `Execution error:` line; one regression covers the
class (any instrumented multi-arity fn, wrong arity).

Blocks: the ruling-#24 owner-agent turn-3 mockup (error-recovery
history beat needs legible arity bytes).
