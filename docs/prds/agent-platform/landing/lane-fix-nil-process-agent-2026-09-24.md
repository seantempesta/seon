---
type: landing
status: partial
lane: fix-nil-process-agent
created: 2026-09-24
report: docs/research/agent-platform/ambiguous-nil-producers-2026-09-24.md
---

# Lane fix-nil-process-agent: unknown generations refuse; failed reads never look unowned (2026-09-24)

Two census rows (§9, "nil means one thing"), fixed at their producers.
`process-start-instant` returns nil only for "no live process with that pid", and
`assigned-to` returns `[]` only for "nobody is assigned".

## Broken or unproven first

- **The live JVM does not run this code.** Default reports `hook-publication :off`
  because its program is the committed archive `data/source/ce73846…`.
  `bin/test-check default --ns seon.cluster.process-test` refused with "The requested
  namespace has no eligible tests". Adoption belongs to root through publication.
- **The `assigned-to` regression has not run.** `seon.cluster.agent-namespace-test/
  assigned-to-refuses-an-unreadable-database-and-answers-none-only-when-none` uses
  `with-database`, which needs a `seon.test/run` member. Default cannot select the
  test because its program predates it, and the lane started no second system.
  The behaviour was proven instead by evaluating the edited form, read from disk,
  in a throwaway namespace `scratch.fix-nil-process-agent` in default (since
  removed; no default Var redefined). See the evidence below.
- **The unknown-instant refusal cannot be provoked on this host.** macOS supplied
  `startInstant` for every live pid probed, including 0 (kernel), 1 (launchd) and a
  zombie grandchild. The refusal branch is exercised only by reading the code. No
  test forces it, because that would need Var redefinition.
- **Flaky neighbour test, now fixed in the fixture (resumed slice).**
  `a-well-behaved-tree-exits-on-term-without-force` lost its TERM-trap marker because
  the JDK closes the child's stdout/stderr pipes when the root exits. The trapping shell's
  "Terminated: 15" line then got SIGPIPE. The owner was not at fault. The same property
  holds in production for `seon.shell.jvm` and `run-process!`, and needs an owner ruling.
  See `docs/seon/issues/process-test-term-trap-marker-is-intermittently-absent.md`.
- **Callers left as they are, out of ownership.** `script/seon/dev/state.clj:7-8`
  aliased `process-start-instant` and `process-identity-alive?` with no caller. The aliases
  were deleted in the resumed slice. `seon.cluster/read-advertisement`
  (`cluster.clj` ~3700) wraps `live?` in `(catch Throwable _ nil)`, which turns the new
  refusal back into "no advertisement". That is the same class, in another owner's file.
  `web.clj` needs no change: the throw propagates through `(first …)`.
- **Contracts compiled but not armed in the proof.** Every new or changed `:malli/schema`
  compiled against the packaged projection in default. The snapshot runs used
  plain `clojure.test`, which does not arm contracts.

## Behaviour changes

- `process-start-instant`: the `(catch Throwable _ nil)` is gone. Platform failures
  propagate whole. For a live process with no instant, the new private
  `start-instant-unavailable!` refuses with the existing declared
  `:seon.cluster.process/start-instant-unavailable` (the same shape `current-identity`
  used; `current-identity` now shares it). A process that exits between the liveness
  check and the info read returns nil (`handle-start-instant`).
- `subprocess-identity` / `subprocess-tree-identities`: a member that has exited is
  absent. It is never recorded as `{pid, start-instant nil}`. Every identity contract in
  the file is now `:seon.cluster.process/identity`, which requires the instant. So
  `matching-process-handle`, `same-subprocess-handle` and `terminate-subprocess!` can no
  longer receive an unknown generation, and `(= nil nil)` can no longer match a reused
  pid.
- `live?` derives from `process-start-instant`. Before, a live process with no instant
  read as dead. Now it refuses.
- `run-process!` timeout refusal: `:seon.operator.subprocess/start-instant` is now the
  root's own instant, and is absent when the root was not captured. Before, it was
  `(first identities)`'s instant and could be nil.
- `assigned-to`: an invalid read throws `ex-info` carrying the invalid-read value. This
  uses the same pattern `agent.clj` already uses for the steward read at ~:155.
  `ensure-namespace-owner!` therefore cannot create an owner on a failed read. The
  page paths (`web.clj` ~3416/3421) fail loudly instead of rendering "unowned".

## Evidence

| probe | before (HEAD) | after |
|---|---|---|
| tree identities of an exited `/usr/bin/true` root (live default JVM / snapshot test) | `[{:pid 36303 :start-instant nil}]` | `[]` |
| new `an-exited-or-unknown-generation-never-matches-a-process` in snapshot | red: `[#:seon.boot{:pid 37043, :start-instant nil}]` (2/2 runs) | green (4/4 runs) |
| `assigned-to` on an invalid-read database (live default, scratch ns) | `[]` | throws, ex-data `{:seon.db/invalid-read true :seon.db/refused-read-operation seon.db/q}` |
| `assigned-to` owned / none | — | `["root"]` for `my.agents.root`; `[]` for `my.agents.nobody` |
| `assigned-to` call time (live, one call) | 0.279 ms (armed) | 0.437 ms (unarmed scratch, single cold call) |
| `process-start-instant` per call, snapshot unarmed, 20 000 calls | 437 µs | 411–568 µs (new, then reloaded new) |
| `subprocess-tree-identities` of self, 2 000 calls | 1935 µs | 1348–2581 µs |
| `live?` per call | 749 µs | 449–573 µs |

The microbenchmarks are noisy and depend on run order. The reloaded-new run was at or
below HEAD on all three probes. No regression was found beyond that noise. Snapshot:
`git archive` of HEAD plus the four lane files, `reference-code` symlinked,
`clojure -M:test`. `seon.cluster.process-test` ran 3 tests with 19 assertions in about 2.2 s
of wall time. `seon.shell.jvm`, `seon.cluster`, `seon.dev.state`, `seon.render.web` and
`seon.cluster.agent-namespace-test` load in the snapshot.

## Changes and size

- `src/seon/cluster/process.clj`: +73 / −62 (net +11)
- `test/seon/cluster/process_test.clj`: +27
- `src/seon/cluster/agent.clj`: +6 / −3 (net +3)
- `test/seon/cluster/agent_namespace_test.clj`: +21
- Commits: `d0ca6de68` (process, with the flake issue), `8adf64a56` (agent).

## Resumed slice (root re-grant)

- `steward-of` (census S3): an invalid read now throws the invalid read. Before, the armed
  output contract refused with the misleading "expected a string, got a map" (live default,
  scratch namespace). After: ex-data `{:seon.db/invalid-read true}`; `"root"` for
  `my.agents.root`; nil for `my.agents.nobody`. Its regression assertions joined the
  renamed `assignment-reads-refuse-an-unreadable-database-and-answer-none-only-when-none`.
  Commit `b7d4582cf` (agent.clj +12/−8, test +8/−2).
- Flake: the fixture's output goes to `/dev/null`. In a snapshot the test was green 15/15;
  before, it failed about 1 run in 3, and a direct owner probe wrote the marker 0/10 times.
  An owner reorder (root signalled last) was tried: 10/10 in one probe, 7/8 in another,
  because the root can also exit on its own. It was reverted, and the owner is unchanged.
  Commit `1ad298a5a` (test +8/−2).
- `script/seon/dev/state.clj`: the two aliases and the now-unused `seon.cluster.process`
  require were deleted. rg found no caller in src/, test/, script/ or bin/, and dependents
  load. Commit `f053f9477` (−4/+1).

No reset is needed. Adoption into default is root's.

## Installed proof after root's restart (default at archive 3ff123708)

Archive 3ff123708 contains `d0ca6de68`, `8adf64a56`, `f053f9477` and `b7d4582cf`. It
does not contain the fixture fix `1ad298a5a`.

- `bin/test-check default --test seon.cluster.process-test/an-exited-or-unknown-generation-never-matches-a-process`:
  passed with 9 assertions (reused recorded green).
- `bin/test-check default --test seon.cluster.agent-namespace-test/assignment-reads-refuse-an-unreadable-database-and-answer-none-only-when-none`:
  passed with 7 assertions (reused recorded green).
- `bin/test-check default --ns seon.cluster.process-test`: 18 pass, 1 fail. The failure is
  `a-well-behaved-tree-exits-on-term-without-force` at :56, the old fixture that
  `1ad298a5a` fixes. That commit is not installed yet.
- `bin/test-check default --ns seon.cluster.agent-namespace-test`: 13 pass, 2 errors, from
  `reassignment-is-an-ordinary-cardinality-one-transaction` and
  `source-bearing-namespaces-without-a-steward-derive-one-problem-line`. Both
  fixture writes of bare `{:seon.ns/name …}` rows were refused for a missing
  `:seon.program/definition-digest`. This is the known class in
  `docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md`.
  It is not caused by this lane.
- `bin/seon status` itself failed during the check: `seon.cluster.boot/readable-response`
  refused its return value for a missing `:seon.error/at`. That is root's to diagnose.
