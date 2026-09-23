---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, testing, performance, seconds-not-minutes, hot-path]
---

# A one-test in-process `bin/test-check` takes twenty to fifty seconds

Lane m4-write-bound, 2026-09-23, default pid 90963 (load average 14–15 from
concurrent lanes): `bin/test-check default --policy named --test NS/TEST` for
ONE member whose body stays under its declared 5 s bound:

| run | test | wall |
|---|---|---|
| `f282e641fd07` | `seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage` | 21.7 s (runner) / 22 s (shell) |
| `96f9455829e8` | `seon.db-test/a-system-write-the-writer-never-acknowledges-returns-outcome-unknown-at-the-bound` | 46.4 s (member duration 22.0 s, red on its bound) |
| `727fb8df98a2` | same | 17.6 s, errored: "Worker-global state changed" (another lane's reload removed 14 instrumented `seon.instrument` Vars mid-run) |
| `62323a02cee6` | same | 47.2 s, green |

Two other requests refused before running with "Test run admission refused
inconsistent evidence." and printed no kind, expected or offending value
(`src/seon/test.clj:940-951` throws them; `bin/test-check` prints only the
message) — a refusal without its cause.

The run facts carry no phase clock (`:seon.test.run/*` has `at`, `basis-t`,
`selection-tx`, `change-basis-t` only), so branch creation, context fork,
selection (`seon.fn/gate-sets` 2.3 s was seen in a concurrent profile) and
recording cannot be separated. Wanted: a phase breakdown per run, then the
deletion that makes a one-member run sub-second beside its body.
