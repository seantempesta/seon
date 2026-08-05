---
type: prd
status: active
tags: [prd, handoff, curation, rendering, maintenance, bootstrap]
---

# Handoff — 2026-08-04 night (bootstrap evolution, curation, output floor, disk)

Read this first, then [session-curation-prd](session-curation-prd-2026-08-04.md),
[universal-output-floor-prd](universal-output-floor-prd-2026-08-04.md), and
[curation-findings-ledger](curation-findings-ledger-2026-08-04.md). All rulings
below are recorded in [README.md](README.md) "Rulings 2026-08-04".

## 1. What this session was

Started as a design conversation about the BOOTSTRAP: how to teach agents the
system with forms rather than prose. That produced four things, in order:

1. the bootstrap-as-graph exploration (concept/form bipartite model —
   [bootstrap-concept-graph](bootstrap-concept-graph-2026-08-04.md), TABLED by
   the owner in favor of empirical work);
2. **session curation** — repair a messy session by re-executing a corrected
   form vector on a fork, adopt it as history (designed, W1+W2 landed);
3. **the universal output floor** — one completeness claim over the two
   existing render projections (designed, ladder steps 1+2 landed);
4. **root-agent maintenance** — the system maintains its own house (designed,
   not implemented).

Plus two platform incidents and a disk emergency, all resolved or scheduled.

## 2. Owner rulings this session (all in README.md)

- **Per-run fork contexts** — each run evaluates in a fresh `sci/fork` of the
  cluster base ctx; sharing moves from the mutable ctx to the durable graph
  (admission → fact → acquisition). Revises #27's channel, not its substance.
  RULED, NOT IMPLEMENTED — this is the next spine item after W3.
- **Session curation sealed**: three branches (live / editor scratch /
  verification proof), editor deliverable is DATA (the revision), only a clean
  proof adopts, adoption supersedes, all other futures deleted. Single-future
  adoption — there is never a merge.
- **Destructive = WRITES, not door-crossings.** Reads (web/fs) are curable;
  writes that left the branch are pinned. Mutation classification by leaf
  METADATA (the workload-classification pattern), reachability derives chains.
- **Open maps EVERYWHERE** (#48 extended to agent-authored fn contracts) —
  swept and guarded; it had drifted back once, hence the guards.
- **Accretion**: add keys freely, meanings never change, growth must be cheap.
- **Platform failures are highest priority** — clusters breaking get
  investigated immediately, attribution verified by evidence.
- **The root agent owns ALL system maintenance** — "we can't expect our users
  to have expertise but we can expect our agents to." Escalates to the USER by
  ordinary message when disk pressure is legitimate use.
- **Continuous REPL dogfooding** — keep lanes hunting ugly output.

## 3. Landed and pushed (branch `codex/runtime-reliability-refactor`)

**Curation spine**: W1 facts (`c508d848c`) — supersedes ref + one derived
active-runs rule across all four transcript visibility queries, opening commit
id, starting/ending namespace facts. W2 engine (`dbcacc91b`, `8763b4b17`,
`012f47efd`) — `system-run-tx` in `seon.cluster.run`, proof forks at the
pre-open commit and executes revisions through the loop with NO model call,
acceptance grades receipts/terminal/result/declared-content, adoption is one
append-only transaction, proof branch retired. Live-proven end to end
(`:transcript-has-curated? true :transcript-hides-messy? true
:original-queryable? true`).

**Output floor**: step 1 (`964b05dee`+) — recursive producer dispatch at every
depth, `seon.print/fit` as the ONE fit owner (original window deleted), agent
profiles as config facts (1,024 tokens / depth 8 / 32 children), elisions carry
count+total+path+offset+profile+digest. Step 2 (`2a625bcb1`) — identity-only
admission for database values/connections, hand-stripper deleted, both
`transact!` arities under one rule. Falsifier (`6af14d45c`, `4b6b1f20b`) — sink
+ projection-boundary leaf facts, path-classification query, **baseline: 75
bypass classes, 0 projected, 0 unresolved** — the number the ladder burns down.

**Platform fixes**: transcript same-instant ordering by ordinal fact
(`2e6f1344e`); capability walk fail-closed (`bcee99a74`); `defn` returns a Var
face (`d6329faa4`); error receipts render as execution errors (`c91de41a5`);
message ordinals as facts (`7cfb2435f`); assigned-namespace eval (`3a6264724`);
D1 declared-content comparison + bootstrap fence (`8763b4b17`); renderer NPE
(`5e5f28fb1`, a config-key namespace mismatch); **expected-rejection log noise
12,846 → 328 lines** (`dbef794ab` + fork `c1527273`); **allocation: schema
declare 3.85 GB → ~20 MB, contracted defn 587 MB → 1.7 MB** (`fba6bc4c1`);
open-maps sweep + guards (`ce099ce79`); agent-facing db faces 2 MB → 803 B
(`59edb37fa`); creation/config faces (`89fe1a287`); MCP envelopes
(`c683c7149`, `07fd06a51`); F11 test→function call edges (`093670eff`); `my.*`
docstrings −42% (`ed41a90f7`); shell capability slice (`45562de0e`); issue index
green (`d0813cb78`); test-root reaping + fault-repeat collapse (`7eeff3e70`,
`ca0e9579a`).

**Committed proofs/regressions**: `test/seon/concurrency_independence_test.clj`
(N-agent independence, asserts no lost writes — currently RED for two harness
defects, see §5) and `test/seon/concurrency_streams_test.clj` (seven collision
scenarios, green).

## 4. The experiment result (first real data)

`43ca3f098`, report:
[bootstrap-baseline-2026-08-04.md](../research/bootstrap-baseline-2026-08-04.md).
100 runs, 50/arm, $0.445, off-peak, no 402s.

| Objective | Arm A (14-form vector) | Arm B (help-only) |
|---|---|---|
| O1 author contracted transform | 8/10 | **10/10** |
| O2 find fn by input schema | **10/10** | **0/10** |
| O3 graph-only answer | 2/10 | 3/10 |
| O4 two-agent delegation | 0/10 | 0/10 |
| O5 repair refused contract | 0/10 | 0/10 |

Readings, in confidence order:

1. **O2 is the vector's proof.** 10/10 with the discovery form, 0/10 without —
   the find-by-shape query is exactly the capability a model cannot guess, and
   teaching it works perfectly. This is the single strongest empirical result
   of the session.
2. **O1 suggests the vector may HURT where the model is already competent**
   (8/10 taught vs 10/10 untaught). Needs replication before belief, but it is
   the concept-graph's "prior" idea showing up as data.
3. **O4 is a platform defect, not a bootstrap outcome** — 0/10 on all four
   predicates in BOTH arms. A full main/peer transcript pair is embedded in the
   report. Diagnose before drawing any bootstrap conclusion.
4. **O5's predicate is now STALE**: it looks for `:seon.schema/open-argument-map`,
   a refusal the open-maps ruling DELETED. Retarget it at the `:any` refusal
   (the bootstrap's forms 8-9 were already switched) before rerunning.
5. Arm B's 36 winner receipt sources are extracted verbatim — that is the
   mining input for the evolutionary loop.

## 5. What is next, in dependency order

**Spine**
1. **W3 — trigger + editor** (curation live end to end: run-boundary trigger on
   error receipts, editor agent spec, pinned-form presentation, writes-fail-
   closed gate). Then Arm C (help-seed + inline curation).
2. **Per-run fork contexts** — ruled, unbuilt. Needs a short spec (base-refresh
   seam, acquisition at run boundaries, fork cost measurement), then the
   concurrency proof: N agents, one cluster, SAME namespace, simultaneous runs.
3. **Output-floor ladder steps 3-8** — MCP routing (incl. the one-symbol
   wrong-node fix, regression already written but UNCOMMITTED in
   `test/seon/cluster/mcp_test.clj`), error data as values (kills D6), doc/dir
   as values, test runner, logs/faults, operator faces, page chrome. Each step
   reports its bypass-count delta from 75.

**Experiment**
4. Fix O5's predicate; diagnose O4; rerun; then generation 1 (promote Arm B
   winner forms into a candidate vector and re-measure).

**Maintenance** ([design](../research/scheduler-mining-and-gc-design-2026-08-04.md))
5. Implement the scheduler (per-agent flow schedule procs, durable fire
   identities, no central ticker — the old central polling ticker did NOT
   survive and must not return) and root's maintenance portfolio: store GC,
   footprint observation, dead-root reaping, log rotation, orphan census; one
   `seon.operator` owner shared with `bin/seon`; results as facts; legitimate
   pressure escalates to the user by message.
6. **Reset semantics (owner, unambiguous): a reset DELETES EVERYTHING and
   rebuilds — no querying, no claim checks.** The design lane found
   `reset --force` can currently report success while scratch roots and logs
   remain; that is the defect to fix. The ownership-claim question I raised was
   about the SCHEDULED reaper (which must not eat live work), not about reset —
   and even there the operator's existing process records are the mechanism;
   my emergency `ps`-grep sweep was a hand-rolled substitute and that is why it
   destroyed a live experiment's workspace.
   ANSWERED by
   [store-existence-authority](../research/store-existence-authority-2026-08-04.md)
   (`07ef1b7a8`): Datahike's `:branches` roster is store-LOCAL and konserve only
   checks caller-supplied paths — neither dependency maintains a parent catalog,
   so a non-database record is genuinely required. The recommendation is to
   EXTEND the operator's existing atomic records into a claim-first authority
   living outside the managed roots (survives the target's death), not to invent
   a registry file. Two `bin/seon status` output defects were filed alongside.
7. **The one-time reclaim, owner-approved, still pending**: head-only store GC
   (**357 GiB reclaim, 17 GiB retained**) + delete the frozen `tmp/` evidence.
   Run as ONE quiesced pass after the leak fixes land. Recipe and measurements:
   [disk-burn-forensics](../research/disk-burn-forensics-2026-08-04.md);
   evidence census:
   [survivor catalog](../research/disk-burn-tmp-survivor-catalog-2026-08-04.tsv).

**Open defects worth attention**
- `concurrency_independence_test` is RED for two harness defects it documented
  honestly (auto-reply messages on completion caused stubbed follow-up
  episodes; one receipt diagnostic false-fails). Fix those and it becomes the
  standing concurrency gate.
- Ordering-by-id-string, third occurrence: transcript candidate window
  (issue filed).
- The findings ledger's D-section still holds unfixed items (D3-D12, D14, D15).
- Issue index has a few unindexed notes from concurrent filing.

## 6. Standing operating notes

- Disk: ~276 GiB free. `tmp/` evidence is FROZEN pending §5.7.
- Lanes: `bin/codex-agent run|resume|summary|status|stop`; never pipe the
  wrapper's stdout. A schema/config/require change must land as its OWN
  immediate commit before long behavioral work — one lane broke shared gates
  twice tonight by holding them.
- Every lane reports ugly output; that loop produced most of tonight's fixes.
