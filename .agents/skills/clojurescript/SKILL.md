---
name: clojurescript
description: "Mine the deleted Seon CLJS pod as historical quarry. Load this only when intentionally reading src-old .cljs, reconstructing a deleted pod behavior, or deciding what lesson—not implementation—to carry into the JVM. Do not load it for current runtime, eval, UI, async, or agent work: fresh Seon is CLJ-only and the pod/self-host engine is deleted."
---

# Historical CLJS pod quarry

The CLJS build is off and the pod is deleted. Current Seon is CLJ/JVM only
(`docs/prds/sci-execution-runtime/plan/README.md:345-351`). Current agent
evaluation belongs to `src/seon/sci/eval.clj`; current web rendering belongs to
`src/seon/render/web.clj`.

Use this skill only to understand a behavior in `src-old/` before designing a
fresh replacement. Never:

- add `.cljs` to fresh `src/`;
- restore shadow-cljs, Bun, `cljs.js`, bootstrap compile state, or the pod;
- apply Promise, `^:async`, `await`, or self-host rules to current JVM code;
- port an old effectful eval surface merely because its semantics are known.

## What the quarry can still teach

The deleted path had two distinct compilers:

- ahead-of-time shadow-cljs for the pod namespaces; and
- the `cljs.js` self-host compiler for agent-evaluated forms.

That distinction explains historical test failures and data in old research.
It is not a current architecture option. The deletion ruling requires the JVM
to own execution once.

When inspecting an old async form, read the vendored compiler rather than
guess:

- `await` is a macro and requires an async analyzer environment at
  `reference-code/clojurescript/src/main/clojure/cljs/core.cljc:975-977`;
- the analyzer propagates async function metadata in
  `reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc`;
- the compiler emits native JavaScript async functions in
  `reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc`; and
- the old self-host entry point is
  `reference-code/clojurescript/src/main/cljs/cljs/js.cljs`.

These sources explain why a top-level old `(await ...)` failed while an await
inside an old `^:async` function could compile. Preserve that only as forensic
understanding.

## Old Seon owners

Read these only as quarry:

| old owner | historical behavior worth identifying |
|---|---|
| `docs/prds/archive/agent-fsm/research/cljs-async-await-2026-06-28.md` | deleted self-host compilation, Promise auto-await, and compile-state evidence |
| `src-old/seon/agent/AGENTS.md` | surviving historical agent/pod contract notes |
| `src-old/seon/web/` | Node/CLJS web server and Datastar feed |

The detailed historical investigation remains at
`docs/prds/archive/agent-fsm/research/cljs-async-await-2026-06-28.md`. Check
every old line citation against `src-old/` because the quarry can still move
during deletion.

## How to carry a lesson forward

Translate an old behavior into one of the surviving shapes:

1. pure code returns ordinary values for the run loop to interpret;
2. genuine capabilities eventually cross one guarded system boundary; or
3. durable facts are committed by the JVM owner.

Do not recreate Promise auto-await, process-global result stashes, in-eval
database mutation, or pod instrumentation. Current SCI containment uses one
time limit and one interrupt function (`src/seon/sci/eval.clj`;
`reference-code/sci/doc/interrupt.md`).

If a current task mentions “the pod,” first prove that it is deliberately
mining `src-old/`. Otherwise load the skill for the fresh owner instead:

- `seon-flow-architecture` for runtime machinery;
- `datastar-web-ui` for the JVM web renderer;
- `data-oriented-clojure` for fresh Clojure; or
- `repl` for current form reading and evaluation.
