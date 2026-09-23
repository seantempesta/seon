---
type: landing
status: landed in part; test.clj call site handed off; instrument follow-up needs a decision
lane: unfinished-runs (README §2 acceptance steps 1 and 5)
---

# Lane unfinished-runs (2026-09-23)

Stability for README §2 acceptance steps 1 and 5. Owner notes:
`docs/seon/issues/an-unfinished-test-run-hides-every-problem-family.md` and
`docs/seon/issues/the-edit-hook-load-files-the-contract-checker-into-default.md`.

## Commits

- `b4c0545d2`: the runner's interrupted-member writer, `problems` confining
  unknown test evidence to its own family, and the `replaced-roots`
  comparison. It adds two regression files and issue notes. Src +56/−4
  (net +52), test +104.

Changed paths: `src/seon/test/runner.clj`, `src/seon/problems.clj`,
`src/seon/instrument.clj`, `test/seon/test/interrupted_request_test.clj`,
`test/seon/instrument_replaced_roots_test.clj`, and the three issue notes named
in the commit.

## What changed

- `seon.test.runner/record-interrupted!` takes a connection, a completion and
  the throwable.
  - Each admitted member of that run with no `:seon.test.member/completed-tx`
    gets a red, terminated record, written through `commit-results!`.
  - The record is `began?/ended? false` with error-count 1. Its failure
    message is "Interrupted: request <id> threw …" followed by every cause link
    (class, message, bounded frames, `ex-data`).
  - Members already recorded are untouched. A second call writes nothing.
  - An interrupted member is an obligation (B4 §2c): it reads red in
    `latest-results`, so it is selected again. It is never green.
- `seon.problems/problems` no longer answers the runner's refusal for the whole
  derivation.
  - The refusal goes under `:seon.problems/failed-tests-unknown` as
    `[execution-error]`, declared in the function's output contract.
  - Every other family derives from the same value.
  - The ai, log and html renderers each name the unknown.
- `seon.instrument/replaced-roots` treats a `:file` as the same source when
  either spelling ends with the other: the classpath-relative `seon/x.clj`
  or the absolute path that `load-file` records.

## HAND-OFF: `src/seon/test.clj` (held by nsa-unblock) — the call site

Until this lands, a thrown request still leaves its batch's members open. Only
the failed-tests family reads unknown. In `seon.test/run`, `run-batch`, wrap the
member loop (currently `(loop [remaining members outcome outcome] …)`, near
test.clj:1755):

```clojure
(try
  (loop [remaining members outcome outcome] ...)   ; unchanged
  (catch Throwable failure
    (let [recorded (runner/record-interrupted!
                    recording (recording-completion batch-provenance [] true) failure)]
      (when (:seon.error/at recorded) (.addSuppressed failure (ex-info (:seon.error/message recorded) recorded))))
    (throw failure)))
```

Its regression runs against the same writer. Retarget
`seon.test.one-request-test/setup-that-throws-still-releases-the-member-branch`:
after the throw, the member's latest result is red with the probe message.

## Evidence (default pid 90963, from the checkout)

| operation | ms | note |
|---|---|---|
| open runs query (before) | 17.6 | `47a759c6f410` 8, `80d1340b16fc` 13, `26a17c0e3f6c` 34, `1da1a9886248` 13 open |
| `problems` before (parent code) | 2,323 | answered refusal `:seon.test/population-unknown` |
| `problems` after adoption | 1,777 | `{error-signatures 8, failed-runs 6, failed-tests-unknown 1, stale-vars 1}` |
| `replaced-roots` over 4,363 rows, parent (5 runs) | 9.0–10.7 (median 9.4) | 15 reported (the hook's `load-file`) |
| `replaced-roots` after (5 runs) | 10.1–12.5 (median 11.2) | 0 reported; +1.8 ms (+18 %), under the 20 % / 50 ms line |
| `bin/seon init --dev default --changed` (5 paths), attempt 1 | 31,139 | refused: `:stale-branch-head` (another lane published) |
| same, attempt 2 | 52,147 | refused: "Source changed during development adoption" (dependents being edited by other lanes) |
| same, attempt 3 | 2,468 | adopted `6ab357cb…` |
| adoption of the edited test file | 15,418 / 9,037 | `seon.db/transact!` dominated under concurrent lanes |
| `bin/test-check … --ns` (first, pre-fix test) | 48,567 | run `cb825bdd2388`, red (assertion text, bound) |
| `bin/test-check … --include-long --ns seon.test.interrupted-request-test --ns seon.instrument-replaced-roots-test` | 56,236 | **run `b941c0837f8c`: executed 2, reused 1, pass 15, fail 0, error 0, passed? true** |
| in-test `admit-run` (2 members) | 13,674 / 15,316 | see issue below |
| in-test `commit-results!` / `record-interrupted!` | 360–542 / 383 | |
| packaged-projection contract compile of the three src files | 3,555 | `seon.contracts-compile-test/check`: findings `[]` |
| `runtime_status` after | — | `problem-counts` present, `replaced-roots []` |

Justifications for rows over 1 s:

- **problems (1.8 s).** `latest-results` pulls every test member on the branch
  (~6,000 pulls), so it is proportional to all recorded members. It is an
  existing cost, not changed by this lane.
- **Adoption (9–52 s).** Publication transactions compete with other lanes'
  concurrent adoptions and edits; the refusals name that. The 2.5 s
  success is proportional to 5 files' analysis.
- **test-check and the tests (>10 s, a defect).** It is filed:
  `docs/seon/issues/admit-run-derives-the-program-digest-from-every-change-since-the-seal.md`.
  - `admit-run` derives the program digest inside its transaction:
    9.6 s on default, over 2,609 program rows changed since the seal.
  - Both regressions therefore declare `:seon.test/long` with that
    measurement (`:seon.test/long-ms 30000`).
- **Contract compile (3.6 s).** It builds the packaged projection from zero
  (the whole schema population).

## Verification limits

- The `seon.test/run` call site is not wired. `record-interrupted!` is proven
  by direct call only (hand-off above).
- Members left open by a JVM exit are not closed: recovery closes turns only.
  The four currently open runs on default stay open until their members run
  again. They now affect only the failed-tests family.
- The instrument follow-up was ruled option 1 and landed at the wire (below).
  `src/seon/instrument.clj` was released without further change.
- Other lanes' file seen broken during this lane: the hook reported
  `test/seon/namespace_agent_loop_test.clj:104:230` unmatched bracket. This
  lane did not touch that file.

RESET NEEDED: no.

## Follow-up: refusals cross the prepl as shown text (`a85bf004b`)

`seon.cluster.boot/diagnostic` (4-arity) renders `:seon.error/offending` once
through `seon.render.value/render-ai` under
`(seon.render/agent-render-profile seon.config/defaults)`. Each chain link's
`ex-data` becomes `:seon.error/shown`; raw `:seon.error/data` is dropped. The
armed wrapper still keeps the live object. Src +17/−6.

- Probe: a map holding default's connection plus 100k items rendered in
  5.4 ms to 423 chars. The elision shows `:seon.print/omitted 99968`,
  `:seon.print/requery-refusal "the value has no result handle"` (a wire
  reply has no result handle).
- Regression:
  `seon.cluster.boot-reply-test/a-refused-request-carries-its-large-evidence-as-bounded-shown-text`,
  run `04201739eb94`: pass 6, fail 0, error 0, passed? true, 58,340 ms.
  - The request itself is over 10 s because of admission cost, already filed
    as the admit-run issue above.
  - The test body is inside its 5 s bound.
- `bin/seon status`, before: 2.01 / 3.22 / 2.11 s. After: 4.28 / 7.41 / 5.55 s,
  and 5.47 / 5.46 / 5.31 / 4.70 s at load average 26.
  - The change is not on the success path: `diagnostic` runs only for a
    refusal.
  - The difference tracks machine load, not this code. It is proportional to
    `mcp-runtime-observation` (problems 1.8 s included).
- Other raw printers (follow-ups, not edited):
  - `src/seon/cluster/boot.clj:536`: `readable-response` pr-strs any response
    whole, including a non-diagnostic refusal such as the `published` error
    returned at `boot.clj:653`.
  - `resources/seon/operator/prepl.clj:16`: the io-prepl `prn` of every event.
  - `bin/test-check:80`, `:97`, `:125`: `pr-str result`.
  - `bin/test-check:133`: `pr-str (ex-data cause)`.
  - `script/seon/dev/mcp.clj:570`, `:629`, `:850`: exception `ex-data` in MCP
    eval failures.
