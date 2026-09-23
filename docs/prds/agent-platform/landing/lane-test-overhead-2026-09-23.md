---
type: landing
status: landed; content-keyed member evidence next
lane: test-overhead (README §2 acceptance step 5; B4 §2)
---

# Lane test-overhead — 2026-09-23

B4 §2 fast-testing path (README §2 step 5). Owner: agents need "fast immediate tests
that give them feedback for perf and correctness while they are making their edits".

## Algorithmic analysis (before code)

| Step | Was proportional to | Now proportional to | Seam |
|---|---|---|---|
| program digest, new commit | every program row changed since the seal: as-of pull of old rows, `program-fact` on both, then `sort-by pr-str` over 7.6 MB of rows (the sort alone 6.6 s) | rows changed since the seal, read as `[identity definition-digest]` (the producer's `:seon.program/definition-digest`), no as-of, no whole-row printing | `program.cljc` `definition-digest` |
| `admit-run` digest | the whole derivation again inside the transaction: the writer's value carries no commit id, so the memo never hit | datoms since the tested basis (`program-written-since?`); an unwritten program keeps the caller's digest | `datahike.db/speculative-cache-context` (`reference-code/datahike/src/datahike/db.cljc:444`); the writer threads speculative revisions, so a revision key cannot name the in-transaction value (probed, see below) |
| reach index, head moved | a history scan of 10 attributes per call; and each refresh overwrote every retained row with `{:db/id}`, so the index was rebuilt on every call | one map comparison of Datahike's reach-attribute revisions; untouched rows kept | `datahike.db/advance-cache-context` (`db.cljc:423`) |
| reach per recorded member | one closure read per member (317 ms each) | one read over every runnable member at request start (68 members in 1,086 ms), then index hits | `seon.test/run` |
| recording member lookup | every member of the run pulled, plus an as-of view, per recorded member: O(members²) per request | the recorded members only, one history read of this run's refs | — |

Simplest alternative considered for the digest: the commit id alone. Rejected for this
slice: every result write would change it, and reuse then falls to the reach-digest
comparison through as-of reads. That is the next slice (content-keyed member
evidence); it deletes the seal-based digest.

## Probes (default, exact forms in the lane transcript; `tm` = nanoTime wrapper)

- `(runner/program-digest db)`: 5,270–11,511 ms before, 453–755 ms after (pid 90963,
  under load, 2,618 rows since the seal).
- In-transaction revision key vs committed (fixture, pid 90963): `:seon.fn/doc` committed
  `#uuid "6ab35d42…"`, in the writer's value `#uuid "2b3aaf9f…"` — a speculative key
  never matches, so the revision-key memo route was dropped.
- `(runner/reach-digests db [s])` five times on one value after a background commit:
  `[232 0 0 0 0]`; second pass over five symbols before the row fix `[102 106 104 …]`,
  after `[0 0 0 0 0]`.
- Phases on pid 55322 (quiet, cold for the symbol): digest 143, select 26, admission 3,
  host 726 (first call), reach 325 (first call for the symbol), `program-written-since?` 111 ms.
- Heap old gen on pid 55322: 968 MB before, 1,504 MB after the proof runs below.

## Timings

| operation | before | after | why over 1 s |
|---|---|---|---|
| one-member named request (`seon.config-test/converged-apply-uses-carried-projection-and-remains-exact`) | 26–56 s for a 1.9 s body (pid 90963) | 2,555 ms, body 2,020 ms: 535 ms beyond the body | the body |
| fully reused request | 1.8 s | 62–72 ms warm; 832–931 ms when it is the first request after a program change (one digest and reach refresh) | — |
| regression + provenance + reach + interrupted namespaces (`c906d9bb2bd7`) | — | 8,189 ms, 6 executed | the bodies |
| the three long-marked admission tests, by identity (`21867bc50dd7`) | 11.6–15.3 s each (their markers) | 7,350 ms for all three, green | the bodies; their `:seon.test/long` markers cite the deleted cost and can go (test files not in this lane) |
| 36-member batch `seon.program-test` (pid 31476) | — | 51.5 s, bodies 34.9 s: ~460 ms/member | bodies plus the writer (below) |
| 38-member batch `seon.render.value-test`, with the batched reach read | — | recording 383 ms/member (was ~700) | writer (below) |
| steady adoption of these paths | — | 347–375 ms | — |
| first adoption of `runner.clj`/`test.clj` | — | 13–17 s | publication re-analysis and `seon.fn/assert-capability-contracts!` (~36 s inclusive); outside this lane, over 10 s: a publication defect |

## Outside this lane's files (for routing)

- `seon.db/transact!` costs ~250 ms per small result transaction (mean 248 ms over 611
  calls, pid 31476); it is now nearly all of the per-member recording cost. B4 step 12
  wants O(1).
- `seon.fn/declared-reference-edges`: ~0.56 s of whole-program reads per selection
  (1,672 ms over 3 calls, pid 90963), inside `gate-sets`.
- `seon.test.runner/latest-results` (this file, not changed here): one pull plus one
  query per symbol; it made status calls take 48 s under load. Next in this lane.
- The two red `seon.test-provenance-test` members fail at their own fixture writes:
  a test row without `:seon.program/definition-digest` is refused by the writer, and a
  completion for a symbol with no test row is refused. Neither path is touched here
  (retired assumption; the test file is not in this lane).

## Changed paths, sizes

- `src/seon/test/runner.clj` +140 −103; `src/seon/test.clj` +11 −2 (includes
  `green-members` made public for nsa slice 4); new `test/seon/test/admission_digest_test.clj` (43).
- Net src +46: the next slice (content-keyed member evidence, program identity from
  the commit) deletes the seal-based `derive-program-digest`, its memo and cache policy.

## Verification boundary

On default pid 55322 from the checkout, patch loaded at boot and adopted. The
regression is green; the long-marked admission tests green. Parent-vs-commit numbers
for the same probe come from different JVMs (90963, 28202 parent; 31476, 55322 commit)
because default was replaced three times during the lane. Contracts of touched
functions compiled only through their adoption on a warm projection.

## Slice 2 — content-keyed member evidence; the seal-based digest deleted

Algorithm: reuse of a green member compares its stored `:seon.test.member/reach-digest`
(the content digest of its static closure, written at recording) with the current
reach digest, one batched read per request: O(candidates), no as-of view, valid on any
branch or lineage. An unknown digest (members recorded before this slice) executes.
`verified?` compares the same pair. The program digest is the digest of the tested
commit (`create-commit-id`, datahike `writing.cljc:363`), O(1); `derive-program-digest`,
its memo, cache policy, revision key and `program-fact` are deleted, so no runner code
reads the source seal any more. `test.clj` still reads the seal's test-input digest
(`select`, `admit-run`, `verified?`) for the B1 c6 cut.

Host exclusions: 726 ms was PER REQUEST — each request re-walked the destructive owners'
reverse closure through `gate-sets`, including the whole-program
`seon.fn/declared-reference-edges` query. `destructive-reach` is now memoized by
`runner/program-revisions` (Datahike's revisions of the program attributes) plus the
revisions of the data attributes declared edges read (`:seon.fn/invokes` holders,
`:seon.fn/reference-to` keys): first call 728 ms, then 27 ms, and 31 ms after two
result commits (pid 63253). It now follows program changes, not requests.

Proof on default pid 63253 (checkout; slice loaded at boot, `:seon.test.member/reach-digest`
installed as `db.type/string`):

| operation | wall | why over 1 s |
|---|---|---|
| regression `seon.test.admission-digest-test` (`d9febaf6848f`), then reused (`b67c04e78405`) | 4,618 ms, then 861 ms | the body (4 assertions over three fixture transactions) |
| one-member named `seon.config-test/optional-setting-flags-assert-only-true`, first request after adoption | 3,630 ms, body 1,551 ms | the first request at a new program derives host reach (728 ms) and reach once |
| same request, reused | 88 ms, 84 ms | — |
| affected namespaces, first then second (`4936dadde226`, `328062e6c554`) | 7,679 ms, then 3,903 ms with 3 reused by content | bodies; the two `test-provenance-test` reds are the owner's fixture writes (retired assumption, routed) |
| phases (warm): select 118, admission 45, digest 0, reach 0, `program-written-since?` 121 ms | — | — |
| heap old gen | 3,672 MB before, 3,408 MB after | — |

My regression's "a result write keeps the digest" assertion was a retired assumption
(the digest now follows the commit) and is removed; `program-written-since?` carries it.
Sizes: src `runner.clj` +41 −131, `test.clj` +46 −37, schema +3 −1: net src −81.
