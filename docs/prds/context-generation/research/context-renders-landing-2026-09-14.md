---
type: research
status: active
tags: [research, render, context, test]
---

# Context render rules — 2026-09-14

Implementation is complete through the printer and DB performance slices.
Final frame/provenance gates and commits are being recorded below. The
stored-run-2 explain rerun remains unavailable: default no longer contains
turn `a51f8821e5be` after the owner's reset. No substitute session was used.

## Measurements

| Observation | Before | After |
|---|---:|---:|
| Default Juniper regenerated opening, UTF-8 bytes | 14,923 | 7,878 |
| Same previews, `seon.ai.tokens/estimate` | 4,660 | 2,456 |
| Canonical no-event virtual turns | — | 3 |
| Generated evaluations added during those turns | settings/runtime churn | 0 |
| Added system bytes during those turns | 458 per historical refresh | 0 |
| Plan objective change: emission / changed subtree bytes | whole read | 269 / 77 |
| Message arrival: emission / changed subtree bytes | whole read | 452 / 124 |

The opening measurements are **read-only regenerated previews**, not edits
to stored history. Before: default basis `536881414`, ten evaluations.
After: default basis `536871869`, ten evaluations. The owner reset and ran
the fixture between them, so record state also differs; these are not an
isolated estimate of code savings on an identical database. Script:
[context_renders_probe_2026_09_14.clj](context_renders_probe_2026_09_14.clj).
Exact outputs: [before](context_renders_before_2026_09_14.edn) and
[after](context_renders_after_2026_09_14.edn).

The recurring loop proof uses the real armed graph, canonical database and
SCI context. Three no-event turns include the production wildcard plan
query. They append zero evaluations and zero bytes. One plan write then
appends exactly the plan read; one message appends exactly the inbox read.
Both changed responses parse as EDN, fit the same eight-times-subtree
assertion, remain byte-identical when timing changes, and evaluate their
full-value hints through the actual retained handles.

Owner-observed live run 3 independently reached **21 turns with 2 system
turns**: opening plus one plan reread, with zero churn.

## Commits and rule ownership

| Commit | Rule landed | Green evidence at checkpoint |
|---|---|---|
| `0dca8534e` | No-self-dependency invariant; sparse settings; stable runtime/root reads; collection-bound read evidence | Three idle turns: 0 evaluations / 0 bytes; platform 84 tests / 505 assertions. The then-red DB cost boundary was recorded. |
| `8df86358b` | Owner-requested preserved slice: nested pairs, compact plan, whole-item printer groundwork | Fast 53/425 and 56/335 tests/assertions; 0 idle system bytes. |
| `e81b119f2` | Retain committed system results in the agent SCI context | Fast 6/232; isolated 6/236; 0 idle system bytes. **RESET NEEDED** for pre-existing boot handles. |
| `0c70a1cb4` | Keep turn-dependent agent-written reads out of generated context | Isolated 4/216; production wildcard query included; 0 idle system bytes. |
| `1f18b99fc` | Changed-path responses and executable full-value hints | Fast 7/245; isolated 8/250; plan 269 bytes, inbox 452, idle 0. |
| `d0817d49b` | Deterministic scalar ordering; whole map coordinates; broader generated printer property | Fast 49/234; isolated 49/238; no read-membership change. |
| `6785c980c` | Native database decoding avoids unnecessary logical projection | Fast 41/272; isolated 41/276; no read-membership change. |
| `eef44fcc3` | Preserve database-free relation queries | Fast DB/help 44/293; isolated 44/297; no read-membership change. |

The prompt-frame and provenance follow-ups pass their combined isolated
gate: **19 tests / 369 assertions**, covering prompt, loop proof, REPL
grammar, help and the trial harness. The provenance fast gate passes
9 tests / 302 assertions; the frame fast gate passes 8 / 45. Idle system
bytes remain zero; plan and message deltas remain 269 and 452 bytes.

All paths were committed explicitly. The preserved mixed slice was the
owner's explicit priority after cleanup; later slices stayed separate.

## Invariant and run-3 fault

`:seon.wake/context-inert` is declared on the excluded schema attributes.
The generated-evaluation seam rejects forbidden evidence with a fault that
includes the source form. Settings return overrides; inherited defaults
are on demand. Runtime selects trigger and listens; its comment gives the
turn query. Root identity/status retain only stable identity/source facts.
The collection-input evidence fix uses Datahike's `resolve-ins` and
`collect`, covering every bound attribute rather than one guessed row.

Read-only inspection of production fault
`7ab85b9a-1107-446f-9bd8-c5694ecc7455` found this complete offending form:

```clojure
(seon.db/q '[:find (pull ?e [*]) :where [?e :my.plan.item/id]])
```

Its original evaluation was **agent-authored**, turn `0ab4ce1cb9ca`, not
an opening renderer. Since-diff had promoted its wildcard evidence into
generated context. `0c70a1cb4` excludes such agent reads from promotion;
they remain callable on demand. Declared opening reads still fault if
invalid. The plan opening uses the explicit selector behind
`(seon.plan/plan {})`.

### RESET NEEDED — `e81b119f2`

At 04:07Z, run 3 met a boot-built handle without
`:seon.agent/context-state`. Faults `d217ae07…` (Juniper) and `ea7ee3f1…`
(root) stopped their turn procs. The carrier is now created at boot and
required by acquisition. There is no call-time atom fallback. The owner
subsequently restarted default; this lane never stopped, restarted,
reforked or reseeded it.

The same persistent SCI context serves system evaluations and `arm!`.
Prospective result handles bind only after the writer succeeds; previews
remain disposable. The loop resolves saved system handles, evaluates each
full-value hint, and evaluates every elision requery from a genuinely
stored long-string evaluation.

## Rendering and printer

Changed evaluations store a plain EDN changed-path map computed with
`seon.db/diff`; removals have an explicit marker. Previous shown structures
are reconstructed by applying saved deltas, without a second durable full
snapshot. `seon.repl/text` emits the fixed system comment, original form,
change and `(get-in result/e… [])` hint. Initial reads stay full; ordinary
agent input is unchanged.

A final live prompt inspection exposed a provenance-query error: a grounded
input inside `not` produced a DB error, which `some?` treated as a match.
The query now binds the turn through the evaluation identity and accepts
only an actual earlier entity id. A regression supplies the database when
rendering the opening and proves it remains unmarked. Existing plan/message
loop cases prove real changed reads are marked.

Transaction, directory, plan-item and whole-plan values select their
schema pairs inside response values, including at the depth boundary.
Selection considers declared optional fields and passes the actual nested
value. The plan retains the current criterion, one line for each other
step, completion times for done steps, and one update example.

The core printer sorts structurally before rendering visible children.
Strings remain whole or become one elision with offset/length. Collections
omit whole children with a count. Keys remain coordinates: an over-limit
key causes whole entries to be omitted rather than replaced. Requery hints
require actual result identity. Generated values include nested maps,
sequences, sets, strings, ratios, decimals, finite doubles, symbols and
characters. The property parses output, compares repeated bytes, checks
counts, and evaluates every emitted requery in real SCI.

The prompt assembly appends `seon.repl/frame`, outside saved evaluations.
The help trial uses that same frame function. No web-page owner was edited
for this line.

## DB performance

Native non-string Datahike storage cannot use the EDN-string codec, so it
needs no logical projection merely to rule out decoding. Query find-field
derivation no longer creates a projection merely to cache its answer.
String-backed logical values still consult their schema. Relation-only
Datalog queries correctly require no database at all.

The unchanged ten-query timing assertion passes: one fast sample measured
**32,135,210 ns raw / 27,681,375 ns wrapped**, versus the earlier
**35,316,833 ns raw / 5,635,710,666 ns wrapped**. The full DB gate includes
codec, temporal-origin and evidence regressions. No bound was relaxed and
no global cache was added. The broader issue of unhanded string-codec
callers remains documented in its existing issue.

## Model trials

The committed help harness ran in a disposable root with the canonical
fixture and real SCI/agent graph. Its automatic cluster armer was parked
before the final initial opening, as the fixture test does, so no empty
virtual turn consumed the instruction. Default was untouched.

- [Initial attempt](help_trial_context_renders_2026_09_14.edn): cheapest
  configured route, OpenRouter, returned HTTP 402 for insufficient credits;
  no model output or score was available.
- [Direct-provider trial](help_trial_context_renders_direct_2026_09_14.edn):
  the unavailable model was removed only from the scratch database.
  Direct DeepSeek Flash scored 12/12. Inspection then found the opening
  marker defect described above; this intermediate artifact is preserved.
- [Final trial](help_trial_context_renders_final_2026_09_14.edn): corrected
  opening, **7,111 prompt bytes**, frame “turns left: 30 of 30”, **12/12**.
  The model named the current read step and emitted its query. Fabricated
  response-map count: **0**. Provider usage: 2,196 prompt / 382 completion
  tokens, 640 cached; estimated cost **$0.00046452**.

The separate required explain rerun on stored run 2 has **not run**.
Read-only lookup found `a51f8821e5be` absent from default after the owner's
reset. Its archived cluster/commit was requested. No alternate run was
substituted and no explain-call budget was spent; its fabricated-response
count is therefore unmeasured.

## Grounding and verification boundary

Read end to end: AGENTS.md; the run-2 landing; both original explain-probe
`:text` accounts; the self-churning-read issue; turn PRD §§14, 15, 18–18d;
and the named implementation owners, including the read-only SCI dir/doc
implementation. The model's own accounts identified misleading repeated
reads, chopped documentation and unusable result hints.

Dependency ledger: SCI context/fork/evaluation in
`reference-code/sci/src/sci/core.cljc`; Datahike query bindings and temporal
values in `reference-code/datahike/src/datahike/query.cljc` and
`db.cljc`; vendored editscript through `seon.db/diff`; the existing
`seon.print` grammar and `seon.render.value` projection; schema pair
selection in `seon.render`; and emission grammar in `seon.repl`.

Default's function-only DB edit briefly mixed a new declaration carrier
with an older decoder during adoption. Reloading the committed private
decoder through the JVM REPL allowed normal publication to complete.
Installed and published source both measured
`6aa8ce64-0b13-51ba-8b3a-9820c96dd73e`; this was in-place adoption, not a
refork. The later marker fix was also verified by hot reload with contracts
re-armed in the disposable scratch JVM.

A plain final platform run hit concurrent deletion of `web/debug-ai-html`
while `test/seon/turn_test.clj:230` still referenced it. Those files were
left to debug-turns. The HEAD-plus-owned-paths platform gate passed
**84 tests / 505 assertions**. Final frame/provenance gate results and
cleanup are appended below once complete.
