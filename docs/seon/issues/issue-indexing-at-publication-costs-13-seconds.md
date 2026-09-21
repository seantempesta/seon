---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [publication, issue, performance, class/p1]
---

# Issue indexing at publication costs 13 s and 89k datoms per complete build

## Problem

`seon.cluster.source/publish!` and the changed-path publication both call
`index-issues!` (`src/seon/cluster/source.clj:363`, called at `:408` and
`:531`), which runs `seon.issue/index!` over every note under
`docs/seon/issues` (1,644 notes) against the publication's connection. Since
the derived citation resolver landed (`7d47d77b7`), a complete publication
writes the whole issue graph again: the gate session's bookkeeping research
(`c2972178b`) observed one 13 s / 89,000-datom publication transaction on
`default`. A complete build starts from a scratch source database value, so
the resolver's idempotence (0 retractions on re-index of the same database)
does not help there: every complete publication pays the full index.

## Why it matters

Publication is on the edit hook's path; a 13 s transaction per complete
build is the class the ten-second-start rule names, and it holds the store's
writer while agents wait.

## Landed for the changed-path publication (2026-09-16)

Candidate 3 (the changed-notes delta), derived rather than remembered — no
digest attribute and no bookkeeping entity. `seon.issue/index-tx` now emits only
the difference between the notes and the facts the database already holds, and
`seon.issue/index!` writes NOTHING when that difference is empty, deriving the
transaction once instead of twice. Measured on `default` over the live 1,649
notes: `index-tx` 1,276 → 358 ms, tx forms 3,333 → 185, an already-indexed note
set 1,174 ms + a transaction → 303 ms + **no transaction**, and one changed note
→ 2 forms / 3 datoms on that issue's entity alone. Two hot spots found on the
way: the character-sequence `words` split (484 ms) and a pairwise class
membership scan (691 ms).

Numbers and the verification boundary:
[issue-index-publication-cost-2026-09-16](../../prds/steward-platform/research/issue-index-publication-cost-2026-09-16.md).

## Still open: the complete build

`publish!` (`src/seon/cluster/source.clj:408`) branches its scratch from `:db`,
so a complete build holds no issue facts and its delta IS the whole graph: the
13 s / 89,000-datom transaction stands. Making it cheap means forking the issue
facts from the previous `current-src` commit into that scratch — a new mechanism
at a seam that deliberately builds from `:db`, so it is an owner decision, not a
lane's. The remaining candidate, moving issue indexing out of publication into
the [TARGET] root maintenance portfolio keyed on `docs/seon/issues` changes,
would close it from the other side.

Related: `render/request-profile` was observed derived 64 times per turn by
the same research (fetch-at-call-time, §2.1); see
[request-profile-is-derived-64-times-per-turn](request-profile-is-derived-64-times-per-turn.md).

## Adoption-margin measurement — 2026-09-17

The armed fresh-store publication measured complete issue indexing at 3176 ms
before the canonical encoding change and 2317 ms after it. On default the
measured complete index was 1908 ms. These are current observations, not the
historical 13-second result being relabelled.

A separate adoption cost was found by the phase-silence probe: `identity-row`
recomputed `citation-attributes`, parsing every stored schema form, once per
issue. `seon.issue/adopt!` now derives one selector and reads all issue rows
with `seon.db/pull-many`; the existing adoption regression retains unchanged
rows, citations, class membership and later edits. The whole-entity writer
validation and issue transaction remain unchanged. Detailed measurements and
the verification boundary live in
[adoption-margin-2026-09-17](../../prds/steward-platform/research/adoption-margin-2026-09-17.md).

## Slice 4 decision boundary — 2026-09-23

The current `publish!` forks the previous published commit, so the older
“still open: complete build” description above no longer describes incremental
publication. On the lane's scratch cluster, the unchanged Markdown index cost
728.880 ms in an 8,171.094 ms one-file publication plus adoption. It still reads
all Markdown notes, runs `git log` over the issue directory, resolves citations
against the program, and derives class membership over all notes. It is
O(notes + program), even for a docstring.

The stored issue row does not retain tags or full note text; `:seon.issue/problem`
is an extracted section. Documentation is outside the source digest by explicit
policy. The publication transaction's changed program identities therefore
cannot identify every changed Markdown input or reproduce class membership.
No new cache or remembered roster was added. Three concrete choices:

1. **Recommended: remove Markdown indexing from incremental code publication.**
   Keep cold population and explicit `seon.issue/index!`; keep code-derived
   issue reconciliation scoped to changed identities. Small deletion, no schema
   change, saves the measured 729 ms. Give up opportunistic Markdown refresh
   on an unrelated source edit; the explicit operation already exists.
2. **Pass changed note paths to the existing issue owner.** Extend its request
   and caller to admit changed/deleted notes and recompute affected classes.
   Preserves incremental Markdown updates, but requires an explicit Markdown
   change source and class-member input; program identities alone do not supply
   either. Medium cross-caller change, no performance claim yet.
3. **Declare full note source and tags as canonical issue facts.** Query affected
   citations/classes from that value and reconcile selected identities. Larger
   schema and ingestion change, RESET NEEDED; retains automatic citation
   derivation but adds durable authored facts. No source cache is justified.

The lane requested the ruling before changing this behavior. Evidence:
[publication phases](../../prds/steward-platform/research/one-jvm-publication-delta-2026-09-23.edn).
