---
type: landing
lane: shrink-web-opus
status: "S1+P2 committed 978bac337, unproven on a live JVM until root restarts default (RESET NEEDED); S3+ on hold per root"
created: 2026-09-26
---

# shrink-web-opus landing

Plan: `docs/research/agent-platform/shrink-plan-render-web-transcript-value-2026-09-26.md` (50fb616e8).

## Broken things first

- **The save gate cannot prove a lane's diff while default runs a published archive.** `bin/test-check default --gate --changed-path …`
  refused at once. The error was "Changed paths lie outside the JVM's source directory.", raised from `seon.cluster/publication-paths`
  (called from `refresh-source!`), because default's root is `data/source/bb07fdf08…`. Full output: `tmp/shrink-web-opus/gate-s1p2.out`.
  Root is logging a task for the gate to accept a commit.
- **S1+P2 have not been run on any JVM.** The after-numbers and the six focused tests are owed after root's restart.

## 978bac337: S1 (read evidence without results) and P2 (call reach once per attribute revision)

- **S1.** Removed `:seon.db/retain-read-results? true` from the seven render read-evidence sites: `render.clj`
  `render-call`; `web.clj` ×5; `walk.clj` root pull.
  - `db/read-evidence` (`db.clj:948-979`) now skips `stable-value` and `read-result-digest` over each whole read result,
    and the render cache no longer retains results.
  - Trade, per owner ruling: when a read's attribute revision moved and its index evidence is not decisive, the call
    re-renders instead of replaying the read.
  - The debug page's "retained read result" and read-request display could no longer show anything, so it is deleted
    (`web.clj` `debug-read-dependencies-html`). The one `web_test` fixture and asserts that covered it are updated.
- **P2.** New private `render/call-reach` memoizes `db/stored-call-reach` in `seon.cache` under kind `:seon.render/call-reach`.
  - The key is the producer plus connection-id, generation, conservative revision and the attribute revisions of
    `:seon.fn/sym` and `:seon.fn/calls`. This is Datahike's validity rule for a cached read (`source-context-unchanged?`,
    `reference-code/datahike/src/datahike/query.cljc:3009-3029`).
  - A value with no connection identity is derived directly.
  - `render-call` reuses its fast-path `call-cache-evidence` when the producer is unchanged, instead of computing it twice.
- **Checks.** `clj-kondo --lint src script test --cache false`: 0 errors. `seon.contracts-compile-test/check` over the three
  source files: `[]`, 83 ms. Net: +42/−27; the added lines are mostly `call-reach`'s docstring.
- **Before** (default pid 89341 on bb07fdf08, page `/agent/root/debug?n=2&root=[:seon.agent/id "root"]`, probe
  `tmp/shrink-web-opus/page-probe.clj`, allocation summed across all JVM threads):

  | load | ms | MB allocated | render-call | stored-call-reach | canonical-value-string | read-evidence |
  |---|---:|---:|---:|---:|---:|---:|
  | cold | 1,090 | 1,509 | 94 (894 ms) | 94 (431 ms) | 25,406 | 94 (23 ms) |
  | warm | 122 | 213 | 94 (83 ms) | 0 | 0 | 0 |

- **After:** pending root's restart.

## What root runs after restart

1. Probe: evaluate the form in `tmp/shrink-web-opus/page-probe.clj` in JVM mode (a fresh `root=` value gives a cold load).
2. Focused tests, one at a time:
   - `seon.render.retained-test`
   - `seon.render-cache-test`
   - `seon.render.web-test` (watch "only the changed blocks re-derive", near line 1117)
   - `seon.render.call-test`
   - `seon.render-source-test`
   - `seon.render.web-context-test`

   A red caused by the S1 trade (a call re-rendered where a replay used to prove it current) gets its expectation
   updated, not the code.
