---
type: research
status: incomplete
date: 2026-09-09
tags: [research, runtime, sci]
---

# Turn rename: bounded continuation

## Recovery checkpoint, 2026-09-09 06:54 UTC

Entering HEAD: `66b296e67`. The tracked tree was clean; inherited untracked
`build/`, `workers/`, and `config/virtual-turns.edn` were preserved. Default
was alive at PID 87173 and MCP health answered. No other lane was operated.

Read AGENTS.md (including the embedded PRD §10 lane rules), the previous
`turn-cut-landing-2026-09-08.md` handoff end to end, and turn PRD §4/§4a,
§10, §12, §14–§16 end to end. Also read §0, §13, the plan README and
working edge. Applied the Clojure, testing, REPL, Datahike, data-modeling,
and flow skills. Inspected the stash's custody diff and the unmerged
candidate inventory; neither was applied wholesale.

This is a small first part of custody removal, not completion of slice 1.
Boot recovery now closes every open turn, including a saved holder named
in an incoming live-process set and system-generated source. It interrupts
unfinished evaluations/effects while preserving terminal evaluations.
The boot caller no longer supplies a process roster. Claim/release and the
legacy run interruption stamp still exist and are explicitly pending.

Dependency ledger: Datahike invokes `:db.fn/call` against its current
transaction database (`reference-code/datahike/src/datahike/db/transaction.cljc:1152`).
The existing `run/recover-call` and cluster boot recovery caller use that
mechanism. The immutable live probe uses the dependency's `with`, declared
referentially transparent in `reference-code/datahike/src/datahike/api/specification.cljc:466`.
The ordinary-proc regression uses the canonical `with-database` fixture,
real SCI, work launcher, agent graph, armed contracts and bounded events.

Verification:

- Entering-HEAD fast baseline: 21 tests / 154 assertions, zero failures/errors.
- Changed recovery fast test: 21 / 154; ordinary-proc fast test: 2 / 106,
  zero failures/errors. The proc test invokes the actual boot recovery
  function on an open turn with no evaluations, then successfully executes
  virtual turns for that agent. It does not substitute the evaluator.
- Final owned-path gate: **23 tests / 262 assertions, zero failures/errors**.
  Paths: `src/seon/cluster/run.clj`, `src/seon/cluster.clj`,
  `test/seon/cluster/run_test.clj`, `test/seon/turn_test.clj`.
  Namespaces: `seon.cluster.run-test seon.turn-test`.
  Log: `tmp/turn-rename-recovery-final-gate.log`; successful root removed
  by the runner. All runs set `SEON_TEST_WORKERS=3`.
- The fixed-seed recovery property varies generated/source state and
  saved holder, requires the expected evaluation count, preserves complete
  terminal entities, requires exact closing time, and verifies repeat
  recovery produces no operations. No literal-nil assertion was substituted.
- Live JVM probe: `turn_rename_recovery_probe_2026_09_09.clj` returned
  closed=true, interrupted=true, idempotent=true, default-unchanged=true,
  four operations. This exercises the changed live Var against an immutable
  copy of default's database, not a default restart or a scratch boot.
  At 06:54 UTC adoption was still converging: adopted
  `6aa1011d-e7c9-5989-8248-c076abcc4c60`, published
  `6aa10239-5ab7-5e05-b108-12ee4b30c9e7`. The loaded boot caller's arglist
  was `[connection]`. No completed-adoption or browser-paint claim yet.

No schema deletion in this checkpoint; no RESET NEEDED line applies yet.
No scratch cluster was seeded, no provider was invoked by this lane, and
default was never stopped, restarted, or reforked. The preliminary virtual
turn log counted four reports / 26 datoms, including a metadata-only report;
this is not the three-write proof and is not a deterministic count claim.

Remaining: finish custody/provenance and its consumers; rename to seon.turn;
prove three writes per virtual turn; route stable identities through seon.id.
Platform gate and final shell cleanup remain pending at this checkpoint.
