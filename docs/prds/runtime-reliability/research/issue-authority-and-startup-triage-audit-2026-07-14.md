---
type: research
status: completed
tags: [research, issue, orchestrator, prd]
---

# Issue authority and startup triage audit — 2026-07-14

## TL;DR

Move the issue authority to `docs/seon/issues/`. Do not rename the surrounding
`docs/seon/orchestrator/` directory to `issues`; the nested issue tree is the
thing worth keeping, while the five sibling orchestrator documents are either
duplicate current-state indexes or dated research in the wrong layer.

The migration must be a Git move, not a rewrite:

- move all 91 issue notes, `README.md`, and generated `index.md` together;
- normalize the issue lifecycle to `open | resolved | superseded` and require
  `blocker | friction | cleanup` severity on every note;
- dissolve `dual-code-paths-registry.md`, whose private IDs, `NEXT-ID`, and
  row-level statuses make it a tracker inside the tracker;
- archive 19 top-level notes already fixed or made impossible by deleted code;
- retain and tighten ten current notes, and split eight still-live findings out
  of the dual-path registry into ordinary one-problem notes;
- add the currently observed changed-test manifest failure as an ordinary issue
  until the active test unit closes it;
- move the three dated orchestrator audits into this PRD's `research/`, delete
  the stale `active.md` and `prds.md` projections after preserving any unique
  links in `_dashboard.md` or the active PRD; and
- update every code, instruction, verifier, and documentation reference to the
  client-neutral path.

At each new top-level orchestrator session, launch exactly one bounded triage
subagent. It checks open notes against current source and cheap existing proofs,
updates dispositions only when evidence changed, and returns at most three
adjacent opportunities. It does not create another report, run the full suite,
or reorder the user's request. Ordinary agents and subagents report every found
problem to their parent and create or update the corresponding issue note; if
they fix it in the same unit, they close and archive that note with proof.

## Scope and evidence read

This audit read:

- root `AGENTS.md` as supplied for the current instruction consolidation;
- `ORCHESTRATOR.md` in full;
- all five `docs/seon/orchestrator/*.md` files;
- every top-level and archived file under
  `docs/seon/orchestrator/issues/`;
- `docs/seon/_dashboard.md`;
- the active runtime-reliability `AGENTS.md` and complete `roadmap.md`;
- `bin/issues-index`, the Markdown validator, agent/verifier definitions, and
  every repository reference to the old path; and
- current source existence and focused implementation evidence for the open
  notes and unresolved rows in the dual-path registry.

Observed inventory:

| Item | Count | Finding |
|---|---:|---|
| top-level issue notes | 30 | The index presents only 27 as open because three use invalid lifecycle values. |
| archived issue notes | 61 | Four lack required severity. |
| issue Markdown files including README/index | 93 | Valuable historical evidence; preserve through Git moves. |
| sibling `docs/seon/orchestrator/*.md` files | 5 | Two duplicate current state; three are dated audits. |
| files outside the issue tree referring to the old path | 28 | Includes code, active instructions, verifier definitions, vision, PRDs, and historical evidence. |

Concrete drift:

- `ctx-install-live-tile-symbol-roundtrip.md` and
  `narration-ghost-echo-not-neutralized.md` use `status: active`;
  `findings-renders-open-plan-as-fact.md` and
  `plan-frontier-hides-open-root-with-done-children.md` use
  `status: completed`. None is a legal issue lifecycle state.
- Six top-level issue notes and four archived notes lack severity. The index
  silently defaults missing severity to cleanup instead of rejecting invalid
  data.
- The checked-in index links `ctx-install-canvas-symbol-roundtrip.md`, which
  does not exist. Its actual source filename still says `live-tile`; the index
  is stale despite claiming to be generated.
- `ORCHESTRATOR.md` names four incompatible lifecycle/severity vocabularies:
  `in-progress`, `verified`, `architectural`, and `blocking`, while the issue
  README permits only `open/resolved/superseded` and
  `blocker/friction/cleanup`.
- `dual-code-paths-registry.md` has its own M/C IDs, a mutable `NEXT-ID`, open,
  closed, deferred, legitimate, and audited states, plus multiple problems per
  row. It directly violates “one note per problem” and is a parallel tracker.
- Ten top-level notes point only at source files deleted by the active JVM
  archive cut. Five more point at deleted hook/supervisor/test mechanisms.
- `docs/seon/orchestrator/active.md` still advertises retired process verbs such
  as `start all` and `restart pod`; the current operator contract is in the
  active PRD and root instructions. `docs/seon/orchestrator/prds.md` still calls
  the already-integrated autosuggest work a separate lane.
- The hook emitted `changed tests build-unavailable` three times during this
  audit because the managed Shadow manifest did not match current source within
  30 seconds. The active roadmap discusses the implementation, but the present
  issue authority has no note for the observed failure.

## One authority and layout

Target layout:

```text
docs/seon/issues/
  AGENTS.md       localized issue-writing and triage rules
  README.md       lifecycle, severity, note contract
  index.md        generated projection; never hand-edited
  *.md            open issues only, one problem per note
  archive/*.md    resolved or superseded notes only
```

`docs/seon/issues/AGENTS.md` should be short. It owns only note structure,
triage mechanics, and the index command. Root `AGENTS.md` owns the universal
reporting obligation; `ORCHESTRATOR.md` owns top-level startup triage. Do not
copy the same protocol into all three.

### Issue lifecycle

Use exactly:

```text
open -> resolved
     -> superseded
```

- `open`: current evidence says the problem remains. Work-in-progress stays
  open; the PRD roadmap records implementation progress.
- `resolved`: the owning mechanism was fixed and the note includes the commit
  plus behavioral or live proof.
- `superseded`: the code/design no longer exists or the premise was replaced.
  The note names the deletion/replacement evidence.

Do not add `active`, `in-progress`, `completed`, `verified`, `closed`, or
`archived`. The filesystem location already distinguishes open from closed;
the PRD roadmap already tracks work state.

### Severity

Use exactly:

- `blocker`: correctness, data/process safety, or liveness can prevent the
  current deliverable;
- `friction`: a real reliability or usability defect that materially slows
  work but does not block the current deliverable; and
- `cleanup`: duplication, stale naming/docs, dead material, or a bounded smell.

Architecture is a tag, not a fourth severity. Add severity to every archived
note too, so the corpus is mechanically uniform.

### Note contract

Every issue note has:

```yaml
---
type: issue
status: open
severity: friction
tags: [issue, agent]
---
```

The body contains:

1. **Problem** — one observed mismatch, not a family or status ledger.
2. **Evidence** — current file/symbol plus a failing test, live observation,
   log, or exact source-existence result.
3. **Owner** — the one namespace/mechanism that should be strengthened.
4. **Acceptance** — behavioral falsification, not exact prose.
5. **Resolution** — added only when closing, with commit and proof.

Search before creating. If the same root cause exists, update that note. If a
large audit finds independent defects, create independent notes rather than a
registry table. Related notes may link to each other but never become a parent
tracker.

## Exact directory disposition

### Move the issue tree

Use Git-aware moves:

```text
docs/seon/orchestrator/issues/** -> docs/seon/issues/**
```

No issue evidence is deleted during the path move. Closed and stale notes are
normalized and moved under the new `archive/` in the same migration.

### Remove the leftover orchestrator directory

| Current file | Disposition | Reason |
|---|---|---|
| `docs/seon/orchestrator/active.md` | Delete after merging any unique entry link into `_dashboard.md`/active PRD. | Stale current-state projection with retired commands; the roadmap is the work ledger. |
| `docs/seon/orchestrator/prds.md` | Delete after confirming `_dashboard.md` links the active PRD. | A manually maintained PRD registry is a third documentation system and is already stale. |
| `docs/seon/orchestrator/config-coherence-audit-2026-06-28.md` | Git-move to `docs/prds/runtime-reliability/research/`. | Dated evidence, not present-tense global state. Normalize frontmatter to completed research. |
| `docs/seon/orchestrator/issues-audit-2026-06-28.md` | Git-move to `docs/prds/runtime-reliability/research/`. | Historical triage evidence superseded by this audit, still valuable. |
| `docs/seon/orchestrator/repo-rough-edges-2026-06-28.md` | Git-move to `docs/prds/runtime-reliability/research/`. | Dated onboarding evidence; current roadmap owns outstanding work. |

After these changes, `docs/seon/orchestrator/` should not exist. The role name
remains valid in `ORCHESTRATOR.md`; it is not a documentation namespace.

## Exact top-level issue triage

The migration should apply these dispositions, then regenerate the index.

### Retain and tighten as open

| Note | Required change |
|---|---|
| `acme-harness-agents-route-drift.md` | Add `severity: friction`; shorten fixed history into Resolution/Evidence. Keep open until an ACME cold/restart proof confirms the generic route listener and readiness behavior. Do not touch the separately owned ACME lane merely to close it. |
| `acme-no-sci-eval-seam.md` | Keep open/friction, but re-evaluate whether the Inspect/public agent path is the intended proof rather than adding a privileged parallel `/eval` API. Close as superseded if no direct seam is required. |
| `als-unify-tx-meta.md` | Keep open/cleanup. Current `seon.db.internal` still owns both `als-instance` and `agent-id-als`. Remove stale `seon.store.wire` wording. |
| `embedding-boot-entity-missing-2026-06-25.md` | Rename to `embedding-first-write-lookup-noise.md`; retain open/friction. Embeddings are now opt-in, but `current-hash-for` still strict-pulls a not-yet-existing lookup ref when enabled. |
| `eval-memory-safety.md` | Retain open and raise to blocker after deleting already-completed history. Runtime result slots are now bounded, but a single materializing query/pull can still exhaust the pod before post-processing. Acceptance must target the producing database boundary, not serialize-and-measure after allocation. |
| `eval-scratch-conn-no-commit.md` | Keep open/cleanup until the existing explicit-connection fixtures and production eval path falsify or reproduce it. Merge the relevant ambient-connection row from the dual registry here. |
| `narration-ghost-echo-not-neutralized.md` | Normalize to `status: open`, `severity: friction`; rename vocabulary only if the active problem still reproduces. Do not add more regex layers without proving the current reserved-marker owner is the right mechanism. |
| `parse-forms-entry-schema-and-bare-keys.md` | Keep open/cleanup but rewrite: entry keys were already namespaced by C20. The remaining question is one portable precise contract for the `.cljc`/Babashka parser boundary without adding a Malli dependency to the leaf parser. |
| `selfhost-cljs-test-is-thunk-resolution.md` | Keep open/friction until a current self-host probe confirms the thunk error. Standard-compiled tests are not proof of the self-host path. |
| `stale-reference-docs.md` | Keep open/cleanup. Both named files still exist and still contain deleted JVM/XTDB/Datalevin paths and terminology. Align them with current architecture or mark historical material explicitly. |

### Resolve and archive

| Note | Evidence to record |
|---|---|
| `ctx-install-live-tile-symbol-roundtrip.md` | `ctx-entities` now calls computed `decode-block`; commit `5b67f9b8` introduced the all-EDN-encoded-attribute decode. Verify one symbol-valued install round trip, then set resolved and archive. |
| `findings-renders-open-plan-as-fact.md` | Its own body records the structural lifecycle-status fix and focused/live proof. Normalize `completed -> resolved`, add severity, archive. |
| `node-test-untestable-context-system.md` | The managed full CLJS gate now completes 1,301 tests/6,159 assertions with zero failures/errors. Record the current clean-build proof and archive resolved. |
| `plan-frontier-hides-open-root-with-done-children.md` | Its own body records the rule fix and behavioral test. Normalize `completed -> resolved`, add severity, archive. |

### Supersede and archive because the owning source/mechanism is gone

These ten notes name deleted JVM application files:

- `coupling-graph-render.md`;
- `dead-web-namespace-viewer.md`;
- `dup-db-name-schema.md`;
- `dup-kondo-analysis.md`;
- `dup-namespace-schema.md`;
- `dup-parse-form-body.md`;
- `example-keywords-in-render-code.md`;
- `graph-missing-schema-index.md`;
- `maybe-in-session-schemas.md`; and
- `sse-keyword-namespace-mismatch.md`.

These five notes describe superseded tooling or test infrastructure:

- `hook-callgraph-review-context.md` — the automatic Gemini review queue and
  `seon.dev.review` path were deleted;
- `hook-error-hints.md` — its six nREPL/reload/review messages belong to the
  deleted hook, not the current Babashka edit hook;
- `supervisor-startup-race-audit-2026-06-25.md` — the Bash supervisor it audits
  was replaced by the Babashka process graph with PID/start-stamp/process-group
  tests;
- `test-coverage-audit-stale.md` — the referenced PRD is gone and the active
  test-impact/runtime audits replace it; and
- `test-suite-audit-2026-06-25.md` — its disabled graveyard and gym-era targets
  were deleted or ported; current test work belongs to the active Slice 4
  evidence.

Set each to `superseded`, add missing/accurate severity, append exact deletion
or replacement evidence, and Git-move to `archive/`. Do not delete the notes;
they explain why historical links existed.

### Dissolve the parallel dual-path registry

Set `dual-code-paths-registry.md` to `superseded`, add severity cleanup, record
the split map below, and archive it. Preserve its rows as historical evidence,
but remove `NEXT-ID` and any instruction to add new findings there.

Create ordinary notes only for live, independently actionable findings:

| Registry row | New/merged issue | Disposition |
|---|---|---|
| M1 | `als-unify-tx-meta.md` and `eval-scratch-conn-no-commit.md` | Merge only the still-current ambient-state evidence. Compiler state and one process-local database connection are legitimate runtime artifacts, not automatically defects. |
| M5b | `acme-harness-agents-route-drift.md` | Generic route-cache invalidation is now attached through `seon.web.router/attach!`; ACME proof remains the note's open edge. |
| C62 residual | `agent-tool-unknown-key-acceptance.md` | New cleanup issue: audit other open `my.*` request schemas using the schema-derived guard pattern proven in `my.plan`; no hand-maintained key lists. |
| C49 | no new note | Resolved by `seon-skills` authority plus generated adapters; `bin/seon skills check` passes. |
| C61 | `hot-reload-spec-projection-stale.md` | New blocker: a schema metadata edit must update database program facts before re-instrumenting, without a reset. This overlaps the active lifecycle slice and needs a direct hot-reload falsification. |
| C60 | `dev-eval-top-frame-misclassified-core.md` | New friction issue, retaining the observed crash and the safety tradeoff. Do not weaken core-fault classification without a repeatable driver. |
| C59 | no new note | Current exact Promise-chain and `await` regressions pass. Preserve the tests; mark the old observation resolved in the registry archive. |
| C57, C22, C8 | no new notes | Their JVM wire/db/compliance source paths were deleted. Superseded. |
| C53 | `duplicate-candidate-ranking.md` | New cleanup issue: determine whether retrieval and repair truly need distinct recall bands; if yes, share distance/ranking mechanics and document the two policies. |
| C40 | `async-contract-instrumentation-gap.md` | New friction issue: structural async opt-outs remain unvalidated; keep the measured deferred policy and define the evidence threshold for a Promise-aware wrapper. |
| C23 | no new note | This is a durable injectable-key invariant, not a defect. Put the concise rule in the owning instrumentation authority/tests. |
| C25 | `render-twin-runs-function-twice.md` | New friction/performance issue, owned by the general render-unit engine. One frozen-database derivation should feed both AI and HTML twins where inputs match. |
| C26 | `database-query-tuple-shape-legibility.md` | New friction issue. Solve through discoverable schema/examples or result rendering, not a prose wall or context-specific test. |
| C29 | `surface-recency-recomputed.md` | New cleanup/performance issue using current surface vocabulary. Measure and fold into active render-unit reuse rather than adding a standalone cache. |

The remaining registry rows already carry closure commits or explicitly
document legitimate mechanisms. They stay only in the archived registry as
historical evidence, not open work.

### Add the current untracked failure

Create `changed-test-manifest-does-not-converge.md` as `status: open`,
`severity: friction`, tagged testing/agent. Evidence is the three current hook
advisories that the managed Shadow test manifest failed to match source within
30 seconds. Acceptance:

- a normal `.clj`, `.cljs`, and `.cljc` edit reaches one bounded selection;
- stale/missing manifests widen honestly or report the actual watcher/build
  fault without waiting 30 seconds per edit;
- the full log and EDN report retain the cause; and
- passing/failing tests remain advisory.

Close this note in the same commit that completes the active unified hook unit
if the live Claude and Codex proofs pass.

## Reference update map

Update all active and historical references mechanically so no obsolete path
looks authoritative:

- `ORCHESTRATOR.md`: new issue path, legal lifecycle/severity, startup protocol;
- root `AGENTS.md`: concise universal reporting obligation and new path;
- `docs/seon/concepts/namespace-stewardship.md`: new path;
- `bin/issues-index`: new directory plus validation/check mode;
- `.codex/agents/seon-verifier.toml` and `.claude/agents/seon-verifier.md`: new
  path and legal checks;
- `docs/seon/vision/**`: `[[orchestrator/issues/x]]` becomes
  `[[issues/x]]` for open notes or `[[issues/archive/x]]` for closed notes;
- active and historical PRD references: absolute old issue paths become the
  new open/archive paths;
- `docs/seon/vision/index.md`: replace `[[orchestrator/active]]` with the
  active runtime-reliability roadmap;
- historical archived issue self-links to `issues-audit-2026-06-28.md`: point
  at its new PRD research location; and
- `bin/issues-index` header/comments: say `docs/seon/issues`, never
  `orchestrator`.

Aim for zero occurrences of `docs/seon/orchestrator`,
`docs/seon/orchestrator/issues`, and `[[orchestrator/issues` after the move.
Historical prose may say “orchestrator” as a role; only the obsolete path is
forbidden.

## Startup triage protocol

This belongs in `ORCHESTRATOR.md`, not duplicated in every localized file.

### Trigger

Once per new top-level orchestrator session, after reading root instructions,
the architecture map, and the active PRD roadmap, launch one bounded issue
triage subagent. Do not launch it again after context compaction; keep the fact
that it ran in the active plan. Subagents never launch triage agents.

### Subagent scope

Give the one agent the complete issue authority, current architecture domain,
active PRD roadmap, and current user request. It may:

- inspect all open notes and the source paths they cite;
- use Git/source existence, focused existing tests, and read-only live checks;
- merge duplicate notes and move genuinely resolved/superseded notes only when
  evidence is conclusive;
- update stale acceptance/file references; and
- regenerate the derived index.

It must not:

- create a dated triage report or another backlog;
- run the complete test suite merely to classify notes;
- modify production source;
- touch ACME or another owned lane;
- mark an issue closed from prose alone; or
- insert issue work ahead of the user's request unless it is a demonstrated
  correctness, process-safety, or liveness blocker.

Bound the task by one agent, one turn, and the existing open issue set. It
returns:

1. blockers that must interrupt current work;
2. at most three adjacent fixes that reuse work already requested;
3. stale/duplicate dispositions it changed with evidence; and
4. unresolved notes whose evidence is insufficient.

### Orchestrator action

The top-level orchestrator reviews and commits issue-only triage changes as a
small coherent commit. It tells the user about blockers and concise adjacent
opportunities; it does not silently expand scope. An unchanged issue set is a
valid result and should cause no file churn.

## Agent and subagent reporting protocol

Add this concise universal rule to root `AGENTS.md`:

> When you discover a bug, code smell, duplicate implementation, stale/broken
> test, unsafe edge, or documentation mismatch, report it to the agent that
> launched you and search `docs/seon/issues/` for the root cause. Create or
> update one issue note before returning. If you fix it in the same unit, close
> and archive the note with commit plus behavioral/live proof; otherwise leave
> it open with current evidence, owner, and acceptance criteria. Never add a row
> to a private registry or leave the finding only in chat.

The parent receives the issue path in the agent's final report. This does not
authorize unrelated production edits. The issue note is the durable handoff;
the active PRD remains the implementation order.

For shared-tree safety, an implementation agent creating a new note uses a
descriptive unique slug and edits no existing issue note unless its task owns
that same root cause. The top-level orchestrator resolves any duplicate during
triage.

## `bin/issues-index` strengthening

Keep one script and improve it in place. It should:

- accept `--check` to validate without writing and exit nonzero on drift;
- require every top-level issue note to have `type: issue`, `status: open`, a
  legal severity, and an `issue` tag;
- require every archived note to have `type: issue`, status resolved or
  superseded, legal severity, and an `issue` tag;
- reject an open note in `archive/` or a closed note at top level;
- reject duplicate filenames/H1 titles and missing H1s;
- generate links from actual filenames, then compare the expected index with
  disk in check mode; and
- write atomically only in normal regeneration mode.

Do not make `index.md` another authority and do not add issue state to the
database. The issue notes are design/work evidence; their index is a cheap
derived developer projection.

## Ordered implementation

1. Commit this audit and coordinate with the instruction/symlink work already
   editing root and localized `AGENTS.md` files.
2. Add the concise root reporting rule and the top-level startup protocol.
3. Git-move the issue tree to `docs/seon/issues/`; add localized `AGENTS.md`.
4. Normalize frontmatter and apply the exact retain/resolve/supersede table.
5. Split the eight live registry findings into ordinary notes, archive the
   registry, and add the current changed-test manifest issue.
6. Strengthen `bin/issues-index`, regenerate `index.md`, and update focused
   script tests through the existing operator runner.
7. Move the three dated audits, remove `active.md`/`prds.md`, and delete the now
   empty `docs/seon/orchestrator/` directory.
8. Update all code/docs/verifier references, choosing open versus archive link
   targets from actual disposition.
9. Run mechanical checks, Markdown validation, and one read-only startup-triage
   dry run. Commit the migration as one coherent documentation/tooling unit.

## Mechanical acceptance checks

The implementation is complete only when all of these hold:

```bash
test -d docs/seon/issues
test ! -e docs/seon/orchestrator

find docs/seon/issues -maxdepth 1 -type f -name '*.md'
find docs/seon/issues/archive -type f -name '*.md'

bin/issues-index --check
bin/issues-index
git diff --exit-code -- docs/seon/issues/index.md

rg -n 'docs/seon/orchestrator|\[\[orchestrator/issues' .
rg -n 'status: (active|completed|verified|in-progress|closed|archived)' docs/seon/issues
rg -L '^severity: (blocker|friction|cleanup)$' docs/seon/issues/*.md docs/seon/issues/archive/*.md

bin/seon test operator
```

Expected results:

- the old-path searches return no matches;
- only `README.md`, `AGENTS.md`, and generated `index.md` are exempt from the
  issue-note status/severity shape;
- a second index generation produces no diff;
- Markdown validation passes for every moved/edited document and all wikilinks
  resolve to actual open/archive targets;
- the operator test pins valid/invalid frontmatter, open/archive placement,
  stale-index check mode, and atomic generation;
- `git diff --summary` shows issue/audit renames rather than delete-and-recreate
  pairs; and
- a fresh top-level session launches one triage subagent, receives its bounded
  prioritized result, and continues the user's requested task without a second
  tracker or silent scope expansion.

## Why this is the smallest robust system

The database and PRD roadmap already answer “what is running?” and “what work is
ordered now?” Issues answer only “what verified problem must not be forgotten?”
Putting them at `docs/seon/issues/` removes the false implication that they
belong to one client or orchestration implementation. One note per problem,
one generated index, and one bounded startup audit are enough. The archived
evidence remains searchable through Git and links, while current work stops
paying the cost of stale files, private registry IDs, and duplicated status
systems.
