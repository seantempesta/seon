---
type: research
status: active
created: 2026-10-09
lane: affected-callers
---

# Affected callers on a branch: interpret the reverse closure, tests included

Tasks `seon.sci.eval/a-file-derived-proof-never-sees-the-branch-redefinition` and
`seon.sci.eval/a-branch-redefinition-never-reaches-its-inherited-callers`. AGENTS.md ("The program is entities"): a
JVM has one set of compiled Vars; a branch interprets its differing entities and their affected callers in SCI and
binds compiled Vars for the rest. The code already does this at acquisition; it misses tests and it never does it
after acquisition. AGENTS.md's design holds; nothing here changes it.

## What exists (default `6e9b9017c` loaded, 2026-10-09)

- Acquisition: `seon.sci.eval/base-ctx` forks the cluster base and `seon.sci.eval/overlay!` →
  `seon.sci.eval/interpret-overlay!` interprets the branch's overridden functions plus `seon.fn/reverse-closure`'s
  `:seon.fn/affected` from stored source; `seon.sci.eval/install-row!` keeps a host-bound member compiled (or refuses
  it by name when the branch changed it). Probe, agent `ac-helper` (branch redefining
  `seon.error/collection-member-problem` to HEAD's fix): `base-ctx` of its branch value 438 ms, 313 interpreted, and
  `(seon.error/explain-problem …)` there answers the branch's `{:a 1}`.
- Gap 1 (file proofs): the closure's `:seon.fn/tests` are dropped; only agent-authored tests are interpreted, and
  `seon.test/resolve-test` resolves a `:core` test to its host Var (`resolve-loaded`), whose compiled body calls the
  compiled callee. Probe: installing the stored row of `seon.error-test/a-map-for-a-sequential-is-refused-as-the-map`
  into that branch context (`install-row!`, ns row then test row, ~1 ms each) and running it through
  `seon.test.runner/run-var!` with the context: 3 pass, 0 fail; the same test resolved from the host is red.
- Gap 2 (the agent's REPL): the agent's live context is acquired once and carried. After a `defn`, re-analysis of
  callers happens only when the definition landed in a NEW Var (`install-evaluated-rows!`'s identity check;
  `seon.sci.arm/arm!` → `reinterpret-inherited-callers!`), which SCI-1 (Var identity kept across forks) made never
  true; and `stale-reach` kept only agent-authored callers anyway. A compiled caller (bound by
  `install-jvm-root!`) is never promoted. Probe: `ac-helper`'s next eval request, inherited `explain-problem` still
  answered `[:a 1]` (compiled), while `collection-member-problem` itself answered the branch.

## Design: one operation, three call sites

`interpret-affected!` (the overlay's own body, generalized): given a context, a database value and the changed
function symbols (seeds), interpret every member of their reverse closure (functions AND tests, file-derived or
REPL) that this context does not already interpret, from stored source, after the namespaces they need; the
context's `::interpreted` set grows by union. Seeds the context itself just evaluated are not re-evaluated.

- Closure: `seon.fn/reverse-closure` over the indexed reverse call edges; cost follows the closure, not the program.
  Measured on default: leaf `seon.render.ns/first-doc-line` 12 ms (65 affected, 5 tests); `seon.error/explain-problem`
  53-65 ms (312, 8); hub `seon.id/digest` 373 ms (2,393, 95).
- Host-bound members (`:seon.fn/host-bound?`): `install-row!` keeps their compiled Var (existing rule); calls
  through them stay compiled, which is RF-3's execution-evidence case, not this lane's. Macros in the closure are
  interpreted from source like any function row; a compiled caller of a host macro stays compiled (host-bound).
- Tests: the overlay records the closure's tests (`:seon.fn/tests`) as the context's interpreted tests
  (`seon.sci.eval/interpreted-test?`) without installing them. `seon.test/resolve-test` loads a file test's
  namespace under the test loader (as the compiled path already does), then interprets the test row into the member's
  context (`seon.sci.eval/interpret-test!`), so the test's helpers the closure does not reach bind their compiled
  interns. Installing file tests eagerly at acquisition failed: a test namespace the JVM has not loaded leaves its
  helpers unresolvable (check run: `Unable to resolve symbol: namespace-unit` for `seon.render.ns-test`). Lazy is
  also the cheaper order: a test is interpreted only when a member runs it.
- When: (1) acquisition (`interpret-overlay!`, now with tests); (2) settlement into the agent's live base
  (`install-evaluated-rows!`, replacing the dead identity check), so the next eval request sees it; (3) at the write
  in the turn fork (`seon.sci.arm/arm!`, replacing `reinterpret-inherited-callers!`), so later forms of the same
  request see it. Promotion happens once per member per context: afterwards SCI-1's context-owned bindings carry
  every later redefinition with no re-analysis, so a repeated `defn` pays only the closure query.
- Cache: the closure is a function of the program's call edges; if the query shows in profiles, key it in
  `seon.cache` by `seon.test.runner/program-revisions-digest` and the seed set. Not added until measured.

## Cost per defn

First redefinition of `f` in a context: closure query (12-373 ms) plus one interpretation per newly promoted member
(about 0.3 ms per function source in a fork, hostbound-opus §1.2; ~1 ms per deftest). Explain-problem: ~65 ms +
~312 × 0.3 ms ≈ 160 ms, twice (turn fork and live base). Hub `seon.id/digest`: 373 ms + 2,393 promotions, measured
4.7-5.3 s at acquisition (hostbound-opus §1.2): over one second, a finding with an existing task
(`seon.sci.eval/a-branch-overriding-a-widely-called-function-acquires-in-seconds`; the fix is the lazy closure, D3,
binding each member to a stub that interprets on first call; landed, see Lazy promotion). Later redefinitions: the closure query only.

## Lazy promotion (lane lazy-callers, 2026-10-09)

Interpreting the whole closure eagerly made a first redefinition cost per caller (3.5 MB, ~1 ms each) whether
or not anything called it, twice. Now `seon.sci.eval/interpret-overlay!` interprets only the overrides (and the
closure's host-bound members, which keep their compiled Var, and its macros, which run at their callers'
analysis) and binds every other closure member's Var, in that context only (SCI-1's per-context binding), to
`seon.sci.eval/lazy-root`: its first call interprets the member from stored source in the context executing it
(`seon.sci.eval/promote-member!`, through `install-selected!`) and then calls what that context binds; a member
that cannot interpret ends bound to a root refusing by name. A member whose namespace the context lacks gets a new
Var holding the lazy root, so a test helper interprets once its test namespace is loaded. Cost: the closure walk,
one query for the members' `:seon.fn/host-bound?` facts, one `bind-root!` per member; interpretation follows the
callers actually called. Settlement no longer walks again: `seon.sci.eval/carry-promotions!` binds in the live base
the closure the turn fork holds beyond it (walking only for a definition the fork never promoted). Interpreted
callers then deref the Var directly; a lazy root stays on the path only where its value was captured.

Measured in check JVMs (HEAD bb934b396 vs this change, `seon.profile/measure` per thread, fresh `base-ctx` each):

| seed | callers | walk | first promotion before | after | first call through a caller |
|---|---|---|---|---|---|
| `seon.render.ns/first-doc-line` | 65 | 52 ms / 122 MB | 252 ms / 304 MB | 15 ms / 23 MB | `render-ai` 15 ms / 8 MB, then 0.2 ms |
| `seon.error/collection-member-problem` | 322 | 197 ms / 589 MB | 574 ms / 1.14 GB | 63 ms / 119 MB | - |
| `seon.id/digest` | 2,401 | 284 ms / 661 MB | 3,027 ms / 6.46 GB | 234 ms / 482 MB | 3.9 ms / 3.9 MB |

A redefining eval request through MCP: `first-doc-line` 2,290 ms / 260 MB (settle 541, evaluate 183) before,
1,627 ms / 28 MB (settle 324, evaluate 75) after; `collection-member-problem` 2,816 ms / 977 MB (settle 554,
evaluate 405) before, 1,109 ms / 111 MB (settle 111, evaluate 70) after. What remains is the test step
(0.8-1.0 s, running the reaching tests) and the closure walk itself (`seon.fn/reverse-closure`, about 0.1-0.4 ms and
0.3 MB per caller, mostly `seon.db/pull-many` of identity names per BFS level), the next slice.

## As landed (769c4a843) and what SCI-3 then deletes

`seon.sci.eval/reinterpret-inherited-callers!` and `install-evaluated-rows!`'s identity check stay: removing them
turned `seon.repl-differential-test/an-inherited-caller-sees-a-same-pass-redefinition` red (`[1 1 2]`), so a
definition can still land in a Var other than the one an interpreted caller was analyzed against (another writer's
caller, re-acquired base). That is SCI-3's evidence to collect before deleting `stale-reach`, `reinterpret-reach!`,
`reinterpret-inherited-callers!` and `inherited?`: find which path still mints a new Var after SCI-1. The test-world
interim in `seon.test/clean-request-handle` can go once the agent's live base is shown to hold the same promotions
(it does after this change for functions; file tests are interpreted at resolve in any context).

Measured (agent `affected-callers`, default `6e9b9017c`, emulated live base over branch `ac-helper`): promoting the
313 callers and recording the 7 tests of `seon.error/collection-member-problem` took 380 ms and allocated about
1.1 GB; repeating it on the promoted context took 63 ms (closure read only); the compiled caller answered `[:a 1]`
before and the branch's `{:a 1}` after, and a later redefinition reached it with no re-analysis. In the check JVM
the redefining eval request (`seon.render.ns/first-doc-line`, 65 callers) took 2.9 s, of which the test step
1.16 s and the defn's own evaluation (arm-time promotion) 355 ms / 425 MB.
