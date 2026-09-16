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

## Live probe (default cluster, pid 88182) — the defect is real and measured

Agent `juniper` on `default`, `render/acquire-context!` called twice on the
caller's thread, then one deliberately neutral commit — `{:db/doc "..."}`: no
identity attribute, no `:seon.wake/listen` attribute, nothing any render pair
declares.

Before the fix (basis 536871126 → 536871127):

| retained calls | current at the same db | current after the neutral commit |
|---|---|---|
| 26 | 26 | 25 — `:seon.render.web/root-acquisition` stale |

The one stale call is the history root, so `derive-context!`'s `reusable?`
(`src/seon/render/web.clj:2429-2437`) is false and the whole history is walked
and re-rendered. **Every commit on the cluster re-walks every acquired agent
history**, whatever it contains.

### Root cause

The root call's evidence for `seon.eval/of-agent`'s query carries
`:datahike.read/attributes :all` (the wildcard `pull` in its `:find`), no index
patterns, no retained `:seon.db/read-result` (the request is not a bounded read
request) and — measured — no `:seon.db/read-result-digest`. With all four
absent, `read-evidence-current?` (`src/seon/db.clj:939-961`) has nothing to
compare but the commit id, which every commit changes.

The missing digest is the bug. `stable-value` (`src/seon/db.clj:422-424`) admits
`inst?`, `uuid?` and `char?` as stable read data and reported this very result
stable, while `read-result-digest` (`src/seon/db.clj:435-446`) encodes through
`seon.schema/canonical-data-string`, which threw on all three
(`src/seon/schema.clj:563-568`). Measured directly: the `of-agent` result's value
types include `java.util.Date` and `java.util.UUID`, `stable-value` returns
`true`, and the digest attempt raises
`:seon.schema/noncanonical-projection-data` ("Schema projection fingerprint
contains non-EDN data"), which `read-result-digest` swallows into `nil`.

Two functions disagreed about what ordinary data is, and the disagreement read
as "no evidence available", which the cache reads as "stale" — the project's
recurring absence-of-signal class, here paying for itself in a full re-walk per
commit.

### The fix (commit `dfd2aae54`, `src/seon/schema.clj`)

`canonical-data-string` now encodes the three EDN literals it lacked:
`(inst? → "i" (inst-ms value))`, `(uuid? → "u")`, `(char? → "c")`. The tags are
new, so nothing that encoded before encodes differently; values that previously
threw are the only behaviour change. This deletes the disagreement rather than
adding a second digester beside it.

After the fix, same probe, same neutral commit (basis 536871150 → 536871151):

| root evidence entries carrying a digest | current at the same db | current after the neutral commit |
|---|---|---|
| 21 of 21 | 29 of 29 | **29 of 29** |

No call goes stale, so no re-walk. Verified in the running `default` JVM after
`bin/seon init --dev default --changed src/seon/schema.clj` converged
(`:current-src` commit `6aaaa74c-2fb7-5a2b-bfbe-8985587cd08e`).

## Verdict

1. **Not a wake or interest defect.** The failing test pauses the graph and calls
   the acquisition directly; `:seon.agent/id` carries no `:seon.wake/listen`.
2. **The expectation is right, and a real defect stood behind it.** An unrelated
   commit was re-walking the agent history on the live cluster — measured above —
   because an ordinary read result containing an instant could not be digested.
   Fixed at the one owning seam, `seon.schema/canonical-data-string`.
3. **The fixture is also imprecise and should be re-read after the re-gate.**
   `d7e5a0268`'s replacement transaction mints `{:seon.agent/id
   "context-bystander"}` — a half-agent next to the real creation path
   (`src/seon/cluster/agent.clj:122-148`) — and retracts `[message
   :seon.message/to root]` while the message stays in root's inbox. The sibling
   test measures the consequence on a page about `seon.flow`: `:discovery`
   28 → 29 and `:invocation` 86 → 131 with `:observation` unchanged. I did not
   rewrite either fixture: the sibling also asserts that the same transaction
   still advances a render pass (`test/seon/render/web_test.clj:1063`), so a
   neutral datom like `{:db/doc "..."}` may not wake the render proc at all, and
   choosing the replacement needs the test JVM this assignment excluded.

## Re-gate

`seon.render.web-context-test`, `seon.render.web-test`, `seon.schema-test`,
`seon.db-test`, plus `bin/test --platform`. `seon.schema/canonical-data-string`
also feeds projection fingerprints and the preprocessed-base composition proof,
so the schema and cluster-publication namespaces are the ones that would notice
an encoding mistake.

## Files read end to end for this note

`test/seon/render/web_context_test.clj`, the failing block of
`test/seon/render/web_test.clj`, `src/seon/render/web.clj` (`derive-context!`),
`src/seon/render.clj` (`acquire-context!`), `src/seon/render/walk.clj`
(`history`), `src/seon/eval.clj`, `src/seon/db.clj` (read evidence, index
patterns, `read-evidence-current?`, `stable-value`, `read-result-digest`),
`src/seon/schema.clj` (`canonical-data-string`), `src/seon/cluster/agent.clj`
(`creation-tx`), and the wake-listen declarations under
`resources/seon/schemas/`.
