---
type: research
status: active
date: 2026-09-09
tags: [research, agent, runtime, test]
---

# Loop proof: first bounded slice

**The end-to-end proof is red.** The ordinary loop does not implement the
full additive system-turn algorithm. This slice records one recurring
regression, a real scratch-JVM interruption proof, default HTTP observations,
and the independent repair of the Virtual turn control's missing routing.
It does not claim the requested loop implementation is complete.

Started approximately 10:48 UTC; 30-minute slice boundary 11:18 UTC.
Entering tracked tree was clean; inherited untracked `build/`, `workers/`,
and `config/virtual-turns.edn` were preserved. The orchestrator reforked
default during startup. This lane never stopped, restarted, or reforked it.
No foreign session was operated and no foreign source failure was used as a
reason to stop. The isolated proof snapshot names HEAD `70cea23f5`.

Read AGENTS.md, including its embedded PRD §10 rules; the entire turn PRD
(including requested §0, §3, §12, §14–§16); all 4,927 lines of `src/seon/turn.clj`;
and all 1,547 lines of `turn-rename-landing-2026-09-09.md` end to end.
Also read the plan README and working edge end to end. Applied the Clojure,
REPL, Datahike, testing, and Flow skills. No implementation was delegated.

## Dependency ledger

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:1152`
  applies each transaction function to the writer's current database.
  Gitlink `cdcb5792db8bd599487f099437265d18a31164a5`.
  First-party idioms: `seon.turn/system-run-call`, batch settlement,
  `recover-call`; canonical fixture `seon.test-support/with-database`.
- SCI `reference-code/sci/src/sci/core.cljc:260`, `:330`, `:345` owns
  interning, reusable contexts, and isolated forks.
  Gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`.
  First-party owners: `seon.sci.eval/fork-for-turn`, `bind-result!`, and
  `seon.cluster.agent/arm!`.
- Flow `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:168`
  defines lifecycle/transform arities and Var-driven step functions.
  Gitlink `dc35f3e0d7bc2eef502e77982f48641f025c8051`.
  First-party graph references `seon.turn/step`; the regression starts the
  actual graph with the canonical environment, projection executor, launcher,
  and bounded event waits. The evaluator is never replaced.
- Read evidence already belongs to `seon.db/read-evidence`,
  `read-evidence-current?`, and `read-evidence-changes`; `seon.turn/system-plan`
  uses those mechanisms. The missing operation is ordinary-loop integration.

## Recurring regression and exact bytes

New recurring surface: `test/seon/loop_proof_test.clj`,
`virtual-loop-end-to-end`. The test asserts wanted behavior; it is not
disabled, marked expected-failure, or rewritten to accept the current gaps.
It uses no provider and one canonical database fixture. The companion
existing `seon.turn-test` real-proc regression now enters through the actual
debug control owner and preserves its private-object/isolation assertions.

The byte digests below are SHA-256 of UTF-8 bytes, not hashes of `pr-str`
strings or character-count estimates. The recurring test prints the same
measurements; `loop_proof_probe_2026_09_09.clj` reproduces the live snapshots.

Isolated desired-behavior gate:

```text
SEON_TEST_WORKERS=3 bin/test --paths test/seon/loop_proof_test.clj -- seon.loop-proof-test
1 test / 47 assertions / 6 failures / 0 errors; exit 1
```

Log: `tmp/loop-proof-gate.log`; snapshot base digest
`5048af244e419a27bc90b939eac55336485692a0e204f2d7bc3e5fdb29d1178d`.
The first two fast iterations exposed mistakes in this new fixture's request
construction (missing turn id, then passing a pulled ref map instead of its
entity id). Those were corrected before behavioral results were collected.
The complete fast behavior run was 1 test / 36 assertions / 4 failures /
0 errors; the isolated regression adds ordinary-wake and recovery checks.

| Isolated stage | Bytes | SHA-256 |
|---|---:|---|
| Stored opening, 1 evaluation | 400 | `b303fdd554330753218376505756798aa60e03349f4966a95556e1ca5e80f87a` |
| Acquired provider prompt | 1429 | `a0e02c3a5535c477f5f9b875c5b9c1aa1260d138bb9f8663c38867bb4929e8fc` |
| Regenerated opening | 399 | `2d6918279b28739c8852f8f74ec2f3e74a2f97514aab333b9751ffe35c65aefd` |
| History after three-form virtual turn | 657 | `79f7113ede32a51bfd3ea9e09bc1e7af1247ae1aecd18caaadf2ac3f402a02d9` |

Passing boundaries:

- System control stores a nonempty opening as one turn's evaluations;
  unchanged regeneration makes no write. Two reads of saved evaluations and
  two prompt acquisitions are byte-identical individually.
- Three forms `(+ 1 1)`, `(+ 2 2)`, `(+ 3 3)` execute through the actual proc:
  **3 transactions**, **30 / 13 / 2 datoms**, **45 total**. Turn
  `ca32a4f57485`; shown values `2`, `4`, `6`; handles resolve to those actual
  values in the agent context and are absent from the base. Earlier history
  remains an exact string prefix.
- Explicit system refresh selects exactly one changed read,
  `(my.message/inbox {})`, and appends it. The old bytes remain a prefix.
- Canonical boot recovery closes one open intent, marks its evaluation
  interrupted, invents no side effect, and a second recovery writes nothing.

Falsified boundaries:

- The acquired prompt is 1,429 bytes while stored history is 400. The current
  neighborhood renderer contributes material outside saved evaluations.
- Compaction regenerates the same source but different bytes.
- The explicit message refresh has wake `:t=536870933`, answering `:t=0`;
  the wake remains unanswered.
- A second message through the ordinary graph produces only `(+ 1 1)`;
  the changed read is absent and both wakes remain unanswered.

Owners and acceptance are recorded in
`docs/seon/issues/ordinary-turns-do-not-use-the-additive-system-turn.md`.
The full desired regression remains red; the repair gate below is narrower.

## Compaction decision required by AGENTS.md §2.5

The requested cross-compaction byte equality conflicts with the current REPL
grammar and the stated identity scheme. `seon.repl/response-entries` includes
measured `:ms`; `entity-emission` includes `result/e<id>`. Compaction retains
turns, so the next turn/evaluation has a new identity and handle. Rerunning a
form also changes its measured duration. This cannot be resolved by asserting
that stored bytes are deterministic within one generation.

Before changing those semantics, the owner was asked for exactly three options:

1. **Recommended:** exact prefix bytes within a history generation; after
   compaction identical forms and values with fresh handles/timings. Smallest
   change, truthful identities and measurements; gives up cross-compaction
   equality of the complete prompt.
2. Remove handles and timing from prompt text. Broader rendering change;
   fresh identities remain in inspection, but prompts lose those details.
3. Retain/reuse original opening observations. Adds retention semantics and
   preserves bytes, but gives up the specified wipe-and-rerun algorithm.

No owner answer has been received at this checkpoint. The existing exact-byte
assertion remains unchanged. No new compaction semantics were implemented.

## Default HTTP and live facts

Default became available as PID **34306**, port **7994**. MCP health answered;
the entering fixture already had one provider attempt, one stored core error,
and a root evaluation error `Unable to resolve symbol: db/q`. The provider
attempt count remained **1** throughout this lane's default controls; this is
zero new attempts, not a claim that the inherited database had zero attempts.
Juniper's effective no-provider setting was true at every snapshot.

| Actual request | HTTP | Bytes | Seconds |
|---|---:|---:|---:|
| GET debug with `?prompt=true` | 200 | 52912 | 0.420240 |
| POST Run system turn | 204 | 0 | 1.082086 |
| POST Virtual turn before repair | 500 | 157 | 0.007343 |
| POST Compact | 204 | 0 | 0.154997 |
| POST Run system turn after Compact | 204 | 0 | 6.381899 |
| POST Compact, second observation | 204 | 0 | 0.170920 |
| POST Run system turn, second regeneration | 204 | 0 | 0.483101 |

POST route is `/agent/juniper/context`; form values are `system-turn`,
`virtual-turn`, and `compact`. These are the page's real controls via curl.
CUA reports zero browser surfaces and native Chrome returns `cgWindowNotFound`
(-10005). Browser paint is **unverified**, recorded in the existing browser
observation issue.

| Default saved-history snapshot | Evaluations | Bytes | SHA-256 |
|---|---:|---:|---|
| Entering fixture | 4 | 814 | `ef20357e12724e68e9fc8be867f7127f4c5b3986222bd2898462a9b5005cb6ac` |
| After first system control | 12 | 5216 | `ae8af3180d1e4df079ab92d6899a6d7c12de894341c6489c8499f261f19fef69` |
| After Compact | 0 | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| First fresh regeneration, measured twice identically | 7 | 3978 | `ebca13ccc18fb662b14ad595a526936073374ee1c3efd2eb8c9f83d048b9e13d` |
| Second fresh regeneration, unchanged record | 7 | 3973 | `7279fcad9856fe26ac642356a6cdb2e47a737fd26ee68e6780a409304434c457` |

These are **saved-history bytes**, not a claim that the current provider
projection equals them. Both fresh regenerations contain the same seven
sources (identity, current/ready/blocked plan, settings, settings attributes,
messages). Three wakes remain unanswered, answering `:t=0` throughout.
The known blocked-plan pull-contract defect recurs as two ExceptionInfo-shaped
shown values; its existing issue was updated rather than declaring it healthy.

## Real JVM interruption

Owned root `tmp/loop-proof-root`, cluster `loop-proof`, configured from the
committed no-provider manifest before agent creation. Scratch attempts: **0**.
Published base `6aa13af0-efc9-565f-901e-cd15603485fb`, source digest
`23ffcf62691cb471202f0890f7605ac20b170939b45e396d792debdb4e521701`.

`loop_proof_probe_2026_09_09.clj/prepare-crash` submits three actual forms:
write a marker agent, run a bounded long SCI loop, then write a second marker.
Evaluation limit 120,000 ms; completion bound 240,000 ms. The proc executes
normally; the evaluator is not substituted. Before killing, the exact scratch
PID **35098**, generation `4dd8dd07-d708-401f-b970-aee30d8c233b`, and root were
verified against operator status and process arguments. SIGKILL targeted only
that JVM, as explicitly required by this assignment.

Before kill: turn `d5a36f2d25f1` open; three durable intents without terminal
facts; marker exists at `:t=536871906`; final marker absent.
After boot at **2026-09-09T10:58:37Z**: same turn closed, and all three
evaluations interrupted at that instant:
`2669a37e9220`, `8cb8f54a0aea`, `b186ad6a4754`.
The first form had executed but its outcome had not reached the batched
settlement, so it is correctly interrupted too. Repeated later observations
show the same marker transaction and no final marker, with no reopened turn.

New virtual turn `64d2ebf4a8b2` executes `(+ 7 8)` and stores `15`, then closes.
Post-restart history: 4 evaluations, 509 bytes, SHA-256
`0040b3ae489609825be7d8a6d7490469aed18bca17d02f779a002b7cf5cf46a6`.
The canonical regression separately invokes the actual boot recovery owner
and verifies idempotence/no execution; the SIGKILL observation is the live
process-boundary proof, not a simulated recovery assertion.

## Independent control repair

Root cause: routing is on the armed cluster instance, but the view/HTTP
construction omitted it. `change-context` also overwrote a caller-supplied
routing atom with the nested handle's absent value. The repair carries the
existing routing atom through `arm-agents!` → `serve!` → `change-context`.
No new routing state or process-global lookup is introduced.

The existing real-proc virtual-turn fixture now enters `web/change-context`,
then observes the committed turn/evaluations independently. Its fast scoped
run passes **23 tests / 363 assertions**, zero failures/errors, retaining
cold 3/23 and warm three-form 3/45 measurements and private-object isolation.

Fresh scratch HTTP service on **62770**, built after the repair:
POST `/agent/crash-proof/context`, `action=virtual-turn` → **204 / 0 bytes /
0.072402 seconds**. It creates turn `bfe6b618db09`, evaluation
`deab95798f6e`, shown text `2`. Final closure and gates are recorded below.
This is new service construction; it does not claim default's already
captured HTTP service acquired the missing input through Var replacement.

**RESET NEEDED for the control-repair commit**: default's captured service
input lacks routing. No schema migration is required, but the orchestrator
must reconstruct that service through its normal lifecycle. This lane does
not operate default's lifecycle.

## Gates, changed files, and cleanup

Control gate (explicitly narrower than the red whole-loop proof):

```text
SEON_TEST_WORKERS=3 bin/test --paths src/seon/cluster.clj src/seon/render/web.clj test/seon/turn_test.clj -- seon.turn-test seon.render.web-test
80 tests / 717 assertions / 0 failures / 0 errors; exit 0
SEON_TEST_WORKERS=3 bin/test --platform --paths src/seon/cluster.clj src/seon/render/web.clj test/seon/turn_test.clj
83 tests / 490 assertions / 0 failures / 0 errors; exit 0
```

The explicit namespace gate completed its coordinator/test phase in 84
seconds; platform in 82 seconds. Their successful isolated roots
`run.eqgf74` and `run.SevUxY` were removed by the gate. Platform's normal
long-test exclusions are not a claim that those tests passed; no `--all`
or `--full` command ran. Every test command sets `SEON_TEST_WORKERS` to 3.

The final scratch HTTP turn closed at **2026-09-09T11:04:45Z**. Last scratch
snapshot: **5 evaluations / 601 bytes**, SHA-256
`522ada619e105ea85b9a8dfc8987b7b9eafeb8aaf800d28e1c65c41e5ea0e7bb`,
no-provider **true**, attempts **0**. The original marker remains at the same
transaction; the post-loop marker is still absent.

Default remained PID **34306**. In-place development adoption converged:
published and adopted source commit both
`6aa13d71-9baf-5d79-b72f-544061fd9399`. This proves publication, not replacement
of the HTTP service's captured input or browser paint.

Files changed in this slice:

- `src/seon/cluster.clj` — carry the existing routing into the web view/service.
- `src/seon/render/web.clj` — preserve supplied routing at the control owner.
- `test/seon/turn_test.clj` — existing real-proc proof enters the web control.
- `test/seon/loop_proof_test.clj` — one recurring desired-behavior loop proof.
- `docs/prds/context-generation/research/loop_proof_probe_2026_09_09.clj` — reproducible live byte/crash probes.
- `docs/prds/context-generation/research/loop-proof-landing-2026-09-09.md` — this evidence and decision.
- `docs/seon/issues/ordinary-turns-do-not-use-the-additive-system-turn.md` — core integration failures.
- `docs/seon/issues/virtual-turn-control-loses-agent-routing.md` — repair evidence and default reconstruction boundary.
- `docs/seon/issues/browser-ui-observation-has-no-accessible-window.md` — current browser-tool failure.
- `docs/seon/issues/blocked-plan-values-refuse-pull-during-ai-projection.md` — observed plan rendering refusal.

No edit to `src/seon/turn.clj` or compaction semantics is claimed. The new
test deliberately records the missing behavior instead of accepting it.
The first negative-wake extension completed **1 test / 50 assertions /
6 failures / 0 errors**: a system-only turn without the message's read leaves
that wake unanswered. The final proof also names turn 0's identity and waits
for the ordinary virtual reply's closure specifically, so a preceding system
turn cannot satisfy its completion check.

Final desired-behavior gate, exact command as above with only
`test/seon/loop_proof_test.clj`: **1 test / 51 assertions / 6 failures /
0 errors**, exit **1**, independently confirmed in a fresh confirmation
worker. Coordinator/test phase **39 seconds**. The six behavioral failures
remain the same; this is a red acceptance regression, not a green loop proof.

Scratch down completed with its JVM reaped and store lock free. Process-table
checks found no Java or runner holder for the owned scratch root or the three
retained failed roots. Removed `tmp/loop-proof-root` and
`tmp/test-runs/run.2Kr0j7`, `run.2lA549`, and `run.0wHLP0`; recursive cleanup
does not follow symlinks. All owned operator/gate shells exited and were reaped.
No worktree was created. Inherited untracked files were preserved. Git
whitespace validation passed. The slice is committed with these explicit
paths; its commit ID is reported in the lane summary.
