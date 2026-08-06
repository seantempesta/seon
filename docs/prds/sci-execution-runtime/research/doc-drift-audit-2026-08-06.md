---
type: research
status: complete
tags: [research, documentation, architecture, skills]
---

# Independent documentation drift audit — 2026-08-06

## Verdict

The 2026-08-05 source is substantially ahead of its maintained documentation.
Four drift clusters can directly cause an agent to rebuild a rejected system:

1. mandatory skills teach the deleted shared-context/session-image model and
   deleted schema/source paths;
2. executable PRD briefs retain `status: active` after their designs were
   replaced;
3. the transfer prompt presents an August 2 handoff as current; and
4. architecture still grants root elevated callability, limits callability to
   public functions, says the base forks per run, and treats shipped turn-free
   maintenance as an unbuilt message path.

The live code consistently implements the surviving model: one program-only
base, a fresh fork per turn, one selected agent's `:seon.def/*` desk, every
indexed function callable, private/public as render curation, direct
turn-free scheduled maintenance receipts, `resources/seon/schemas/`,
`seon.db`, `seon.effect`, and process claims under `data/operator/claims/`.

Four blocker issue clusters were filed:

- [mandatory skills regressed to deleted runtime semantics](../../../seon/issues/mandatory-skills-regressed-to-deleted-runtime-semantics.md);
- [architecture retains rejected 2026-08-05 runtime contracts](../../../seon/issues/architecture-retains-rejected-2026-08-05-runtime-contracts.md);
- [active PRD briefs present superseded designs as current](../../../seon/issues/active-prd-briefs-present-superseded-designs-as-current.md); and
- [the transfer prompt orients agents to the deleted August 2 system](../../../seon/issues/transfer-prompt-orients-agents-to-the-deleted-august-2-system.md).

## Read and audit scope

I read the following authorities in full before classifying drift:

- root `AGENTS.md`, including the 2026-08-05 deletion, vocabulary, open-map,
  callability, operator, testing, and documentation laws;
- all three 2026-08-05 working-edge blocks at the top of
  `docs/prds/sci-execution-runtime/plan/unsettled.md`;
- all three **Rulings 2026-08-05** batches in
  `docs/prds/sci-execution-runtime/plan/README.md`;
- every file under `docs/seon/architecture/`;
- `docs/prds/sci-execution-runtime/AGENTS.md`, its compatibility roadmap, and
  the non-exempt `plan/` briefs;
- all eleven `.agents/skills/*/SKILL.md` files and the directly controlling
  `data-oriented-clojure/references/program-state.md` reference;
- `docs/conventions.md` and `docs/TRANSFER_PROMPT.md`; and
- `docs/prds/readme.md` plus the authority/frontmatter and Git history needed
  for the folder inventory.

The required historical exemptions were applied. Ruling records, research
archives, and prose that says “old spelling → new spelling” are not drift merely
because they preserve the old spelling. Findings below concern current
architecture, skills, runbooks, executable active-status briefs, conventions,
or orientation.

## Current-source baseline

| Contract | Current evidence |
|---|---|
| Turn context and desk | `src/seon/sci/eval.clj:1309-1367` forks the base and reads only the selected agent's `:seon.def/*`; `src/seon/cluster/loop.clj:1493-1514,1643-1658` creates that fork per turn and settles desk rows with the terminal receipt. |
| Desk declaration | `resources/seon/schemas/seon.def.edn:1-45` declares the current family. |
| Callability and privacy | `src/seon/sci/eval.clj:803-859` installs indexed functions regardless of `:seon.fn/private?`; `src/seon/render/ns.clj:289-314` filters private functions only for foreign namespace rendering. |
| Schema population | `src/seon/schema/edn.clj:1-15,49-51` loads the directory-backed `resources/seon/schemas/` population. |
| Database and effects | `src/seon/db.clj:1-14` is the one Datahike namespace for reads and writes; `src/seon/effect.clj:1-19` is the one system-side capability request owner. |
| Maintenance | `src/seon/schedule.clj:1-8,34-98,282-360,534-616` seeds tasks, claims receipt facts, and invokes handlers directly; `src/seon/cluster.clj:1371-1389` installs root's portfolio. |
| Exclusive sweep | `src/seon/operator.clj:744-764` exposes the control-lock-protected collection owner; the working edge records the live dry-run and collection proof. |
| Process-record authority | `resources/seon/operator/state.clj:180-199` derives root and process claim paths under `data/operator/claims/`; `script/seon/fresh_operator.clj:131-178` reads and writes through those paths. |
| Deleted quarry | root `AGENTS.md:250-254` says `src-old/` and `test-old/` are absent and Git history is the archive. |

## Ranked findings

### D1 — Blocker: mandatory skills teach deleted runtime state

An agent is required to load these skills before touching the corresponding
owners. Their blast radius makes the false instructions more severe than an
ordinary stale paragraph.

| Current authority | What it claims | Current code/ruling |
|---|---|---|
| `.agents/skills/seon-flow-architecture/SKILL.md:74-86,478-492` | Boot restores a durable session image into one shared context; supplied context is mutated across evaluations. | `src/seon/sci/eval.clj:1309-1367` creates a fresh turn fork and rehydrates one desk; `src/seon/cluster/loop.clj:1493-1514` uses it per turn. |
| `.agents/skills/repl/SKILL.md:15-21,50-61` | Shared context is current; a fresh per-run fork is an unbuilt target; cross-run state is a session image. | The per-turn fork and desk are current source, not target. |
| `.agents/skills/datahike/SKILL.md:256-259,633-634` | Program state ends in a session image and is tested by `session_image_test.clj`. | `resources/seon/schemas/seon.def.edn:1-45` and `test/seon/sci/desk_test.clj` are the surviving owners; the named test is absent. |
| `.agents/skills/data-modeling/SKILL.md:243-247` | The fourth program-state boundary is a session image. | Its own linked `program-state.md:27-62` correctly says per-turn fork plus desk. |
| `.agents/skills/data-modeling/SKILL.md:45-54`; `.agents/skills/datahike/SKILL.md:221-234`; `.agents/skills/llm-providers/SKILL.md:17-29,94-98,145-148`; `.agents/skills/seon-context-config/SKILL.md:2-27` | Schema work begins in `resources/seon/schema.edn`. | `src/seon/schema/edn.clj:49-51` names `seon/schemas`; the monolith is absent. |
| `.agents/skills/clojurescript/SKILL.md:3,13-14,48-61`; `.agents/skills/data-oriented-clojure/SKILL.md:19-22`; `.agents/skills/seon-context-config/SKILL.md:19-20` | Readers should open `src-old/` as quarry. | Root `AGENTS.md:250-254` requires `git show`/`git log`; the directories are absent. |

The datahike skill also pins `c152727...` at lines 39-43 while the checked-out
gitlink is `56f1c62105b7087f0cac13162f9fd54b1690986e`. This recurrence is
already adjacent to the open pin-drift issue, but the deleted runtime guidance
requires its own blocker.

### D2 — Blocker: active-status plan briefs are executable archaeology

`docs/prds/sci-execution-runtime/AGENTS.md:24-27` says old briefs are
archaeology, but the files themselves still declare active status. A direct
reader gets the opposite signal.

| Current authority | What it claims | Current code/ruling |
|---|---|---|
| `plan/seondb-facade-contract-spec.md:1-13,67-103` | Active read facade; writes remain with another owner. | `src/seon/db.clj:1-14` owns reads and writes; “facade” is explicitly rejected vocabulary. |
| `plan/stateless-resume-design-2026-08-01.md:1-20,84-106` | Active session-image implementation plan. | Desk facts and per-turn rehydration replaced it. |
| `plan/repl-session-context-2026-08-01.md:1-14,32-55` | Active shared REPL-session design with a stored prompt and old agent namespace convention. | The 2026-08-05 desk and namespace-page rulings supersede those mechanics. |
| `plan/refactor-wave-2026-08-01.md:120,348,602,732-754` | Active implementation inventory around `install-session-image!` and shared-context isolation. | The named owner is deleted; `fork-for-turn` and desk settlement survive. |
| `plan/ambient-injection-prd-2026-08-05-r2-draft.md:1-24` | Both ruled/graduated and unruled/draft. | The 2026-08-05 ruling batch settles the r2 seam; status and preamble disagree. |

The failure is lifecycle, not forbidden historical spelling. These documents
may retain every dated old name once they locally fail closed as completed or
superseded evidence.

### D3 — Blocker: the transfer prompt declares the August 2 system current

`docs/TRANSFER_PROMPT.md` is an entry point named by both root and localized
instructions, so its dated handoff outranks later truths for a new reader.

| Doc location | Current claim | Current code/ruling |
|---|---|---|
| `docs/TRANSFER_PROMPT.md:35-43,133-146` | `src-old/` remains readable and should be opened. | Root `AGENTS.md:250-254`: Git history only. |
| `docs/TRANSFER_PROMPT.md:161-170` | Bare `bin/test` is the full suite. | `docs/conventions.md:492-505`: bare is fast/non-long; `--full` is complete. |
| `docs/TRANSFER_PROMPT.md:201-224` | August 2 addendum/rulings are current; one shared context and session restoration are built truth. | `plan/unsettled.md:19-60` records the 2026-08-05 desk/per-turn fork landing. |
| `docs/TRANSFER_PROMPT.md:247-249` | One `resources/seon/schema.edn`; family files and globbing are gone. | `src/seon/schema/edn.clj:49-51` loads `resources/seon/schemas/`. |
| `docs/TRANSFER_PROMPT.md:299-312` | Agents use `store/transact!`; `seon.effect` does not exist. | `src/seon/db.clj:1-14` and `src/seon/effect.clj:1-19` are the current owners. |

`docs/conventions.md:13-15,621-623` repeats only the deleted checkout-directory
claim. Its test commands, open-map law, schema directory, and `seon.db`
guidance are otherwise current.

### D4 — Blocker: architecture preserves rejected access semantics

- `docs/seon/architecture/architecture.md:196-205` calls root's access an
  “elevated grant.” Its own lines 529-534 correctly say no grants exist.
- `docs/seon/architecture/toolkit.md:49-54` says public functions are callable
  by definition and derives a public-only surface. Its own lines 63-69 and
  `src/seon/sci/eval.clj:803-859` say every indexed function is callable.
- `docs/seon/architecture/architecture.md:196-213` defines `/agent/{id}` as the
  primary “view,” even though the canonical current abstraction is the
  namespace page and agent routes are aliases. `src/seon/render/route.clj` is
  the route authority; root `AGENTS.md:735-738` states the canonical routes.

These claims can lead directly to a grant wall, public-call allowlist, or
agent-page renderer—the exact rejected mechanisms ruling #20 and the namespace
page ruling removed.

### D5 — High: architecture calls shipped turn-free maintenance an unbuilt message path

- `docs/seon/architecture/agent-runtime.md:235-247` labels scheduling target and
  says a fire commits a message, with scheduled tasks waiting for procs to land.
- `docs/seon/architecture/data-model.md:106-109` says no durable schedule
  identity exists, while lines 171-173 label the identities target-only.
- Current `src/seon/schedule.clj:1-8,34-98,282-360,534-616` and
  `src/seon/cluster.clj:1371-1389` implement declared schedules, tasks, fires,
  maintenance requests/receipts, direct handler invocation, and root seeding.

The target architecture should describe the surviving direct receipt path,
not the rejected ordinary-message design.

### D6 — High: architecture says the SCI base forks per run

`docs/seon/architecture/laws.md:93-100` says the base forks once per run. The
correct target is already stated in `docs/seon/architecture/agent-runtime.md:18-22,164-193`:
fresh per-turn fork plus selected agent desk. Live evidence is
`src/seon/cluster/loop.clj:1493-1514`.

### D7 — High: the PRD authority map does not account for nine active roots

`docs/prds/readme.md:7-18` lists the SCI runtime, package-capabilities,
source-cleanup, generate-code, and archive. It omits eight other top-level
folders whose sole README says `status: active`. The current working edge links
only `error-model` among those roots (`plan/unsettled.md:79-86`). The result is
an unqueryable lifecycle: “active” can mean dependency-ready, queued successor,
completed implementation diary, or abandoned proposal.

This is not evidence that every omitted PRD should be deleted. It is evidence
that each needs an explicit lifecycle decision and that completed/stale roots
should move under the existing archive boundary.

### D8 — High: current architecture repeats the deleted schema monolith

- `docs/seon/architecture/context.md:417-440` ends otherwise accurate config
  guidance at `resources/seon/schema.edn`.
- `docs/seon/architecture/library-grounding.md:31-40` names the monolith in
  three current owner rows.
- `docs/prds/sci-execution-runtime/AGENTS.md:33-43` sends every runtime reader
  to the same absent resource.

Current loader evidence is `src/seon/schema/edn.clj:1-15,49-51`.

### D9 — Medium: maintained dependency pins and seams are stale

`docs/seon/architecture/library-grounding.md:14-27` disagrees with four current
gitlinks:

| Dependency | Document | Checked-out gitlink |
|---|---|---|
| Datahike | `256b714...` | `56f1c62105b7087f0cac13162f9fd54b1690986e` |
| Konserve | `737697d...` | `07377c27c8288b7484f0aa7b82e8158b415985be` |
| SCI | `a27e2c0...` | `2db3358cba913b6fbbe49c7b5b34d7ac72715924` |
| Datastar | `1cef624...` | `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` |

Lines 35-40 also describe shared-context evaluation and monolithic-schema
owners. Core.async, Malli, clj-kondo, and Reitit pins were verified accurate.

### D10 — Medium: toolkit target omits `my.branch` and outgrows collection status

- `docs/seon/architecture/architecture.md:624-632` and
  `docs/seon/architecture/toolkit.md:71-85` omit ruled `[TARGET] my.branch`.
  Root `AGENTS.md:684` defines its verbs and boundary.
- `docs/seon/architecture/toolkit.md:299-312` says the target claims no garbage
  collection. Current `src/seon/operator.clj:744-764` and the 2026-08-05
  exclusive-sweep ruling establish collection as the one surviving owner.

`my.branch` is not yet present under `src/my/`, so the correction must remain
explicitly target-marked rather than pretending it shipped.

### D11 — Medium: render producer vocabulary slips back to the value schema

`docs/seon/architecture/architecture.md:92-100` says a function returns
`:seon.render/hiccup` as a render twin. Current
`resources/seon/schemas/seon.render.edn:5-21,44-50` declares producers under
`:seon.render/ai` and `:seon.render/html`; Hiccup is the HTML value schema, not a
third producer arm.

## Architecture file-by-file calibration

| File | Result |
|---|---|
| `architecture.md` | Drift: elevated grant, agent-view primacy, Hiccup producer arm, missing `my.branch`. Accurate: lines 529-534 state ruling #20 exactly. |
| `agent-runtime.md` | Drift: maintenance target/message path and `session restore` source wording. Accurate: lines 18-22 and 164-193 are the best current desk/context account. |
| `context.md` | Drift: monolithic schema path only. Accurate: root overlay is explicitly context, never a grant; config acquisition semantics match current owners. |
| `data-model.md` | Drift: denies shipped schedule identities. Accurate: open maps and `:seon.def/key` desk identity. |
| `laws.md` | Drift: per-run fork. The channel transport and consumer-fit laws remain accurate. |
| `library-grounding.md` | Drift: four pins, schema monolith, shared-eval seam. The core.async/Malli/clj-kondo/Reitit pins and route source map are accurate. |
| `observability.md` | Verified accurate for the audited rename/runtime boundary; “durable session definitions” is imprecise but does not specify the deleted mechanism. |
| `toolkit.md` | Drift: public-only callability, missing `my.branch`, old collection status, `seon.eval` owner wording. Accurate: lines 63-69 state unrestricted callability and lines 149-165 mostly state the current `seon.db` contract. |
| `ui.md` | Verified accurate on canonical namespace pages, agent aliases, current/target package distinction, and shared render projections. Historical “corpus” wording is low-risk vocabulary cleanup, not a rejected mechanism. |

## Skill-by-skill calibration

| Skill | Result |
|---|---|
| `clojure-testing` | Accurate for the per-test database fixture, focused runner, and open-map/generator law. |
| `clojurescript` | Stale: requires an absent `src-old/` checkout instead of Git-history quarry. Its “CLJ-only, do not port” boundary is accurate. |
| `data-modeling` | Stale schema resource and session-image heading; its data-oriented modeling rules remain accurate. |
| `data-oriented-clojure` | Stale quarry path only; the core immutable/EAV/open-map guidance is accurate. |
| `datahike` | Stale gitlink, monolithic loader, in-flight migration claim, session-image owner/test. Its `seon.db` read/write contract and identity-only admission guidance are accurate. |
| `datastar-web-ui` | Accurate current/target separation and route/render ownership. |
| `llm-providers` | Current provider-specific guidance may still be useful, but all monolithic-schema citations and several line ranges are stale; re-ground before use. |
| `repl` | Blocker: current/target context lifetime is reversed. Reader-surface and hot-reload distinctions remain accurate. |
| `seon-context-config` | Stale monolith, `.cljc` owners, session-value settlement, and old quarry path. Sparse overlay and acquisition-boundary principles remain accurate. |
| `seon-flow-architecture` | Blocker: shared context/session image and unbuilt maintenance surface. Workload and Flow transport sections remain largely accurate. |
| `ui-canvas` | Accurate boundary between canvas/control design and the existing Datastar web UI. |

## PRD folder inventory

### Classification method

“Active link” below means a link from the current top working edge or the
active PRD authority map—not an old ruling block or dated research page.
Git dates are the most recent commit touching the folder as of this audit.
Structural `research/`, `plan/`, `specs/`, and spike directories inherit their
owning PRD's classification; they are enumerated separately so every directory
under `docs/prds/` is accounted for.

Recommended archive convention: move a complete or stale PRD intact to
`docs/prds/archive/<original-folder>/`, preserve relative research links, add or
retain an inert localized `AGENTS.md`, and give the root authority/roadmap
`status: archived`. Dated research keeps its original frontmatter, matching
`docs/prds/readme.md:20-45` and the existing `docs/seon/issues/archive/`
lifecycle.

### Non-archived PRD roots

| Folder | Last touch | Active inbound | Classification | Recommendation |
|---|---:|---|---|---|
| `docs/prds/accretion-testing/` | 2026-08-03 | None from current edge | **ACTIVE, queued but underlinked**; green-to-install remains a live goal, but candidate-context language predates the current SCI pin. | Keep; refresh evidence and add to the authority map if still queued. |
| `docs/prds/agent-bootstrap/` | 2026-08-03 | None | **STALE**; the append-only session/readline design predates the 08-05 desk and current grader→bootstrap design session. | Archive after extracting any still-ruled form-series decisions into the new bootstrap brief. |
| `docs/prds/background-work/` | 2026-08-03 | None from current edge; older working-edge blocks call it approved | **ACTIVE, queued**; effect/receipt work remains relevant and does not depend on the deleted pod. | Keep, but add an explicit current dependency edge or mark paused. |
| `docs/prds/error-model/` | 2026-08-03 | `plan/unsettled.md:85` | **ACTIVE**; explicitly next behind desk/P17 and grounded in the split schema layout. | Keep. |
| `docs/prds/generate-code/` | 2026-08-02 | Historical link only | **COMPLETE/SUPERSEDED**; both runbook and roadmap already say superseded. | Archive now; current historical inbound already labels it quarry. |
| `docs/prds/in-server-tests/` | 2026-08-03 | None | **ACTIVE, orphaned successor**; its one-runner goal remains plausible, but no current ledger names it. | Keep only if explicitly queued; otherwise archive until re-carved from current source. |
| `docs/prds/mcp-surface/` | 2026-08-05 | None from current edge | **COMPLETE/SUPERSEDED**; README records Tier 3B landed, while remaining defects have their own issue owners; shared-context statements are stale. | Archive; leave follow-up work in issues/new PRDs. |
| `docs/prds/operational-events/` | 2026-08-03 | None from current edge; links to error/operator PRDs | **ACTIVE, queued**; intended event/gauge work is not disproved, but it depends on a completed operator brief. | Keep only with a current dependency edge; refresh its dependencies. |
| `docs/prds/operator-integration/` | 2026-08-03 | Only from operational-events | **COMPLETE/SUPERSEDED**; `seon.operator` exists and 08-05 claims/maintenance/refork work has outgrown the implementation plan. | Archive; operational-events should link current source and rulings instead. |
| `docs/prds/package-capabilities/` | 2026-08-02 | `docs/prds/readme.md:15-16` calls it successor | **STALE**; roadmap still requires Bun execution children and an open pod-host choice (`roadmap.md:37-55`). | Archive this roadmap and carve a CLJ/JVM successor if the capability program remains wanted. |
| `docs/prds/sci-execution-runtime/` | 2026-08-05 | Root instructions, authority map, architecture, and current edge | **ACTIVE**; sole program-order authority. | Keep. Clean lifecycle within `plan/`; do not archive the program. |
| `docs/prds/source-cleanup/` | 2026-08-05 | Historical only | **COMPLETE/SUPERSEDED**; runbook and roadmap fail closed, but seven sibling PRDs still say active. | Archive the entire folder intact; archive location resolves the sibling-frontmatter ambiguity. |
| `docs/prds/archive/` | 2026-08-02 | `docs/prds/readme.md:18` | **ARCHIVE AUTHORITY**. | Keep as the single PRD archive destination. |

The frontmatter that most clearly lies is not dated research inside an archive;
it is the active sibling material under `source-cleanup/`, active completed
briefs under SCI `plan/`, and the active pod-dependent package roadmap.

### Existing archived PRD roots

All entries below are already correctly fenced by location and an archived
localized runbook where present. Active/draft status inside dated research is
historical metadata and is not a current-authority lie.

| Archived folder | Last touch | Classification / action |
|---|---:|---|
| `_example-feature/` | 2026-07-21 | Archived template; keep. |
| `agent-canvas-interaction/` | 2026-08-02 | Complete/superseded; keep archived. |
| `agent-ctx/` | 2026-08-02 | Complete/superseded; keep archived. |
| `agent-fsm/` | 2026-08-02 | Complete/superseded; keep archived. |
| `agent-runtime/` | 2026-07-21 | Complete/superseded; keep archived. |
| `agent-runtime-correctness/` | 2026-08-02 | Complete/superseded; keep archived. |
| `agentic-tool-refinement/` | 2026-08-02 | Complete/superseded; keep archived. |
| `bun-native-runtime-simplification/` | 2026-08-02 | Complete/superseded; keep archived. |
| `database-authority-mesh/` | 2026-08-02 | Complete/superseded; keep archived. |
| `database-browser/` | 2026-08-02 | Complete/superseded; keep archived. |
| `database-lifecycle-recovery/` | 2026-08-02 | Complete/superseded; keep archived. |
| `diffusion-dynamic-context/` | 2026-08-02 | Complete/superseded; keep archived. |
| `embeddings/` | 2026-07-21 | Complete/superseded; keep archived. |
| `frozen-turn-inputs/` | 2026-08-02 | Complete/superseded; keep archived. |
| `independent-downstream-distribution/` | 2026-08-02 | Complete/superseded; keep archived. |
| `inspect-autocomplete-evidence/` | 2026-08-02 | Complete/superseded; keep archived. |
| `local-performance-graduation/` | 2026-08-02 | Complete/superseded; keep archived. |
| `namespace-ui/` | 2026-07-21 | Complete/superseded; keep archived. |
| `reactive-render-units/` | 2026-08-02 | Complete/superseded; keep archived. |
| `refinement/` | 2026-07-21 | Complete/superseded; keep archived. |
| `repl-autosuggest/` | 2026-08-02 | Complete/superseded; keep archived. |
| `root-workspace-sessions/` | 2026-08-02 | Complete/superseded; keep archived. |
| `runtime-reliability/` | 2026-08-02 | Complete/superseded; keep archived. |
| `unified-flow/` | 2026-07-21 | Complete/superseded; keep archived. |

No existing archived root needs to move again. No current working-edge block
links one as an implementation authority.

### Structural descendant directory accounting

These are not independent PRDs. Each listed directory inherits the verdict and
last-touch evidence of the owning root above:

| Owner | Structural descendants, relative to owner | Inherited classification |
|---|---|---|
| `generate-code/` | `research/` | Complete/superseded. |
| `package-capabilities/` | `research/` | Stale successor evidence. |
| `sci-execution-runtime/` | `plan/`; `plan/reference/`; `research/`; `research/context-walk/`; `research/context-walk/s0-baseline/`; `research/context-walk/s1-shadow/`; `research/scripts/`; `research/scripts/context-walk-falsification/`; `research/scripts/flash-quality-2026-08-01/`; `research/scripts/perf-benchmark-2026-07-31/`; `research/scripts/transact-throughput-2026-07-31/`; `research/visual-qa-2026-07-31/`; `specs/` | Active program; `plan/reference/` and dated research are historical evidence within it. |
| `source-cleanup/` | `research/` | Complete/superseded. |
| `archive/_example-feature/` | `research/` | Archived. |
| `archive/agent-canvas-interaction/` | `research/` | Archived. |
| `archive/agent-ctx/` | `research/` | Archived. |
| `archive/agent-fsm/` | `archive/`; `research/`; `research/inspect-bridge-spike/` | Archived. |
| `archive/agent-runtime/` | `architecture/`; `archive/`; `research/`; `sidecar-poc/`; `sidecar-spike/`; `spikes/`; `spikes/sci-interrupt/`; `spikes/sci-interrupt/src/`; `spikes/sci-interrupt/src/spike/` | Archived. |
| `archive/agent-runtime-correctness/` | `research/` | Archived. |
| `archive/agentic-tool-refinement/` | `research/` | Archived. |
| `archive/bun-native-runtime-simplification/` | `research/` | Archived. |
| `archive/database-authority-mesh/` | `research/` | Archived. |
| `archive/database-browser/` | `research/` | Archived. |
| `archive/database-lifecycle-recovery/` | `research/` | Archived. |
| `archive/diffusion-dynamic-context/` | `archive/`; `flash-worker/`; `research/` | Archived. |
| `archive/independent-downstream-distribution/` | `research/` | Archived. |
| `archive/inspect-autocomplete-evidence/` | `research/` | Archived. |
| `archive/local-performance-graduation/` | `research/` | Archived. |
| `archive/namespace-ui/` | `archive/`; `archive/research/`; `research/` | Archived. |
| `archive/reactive-render-units/` | `research/` | Archived. |
| `archive/repl-autosuggest/` | `research/` | Archived. |
| `archive/root-workspace-sessions/` | `research/` | Archived. |
| `archive/runtime-reliability/` | `research/` | Archived. |

The archived roots not shown in this descendant table have no child
directories. Together with the root tables, this accounts for all 88
directories returned by `find docs/prds -type d`, including `docs/prds/`
itself.

## Calibration: current documentation that should not be swept away

This audit was not a spelling purge. The following authorities were checked
against source and are accurate enough to preserve:

- `docs/seon/architecture/agent-runtime.md:18-22,164-193` correctly explains
  base, per-turn fork, program publication, and agent desk.
- `docs/seon/architecture/architecture.md:529-534` and
  `docs/seon/architecture/toolkit.md:63-69` correctly state ruling #20; repairs
  should remove contradictory earlier paragraphs, not weaken these.
- `docs/seon/architecture/ui.md` correctly makes namespace pages canonical and
  agent routes aliases, and clearly labels revisioned render packages where
  current/target differs.
- `docs/seon/architecture/observability.md` remains aligned with captures,
  attempts, receipts, blobs, and database-backed forensics.
- `docs/conventions.md:149-176` preserves open-map accretion, and lines 384-419
  use the current `:seon.db/db`, `/database-value`, and `/connection` names.
- `docs/conventions.md:492-505` correctly distinguishes fast and full test
  tiers.
- `.agents/skills/data-oriented-clojure/references/program-state.md:27-62`
  correctly distinguishes the base, turn fork, and desk; it should be the
  shared replacement source for stale skill prose.
- `.agents/skills/clojure-testing/SKILL.md`,
  `.agents/skills/datastar-web-ui/SKILL.md`, and
  `.agents/skills/ui-canvas/SKILL.md` preserve the audited current boundaries.
- Current 2026-08-05 briefs—`agent-desk-and-checkout-prd-2026-08-05.md`,
  `exclusive-sweep-design-2026-08-05.md`,
  `operations-and-maintenance-spec-2026-08-05.md`, and
  `rename-pass-2026-08-05.md`—accurately retain old spellings as historical
  referents while specifying the surviving design.
- `docs/prds/sci-execution-runtime/roadmap.md:7-15` is an accurate compatibility
  pointer, not a second roadmap.

## Recommended repair order

1. Fix and independently verify the mandatory skills; they have the widest
   executable blast radius.
2. Remove the transfer prompt's dated current-state section and repair its
   quarry/test commands.
3. Correct architecture callability, per-turn context, maintenance identities,
   schema paths, dependency pins, render vocabulary, and `[TARGET] my.branch`.
4. Make the active `plan/` lifecycle fail closed, then move completed/stale
   top-level PRDs intact to `docs/prds/archive/`.
5. Re-run the complete Markdown/skill/link gates and independently verify that
   no maintained authority teaches session image, shared mutable turn context,
   monolithic schema resource, checkout quarry, read facade, grants, or
   message-delivered maintenance as current.
