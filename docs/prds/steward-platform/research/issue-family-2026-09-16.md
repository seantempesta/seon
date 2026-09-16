---
type: research
status: implemented; live proof recorded; orchestrator gate pending
created: 2026-09-16
tags: [research, schema, database, agent]
---

# Issue family: publication, workers, and live evidence

## Implemented scope — 2026-09-16

The owner approved option 1 and expanded it to atomic worker creation and
the virtual-turn proof. The guarantee delivered at the publication seam is:
**note identities and their program refs are indexed in a separate transaction
after program writes, before the final source seal; worker creation composes
the existing creation and opening transactions atomically.**

The API adds tests; it does not enforce non-removal through arbitrary
database writes. Automatic test execution and resolved-tx settlement remain
the separately assigned settlement lane's responsibility. Their design is
recorded in [the residual issue](../../../seon/issues/issue-test-preservation-and-settlement-need-writer-integration.md).

Commits, each path-limited:

- `6a491f0b3`: issue schema and agent reverse unit after the plan.
- `ff48a4110`: renderer declarations accepting both the declaring issue shape
  and render units. Default's exact render-contract observation accepted both
  functions; assert-render-contracts! accepted :seon.issue/issue. This fixed
  the HEAD publication refusal introduced by landing schema before its pair.
- `a7d1e115e`: indexing, publication/adoption, query index, and CLI refusal report.
- `4a1cfb7c1`: atomic worker creation and my.issue operations.
- `fe9aeb336`: separate post-program issue transactions, conditional preservation
  of installed test reach digests, and publication fixture corrections.
- `cda42c461`: function lookup refs, error occurrence counts, and current-reach
  verification after the two-argument seon.test/verified? landed.

The former Markdown schedule is now a query. The CLI still supports
`bin/issues-index --check`; it returns the indexer's unresolved citations and
exit 1 when they exist. Issue identity is the note slug, including when a note
is archived. Removed notes retain identity tombstones. Database-authored
issues have no note path. Class tags become member refs; ordinary tags are
not a second classification authority.

The existing program replacement helper only handles program identities;
the initial design's reuse claim below was falsified in the REPL. The issue
owner instead computes exact attribute replacements for its own family.
Indexing and adoption preserve assignment, budget, and added success tests
for present notes. Source adoption consumes the published database, not a
second filesystem read. Markdown-only edits require explicit publication
with `bin/seon init --dev default --changed docs/seon/issues`; this lane did
not widen the hook's admitted file-extension policy.

## In-process regression record

All calls used MCP JVM mode and explicit cluster custody. No bin/test,
bin/test-fast, or test JVM was launched. Development candidates were evaluated
before file edits, then the affected test was run; saved definitions were
reloaded/adopted and tested again with armed contracts. Tests ran serially.

The exact invocation shape was:

```clojure
(let [c (seon.operator/connection "default")]
  (seon.test/run #'seon.cluster.source-test/latest-test-evidence-survives-rebuilding-from-an-older-base
                c {:seon.test/remaining-ms 180000
                   :seon.test.run/provenance
                   (seon.test.runner/provenance (seon.db/db c))}))
```

The other invocations substitute the fully qualified test below. Ordinary
issue tests used a 120000-ms bound; they completed far below it. Longer source
tests retained their result in a disposable JVM future so MCP's 60000-ms
transport bound did not discard the result. Every returned result was read.

| Slice / test | Before file edit | Saved definition / recorded run |
|---|---|---|
| Schema contract fixture | 3/0/0 | run 67ccc991cf49 |
| seon.issue-test/indexed-issues-replace-facts-and-retain-identities | 18/0/0 on scratch; lookup-ref candidate 18/0/0, 1345 ms | default 18/0/0, run entity 69330, 3177 ms including reload/arming |
| seon.issue-test/issue-worker-creation-is-atomic | 13/0/0, 4173 ms | scratch 13/0/0, 4715 ms; final default 13/0/0, run entity 69334, 4771 ms |
| seon.issue-test/issue-worker-opening-links-its-issue | real SCI/Flow fixture 10/0/0 including cold fixture assertions | 8/0/0, run entity 66828, 11241 ms |
| seon.dev.issues-test/issue-cli-reports-absence-and-refusals | same implementation under canonical fixture | 5/0/0, run entity 67637, 591 ms |
| seon.cluster.source-test/incremental-upsert-seals-one-activation-on-the-expected-commit | existing seal harness | 7/0/0, run entity 70774, 982 ms |
| seon.cluster.source-test/latest-test-evidence-survives-rebuilding-from-an-older-base | reproduced 12 failures; missing completion reach digests | final default 21/0/0, run entity 68457, basis 536871714, at 03:01:13Z |
| seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows | 16/1/0: expected set omitted the source-file entity | final default 17/0/0, run entity 68465, basis 536871721, 42001 ms |

Counts are pass/fail/error, not suite tallies. Scratch evidence was produced
on the lane's isolated worktree runtime; default supplied the final source
publication and issue API checks. The scratch fixture needed the already
committed generic read-identity fix 474234fb7. No foreign main-tree files were
edited to obtain that result. Early failures also exposed a missing test-only
dependency and a missing fixture fault channel; neither is counted as green.

Batch 19's blocks were read only from its platform.md. The reproduced source
failures did not establish issue-index replacement of test evidence: the
completion fixture lacked the required reach-digest map introduced by
f2d537187, and the incremental expected identity set predated 3402913f3's
source-file entity. The revised fixture derives digests through runner/reach-digests
and explicitly verifies preservation. Its global conflict probe now counts
only its own run identity, avoiding interception of concurrent test completions.
One candidate run saw a live definition revert during adoption and one saw
concurrent namespace drift; both red outcomes were retained during diagnosis.

## Live default proof

Default PID 7595 was never stopped, reforked, or restarted by this lane.
Host forms were hot-reloaded and re-armed, and ordinary development adoption
continued. A file save alone is not claimed as proof of convergence.

The first seon.render.web steward is agent `8273411a3e45`, created through
creation-tx and steward-call. A live pull verified the namespace's steward ref.
The namespace-to-function-to-issue query returned 24 issue identities.
At the 03:12Z census default held 1621 indexed issue entities and one authored
issue, with 1514 citation refusals. A later report showed 248 open issues and
1515 unresolved-symbol refusals as the shared notes changed. The CLI check
returned exit 1 and 246790 bytes of refusal evidence; it did not report green
on unresolved citations.

The first real worker, `12254041a057`, started on
`agent-form-calls-to-core-namespaces-are-not-indexed` while its cited test was
5/2/0. Its opening `b93f168916f7` closed at transaction 536871499 with ten
evaluations: plan ordinal 2, issue ordinal 3, every shown value present and
no evaluation error. Its ordinary virtual reply read my.issue/status in turn
`d46823b798c6`, closed at 536871519 with one successful evaluation.

The exact opening is saved in
[text](issue-family-opening-2026-09-16.txt) (9901 UTF-8 bytes) and
[EDN](issue-family-opening-2026-09-16.edn). These historical bytes retain the
then-existing sparse-function rendering warnings. The subsequent lookup-ref
change was verified through SCI without those false restart messages; history
was not rewritten to hide them. Chrome's namespace page visibly placed the
issue block after the plan and displayed its test's verified state.

my.issue/add! and my.issue/tests! were additionally called through MCP SCI
on real data. The authored inspection issue is `d1f11894d81f`, worker
`856c73b784fb`, namespace my.agents.issue-family-paid, budget 1. The owner file
explicitly permits DeepSeek; the live configured model is deepseek-flash.
Its paid-session result follows.

### Paid-session ledger

| Agent / issue | Provider turn / attempt | Model | Captured prompt | Tokens in / out / cached | Terminal result |
|---|---|---|---|---|---|
| 856c73b784fb / d1f11894d81f | 36e029636c82 / 3c0ce4212c40 | deepseek-flash | 10993 UTF-8 bytes | 3355 / 142 / 0 | closed at 536871861; wait disposition; 3 successful evaluations and 1 reader error |

The exact [captured prompt](issue-family-paid-prompt-2026-09-16.txt) and
[reply/evaluations/usage](issue-family-paid-2026-09-16.edn) are retained.
The intended my.issue/status and my.agent/done forms both succeeded. Plain
prose containing an inline form created an extra evaluation and an unmatched
delimiter error; this is tracked in
[the reader issue](../../../seon/issues/inline-form-in-reply-prose-becomes-an-evaluation.md).
This is a completed inspection session, not a claim that the assigned
publication defect was repaired by the model.

The existing explain_probe_2026_09_14.clj helper made one separate DeepSeek
call on the exact captured prompt. Its [saved answer](issue-family-explain-2026-09-16.edn)
correctly identified the issue, test, requested forms, and red stored evidence.
Usage: 3432 prompt tokens, 869 completion tokens, 3200 cache-hit tokens.
It identified redundant instructions in the two messages and issue/plan
problem text. Its explanation for prose addressed the current out-of-band
question, not the original reply, so it does not establish why that reply
violated the forms-only instruction. This limitation is part of the evidence.

The first internal message did not refill the episode budget, by design.
The operator's ordinary inbound-message transaction at 536871847 supplied
the outside wake; the configured budget remained 1. The live graph had to
be armed explicitly for this newly created worker. No alternate provider
loop was introduced. The in-flight settlement lane ran issue tests between
opening evaluations; the source-publication regression exceeded those
30000-ms evaluation bounds. The [filtered thread dump](issue-family-settlement-stack-2026-09-16.txt)
shows seon.plan/run-issue-tests! → seon.test/run → source publication. No
protected settlement file was edited. The opening eventually completed.

Chrome directly displayed the paid worker as idle, the plan at 0/1, its
issue block immediately after the plan, the linked test red, and no routed
core fault. The issue remains open. A separate
[adoption-window issue](../../../seon/issues/development-adoption-window-loses-newer-issue-and-test-facts.md)
records the lost earlier assignment and reverted test evidence without
claiming an unverified writer attribution.

## Exact boundaries and gate

The original prohibitions were respected except for the owner's explicitly
granted source publication, digest, adoption, and CLI hunks. Before editing
or committing cluster.clj its diff contained only the lane's publication
hunk; no error-graph commit-fault edits were included. The lane did not edit
db.clj, plan.clj, turn.clj, the test owner, the error owner, or program-provenance
owners. Settlement changes observed in the live JVM belong to their lane.

The ownership-decision issue is superseded by authorization, with the writer
and settlement residual named. No class/member roster was specified by this
concrete issue-family assignment, so this note makes no unrelated class-closure
claim. New detector and multi-issue proposals appended to spec sections 7–8
remain later work; this slice implements the approved single-issue case.

`tmp/orchestrator/gate-requests/issue-family.txt` requests the platform re-run
at fe9aeb336. Both requested source tests passed in-process. The orchestrator's
isolated gate remains the final proof; this lane does not claim a platform
green from its in-process runs. git diff --check passed on committed paths.
The exact source-test results are also saved in
[the publication proof](issue-family-publication-proof-2026-09-16.edn).

The scratch worktree runtime was shut down through its own bin/seon down;
the worktree and old lane root were removed. All lane shell commands exited,
and the retained test/explain futures completed. The two real worker agents
remain as durable proof facts on default; no default lifecycle operation ran.
The documentation hook still reports the pre-existing dependency-pin errors
in agents-md-audit-2026-09-15.md; they are outside this lane's edits.

Final read-only publication probe: current-src was
`6aaa0c28-fb31-5521-9bd8-c9c64e5f123f`, while default's recorded adopted
commit was `6aaa0609-cbb4-522a-a7c4-f53e06ce04b2`. They differed during
concurrent publication. Therefore the proofs above establish the named
hot-loaded definitions and ordinary worker behavior, not complete default
adoption convergence at handoff. The paid worker's next-agent-work was nil.

## Historical pre-approval decision

The remainder is the dated initial scope decision, retained as history. Its
statements that no implementation or live proof ran describe that initial
checkpoint only; the execution evidence above supersedes them.

The requested guarantee is: every indexed issue follows publication and a
started issue retains its success tests until verified settlement resolves it.
That guarantee requires changes outside the assignment's owned paths. No
production edits were made. This is the assignment's pre-edit design gate,
not its conditional stop after slice 2: agent creation itself is reusable.

## Authorities and scope

Read AGENTS.md and docs/seon/issues/README.md end to end; read
docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md end to end,
the namespace-data-model's required sections 0, 3.3, 8 and 9, and the task
prototype including sections 1–4. Read the existing script and CLI end to
end. Loaded data-oriented-clojure, data-modeling, datahike, repl,
clojure-testing and datastar-web-ui skills. Read the active roadmap entry
and the class-mining structural-kill table. The specific issue-family
assignment names no class issue or member list; no class/member closure is
claimed from the generic lane preamble.

Baseline HEAD: `3c35a62127424075c2c7f585fb88e04e10c652c7`, branch
`steward-platform`. Concurrent edits were present in program, turn, test,
schema bridge and rendering owners. They were preserved.

## Dependency ledger and exact seams

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:1152`
  applies a transaction function to its current transaction database.
  `src/seon/cluster.clj:2272` demonstrates composition of agent creation
  and generated opening in one transaction function.
- `src/seon/cluster/agent.clj:121` exposes `creation-tx`;
  `src/seon/turn.clj:729` exposes `generated-run-tx`. Both use the same
  `namespace:<symbol>` tempid. No cluster edit is needed merely to compose
  these public functions. The creation contract also requires
  `:seon.cluster/name`, omitted by the prototype's abbreviated example.
- `src/seon/program.cljc:849` owns exact attribute replacement. Reuse is
  possible without editing this protected owner; preserve the issue
  identity and separate indexed attributes from worker-authored attributes.
- `src/seon/cluster.clj:1383` owns complete source population, with
  `seon.fn/index!` at line 1419. `source-roots` at line 1449 determines the
  digest inputs. Issue markdown is absent from those roots.
- `src/seon/cluster/source.clj:448` owns incremental source publication;
  `src/seon/cluster.clj:1935` onward separately adopts schema/program facts
  into default. Calling a new issue indexer only from the CLI would not
  establish either automatic publication or development adoption.
- `src/seon/db.clj:2810` checks map assertions and `:db/add`, not
  retractions; `transact-call` at line 2818 then submits to Datahike.
  An additive `my.issue/tests!` API does not prevent a worker using the
  already callable database API to retract a test, retract the attribute,
  or retract the entity. A writer invariant needs coverage of expanded
  transaction functions as well as direct operations; a caller pre-read
  is insufficient. No such invariant is declared by the proposed schema.
- `src/seon/plan.clj:554` records step completion only. The turn owner
  invokes `plan/settle-call` at lines 2204, 3423, 3462 and 4642.
  Test execution belongs before the settling transaction, under the
  existing execution bound; it must not run within a Datahike transaction
  function. Writing issue `resolved-tx` also needs the completion seam.
  The issue namespace alone cannot insert these behaviors.

## Live evidence

All probes used MCP JVM mode on default with explicit
`(seon.operator/connection "default")`. No production definition was
evaluated or changed; no transaction was submitted. The script containing
the successful forms is the adjacent
`issue-family-probes-2026-09-16.clj`.

`bin/seon status`: default alive, PID 69622, prepl 55914, web port 7994,
no orphan Seon JVMs. MCP runtime status: 4 error signatures, 31 errored
evaluations (tool key `errored-receipts`), 1 failed run, 17 stale Vars.
These are inherited observations, not attributed to a cause.

At basis 536872692 the complete small probe returned:

```clojure
{:basis 536872692
 :issue-declared? false
 :retraction-preflight nil
 :source-roots ["src" "test" "config/default.edn"]
 :verified-arglists ([database test-symbol]
                    [database test-symbol program-digest])}
```

The retraction probe only called the pure preflight on an existing agent's
plan attribute; it did not retract anything. It establishes preflight's
absence of a retraction check, not a completed issue-retraction experiment
(the issue schema is not installed).

The creation/opening probe returned 3 and 2 transaction entries,
respectively, including `steward-call` and `open-call`. Its envelope was
windowed (4789 bytes, blob digest
`a7ec67f9e2e155e0119a3ba95bfb0da60f43ad3f2c593374dc34ed9d0ce5e645`),
so it is evidence of returned transaction shape, not an atomic commit
proof. The small follow-up evidence above was not windowed.

Two exploratory forms failed before mutation: creation without required
cluster name, and `contains?` on Malli's composite registry. Corrected
forms use the complete creation request and `malli.registry/-schema`.
The live `verified?` metadata has two arities while the source file read
still has three arguments only; do not infer that reach-digest is landed
from live metadata. Verify semantics and adoption with that lane's final
commit before choosing the final completion predicate.

## Three priced options

Estimates below are engineering effort, not measured runtime.

1. **Index and inspect first — recommended, simplest viable constraint.**
   Authorize narrow changes to publication/digest/adoption owners, then
   deliver schema, exact indexing, CLI refusal report and query/render
   surfaces. Keep worker start and automatic resolution out of this
   checkpoint. Guarantee: issue rows and refs follow the selected source
   publication. Cost: approximately 4–8 hours plus serial integration.
   Give up: executing issue workers in this checkpoint.
2. **Deliver the full guarantee across owners.** Authorize coordinated
   publication, database writer and plan/turn settlement changes alongside
   the issue family. Prove non-removal through direct operations and
   transaction functions, then automatic verified settlement and virtual
   turn behavior before the paid session. Cost: approximately 1–2 engineer
   days plus integration, depending on the writer constraint mechanism.
   Give up: the current lane's path isolation and independent landing.
3. **Explicit indexing and cooperative worker API only.** Keep current
   path ownership; provide explicit `index!` and additive `tests!`, with
   manual resolution. Change the acceptance criteria to state that direct
   database retractions remain possible and note-only edits do not publish
   automatically. Cost: approximately 4–8 hours. Give up: the requested
   structural guarantee and automatic publication/settlement. This is a
   narrower product, not completion of the approved spec.

The decision is required by AGENTS.md's owner design gate and the
assignment's equivalent instruction to stop before hours of cross-owner
work. The prohibited files are explicit in the spec's section 5. No skill
introduced an additional approval requirement.

## Verification boundary and handoff

No implementation slice, schema installation, in-process regression,
test JVM, canonical gate, virtual worker turn or provider session ran.
There is no claim of completion. No default stop/refork/restart, background
shell or scratch root was created. The gate request records that no code
gate is ready. The residual is tracked in
`docs/seon/issues/issue-family-guarantees-cross-prohibited-owners.md`.

The documentation hook reported 12 Markdown lint errors. Its visible
diagnostics name stale dependency-pin citations in the existing
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`;
the hook elided the remainder, so not all 12 are attributed here.
`git diff --check` passed. The shared index was not edited by this lane;
the new open note needs an owner schedule entry under the current checker.
