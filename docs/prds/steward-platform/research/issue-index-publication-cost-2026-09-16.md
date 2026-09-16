---
type: research
status: current
created: 2026-09-16
tags: [research, steward, issue, indexer, publication, performance]
---

# Issue indexing at publication: the delta is the transaction

Measured and changed on `default` (pid 37572, never stopped, reforked or
restarted), `jvm` mode with explicit custody
(`(seon.operator/connection "default")`), against the live 1,649-note set.

## 1. Where the seam is

`seon.cluster.source/index-issues!` (`src/seon/cluster/source.clj:363`) runs
`seon.issue/index!` over every note, from two callers:

- `publish!` (`:408`) — a COMPLETE build. Its scratch branches from `:db`
  (`registry/branch! … :seon.cluster.registry/from :db`), so the source database
  holds no issue facts and the index writes the whole graph: the 13 s /
  89,000-datom transaction the issue note records (`c2972178b`).
- `upsert!` (`:531`) — the CHANGED-PATH publication the edit hook uses. Its
  scratch branches from `expected-commit`
  (`src/seon/cluster/source.clj:502-504`), i.e. from the previous `current-src`
  commit, so **the issue facts are already present** and only a delta is owed.

## 2. Before (measured, already-indexed database)

| step | measurement |
|---|---|
| `seon.issue/notes "."` | **250 ms**, 1,649 notes (git dating read included, warm) |
| `seon.issue/index-tx` | **1,276 ms**, **3,333 tx forms** (1,649 bare `{:seon.issue/id slug}` upserts + 1,649 full replacement maps + 35 retracts) |
| applying that tx | 195 ms, 358 datoms (278 added / 80 retracted) — the genuinely changed notes |
| re-index of the result (nothing changed) | index-tx **1,174 ms**, 3,298 forms, transaction 74 ms, **1 datom** (`:db/txInstant` alone) |

So the resolver was already idempotent in DATOMS — and paid full price in WORK.
`index!` derived `index-tx` **twice** per call (once in the writer through
`[:db.fn/call #'index-tx …]`, once afterwards only to read its metadata), so an
unchanged note set cost ≈ **2.6 s of derivation plus a transaction** on every
changed-path publication.

Inside the 1,276 ms:

| phase | ms |
|---|---:|
| `parse-note` over 1,649 notes | 610 (of which the character-sequence `words` split: **484**) |
| class membership (`for [member parsed …]` per note — 1,649² tag scans for 115 members over 13 classes) | **691** |
| `db/pull '[*]` of the 1,649 existing issues | 51 |
| token resolution (679k tokens) | 56 |
| `citation-index` build (13,464 spellings) | 12 |
| `citation-attributes` (schema rows) | 15 |
| `qualified-token` over every token | 20 |

## 3. The change (`src/seon/issue.clj` only)

1. **`replacement-tx` emits the delta.** An attribute already holding its
   desired value contributes nothing; only differing attributes are retracted
   and only differing attributes are asserted. An unchanged issue contributes
   **no form at all**.
2. **Presence upserts only for new slugs.** `{:seon.issue/id slug}` exists so a
   class note can name a member minted in the same transaction by lookup ref;
   for the 1,649 slugs the database already holds it was a pure re-assertion.
3. **`index!` writes nothing when the delta is empty**, and derives `index-tx`
   ONCE: the diagnostics come from the derivation that produced the delta
   instead of a second full pass whose transaction was discarded. The writer
   still derives the transaction it commits (`[:db.fn/call #'index-tx …]`), so
   the authority re-decides the write; only "write nothing at all" is decided
   from the pre-read, on a connection its publication holds privately.
4. **`words` is one index scan** instead of `partition-by` over boxed
   characters. Output verified IDENTICAL over all 1,649 notes before the edit.
5. **Membership is one pass** over the notes (`members-by-class`) instead of one
   scan of every note per note.

No new attribute, no digest, no bookkeeping entity: "unchanged" is derived by
comparing the notes' facts with the facts the database already holds.

## 4. After (same JVM, same note set, adopted through the edit hook)

| step | before | after |
|---|---:|---:|
| `words` over all notes | 484 ms | **74 ms** (31 ms unloaded) |
| `index-tx` (changed note set) | 1,276 ms | **358 ms** |
| tx forms emitted | 3,333 | **185** |
| applying it | 195 ms | **4 ms** (358 datoms, unchanged) |
| `index-tx` on an already-indexed database | 1,174 ms → 1 datom written | **303 ms → EMPTY, no transaction** |
| `index!` on an unchanged note set | ~2.6 s derivation + 1 transaction | **~0.3 s derivation + 0 transactions** |

Live proof on the real 1,649-note set (`d/with`, nothing committed to
`default`):

- unchanged set: `(empty? (seon.issue/index-tx stable notes))` → **true**;
- one note's `status: open` → `resolved`: delta is **2 forms**, touching exactly
  entity `42642` (`issue-indexing-at-publication-costs-13-seconds`), and the
  transaction is **3 datoms** (retract, assert, `:db/txInstant`);
- `:seon.issue/members` 115 before = 115 after and 1,842 citation entities
  unchanged, so the one-pass membership derives the same facts.

A real changed-path publication of `src/seon/issue.clj` after the change
(`bin/seon init --dev default --changed src/seon/issue.clj`) reported
`incremental scalar publication: 1 paths` and converged in **106.65 s** wall —
queued behind two other lanes' publications and dominated by clj-kondo over the
whole tree, schema declarations, program reconciliation, SCI acquisition and JVM
instrumentation. That number is NOT the index's share: the index's share is the
0.3 s derivation and zero datoms measured directly above, down from ≈2.6 s and a
transaction. The publication seam's remaining wall time belongs to other phases
and is out of this lane's boundary.

## 5. What is NOT fixed: the complete build

`publish!` starts from a scratch that holds no issue facts, so its delta IS the
whole graph: the 13 s / 89k-datom transaction stands. Making it cheap means
forking the issue facts from the previous `current-src` commit into the complete
build's scratch — a NEW mechanism at a seam that deliberately builds from `:db`,
so it is recorded here and on the issue note rather than built. The derivation
cost of that build drops with §3 (1,276 → 358 ms), the datom count does not.

## 6. Verification boundary

- Live, on `default`, `jvm` mode with explicit custody: every number above.
  Nothing was committed to `default`'s branch by this lane — the transactions
  were measured with `datahike.api/with` on database values.
- **The in-process regression could NOT be run**: `default`'s shared
  `seon.test-support/database-base` is poisoned in this JVM — every run of
  `seon.issue-test/an-unchanged-note-set-indexes-without-a-transaction` returns
  `Could not locate clojure/tools/build/api__init.class … on classpath` as an
  uncaught exception (1 error, 0 assertions). Retried once after two minutes
  with the same result. Per the standing rule the base was not rebuilt; the
  regression is for the orchestrator's cold gate.
- Publication under this lane's window was contended: three `bin/seon init
  --dev default` invocations from different lanes were queued at once and one
  foreign publication (`src/seon/test.clj`) failed its 180 s bound
  (`logs/current-source-failure.log`, operator exit 124) before this lane's
  edits adopted. Adoption of this lane's code IS confirmed in the running JVM
  (the new definitions resolve and the measurements above are the adopted
  code's).

## 7. Regression

`test/seon/issue_test.clj`
`an-unchanged-note-set-indexes-without-a-transaction`: indexing an
already-indexed note set leaves `seon.issue/index-tx` empty and the database
basis `:t` unmoved, and one changed note produces a delta touching only that
issue's entity.
