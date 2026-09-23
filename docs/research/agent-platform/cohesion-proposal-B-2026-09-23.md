---
type: proposal
status: independent proposal; no authority changed
created: 2026-09-23
scope: agent-platform plan, architecture, instructions and skills
---

# Proposal B: one model, one plan, one owner per rule

## Inventory and diagnosis

At `refactor/agent-platform` on 2026-09-23, root `AGENTS.md` is 315 lines. The
plan directory has 16 real Markdown files / 5,903 lines: `README.md` 497,
`AGENTS.md` 94, `AGENTS-rewrite-2026-09-21.md` 16, and thirteen lane specs
totalling 5,296. `CLAUDE.md` is a symlink to plan `AGENTS.md`, not another 94
lines. The lane counts are A1 327, A2 275, B1 578, B1b 308, B2 418, B3
483, B4 719, C1 358, D1 438, Flow 745, namespace loop 344, projection sweep
221, and realities lifecycle 82. The eight top-level architecture Markdown files
total 1,177 lines (`README` 40, architecture 320, agent runtime 172,
clusters/branches/contexts 74, modeling guide 257, reference code 68, UI 103,
vocabulary 143). Twelve `SKILL.md` files total 1,778 lines; testing 183,
REPL 171, data-oriented Clojure 98, Datahike 505, modeling 212 are the largest.

`ls docs/research/agent-platform docs/prds docs/seon/issues | wc -l` returns
**556**. Direct file counts show the navigation problem more clearly: 110 files
/ 28,891 Markdown lines in `docs/research/agent-platform`, 161 files / 23,282
Markdown lines in `docs/prds/agent-platform/landing`, 24 files / 6,138 Markdown
lines in its older research directory, and 436 issue Markdown files / 26,047
lines. `docs/prds/context-generation/plan` has 25 files / 10,331 Markdown
lines and `docs/prds/steward-platform/plan` 31 / 27,848. Those are historical
inputs, not a reading list for the next platform agent. `docs/` also contains
architecture, reference, pod history, issues, archive, and three active PRD
trees. This proposal does not silently claim those other PRDs are superseded.

The failure is authority duplication, not merely length. Plan README §7 is a
second set of designs after §4; its final rulings at lines 426 and 430 change
publication and SCI context semantics without editing those sections. Flow's
last section (lines 741–745) proposes a row to append to README. B4 announces
it is superseded in part at line 3 and then retains the superseded lifecycle.
Projection sweep line 100 keeps rejected writer scopes below the winning
memoization ruling. The next reader must arbitrate among prose versions before
they can make a small change.

## The central model, verbatim target (twelve lines)

Put the following **once**, as plan `README.md` §1. Root `AGENTS.md` and the
architecture index link to it and contain only their own laws/mechanisms.

> Seon's program is data: declared functions, schemas, tests and renders are rows
> on a Datahike branch, with identity and dependencies that can be queried.
> An agent's environment is that program loaded into a retained SCI context from
> a shared pre-executed point; a new branch forks it cheaply, and accepted
> program changes advance it in place between evaluations.
> The agent changes and explores the program by evaluating forms at the REPL.
> Every admitted function has a complete schema and reaching tests. Those
> declarations make relevant context retrievable and failures precise.
> Each agent works on an isolated branch; a test uses the same branch, context
> and custody entrance for one body. A tested merge admits only program rows
> onto the shared head after explicit acceptance. Verified write-back makes
> accepted rows and source files equivalent.

The statement is a **target contract**, not an assertion that all operations
are installed. Directly below it, put a short table: already installed,
partially installed, unproved, with an evidence link and checked commit/date.
Define “shared pre-executed point” concretely: shared program content and loaded
Vars form the base; an SCI fork creates an independent context, while a change
to an existing context advances that retained context at an idle boundary.
Branch creation is a Datahike pointer, not context construction. Do not say
“every output” is transacted: evaluation returns live objects and shown text;
only admitted declarations become program rows.

## Target document set and exact moves

Keep the eight original domain specs as *owners*, not lane narratives. Rename
only if a separate mechanical link migration is justified; the existing names
are already linked widely. Every surviving spec begins with: current/target
status, one data flow, owning writer/readers, dependency seam, ordered cut,
proofs and retirement. The README owns cross-domain order and acceptance.

| Surviving file: one job | Merge into it; then remove the source | Target lines |
|---|---|---:|
| `plan/README.md`: the model, current gap, one ordered cut graph and complete-loop acceptance | Distill README §7 into its owners; keep only cross-owner gates and unresolved decisions in the relevant cut row | 240–300 |
| `plan/lane-a1-projection-carried.md`: projection/contract acquisition and Malli arming | `lane-projection-as-a-read-sweep.md` (221), including the site inventory and memoization ruling; reconcile with A2's database value constructors | 190–240 |
| `plan/lane-a2-datahike-one-answer.md`: transaction, read, retention and storage guarantees | Only its own A2 rulings currently duplicated in README §7 and modeling guide | 160–210 |
| `plan/lane-b1-one-publication-path.md`: one declaration/publication/adoption path and operator entry | `lane-b1b-operator-and-boot-rewrite.md` (308); absorb its eight drills and delete B1's displaced operator section | 220–280 |
| `plan/lane-b2-walk-flow-fork.md`: retained SCI execution, turn and Flow graph | `lane-flow-owns-running-machinery.md` (745), plus SCI/Flow portion of `lane-realities-one-lifecycle.md` (82); preserve N1–N4 proof cases and later order, delete duplicate scheduling prose | 230–300 |
| `plan/lane-b3-errors-tasks-dials.md`: error and task facts, config and effect policy | B3 rulings in README §7; do not create a second error-route spec from research | 190–250 |
| `plan/lane-b4-tests-in-process.md`: test as one-body agent, selection, recording, injected data worlds | Test portion of `lane-realities-one-lifecycle.md`; replace its own superseded capture/worker sections, retain actual-exit and per-member evidence proofs | 220–280 |
| `plan/lane-c1-wrapper-profiling.md`: inclusive observations from the armed wrapper | C1 rulings and automatic-finding decision currently in README §7 | 150–190 |
| `plan/lane-d1-isolation-merge-writeback.md`: branch, combined-program gate, accept and export | `lane-namespace-agents-first-loop.md` (344) as its first vertical acceptance case; D1 portion of realities lifecycle; keep the first loop distinct from full export acceptance inside the same spec | 220–280 |

This removes five stand-alone lane specs (B1b, Flow, namespace loop,
projection sweep, realities lifecycle): thirteen become eight. It also removes
`plan/AGENTS-rewrite-2026-09-21.md` (16), whose only job is saying another file
was activated. Retain `plan/AGENTS.md` as a short plan-local instruction file;
its `CLAUDE.md` symlink stays. Put the source of every moved requirement in the
commit's mapping table, not in the next agent's reading path.

Architecture should answer *how*, without carrying a second implementation
schedule. Keep `architecture.md` for the one system diagram/data flow and its
dependency constraints; fold `clusters-branches-contexts.md` (74) into its
branch/context section, then delete that file. Keep `agent-runtime.md` only for
turn, retained context, private objects and evaluation semantics; `ui.md` only
for render/delivery; `data-modeling-guide.md` only for choosing stored shapes;
`reference-code.md` only for pinned dependency seams; `vocabulary.md` only for
terms and explicit retired terms. Reduce architecture `README.md` to a small
index linking the plan's one model statement. Existing direct links to the
cluster page must move to the new architecture anchor in the same commit.

Keep the twelve skills as task-entry procedures with verified current commands
and `file:line` seams, not second PRDs. Specifically: `repl` must replace its
per-turn fork and hand reload recipe with the installed shared entrance and an
explicit gap; `clojure-testing` must resolve its fixture helper description
against B4's declared-input model without pretending a target has landed;
`data-oriented-clojure` must remove duplicate laws and its “fork-for-turn”
teaching, retaining concrete Clojure/Malli/Datahike choices. `datahike` and
`data-modeling` should cross-link at transaction vs shape decisions instead
of both restating deletion policy. `seon-flow-architecture` must point to
B2's single Flow design, not teach an independent graph plan. `codex-lanes`,
`clojurescript`, `datastar-web-ui`, `llm-providers`, `seon-context-config`, and
`ui-canvas` remain specialist procedures after each claim is checked. Audit
their reference subfiles as well; delete any that merely reproduce a retired
spec, preserve dependency source notes that are still necessary.

The 110 research files, 161 landings, 24 older platform research files, and
other PRD trees are **not** startup reading. Preserve dated measurements,
reviews, raw probes and owner quotes as history, but remove their links from
the start path unless one is the sole evidence for an active claim. A linked
research fact moves into the owner as a concise requirement plus evidence
pointer; duplicate explanations remain historical. Archive superseded PRD
plans only after a link-and-obligation audit, with context-generation and
steward-platform as a separately reviewed cut. Keep the issue index as the
ranked defect schedule; merge duplicate issue classes only with their evidence
and status accounted for. Do not declare all 436 issues obsolete by directory.

## Rule placement and known contradictions

There is no “ruling” appendix. Convert each owner ruling to an imperative or
invariant in the section that determines behavior, with an evidence link in a
small footnote or adjacent acceptance row. Owner quotes stay in dated history.
README §7 disappears. Its cross-owner conditions move to the README cut graph;
its local decisions move to A1–D1. An unresolved owner choice stays as a clearly
named gate at the exact dependent cut, with three priced choices only when it
is genuinely new. README §8 becomes a two-paragraph documentation maintenance
rule in plan `AGENTS.md`; dated issue audit totals remain in research.

Examples requiring explicit edits, not “see also” links:

| Conflict or duplication | Single destination and disposition |
|---|---|
| A1 title/architecture §5 and modeling guide §7 say projection is carried by constructors; README §7 line 416 rules a memoized function of the value | A1 defines `projection-of(value)` and its cache key/invalidation as current target; A2 owns constructor values. Rewrite architecture/modeling prose to match. Preserve the rejected writer-stamp design only in dated research. Verify current implementation before marking installed. |
| `agent-runtime.md` §1, REPL skill and root AGENTS's “reacquire” wording can imply rebuild/fork per turn; README §7 line 430 rules retained in-place advancement | B2 owns the exact transition and content-valid cache; architecture explains it; REPL skill gives the live probe. Root says only the boundary law. Distinguish commit identity for branch head from content identity for reusable derivations. |
| README §4 says filesystem hook indexes edits into candidates, clusters page §8 describes a shared candidate, README §7 line 426 says agents use own branch and files change through accepted write-back | D1 owns the intended REPL→branch→gate→write-back path and the current bootstrap gap; B1 owns thin MCP/operator access. Remove any claim that direct hook/file edits already implement the desired agent path. |
| D1 §10 presents whole-combined-program coverage as a choice while current root law and README §2 require task tests plus all current tests reaching changed functions | D1 states the binding gate and confines broader policy to an explicitly unresolved owner gate only if still live after current evidence check. Do not silently change the guarantee during condensation. |
| B4's top-level owner quote demands simple schema-declared injection, while its old fixture/worker material and testing skill prescribe bespoke preparation | B4 states the desired injection contract and enumerates exact still-installed helper gaps; testing skill teaches only commands available at the checked revision. Delete obsolete runner design, retain unique actual-exit and recording obligations. |
| README §7 line 409 rules in-place schema adoption and purging a replaced attribute's data; architecture §2 summarizes only the purge, while root requires incremental live-branch proof | A1/A2/B1 state the exact accretive path, attribute purge/reinstallation, file-derived reapplication, and proof. A condensed sentence must not imply the whole store or entity is dropped. |

## Unified plan README outline

1. **Model and current truth:** the eleven-line statement, then installed/partial/unproved rows with last verified revision.
2. **One agent change:** REPL form → canonical declaration row → immediate branch feedback → injected test → tested merge → accepted write-back; explicitly name live objects versus durable shown text.
3. **Owners and seams:** eight-row table with each producer, consumer, dependency and single spec link.
4. **Order and gates:** four cuts with dependency arrows, current blocker and one acceptance probe each; namespace first loop is the first D1 case, full export follows.
5. **Proof and cost:** one focused request during work, cut-level integration, installed-load/arming/presentation evidence, sub-second and memory measures, explicit unknowns.
6. **Completion:** complete-loop scenarios, size target, remaining open decisions at their dependent row, and links to issue index and dated evidence.

No lane chronology, dated status diary, duplicated laws or §7 ruling table in
the README. Current status belongs in a concise checked table, updated when a
cut is accepted; raw evidence stays in the landing note.

## Root instructions against the model

The rewritten `AGENTS.md` strongly captures the owner's Clojure discipline,
database authority, one JVM, branch custody, honest proof, no worktrees, and
tested merge. Its 315 lines exceed the owner brief's ~250-line aim, and many
domain details duplicate architecture or skills. In particular, the long
presentation/error/Flow/test/operations paragraphs should state invariant and
owner, leaving function-level mechanism to a spec or skill. Keep essential
working laws in root so an agent need not load a specialist skill to avoid a
dangerous act.

It still opens with source/files as the practical center and defines the
program as “rows” only later. Lead with the REPL-and-data agent model, linking
the single plan statement rather than copying it. Treat shell editing,
committing and git ownership as the current *outside-agent bootstrap* under
the operating section; the target agent experiences one REPL and a branch.
The root's “reacquire from branch head” is correct for custody but must not
imply reconstructing the SCI environment on every turn. Root currently says
“derive from held immutable values” and “reuse content-valid work”; B2 must
make their distinction operational. The root also mixes owner target and
installed command claims; give each status in plan/skill rather than burying
dates in evergreen law.

`plan/AGENTS.md` repeats root cadence, evidence, ownership, size and review.
Reduce it to three plan-specific rules: where current design lives, the cut
checkpoint and ledger, and the location/required fields of a landing note.
Move model-specific obligation bullets into the owning specs. The model does
not require eight lane identities as an instruction vocabulary.

## Execution: small, lossless documentation cuts

1. **Freeze an obligation inventory before deletion.** For every heading,
   “must”, “ruled”, “open”, acceptance test, number and owner quote in the
   5,903-line plan and relevant architecture/skills, record source line,
   destination section, status (binding target, installed fact, open choice,
   historical result) and evidence link in a disposable working table under
   `tmp/`. Review the table against AGENTS and owner-intent notes. This is an
   audit instrument, never another plan. No source document is removed yet.
2. **Rewrite README §1–3 and its status table.** Establish the model and single
   end-to-end path; delete the redundant “what becomes simpler” prose as its
   guarantees move to owner specs. Review this before changing the other files.
3. **Merge the narrow overlapping specs, one owner at a time.** Projection
   sweep→A1, B1b→B1, namespace loop→D1, realities portions→B2/B4/D1. Each
   small commit moves exact proof obligations, updates links, deletes its
   obsolete source and checks the inventory for unmapped rows. Avoid a single
   5,000-line rewrite commit.
4. **Merge Flow into B2 separately.** First map N1–N4, owner-ratified routing,
   launcher retention and deferred order; condense repeated motivation and
   the proposal-only README row; then delete the 745-line stand-alone spec.
   Independent review must verify nothing was lost at the fork/terminal seams.
5. **Integrate every §7 ruling into its owner and replace README §4–8.** Delete
   §7 entirely, not just its heading. Keep one reviewed cut graph and complete
   acceptance table. Resolve contradictory wordings explicitly; do not label
   a target installed on the strength of a plan citation.
6. **Align architecture and skills.** Fold the cluster page into architecture,
   delete its old file, update incoming links. Rewrite the three sampled skills
   first against current source/installed tools, then audit the other nine.
   Shorten root and plan AGENTS last so essential laws are not lost mid-cut.
7. **Audit historical incoming links and proof.** Check `rg` references to all
   removed paths and headings, verify the requirement inventory has exactly one
   current owner per binding row and no orphaned decision, and read rendered
   README→spec→skill navigation as a new agent. Compare original and final
   owner-ruling/acceptance inventories. Documentation-only changes get link,
   status and line-count checks; there is no JVM or test gate justification
   for the rewrite itself. Archive older PRDs only in a later reviewed cut.

Proposed steady-state budget: **plan ≤2,300 lines** (README 300, eight specs
about 1,950, plan instructions ≤50), **root instructions ≤240**, **twelve
skills plus their reference files ≤1,050**. Combined plan + instructions +
skills **≤3,600 lines**, down from at least 7,996 for plan + root + the
twelve current `SKILL.md` files alone (5,903 + 315 + 1,778; skill reference
files add more). Architecture target ≤750 lines, separately. These are
ceilings for review, not a reason to delete a unique guarantee. If a section
cannot shrink without losing one, name the obligation and adjust its budget
openly; do not reintroduce a second authority.

The reviewable outcome is a next-agent path of four reads: root instructions,
plan README, one owning spec, and one task-specific skill. Architecture is
opened to verify a mechanism; dated research is opened for a cited fact or
probe. Neither is a rival plan.
