---
type: research
status: current
created: 2026-09-17
tags: [research, steward, render, selection, decision-9]
---

# No render fallback for an attribute-scoped request — landing note

Owner ruling, 2026-09-17, [owner decisions](../plan/owner-decisions-2026-09-17.md)
decision 9, **option 1**: an attribute-scoped render request that finds no
declared pair for that attribute resolves to the generic printer; it never
silently borrows the owning entity's or a neighbour's declared form. The
render-pair curation task needs every uncurated attribute to be visibly generic
and therefore findable.

## Paths read end to end before designing

- [docs/prds/steward-platform/plan/owner-decisions-2026-09-17.md](../plan/owner-decisions-2026-09-17.md)
  — Part 1 and decision 9;
- [docs/prds/steward-platform/plan/decisions/batch-b-admission-render-turn-2026-09-17.md](../plan/decisions/batch-b-admission-render-turn-2026-09-17.md)
  §9 (a), (b), (c), (d) and the vocabulary corrections;
- [AGENTS.md](../../../../AGENTS.md) §0–§5 and §7;
- [docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
  §4b (lane rules);
- `tmp/orchestrator/wave2/repl-rule.txt`;
- the seam: `src/seon/render.clj` (`attribute-producer`,
  `attribute-declared-producers`, `render-invocation-argument`,
  `declared-producer`, `selection-stage-order`, `schema-producers`,
  `schema-stage`, `floor-producer`, `project-node*`, `render-form`,
  `render-form-value`, `render-ai`/`render-html`);
- `src/seon/render/walk.clj` (`connection-attributes`, `acquisition-members`'s
  `visit`, `declared-acquisition`, `neighborhood`);
- the consumers of `:seon.render.walk/attribute`: `src/seon/error.clj:1644`,
  `src/seon/bootstrap.clj:343`, `src/seon/render/web.clj:1149`,
  `resources/seon/schemas/seon.render.walk.edn`;
- `test/seon/render/history_test.clj`, `test/seon/render_coverage_test.clj`;
- the declarations: `resources/seon/schemas/seon.agent.edn:3-24` and `:122-126`,
  `resources/seon/schemas/seon.message.edn:67-73` and `:141-152`,
  `resources/seon/schemas/seon.ns.edn:15`, `:23`, `:32`.

## The cause

`:seon.render.walk/attribute` carried TWO meanings on one key, and the seam
guessed which one it had from the value's own keys
(`attribute-value?`, `src/seon/render.clj:374-381` before this change):

```clojure
        attribute-value? (and attribute
                              (or (not (map? value))
                                  (contains? value attribute)))
```

The walk stamps the key for a member it synthesized FOR an attribute (a declared
concern block — the request IS about that attribute) and for a member it merely
REACHED THROUGH an attribute (a neighbour entity — the request is about the
neighbour). `contains?` separates them correctly only when the owning entity
actually carries the attribute. When it does not — an absent optional attribute
— the guard read the owner as a neighbour, `attribute-declared-producers`
returned nil, and selection fell through to `schema-producer`, so a question
about ONE ATTRIBUTE was answered with the ENTITY's declared form. That is the
project's named failure class: a check that reports health from absence of
signal.

The fix is the owner law of 2026-08-29 — derive at the authority, or hand it the
decision. The walk knows which member it built; it now hands that decision on the
render request instead of letting the render seam re-derive it from the value.

## The diff

Three files, no new mechanism, one function deleted.

**`src/seon/render.clj`**

- `attribute-declared-producers` is DELETED and replaced by `attribute-scoped?`,
  which asks the request one question: does it carry
  `:seon.render.walk/attribute`?
- `declared-producer` — an attribute-scoped request resolves to
  `attribute-producer` (the attribute's declared pair) or to nil; only a request
  that is NOT attribute-scoped consults `schema-producer`.
- `schema-stage` — the same split. With no declared pair the stage is
  `:no-match` and the existing FLOOR stage answers, which is the generic printer
  for every output (`floor-producer`: `seon.render/render-form`,
  `seon.render.value/render-html`, `seon.render.value/render-ai`). The old
  special case that injected `seon.render/render-form` at the schema stage for
  form output is gone; the floor already said the same thing.
- `project-node*` — an attribute scopes the request's SUBJECT, not the nodes
  beneath it. The value renderer hands every child node the parent's request, so
  the attribute is dropped for any node at a non-empty path. Without this, an
  attribute-scoped render would ask each nested value to answer for an attribute
  nobody asked it about and its own declared shape would never be consulted.

**`src/seon/render/walk.clj`**

- new private `scoped-attribute`: a member the walk REACHED carries that
  entity's own `:seon.render.walk/eid` (set in `visit`); a member
  `declared-acquisition` synthesized FOR an attribute stands for the attribute
  and has no entity of its own. `neighborhood` stamps
  `:seon.render.walk/attribute` on the render request only for the second.
  The UNIT keeps its `:seon.render.walk/attribute` in both cases, so
  `seon.bootstrap/listing-candidates`, `seon.error/faults-input`,
  `declared-acquisition`'s own `connected` filter and
  `test/seon/render_coverage_test.clj:218` are untouched.

**`test/seon/render/history_test.clj`** — see "The regression" below.

## Before / after on live data (`default`, pid 53320)

Selection for an ENTITY-scoped request is unchanged: Juniper's agent entity
still selects `seon.cluster.agent/render-identity-ai` and the `my.message`
namespace entity still selects `seon.render.ns/render-ai`.

Attribute-scoped requests, `:seon.render/ai`, live database:

| value | attribute | attribute's declared pair | entity pair (the old fallback) | now selected |
|---|---|---|---|---|
| Juniper's agent entity | `:seon.agent/settings` | `seon.agent/render-settings-ai` | `seon.cluster.agent/render-identity-ai` | `seon.agent/render-settings-ai` |
| Juniper's agent entity | `:seon.agent/plan` | `seon.plan/render-plan-ai` | `seon.cluster.agent/render-identity-ai` | `seon.plan/render-plan-ai` |
| Juniper's agent entity | `:seon.agent/id` | none | `seon.cluster.agent/render-identity-ai` | `seon.render.value/render-ai` |
| `my.message` namespace entity | `:seon.ns/requires` | none | `seon.render.ns/render-ai` | `seon.render.value/render-ai` |
| `my.message` namespace entity | `:seon.ns/name` | none | `seon.render.ns/render-ai` | `seon.render.value/render-ai` |

A declared attribute pair still wins; only the uncurated attributes moved.

**What the old fallback actually produced.** Juniper's agent entity scoped to
`:seon.agent/id`, rendered through the borrowed
`seon.cluster.agent/render-identity-ai`:

```clojure
;; I should understand how this REPL works before I act.
(help)

;; I should know my identity, namespace, and its steward.
(seon.db/pull
  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
  [:seon.agent/id nil])
```

The borrowed form was handed the attribute's shape and emitted a read for the
agent whose id is `nil`. That is the ruling's own argument, measured.

**What the generic printer now produces** for the same request: the whole agent
entity's AI projection — `{:db/id 47398, :seon.agent/id "juniper",
:seon.agent/namespace #:db{:id 47397}, :seon.agent/plan {…}, …}`, about 5 KB
under the 15000-token agent profile, uncut.

The neighbourhood walk from Juniper at distance 1, `:seon.render/form`, still
renders the namespace it reached through `:seon.agent/namespace` as
`(dir 'my.agents.juniper)` — the namespace's OWN declared form, not the generic
pull. That is the neighbour case the old guard protected, now preserved at the
walk.

## UGLY OUTPUT

Honest and filed rather than papered over with a fallback:

1. The generic answer to an attribute-scoped request is about the OWNING
   ENTITY, not the attribute —
   `(seon.db/pull '[*] [:seon.ns/name fixture.history])` for a request about
   `:seon.ns/requires`, and the entire agent entity for a request about
   `:seon.agent/id`. Filed as
   [a-generic-attribute-scoped-render-answers-with-the-owning-entity](../../../seon/issues/a-generic-attribute-scoped-render-answers-with-the-owning-entity.md).
   Batch B §9(c) predicted a `db/q` listing here; there is no such producer —
   `seon.render/render-form` (`src/seon/render.clj:1296`) spells an entity pull
   and nothing else. Fixing that moves the floor for every attribute-scoped
   render, so it is its own slice.
2. `seon.test/run`'s failure message renders the compared values under the
   agent profile, so a quoted pull PATTERN inside an expected form came back
   with a `:seon.print/bound-by :seon.render.profile/max-depth` elision value
   spliced into it at depth 8. The message is honest about eliding, but a
   failure message a person has to read is a poor place to spend the depth
   budget. Recorded here, not filed: it is the test runner's report, not a
   render defect, and the assertion was rewritten to compare values instead of
   printed bytes (PRINTED-BYTES RULE).

## The regression

`seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain`
was 3 / 9 / 0 before this change (reproduced in process on `default`, canonical
fixture, armed, reloaded through `seon.test`'s own loader). The four drift
assertions were updated to the declarations already in the tree and the four
under the ruling now assert the ruled behaviour:

- the fixture's schema-key filter now seeds `:seon.message/inbox` and
  `:seon.agent/agent` alongside `:seon.message/to`, so the schema stage has the
  rows it is being asked about;
- `message-form` returns `(my.message/read #:my.message{:id "history-message"})`
  since `105acca21` moved the `my` APIs to request maps;
- `inbox-form` is declared on `:seon.message/inbox` and reads the reverse
  `:seon.message/_inbox` edge since `ae0e54841`; the assertion now checks the
  form's structure rather than its printed bytes;
- `:seon.message/to` has no declared pair, so an attribute-scoped request for it
  resolves to the generic `seon.render/render-form`, not to the message's
  `message-form`;
- the agent entity pulled `'[*]` declares no `:seon.render/form` pair on its own
  attribute map (`situation-form` is declared on the DERIVED situation map,
  `resources/seon/schemas/seon.agent.edn:122-126` — a different value), so the
  floor answers `(seon.db/pull '[*] [:seon.agent/id "history-agent"])`;
- a namespace with no `:seon.ns/requires` scoped to `:seon.ns/requires` reaches
  the floor instead of `namespace-form`.

New: `seon.render.history-test/a-neighbour-the-walk-reached-renders-by-its-own-shape`
walks an agent at distance 1 for `:seon.render/form` and asserts the namespace
unit the walk reached through `:seon.agent/namespace` renders
`(dir 'fixture.neighbour)` — its own declared shape — with the attribute still
stamped on the unit. Without `scoped-attribute` this unit falls to the generic
entity pull, so the regression fails on the seam it protects.

## In-process runs (not the gate)

On `default` pid 53320, canonical fixture base already realized, three-argument
`seon.test/run` with `:seon.test/remaining-ms` 120000-180000, each test
namespace reloaded through `#'seon.test/with-test-loader` first.

| run | result |
|---|---|
| `form-is-the-third-output-of-the-existing-selection-chain`, BEFORE the change | 3 pass / 9 fail / 0 error |
| `form-is-the-third-output-of-the-existing-selection-chain`, AFTER | 14 pass / 0 fail / 0 error |
| `a-neighbour-the-walk-reached-renders-by-its-own-shape` | 4 pass / 0 fail / 0 error |

| `seon.render-coverage-test`, all 7 | 5 green, 1 pre-existing red, 1 bound expiry — see "Boundary" |

A sweep of the remaining `seon.render*` tests that `seon.fn/tests-reaching`
names for the changed functions (83 in total) was stopped after those 7; the
cold gate covers the rest.

## Boundary — reported honestly

- These are IN-PROCESS runs in the development JVM. The cold `bin/test` gate is
  the proof of record and the orchestrator owns it; this lane launched no test
  JVM.
- `seon.fn/tests-reaching` names 208 tests across ~46 namespaces for the changed
  functions (`seon.render/declared-producer`, `schema-stage`, `producer`,
  `project-node`, `seon.render.walk/neighborhood`), 83 of them under
  `seon.render*`. In-process runs cost roughly two minutes each in this shared,
  loaded JVM, so the sweep was stopped at 7 completed to hand the diff to review
  rather than hold it for two and a half hours. Those seven are the whole of
  `seon.render-coverage-test`: 5 green, 1 bound expiry that is not a verdict
  (`only-agent-context-renders-prepare-cost-facts-for-the-caller` did not arrive
  within its 120000 ms `:seon.test/remaining-ms` in this loaded JVM), and 1 red,
  `a-refused-render-producer-contributes-a-stable-typed-unknown` — expected
  `:seon.instrument/contract-violated`, got nil.
- That red is PRE-EXISTING and already attributed elsewhere:
  `tmp/orchestrator/gate-requests/render-repl-reds.txt:26-32` lists it under
  "KNOWN REMAINING RED (do not attribute to this lane)", filed as
  [a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind](../../../seon/issues/a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind.md).
  It is also not reachable by this diff: `probe-render`
  (`test/seon/render_coverage_test.clj`) supplies its producer through
  `:seon.render/ai`, which selection answers at the EXPLICIT-REQUEST stage, two
  stages before the schema stage this change touches, and the test carries no
  `:seon.render.walk/attribute` anywhere.
- Adoption of these edits (`bin/seon init --dev default --changed
  src/seon/render.clj --changed src/seon/render/walk.clj`) reloaded
  `seon.render` and `seon.render.walk` and ran through JVM instrumentation, then
  refused to record its commit: `Source changed during adoption through the one
  retry; the next edit must converge it`. A concurrent lane's in-flight
  `src/seon/fn.clj` also carried an unmatched bracket during this session, which
  refused static analysis for every publication attempt in the window
  (`logs/current-source-failure.log`). Both are shared-tree churn, not this
  diff; the two namespaces this lane owns ARE the reloaded definitions, verified
  by resolving `seon.render/attribute-scoped?` and
  `seon.render.walk/scoped-attribute` and by the absence of
  `seon.render/attribute-declared-producers`. No new arity was added, so no
  re-arming was required for the proofs above.
- Protected paths were not touched. `src/seon/render/transcript.clj` was READ
  (for `inbox-form` and `message-form`) and not edited.
