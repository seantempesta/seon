---
type: issue
status: open
severity: defect
created: 2026-09-23
tags: [issue, testing, fixtures, schema, agent-platform]
---

# Fixture agent rows lack the now-required `:seon.agent/branch`

`:seon.agent/branch` became part of the agent entity (`resources/seon/schemas/seon.agent.edn:1`,
commits `1ada78050`, `8a069b5e4`, `7f6718507`). Test fixtures still write bare
`{:seon.agent/id "…"}` rows, which the write refuses: "expected the required key
:seon.agent/branch with a keyword, got a map missing :seon.agent/branch".

Sightings on default pid 90963, 2026-09-23:
- lane m4-write-bound: run `b2f5b25ee8cc` (51 members): 42 reds, most of them
  my.plan/note/message/agent tests failing at this fixture write; run `deb20e255a9d`:
  `seon.db-test/retained-read-evidence-invalidates-only-on-a-depended-attribute` errors at
  `test/seon/db_test.clj:668`.
- lanes turn-stability, mcp-and-stop and entrance-supplier report the same class in
  their landing notes (`seon.cluster.agent-test` 11 errors).

`rg -c '\{:seon.agent/id "[^"]*"\}' test` finds bare agent rows in more than twenty
test files (18 in `db_test.clj`, 14 in `effect_test.clj`). This is the "retiring
without converting" habit: the requirement landed without its fixture callers. The
fix is one scripted conversion (a canonical agent-row helper, or the branch
supplied by the fixture) over every file, one owner, then the reaching tests.
