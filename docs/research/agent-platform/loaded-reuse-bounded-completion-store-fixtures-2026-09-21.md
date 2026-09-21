---
type: research
status: complete (three design concerns resolved; corrections integrated into plan/ at the same commit)
created: 2026-09-21
tags: [agent-platform, review, sci, isolation, profiling, bounded-execution, fixtures, datahike]
---

# Loaded reuse, bounded completion, independent-store fixtures

Read: the README and B2 at `4a6470532`, the astra feedback note, the
second-perspective note, A1, C1, B4, D1. Seams read this session:
`reference-code/sci/src/sci/core.cljc:112-140` (`copy-var*`), `:345-350`
(`fork`), `sci/impl/utils.cljc:362-379` (`bind-root!`);
`src/seon/sci/eval.clj:841-935` (`install-row!`, `install-jvm-root!`),
`:694-712` (`install-function-contract!`);
`reference-code/core.async/.../flow/impl.clj:29-36` (`futurize`), `:243-320`
(the proc loop); `src/seon/turn.clj:3358-3378`, `:5217-5262`,
`src/seon/cluster/agent.clj:805-850`; `reference-code/datahike/src/datahike/versioning.cljc:550-640`
(`fork-database`), `gc.cljc:22-100` (`reachable-in-branch`, the barrier
invariant); `src/seon/db.clj:365-412` (`call-with-custody`). One read-only
JVM evaluation (closure sizes, in the wins note §5.3). Documentation only.

## 1. Loaded reuse, overrides and profiling

**What construction guarantees.** `install-jvm-root!` binds a core row as
`(sci/copy-var* host-var sci-ns)`: the SCI Var holds the JVM root VALUE at copy
time. Codex's probe is exact: that compiled value keeps calling its JVM callees
through their JVM Vars. So per-context installation guarantees direct contract
ownership and root stability for the copied function itself, and nothing
about its callees. No SCI fork change can intercept a compiled call.

**The eligibility rule, at acquisition and at change, from stored facts.**
For a context X over program value P_X, against the JVM's loaded commit L
(the development cluster's `:seon.source/commit-id`):

1. `overridden(X)` = rows whose `:seon.fn/source` or namespace bindings
   (`:seon.ns/requires`, aliases, refers, imports) differ between P_X and L for
   the same symbol, plus X's private redefinitions (a fork's own Vars, by
   `:sci/generation`). **A contract-only difference is not an override**: the
   body is byte-equal, so the JVM value is reused and X's contract is applied
   by X's own wrapper (`install-function-contract!` over the copied root).
   Without this exemption the first task class (adding contracts) would
   interpret the caller closure of every contracted private function.
2. `affected(X)` = the reverse closure of `overridden(X)` over `:seon.fn/calls`
   (AVET-indexed) ∪ declared `:seon.fn/invokes` edges, restricted to rows in
   P_X. Computed once per acquisition or change; measured 313 ms for fifteen
   seeds over 34,363 edges (wins note §5.3).
3. `interpreted(X)` = `overridden(X)` ∪ `affected(X)`: installed from stored
   source by `install-function-from-database!` and armed per context. Every
   other row binds the JVM copy. Unknown dispatch (a row with unresolved
   references that could reach an overridden symbol) joins `affected` and is
   counted; that count is B1's fidelity finding when it is large.
4. Change propagation: an admitted change (agent write, deletion, resolver
   change, adoption) names its identities (B1's report). `overridden` and
   `affected` are updated from those identities and their reverse closure;
   untouched eligible rows keep their copies.
5. Shared JVM reload (adoption advances L): for every retained context, the
   adoption's identities whose loaded body changed but P_X did not enter
   `overridden(X)` at X's next boundary, with their closure interpreted from
   P_X's stored source. Serial turns make "next boundary" exact; an evaluation
   in flight across an adoption is the existing non-atomic reload tear and is
   recorded against the adoption's identities, never repaired.

Closure sizes decide practicality: `seon.error.refusal/diagnostic` 1,902 of
4,603 functions, `seon.db/q` 1,116, `seon.cluster.message/send!` 3. Every
override reports its interpreted-closure size as data.

**An affected caller that cannot be interpreted** (SCI-unloadable: host
interop, `deftype`, `gen-class`) — owner decision, three options:

| Option | Guarantee | Cost | Given up |
|---|---|---|---|
| Refuse the override in that context, naming the caller (recommended) | no compiled caller ever bypasses an override | some core overrides impossible in SCI | agent autonomy on host-bound seams |
| Admit with a typed `bypassing-callers` set the agent sees every turn | visible partial isolation | one more fact; agents must read it | the "never bypass" guarantee |
| Interpret best effort, JVM fallback recorded | maximal admission | silent divergence in behaviour | correctness; rejected by the owner's statement |

**Profiling attribution.** A cell lives in the wrapper closure that owns the
call: interpreted rows have per-context wrappers, so their cells are that
context's by construction. A JVM Var wrapper has ONE cell per definition; it is
the **host** cell, attributed to no cluster. Candidate work that reaches JVM
paths lands in host cells and the read reports it as host work, never as the
development cluster's. B4's per-member reach observation needs an execution
scope, which already exists: `call-with-custody` binds `seon.db/*conn*` for
test bodies (`db.clj:365-387`); the wrapper inserts `(symbol, digest)` into the
bound scope's set when one is bound. Profiling totals need no scope; no
registry, no per-call graph walk.

**Spec corrections landed:** README §3 (eligibility rule; host attribution),
B2 §2a (the five steps; contract-only exemption; closure reported), A1 A1-2
(form 4 confirms direct ownership only; indirect isolation is B2's closure),
C1 §2 (host cell, not the development cluster's).

## 2. Bounded completion

Codex is right that an unbounded second `.get` can wedge the proc and shutdown.
The proc loop (`impl.clj:271-320`) reads the next input only after the
transform returns, so serial ownership needs no permit; what the transform
must guarantee is that it RETURNS under a bound with honest evidence.

The transform, replacing the 252-line observer and `submit-evaluation!!`:

1. `futurize` the evaluation on the carried compute executor; `.get` under the
   part's declared `time-limit`. SCI's `interrupt!` fires there for interpreted
   code; a host call ignores it.
2. On timeout: commit the fault naming agent, turn and part (bounded
   reporting); `(.cancel task true)` — JDK blocking IO, sleeps and locks
   respond to the interrupt, CPU loops and natives may not; `.get` once more
   under the SAME declared bound (no new dial).
3. Exited: record termination, settle the part as `:time`, continue.
4. Still alive: record `:seon.turn/part-abandoned` with the thread id, mark the
   agent's context handle unusable (`:seon.agent/context-state` carries the
   abandoned part), disarm the agent's graph, and return the fault. The next
   boundary refuses to reuse the context until `.isDone`; the abandoned
   thread holds the old fork's env and the agent's private objects, which the
   fault says. `disarm!` waits the same bound and reports, never longer.
5. Out-of-proc callers (`submit-source!`, debug controls) send through the
   wake in-port; no path enters the context while the transform runs.

No wait is unbounded; overlap is impossible by the loop; a live body after
two bounds is a recorded, visible fact, not a silent wedge, and the JVM is
never claimed to terminate arbitrary work. Landed in B2 §2b and §6.

## 3. Independent-store fixtures

Four sites need more than a branch (B4 §2d). `fork-database` copies every
konserve key and warns that a concurrently written source can tear; a
publication monitor does not quiesce other branches' writes in one JVM.

| Site | Real need | Smallest sound arrangement |
|---|---|---|
| `cluster/source_database_test.clj:14` | a commit-bearing store for `commit-as-db` | dissolves with B4 commit 1: the fixture branches off the OPEN file store, which carries the commit graph; no copy |
| `blob_test.clj:218`, `ai_stream_fold_test.clj:378` | store-global blob mechanics, no program rows | fresh empty store with the schema installed (B4 already) |
| `cluster/mcp_test.clj:567` | evaluations producing artifacts, then root retraction and paging | first the probe: if the retraction assertion derives from datoms on the test's branch (`blob/get` after retracting the root), a branch suffices; otherwise a **reachable-only copy** |

The reachable-only copy is sound by the barrier invariant Datahike's GC relies
on (`gc.cljc:83-100`): every key a commit references is written before the
head flips, and a commit is immutable, so the set `reachable(C)` computed by
`reachable-in-branch`'s walk from the fork-point commit record (plus Seon's
`:datahike.gc/reachable-extension` for blob keys, `gc.cljc:152`) is closed and
complete at any time after C is committed, whatever other writers do. The fork
change is `fork-database` taking `{:reachable-only? true}` and copying that set
instead of `k/keys` — ≈ 15 lines reusing the walk. Its cost is proportional to
C's reachable keys and is measured once for the one test that needs it; a
duration allowance is never a consistency claim. Landed in B4 §2d and §6.
