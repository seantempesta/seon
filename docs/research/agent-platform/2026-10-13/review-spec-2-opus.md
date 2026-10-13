---
title: "Second independent review of the simplified TASK-CHAIN spec"
type: research
status: "review; findings for root"
date: 2026-10-13
model: Claude Opus 5.5 (wrote neither the spec, its simplification, nor the first review)
basis: "docs/seon/schedule/cards/TASK-CHAIN.md at 1415880d5 (§§1–7, lines cited as L<n> of that revision); source at HEAD 1415880d5 read with rg and direct reads; prior revision via git show 40dd25678; two JVM-mode reads on default (no root SCI, no write, no restart)"
---

# Second review of TASK-CHAIN (1415880d5)

**Verdict: not ready to lock yet. It is close.** The simplification absorbed 11 of the first review's 13 findings,
and two more became moot. It also dropped three decisions the 40dd25678 text had made (findings 1, 9 and 10), and
the prose phase leaves three decisions to the implementer (findings 2, 3 and 4). Each fix below is a line or two of
spec text. Once findings 1–4, 7 and 8 are written in, the spec can be locked. Findings 5, 6 and 9–15 can be fixed in
the same edit.

JVM reads on default: the walk-subject and prose census read took 178 ms evaluation, 396 ms request and 33 MB
allocated. The namespace-closure read took 5 ms and 78 ms.

## 1. The 13 prior findings

| # | Prior finding | Status | Where (1415880d5) |
|---|---|---|---|
| 1 | Stage guidance has a second owner (`seon.bootstrap/help-value` prove/implement groups, context Prove-only branches) | absorbed | L92–93 "stage guidance comes **only** from instructions, replacing its prove/implement groups and context's Prove-only guidance". L152 deletes it with successor inference. bootstrap is in phases 3 and 4 (L152–153). |
| 2 | Inputless handoff runs undefined | absorbed, one gap left | L106–108: a single tag's task is the default, templates carry a worked-example intent, and template self-tests must pass. The Prove template's inputless case is still undecided (finding 8d). |
| 3 | Prove product judged twice | absorbed | L69 "Prove's deftest alone judges designed red; merge admits product reds by membership". L83 `merge-verdict` has no designed-red clause. L116 drops the subset assertion. |
| 4 | `refine!` lets an agent rewrite its gate | absorbed | L49 "No gating-proof route". L62–64 make gate identity come from the assigned basis. That basis is ambiguous (finding 7). |
| 5 | Design stub-error tests become reaching regressions | absorbed | L115 "New stub interface tests assert the instrumented input contract, not the temporary body". Coverage in `seon.cluster.source/accept-merge!` is static (`seon.fn/gate-sets`), so a contract-refusal call still covers the stub. |
| 6 | Terminal-end literal in `seon.task/ready` | absorbed | L73–74. `seon.task/ready` still holds `#{:given-back :capped :stalled :cancelled :error}` today, and phase 5 (L154) names task/ready. |
| 7 | `seon.cluster.agent/write-attempt!` bypasses `assignment-read` | absorbed | L60–61. Phase 3 lists cluster.agent. |
| 8 | A1 census misses `seon.error/proof` | absorbed | L82 and A1 (L131) "including error/proof". Phase 3 lists error. A1 still does not list the allowed roles (finding 8e). |
| 9 | Reds unwritten, nobody assigned | absorbed | Slice 0 (L149). |
| 10 | A5 unbounded, double tags, clone identity, cost scope | absorbed | L139–141 (separate full-loop tests, bound naming `prepare-merge!`), L126 (one phase per test), L55 (clone name), L121–122 (cost claim). |
| 11 | Retrospective has no datum | absorbed | L75: "no retrospective trigger or sixth stage". |
| 12 | Handoffs not cumulative; one table test | absorbed / moot | L114–117 make each handoff cumulative. L119 keeps one entry per tag, which the default-task rule needs, so a table test is moot. |
| 13 | File collisions | absorbed / moot | L145 says "serial (shared owners)". The namespace-source slice it collided with is gone, but it was dropped rather than filed (finding 9). |

## 2. Ranked findings

### 1. High: the agent-side done judgment has no owner, so two owners judge acceptance

**Evidence.** Today an agent ends `:done` when `seon.turn/task-end` sees `seon.context/task-done?`. That reads
`seon.context/step-verdict`, which is `seon.context/step-status` over `step-observation`, plus `unmet`,
`task-finished?` (`#{:done :satisfied}`) and context's own designed-red, contract and base-results copies.
`seon.cluster.source/accept-merge!`'s docstring says it uses `step-status` too.

At 40dd25678 the spec said what happens to all of this:

- a row "Current candidate observation → working or proposed `:done` | `seon.context/step-status`, consuming the
  shared verdict";
- a `chain-acceptance` deletion list: "Delete `unmet`, context's designed-red/contracts/base-results copies,
  broken/new-red verdict production and readers, `:satisfied`, facts-only success and empty-delta refusal".

1415880d5 keeps only "shared by preview and merge" (L83) and "replace observation production/consumption together,
delete satisfied" (L153). No symbol is the preview, `task-end` is not named, and `unmet` and the context copies are
not deleted. An implementer can keep `step-status` as an agent-side judgment next to `merge-verdict`, which is the
"two owners for one decision" the owner ruled out.

**Smallest change.** Put back one rule under "Accept once": "`seon.context/step-status` is the branch preview:
`seon.cluster.source/merge-verdict` over the branch's held candidate. `seon.turn/task-end` proposes `:done` when it
returns no error, and the proposal is never success." Put back the deletion list in phase 4: `unmet`,
`task-finished?`, and context's designed-red, contracts, base-results and broken/new-red production.

### 2. High: the prose cut has a load cycle and would silently drop 87 docstring sections

**Evidence.**

- **Load cycle.** `seon.ref` requires `seon.sci.lookup` directly. The namespace-alias closure from the JVM read:
  `seon.ref` reaches `seon.sci.lookup`; `seon.sci.lookup` does not reach `seon.ref`; neither do `seon.instrument`
  or `seon.fn`. `ref.clj` uses lookup for exactly two things, `docstring-parts` (being deleted) and
  `schema-documentation`. L91 has `lookup` (four internal callers) and `seon.instrument`'s Example read switch to
  `seon.ref` document spans. That makes lookup require ref, which is a cycle. §7 asks whether this can be done
  without a cycle, but §3 does not say how.
- **Silent loss.** `docstring-parts` recognises `Example:`, `Edge cases:`, `Errors:` and similar lines ending in a
  colon. CommonMark does not treat those lines as headings. `rg` counts 87 of them in `src` (62 in `src/my`, 25 in
  `src/seon`) against 40 `## ` headings. With the splitter deleted, those Examples disappear without an error. That
  affects instrumentation's Example inputs (`seon.instrument`) and the summary/body display in lookup and
  `seon.render.ns`. The data gives no sign that anything was lost.

**Smallest change.** Add two sentences to §3:

1. "`seon.sci.lookup/schema-documentation` (one pull of `:seon.schema/form`) moves to `seon.ref`'s side, so
   lookup, instrument and render.ns require `seon.ref` and nothing requires back."
2. "One scripted sweep in the same commit converts the colon headings to `## ` headings. The summary is the first
   sentence of the first paragraph's source span, and the Example is its section's source span, never rendered
   text." Rendered text would turn `*print-length*` into emphasis.

Add "zero colon headings" to A5.

### 3. High: the namespace-name candidate rule turns 70 existing backticked spans into problems

**Evidence.** L91 closes namespace-name resolution. A5 (L135) says "unresolved backticks remain problems". Today
`seon.ref/span-candidate` ignores every unqualified span. On default, task intents and `:seon.fn/doc` hold 291
backticked dotted unqualified spans (142 distinct). 49 distinct spans, 70 occurrences in all, are not namespaces:
`deps.edn` (10), `java.nio.file.Path`, `RT.longCast`, `user.dir`, `README.md`, `sample.s1`, `datahike.datom.Datom`
and others. If every dotted span is a candidate, these 70 become new problems. If only resolving spans count,
namespace typos are lost, and that is the very argument §3 (L95–97) uses against bare names. The spec does not
choose.

**Smallest change.** Decide it in §3, for example: "An unqualified symbol span is a namespace reference when it
names a `:seon.ns/name` or a loaded or library namespace; otherwise it is prose, like a class or file name. Only a
qualified symbol or keyword, or a based path, is reported unresolved." Add one row to A5 for each side (`my.agent`
resolves; `deps.edn` is prose). The other choice is to keep every dotted span as a candidate and sweep the 70 spans
in the same commit. That costs more and the spec should not leave it to the implementer.

### 4. Medium: stored docstring references have no attribute, no edge rule and an unnamed consumer

**Evidence.** L87–88 say docstring references "become stored, derived data consumed by context and
`seon.task/task-edges`" and that the phase must "not overload the existing symbol set". No attribute is named.
The attribute being deleted, `:seon.fn/must-read`, is declared `:seon.program/definition-edge true`, so a cited
function cannot be retracted without repair. Whether docstring citations get that rule changes `my.program/ns-unmap!`
behaviour across the codebase. On default, `:seon.fn/must-read` and `:seon.fn/see-also` hold **0** stored edges.
The only authored use is the fixture in `seon.task-ingestion-test`, so today's referrer path carries no data.
Storing keyword and path candidates has no stored consumer: context resolves them at read through `inner`.

**Smallest change.** "`:seon.fn/references` replaces `:seon.fn/must-read` and `:seon.fn/see-also`. It uses
`:seon.task/references`' schema (`:seon.program/edge-symbol`, definition-edge true), is derived at indexing by
`seon.ref/names`, and is consumed by task-edges' referrers and context. Keywords and paths are resolved at read and
never stored." Then list the attribute in §1 (finding 5).

### 5. Medium: §1's field census misses two new keys

**Evidence.** §1 says `:seon.task/proofs` is "the one added storage role" (L44). The spec also needs:

- `:seon.test/task` (L106–108), which does not exist anywhere in `src` or `resources` today. It is a test-request
  input and part of the member's reuse identity (40dd25678 said so explicitly; 1415880d5 dropped that sentence).
- The docstring-reference attribute from finding 4.

The owner's rule says fields are driven by need, and each needs its consumer stated.

**Smallest change.** Add two §1 rows: "Run a template default against a clone → `:seon.test/task` request input,
part of member evidence identity, consumed by the runner's binding." "Index docstring citations →
`:seon.fn/references`, consumed by task-edges and context."

### 6. Medium: the three new step values have no consumer that tells them apart

**Evidence.** L47 extends `:seon.task/step` with `:survey :define :design`. The only consumers of step in the
design are `seon.task/obligations` (Implement adds subject-tagged tests and effective product) and Prove's order of
running (product first). Survey, Define and Design all select "copied ∪ own" and run in the same order. The help
step groups are deleted. No rule, test or render branches on the three new values. The real need is that absence
must stop meaning `:implement` (the schema description says it does, and `obligations` defaults to it). Otherwise a
Survey clone with no step would select the subject's red test and could never merge.

**Smallest change.** Do not extend the enum. Define absence as "copied ∪ own", with `:implement` adding
subject-tagged tests and effective product and `:prove` running product first. For an unchained task the subject is
the task itself, so its selection does not change. Implement templates declare `:implement`. Census the chains on
default in the same commit: the live step frequencies are `{:prove 1}`, so its sibling Implement leaf gets
`:implement`. If root wants the stage name in data for display, the clone name already carries it (L55).

### 7. Medium: "assigned target basis" is ambiguous, so A1's removal row is not writable

**Evidence.** L63 says "Required gate identities come from the assigned target basis". The attempt row stores
`:seon.task/revision` (`seon.task/attempt-tx`). That is a declaration digest, so it covers copied
`:seon.task/proofs` but not gates derived from `:seon/tasks` tags on program entities. Two readings are possible:

- obligations as-of the assignment transaction on main, which would ignore root's later revisions that L64 allows;
- obligations on the merge's target head.

**Smallest change.** "Required obligations are `seon.task/obligations` on the merge's target (main) head, united
with the candidate's additions. A branch can add gates but cannot remove them, root's revisions on main apply, and
nothing is stored or read as-of." Write A1's removal row against that.

### 8. Medium: handoff and acceptance assertions that still need a decision

- **a. Define "explicit no cost claim" (L114).** Nothing says what text satisfies it. The walk agent would guess.
  Smallest change: "a cost claim is an Expected cost section that cites (backticked) a `seon.profile/measure`
  deftest; a present section with no such citation is no claim." This uses only `seon.ref`.
- **b. Design "contracts compile" (L115).** It does not say whose contracts. Smallest change: "every function the
  Approach section cites, resolved on the candidate, has a contract that compiles against the candidate registry."
- **c. Section matching.** It does not say whether a heading matches by text, anchor or level. Smallest change:
  "by `seon.ref/document` heading anchor at any level". The template instructions name the same headings.
- **d. Prove's inputless self-test (L108).** "Canonical fixture evidence on a disposable branch" means either
  hand-writing a test result, which CLAUDE.md bans as a hand-built entity, or running a red test, which the spec
  bans as a recursive run. Smallest change: each handoff is a pure shared helper over (effective document,
  obligations, stored results). The template's inputless run passes the worked example's values, and a clone run
  reads its branch's stored results.
- **e. A1 census (L131).** "by role" does not list the roles. Smallest change: list the live readers of
  `:seon.task/tasks`, which the first review found by `:seon.fn/keywords`: `obligations` is the only selector, and
  the writers, `candidate-dangling`, `open-tasks`/`task-findings` triage and `seon.fn/task-links` are allowed by
  name.
- **f. A4 (L134).** "A local schema's ordered vector with duplicates" names no schema; no task member is
  vector-typed. Either name the test-local registry extension or drop the clause.
- **g. A7 (L137).** "Zero post-launch hand acts" should say what is measured: the test body writes nothing after
  the subject's `:running` fact except the scripted `:seon.turn.loop/complete` replies. That matches
  `seon.cluster.db-flow-test/a-requested-green-end-merges-and-publishes-with-no-hand-act`.

### 9. Medium: the namespace-source prerequisite was dropped, not filed

**Evidence.** 40dd25678 §6 named the corrupted-namespace-form producer fix (`seon.fn/runtime-require-specs` →
`seon.sci.eval/namespace-context-row`, red `seon.cluster.write-back-test/repl-requires-never-corrupt-the-written-ns-form`,
task `seon.task/chain-namespace-source`). 1415880d5 removes it, and `rg` finds the test and the task nowhere in
`test`, `resources` or `docs/seon`. This is an observed defect whose only record is a superseded revision.

**Smallest change.** File it as its own task in `resources/seon/tasks/seon.fn.edn`, or put back one line under §6,
"precedes, not a phase".

### 10. Medium: phase owner lists lost files that hold what they delete

**Evidence.** Phase 4 (L153) deletes `:satisfied`, which `rg` counts in `src/seon/context.clj` (5),
`src/seon/cluster/agent.clj` (4), `src/seon/turn.clj` (3), `src/seon/task.clj` (3), three schema files and
`resources/seon/tasks/seon.context.edn`. Its owner list omits task and cluster.agent. The abbreviated lists ("ref,
fn indexing, lookup, …") cannot be passed to `bin/lane claim`.

**Smallest change.** Put back the 40dd25678 §6 file paths and correct them by `rg`. Add `src/seon/task.clj` and
`src/seon/cluster/agent.clj` to phase 4. Add `src/seon/sci/lookup.clj`, `src/seon/instrument.clj` and
`src/seon/render/ns.clj` to phase 2.

### 11. Low: `:seon.task/proofs` carries three meanings

On a declaration it is the copied defaults (L44). In `seon.task/task-edges` it is the tagged tests. In
`obligations` and `assignment-read` it is the union. Converting task-edges to obligations (L60) removes one of
these. Smallest change: the attribute description says "the copied part of `seon.task/obligations`", and
`seon.render.ns/task-ai` shows obligations rather than the raw member.

### 12. Low: the bare-name choice is still "(recommended)" in a LOCKED spec

L95 asks for owner assent to the backtick rule, which runs against the owner's "everything we know how to uniquely
identify". Get that assent before locking. The evidence for the recommendation holds: prose-references Part 3 shows
bare parsing is safe only when unresolved spans are dropped silently.

### 13. Low: two prose-references gaps are not mentioned

These are prose-references Part D items 6 and 7:

- `tmp/` paths already resolve to `:unresolved` (P21), which is enough.
- A `#anchor`-only link in task prose is silently ignored (P17).

Smallest change: add one A5 row, "an anchor-only link with no document is a problem".

### 14. Low: `seon.ref/candidate` is private

§3 (L85) lists it as a shared owner. Say whether it stays internal to `seon.ref/references` and `seon.ref/names`
(recommended) or becomes public.

### 15. Observed outside scope

These are not chain findings. They are recorded so they outlive this note:

- `seon.instrument`'s Example reader wraps its read in `(catch Throwable _ nil)`, which swallows errors, against
  "Nothing crashes / no other layer catches".
- `seon.cluster.source/accept-merge!` has a `file:line` comment (`sci/eval.clj:1005-1011`).

Phase 2 touches the first. It should delete the catch rather than move it.

## 3. Walk: `seon.turn/a-send-inside-a-do-form-leaves-a-message`

Live state on default: the task is `:held`, has no children and no product, and its six intent references all
resolve. Those are the red test `seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes`,
which is tagged to the subject, the triage document path, `my.message/send`, `:seon.message/id`,
`seon.cluster.message/send-call` and `seon.cluster.message/send!`. No `seon.step` tasks exist yet.

| Stage | What it gets | Where it would get stuck or guess |
|---|---|---|
| Intake | `seon.task/chain!` adds five clones, for example `seon.turn/survey-a-send-inside-a-do-form-leaves-a-message`, to `resources/seon/tasks/seon.turn.edn`. One `:running` fact readies Survey. | Nothing, as long as Survey's obligations exclude the subject-tagged red test. Under today's absent-step-is-`:implement` default they include it (finding 6). |
| Survey | Effective intent (one paragraph with no headings) plus copied instructions. Obligations are `survey-handoff`, run with `:seon.test/task` set to the clone. | It must rewrite the whole intent into Problem / What exists through `refine!`. That is feasible once the heading-match rule (8c) is stated. Today `seon.bootstrap/help-value` would tell it to fix a function; phase 3 deletes that. |
| Define | Cumulative document. | Guesses what text counts as "no cost claim" (8a). |
| Design | No new interface: the producer is `send-call`/`send!`. | Guesses which contracts must compile (8b). |
| Prove | Declares product `#{…a-turn-delivers…}` through `refine!` on the subject. That test is already red (a failing assertion, so `seon.test.runner/designed-red?` holds once it has run on the branch). | Nothing, provided `:my.task/refine-request` admits `:seon.task/product`. Today it destructures only intent and instructions, so phase 1 must add it, with A4 covering it. |
| Implement | Obligations are `implement-handoff`, the subject-tagged red test and the effective product. The effective product requires `:seon.task/effective`'s schema to gain product: today it holds only id, sym, intent and instructions. | Nothing beyond phase 1's schema change. |
| Merges | Survey to Prove are task-only. Implement's merge carries the program change. | Who decides Implement's `:done` before the merge is unnamed (finding 1). |

## 4. Prose-parser phase, checked against prose-references.md

| Item | In the spec? |
|---|---|
| One parser (`seon.ref/document`, `seon.ref/candidate`) for task prose, function, test and namespace docstrings, schema descriptions, documents and help | yes (L85) |
| Namespace names | yes (L91), but the candidate rule is undecided (finding 3) |
| Test docstrings and namespace docstrings | yes (L86, L91) |
| `(help)` | yes (L91–93). Stage guidance comes only from instructions. |
| Index-time docstring references | yes in intent (L87), but the attribute and edge rule are undecided (finding 4) |
| Delete `:seon/must-read` and `:seon/see-also` | yes (L89–90). There are 0 stored edges on default, so the deletion is cheap. |
| Delete `seon.sci.lookup/docstring-parts` | yes (L90), but the cycle and the 87 headings are unhandled (finding 2) |
| Bare-name decision | recommended, not ruled (finding 12) |
| `tmp/` and anchor-only links | anchor-only links are not mentioned (finding 13) |

## 5. Smallest edit to lock

Make these changes to TASK-CHAIN.md, roughly 15 lines in all:

- §2: the preview rule and the obligations-basis rule (findings 1 and 7).
- §3: the cycle move, heading sweep, namespace candidate rule, `:seon.fn/references` and `candidate` visibility
  (findings 2, 3, 4 and 14).
- §1: the two keys, and drop the enum extension (findings 5 and 6).
- §4: the three handoff definitions and the pure helper (finding 8a–d).
- §5: the A1, A4, A5 and A7 wording (findings 8e–g and 13).
- §6: restored file paths and the namespace-source task (findings 9 and 10).
- The owner's line on backticks (finding 12).

No new mechanism is needed. Each change states a decision or deletes something.
