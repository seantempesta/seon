---
title: "Plan (Opus): the program context as an immutable value of a commit"
type: research
status: design round 3 plan, independent of Sol's; design only, no code
basis: HEAD 4ac288008 read by symbol (`seon.sci.eval/base-ctx`, `derive-base-ctx`, `copy-base-ctx`, `acquire!`, `acquired-database?`, `fork-for-turn`, `regenerate-agent-context!`, `base-bindings`, `turn-interns`, `acquired-program`, `fork-cluster-ctx`, `branch-ctx`; `seon.turn/resume-turn`; `seon.cluster.agent/acquire-context!`); SCI fork pin f455b3b4 (`sci.core/fork`, `sci.impl.vars/update-binding!`, `owns?`); dig-immutable-env.md; hybrid-start-a-flow.md §H5; start-a-flow-benchmark.md; JVM probes on default, 2026-10-12 (below)
---

# The program context as an immutable value of a commit

## 1. Design in one paragraph

What the owner calls "the environment" is, in SCI's words, a **context**; Seon already uses "environment" for
`:seon.env/environment` (the cluster's world map), so this plan says **program context**: the SCI context holding
exactly the program a commit holds, with no custody, no recorder, no agent and no atom. It is a plain value
`{:sci/env <the env map> ::installed <set> ::program <snapshot map> ::key <key>}` plus SCI's static ctx fields, and
it is a pure function of a commit: `(program-context store commit)` computes the key from the commit's content
revisions (H2: carried by the commit) and does one `seon.cache/lookup` of kind `::program-context`; on a miss it is
derived from Datahike exactly as today (`derive-base-ctx` for the loaded program, the overlay plan interpreted over
it). **Nothing ever writes it.** An agent's context is **one fork** of it (a fresh `:env` atom with a new generation,
fresh kernel atoms, its custody and print session), and the agent **keeps the value it forked from**. At each eval
request (the idle boundary) the agent computes the key of its branch head: equal key, it reuses its own context
unchanged (no fork, no acquisition); a different key, it forks the new value and carries its private layer (own Vars,
own bindings, atoms, result objects) across, comparing against the *held previous value* instead of today's
per-turn `base-bindings` snapshot. A settlement that wrote program rows does not mutate a "live base": it **builds
the next value** (fork of the current value, the evaluated roots transferred in, frozen) and inserts it under the new
commit's key, so the writer's next request, every other agent on that branch and a successor forked from that commit
all hit it. Resume at any commit is lookup + fork; a successor forks its predecessor's end commit and gets its
predecessor's program context by the same lookup. What cannot be stored (live private values, closures, host
objects) never needed storing: it is the agent's, it dies with its process, and its handles already answer "gone".

## 2. Measured on default (JVM probes, 2026-10-12; disposable copies, no fork of a live context)

| Probe | Result |
|---|---|
| program in the base | 652 namespaces, 8,435 Vars, 4,603 installed functions, 6,463 function rows |
| `sci/fork` of a context | 0.23 µs; a **value fork** (fresh `:env` atom from the held map + two kernel atoms) 0.55 µs, 2.6 KB |
| `base-ctx` hit (copy + `acquired-basis` + plan + dials + refusal recording) | 1.07 ms, 1.8 MB; miss after eviction 190 ms |
| `acquire!` on an equal program (every turn) | 0.34 ms, 0.7 MB (`acquired-database?` 0.28 ms) |
| `program-revisions` (171 attributes) | 0.02 ms |
| `fork-for-turn`, fresh agent, no history | 3.1 ms, 5.5 MB, of which `base-bindings` **2.3 ms, 5.3 MB** (walks all 8,435 Vars) |
| `fork-for-turn`, carried agent (**every turn today**) | **8.0 ms, 10.1 MB**, also on an unchanged program |
| `turn-interns` on a context with no own defs | 0.14 ms |
| sharing: fork A `(def x)`, `(defn seon.id/id …)` | A sees both; B, C (forks of the same value) and the held value see neither; A holds 1 own binding, the value 0; host `seon.id/id` unchanged |
| an atom defined in an agent fork, after `fork-for-turn` carries it | the same object (`swap!` counts 1 then 2) |
| `context-weight` of a base entry | 79 MB charged (12,288 B × 6,463); an overlay shares the base's `:namespaces` map structurally (`identical?` confirmed for an equal program) yet is charged the same |

## 3. The parts

| Part | Contents | Key | Cost now | Floor |
|---|---|---|---|---|
| commit → key | content revisions of `acquisition-attributes`; loaded commit; plan digest; armed dials | `[store loaded-commit (digest plan) projection-revisions dials]`; empty plan → `[store loaded-commit]` (H4) | revisions 0.02 ms; plan cached `::overlay-plan` | ~0.1 ms, no allocation of note |
| loaded program context | JVM Var copies, armed wrappers, projection, program namespaces | `[store loaded-commit]` | cold 850–1,056 ms / 1.8 GB (H9); hit 1 ms | 100–200 ms cold (H9, H10); hit 0.01 ms (lookup only) |
| branch program context | the loaded value forked, the plan interpreted, frozen | full key above | per fork today: overlay derivation on a fresh fork (H3) | one derivation per distinct program, shared by every branch and commit holding it |
| next value after a settlement | current value forked, the request's roots transferred (`transfer-evaluated-roots!`), frozen | the settled commit's key | today written into the live base in place | proportional to the request's definitions |
| agent context | one fork + custody + print session + result objects | none (owned by the agent's proc) | 3.1 ms fresh, 8 ms every turn | 0.01 ms fork + one pull for a resumed agent's print session |
| idle-boundary advance | private layer carried onto a fork of the new value | — | 8 ms / 10 MB every turn | 0 on an equal key; on a change, proportional to the private layer (0.14 ms empty) |
| successor | predecessor's end commit → same lookup | that commit's key | full branch acquisition today | lookup + fork |

## 4. Reused, changed, deleted (by symbol)

**Reused.** `sci.core/fork` semantics (context-owned bindings, Var identity kept); `seon.cache/lookup`;
`seon.db/content-revisions`; `seon.sci.eval/derive-base-ctx` and `derive-overlay-plan`/`overlay!` as the miss path;
`turn-interns`, `own-bindings`, `inherit-var-bindings!`; `transfer-evaluated-roots!` (now into a fork of the value);
`replaced-definitions`; `session-print-options`, `bind-settled-results!` for a resumed agent.

**Changed.** SCI fork: `sci.core/fork` of a context marked frozen creates the child atom from the held env map
without `swap!`ping the source's generation (today every fork writes its source; harmless to a source that never
evaluates, but it is the one write keeping the value from being a value). `seon.sci.eval/base-ctx` →
`program-context`: returns the value, never a copy with custody. `fork-for-turn` → `fork-for-agent`: value in,
context out; with a carried agent context it runs only when the key moved. `regenerate-agent-context!`: compares
against the agent's held previous value (`get-in` its `:namespaces`) instead of `::base-bindings`.
`acquired-program`: returns the next value for `seon.cache` instead of writing the live base. `seon.turn/resume-turn`
and `seon.cluster.agent/acquire-context!`: key compare, then reuse or advance; no `acquire!`. `seon.test`'s request
world and `seon.cluster.boot`'s cluster context: lookup + fork. `my.program` operations (`ns-unmap!`,
`remove-ns!`): evaluate in the agent's fork and yield the next value like a settlement. `context-weight`: what the
entry retains beyond the value it was forked from (plan size for an overlay, a settlement's definitions for a next
value), measured, not 12 KB × every function.

**Deleted.** `acquire!` (its `reset!` of `:env`, `::installed-functions`, `::program-snapshot`); `acquired-database?`
and `loaded-program-current?`'s `swap!` (a key compare replaces both); `copy-base-ctx`, `fork-cluster-ctx`,
`branch-ctx`, `program-equal`; `base-bindings`, `::base-bindings`, `same-program-root?`; `derive-base-ctx`'s two
`swap!`s (build the snapshot map, then freeze); kinds `::base-context`, `::overlay-context` (one kind
`::program-context`); `:my.program/base-ctx`, `::base-ctx` carried on contexts (the agent holds the value);
`acquire-function-from-database!`'s acquisition (lazy installs, if kept, land in the agent's own fork).

## 5. Phases (one owner each, red first)

**E1. A fork writes nothing into its source (SCI fork).** `sci.core/freeze` marks a context; `fork` of a frozen
context leaves its `:env` map `identical?`. Red proof `sci.core-test/forking-a-frozen-context-writes-nothing`
(SCI fork suite) and `seon.sci.eval-test/sci-fork-bindings-are-per-context` stays green. Owner: SCI fork, Opus.

**E2. The program context is a value.** `seon.sci.eval/program-context` over `derive-base-ctx`/overlay, keyed as §3,
one cache kind, frozen. Red proof `seon.sci.eval-test/no-agent-work-writes-a-program-context`: take the value for a
fixture branch, run an agent request that `defn`s, rebinds a program Var, `def`s an atom and `my.program/ns-unmap!`s,
then every map in the value is `identical?` to before; two commits with equal program revisions answer the
`identical?` value; warm lookup under 0.5 ms / 0.5 MB. Retires H3/H4's two kinds. Owner: `seon.sci.eval`, Opus.

**E3. One fork per agent.** `fork-for-agent` replaces `copy-base-ctx`, `fork-cluster-ctx`, `branch-ctx` and the fresh
path of `fork-for-turn` at every caller (`acquire-context!`, `seon.test`, `seon.cluster.boot`). Red proof
`seon.cluster.agent-test/a-fresh-agents-environment-costs-one-fork` (the original H5 proof, re-tagged):
`sci.core/fork` called once, environment leg under 1 ms / 1 MB beyond the lookup (measured 3.1 ms / 5.5 MB after
the 1 ms lookup). Owner: `seon.cluster.agent` + `seon.sci.eval`, Opus (one-path semantics).

**E4. The idle boundary forks only when the program moved.** `resume-turn` computes the head's key; equal → the
agent's context as it is; moved → fork the new value, carry the private layer against the held old value. Red proofs
`seon.turn-test/an-eval-request-on-an-unchanged-program-forks-nothing` (zero `sci.core/fork`, acquisition leg under
0.5 ms; measured 8 ms / 10 MB) and `seon.turn-test/a-private-atom-survives-another-agents-program-change` (agent A
`def`s an atom, B commits a `defn` on A's branch, A's next request sees B's definition and the same atom).
`seon.render-simplification-test/a-program-change-replaces-a-private-definition` and
`seon.sci.eval-test/acquired-source-context-forks-have-branch-custody-and-private-defs` stay green. Deletes
`base-bindings`. Owner: `seon.turn` + `seon.sci.eval`, Opus.

**E5. A settlement builds the next value.** `acquired-program` forks the current value, transfers the request's
roots, freezes, inserts under the settled commit's key (read-through: an equal program another branch built wins).
Red proof `seon.sci.eval-test/a-settled-program-context-equals-its-rebuild`: after a request that defines a function,
its test and a caller, the next value and a cold `program-context` of the same commit (cache entry removed) resolve
the same symbols to the same declarations and the caller returns the same value; the writer's next request performs
no overlay derivation. Deletes `acquire!`. Owner: `seon.sci.eval`, Opus.

**E6. A successor reuses its predecessor's point.** `start-flow!` (H7) forks the predecessor's end commit; the program
context is that commit's lookup. Red proof `seon.flow-test/a-successor-starts-from-its-predecessors-program-context`:
zero calls of `derive-base-ctx`/`derive-overlay-ctx`, environment leg under 2 ms, the predecessor's last definition
resolvable, none of its private Vars present. Owner: `seon.flow`, after H7.

**E7. Weight is what an entry retains.** `context-weight` charges the delta from the value forked from. Red proof
`seon.sci.eval-test/a-program-context-weighs-its-own-declarations`: three branches with one-function changes add
under 3 × 1 MB of weighted bytes (today 3 × 79 MB charged); confirm with a retained-heap probe before trusting the
estimate. Owner: `seon.sci.eval`, Sonnet (measurement).

**Order.** E1 → E2 → E3 → E4 → E5; E7 after E2; E6 after E5 and H7. H2 (revisions ride the commit) makes the key
correct after restart; until then H1's bridge stands and the key is still right in-process.

## 6. Risks

1. **A closure in a shared value names an agent's context.** Our SCI fork binds an SCI fn to its defining context
   when called with no context on the thread (f8a2542d, 41194667). A next value built from agent A's evaluated roots
   carries fns defined in A's fork; called from a host thread with no context, they run under A's context. E5's
   proof must call a transferred fn from a bare thread; if it reads A's bindings, the transfer re-roots the fn on the
   frozen value (SCI fork change) rather than guarding callers.
2. **Incremental and rebuilt values diverge.** E5 is the only place the value is not derived from Datahike; its
   equivalence proof is the regression, and a divergence is fixed in the transfer, never by a second comparison.
3. `setContextual` flips a flag on a shared SCI Var (monotone, idempotent); it is not a value write, but E2's
   `identical?` proof does not see it.
4. Lazy installs (`kernel/ensure-function!`) today write the base on first call; on a frozen value they land in each
   agent's fork, repeating work per agent. Measure how many functions the loaded copy leaves lazy before E3.
5. Root and many test members share one branch; each now holds its own fork and advances at its own boundary,
   which is AGENTS.md's rule, but tests that read the base after another member's write must request again.

## 7. Unsure

- Whether a successor should also inherit its predecessor's **private layer** when both live in one process (a fork
  of the predecessor's agent context instead of the value). Recommended no: private state is not program, and a
  successor's start must equal its start after a restart.
- Whether `(program-context commit)` should key by commit id first (one more cache row per commit, saves the 0.1 ms
  key derivation) or only by content. Recommended content only: the derivation is cheap and one key per program.
- How much of today's 314 MB "duplicate overlays" is the weigher's overcount rather than retention (E7 measures it).
- Whether the frozen marker belongs in the SCI fork (`sci.core/freeze`) or Seon should simply never fork the value
  through `sci/fork` (a value fork is three lines but reaches into SCI's `:env` key). Recommended the fork: SCI owns
  contexts.
