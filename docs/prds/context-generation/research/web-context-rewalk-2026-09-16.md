# The "unrelated" transaction mints an agent — web-context re-walk triage

2026-09-16 · branch `steward-platform` · HEAD `46b6ef7bc`
Subject: `seon.render.web-context-test/context-and-page-do-not-demand-the-render-proc`
(batches 70 and 75), with its sibling
`seon.render.web-test/unrelated-transaction-reuses-debug-observation-and-render-call`.

## Measured failure (batch 75, `tmp/orchestrator/gate-results/batch-75/named.md:277`)

```
FAIL (web_context_test.clj:104) an unrelated transaction preserves the acquired history
expected: (= 1 @walks)   actual: (not (= 1 2))
FAIL (web_context_test.clj:116) a changed evaluation invalidates the retained history root
expected: (= 2 @walks)   actual: (not (= 2 3))
```

The second failure is the first one plus one: every later count is shifted by
the single extra walk, so there is ONE event to explain, not two.

Sibling, same commit, same fixture shape
(`tmp/orchestrator/gate-results/batch-75/named.md:265`):

```
FAIL (web_test.clj:1061) the database wake reuses observation, discovery, and invocation
expected: (= before @counts)
actual: (not (= {:discovery 28, :invocation 86, :observation 1}
                {:discovery 29, :invocation 131, :observation 1}))
```

That page's subject is `[:seon.ns/name seon.flow]` (`test/seon/render/web_test.clj:1041`).
Its entity observation did NOT re-run (`:observation` stays 1), but one more render
function was selected and 45 more render functions were invoked.

## The wake/interest hypothesis is refuted on this path

- The context test pauses the whole graph before it transacts anything
  (`test/seon/render/web_context_test.clj:22`, `(flow/pause (:graph context))`)
  and then calls `render/acquire-context!` directly on a virtual thread
  (`web_context_test.clj:37`). The render proc and `src/seon/cluster/wake.clj`
  are not on the measured path; `@walks` counts `seon.render.walk/history`
  calls made by the caller itself.
- `:seon.agent/id` carries no `:seon.wake/listen` declaration
  (only `seon.message`, `seon.effect`, `seon.issue`, `seon.schedule.fire`
  declare it: `resources/seon/schemas/seon.message.edn:146`,
  `seon.effect.edn:167`, `seon.issue.edn:32`, `seon.schedule.fire.edn:12`),
  so minting an agent offers no render wake either.

The counter that moved therefore belongs to the acquisition cache in
`src/seon/render/web.clj:2411-2440` (`derive-context!`'s `reusable?`), not to
wake or interest derivation.

## What the fixture change actually altered

`git show d7e5a0268` ("tests: an unrelated transaction is unrelated") replaced a
transaction that re-asserted an existing message with one that

1. mints a NEW entity `{:seon.agent/id "context-bystander"}`
   (`web_context_test.clj:99`, `web_test.clj:1056`), and
2. re-points `:seon.message/to` from `[:seon.agent/id "root"]` to the new
   bystander — a cardinality-one ref, so the datom
   `[message :seon.message/to root]` is RETRACTED, while
   `:seon.message/inbox [:seon.agent/id "root"]`, asserted at
   `web_context_test.clj:24`, still stands.

The pre-`d7e5a0268` transaction changed only `:seon.message/content` on an
already-existing entity; it minted nothing and retracted no ref to the subject.
So the fixture did not merely stop addressing the agent — it started adding an
entity and retracting an edge that pointed at the agent under test.

Two independent reasons that transaction is not "unrelated":

- **A bare `:seon.agent/id` is a half-agent.** The one real creation path
  (`src/seon/cluster/agent.clj:122-148`) asserts namespace, plan, settings,
  runtime and a steward call. The fixture asserts an identity with none of it,
  so any derivation that enumerates agents now meets a new, partially populated
  agent. The sibling counts (`:discovery` 28 → 29, `:invocation` 86 → 131 on a
  page about `seon.flow`, with observation unchanged) are the measured shape of
  a new entity entering a derivation, not of a page re-observing its subject.
- **The message is still in root's inbox.** Retracting
  `[message :seon.message/to root]` mutates an entity reachable from the agent
  under test.

## Evidence-checked invalidation mechanics (no re-walk needed for these)

`derive-context!` reuses the retained history when the database is the same
commit, else when every retained call's read evidence is still current
(`src/seon/render/web.clj:2429-2437`). `seon.db/read-evidence-current?`
(`src/seon/db.clj:923-962`) prefers an exact index check
(`index-evidence-current`, `src/seon/db.clj:905-921`) whenever the read retained
index patterns; `index-pattern-change` (`src/seon/db.clj:857-870`) scopes an
attribute+value pattern to `:avet` and an entity pattern to `:eavt`, and falls
back to `:aevt` — ANY datom on the attribute — when a read retained only an
attribute.

The history walk's own reads are entity- or value-scoped and should survive:

- `render.walk/history` pulls the agent by lookup ref
  (`src/seon/render/walk.clj:883`); `pull-index-patterns`
  (`src/seon/db.clj:565-614`) turns a non-wildcard lookup-ref pull into
  `{:attribute :seon.agent/id :value "root"}` plus `{:entity root :attribute
  :seon.agent/id}`.
- `seon.eval/of-agent` (`src/seon/eval.clj:47-59`) binds `?agent-id` as an
  input, and `query-index-patterns` (`src/seon/db.clj:450-563`) resolves the
  remaining clause variables against the database before recording patterns, so
  its clauses are entity-scoped.

Note the wildcard caveat: `pull-index-patterns` returns no patterns for a
wildcard selector (`src/seon/db.clj:582`), and `of-agent`'s default selector is
`'[* ...]` (`src/seon/eval.clj:27`). A read whose evidence degrades to
attribute revisions is invalidated by any commit touching that attribute, and
`dependency-revision` keys an `:all` plan on the bare commit id
(`src/seon/db.clj:740-752`). That is the one shape that could re-walk on a
genuinely unrelated commit.

## Verdict (provisional — one probe outstanding)

The fixture, not the wake/interest derivation, is the near-certain cause: the
transaction the test calls "unrelated" mints an agent identity and retracts a
ref to the subject agent, and the sibling test measures that transaction adding
work to a page about an unrelated namespace. The correct repair is to make the
transaction genuinely neutral (no new identity attribute, no retraction of an
edge pointing at the subject) rather than to relax the expectation, which states
the ruled behaviour correctly.

The remaining question is only whether a genuinely neutral commit ALSO re-walks,
which would be a second, real defect in evidence granularity (the wildcard shape
above) hiding behind the fixture.

### The one probe that settles it

On a live cluster, with the acquisition cache warm for one agent:

1. `(render/acquire-context! request)` twice, retaining `::web/ai-calls` for the key;
2. transact one datom that asserts no identity attribute and touches no entity
   reachable from the agent (e.g. `:seon.ns/doc` on an unrelated namespace row);
3. call `db/read-evidence-current?` on each retained call's
   `:seon.render.call/read-evidence` and report which call, if any, reports false,
   with its `:datahike.read/dependency-plan` and `:seon.db/read-index-patterns`.

If every retained call stays current, the expectation is right and only the
fixture needs repair. If one call reports false, its read is the coarse-evidence
defect and the fix belongs in that read, not in the test.

This probe was not run: the default JVM was at its prober cap for the duration
of this assignment, and test JVMs were out of scope.

## Files read end to end for this note

`test/seon/render/web_context_test.clj`, the failing block of
`test/seon/render/web_test.clj`, `src/seon/render/web.clj` (`derive-context!`),
`src/seon/render.clj` (`acquire-context!`), `src/seon/render/walk.clj`
(`history`), `src/seon/eval.clj`, `src/seon/db.clj` (read evidence, index
patterns, `read-evidence-current?`), `src/seon/cluster/agent.clj`
(`creation-tx`), and the wake-listen declarations under
`resources/seon/schemas/`.
