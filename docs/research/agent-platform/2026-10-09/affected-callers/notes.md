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
- Tests: `install-selected!` already takes test rows; the closure's tests join them with their namespaces.
  `seon.test/resolve-test` resolves a test from the context when the context interprets it (`:agent`, or in
  `::interpreted`), else the host Var as today.
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
binding each member to a stub that interprets on first call). Later redefinitions: the closure query only.

## What SCI-3 then deletes

`stale-reach`, `reinterpret-reach!`, `reinterpret-inherited-callers!`, `inherited?` (callers already interpreted see
the redefinition through context bindings; compiled ones are promoted by `interpret-affected!`); the pre-copy
`doseq` in `install-selected!`; and the test-world interim in `seon.test/clean-request-handle` once the agent's live
base holds the same promotions (it then forks the live base again).
