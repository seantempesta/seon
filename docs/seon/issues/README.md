---
type: reference
status: active
tags: [reference, issue, index]
---

# Issues — Lightweight Tracking

One note per problem. Tracking is deliberately lightweight: a single lifecycle,
a single severity vocab, an `archive/` for closed notes, and one ranked owner
schedule.

## Lifecycle (`status`)

```text
open  →  resolved    (it was fixed)
      →  superseded  (it no longer applies — design changed, dead code removed)
```

There are exactly three values. `resolved` and `superseded` are both "closed"
and their notes live in `archive/`. Nothing else (`active`, `completed`,
`verified`, `closed`, `archived`) — those were drifted spellings, now normalized.

## Severity

Exactly three values, required on every issue:

- `blocker` — blocks shipping / other work; fix first.
- `friction` — slows agents or humans down; real but not blocking.
- `cleanup` — tidiness, dead code, duplication, naming, convention drift.

Architectural issues carry an `architecture` tag (the lens is in the tags, not a
separate severity).

## Layout

```text
issues/
  README.md     ← this file (the convention)
  index.md      ← ranked schedule for every open note
  *.md          ← OPEN issues only
  archive/*.md  ← resolved + superseded issues
```

After triage, top-level `*.md` is ONLY open issues. When an issue is fixed or
no longer applies, set its `status` and `git mv` it into `archive/`.

## The index is the schedule

`index.md` is the owner's ranked execution schedule. Every top-level open issue
appears exactly once with one disposition:

- a named running lane;
- a named future wave; or
- after verification makes it moot, a resolved/superseded archive entry with
  the dissolving commit or ruling.

Severity still ranks the work inside those destinations; it is not itself a
destination. Update the schedule whenever an issue opens, closes, or changes
owner. `bin/issues-index [--check]` only VALIDATES: it reads the notes plus
`index.md` and fails on a missing, duplicated, or severity-mismatched row, a
row naming a note that is no longer open, or a blank destination. It never
generates or overwrites the schedule.

## Frontmatter template

```yaml
---
type: issue
status: open          # open | resolved | superseded
severity: cleanup     # blocker | friction | cleanup
tags: [issue, agent]  # + architecture, web, schema, database, flow, … as fitting
---

# Short imperative title

## Problem

One observed mismatch.

## Evidence

Current file/symbol plus a failing test, live observation, or exact source
existence result.

## Owner

The one namespace or mechanism that should be strengthened.

## Acceptance

Behavioral falsification, not exact prose.
```
