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

## Resumed slice: accepted compaction rule and additive virtual loop

The orchestrator accepted `e6832e8d8` and reconstructed default. This slice
uses the provisional ruling from the 2026-09-09 resume: exact bytes within
one history generation; after compaction, the same sources and shown values,
with new evaluation handles and timings permitted. It also follows the
explicit resume rule that a system-only turn does not answer a wake.
The earlier red results above are historical observations, not this slice's
verdict. The named authorities were read end to end in the first slice.

The prompt now renders `seon.eval/of-agent` through the evaluation schema's
existing AI render pair. The current-neighborhood prompt cache and its
replacement of earlier entries are removed. Ordinary `:open` first invokes
the existing system-turn owner to append changed reads, then opens the reply.
Answering derives from database transaction facts: a successful model attempt
or an ordinary plan frozen after its turn's opening qualifies. Source submitted
and frozen at opening, including a system turn, does not. There is no new
turn-kind attribute or fictitious provider attempt. Root's generated supervision
forms now use `seon.db/q`, `seon.db/db`, and `my.run/complete` explicitly.

The recurring `seon.loop-proof-test/virtual-loop-end-to-end` retains the
canonical database, real SCI, actual Flow proc, armed contracts, three-write
assertion, live `result/e<id>` objects, stored shown text, and boot interruption
assertions. Compaction compares forms and shown values; every acquired prompt
is compared with its stored history. The root alias probe executes the generated
query in a bare root namespace in this same regression.

Snapshot fast proof: **1 test / 52 assertions / 0 failures / 0 errors**.
Opening: **1 evaluation / 400 UTF-8 bytes**, SHA-256
`2ddf7b76bb0b15e98daf1020f6d517ef75c3c4b668fa893a350720655d83cecc`.
Compacted opening: **399 bytes**,
`f77b6a8450323b9341b2361ad87ab6664cb0fab99ea43eea0bae6f60831527c7`.
The three-form reply makes exactly **3 writes**, with **30 / 13 / 2 datoms**
for open / evaluations / close; resulting history **659 bytes**,
`0bab98fbba5a5ab67fa703c6dd52343d8bea1bbe7fb96df5ca3d5f4b61315357`.
The explicit changed-read system turn appends **1** evaluation but leaves
wake **536870931** unanswered, with answering basis **0**. The actual
ordinary wake path appends inbox read then `(+ 1 1)` and answers the wake.
These timing-bearing digests identify this exact invocation; no digest is
asserted constant across separate fixture generations.

### Default controls and curl, no provider

Default was reconstructed by the orchestrator, not this lane. This slice
observed PID **40078**, prepl **63396**, HTTP **7994**, and no-provider **true**.
The default fixture already had **1** provider attempt at entry and retained
exactly **1** throughout; the proof adds **0** attempts. The reproducible
`loop_proof_probe_2026_09_09.clj` snapshot now also acquires the actual prompt
and records its byte count, SHA-256, and equality with stored history.

Every POST below used `/agent/juniper/context` with the page control's
`action` form field and returned **204 / 0 response bytes**:

| Action | Seconds | Observation after completion |
|---|---:|---|
| system-turn | 1.053210 | 13 evaluations, 5304 bytes |
| system-turn | 0.463354 | 14 evaluations, 5726 bytes |
| compact | 0.164364 | evaluations wiped |
| system-turn | 0.373890 | 7 evaluations, 3973 bytes |
| system-turn | 0.127040 | same 7 evaluations, identical 3973 bytes |
| virtual-turn | 0.066134 | appended `(+ 1 1)` |
| compact | 0.180669 | evaluations wiped |
| system-turn | 0.952830 | 7 evaluations, 4138 bytes after the new message |
| compact | 0.136693 | evaluations wiped |
| system-turn | 0.896641 | same 7 sources and shown values, 4138 bytes |

Measured UTF-8 prompt/history digests (actual prompt equals saved history in
each snapshot):

| Snapshot | Bytes | SHA-256 |
|---|---:|---|
| entry, 5 evaluations | 902 | `f21449e71192968bff83cd6d243a59da9842709bd948c7701f97080cd41184b6` |
| first system turn | 5304 | `6c5bb7f3a093a66399e156128735d72289d0ad07124ea304dc55e734ea18e2e0` |
| second system turn | 5726 | `33fe36fb09a2d984d85271b1e0bb1732ffbfe023304f38b3c0c363ebf903383a` |
| fresh compacted generation and unchanged repeat | 3973 | `04a0d72e4dad59e6f0ca227f24cb063d8b83e2bc71829a768ab054db4668fee0` |
| message refresh and ordinary reply, 10 evaluations | 5366 | `e6e294d958840eedf146972f679c0f8902c80bc996717559d92faf0fe13492a8` |
| compacted message generation | 4138 | `445f0b681b2cfa5b5170efd9e8b1e7efaef92f8442030e2252c561373eb03870` |
| next compacted generation, same forms and shown values | 4138 | `a57f085e68557a0c6afe1c6ca1a8efd8046aab7b89b92dfa663ed323da29ba25` |

The second initial system turn legitimately refreshed old `(help)` read
evidence affected by turn metadata. The unchanged-generation check follows
compaction, when that legacy evaluation is gone; it appended **0** evaluations.

Message `loop-proof/resume/wake-2026-09-09`, content
`Virtual proof: refresh this inbox observation.`, committed at **:t 536871026**.
The real no-provider proc appended exactly
`["(my.message/inbox {})" "(+ 1 1)"]`. The prior eight evaluations' complete
text remained an exact prefix. Unanswered wakes became **0**, answering basis
**536871028**. GET `/ns/my.agents.juniper/debug?prompt=true` returned
**200 / 70708 bytes / 0.319366 seconds**. This proves HTTP and database
behavior; it does not claim browser paint. The previously filed inaccessible
browser-window issue still bounds visual verification.

The last two compactions compare actual vectors of source and shown value,
not normalized prompt strings: equality **true**. Their differing digests
are the expected fresh handles/timings under the provisional ruling.

### Fresh scratch root and verification limits

Started only `bin/seon --root tmp/loop-proof-root start loop-proof`, with the
existing `turn_schema_no_provider_2026_09_09.edn` manifest, and installed the
canonical Juniper fixture. Scratch HTTP **63807**, prepl **63800**; published
source commit `6aa141f4-0da3-5c49-a6c6-244f1025b5ec`, source digest
`7c7c6f7e01e32dae781e603d3f3a35d5ab867b4cdf3b960b8b867ed646717bc1`.
Root's generated supervision query executed successfully without aliases,
storing the completed disposition and `Read juniper's recent history.` result;
evaluation error absent, provider attempts **0**. The first slice's actual
scratch SIGKILL/boot proof remains recorded above; the recurring proof runs
boot recovery again in this slice.

The expanded exploratory run reported **160 tests / 1132 assertions /
22 failures / 4 errors** before fixture corrections. A HEAD-only snapshot
at `fd345e5e7`, using `--paths AGENTS.md`, independently reproduces
**27 tests / 117 assertions / 17 failures / 2 errors** in
`seon.bootstrap-test`, `seon.render.history-test`, and
`seon.render.root-pull-test`. These are existing local legacy consumer
boundaries, not another lane's uncommitted breakage. Obsolete tests asserting
prompt-cache replacement were removed; current prompt behavior belongs to
the recurring proof. The remaining legacy namespace failures are recorded
in the existing consumer-fixture issue. No shared-tree load failure required
a worktree, and no other lane's files or sessions were operated.

A further probe of `system-turn` after a successful attempted model history
hits `seon.render.transcript/render-history-ai`'s contract refusal. This is
outside the no-provider proof, and remains a named issue rather than a green
model-loop claim. Generated system-read preview still uses its existing SCI
preview context; persistent private bindings in refreshed read forms have
not been proven by this regression. No schema changed: this slice introduces
no additional RESET NEEDED. It does not withdraw the first slice's historical
service-reconstruction requirement, which the orchestrator has now handled.

Files touched in this resumed slice:

- `AGENTS.md` — align system-only wake wording with the resume instruction.
- `src/seon/bootstrap.clj` — fully qualified generated supervision forms.
- `src/seon/turn.clj` — ordinary opening refresh and transaction-based answering.
- `src/seon/render/walk.clj` — saved evaluations are the prompt history.
- `src/seon/render/web.clj` — remove mutable current-neighborhood prompt replacement.
- `test/seon/loop_proof_test.clj` — one recurring proof with revised compaction and root query.
- `test/seon/cluster/prompt_test.clj` — prompt byte stability independent of a retained cache.
- `test/seon/turn_loop_test.clj` — actual stored opening before the call-phase fixture.
- `test/seon/render/web_debug_test.clj` — remove retired prompt-cache assertions.
- `test/seon/render/history_test.clj` — remove retired history-replacement assertions.
- `test/seon/render/root_pull_test.clj` — remove vacuous retired helper assertion.
- `docs/prds/context-generation/research/loop_proof_probe_2026_09_09.clj` — actual prompt measurements.
- `docs/prds/context-generation/research/loop-proof-landing-2026-09-09.md` — this evidence.
- `docs/seon/issues/ordinary-turns-do-not-use-the-additive-system-turn.md` — repaired virtual path and remaining boundaries.
- `docs/seon/issues/turn-consumer-fixtures-read-retired-result-storage.md` — independent HEAD baseline.

### Resumed final gates and cleanup

Final namespace gate: **135 tests / 1032 assertions / 0 failures / 0 errors**,
exit **0**, coordinator/test phase **116 seconds**. This includes the final
root query assertion in the recurring proof. The immediately preceding
revision was also green at **135 / 1028 / 0 / 0**. Exact command:

```sh
SEON_TEST_WORKERS=3 bin/test --paths src/seon/bootstrap.clj src/seon/render/walk.clj src/seon/render/web.clj src/seon/turn.clj test/seon/loop_proof_test.clj test/seon/render/history_test.clj test/seon/render/root_pull_test.clj test/seon/render/web_debug_test.clj test/seon/cluster/prompt_test.clj test/seon/turn_loop_test.clj -- seon.loop-proof-test seon.turn-test seon.turn-work-test seon.turn-loop-test seon.cluster.prompt-test seon.render.web-debug-test seon.render.web-test
```

The final publication observation has identical published and adopted source
commit **`6aa14649-9ffc-50c5-aaf7-7bffa2ae9d2c`**. Default exercised in-place
development adoption following the orchestrator's earlier reconstruction.
Two initial convergence probe forms used the wrong `current` arity and old
registry namespace; both returned explicit errors before the corrected
observation above. They performed no lifecycle action or state mutation.

Scratch down reaped PID **41959** through the root-scoped operator, and
reported the store lock free. Process-table inspection found no Java or test
runner holder for the scratch root or failed `run.OvBpOa`; both were deleted.
Successful final namespace roots `run.hsg4xV` and `run.KUG37R` were removed
by the runner. No worktree was created. Inherited untracked `build/`,
`workers/`, and `config/virtual-turns.edn` were preserved.

Platform gate: **83 tests / 490 assertions / 0 failures / 0 errors**, exit
**0**, coordinator/test phase **52 seconds**, using the same explicit paths
with `bin/test --platform --paths` and `SEON_TEST_WORKERS=3`. Its successful
root `run.3N1jCB` was removed by the runner. The runner's printed long-test
exclusions are not a claim that they passed; this lane never ran `--all` or
`--full`. All owned test and operator shells exited and were reaped. Final
whitespace validation passed; the commit includes only the listed paths.
