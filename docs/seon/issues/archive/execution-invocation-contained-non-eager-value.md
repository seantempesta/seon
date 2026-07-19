---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, cljs, pod]
---

# Execution invocation contained non-eager value

## Problem

After a deliberate replacement-child recovery, turn `xi0o95ub0bxt` for agent
`violet-emus-create` failed before returning eval rows because the parent
invocation contained a value rejected by the eager ordinary IPC contract. The
host reduced the exception to its message, losing the already available
structural value path and type, so the exact producer could not be identified
from retained evidence.

## Owner

`seon.execution/encode-message` owns the ordinary-data check and
`seon.execution.host/invoke-now!` owns retained parent-side failure evidence.
The producer was `seon.repl.internal/parse-forms`: ClojureScript's reader
expands `#(...)` with a nested eager `cljs.core/Cons`. It is valid form data but
is intentionally not one of the persistent collection shapes admitted by the
execution IPC contract.

## Resolution

The parser now recursively materializes reader-produced seqs as persistent
lists before emitting an eval form. The IPC predicate remains strict and still
rejects arbitrary sequential values. Parent encoding and host failure evidence
also retain an ordinary value path and host type, so any future violation names
its producing boundary.

Focused parser, search, execution, and host proof passes 116 tests and 660
assertions. Live turn `kdhmf2xb6rih` then ran the exact anonymous-function form
successfully in 233 ms, called cross-child function
`my.graduation.shared/greeting` with exact result `"shared:ordinary-ipc"` in
218 ms, and entered `wait`; the Bun pod remained ready.

## Acceptance

- Parent IPC refusal retains an ordinary structural value path and value type.
- Reproducing the same live journey identifies and fixes the producing owner.
- A replacement child renders its prompt, evaluates another shared-program
  call, and enters `wait` without another core fault.
