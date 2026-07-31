---
type: issue
status: open
severity: blocker
tags: [issue, agent, context, toolkit]
---

# Name the real messaging function in the getting-started instruction

## Problem

The one instruction every agent reads says:

```text
Talk to other agents with `(my.message/send! …)`.
```

There is no `my.message/send!`. The function is `my.message/send`
(`src/my/message.cljc:100`). The instruction is the highest-authority text in
the prompt, so a fresh agent follows it, the runtime lint refuses the form,
and the agent burns a whole turn — one paid model call — recovering.

## Evidence

Observed 2026-07-31, cluster `visual-qa`, agent `scout`, first turn:

```text
Form 0 returned {:seon.error/kind :seon.cluster.loop/lint-rejected,
 :seon.error/message "Static analysis rejected this source form with 1 error
 finding(s)." … "Unresolved var: my.message/send!" …}
```

Its second turn opens with the model doing the runtime's work:

```text
;; I see from the previous run that Form 0 was rejected because
;; `my.message/send!` was unresolved. … `my.message/send` is defined
;; (without the `!` suffix).
```

Source: `src/seon/cluster/instruction.cljc:23`.

Note the arity the agent guessed first was also wrong (a single map); the
real contract is positional `[to content]` / `[to content about]`. The
instruction shows neither.

## Owner

`seon.cluster.instruction/getting-started-text`.

## Acceptance

A fresh agent's first turn sends a message successfully without a
lint-rejected form. The instruction's example call matches the function's
committed `:seon.fn/spec`; a test derives the named symbols from the program
graph so the text cannot drift from the toolkit again.
