# Batch D review — review-astra-batch-d

**Changes requested.** The source loads and its declared contracts compile, but the new total entries throw on a hostile Throwable; the fixed core budget can be disabled by value metadata; error display walks whole ex-data before selection; and the new fallback diagnostic prints an unbounded exception message. There are also stale task references, stale host-bound test subjects, and one lost identity observation in a converted database error. No production or test edits are included in this review; fixes below are concrete handbacks, not claimed landings.

Reviewed candidate: `910b70717ed71d4b156945ca7802b9df7720c77b` (all six assigned commits, including `fa31a1a97`). Installed default: `108dd1c3e20dfbf3488c69ed80e2dafefabec86d`. Line citations below are candidate source unless labeled otherwise. This is the Astra-side review, not a claim that both independent reviews are complete.

## Findings, severity order

### P1 — the four “total” entries can throw from their final fallback

`src/seon/render/value.clj:213` catches structural printing failures, but its catch invokes `throwable-text` outside another containment boundary (`:224`). That function calls `causes` (`:179`), whose `.getCause` (`:135`) is unguarded. `core-html` (`:230`) delegates directly; the fallback portions of `text` (`:249`) and `html` (`:260`) are also outside their try. This reaches the emergency sinks at `src/seon/fault.clj:354` and `src/seon/render/web.clj:3925`.

Reproduced in the candidate scratch JVM, with no redefinitions:

```clojure
(let [t (proxy [RuntimeException] []
          (getCause [] (throw (IllegalStateException. "hostile cause"))))]
  (seon.render.value/core-text t))
```

`core-text`, `core-html`, `text {:seon.render/value t}`, and `html` all threw `IllegalStateException: hostile cause`. This is not an out-of-memory hypothetical: an ordinary accessor exception escapes.

**Fix:** contain the entire core operation including cause discovery and fallback; an accessor failure must yield opaque class/identity data through the same printer, with one diagnostic. Do not place a fresh guard in each caller. Tighten the core's totality proof to include throwing `getCause`, `getMessage`, and `getStackTrace` accessors through the public entries. Existing `deep-cyclic-and-accessor-hostile-chains-stay-bounded` only tests a throwing message accessor through a private helper.

### P1 — a value can disable the core's supposedly fixed budget

The core supplies a fixed 1,000-token profile (`value.clj:190-198`), but `prepare` honors the input value's `:seon.print/full` metadata and replaces budget, depth and member bounds with `Long/MAX_VALUE` (`:1465-1473`). `core-text` passes that value unchanged (`:215`).

Reproduction: `(core-text (with-meta (vec (range 2000)) {:seon.print/full true}))` produced approximately **2,254 tokens**, versus the declared 1,000, in 81.66 ms. Larger finite values grow with the value; an infinite value with this opt-in has no finite selection bound (source inference, not run).

**Fix:** make fixed-core selection disregard the full-print opt-in, as an explicit printer-entry policy. Preserve the original value. Add the metadata-bearing input to `the-core-printer-needs-no-render-database-or-config` and assert bounded selection/work, not merely return type.

### P1 — display work is proportional to all ex-data, before the print budget

`core-text` calls `refusal/chain value nil` before selection (`value.clj:213-219`). `chain` calls `link`, then `referenced` on each link's ex-data (`src/seon/error/refusal.clj:375-381`). `referenced` opens every map/vector/set and visits every child (`:278-350`). No world changes means the original collection is returned identical, but it does not avoid the traversal. Repeated aliases are revisited; there is no identity memo across shared subtrees or links.

A counted identity projector supplied to the existing private `referenced` seam produced:

| Payload vector members | Projector calls | Result identical? | Time |
|---:|---:|---|---:|
| 1,000 | 1,002 | yes | 1.54 ms |
| 10,000 | 10,002 | yes | 8.95 ms |
| 100,000 | 100,002 | yes | 58.22 ms |

This is a deterministic work count, not a timing-based guess. The installed default probe separately confirmed that 10,000 ex-data items survive by identity (2.54 ms). The private-seam measurements are unarmed candidate observations, not installed performance claims.

**Fix:** separate durable world-reference conversion from display selection. Recording whole durable evidence may traverse it, as `docs/seon/specs/errors.md` §1 explicitly permits; emergency display must not call that full conversion before selecting what it shows. The printer should inspect only the shown Throwable links/members and retain the original data for retrieval. Do not restore the deleted data clipper. Also account for `chain`'s complete cause/suppressed traversal (`:421-442`) and all stack extraction in a display path; bounded output alone is not bounded work.

### P1 — fallback diagnostics bypass the only print budget

The catches in `text` and `html` concatenate the exception's complete message (`value.clj:246`, `:257`); `unavailable-line` concatenates it again without passing it through structural selection (`:232-237`). These entries declare arbitrary maps, and `prepare` converts the cursor path before its own try (`:1430-1434`), so this is reachable without a substituted Var:

```clojure
(text {:seon.render/value 42
       :seon.render.data/cursor
       {:seon.render.data/path
        (lazy-seq (throw (ex-info (.repeat "x" 100000) {})))}})
```

Text output was approximately **25,023 tokens** (51.50 ms); HTML's diagnostic text was approximately **25,023 tokens** (0.51 ms). The value itself is just 42. An earlier probe using only a custom `Seqable` raised a short conversion error instead; the lazy-sequence probe above is the successful reproduction.

**Fix:** retain failure evidence as data and send it through the printer's bounded selection. The human diagnostic sentence should be fixed prose and the error data should remain inspectable. Do not add a `subs` clipper. Add a long-message row to the public total-entry regression, asserting exactly one diagnostic as data and a bound on the shown result.

### P2 — the long tests load, but their host-bound subject is no longer host-bound

`test/seon/sci/branch_execution_test.clj:239`, `:323`, `:404`, `:428`, and `test/seon/cluster/agent_test.clj:1344` name `seon.render.value/bounded-text`. The definition exists, and both namespaces **compile/load successfully**; no unresolved Var repair is needed. However the tests explicitly assert that this declaration contains its own proxy (`branch_execution_test.clj:241-243`, `:446-447`; `agent_test.clj:1342-1344`). Its body is ordinary `try`/`binding`/`pr-str` (`value.clj:108-126`), with no proxy. The indexer marks a declaration from its own operations, never inherited callers (`src/seon/fn.clj:1063-1110`). The old `seon.print.value/bounded-text` also lacked the proxy immediately before the move: this is a stale premise perpetuated by the rename, not a newly proven test red.

**Fix:** use a stable, deliberately host-bound fixture declaration and its real helper for these branch tests, with the canonical declaration/fixture writer. Keep the refusal-by-name and graph-arming behavior. Merely renaming back, making the printer host-bound, or deleting these tests is wrong. Their behavior was not executed in this review, per the no-lane-test-run instruction; root must run the exact members below including long tests.

### P2 — one message conversion drops the held test identities

Most conversions preserve data (table below), but `src/seon/db.clj:5507-5523` removes the previous message's `mapv` of `[:seon.db/value-identities value]` and retains only numeric `:seon.db/removed-values`. The prior snapshot already computed those identity maps (`:5459-5461`); `retention-check` receives them (`:5471-5494`) and can report them without a query. The returned error carries the owner's identity and removed entity ids, not the removed tests' observed identities. Later re-query is not equivalent to retaining the prior observation, especially after subsequent changes.

**Fix:** carry the removed/erased identities as a declared error data member derived from the held `prior` map, then keep the fixed prose. Assert that identity data in the writer refusal. Source-proven loss of that field; a live transaction reproduction was not performed.

### P2 — namespace retirement leaves operational references behind

No executable call or require of `seon.print.value` remains in `src`, `test`, `script`, `bin`, `.clj-kondo`, or `.agents`. No runtime caller of the deleted refusal functions, `:seon.error/bounded-chain`, `value/floor`, or `flow/call-with-root-bindings` was found. The moved private `datum` and `bounded-text` exist; similarly named `seon.error/bounded-text` is a different surviving definition.

But `resources/seon/tasks/seon.print.value.edn:4`, `:8`, `:13` and `resources/seon/tasks/seon.render.value.edn:59` still contain **live `:must-read` symbols** for the deleted namespace. `seon.render.value.edn:58` still instructs a future lane to preserve that namespace and delete the new private helper. `docs/seon/schedule/README.md:99` and `docs/seon/schedule/triage.tsv:31` still route a task to the deleted namespace. The schedule is root-owned and was not edited. `docs/seon/specs/render-dedup.md:189` still lists `print/value.clj` as the surviving floor, conflicting with its revised D6.

**Fix:** have the task/schedule owner reconcile identities and must-read targets with the surviving printer/producer in one publication. Keep dated research/history citations explicitly historical; do not mechanically erase them. A full tracked docs/skills search found 132 matching lines, mostly dated evidence, not 132 active callers. No removed-symbol hit was found in current skill files.

## What the conversions do preserve

| Conversion | Retained data / source |
|---|---|
| Value-type refusal | Attribute and original value in `:seon.error/offending`, `src/seon/db.clj:2034-2046` |
| Query input mismatch | Query in `:seon.error/data`, expected `:in`, arguments in offending, `db.clj:2691-2703` |
| Invalid pull arguments | Complete argument vector in offending, `db.clj:2964-2978` |
| Unique conflict | Conflict value and owner in the conflict map, `db.clj:3510-3524`; fixed prose at `:3526` |
| Write identity / component validation | Identity associated into data at `db.clj:3962-3966`; component data merged at `:4126-4134` |
| Deletion referrers | Native `breaks` retained, `db.clj:4805-4824` |
| Changed program identities | Full `changed` vector in offending, `src/seon/turn/batch.clj:575-582` |
| Missing configured functions | `missing` retained in offending, `src/seon/config.clj:670-680` |
| Contract reuse / polymorphism findings | Similar keys retained; definition newly retained, `src/seon/schema/admission.clj:311-324`, `:410-425` |
| Reach-digest refusal | Full declaration list in data and tests in offending, `src/seon/test/runner.clj:1125-1141` |
| Test-world refusal | Offending value kept whole, `src/seon/test.clj:1725-1729` |

This is source evidence, not proof of every downstream renderer. The edit-hook issue noted by the author remains material: `bin/seon-hook:596-599` selects only a subset of finding keys before presentation, so moving evidence out of messages can hide it at that consumer. Its conversion must include the consumer, not merely preserve data internally.

`render/walk` now returns data through its existing walk owner (`src/seon/render.clj:1863-1950`); no alternate evaluator was added by this batch. The refusal chain no longer clips long user data, and the frame uses `root-frame` (`src/seon/error/refusal.clj:658-667`). Those are positive changes; neither establishes the display-work bound.

## Verification and limits

- `bin/seon status`: source archive `108dd1c3e`, PID 47705, start `2026-10-07T04:29:27.311Z`; shell status said unknown, MCP `runtime_status` said **faulted**. MCP reported 14 open faults, 171 errored receipts, 43 unknown tests, 14 over-bound warnings. Live heap used about 1.99 GB, heap live 1.30 GB, maximum 11.0 GB. Existing linked cost tasks include `d46e436e65f4` and `2f25d8d33eaf`; no health pass is claimed.
- MCP JVM probe used explicit `agent root`, `cluster default`, and `(seon.cluster.boot/connection "default")`. It verified `core-text` is **not installed** and whole ex-data identity is retained. Result handle: `result/ea2540d34b22d`. The returned envelope supplied no extra timing/call fields; 2.54 ms is the timing measured inside the form.
- A `git archive` of candidate HEAD was created under owned `tmp/review-astra-batch-d/candidate`; no worktree. Ignored dependency lint configuration was linked as `bin/lane` does. `clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1,488 warnings, 7.221 s**. An initial working-tree lint was accidental and is not the candidate evidence.
- Scratch JVM: candidate first-party source/resources/test/script and the existing dependency classpath, `-Xmx2g`. Both questioned test namespaces loaded; `seon.render.floor-test`, `seon.test.runner`, `seon.turn.batch`, `seon.render.walk`, `seon.render.web`, `seon.cluster.boot`, `seon.fault`, `seon.test.accretion`, and `my.background` loaded. Branch test namespace acquisition took 7.79 s (transitive namespace compilation, not a warmed ordinary call); agent-test 209 ms; floor-test 17 ms. This does not prove store boot/publication/adoption.
- `seon.contracts-compile-test/check` on 38 surviving Clojure files touched by the six commits, with candidate packaged forms: **0 findings, 2.22 s**. It reaches `malli.core/function-schema` at `reference-code/malli/src/malli/core.cljc:3130` through `test/seon/contracts_compile_test.clj:718`. Malli pin and checkout both `43c6dca8d2d8df3162b13725939954807be4a4ed`. No baselines were supplied: this proves contract compilation, not completeness of every pre-existing uncontracted helper. Its cost covers the packaged projection and contract corpus, not one contract.
- Unarmed core printing without db/custody produced about 29 tokens in 61.73 ms and left its config-defaults delay unrealized. This verifies the ordinary **unarmed** no-db/config branch. Armed source still calls a `:seon.render/unit` entry with no explicit database, so supply/guard/fallback behavior needs installed proof after adoption; do not call the unarmed smoke test that proof.
- Scratch heap used 485 MB with 2.15 GB maximum at the final observation. Warm ordinary probes were below one second. Namespace and contract acquisition costs above are explicit; no end-to-end boot timing or allocated-memory profile is claimed.
- Foreign boundary: uncommitted AGENTS/skill/spec edits, `test/my/examples_test.clj` owned by examples-world, task-id work, and a dirty `reference-code/http-kit` checkout were present. Candidate first-party files exclude all foreign edits. Dependency checkout cleanliness/pinned rebuild and the HTTP dependency's load effects were not certified. None stopped the independent source, lint, contracts, or finite probes. No other lane/session was contacted or edited.
- No `seon.test/run`, test runner, provider run, reset, reload or adoption was started. All listed greens/identical-on-HEAD reds in the author's notes remain **unverified here**. No browser paint proof. Both scratch processes exited; no disposable live root was created.

## Exact central test requests

Run after the relevant fixes and candidate adoption, with long members included. Existing names:

- `seon.render.floor-test/the-core-printer-needs-no-render-database-or-config`
- `seon.render.floor-test/the-total-entries-always-answer`
- `seon.render.floor-test/deep-cyclic-and-accessor-hostile-chains-stay-bounded`
- `seon.render.value-test/a-renderer-that-throws-still-prints-through-the-floor-and-says-large-value`
- `seon.error.refusal-test/a-large-argument-stays-whole-in-the-chain`
- `seon.error.refusal-test/the-chain-still-names-places`
- `seon.error.refusal-test/a-set-holding-a-deep-value-is-referenced-without-the-stack`
- `seon.error.refusal-test/the-frame-comes-from-the-root-cause`
- `seon.test.runner-test/a-huge-message-and-cause-print-bounded-text-and-keep-the-whole-message`
- `seon.flow-test/a-generated-connections-writer-keeps-none-of-the-callers-bindings`
- `seon.flow-test/an-unstored-panic-prints-bounded-text-and-keeps-the-whole-cause`
- `seon.sci.branch-execution-test/an-override-of-a-declaration-the-indexer-marks-host-bound-refuses-by-name`
- `seon.sci.branch-execution-test/an-adoption-record-moves-the-loaded-program-every-acquisition-compares-against`
- `seon.sci.branch-execution-test/a-branch-forked-before-a-platform-change-binds-the-loaded-compiled-var`
- `seon.sci.branch-execution-test/an-unoverridden-host-bound-declaration-refuses-by-name-only-when-called`
- `seon.cluster.agent-test/a-changed-core-entity-refuses-by-name-and-every-agent-still-arms`
- `seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`
- `seon.turn-test/a-schema-key-changed-since-the-run-read-it-is-refused-at-the-writer`

The last two have known fixture reds in the author's notes; route those to their fixture owner rather than excluding the behavior forever. Add the demonstrated hostile cause, full metadata, and long diagnostic inputs to the existing core/total-entry tables. Add deterministic bounded-work evidence for the public core, and a writer-level data assertion for the retained test identities. These additions are requested, not present tests.

The renderer-failure regression currently counts textual `:seon.error/layer` occurrences in `(str diagnostics)` (`test/seon/render/value_test.clj:1294-1297`); change that assertion to count the actual diagnostic maps by their layer. Printer layout assertions may stay in printer tests. The new total-entry test currently checks only string/vector return and one visible key (`floor_test.clj:108-116`), so it does not prove the advertised exactly-one-diagnostic or bounded-work guarantees.

## Reproduction artifacts / landing

- [Finite probe script](review-batch-d-review-astra-batch-d-probe.clj): run with `clojure.main` from an archived candidate using its src/resources/test/script classpath and dependency jars. It loads namespaces and calls the contracts checker; it never runs deftests. Its first-party paths are fixed to the reviewed slice.
- [Recorded output](review-batch-d-review-astra-batch-d-evidence.txt): corrected timings, lint summary, installed observation and scratch observations. The two processes' outputs are kept separately within the file.
- Review-only landing: three documentation/evidence files; production/test line delta **0/0**. Candidate lint and contracts were run before this landing; the added docs are outside their roots. No claim of a code fix, test green, installed change, or complete dual-review acceptance.
