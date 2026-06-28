---
type: orchestrator
status: active
tags: [orchestrator, issue, index]
---

# Issues — Lightweight Tracking

One note per problem. Tracking is deliberately lightweight: a single lifecycle,
a single severity vocab, an `archive/` for closed notes, and a DERIVED index.

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
  index.md      ← GENERATED — do not hand-edit (see below)
  *.md          ← OPEN issues only
  archive/*.md  ← resolved + superseded issues
```

After triage, top-level `*.md` is ONLY open issues. When an issue is fixed or
no longer applies, set its `status` and `git mv` it into `archive/`.

## The index is derived

`index.md` is a projection, regenerated — never hand-maintained (Seon "derive
don't store"). Regenerate after any frontmatter change:

```bash
bin/issues-index
```

It scans the OPEN issues' frontmatter and groups them by severity (blocker →
friction → cleanup), with a derived lane (Core / UI / agent / …) per row.

## Frontmatter template

```yaml
---
type: issue
status: open          # open | resolved | superseded
severity: cleanup     # blocker | friction | cleanup
tags: [issue, agent]  # + architecture, web, schema, database, flow, … as fitting
---

# Short imperative title

What's wrong, where (file:line), and what it should be.
```
