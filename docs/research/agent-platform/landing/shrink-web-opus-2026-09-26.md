---
type: landing
lane: shrink-web-opus
status: "S1+P2 978bac337 measured on pid 29853; red fixes 8303cd5a3 (RESET NEEDED); two-tabs red unresolved; S3+ on hold per root"
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

- **After** (default pid 29853 on 116d229c0, which contains 978bac337; same probe and page). The graph has grown since:
  164 render calls instead of 94, a 385 KB page instead of 223 KB. `seon.maintenance/collect-released!` (5 s) ran
  concurrently, so the JVM-wide allocation figure is contaminated and is not a like-for-like comparison.

  | load | ms | MB allocated | render-call | stored-call-reach | canonical-value-string | read-evidence |
  |---|---:|---:|---:|---:|---:|---:|
  | cold | 918 | 2,334 (with a concurrent 5 s maintenance) | 164 (700 ms) | **14 (28 ms)** | **0** | 328 (10 ms) |
  | warm | 248 | 506 | 164 (120 ms) | 0 | 0 | 0 |

  - Per render call, cold: 11.6 ms → 5.6 ms.
  - Call reach: 94 calls / 431 ms → 14 calls / 28 ms. The cache hits once per distinct producer.
  - `canonical-value-string`: 25,406 calls → 0.

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

## Red triage after restart, and 8303cd5a3

- **`render.web-test/transaction-refusals-map-to-http-without-success` (HTTP 204 for a refusal).**
  - The cause predates S1. `inbound` decided success by the absence of three guessed keys (`:seon.db/invalid-read`,
    `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id`, since 683fc9c20/1df3cb832). So a
    `:seon.db/transaction-refused-error` without a request id answered 204.
  - Fix: success is the report's `:db-after`, per `db/transact!`'s declared output `[:or :seon.db/transaction-report
    :seon.db/transaction-refused-error]`.
- **Task 80f54dd48aef (a handler throw reaches http-kit's unbounded printer; the 2026-09-26 OOM).**
  - `handler` now catches `Throwable` around the route.
    - `entrance-fault!` records the fault. A recording that itself fails is logged as bounded `render.value/floor` text.
    - The response is a 500 whose body is `render.value/floor` of the throw.
  - The task entry is removed from `resources/seon/tasks/seon.render.web.edn`.
  - Regression: `seon.render.web-test/a-route-throw-answers-a-bounded-500-inside-the-handler`. A POST whose form body
    is a Long makes `decode-form`'s `slurp` throw a real IllegalArgumentException.
- **Task 0be9b4c92c56, first half.** `page-response` reads the database once. A refused read answers 503 with its
  bounded message instead of rendering against the refusal map. The mark comes from `tmp/oom-array-opus/web-task-marks.patch`.
  - The second half (a server outliving its cluster's connection) is still open, so the task and its mark stay.
  - No regression yet: it needs a released connection under a live server.
- **`render.web-context-test/unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot`: the S1 trade,
  shown on the live JVM.**
  - 4 of the 11 retained calls on default's root page read with dependency attributes `:all` and no index patterns:
    `seon.cluster.status/render-html`, `seon.agent/render-settings-html`, `seon.note/render-notes-html` and
    `seon.error/render-faults-html`.
  - Such a read has no revision proof. Without retained results there is no replay, so any commit makes those
    blocks candidates.
  - A candidate re-derives the page, and the fleet table takes the live fleet as its argument.
  - The test now asserts that renderers whose reads are proved by revision keep their calls.
  - **Consequence for root:** these four root-page blocks now re-render on every commit to the store. The producer
    fix is to give those renderers attribute-bounded reads, not `[*]`/entity-wide reads.
- **`render.web-test/two-tabs-each-get-their-own-complete-paint`: UNRESOLVED.**
  - It reproduces deterministically when run alone: `bin/test-check default --test …` takes 2.4 s and gives 4 sends
    against 2 and 2 passes against 1.
  - A join always wakes a derive-all pass (`{::join true}`, since 0cf7e3e09 / b5ffb9570).
  - I have not proven whether S1 changes that pass's output. One candidate: a re-rendered time-dependent block, such
    as the status renderer, changes the package.
  - A JVM-mode probe of the fixture is refused ("needs an executing test handle"). The next step is an
    instrumented test body, or root's call.
- **Not mine, left for their owners:**
  - `render-cache-test` fixture write refused (the root agent is absent on the fixture branch).
  - `retained-test` `other connection`: the compiled `test/seon/render_simplification/fixture_a.clj` Var answers.
    That is invocation resolution, not reuse.
  - The fault-path reds (0cbf4336f).
  - `declared-units` (new `:seon.agent/admission-closed-tx`).
  - `next-turn-context` (its own retract transaction returned nil).
  - The `data-*` floor text.
  - `coalesce-floor`.
  - `start-refuses-a-flat-process-write-before-binding`.
- **Foreign breakage seen while working:** `test/seon/db_test.clj` was left unreadable (unbalanced paren at 872) by
  another lane's edit. It is not mine, and I did not touch it.

## RESET NEEDED: root runs after restart, one at a time

1. `bin/test-check default --ns seon.render.web-test`
2. `bin/test-check default --ns seon.render.web-context-test`

In web-test, expect green on `transaction-refusals-map-to-http-without-success` and the new
`a-route-throw-answers-a-bounded-500-inside-the-handler`. `two-tabs…` is expected red until it is resolved.

## Runs on default pid 32187 (fa4e4bccf, includes 8303cd5a3)

| namespace | before (pid 12212, triage) | after (pid 32187) |
|---|---|---|
| `seon.render.web-test` | 420 pass / 25 fail / 2 error, 120 s | 451 pass / 18 fail / 3 error, 120 s (check bound; `unrelated-transaction-reuses…` unfinished at the bound) |
| `seon.render.web-context-test` | reds incl. `unrelated-adoption` render counts | 52 pass / 2 fail / 2 error, 26 s |

- **Now green:** `transaction-refusals-map-to-http-without-success`, the new
  `a-route-throw-answers-a-bounded-500-inside-the-handler`, and `runtime-evaluation-drops-a-closed-debug-pages-package`.
  The test run also passes `unrelated-adoption`'s renderer-count assertion.
- **`unrelated-adoption` is still red on its last assertion**, "the page shows the newly adopted commit". The status
  renderer ran twice, but the body lacks the new commit. On the live JVM the status block does print the adopted
  commit. The cause is not found and is not proven to be S1.
- **two-tabs: the block is the fleet table, not S1 (live JVM evidence).** On default at an unchanged basis
  (536872313 before and after), one tab join made one pass that invoked exactly one producer,
  `seon.oversight/html-table`, and re-serialized one surface (`surface-html` ×1). `oversight/fleet-value` includes
  `:seon.render.web/render :seon.oversight/passes N`, the render proc's own pass count (`oversight.clj:83`, shown at
  `:349`). Every pass therefore changes the fleet table, and so the package: a join's derive-all pass always repaints.
  - The fleet argument is computed outside `render-call` (its read evidence is `[]`), so read evidence, and S1, play
    no part.
  - The fix belongs in the oversight owner: a page must not display the rendering proc's own pass counter, or the
    fleet must omit volatile counters.
  - The code is old (9c0ecae86, 2026-09-22), so what changed the test's outcome today is probably the fixture (the
    test handle now carries routing), not render. This is unverified.

## 5441487fc: the fleet view omits the render proc; 7ba894703: attribute-bounded reads

**Files taken** (no ledger row held them): `src/seon/oversight.clj`, `src/seon/note.clj`, `src/seon/cluster/status.clj`.
Root records them in the ledger. I touched `src/seon/config.clj` and restored my own edit to HEAD (it had no other
diff); its change is a patch.

- **5441487fc (`seon.oversight/unit`).** The page's fleet value drops the `:seon.render.web/render` plumbing entry:
  a view never shows the proc that renders it. Its pass count moved on every pass, and its ping answers only when it
  is not mid-pass, so every join repainted. `flow-status` (MCP `runtime_status`) keeps the entry. Regression:
  `render.web-test/two-tabs-each-get-their-own-complete-paint`.
- **7ba894703.** Probe on default (pid 32187), read-evidence attributes per read shape:

  | read | attributes |
  |---|---|
  | `[*]` pull, notes | `[:all]` |
  | named `note-selector` pull | the named attributes |
  | status `(pull ?c [...])` inside `:find` | `[:all]` |
  | status id `q`, then named pull | `#{:seon.cluster/name}`, `#{:seon.cluster/name :seon.source/commit-id}` |
  | `config/effective` (`[*]` pull of the config row) | `[:all]` |
  | named-dial pull | 103 attributes; same `select-keys` result (checked) |
  | `error/for-agent` (find-pull) | `[:all]` |
  | id `q`, then `pull-many` with the selector | 534 attributes; same fault set (checked) |

  - Notes and status land.
  - New regression `render.web-context-test/root-page-blocks-read-attribute-bounded`: every retained root-page
    call has named read attributes, with faults and settings excepted until their patches land.
- **Patches for root:**
  - `tmp/shrink-web-opus/error-faults-bounded.patch` (error.clj, held by shrink-schema). It trades a 1.4 ms
    find-pull for a 6.3 ms pull-many (2026-09-24 numbers), paid only when a read attribute changed.
  - `tmp/shrink-web-opus/config-bounded-dials.patch` (config.clj). **Not landed:** the named-dial pull measured
    73 → 310 µs per read (200 runs), and `config/effective` runs 3× per agent form. A hot-path regression over 20%
    needs a cheaper form first; I could not split pull cost from filter cost before default went down.
- **`unrelated-adoption`'s last assertion (the page lacks the new commit though status ran twice): hypothesis, not
  verified.**
  - The status read was `:all`. `render-call` invokes the producer and then returns the *previous* output when
    `reusable?` holds, and `reusable?` rests on `read-evidence-current?`.
  - If `:all` evidence compares equal on revision (conservative revision unchanged), a stale output is served though
    the producer ran.
  - With the bounded status read, the revision includes `:seon.source/commit-id`, so the assertion should pass.
  - If it still fails, `db/read-evidence-current?` on `:all` plans is the owner to examine (db.clj).
- **RESET NEEDED** (HEAD does not boot yet: defn-scan is fixing `schema_test.clj`). After restart, root runs:
  1. `bin/test-check default --ns seon.render.web-test` (two-tabs expected green)
  2. `bin/test-check default --ns seon.render.web-context-test`
  3. `bin/test-check default --ns seon.oversight-test`
