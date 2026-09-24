---
type: landing
lane: fix-failure-message-bound
created: 2026-09-24
status: four commits installed on default (pid 70372, checkout, hook publication on); one reader outside the lane still to convert; drift attribution needs an owner decision
---

# Lane fix-failure-message-bound (2026-09-24)

Existing mechanism: `prepare-failures!` already staged `expected`/`actual` above
`:seon.config.eval.result/blob-threshold` through `stage-field` → `blob/stage!`, and
`failure-text` already read blob digests through its `field` helper. The message now
goes through the same path. No new bound and no new mechanism were added.

## Broken first

- **`src/seon/issue.clj:841` still reads `:seon.test/failure-message` from a recorded
  result.** That file is outside this lane. After `f46e9939b`, a recorded result carries
  `:seon.test.failure/reports` and no joined text, so the issue status text loses the
  failure body of a red admitted test. The one-hunk conversion to
  `seon.test/failure-message` is ready as `tmp/fix-failure-message-bound/issue.patch`.
  `test/seon/issue_settlement_test.clj:162` and `:224` then read
  `(tests/failure-message red)` in place of the key. That suite is red today for foreign
  reasons: a 31 s duration, and fixture rows missing `:seon.program/definition-digest`
  (run `71873b4e4eba`).
- **Ownership:** the attributes do not live in `seon.test.edn`. They live in
  `resources/seon/schemas/seon.test.failure.edn` (failure row and `:value`) and
  `resources/seon/schemas/seon.test.report.edn` (report row, from which the runner
  derives `report-attributes`). I edited those two files. The ledger names no holder for
  either one. Root should record them against this lane.
- **Worker-global drift under concurrent adoption is not fixed.** See the options below.
  Here is what I saw: `--ns seon.test.runner-test` (`29f93ec4a920`) had one member red
  on `:drift-added` of 36 `seon.schema.datahike/*` Vars, which another lane's adoption
  re-armed mid-run. The rerun of `--ns seon.id-test` (`22bb116087ef`) was green.
- `test/seon/schema/datahike_parity.edn` does not list the two new attributes. It was
  last updated at `62487dbc3` and has not tracked the schema changes since then, so
  `canonical-population-native-parity` was not run and is not claimed.
- Earlier OOM payloads can come back through capture. `failure-identity`
  (runner.clj:144) still prints the whole message once to hash it, which costs
  O(message) transiently at capture. It is no longer stored or re-joined.

## Commits

1. `62d97cfcc`: the message is stored as a blob above the threshold, never inline.
   - Adds `:seon.test.failure/message-blob` and `:seon.test.failure/message-size` to the
     failure row, the `:value` shape and the report row.
   - `prepare-failures!` stages the message through `stage-field`.
   - `failure-text` reads it through `field`.
   - A result whose failures are staged no longer carries its joined
     `:seon.test/failure-message` onto the test row. That was the second unbounded
     inline copy of the same claim.
   - +14 / −3.
2. `f46e9939b`: `recorded-member-results` returns the reports and counts only, and builds
   no failure text. This path serves readiness: `boot/readiness` → `problems/failed-tests`
   → `latest-results`. Text is built only where it is shown:
   - `problems.clj:589/625` via `seon.test/failure-message`
   - `render/test.clj:49/83` via `failure-text`
   - `test.clj:1915` face via `failure-message`
   - `accretion.clj:250`, which prefers reports

   `problems/failed-tests` already selected on counts, so it needed no edit. The
   converted reader is `interrupted_request_test.clj:63`. The regression is
   `seon.test.interrupted-request-test/a-huge-assertion-message-is-a-blob-and-readiness-reads-only-counts`.
   +46 / −5.
3. `497857b30`: `turn_test.clj:174` prints the fault's operation, layer and message, not
   the whole fault. That whole-fault print was the 557,665,187-character message in the
   dump. +2 / −1.
4. `6ac1b356a` (root's follow-up b): an admission refusal's message names its
   `:seon.test/admission-refusal` kind, plus a scalar expected value. Installed:
   `#'seon.test/admission-refusal!` answers "…inconsistent evidence:
   :seon.test/program-mismatch, expected \"digest-a\"." I could not reproduce the refusal
   itself: `--ns seon.cluster.prompt-test` was admitted (`3c0579660a52`, with foreign reds
   in `seon.cluster.prompt`). +5 / −1.

Net: source and schema +33 / −9, tests +34 / −2.

## Evidence (default pid 70372, from the checkout, 11 GB heap)

| probe | before | after |
|---|---|---|
| stored `:seon.test.failure/message`, 557 M-char claim | inline (OOM at boot; `tmp/oom-diagnosis/summary.txt`) | 10 MB claim: report row `message-size` 10,485,760 + `message-blob` digest; no inline message or test-row text above the 4,096 threshold |
| readiness read of that member | `str/join` of `failure-text` per failing member (8 × 1.1 GB copies in the dump) | `latest-results` carries no `:seon.test/failure-message`; its thread allocation is under half the message size (asserted) |
| `latest-results` over 239 symbols, 3 runs | not measurable (parent not loadable in this JVM) | 17.2–17.8 ms / 27.8 MB (answers the typed "Recorded run coverage is unavailable" on this fresh store) |

Tests on default, each one `bin/test-check` request:
- The new regression: `0c844884dba8`, 10 pass, 6.4 s (the member is declared long
  because of admit-run). On the parent it would fail these assertions: no
  `message-blob` attribute exists there, and `recorded-member-results` sets the text.
- `…/a-thrown-request-records-its-unfinished-members-as-failed-obligations`:
  `9ca02597f2b7`, 8 pass.
- `seon.turn-test/generated-read-evidence-rejects-turn-activity`: `e36b2d911867`, 6 pass.
- `--ns seon.test-runner-test`: `d8026ce55b2a`, 48 pass and 1 error. The error is
  `result-recording-is-total-under-concurrent-test-retraction` ("Test completion has no
  surviving test definition"), which is the same red the fix-unbounded-failure-text
  landing reported before this lane.
- `--ns seon.test.runner-test`: `29f93ec4a920`, 6 errors. These are foreign:
  `seon.cluster.reload-measure/-main` is host-bound, plus the drift above.

## Decision needed: worker-global drift during a concurrent adoption (root's item a)

`run-vars!` compares the JVM-wide `instrument/instrumented` set before and after the
body (runner.clj:352/368). The host member path calls `run-var!` with `{}` custody
(test.clj:150) on purpose, so the runner holds no database from which to read
`:seon.test/adoption-identities`. The adoption also arms before its record commits
(cluster.clj:2617 → 2640), so a record-derived exclusion still races. The drift is
real: the member's program changed under it, and that breaks the law "no reload may run
under an evaluation". Three options:

1. **Simplest (recommended): exclusion at the adoption owner.** `development adoption`
   (cluster.clj) waits for running test members and evaluations before it reloads and
   arms. Members already hold the idle boundary through their request, so this would
   use that existing mechanism. The drift check stays as it is and is then true.
   Guarantee: no member runs across a program change. Cost: about 20 lines in
   cluster.clj, owned by the adoption holder. Sacrifice: a publication waits up to one
   member bound (5 s default; 30 s for a long member).
2. **Name, don't blame.** `run-vars!` hands its drift back as data. `member-result`
   (test.clj), which holds the connection, then reclassifies drift that falls in the
   namespaces of adoptions committed in the run's basis window as "program changed
   during the member". That is typed unknown, not green. Cost: about 25 lines across
   runner.clj and test.clj. Sacrifice: it races the arm→record window, and a schema
   change re-arms Vars outside the adopted namespaces.
3. **Re-run the member once when an adoption landed during it.** Cost: about 15 lines.
   Sacrifice: this is a retry loop, which the laws refuse. I list it only for
   completeness.

## Verification limits

The parent was not measured in the same JVM, because default loaded these edits at boot
and redefining its Vars is barred. The before figures come from the OOM diagnosis.
Readiness over a stored inline huge row was not exercised, because the fixed writer can
no longer produce one and hand-built rows are barred. The regression asserts the writer
and the read on a real admitted run. RESET NEEDED: none.
