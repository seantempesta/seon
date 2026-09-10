---
type: research
status: working
tags: [agent-context, render, repl]
---

# Context page review — 2026-09-09

Authority: reread chart §9.4 and §14 through the roadmap. This checkout
references §14a in roadmap 1c but contains no §14a heading or body; the owner's
17:55 assignment supplies its five concrete requirements. The previously read
AGENTS and specialized skills still apply.

Default probe: PID 92059, JVM MCP with an explicit connection, help is a vector
of 13 lines, 2498 UTF-8 bytes in `pr-str`. No default write was made.

Dependency ledger: schema-selected terminal projection already lives in
`src/seon/render.clj/project-node*`; `source-producer?` derives source intent
from indexed return contracts. The value renderer currently admits only flat
errors to AI schema selection. Reuse that selection and exclude source-producing
pairs, so a returned help entity renders its lines while a pulled agent remains
data. `seon.repl` owns historical shown text. `seon.eval/of-agent` owns ordered
evaluation acquisition; the debug page must carry that result to its prompt pane.

Foreign boundary: the data lane holds `src/seon/agent.clj`,
`src/seon/cluster/agent.clj`, `src/seon/plan.clj`, `src/my/plan.clj` and the plan,
config schemas. Its landed identity entry is `(seon.id/id title 8)`; its landing
note confirms hashing `(pr-str data)`. Renderer hunks for held files belong here
until those files are released. No foreign session is operated.

RESET NEEDED with the data lane's schema batch. Never stop, restart, or refork
default. Paid trial remains `:unavailable` after the recorded OpenRouter HTTP 402;
this assignment makes no paid request.

## Help slice

`help` returns `#:seon.help{:lines [...]}`. Its declared pair renders bare
lines for AI and a `ul` for HTML. The identity source already performs `(help)`
first, so no change to the held identity file is needed. The value renderer now
consults the existing schema selection for returned maps while excluding
source-producing pairs by their indexed contracts; nested pulls remain data.

Real SCI on the canonical fixture passed **26 tests / 167 assertions** in the
fast loop and **26 / 171** isolated. The returned-error regression separately
passed **1 / 14**, retaining its schema-first error behavior. Platform:
**83 / 490**, zero failures/errors. The scratch fixture's actual stored `(help)`
result is **2432 UTF-8 bytes**, exactly equal to `render-help-ai` of its returned
map; [value, shown text, and HTML](context_page_help_2026_09_09.edn).

Default's publication attempt saw changing source during analysis and refused;
it still returned the old vector. The scratch checkout isolates HEAD `24adad072`
plus these paths. Its first settings probe saw a newly loaded function without
its program row, so its zero-argument call correctly refused; scratch development
adoption must finish before the final reseed and capture. This is not a prompt
pass or a reason to operate default.

## Turns and returned data

Turn concern pairs now emit no AI text and render HTML headers only: opening,
trigger, evaluation count, and reply. A blob-backed reply shows its stored ref
instead of claiming no reply. The prompt pane renders each current evaluation
through `seon.repl/render-html`, without repeated floor wrappers sharing one id.

The browser falsified the first current-turn selector: at the observed scratch
basis, turn `2b4e991b596f` opened at 00:09:43 and committed at 536871024 with
eight evaluations, while `93959e14d8d4` opened at 00:09:42 and committed later,
at 536871026, with none. Commit order selected the wrong turn. The selector now
orders by `opened-at`; the canonical regression deliberately commits an older
opening later and also verifies an actually newer empty turn. Aggregate count
returns nil for zero matching evaluations; the known turn's header shows `0`.

The full prompt also exposed schema selection inside a returned settings vector:
the provider map matched both the settings source renderer and missing-model
prose. [The direct-map collision was separately reproduced](context_page_render_collision_2026_09_09.edn).
AI selection now applies to the returned entity and flat errors; nested data
retains its shape. An input accepted by a source-generating block stays data even
when it also matches a prose candidate. The regression covers the vector and
the direct provider map, alongside nested pull preservation and returned errors.

Native Chrome is available even though the browser connector inventory is empty.
On the scratch debug page, its accessibility tree and screenshot showed
`Turns (4)`, the latest two-evaluation turn's headers, a blank concern AI side,
and `Context now` containing the two actual evaluations. The first screenshot
also exposed an absent generated CSS asset in the throwaway checkout;
`bin/css` built it in 89 ms. No default tab was changed; the probe uses a new tab.

Final combined fast gate: **29 tests / 205 assertions**. Isolated gate:
**29 / 209**. Platform: **83 / 490**, all green. These isolated gates use
HEAD `dbec8be7e`, which landed the data lane's plan/component slice during this
review, plus only this slice's named render/test paths. The last platform run
used one worker; all runs capped `SEON_TEST_WORKERS` at three. The settings/plan
patch still applies cleanly after that commit; the shared writer files remain
untouched as assigned. RESET NEEDED includes `dbec8be7e`.

## Settings and plan renderer patch

[The coordinated patch](context_page_held_renderers_2026_09_09.patch) contains
the settings reader/source, plan example comments, and the §18d grammar and
storage changes described below, with their canonical regressions. Its base is
`7e7c74f01`, including the landed data changes in `dbec8be7e`. It has not been
applied to shared writer files, as the assignment requires. At integration,
retain the data lane's rename of `:seon.eval/value` to `:seon.eval/shown`; the
patch's added renderer provenance accompanies that same shown-text attribute.

Settings derive effective cluster defaults plus agent overrides through the
existing config/AI owners, group declared agent-setting attributes by namespace,
and append `turns-left`. Provider, retry, evaluation, and budget come first;
additional declared groups follow without disappearing. Missing configuration
and underlying refusals remain error values.

The plan comment teaches an add against the existing component's `db/id`,
`(seon.id/id title 8)`, the next position, and `retractEntity` removal. The
regression reads the exact comment forms and evaluates both through SCI,
verifying id `ba37cf26`, the added item, removal, and the retained original item.
The first fixture attempt lacked a correctly scoped agent context; replacing it
with canonical creation and `fork-for-turn` made the fast check pass **1 test /
14 assertions**. This was fixture setup, not an effective-settings refusal to hide.

The isolated checkout began at `24adad072`; it contains the reviewed committed
`dbec8be7e` delta plus this lane's help/render changes and held renderer patch.
Gate snapshots explicitly include those source/schema/test paths and exclude all
shared in-flight edits. No other lane's session was contacted or changed.

The earlier scratch session exposed [feed and turn backstops](../../../seon/issues/scratch-debug-feed-and-turn-backstops-after-adoption.md).
The recorded error facts preserve the observations; their cause is unverified.
The final reseed uses a fresh scratch fork of publication
`6aa1f976-68ad-5a95-94b6-b8386659213c`, which includes the landed component schema.

## Prompt-first grammar — owner §18d

Read turn PRD §18d from `dbe7e9173` in full before this correction. The prompt
now precedes the agent's complete input: comment lines, then the form exactly as
typed. HTML uses the same input text rather than printing the thought in a
separate paragraph above the prompt. The fixed multiline regression is exactly
**128 UTF-8 bytes**. A canonical system turn stores the real help evaluation;
its first grammar entry was **2516 bytes**, including the **2432-byte** bare response.
The returned invalid-write refusal's response is **131 bytes**, starting with
`Expected:` and equal to its saved renderer output.

The projection already knows which function it invoked. It carries that selected
symbol into the evaluation's optional `:seon.eval/renderer` fact. History uses
the saved shown text directly when that fact exists; it neither reselects nor
invokes a renderer. Ordinary values retain the reply map. This records rendering
provenance at its authority instead of trying to infer a prose shape from text
after the result object is gone. The stored-attribute declaration is on the
existing evaluation storage schema, alongside shown text.

The first storage check correctly refused the undeclared renderer attribute;
adding the member to the storage schema fixed it. The HTML check exposed a
dropped explicitly supplied namespace; `entity-emission` now retains it. A
later broad substring assertion was invalid because help itself teaches the
reply-map spelling; exact entry equality and byte count are the regression.

The shared `seon.repl`, evaluation/turn schemas and writers, and render owners
now have foreign edits. The grammar implementation therefore remains in the
isolated patch with the settings/plan hunks. No shared foreign file was edited.
The earlier settings-only isolated gate was interrupted by TERM on the owner's
new instruction; it supplies no gate verdict. The combined grammar gate replaces
it. RESET NEEDED includes the renderer attribute and the data lane's schema batch.

## Final capture and verification

The whole-prompt read caught help's stale claim that every result uses a reply
map. Correcting that line yields a **2475-byte** bare help response and a
**2559-byte** exact help entry. The final [cookbook prompt](context_cookbook_final_prompt_2026_09_09.txt)
is **8651 UTF-8 bytes**, read end to end: eight distinct opening evaluations,
one help, effective settings with 20 turns left, one incoming message, six plan
steps with `done-when`, and the add/remove examples before the plan read.
[The capture](context_page_capture_2026_09_09.edn) retains shown text and renderer
provenance. The [probe](context_page_probe_2026_09_09.clj) records it without
calling a provider. Score: **`:unavailable`**, OpenRouter HTTP 402 from the
owner's prior trial; no paid request or retry was made here.

[Actual plan example reports](context_page_plan_examples_2026_09_09.edn): add
**407 bytes**, remove **415 bytes**, id `ba37cf26`, position 6. The new parent
identity appears as `[:my.plan/agent [:seon.agent/id "juniper"]]`. The fixture
was reseeded after executing those writes.

Combined isolated gate: **19 tests / 128 assertions**, zero failures/errors.
After the help wording correction: **3 / 60**, zero failures/errors. Final
platform gate: **83 / 490**, zero failures/errors, one worker. The complete
scratch development adoption converged at
`6aa1fd30-7141-5589-83d9-ee1fe20aff65` before the final reseed. Its incremental
attempt had [refused missing function provenance](../../../seon/issues/incremental-publication-refuses-missing-function-provenance.md);
complete publication supplied the live proof.

The final native Chrome recheck was unavailable with `cgWindowNotFound`, twice,
and an empty browser inventory. [Existing tool issue updated](../../../seon/issues/browser-ui-observation-has-no-accessible-window.md).
Earlier screenshots prove the turn/settings page changes; the final grammar is
verified by exact stored prompt and HTML regressions, not a new paint claim.

[Verification record](context_page_verification_2026_09_09.edn) lists the exact
snapshot paths, namespaces, counts, source commit, and prompt digest. The final
32073-byte patch passed `git apply --cached --check` against an isolated index
at `7e7c74f01`; no shared source was modified by that check. Scratch JVM 55448
exited through `bin/seon --root … down`; the store lock was free and `lsof`
reported no open files under this lane's root or worktree before deletion.
Both were removed, with the shared `reference-code` target intact. All owned
shells ended. Default was never stopped, restarted, or reforked.

## 19:25 resume — land on the data batch

Read the landed data-lane note end to end and reread turn §18d. The prior
held patch now uses `:seon.eval/shown`; its renderer provenance survives
evaluation, settlement, entity rendering, and the fixed transcript selector.
The exact multiline entry is **128 bytes**, help is **2470 bare bytes** and
its complete entry **2554 bytes**. The flat invalid-write response remains
**131 bytes**, schema first. These are executed canonical armed SCI results.

Effective settings group provider, retry, evaluation, and budget namespaces,
then remaining groups and turns-left. Both authored plan examples execute
through SCI: `(seon.id/id title 8)` names the added step; `retractEntity`
removes it while preserving the existing step. The public renderer docstring
contains both writes. Data-lane selectors already use `done-when`,
`completed-tx`, and `:seon.message/_inbox`.

Gates: selected paths, **19 tests / 128 assertions**, then the additional
fixed-transcript-selector regression **2 / 13**, both green, one worker.
Default MCP observed the changed loaded `seon.repl/text` at basis 536870998.
CUA again lists no browser surfaces and native Chrome returns
`cgWindowNotFound`; browser paint remains unavailable.

The effective-settings read conservatively refreshes after a system turn
adds its runtime edge, despite unchanged remaining count;
[the evidence and follow-up](../../../seon/issues/effective-settings-read-refreshes-after-system-turn.md)
are recorded. No other lane's edits or sessions were used.

Platform gate: **84 tests green**, one worker, isolated root `run.mZQqlO`;
all gate shells exited and successful gate roots removed automatically.
