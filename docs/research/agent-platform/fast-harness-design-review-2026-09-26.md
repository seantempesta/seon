---
type: design-review
status: complete
created: 2026-09-26
verdict: REVISE
reviewed-commit: 62cd03ebd35bc5ba5950efcb5c0ef123bedb1ad2
source-pin: 123b0d5c93a816ce790075bc02dda72d25fde24f
---

# Fast harness design review

**Verdict: REVISE.** Keep the existing `seon.test/run` pipeline, acquire a request's
program once, and reuse immutable prepared worlds through Datahike branches. The
measured acquisition cause is credible, but the proposed same-branch acquisition can
mutate the caller's SCI context. The seed key, clean-input proof, outcome rules and
conversion order also need correction before implementation.

Reviewed [Opus's design](fast-harness-design-2026-09-25.md) at `62cd03ebd`, unchanged
in the inspected checkout. Source citations below are at `123b0d5c9`, unless a
dependency pin is named. This is a static review: no tests, runtime probes, source
edits, publication or adoption. The design's timings and reported faulted default are
author evidence, not independently repeated measurements. Concurrent source edits
were left alone; they do not obstruct this review.

The governing owner rules are: “slow means poorly designed since we know we can spin
up envs and execute them faster”; “Make sure it's easy for us to write tests the same
way it'll be easy for agents to write tests”; and “No duplicate systems. We need the
same capabilities of setup and fixtures.” AGENTS.md additionally requires positive
current proof, explicit worlds, actual exit before release, content-valid reuse,
sub-second ordinary operations, and measured justification for slower work.

## Required revisions

### 1. S1: acquire once, but first give the request its own SCI context

**Priority: blocking correctness.** The cause is supported: `clean-request-handle`
carries the caller's context without acquiring the request's program
(`src/seon/test.clj:1792`); `member-result` then acquires each isolated member
(`:1883`). The reported 325–581 ms versus 28.8–30.4 ms prototype is useful evidence
for moving shared acquisition out of that loop.

However, “`acquire-context!` on its own branch” is insufficient and unsafe as written.
The request's `:seon.sci.eval/base-ctx` and `:seon.sci.eval/ctx` are the caller's same
objects. `acquire-context!` derives `isolated?` from branch equality, not the handle's
`:isolated` label (`src/seon/cluster/agent.clj:922`). For that same branch it selects
`source-ctx` directly (`:974`), calls `acquire!` (`:987`), and replaces its environment
(`:991`). Acquisition resets the context's environment, installed functions and
snapshot (`src/seon/sci/eval.clj:3338`). Thus the seemingly small optimization can
install the request overlay or routing into its parent. Even an acquisition cache hit
does not make the subsequent environment replacement harmless.

Revise S1 to use the existing branch-context fork owner before acquisition, with the
request connection, projection and environment explicitly supplied. That seam already
exists at `sci.eval/fork-cluster-ctx` (`:3424`, fresh environment and snapshot at
`:3457`). Preserve the original request's connection/branch ownership and cleanup:
the same-branch acquisition's returned handle does not automatically inherit those
ownership flags (`src/seon/cluster/agent.clj:1021`). Do not add another context cache.

Acceptance must include a differing program overlay, a private definition, sibling
members and a nested request: parent values, custody, routing and instrumentation
remain unchanged; siblings cannot observe each other's changes; failures release the
request exactly once; an unfinished body retains its member branch until actual exit.
Repeat the performance probe through the corrected path. The design acknowledges an
isolation proof is owed; these are concrete reasons its current +6/−3 estimate cannot
yet be accepted.

### 2. Prepared worlds: retain the checkpoint key; revise S6 and S7's reuse contract

The existing checkpoint is a sound starting point: its key includes published base,
cluster, effective configuration and process (`src/seon/test.clj:1747`), its creator
prepares it once, and readiness requires its source record (`:1688`, `:1751`).
Datahike `branch!` reuses the selected stored roots and publishes the roster pointer
last (`reference-code/datahike/src/datahike/versioning.cljc:251`, `:268`, pin
`4157916b5a1b8d80d7f809c889bce1b098b1d75d`). No memory-store cloning or extra cache
is warranted. This is reuse within that process and base, not universal persistence
across restarts.

S6's `(id/digest [request-commit seed-definition-digests])` does not establish its
claimed cross-request reuse. An overlay is written on a newly created request branch
(`src/seon/test.clj:1764`, `:1775`); the proposal does not establish a stable commit
identity for equal overlay contents on different requests. It also fails to specify
literal seed data, seed ordering, namespace identity, transitive seed dependencies,
or external inputs. Existing seeds include literal transactions, named functions and
inline functions (`src/seon/fn.clj:527`); “seed-definition-digests” alone is underspecified.

Use the existing checkpoint plus canonical overlay/seed input evidence, or demonstrate
why the captured commit is stable for the supported case. Persist seed declarations
through the same schema/analyzer path as member seeds; JVM namespace metadata alone
cannot be the authority for agent-authored namespaces. Cache only prepared database
facts. Mutable objects, servers, graph ownership and per-member effects need their
ordinary scoped lifetimes. Specify partial-preparation refusal, publication of readiness,
retirement and safe rooting while callers branch. In particular, S7 cannot delete the
request branch without replacing its role of rooting the captured checkpoint during
use. Datahike deletion removes a roster root and leaves it accessible only until GC
(`versioning.cljc:279`); holding a commit identifier is not retention.

S7 must also resolve its contradictory “program identity” versus “commit-keyed memo”
wording. A new receipt commit must not rebuild gate sets. Define the actual read inputs,
invalidation event and cache-hit/miss proof, using the existing derivation owner.

### 3. S3: prove captured inputs, not a separate working-tree observation

**Priority: blocking evidence correctness.** `git diff --quiet HEAD -- <input roots>`
does not enumerate untracked source files. It also observes files at a different
instant from capture: dirty bytes can be captured, restored, then reported clean.
A boolean cannot explain `DIRTY INPUT: <paths>` or prove which Git tree was compared.

Use `seon.test.cache/input-roots` (`src/seon/test/cache.clj:129`), its tracked plus
nonignored-untracked inventory (`:32`), and the publication capture's held bytes and
digests (`src/seon/cluster/source.clj:107`, `:208`). Compare that exact inventory and
content against a captured Git tree identity, including deletions and dependency pins;
preserve missing/read-failed evidence as unknown. Do not widen the gate to documentation.
Revalidate the tested program/input identities at the admitting writer. Keep the
existing detect-don't-lock rule and retain both identities when adoption overlaps work.

Separate file-publication cleanliness from agent program evidence. Accepted branch rows
can legitimately precede file write-back; requiring their new program to exist in Git
before merge creates a cycle. Conversely, a clean loaded file base does not certify an
arbitrary overlay. Name the loaded source, tested candidate and overlay/input evidence,
and define exactly which acceptance gate consumes each. Preserve diagnostic results
from dirty runs while making their ineligibility visible to every reuse/merge consumer.
Do not replace all evidence with an unexplained `:committed?` stamp.

Required cases include untracked code, capture followed by restoration, a docs-only
edit, dirty dependency inputs, an agent-only candidate, and adoption during execution.
These are future acceptance requirements; none was executed in this lane.

### 4. S2: a historical comparison is useful, but unknown is not red or absent

Reading a pre-admission snapshot is correct. `runner/latest-results` is already the
right reader, but explicitly returns unknown for an incomplete latest execution and
refuses a failed read (`src/seon/test/runner.clj:1857`). It selects native executions
on the database's branch (`:1867`), so a fresh lane branch does not automatically have
the root baseline. Name the baseline database/run and return “no baseline” when absent;
do not add a second baseline store or use historical evidence to certify the candidate.
The operation is batched, not literally one query: it also reads member evidence.

Extend the transition table for absent, unknown, void and reused results. A first observed
red may be labelled `:new-red` for display only if its absent baseline is explicit; it
does not prove a newly introduced regression. Equal reach digests establish equal
recorded code/schema reach, not equal external inputs or the same failure. The reach
owner hashes declaration/schema leaves (`runner.clj:709`, `:834`). Remove the assertion
that unchanged reach proves the current failure predates the change. Record differing
failure evidence without claiming causation.

Keep `passed?` strict and define a total outcome table. In particular, the design's
“any executed red” rule conflicts with §6's “void → incomplete.” A discarded body result
cannot be a trusted product red. Valid recorded reds, unknown/void members, exclusions,
admission/recording refusals, unfinished work, reuse and empty selection must all have
specified outcomes. For a valid red plus incomplete work, `:red` may take precedence
provided incompleteness remains explicit. No valid red plus any unmet obligation must
be `:incomplete`; zero members cannot silently become green. Propagate the 0/1/2 exit
contract through launcher consumers. The proposed two example requests do not cover
these evidence distinctions.

### 5. Enforcement: use the existing seams and state what each actually proves

The proposed kondo mechanism exists. Filename groups are implemented by
`ns-groups-eduction` (`reference-code/clj-kondo/src/clj_kondo/impl/config.clj:524`),
and `lint-discouraged-var!` supports resolved Var rules, severity and call/value
positions (`impl/namespace.clj:714`, pin `57252e07975710aa579b24f0d1b2b1e04195caa2`).
Configure the group through `:config-in-ns`; account for the analyzer's absolute mirror
paths (`src/seon/fn/analyzer.clj:80`). Demonstrate both direct-file and publication
analysis. Strings need no special exclusion rule: they are not calls.

A rule scoped to `test/` is file hygiene, not the entire agent admission guarantee.
Specify how the existing SCI gate refuses the corresponding operations for fileless
agent tests, and preserve real instrumentation-subject tests through the canonical
owner. An isolated platform designation alone does not turn a prohibited call into an
allowed one under an unconditional lint rule.

The writer refusal for absent analysis evidence belongs beside the existing final-row
digest validation (`src/seon/db.clj:4270`, `:4321`). Require the canonical value's
analysis digest for surviving function/test declarations, including removal of that
attribute; allow legitimate full retraction and same-transaction repair. Check attempted
as well as effective changes where needed. A mere nonempty digest does not prove an
invented row was analyzed or its dependency facts are current. Retain the existing
definition validation and name the analysis producer/consumer linkage. Sweep canonical
producers before enforcing; a writer refusal is not a substitute for their conversion.

### 6. The conversion inventory is useful; the proposed mechanical replacements are not all sound

Treat 267 `with-redefs`, 54 `with-redefs-fn`, 18 `alter-var-root`, 57 provider stand-ins
and the smaller class totals as author inventory estimates, not a reconciled deletion
manifest. The classes overlap and do not enumerate every site. Record each actual call
site's surviving behavior, owner, replacement and deletion, using resolved analysis
rather than a textual occurrence count. Retire tests of deleted machinery.

Two concrete corrections:

- **Provider fixtures already exist.** `test/seon/cluster/turn_test.clj:362` implements
  `with-provider!`, a local port-zero HTTP peer, real `ai/complete`, checked config and
  cleanup. It uses `:seon.config.ai/endpoint` and `:seon.config.ai/no-auth` (`:392`),
  rather than writing the request attribute `:seon.ai/endpoint` into config. There is
  also an HTTP stream fixture at `test/seon/ai_test.clj:1083`. Promote/consolidate the
  existing capability before proposing another http-kit server. Scripted replies must
  preserve ordered multi-turn behavior, streaming termination, failures, cancellation,
  usage and scoped cleanup where those are the test subject. No-auth suffices for many
  turn tests, but cannot replace credential-specific assertions indiscriminately. Q1's
  keep-redefinitions option conflicts with the owner's ban; a product test-mode dial
  conflicts with the one-real-path requirement.
- **Global profiling is not isolated call-count evidence.** `seon.profile/explain`
  explicitly includes concurrent JVM work (`src/seon/profile.clj:204`), and unarmed
  dependency calls have no cells. It cannot mechanically replace spies on `d/history`
  or exact counts of shared host Vars. Prefer assertions on effects/results and declared
  scoped evidence; use profiling as attribution only where its scope proves the claim.
  Do not serialize all tests to make global counters usable.

Similarly, an invalid transaction and a released-connection read are not interchangeable
with every injected operational failure. Retain each surviving failure class at its real
owner, with explicit external-effect inputs only where appropriate. Store-global tests
must be classified by what they mutate: uniquely named branches do not isolate the
store roster, blob collection or shutdown. Q2's shared store is acceptable only for
members proven independent of those global subjects.

### 7. One fixture system is the correct design, but callable names are not parity proof

The same `deftest` syntax, declaration producer and test request are the right surface.
Keep one builder population. The design proves name resolution and one simple test,
not setup/fixture equivalence. `with-database` depends on an executing member handle
(`test/seon/test_support.clj:265`), so resolving its Var in SCI says nothing about the
custody, seed, write-refusal, cleanup or timeout behavior an agent actually gets.

Make the planned file/SCI twin use the same canonical setup, a checked write, an
assertion over pulled data, and a fixture refusal. Compare stored run evidence and
cleanup, with real armed SCI. Include the namespace-seed path if S6 is retained.
Resolve Q4's differing analyzer inputs before claiming reusable proof across both ways;
equal definition digests alone do not settle input equivalence.

Promoting `define-agent-test!` is reasonable only after removing its incidental harness
policy: it currently creates a future and reads `config/defaults`
(`test/seon/test/one_request_test.clj:26`). The common helper must enter the production
evaluation/declaration path with explicit world and owned execution lifetime, not become
a second evaluator/settlement pipeline. Verify availability after fresh development
boot and adoption; the design's cited test-support classpath issue remains an explicit
proof obligation, even though a warm context resolves the helper. Host/platform
capabilities may have declared isolation, but root owns `bin/test --platform`; ordinary
agents submit the same declared test request and receive an explicit unmet obligation.

### 8. Keep the ≤50 ms/member and ≤300 ms/request targets; the present budget does not prove them

The prototype's acquisition plus resolve/release is approximately 38–48 ms, supporting
a ≤50 ms marginal harness target. It does not include isolated recording cost, seed
preparation, or the once-per-request acquisition. That acquisition alone measured
389 ms, already beyond the proposed 300 ms request allowance. The ≤300 ms target needs
a measured content-valid fast path; moving 389 ms outside a member loop is not that proof.

Define the budget as request overhead plus marginal per-member overhead plus body work,
with each phase assigned exactly once. Measure a fresh execution (not green reuse),
unchanged-world repeated requests, changed overlays, nested requests and increasing
member counts. Record selection, preparation/acquisition, admission, completion writes,
release, allocations/retained memory, and cache misses. Use the same probe on parent and
change; include sample counts and spread. S7's write batching must preserve durable
admission before body execution, reservations, actual exit and completion after the body.
Do not combine these lifetimes merely to remove a transaction.

The statement that all remaining work over five seconds is a product cost is premature:
recording is unmeasured, seed/resource setup still belongs to the harness, and nested
harness work remains inside bodies. Filing an issue alone does not satisfy AGENTS.md's
requirement to fix or justify operations over one second, with owner authorization and
an issue over ten seconds. A 15–50 s namespace projection is acceptable only as measured
work proportional to its members, not an ordinary-request exception. No memory evidence
or committed reproducible measurement script accompanies the current design.

### 9. Reorder the slices and make their deletions reviewable

S5 globally refuses provider redefinitions, yet S8 converts those sites last. That makes
the stated “each loads on its own” order false. The writer refusal also has no explicit
owner row in the slice table, despite touching the database boundary. Namespace seeds
need declaration/analyzer/schema ownership beyond `src/seon/test.clj`.

Revise the order around dependencies: corrected S1 first; corrected S2 independently;
measured S7; canonical fixture/provider capability and behavior-preserving conversions;
then their lint/writer enforcement in the same loadable cuts. S3 needs its captured-input
and gate contract settled first. Add S6 only with complete seed keying and demonstrated
repeated setup to delete. Do not globally enable an error while known remaining sites
make publication impossible, and do not exempt them to manufacture green.

The named deletions of duplicated admission helpers and whole-source re-analysis are
good. The table's unspecified “per class” and “repeated per-test setup” deletions are
not a bounded implementation budget, nor do test deletions automatically justify new
runtime machinery. Give actual retired paths and estimated source/test deltas per cut,
including provider consolidation, source capture/gates and seeded-world lifecycle.
Explain necessary growth before integration. Retain meaningful analyzer coverage when
removing the accretion test's whole-source analysis. “Independent of every other lane's
file” is not established by this design; ownership must be checked at launch.

## Review evidence and limits

Inspected the design, AGENTS.md, relevant Clojure/testing/REPL/Datahike skill instructions,
the acquisition and test owners, writer validation, input inventory, existing provider
fixtures, profiler, and the pinned dependency seams. SCI's `fork` guarantee is scoped
to new/redefined Vars (`reference-code/sci/src/sci/core.cljc:345`, pin
`409888f61fa1f3bbade8d7e3eecb9b8e09c8004a`); Seon's fork owner must separately carry
fresh custody and owned state. This supports the isolation correction without inventing
another fixture or interpreter system.

Only this review file was authored. Source/test line delta: **0/0**. Tests and live
measurements: **not run, as instructed**. Runtime timings and memory: **not measured**.
No installation, arming, performance or green-test claim is made by this review.
