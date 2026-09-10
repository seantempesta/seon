---
type: research
status: complete
tags: [schema, database, agent]
---

# Data lane — chart roadmap steps 3–7, 2026-09-09

Read end to end: the data chart PRD and raw-data-forms probe, active roadmap
and working edge; turn PRD §10 and §13–§15. AGENTS.md has no separate §10:
its opening copies the turn PRD lane rules. The owner's assignment overrides
the chart's older map-arity id example, singular runtime turn, and `to` wake.

## Dependency ledger

- Java MessageDigest SHA-256 through `src/seon/id.clj`; existing evaluation
  and digest callers retain their ordered-vector identities. The new entry
  hashes exactly `(pr-str data)`, default length 12.
- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:640`
  resolves unique identity upserts; `:64` recognizes `"datomic.tx"`;
  `:738` expands nested maps; `:997` retracts entities and incoming refs.
  Existing first-party seams: `seon.plan/add-step-call`, message delivery,
  and `seon.turn/open-call`.
- Canonical fixture: `seon.test-support/with-database`; armed fast loop and
  isolated path-only gate. `SEON_TEST_WORKERS` capped at 3; final gates use 1 after the
  parallel-base loss was observed.

## Initial live evidence

Default PID 92059, PREPL 53086. MCP status returned health/Flow unknown,
`Read timed out`; JVM arithmetic returned 2 in 1 ms, immutable schema/plan
probe in 3 ms. Plan eid 36225. Completion was `db.type/instant`; item id
was `db.type/string` and `db.unique/identity`. Existing issue updated.

## Shared-tree boundary

Cookbook research probes, help trial, trial reports, trial test, and its
issues were already edited/untracked. They are excluded from this lane's
changes and gate snapshots. No foreign session is operated.

## Verification

First slice: the identity entry and its regression. Scoped gate and fast
iteration: 3 tests, 33 assertions, zero failures/errors (identity and plan API
snapshot). Explicit platform-only snapshot of the identity files also green.
Exact check: `(seon.id/id 'abc)` = `"ba7816bf8f01"`; length 8 = `"ba7816bf"`.
The independently progressing plan changes are not part of that identity
commit or its platform snapshot.

Identity platform: 83 tests / 490 assertions, zero failures/errors.
Plan fast iteration: 21 tests / 125 assertions, zero failures/errors.
The first isolated plan gate hit the recorded parallel-base filestore-key
loss; its isolated title-update confirmation passed. The separate shown-text
test used a base SCI context without agent call preparation. It now acquires
the agent context through the same `fork-for-turn` owner as production.
Final scoped and platform results follow below.

RESET NEEDED once when the complete schema batch lands; the owner
reforks default once. This lane uses `tmp/data-lane-root` for live proof.

Plan/component slice: scoped gate 22 tests / 134 assertions; platform 83
tests / 490 assertions, all green. The shown-text fixture now refuses
failed setup loudly and seeds its cluster through the canonical owner.

Plan raw reports are preserved in `data-lane-plan-reports-2026-09-09.edn`
(2107 UTF-8 bytes), produced with Datahike `with` on the canonical publication.

The message slice was verified in `tmp/data-lane-wt` at `dbec8be7e`, with
only owned changes and the necessary family/input renderer hunks. Its scoped
gate passed 28 tests / 256 assertions; platform passed 83 / 490. Cookbook
then landed `7e7c74f01`; combined verification uses that committed renderer
baseline, preserving the cookbook's remaining research edits.

Combined fast loop proof passed after preserving the historical outside-wake
transaction when a handled inbox edge retracts. The new regression verifies
that handling cannot erase the episode's outside-wake basis.

## Fresh-schema live proof

Scratch root `tmp/data-lane-root`, cluster `data-lane`, URL
`http://127.0.0.1:7966`, PREPL 58995. Fresh publication
`6aa1feb1-dcb2-5441-af1e-f001f402e084`; subsequent development adoption
`6aa1ffb9-3390-5a13-8aea-7bf770beaaa8` completed SCI acquisition and JVM
instrumentation. Default was never stopped, restarted, or reforked.

`seon.data-shapes-test/plan-probe` and `message-runtime-probe` ran against
that cluster's published database with `datahike.api/with`. Exact reports:
[data-lane-raw-reports-2026-09-09.edn](data-lane-raw-reports-2026-09-09.edn),
4,565 UTF-8 bytes. Completion emits 2 datoms; add 6; current 2; remove 6.
Plan identity remains eid 36351. Message send emits 6 datoms, opening 8,
answering 9 (including the inbox retraction and read transaction), closing 2,
and adding an attribute-only listen 3. Message ids `dc5442b7` / `102168c2`
are fresh 8-character events. All exact instants and refs are in the artifact.

The canonical Juniper installer completed through the actual agent graph in
9,116 ms and generated system turn `b42bd37d1529`. Its plan eid 36576,
settings, and runtime all point to Juniper through their owner identities.
The no-provider override is true; the attempt query returned no rows.
[data-lane-juniper-prompt-2026-09-09.txt](data-lane-juniper-prompt-2026-09-09.txt)
contains the exact 7,341-byte acquired prompt. HTTP `/agent/juniper` returned
200 and 20,499 bytes, saved in
[data-lane-juniper-page-2026-09-09.html](data-lane-juniper-page-2026-09-09.html). This proves route delivery, not browser paint: CUA had
no browser surfaces, and native Chrome returned `cgWindowNotFound` (-10005),
matching the existing browser-observation issue.

Loading the whole canonical test fixture in one MCP call exceeded that
call's 60-second bound. A separate live JVM arithmetic probe returned 2 in
0 ms; loading the probe namespaces took 18 ms. The small raw probes and
Juniper installation subsequently succeeded, so this is a bounded-call
failure, not an inference that the cluster was down.

## Boundaries and next owners

- Chart step 8 owns matching authored listen patterns. This batch supplies
  their attribute/entity/value data and verifies logical-value round trips.
- Steps 9–12 own the runtime block pair. The generic attribute-map HTML is
  recorded in `docs/seon/issues/runtime-component-still-renders-as-an-attribute-map.md`.
- General reverse-ref writes and partial entity-map validation remain a
  separate admission defect, recorded in
  `docs/seon/issues/raw-write-validation-refuses-reverse-refs-and-partial-entity-maps.md`.
  Attempt links and model gauges now use forward `:db/add` operations.
  Refused attempt recording propagates its diagnostic and prevents another
  provider call or reply freeze. Error evidence keeps its bound marker inside
  its existing stored text, never as retired evaluation attributes.
- During final verification a publication lane held `src/seon/fn.clj` and
  `test/seon/cluster/source_test.clj`. They are excluded from the final owned
  path list and commit; no foreign session was operated. An earlier broad
  integration snapshot included them and is not the final path-only proof.

RESET NEEDED: one refork of default after this complete batch. No migration
or intermediate default reset was performed.

## Combined gate results

The broad integration snapshot passed 147 tests / 1,140 assertions, including
all loop-proof tests and the message, provider, problems, and turn namespaces.
It included the publication lane's then-uncommitted files; this is explicitly
an integration observation. After excluding those paths, the owned-path gate
passed 64 tests / 661 assertions (`seon.data-shapes-test`,
`seon.loop-proof-test`, `seon.turn-test`, `seon.error-test`). The subsequent
inbox-only correction and explicit platform result are recorded below.

The final review preserves `reply-size` as the actual staged reply's size;
removing the plan digest does not repurpose it to count a printed source
vector. The no-provider system-turn case now uses the same opening/reply
transaction basis as wake answeredness, so generated opening text alone
cannot mark an inbox message read.

Cookbook's necessary keyword and render-input hunks were applied only after
its renderer files landed clean in `7e7c74f01`. There is no uncommitted held
renderer patch remaining from that coordination. The runtime block's future
pair belongs to chart steps 9–12, as recorded in the linked issue.

Final inbox/loop gate: **9 tests / 167 assertions, zero failures/errors**.
This includes the system-only inbox regression and `seon.loop-proof-test`.
Final live development adoption: `6aa203d1-1eb6-5525-ac0c-a0048fc4113e`,
confirmed through the cluster's `:seon.source/commit-id`. Juniper's owner refs
and no-provider setting remained present; the durable fault query returned
an empty vector (live probe 1,536 ms).

The dated complete file list, including the identity and component slices,
is [data-lane-owned-paths-2026-09-09.txt](data-lane-owned-paths-2026-09-09.txt).
Identity commit `24adad072`; plan/component commit `dbec8be7e`; the final
message/runtime batch is the commit carrying this note.

Explicit platform gate: **84 tests / 505 assertions, zero failures/errors**,
using `SEON_TEST_WORKERS=1 bin/test --platform --paths <owned files>`.
No all/full suite was run. Scratch `data-lane` was stopped by its root-scoped
operator, PID 62339 was confirmed exited, and its root and the temporary
message worktree were deleted. Holderless test roots belonging to this lane
were removed after checking the process table; foreign roots and edits were
preserved.
