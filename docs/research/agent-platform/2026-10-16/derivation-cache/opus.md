---
title: Derivation keys and the context as units (plan A, Opus)
date: 2026-10-16
lane: cache-plan-opus
type: plan
status: research only (root, 2026-10-16). Nothing is queued until the bench metric shows that context-assembly cost matters.
---

# Derivation keys and the context as units: plan A

This plan was written independently of Astra's. It builds on the [dig](../digs/derivation-cache.md) (`3ef112aa4`),
the [block-cost report](../block-cost/report.md) (`d4585c8cd`), and the printer [hybrid](../printer-design/hybrid.md)
P1c/P1d, which this plan reuses and does not redesign. Every number below was measured by this lane on default
(JVM mode, root, read-only: no definitions, no reload) on 2026-10-16, unless it cites another source.

## 1. What exists

`seon.cache/lookup` is the one bounded store. Seon already has three separate versions of "derive, then keep the
result while what it read is unchanged":

| Where | Key | How currency is proven |
|---|---|---|
| `seon.render` `::invocation` (`seon.render/invocation-cache-key`, `seon.render/reusable-invocation`) | branch scope, producer, output, hashes of program, projection and input evidence | read evidence stored in the value, checked with `seon.db/read-evidence-current?` |
| `seon.health/derived` | name, loaded program, connection, generation, revisions of the attributes the last run read | the key itself: per lineage |
| `seon.db/projection-cache-key` | connection, generation, revisions of the projection attributes | the key itself: per lineage |

Every other kind uses either a content key (digests, file bytes, forms) or a commit, connection or branch key
(`::publication`, `::builder-digest`, `::request-view`, `::loaded-digests`, `::basis-valid`, `::branch-end`,
`::admission-end`, `::effective`, `::unmerged`, `::program-content`, `seon.health/untested` and `problems`). A
commit key is correct only when the derivation reads the whole commit. Otherwise every unrelated write misses: the
block-cost miss is publication keyed by main's commit, which MCP recording advances on every request.

## 2. Measurements (default, archive running 2026-10-16)

| Probe | Result |
|---|---|
| `(seon.cache/status)` | 311 of 512 MB held. **0 evictions in every top kind.** `seon.db/behavior-digest`: 1 hit, 3,066 misses, 0 evictions, 1,752 ms spent deriving. `reach-index-by-revision`: 0 hits, 13 misses, 54 MB. `seon.health/derived`: 97 hits, 80 misses, 5.2 s. |
| `seon.context/current-state`, `:chain-68d99c8aef55`, agent `attempt/5548298627d3` (no lint) | first call 232 ms / 346 MB / 31,217 calls / 413 reads; second call 43 ms / 69 MB / 28,461 calls / **413 reads** (nothing at unit level is reused) |
| Block-cost report, agent with branch changes | first call 977 ms / 627 MB with 3 kondo runs; second 190 ms / 124 MB; root's cold observation 12.8 s / 15.9 GB |
| Read evidence of that one block | 357 captured reads → 95 evidence entries over 106 attributes, **5 of them `:all`** (wildcard pulls). `read-evidence-current?` on the **same** value: **false**, 21 ms. |
| Datahike's stored attribute revisions: main's live value compared with three branch heads, 1,184 attributes | revisions equal for **0** attributes, while the datoms are equal for 995, 712 and 860. Never unsound, but the revisions never share across branches. |
| `datahike.db/datoms-diff` per attribute (`:aevt [a]`) | all 1,184 attributes: 55 ms on a near branch, 248 ms and 1.4 s on far-diverged ones. 15 block attributes between a branch commit and main: 2.6 ms. `commit-database` of a basis commit: 1.1 ms. |
| **False-current defect** | A main read of `:seon.fn/form-span`, checked on `:chain-68d99c8aef55`, is reported current although 34 results differ on each side. Filed as `seon.db/evidence-read-on-one-branch-is-not-current-on-another` (cause in §6). |

These findings correct two premises:

- **Eviction is not the defect today.** The behavior-digest miss rate comes from demand: each definition is asked
  for once, so a cache cannot help it.
- **Dropping branch scope from a key (hybrid P1c) does not, by itself, share across branches.** The evidence carries
  a lineage, so a check from another branch either misses (revisions never match) or, through the defect, wrongly
  reports current.

## 3. The rule

> **A derivation is `(f explicit-inputs reads)`. Its key is `[kind code explicit-inputs]`, each by content. What
> it read from a database is the read evidence stored beside its value, never in its key. A later database value
> reuses it when the evidence holds there.**

1. **Key.** The key is the kind, the code identity (the deriving function's definition digest, or the loaded program
   identity for compiled code), and the explicit inputs by content: digests, values, a git tree id, the content
   digest of a printed unit and the printer options. **No database, commit, connection or branch is ever part of a
   key.** A commit id is a valid key only for a derivation of whole values, such as `commit-graph-record`,
   `merge-base`, and the changed-attribute set of two commits in step 2d.
2. **Validity.** The value is stored as `{:value v :evidence [...] :basis commit-id :verified #{commit-id}}`, with
   one slot per key, as `seon.render/retain-invocation!` already does. On a later value D, `seon.db/current-at`
   tries these proofs, cheapest first:
   - a. D's commit is in `:verified`, the commits this entry was already proven on: O(1). This also fixes the
     "false on its own value" probe.
   - b. Same lineage, equal attribute revisions: `read-evidence-current?`'s existing first step.
   - c. Same lineage, revisions moved: the existing index-pattern check over `d/since`. Lineage must mean the same
     branch head ancestry, not the same connection id (§6).
   - d. Any other value: `datoms-diff` between the basis commit and D, restricted to the evidence's attributes,
     then the read patterns matched against the difference. Cost follows the difference: 2.6 ms for 15 attributes
     between a branch and main. The changed-attribute set of a commit pair is itself a commit-keyed cache kind, so
     every entry with that basis shares it.
   - e. Replay with the result digest, Salsa-style early cutoff, when the request was retained.
   - f. Otherwise recompute and replace the slot.

   A proven commit is added to `:verified`. An `:all` read makes step b fail on any write. The cure is at the read's
   producer: a selector derived from the entity schema, not `[*]` (CLAUDE.md, "derive pulled shapes"). The cache
   is never the place to fix it.
3. **Nesting.** A derivation that calls another records `[kind key value-digest]` for each child among its reads. A
   parent is current when every child is current and every child digest is unchanged. That gives early cutoff
   without re-reading the database: a unit over an unchanged task stays a hit even when the task's chain advanced.
4. **Non-database inputs are explicit content.** Checkout bytes are the git tree id of checkout HEAD. Analyzer
   configuration and dependency analysis are their digests. Proof results are database reads like any other. This
   closes the block-cost report's list of what the publication key leaves out.
5. **Weight.** Weight is the honest retained bytes, a count the value already has times a calibration, because the
   byte budget is a memory promise. Recompute cost stays out of the weight. It is visible in `status` as
   `load-ms` per kind against hits. A kind with near-zero hits over a session has a key or demand defect: fix the key,
   or delete the kind. A derivation of one entity whose inputs are all in that entity's write becomes a fact its
   writer stores (behavior-digest).

**The one owner.** `seon.db/derived`, of shape `(derived kind key derive-fn database)`, is a lift of
`seon.health/derived` and `seon.render/reusable-invocation`. It binds `seon.db/*read-evidence-sink*` around
`derive-fn`, stores the value with its evidence through `seon.cache/lookup`, and checks currency with
`current-at`. `seon.cache` stays generic. The three variants in §1 become callers of it.

## 4. The context and pages as units

The owner's model is a vector of units, each `{:seon.render/value v :seon.render/request r}`, each printed by the
one printer and cached. The model context then becomes:

`[help, task, chain, attempts, send-backs, must-read₁…ₙ, exchange₁…ₘ, proofs, step, changes, cost, unread, turns]`

- **Value.** Each unit's value is one `derived` of its own small reads: the task pull, one must-read's resolution,
  one evaluation's saved emission. Its evidence is that unit's reads only. A turn's recording therefore invalidates
  `turns`, `unread` and the newest exchange, and nothing else. `step` (publication) keys on the branch's held
  digests, proofs, task and checkout tree id, with main's reads as evidence. An unrelated main commit leaves it
  current through step 2b or 2d, which removes the block-cost miss: three kondo runs and one `seon.db/with`.
- **Print.** Each unit's printed output is hybrid P1c's render cache: value content digest + options + read
  evidence. That is the same rule with `code` = the render plus printer identity, so P1c is this rule's first
  caller, not a second mechanism.
- **Assembly.** Assembly concatenates the cached outputs in order. `seon.context/model-context`,
  `seon.context/block-views` and the debug page read the same vector, which gives "one derivation, every consumer".
  The provider's cached prefix is the longest unchanged run of units.
- **Delete.** This deletes `seon.context/attempt-sections` and the `attempt-start` re-render split, because
  attempt-level units are simply unchanged across turns, as well as the whole-block `shown` calls and hybrid P1e's
  history text joins.

## 5. Kinds: convert, keep, delete

| Kinds | Fate |
|---|---|
| `seon.render` `::invocation`, `seon.health/derived`, `seon.db/projection` / `value-projection` | convert to `seon.db/derived`; `seon.health` `::reads` deleted |
| `seon.cluster.source/publication`, `seon.cluster.agent/basis-valid`, `branch-end`, `admission-end`, `seon.config/effective`, `seon.health/untested` and `problems`, `seon.cluster.source/unmerged`, `seon.db/builder-digest`, `seon.db/request-view`, `seon.test.runner/program-content` | convert: commit or connection out of the key, reads become evidence |
| content-keyed kinds (schema projections, `file-derivation-key`, `parsed-facts`, `seon.ref/document`, `reach-node`, `call-reach`, task file kinds) | keep as they are; they already follow the rule |
| `commit-graph-record`, `merge-base` | keep: derivations of whole commits |
| `seon.db/behavior-digest` (1/3,066), `reach-index-by-revision` (0/13, 54 MB) | delete: behavior digest becomes a fact stored by the writer beside the definition digest; reach index keyed by reach-node content, or dropped |
| `seon.test.runner/reach-attribute` and `-head` | convert: per-lineage revision keys become evidence |

## 6. Findings and risks

- **The false-current defect (filed).** `seon.db/branch-database` and `seon.db/commit-database` use
  `datahike.api/commit-as-db`, which stamps the caller's connection id and generation onto another branch's commit
  (`datahike.versioning/attached-cache-context`). Step c of `read-evidence-current?` then runs `d/since` across
  branches whose transaction numbers overlap. Today renders are keyed per branch scope, which mostly hides this.
  **P1c's key change makes it reachable**, so its fix (step 2d) must come before or with P1c. Coordinate with
  printer-p1a.
- Revisions never match across branches (0 of 1,184), so cross-branch sharing rests entirely on step 2d. Its cost
  grows with divergence: 1.4 s for all attributes on a far branch, but only the evidence's attributes are diffed.
- A retired branch's basis commit may be collected by GC. Its entries then recompute, as the rule intends (an
  unknown is a miss).
- No change was measured. The parent numbers are in §2. The win claimed for P2 is a projection, and remains a
  hypothesis until P2 runs.

## 7. Phases (each keeps HEAD booting; each red proof names the behavior)

| Phase | Change | Red proof | Deletes |
|---|---|---|---|
| **D0** evidence across branches | `seon.db/current-at` (steps a–d), lineage by ancestry; fixes the filed task | new `seon.read-evidence-test` row: a read on one `d/branch!`, checked on a sibling that differs below the basis t, answers false. `a-fresh-connection-keeps-an-unrelated-read-current` stays green | the connection-id lineage test inside `index-evidence-current` |
| **D1** the one owner | `seon.db/derived`; health and render invocation call it (P1c's key goes through it) | P1c's `the-same-definition-over-the-same-data-renders-once-across-two-branches`; new `seon.db-test/a-derivation-is-reused-after-an-unrelated-commit` (counted derive calls) | `seon.health/derivation-key`, `::reads`, `seon.render/reusable-invocation`'s separate path |
| **D2** publication | key without main's commit; checkout tree id; main reads become evidence | `seon.profile/measure` of `current-state` on the block-cost probe, parent against change in one run, across two MCP requests: second-request kondo calls 0, `seon.db/with` 0; recomputation after a changed proof result and after a stewardship change (two rows) | main-commit term of `::publication`'s key |
| **D3** context as units | `model-context` = vector of units, each `derived` and printed through P1c | new `seon.context-test/a-turn-recomputes-only-its-changed-units` (counted unit derivations after one recorded evaluation: turns, unread, newest exchange); cost: 40-unit build, parent against change, cold and warm | `attempt-sections`, `block-views`' split, whole-block `shown` |
| **D4** kinds | the §5 conversions and deletions, one commit per owner file | each converted kind's existing tests, plus `seon.cache/status` hits > 0 after a second request | behavior-digest kind (fact at writer), reach-index-by-revision |

**Phase status:** research only. None filed.
