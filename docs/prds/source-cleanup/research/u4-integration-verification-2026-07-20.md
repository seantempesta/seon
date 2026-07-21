---
type: research
status: complete
tags: [research, prd, runtime, testing]
---

# U4 integration verification

## Scope and disposition

This is an independent, read-only verification of U4 commit `b7808e35`
against its recorded host-runtime claims. It also resolves the ownership of
the three drill scripts that were dirty when the verification was assigned.
No lifecycle operation or drill was run.

**Disposition: accepted for the contracts U4 claims.** No serious contract
problem was found. U4 remains honest about the work it deliberately carries
forward: the JVM host still lacks the child run-fence assertion, ALS print
capture, the render-ai result skeleton, and the preflight/repair sub-loop.
Those are not represented as complete in the SCI roadmap.

The three questioned scripts are no longer dirty or unowned. Commit
`2821bb87` records `tmp/sci-probe/jvm/drill.sh`,
`tmp/sci-probe/jvm/pod-restart-drill.sh`, and
`tmp/sci-probe/jvm/run-all-drills.sh` together with their one shared lifecycle
owner, `drill-lifecycle.sh`. Their current worktree diff is empty. They are
therefore committed, coherent inputs and do not require a discard, stash, or
later path handoff.

## Claims checked

### Durable receipt and corpus recording

The source implements the claimed ordering rather than merely describing it:

- `seon.host/eval-batch-result` calls
  `seon.host.context/start-eval-receipt!` before evaluating a form and refuses
  to execute the form if the receipt transaction fails.
- `start-eval-receipt!` obtains a managed candidate through the public pure
  `seon.db.id/candidate-manifest` seam, sends the candidate manifest through
  the serialized writer request, and retries only the named generated-candidate
  conflict up to the existing bounded allocation limit.
- `record-eval-terminal!` puts the `:running` CAS fence, frozen eval row, and
  successful program-graph tee data in one transaction.
- `seon.host.record` is a pure data builder for eval rows, strict single-defn
  function rows, exact read-attribute replacement, namespace require edges,
  and schema rows. It does not introduce another database API or writer.

The real-memory-writer regression
`host-evals-record-the-same-corpus-data-as-the-child-tee` executes three forms
and reads back three terminal receipts under the owning turn, their agent
connections, the function projection, and the schema projection. It then
stops and starts the host and proves the earlier definition evaluates from a
fresh host context. This is behavioral evidence for the central recording and
replay claim, not only a shape assertion.

### Home namespace, replay, and provenance

The host establishes the requested starting namespace, changes it only for a
successful explicit `ns`/`in-ns` form, and queries replay sources by the
agent's deterministic home namespace. Invocation execution binds the agent
identity around the host database wrappers; the context request builders add
the matching user and process references to reads and writes. The implementation
therefore matches the roadmap's narrower "host half" provenance claim.

The retained U1.5 drive log at
`tmp/sci-probe/exec/out/u4-proof-drive.log` shows turns 1, 2, 4, and 5 terminal
with eval rows, a contained host-exit result for turn 3, and turn 5 without an
unresolved-symbol failure after the host restart. The log also exposes an
unrelated driver-fixture weakness: its authored `seon.agent.message/id` values
are rejected by the stored compact-ID policy, so the cross-turn message facts
remain nil. That does not falsify definition replay—the direct real-writer
restart test returns `8` from the pre-restart definition—but this drive should
not be cited as proof that those message transactions succeeded.

### Recorded gates

The retained CLJS test log
`tmp/test-cljs-20260720-201548-33934.log` contains the claimed 1336 tests / 6170
assertions with zero failures and zero errors. I did not find a retained log
containing the exact 261 tests / 2016 writer assertions; that count is recorded
in both the U4 commit message and the SCI roadmap, while the checked-in
real-writer regression remains inspectable. This is a minor evidence-retention
gap, not a source-contract blocker, and the source-cleanup program's later
frozen writer gate will supersede it.

The post-U4 combined drill output at
`tmp/sci-probe/jvm/out/run-all-drills.out` records both host-kill and
pod-restart as PASS. The pod-restart child output records admitted,
pod-unavailable, recovered, and done/pass phases. Commit `2821bb87` additionally
removes broad stale cleanup from each individual script in favor of the one
private-runtime lifecycle helper and serializes the combined runner with a
recoverable lock.

## Freeze ruling

The U4 host/database paths and all four drill lifecycle paths are committed and
have no worktree diff. On U4 alone, Stage 1.6 may enter its source freeze now;
there is no remaining script disposition to wait for. The top-level
orchestrator must still apply the Stage 1.6 runbook's global conditions—every
other source owner released, retained branch/process state disposed by its
owner, and no build input changing during the checkpoint—before counting the
frozen proof.
