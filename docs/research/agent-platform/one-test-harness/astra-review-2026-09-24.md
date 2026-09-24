---
type: review
status: changes requested
created: 2026-09-24
scope: read-only review of one-test-harness/design-2026-09-24.md
---

# Astra design review — one test harness, 2026-09-24

**Verdict: revise before implementation.** The existing composition is `seon.test/run → agent acquisition →
body/report capture → release → evidence writer`. Keep it; make the agent attempt own preparation, custody,
resources and program validity. The harness should supply a deftest body and consume its outcome, not
implement another world.

Read end to end: CLAUDE.md, the 411-line design, both complete census/survey notes, clojure-testing skill,
and the 258-line deftest/forks note. Also checked the relevant checkpoint ruling, testing predecessor,
Clojure/REPL/Flow/Datahike/provider skills, first-party callers and dependency source. Source citations
below name the reviewed tree at `9e6936f76a0fe6130f93865bc3ee2af45c7bd9a2`; `sci/eval.clj` had unrelated
in-flight edits, so its citations describe those observed bytes, not installed proof. Design source pin:
`d0d9dad21`; survey/census pin: `8bb0eea92` plus lane edits.

**Live limits first.** Read-only MCP `runtime_status` found PID 70372, start `2026-09-24T16:53:17Z`, alive
but faulted: five error signatures, including graph-stop acknowledgement failure, and four replaced roots
from `web_debug_test`. A concurrent publication diagnostic reported stale `current-src` head (6,689 ms).
Neither fault was repaired here. No JVM, branch, graph, test request or provider call was started. No
reload/adoption was attempted. These are design counterexamples, not claims that a proposed implementation
was executed.

## Ranked findings

### 1. P0 — A clean database seed still routes an agent into live default

Design §1 seeds root through `seed-root-agent!`, passing the original cluster name. That calls
`ensure-entity!` without a branch (`cluster.clj:3299`); `creation-tx` defaults it to
`registry/cluster-branch cluster-name` (`cluster/agent.clj:205`). For default this is
**`:cluster-default`**, not the seed or member branch. `arm!` calls `acquire-context! handle agent-id`
(`agent.clj:988`), which follows that row's branch (`:813–819`). Shared context state can return the
already-held live root connection (`:805`, `:829–833`, `:856`); without that handle it may instead refuse
creation of the existing branch (`:834–849`). Counterexample: seed, fork a member, arm its root with the
live root handle retained. Root can read/write live data although the test body's `(db/db)` is clean.
MCP independently read live root's branch as `:cluster-default`.

**Smallest fix:** make attempt acquisition/agent creation carry the actual receiving branch through the
canonical writer; never infer custody from the cluster label. Create attempt agents on that branch, rather
than retaining executable root routing in a shared checkpoint. Audit the inherited environment too:
`fork-cluster-ctx` replaces connection/projection but retains other supplied services (`sci/eval.clj:2856`).
The proof must arm an agent and observe its actual connection and writes; zero rows in the member before
arming is insufficient. Assert a live sentinel is absent from body, agent, sibling and error recorder, and
that live default is unchanged.

S4 must pass custody **into** `runner/run-var!`, not just wrap its caller: `runner.clj:365` rebinds exactly
the supplied custody, currently `{}` at `test.clj:160`; `db/call-with-custody` explicitly clears absent
custody (`db.clj:383`). Otherwise the mechanical sweep removes the only scope that supplied the connection.

### 2. P0 — Moving the adoption record closes only the successful-write gap

The move in owner change 6 is necessary: reload/arm ends inside the lock at `cluster.clj:2700`, while the
record is written at `:2704`. But reload, arming or that transaction can fail after Vars changed;
`call-with-program-change` always unlocks (`:2233`), and the outer cleanup only releases the materialized
database (`:2726`). The old record can then equal the old seed while compiled code is new or partially
unarmed. Recording that equality does **not** prove the program ran. The live replaced roots are a separate
concrete reason not to infer arming from a record.

Also, resolution occurs before the body lock (`test.clj:1509`, `:1533`): deletion and recreation of a Var
between those steps can leave the member holding an old Var. Seeds execute before the proposed body lock
too. Finally, a member's own adoption bypasses the write lock (`cluster.clj:2218`); a test calling adoption
is not excluded from concurrent readers merely because it already holds a read lock.

**Smallest fix:** the program/adoption owner must mark execution unavailable before mutating roots and
publish readiness only after exact bytes, arming and record agree; failure keeps affected execution
unavailable. Acquire validity and resolve the body under the same exclusion; apply it to seed execution and
ordinary agent evaluation. Refuse seed/program mismatch before running, not after possible effects. Keep
loaded commit as provenance, but do not delete the existing validity checks until these failure paths are
covered. Probe successful inter-member adoption, partial reload, arming failure, record refusal, and a
replaced/deleted test Var independently.

### 3. P1 — Seed reuse lacks both an input identity and an atomic ready state

Design §2 keys checkpoints by loaded commit, cluster and seed digests. Its pure seed functions may read EDN
files, and boot seeding receives compiled config and process identity (`boot.clj:56–74`). A file or manifest
can change without any seed function digest changing. The key then serves old data. Function references
returned inside config also need their definitions in reach; storing a test→seed ref alone does not do that.
`runner/reach-facts` follows a fixed set of calls/references/schema tokens (`runner.clj:675`, `:749`), not
arbitrary new seed refs or runtime symbol values.

Counterexample two: `registry/branch!` publishes the branch immediately and returns `created? false` to a
loser (`registry.clj:212–229`). A second request can find the named seed while the winner is still applying
its facts, or after setup failed. Existence is not a fully prepared checkpoint. Native `d/branch!` copies
stored roots; it does not atomically run Seon's subsequent seed transactions
(`reference-code/datahike/src/datahike/versioning.cljc:247–274`, pin `620721d674`).

**Smallest fix:** preparation belongs to the agent checkpoint owner. Include declared external/config inputs
and ordered seed dependency closure in its content identity; apply every seed to the previous seed's
returned database through checked writers. Use the existing serialized branch/publication ownership to
expose only a complete checkpoint, retaining its GC root until dependants have acquired their branches. Do
not add a test-owned ready cache. Extend the existing graph walk and its invalidation inputs with the
declared seed edges. Test changed seed helper, external seed bytes, seed order, refused seed write,
concurrent first preparation and old-seed retirement. Seeds that change program rows must enter ordinary
program acquisition, never leave compiled bodies running different code under an unchanged loaded-commit
label.

### 4. P1 — Graph release is correctly placed, but S1 does not capture every owner

`release-context!` currently tries connection release, unlink and state removal even after an earlier
failure (`agent.clj:765–780`). Prepending disarm to that list would still close the database after a failed
join. Disarm's existing stop/join is the right primitive (`:1179–1201`); Flow stop alone only sends stop and
closes observation channels (`core.async .../flow/impl.clj:174–183`, pin `dc35f3e0d7`).

Adding a routing field to a handle does not capture graphs still armed using the tests' independent routing
atoms. `arm!` accepts routing separately (`agent.clj:963`). Likewise merely making `loop-handle` public
leaves its nine resource arguments and fresh context-state atom (`cluster.clj:3431–3439`); tests still
construct another world.

**Smallest fix:** convert every arming caller to the attempt-owned routing in S1, serialize release against
new arms through that same owner, attempt all graph stops, and close/unlink only after all owned execution
has exited. Failed join retains connection, routing and custody for diagnosis/release. The agent path must
supply the loop handle and owned channels/launcher, rather than having tests assemble them. Include setup
failure, two graphs with one refusing exit, concurrent arm/release and nested agent acquisition in the
probe. Record cleanup before final green evidence: today release happens in `bounded-result` before results
return (`test.clj:181–187`), contrary to the design's opening “record, then release” account.

### 5. P1 — Batch refusal fix breaks its declared result type and readiness caller

The diagnosis at `runner.clj:1756–1765` is correct. The proposed fix is not six lines: `:seon.test/results`
is a vector of count-bearing results (`seon.test.edn:58–81`). A typed unknown with no counts violates it.
`problems/failed-tests` then calls `pos?` on those missing counts (`problems.clj:385–387`), producing an
NPE. The exact scalar counterexample was confirmed over MCP. Filling counts with zero would hide unknown as
health, and falling back to an older green would be unsound.

**Smallest fix:** add a named per-member unknown alternative, preserving symbol and run identity, and update
the result reader's contract and all consuming renderers, readiness and reuse decisions in the same slice.
Keep one query per distinct run. Missing execution may remain absent under the existing contract; do not
demand 2,163 output entries without deliberately changing that contract too. Probe a green, a red and an
incomplete member in the **same run**, plus a never-run symbol; good results remain visible, unknown remains
visible and cannot reuse, query failure still refuses the entire read. Use Malli's existing
`validate`/`explain` (`reference-code/malli/src/malli/core.cljc:2645`, `:2670`, pin `befcd302a8`).

### 6. P1 — Platform reach and a rebinding ban do not prove ordinary confinement

The survey explicitly inspected names/docstrings and one local-helper level, not every body; its final
limits admit missed cross-namespace helpers. “230 ± exceptions” is therefore an inventory comparison, not
the acceptance criterion for S7. Stored `:seon.fn/calls` is a set of symbols (`seon.fn.edn:49`): it cannot
alone identify the target namespace and source line of a computed `with-redefs-fn` map. The census needed a
separate syntax walk and manual resolution of 58 targets. The own-namespace exception is unsafe for a shared
compiled test namespace: two attempts can still mutate the same Var. SCI fork isolates bindings, not
arbitrary inherited atoms (`sci/core.cljc:345`; checkpoint ruling §4b already records this).

**Smallest fix:** derive known platform reach through the existing graph, including seed/setup/fixture and
declared dispatch edges; retain explicit justified platform markers for dependencies and unresolved dynamic
host work. Apply host mutation policy at the agent admission boundary, shared by test attempts, using actual
analyzer/source evidence; unknown confinement is platform/refused. Allow genuinely private SCI/throwaway
state, not “same namespace” as a substitute for ownership. Keep the diagnostic check until this boundary is
proven. Then remove it from ordinary execution; platform drift must halt admission of later members if roots
remain damaged. A disposable JVM is not protection for its next test.

### 7. P1 — Dropping Var-keyed capabilities is right; effects still lack an authorable route

“Everything else is facts” (§2b) excludes real process-local environment inputs. `seon.effect/request*`
requires a current evaluation (`effect.clj:708`), obtains a qualified handler from the owner row, then uses
JVM `requiring-resolve` (`:734–741`). An agent's admitted SCI-only stub therefore cannot substitute that
handler today. Running a deftest with only connection custody also supplies no effect evaluation identity.
The proposed AI transport addresses neither gap for web/filesystem effects.

**Smallest fix:** have the ordinary agent attempt establish the evaluation/environment and resolve declared
handler symbols through its context. Keep external substitutions as domain capability values, using the
existing `:seon.effect/capability` declaration and dispatcher where applicable; do not invent a test
override registry. Declare any additional domain configuration only where that production owner needs it.
Show one complete agent-authored effect stub, its seed/config installation, request, receipt, bounded
failure and unchanged sibling through the real path. No function bodies in metadata or EDN. A Var-keyed
global replacement map remains unnecessary.

### 8. P2 — The six owner changes are not six necessary small changes

| Change | Evidence and smallest disposition |
|---|---|
| 1. Schema resource root | `schema/edn.clj:198`, `:208`, `:332`, `:403` already take resource names. The remaining ambient boundary is `io/resource` and packaged population/stamp (`:361`, `:384`). Use existing explicit resources for those tests first; if filesystem fixtures need it, pass the resolved resource/location at the I/O boundary, including cache identity. Do not thread a redundant root through every function. |
| 2. Analyzer directory | **Delete production change.** `analyze` accepts `::cache-root` (`fn/analyzer.clj:589`, `:604`) and passes it as `:cache-dir` (`:636`); confirmed in installed Var metadata. Convert the test to this input while preserving its poisoned-cache and symlink assertions (`test/seon/fn/analyzer_test.clj:45–79`). |
| 3. Feed backstop | **Delete proposed new dial/read.** `feed` already accepts `:seon.config.eval/time-limit-ms` in service data (`render/web.clj:2868`) and passes it to the wait (`:2908`). Supply that existing service/config input; prove the same first-paint/refusal/close behavior. |
| 4. Prompt input | Reasonable separation, but retain one public acquisition composition. `prompt` already delegates to `acquire-context-report` (`cluster/prompt.clj:457`). Expose/accept the rendered value at its consuming selection/pricing seam, not a second prompt implementation; convert the three actual callers. |
| 5. Function provider | Useful agent feature, **not proven at +15 lines**. `ai/complete` takes projection/request (`ai.clj:1474`), no ctx; `kernel/invoke` needs ctx, database, bounds/caps and returns an invocation envelope (`sci/kernel.clj:585`). Specify carriage, request/reply schemas, dispatch edges, error/stream behavior and default refusal before sweeping 77 stubs. Use the existing loopback provider for an initial proof; keep HTTP-leaf tests. |
| 6. Adoption record | Move it inside the lock, with finding 2's failure/validity handling and per-attempt provenance. This serves every agent, not only test members. |

The credential rewrite is acceptable only for presence/absence leaf tests. Selecting an arbitrary real
environment variable cannot supply an exact deterministic header or secret value. Use no-auth loopback where
authentication is irrelevant; preserve the precise credential behavior with an explicit environment input at
its owner.

### 9. P2 — The REPL example needs a real namespace and schema contract

Keep plain deftest plus an ordered vector of qualified seed symbols. But the example uses `:seon.test/seed`
for both that vector and the seed function's returned map: give the returned value its own schema name.
Preserve seed order through storage; cardinality-many refs alone do not preserve vector order. Include the
7.9b admission validation and test→seed→written-schema edges in S2b, not just `callers` output.

Aliasing `run-tests` to `my.test/run` is not enough: `run-owned` returns only a vector
(`src/my/test.clj:28–40`), and `owned-symbols` selects the **assigned agent namespace** (`test.clj:1331`),
not necessarily the authored `my.workout-test` namespace. Delete that duplicate selector by passing
explicit/current namespace to the existing request; return its full run result. Probe from a different
assigned namespace, including a seed refusal and a real red assertion with the authored source line. Do not
import the predecessor's task EDN strings containing deftest bodies: task metadata names qualified test
symbols. Any transient source entrance for pre-entry checking must use the same candidate program/attempt
and remain separate from durable metadata; S13 still needs its stated entry-before-install review.

## Open question, migration and deletion ruling

**Choose base plus the agent branch's differing program rows, including retractions and affected callers.**
The same agent-path preparation must serve core and SCI tests. Do not fork an agent's operational branch by
default: a test asking “no errors?” otherwise changes outcome with yesterday's faults, while the proposed
reach/input key remains equal. Do not drop its differing program rows either: that tests the wrong
candidate. Explicit data needed by a test comes through its seeds. The source branch commit remains
provenance for the overlay; it is not the test world identity. This follows the already-reviewed checkpoint
ruling (§4b), not a new harness layer.

Keep the ordering of stop-before-seed and confinement-before-drift-deletion, with these corrections: S0
includes schemas/readiness; S1 includes every arming caller; program validity (S3) precedes seed
execution/reuse; agent-owned clean preparation and one core/SCI authoring proof precede S5; function
transport's proof precedes its sweep. Move the independent 13 deletions forward. Do the 152-body turn
subsumption review before converting those bodies. No extra JVM is needed for ordinary work; platform
process/bootstrap subjects remain the explicit orchestrator-owned exception, through the same interface,
with no second worker pool.

S5's 219 files are a mechanical campaign, not one loadable slice: script once, then integrate by disjoint
namespace groups with actual pass/no-new-failure-class evidence. “Reds attributed” is insufficient for
changed behavior. S2b/S3/S7 estimates omit schema, graph, reader and caller changes above; reprice them
before implementation. Separate body cost from harness overhead, measure first preparation and warm reuse,
and retain raw timing/allocation evidence. The earlier 3,319 ms includes admission and recording that the
130 ms private-fork experiment explicitly did not exercise.

Delete duplicate acquisition/handle constructors once callers use the agent owner; delete retired guards,
informational-only tests and genuinely subsumed spies. Keep observable cache-cost regressions and
release/isolation behaviors even when their old helper disappears. Do not convert needless tests just to
preserve counts. Delete new owner changes 2 and 3; avoid a second capability registry, test seed cache, test
lifecycle executor or task-proof metadata language.

## Read-only evidence and landing limits

MCP JVM forms used explicit root `/Users/sean/src/seon`, cluster `default`, namespace `user`, `read_only
true`; no defs/requires. Probe 1 read `(seon.db/db (seon.cluster.boot/connection "default"))`, queried
`:seon.source/commit-id` and test count, pulled the three proposed schema keys, and read Runtime heap usage:
loaded record `6ab565d6-d776-5583-addd-6151424aa41b`, 2,163 tests, all three keys absent; 3.34425 ms body /
9 ms PREPL, 10,261,446,464 bytes instantaneous heap usage. Probe 2 pulled root id/branch, read
`(:malli/schema (meta #'seon.fn.analyzer/analyze))`, and caught `(pos? (:seon.test/fail-count
{:seon.test/population-unknown :completed-member}))`: root `:cluster-default`, cache-root declared,
`NullPointerException`; 0.658542 ms / 7 ms. Heap is a shared-process observation, not review allocation. No
performance improvement, runtime installation, test pass or clean default is claimed. Only this review file
was added; source/test line delta is zero. Unrelated lane edits were preserved. The edit hook also reported
stale gitlink citations in unrelated historical landing notes; those are outside this file's authorized
scope. Its missing-frontmatter finding on this review was corrected.
