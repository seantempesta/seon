---
title: "Card SCI-5 An SCI fn on a context-less thread runs under its defining context (gitlink bump)"
type: reference
status: active
created: 2026-10-09
---

# SCI-5 An SCI fn on a context-less thread runs under its defining context

Task `seon.sci.eval/a-host-thread-reads-a-redefined-var-as-its-first-definition`; census
docs/research/agent-platform/2026-10-09/ctx-audit/census.md. Root ruling 2026-10-09: option A1 (correct now, fast
later). The fork change is landed and pushed; what remains is root's gitlink bump. Seon's `src/` needs no change.

## What exists and what becomes true

Since SCI-1 every Var read or write selects the binding of the context in `sci.ctx-store/*ctx*`
(`sci.impl.vars/active-binding`). A thread SCI did not enter (a raw `Thread`, an executor, a Flow proc, a handler
`seon.effect/request*` dispatches) has no `*ctx*`, so an agent's Var read there answered its first definition and
`alter-var-root` threw "No context found". `future` and `pmap` convey `*ctx*` and were already right.

Fork `seon-env-hook` `f8a2542d` (`sci.impl.fns/in-defining-ctx` inside `sci.impl.fns/gen-fn`): each SCI fn arity
checks `*ctx*` on entry; when it is nil the fn re-enters itself under `(binding [*ctx* ctx])` of the context it was
defined in. With a context active nothing changes, so an inherited fn called in a fork still sees the fork's
definitions. The binding happens once per entry from a context-less thread; nested calls never bind again.

## Cost (`sci.vars-bench`, ns per loop iteration, empty loop about 8.4)

| | 7cb34638 | f8a2542d |
|---|---|---|
| call through a never-redefined Var | 28.8-29.7 | 32.5-33.5 (+4 ns, +14%) |
| call through a redefined Var | 40-43 | 45-47 (+10%) |
| allocation per call | 128 / 160 B | +32 B (the `*ctx*` thread-binding MapEntry) |
| read loops, fork over 5,700 Vars, def in a fork | | unchanged |

Later speed-up: SCI's own call nodes always run with a context active, so they can invoke an unchecked entry and
leave the check to host callers (with SCI-4).

## Root's step

One commit bumping the `reference-code/sci` gitlink to `f8a2542d`, through `bin/lane commit ... --test
seon.sci.context-reads-test` (the archive pins the submodule), then restart default with `start --head` in the next
batch.

## Proof

- Seon: `seon.sci.context-reads-test/a-host-thread-reads-the-current-definition` turns green (expected
  `[[2 :second] [2 :second] [2 :second]]` for future, raw Thread and ForkJoinPool); the guard
  `seon.sci.context-reads-test/a-branch-redefinition-of-a-loaded-test-runs-under-its-own-bound` stays green.
- Fork: `sci.fork-bindings-test/a-context-less-thread-runs-an-sci-fn-under-its-defining-context` (raw Thread,
  executor, future, an inherited caller nested in a fork's fn and in a grandchild fork, `(#'v)`, `@#'v` and
  `alter-var-root` on a context-less thread, the base sees its own, a host-held fn): 6 failures at 7cb34638, green at
  f8a2542d. `lein test`: 415 tests, 1619 assertions, only the 5 known baseline failures.

## Known gaps

- A fn defined in the base and passed by value from a fork to a host thread, e.g. `(Thread. calls-priv)`, runs
  under the base, its defining context; a fn literal written in the fork runs under the fork.
- A fn binds the context map it was created in. If Seon hands an agent a new context per eval request rather than
  the same one, a thread started in an earlier request keeps that request's bindings; verify after the bump with a
  long-running thread started in one eval request and a redefinition in the next.
- Rejected alternative, kept on fork branch `sci-fn-ctx-pass` `6aa8db7b`: pass the ctx to analyzed Var reads and
  Var-head calls. Within noise, but `(#'v)`, `@#'v` and writes on a context-less thread stay wrong.
