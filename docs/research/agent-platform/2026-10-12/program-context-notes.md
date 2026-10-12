---
title: "Program context lane: where the code contradicts hybrid-immutable-env.md E3/E4"
type: research
status: lane program-context, 2026-10-12; dated evidence, not a plan
basis: HEAD 786915ca3 plus the E4 slice (this lane's next commit); `seon.sci.eval/fork-for-turn`, `seon.sci.eval/regenerate-agent-context!`, `seon.sci.eval/install-evaluated-rows!`, `seon.cluster.agent/acquire-context!`, `seon.cluster.agent/start-flow!`
---

# Program context lane notes

What was built, and three places where the hybrid's E3/E4 text cannot be met as written before E5. Each has options;
the recommended one is marked.

## What the E4 slice does

`seon.sci.eval/fork-for-turn` compares the branch head's program key (`seon.sci.eval/program-key-input`: store,
`seon.sci.eval/program-revisions`, dial revisions, the loaded commit) with the key the agent context holds in its
kernel snapshot (`::program-key`). Equal: the context is returned as it is; no acquisition, no fork, no walk. Moved: the
caller's base is acquired when its own key moved too (`seon.sci.eval/acquire!`, moved here from `seon.turn/resume-turn`; the base holds `::program-key` from `seon.sci.eval/base-ctx`, `acquire!` and a covered settlement), the head's
`seon.sci.eval/program-context` is looked up, and if it is not the value the context forked from (`::program-context`)
one fork of it (`seon.sci.eval/forked-program`) receives the private layer. The layer is read only in the namespaces
whose map differs from the held value's or that hold a rebound Var (`seon.sci.eval/own-namespaces`), so the
8,435-11,085-Var snapshot `seon.sci.eval/base-bindings` is deleted. An agent context is now a fork of a frozen program
context, never of the mutable base.

## 1. The new context cannot be "returned and installed by the owning Flow proc" yet

The agent context has two holders: the graph's handle (`:seon.sci.eval/agent-ctx`, set by `seon.cluster.agent/start-flow!`)
and the roster entry's execution (`:seon.sci.eval/ctx`), which the test step reads through
`seon.cluster.agent/acquire-context!`. A returned context installed by one would leave the other stale, so a moved key
still advances the context in place (`reset!` of its `:env` and kernel atoms), as before.

- **A (recommended).** Keep the in-place advance until one owner holds the agent context: the test step takes the
  request's context from the turn (E3's "the test step reuses the request's context") instead of the roster, then the
  proc installs the returned value and the roster stops holding it.
- B. The roster holds a handle the graph derefs. A second place for the same state; refused by CLAUDE.md.

## 2. E3's "one fork per fresh agent" needs E5 first

A fresh agent on a new branch gets two SCI forks: the branch base (`seon.sci.eval/branch-ctx` → `seon.sci.eval/base-ctx`
→ `seon.sci.eval/copy-base-ctx`) and the agent's (`forked-program`). The base must stay a live context because
settlement writes into it (`seon.sci.eval/install-evaluated-rows!` → `seon.sci.eval/transfer-evaluated-roots!`,
`seon.sci.eval/interpret-affected!`) and `my.program/ns-unmap!`/`ns-unalias!` evaluate in it (`my.program` native!).

- **A (recommended).** Do E5 next: settlement derives the next value (`interpret-overlay!` on a builder) instead of
  writing the base; then the base holds custody only and E3 deletes `copy-base-ctx`, `fork-cluster-ctx` and
  `branch-ctx` with one fork per agent.
- B. Create the base lazily, only for a request that settles program entities. Keeps the transfer path alive longer.

## 3. The writer keeps its context after its own settlement (hybrid owner question 3, decided by measurement)

The hybrid's first version re-forks the writer at its next request. Measured in the check JVM, that re-fork derives
the overlay of the settled commit from the loaded base (`seon.sci.eval/derive-overlay-ctx`, proportional to the whole
branch plan, not the request): `seon.request-cost-test/a-redefinitions-reply-accounts-for-the-window` went red, the
reply attributing 536 MB of a 784 MB JVM window (it attributed at least 90% at HEAD). So when the base covers the
settlement (`seon.sci.eval/installation-covers-program-change?`), `seon.sci.eval/install-evaluated-rows!` advances the
base's and the writer's `::program-key` to the settled commit's, as it already advanced their `::acquired-basis`: the
writer's context already holds what it settled, and its next request forks nothing. The value it holds stays the one it
forked from; the next moved key (another writer, an adoption) forks the head's value and carries the layer against it.
E5 replaces this with the derived next value.

## Findings on the way

- A new agent's first request on default (agent `pc-probe-b`, 2026-10-12 03:00Z, concurrent with `pc-probe-a`'s first
  request) spent 48,422.6 ms in `:custody`, with a JVM window of 42,046 MB allocated and 360,314 ms CPU; `pc-probe-a`'s
  identical request took 743 ms. Unattributed; `:custody` for a fresh agent was 165-178 ms in the cache-hits audit.
- The adoption-moved base (`[store 6acc3ec1]`) stays cached at an estimated 80 MB until Caffeine evicts it. Decision:
  no eviction call at adoption (a second owner of the kind's keys); E7 weighs the value honestly so Caffeine owns it.
  A JOL retained-size walk was not run on default: from the base's root it reaches most of the 400 MB live heap.
