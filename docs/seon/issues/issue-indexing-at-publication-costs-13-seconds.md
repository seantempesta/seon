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

## Candidates (not decided)

1. Index issues once per note-set digest: `seon.issue/index!` records the
   digest of the note bytes it indexed; publication skips the index when the
   digest is unchanged and the existing issue facts are already in the
   forked source (a fork keeps them).
2. Move issue indexing out of publication to its own bounded maintenance
   step keyed on `docs/seon/issues` changes (the root maintenance portfolio
   target), leaving publication program-only.
3. Keep it in publication but as the changed-notes delta only.

Related: `render/request-profile` was observed derived 64 times per turn by
the same research (fetch-at-call-time, §2.1); see
[request-profile-is-derived-64-times-per-turn](request-profile-is-derived-64-times-per-turn.md).
