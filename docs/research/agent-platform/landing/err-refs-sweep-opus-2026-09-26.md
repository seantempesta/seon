---
type: landing
lane: err-refs-sweep-opus
status: implemented and committed; the regression is not yet run (root runs it after adoption); two patches for root
date: 2026-09-26
task: 480378b94c9b (seon.error.refusal/errors-reference-worlds-never-copy-them), 4c559398c4be (flat suppressed causes)
---

# err-refs-sweep-opus: errors hold references, never worlds

## Broken or unverified first

- **The regression has not run.** Default runs a committed archive, so the new code is unproven there until root adopts it
  and runs the list below. What was proven: the rewritten `seon.error.refusal`, loaded into a disposable scratch
  namespace on default (JVM mode, removed afterwards) against the live projection, database and connection; the
  contract-compile check over all 11 changed sources against this checkout's schema files (0 findings, 767 ms); the new
  chain schema composed into a declaration projection (flat chain valid, `suppressed-by -1` refused); and `tasks-tx`
  read-only (one new task).
- **Worlds are recognized only when a projection is handed.** Which values count as worlds comes from the registry: every
  schema declared `:seon.schema/identity-only` (database value, connection, projection, environment, SCI context). With no
  projection in hand (`seon.schema/handed-projection` unbound, as in JVM-mode prepl calls), `references` returns its input
  unchanged. So the producers below are also fixed directly, and the instrument wrapper passes its own arming projection
  explicitly. Stores, Flow graphs and channels are not declared identity-only. They print as `#object[...]` (small), so
  they were not added.
- **Live evidence of the defect (before):** on my MCP branch, `(env/require-environment {:seon.schema/projection p :x 1} …)`
  put more than 85,000 characters of the projection into the reply. That error also failed its own output contract,
  because `:x` is not a qualified keyword in `::available-members`; this lane deletes that key. In JVM mode, a plain
  `pr-str` of an error holding the projection threw `OutOfMemoryError: Required array length 2147483638 + 80`, so printing
  a projection needs more than 2 GB.
- `kernel/failure-value` drops SCI location links from the chain. With flat indexed links, that removal must renumber the
  relations (`refusal/without-links`), which is in the cross-owner patch. Until it lands, a suppressed link's index can be
  off by the number of dropped SCI wrapper links.
- Root's MCP acceptance (the audit probe for these rows) is still to do after adoption.

## Design

What already existed: `seon.sci.admit` replaces registry-declared identity-only values with their identity
(`admit.clj:305-346`, `schema/identity-only-projector` at `schema.clj:3831`), but only at storage. Returned and thrown
values, and every cause link's `ex-data`, carried the worlds whole.

The owner of the fix is `seon.error.refusal`, which is the one normalization point.
- `references [projection value]` uses the same registry projector. It replaces each world with its identity, recurses
  through maps, vectors and sets, does not enter records or sequences, and returns an unchanged collection identical.
  Cost: 0.16 ms on a real contract refusal, 0.013 ms per ordinary diagnostic.
- `diagnostic` references its members and `chain` references each link's `ex-data`, using the operation's handed
  projection. Two-arity `chain` and `references` take the projection explicitly.
- `chain` is now flat, redone after the reverted f4d490885. The primary chain comes first (outermost to root), followed by
  each suppressed throwable and its causes. A suppressed link names its suppressing link's index
  (`:seon.error/suppressed-by`) and each of its causes names the link it caused (`:seon.error/cause-of`). A throwable seen
  once is not linked again. There is no recursive schema: two `[:int {:min 0}]` members on `:seon.error/link`.
  `without-links` removes links and renumbers the relations.

Producers fixed at the source (the reference that replaces each copy):
- `instrument/violation` and `minimal`: the arming projection is passed in. The whole refusal (offending arguments,
  per-problem parent and input) is referenced. The dead `caps` rebinding is deleted.
- `my.program/read-result`: `offending` is now `{subject, basis-t}` and the failure is `:seon.error/throwable`, so the
  whole chain is kept. The copies `read-failure-message`, `read-exception-data` and the hand-built `exception-class` are
  deleted.
- `env` (environment-state, replace-environment!, absent-member-error, construct, scope ×2, supplied-agent-id,
  require-environment, carry-state): `offending` is the member-name vector (`members-of`) or the type. Deleted:
  `::available-members` (it also refused unqualified keys), `:seon.env/supplied` ×6, and the data copies of member and
  boundary. supplied-agent-id no longer copies the whole environment; its cluster name is the reference.
- `context/capture-tx`: `{run id, basis-t, arm flags}` replaces the whole capture request (database plus rendered
  context).
- `cluster.boot/start!` `failed`: carries cluster name, reached `:seon.boot/layers`, pid and start-instant. The live
  partial instance stays in the one roster (`running-instances`) under its name. The `dissoc :seon.boot/instance` in
  `request!` is deleted.
- `sci.reader` over-bound: `{length, max-source}`, with no text copy.
- `cluster.reply` over-bound: the text is carried once (as `::unreadable`). Deleted: the top-level `::text` and
  `data ::text` copies.
- `effect/request*` admission refusal: the over-bound request copy is deleted. The admission marker plus `max-bytes`
  remains.
- `ai`: the provider body is carried once (`::body`). The duplicate `:seon.error/offending` is deleted at
  `unreadable-stream-data` and at the non-2xx arm.
- `error/prepare`: the thrown `ex-data` (`error-value`) and the refused-declaration `offending` are referenced under the
  supplied projection, and the chain gets the explicit projection.

A digest was not added for over-bound text: nothing stores that text under a digest, so a digest would reference nothing.
Length and bound are the honest reference.

## Before and after (three real errors, printed bytes, with the printer stopped at 20 MB)

| error | before | after |
|---|---|---|
| contract refusal, `my.program/callers` with a request carrying db and projection | >20,000,000 (projection alone >2 GB) | 2,022 |
| `my.program/read-result` over a request carrying db, failure `ex-data` carrying projection and connection | >20,000,000 | 836 |
| cause chain whose `ex-data` holds db, projection and connection | >20,000,000 | 515 |

`env/require-environment` and `env/scope` over a projection-carrying map were also over 20 MB before. After the change
they hold a member-name vector; this was not measured live because loading env into a scratch namespace would re-register
`seon.env/environment?`.

## Lines

Source +203 −87 across 11 files. Most of the growth is `refusal.clj` (+125 −25: `references`, flat `chain`,
`without-links`, and docstrings). Tests +99 −22. Schema: 2 link members. Task: 1 new. This is over the ~100 guide. It
replaces a copy at every producer with one registry-driven normalization plus about 30 deleted copy lines at the
producers.

## Suspected sites routed to root (not in this lane's files)

- `call_preparation.clj:846,853`: a Throwable stored as `offending` instead of `:seon.error/throwable`, which loses the
  chain. `:878`: a supplier-produced value as `offending`. It can be a world (a supplied db or connection); `diagnostic`
  now references it when a projection is handed.
- `sci/kernel.clj` ~700: SCI's `:sci.impl/callstack` volatile inside link `ex-data`. The frames can hold namespace
  objects and metadata. Recommendation: drop it where kernel already removes SCI location links.
- `db.clj:4633/4732/4779` write-observation copies of the whole transaction (err-s3 finding; still open).
- `schedule.clj:580-629`, nested write refusals `turn.clj:3854,3920,5523`, `fault.clj:256`, `agent.clj:1388,1635`,
  `source.clj:52`: not inspected by this lane. When a projection is handed, their `diagnostic`/`chain` members are now
  referenced.
- `my/program.clj:617` (`refusal`) copies the breakage report three times (as data, as offending and as merged keys). It
  holds no worlds; it is a duplication to collapse later. `:817` native-call arguments: not a world.
- `seon.cluster.reply.edn`: an over-bound reply is declared as `unreadable-error`, which requires the text. A separate
  over-bound declaration would drop the last copy.

## Patches for root

- `tmp/err-refs-sweep-opus/db-references.patch` (db.clj). `malformed-query-pattern-error` keeps the query form, not the
  request that carries the database. `query-input-shape-error` offending arguments are referenced under the database's
  carried projection. `transact!` connection-not-live carries `connection-identity`.
- `tmp/err-refs-sweep-opus/cross-owner.patch`:
  - `sci/kernel.clj`: `without-links` in place of `(remove sci-location?)`.
  - `eval/drive.clj:400`: releases the partial instance from the roster by name.
  - `test/seon/sci/eval_test.clj:616`: `violation` now takes a projection.
  - `script/seon/operator.clj:1565`: the root message comes from the primary chain.
  - `seon.program.edn`: deletes `read-failure-message` and `read-exception-data`.

## Root runs

1. Static: `bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`,
   `--test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn`.
2. After adopting this commit and both patches: `--ns seon.error.refusal-test` (includes the class regression
   `every-producer-references-the-worlds-it-was-handed`), `--ns seon.env-test`, `--ns my.program-test`,
   `--ns seon.cluster.reply-test`, `--ns seon.sci.reader-test`, `--ns seon.effect-test`, `--ns seon.error-test`,
   `--ns seon.instrument-test`, `--test seon.sci.eval-test/compiled-runtime-metadata-cannot-be-changed-by-agent-code`,
   `--ns seon.cluster.boot-test` (isolated; it boots a partial cluster).
3. Acceptance through MCP on a fresh branch: `(env/require-environment {:seon.schema/projection (seon.schema/handed-projection) :seon.x/y 1} :seon.test/probe)`
   must answer a small error whose `:seon.error/offending` is `[:seon.schema/projection :seon.x/y]`.
