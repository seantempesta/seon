---
type: research
status: active
tags: [agent, context, render]
---

# Context blocks — 2026-09-09

Read AGENTS.md's verbatim lane rules and turn PRD §10, §13–§16, and §18 end to end before implementation. The initial default page was read before source inspection. Default was alive (PID 40078, port 7994); MCP runtime status returned health/Flow unknown with `Read timed out`. JVM evaluation subsequently returned 2 from `(+ 1 1)`. This is a failed health observation, not proof of a dead cluster.

## Initial observation

The would-be system turn has no help/instructions, exposes a raw identity pull, emits three plan reads, shows two ExceptionInfo objects for blocked steps, dumps the complete settings keyword set after overrides, and emits `(my.message/inbox {})`. Four inbox messages include two fixture messages and two unrelated probe messages. The saved prefix contains only `(+ 1 1)`; historical probe turns also remain in the debug view. No provider was invoked by this assignment.

## Dependency ledger and verified boundary

- SCI's call-preparation hook (`reference-code/sci/src/sci/core.cljc:310`) receives evaluated arguments and returns prepared arguments or a reduced refusal. `src/seon/call_preparation.clj:657` derives shorter calls from declared contracts; request-map defaults belong there.
- Datahike's transaction function (`reference-code/datahike/src/datahike/db/transaction.cljc:1152`) receives the transaction database. Existing plan mutations make ownership and position decisions there.
- `src/seon/db.clj` owns pulls and read evidence; `src/seon/plan.clj` owns the moved plan queries, transactions, and render functions. No alternate storage family was introduced.
- With the live database's projection explicitly supplied, JVM `my.plan/blocked` returned ordinary maps containing dependency strings under `:my.plan.item/needs`. That attribute declares stored refs. The AI projection treated these strings as entity refs and failed. Derived summaries now carry the existing `:my.plan/needs` stable-reference maps.

## Slice 1 — `105acca21`

Positional operations move to system namespaces; `my.*` exposes request-map calls. Fully namespaced keys remain mandatory; §18's abbreviated key examples do not introduce unqualified attributes. The section's blanket request-map rule governs its two positional plan examples.

Verification and live adoption results will be recorded with the slice commit. Scratch root: `tmp/context-blocks-root`, no-provider configuration. Default has not been stopped, restarted, or reforked.

### Slice 1 verification

- Fast: 43 tests / 430 assertions / zero failures or errors.
- Path-limited gate: 47 tests / 456 assertions / zero failures or errors across
  the my API namespaces, filesystem/edit predicates, and real SCI shown-text regression.
- Expanded gate: 71 tests / 574 assertions / 6 failures / 3 errors. The
  detached HEAD-only baseline at `0b3d31b26` reproduces those same failures:
  24 tests / 123 assertions / 6 failures / 3 errors in call-preparation and
  bootstrap. This is a pre-existing consumer-fixture boundary, not an
  attribution to another session. Details are recorded in
  `docs/seon/issues/turn-consumer-fixtures-read-retired-result-storage.md`.
- Fresh scratch fork, no-provider: Juniper seeded through the maintained
  fixture installer and read by HTTP at port 7833. The HTML has 65,811 bytes;
  rendered page text has zero `ExceptionInfo` strings, four bare inbox calls,
  and zero inbox calls carrying `{}`. Blocked dependencies are stable maps.
- Computer Use reports no browser available. Page-text verification is
  complete; visual layout and browser repaint are not claimed.

While slice 1 ran, owner commits `02c77c542` and `0b3d31b26` amended §18/18a.
The owner explicitly selected the amended requirements at 11:22; §18 and
§18a were reread end to end before continuing.
- Path-limited platform gate passed: 83 tests / 490 assertions / zero failures or errors. Slice commit: `105acca21`.

## Slice 2 — agent attribute family

All agent attribute and contract keys move to `:seon.agent/*`, including
call-preparation inputs, lifecycle graph arguments, stored refs, fixture
writers, query readers, and tests. The existing `seon.cluster.agent`
namespace remains the lifecycle owner. Its schema resource merges into
`resources/seon/schemas/seon.agent.edn`; there is one declaration population.

**RESET NEEDED**: the rename changes stored identity and ref attributes.
Default must be reforked once by the orchestrator, batching pending schema
changes. This lane does not stop, restart, or refork default.

The default adoption attempt after slice 1 refused at schema population:
`Predicate seon.edit/valid-form-operation? has no admitted callable in the corpus projection.`
The supported JVM REPL loaded the moved predicate owners and registration
call sites successfully. This is the previously recorded boundary in
`docs/seon/issues/a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md`.
The default HTTP page still showed old blocked ExceptionInfo objects and
`(my.message/inbox {})` at that observation; no successful adoption is claimed.

Fresh scratch publication `6aa1977d-e2e7-55a5-b435-bb333566f307` booted
with the renamed identity installed. The maintained Juniper installer
returned objective `Improve Juniper context inspection`. HTTP debug output
was 65,159 bytes and showed `[:seon.agent/id "juniper"]` plus bare inbox
reads. An explicit JVM read returned:

```clojure
{:identity {:seon.agent/id "juniper"
            :seon.agent/namespace {:seon.ns/name my.agents.juniper}}
 :blocked [{:my.plan.item/id "juniper/compare-changed-results"
            :my.plan.item/title "Compare refreshed results"
            :my.plan.item/expected-result "The comparison shows the previous and refreshed results together, with the relevant changed input."
            :my.plan/needs [{:my.plan.item/id "juniper/render-plan"}]}
           {:my.plan.item/id "juniper/try-live-turn"
            :my.plan.item/title "Try the assembled context in a live agent turn"
            :my.plan.item/expected-result "Juniper identifies the current step and records a truthful plan update from the assembled context."
            :my.plan/needs [{:my.plan.item/id "juniper/compare-changed-results"}]}]
 :renamed-id-installed true}
```

The later default adoption attempt encountered the renamed population while
its loaded contracts still referenced `:seon.cluster.agent/routing`; it
refused with `:malli.core/invalid-schema`. No successful default adoption
is claimed. This reinforces the RESET NEEDED boundary.

The expanded rename gate also exposed stale message-surface expectations
from slice 1. Those tests now expect one request-map arity, generated map
reply examples, and a successful bare inbox call. Their real SCI fixture
now initializes the cluster environment through `config/apply!`. The
overlay test now checks its stated guarantee (every AI dial is overridable)
without incorrectly forbidding the declared evaluation and turn overrides.

### Slice 2 verification

- Corrected message fast loop: 20 tests / 59 assertions / zero failures or errors.
- Final path-limited gate: 45 tests / 167 assertions / zero failures or errors.
- Path-limited platform gate: 83 tests / 490 assertions / zero failures or errors.
- Default remained live after the refused adoption: HTTP debug response
  71,308 bytes, old identity keys still present, four `ExceptionInfo`
  occurrences across the displayed projections.
- Test workers were capped at 3. No default lifecycle operation occurred.

Slice 2 commit: `3f07beb88` (**RESET NEEDED**). Its post-commit default
adoption again refused; default's REPL returned `2` in 1 ms and its debug
page remained available. No lifecycle operation was taken on default.

## Slice 3 — one help value

`seon.bootstrap/help` expands to the existing bootstrap owner's new
`help-value` read. The value is the exact §18a vector, with the namespace
substituted and Tools derived through the existing program-graph namespace
query plus public function/doc facts. It prints no separate instruction
text and has no topic dispatch. The identity renderer emits `(help)` first.

A real SCI/system-turn regression verifies the first stored evaluation,
13 one-line strings, all three ▲ warnings, derived Tools, no printed
output, source-version read evidence, and unchanged saved timing/handle
bytes. The test changes unrelated agent data (evidence stays current),
then changes the help definition's source (evidence becomes stale).
The fixed prose is read from its owning function's indexed source solely
to record that dependency; it is not stored as another instruction row.

Fast loop: 3 tests / 30 assertions / zero failures or errors. The first
scratch adoption attempt encountered `Clj-kondo cache is locked by other
thread or process`; the operation was retried without touching another
session or its files.

Live adoption falsified a second assumption: the qualified help macro was
new, but the bare `clojure.core/help` still expanded to the boot-time
`situation` call. Acquisition now binds the bare name to the acquired
macro Var itself. The regression verifies that identity. The live probe
after adoption returned exactly:

```clojure
[(seon.bootstrap/help-value) (seon.bootstrap/help-value)]
```

Scratch source commit `6aa19efc-cc73-565c-bc5c-0e45b413755f` was adopted
in place. After compaction, system turn `fecf3fb45f4d` stored the new help
vector first. Its debug HTTP response was 74,539 bytes; the stored help
had all 13 lines, three ▲ warnings, a result handle and `:ms 94`. The
would-be system turn was then empty because every read was unchanged.
The old plan forms and settings keyword list remain for slice 4.

Final focused gate: 6 tests / 57 assertions / zero failures or errors.
Final platform gate: 83 tests / 490 assertions / zero failures or errors.
Both gates capped workers at 3 and isolated only this slice's paths.
The cache rebuild diagnosis and bounded retries are recorded in
`docs/seon/issues/source-publication-cache-contention-hides-dependency-analysis-failure.md`.
The normal publication classpath rebuild completed, followed by successful
scratch adoption. No other lane's process was operated.
