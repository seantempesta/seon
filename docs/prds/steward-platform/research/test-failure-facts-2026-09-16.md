---
type: research
status: active
tags: [research, test, database, schema, render]
---

# R2 — structured test failure facts and "what made it red"

Research only: no source or test edit, no test JVM. Every number below was
measured once through `mcp__seon__eval_clj` `mode: jvm`, root
`/Users/sean/src/seon`, cluster `default`, at basis **536872041–536872045**,
branch `cluster-default`, under concurrent development load. The forms are
[the probe](test-failure-facts-probe-2026-09-16.clj); it writes nothing.

Read end to end: [AGENTS.md](../../../../AGENTS.md);
[namespace data model §7.3 row 17](../plan/namespace-data-model-2026-09-16.md);
`src/seon/test/runner.clj`; `resources/seon/schemas/seon.test.edn`;
`src/seon/render/test.clj`; `src/seon/test.clj`;
`clojure/test.clj` from `org.clojure/clojure 1.12.1` on the classpath;
the prior [reach-digest probe](reach-digest-2026-09-16.md).

## 1. What the reporter actually hands the runner, per assertion

`clojure.test/do-report` (`clojure/test.clj:351-367`) merges a source position
into every `:fail` and `:error` map before `report` sees it, so one assertion
event carries exactly:

| Key | Source | Present when |
|---|---|---|
| `:type` | the assertion macro | always (`:pass`/`:fail`/`:error`) |
| `:expected` | `is`'s unevaluated form | `:fail` and `:error` |
| `:actual` | the value, or the `Throwable` for `:error` | `:fail` and `:error` |
| `:message` | `is`'s second argument | when the author wrote one |
| `:file` `:line` | `stacktrace-file-and-line`: first frame that is not `java.lang.*`, `clojure.test$*`, `clojure.core$ex_info` (`:fail`), or the throwable's own first frame (`:error`) | whenever that frame is a compiled first-party test frame |
| `:var` | `*testing-vars*` | inside `test-vars` |
| — | `clojure.test/*testing-contexts*`, read out of band | inside `testing` |

The runner sees all of them in one place: `capture-event!`
(`src/seon/test/runner.clj:166`), called from `capture-and-report-event!`
(`:246`) which the `test/report` binding in `run-var!` (`:510`) and the suite
path install. It keeps `:type` (as a count) and then **discards every field**:
`failure-message` (`:121`) flattens contexts + message + rendered expected +
rendered actual into one string, and `failure-identity` (`:133`) SHA-256s
`[test-symbol type message printable-expected printable-actual signature]`
into a 64-char string. `:file` and `:line` are never read. `captured-results`
(`:494`) joins the messages with `\n\n` and sorts the identity strings;
`record-tx` (`:1574`) writes them as `:seon.test/failure-message` and the
`:seon.test/failing-assertions` set, retracting both attributes first so the
row's latest result is total.

Measured holders on `default`:

| Measure | Value |
|---|---:|
| Test rows with `:seon.test/failure-message` | 91 |
| Test rows with `:seon.test/failing-assertions` | 87 |
| Failing-assertion identities in total | 260 |
| Identities per red row | 1 (36 rows), 2 (12), 3 (10), 4 (12), 5 (6), 6–13 (11); max 13 |
| Message bytes: min / median / max / total | 64 / 2,750 / 14,023 / 247,852 |
| Messages over the declared 4,096 B blob dial | 14 of 91 |
| Messages over 16 KiB | 0 |
| Mean bytes per failing assertion | ≈ 953 |
| Test rows carrying `:seon.fn/file` and `:seon.fn/form-span` | 1,686 of 1,779 |
| Red rows carrying `:seon.fn/file` | 83 of 87 |

The site the runner threw away is already an entity: `:seon.fn/file` is a ref
to `:seon.fn.file/path` (identity), beside `:seon.fn/form-span`, on the test
row itself (`resources/seon/schemas/seon.fn.edn:2-3,36-37`).

## 2. The schema: one `seon.test.failure` component per failing `is`

New file `resources/seon/schemas/seon.test.failure.edn` (attributes only —
absent means no key, never a stored nil):

```clojure
#:seon.test.failure
{:id       [:string {:min 12 :max 12 :seon.db/identity true}]
 :test     :seon.db/ref                       ; the :seon.test entity
 :type     [:enum :fail :error]               ; the reporter's own two states
 :ordinal  [:int {:min 0}]                    ; nth failure at the same site in one run
 :message  :string                            ; the author's :message
 :contexts [:vector :string]                  ; *testing-contexts*, outermost first
 :expected      :string                       ; exact printed form, under the dial
 :expected-blob :seon.blob/digest             ; above the dial
 :expected-size [:int {:min 0}]
 :actual        :string
 :actual-blob   :seon.blob/digest
 :actual-size   [:int {:min 0}]
 :file      :seon.db/ref                      ; -> :seon.fn.file entity
 :line      [:int {:min 1}]
 :signature :seon.error/signature             ; :error only, already derived at :107
 :throwable :symbol                           ; the exception class, :error only
 :first-run :seon.db/ref                      ; -> :seon.test.run
 :last-run  :seon.db/ref
 :seen-count [:int {:min 1}]
 :last-seen-at :inst}
```

and on `:seon.test/test`, one cardinality-many component:
`[:seon.test/failures {:optional true} [:vector {:seon.db/cardinality :many
:db/isComponent true} :seon.db/ref]]`.

**Identity, per the owner rule.** `:seon.test.failure/id` is
`(seon.id/id [test-sym site ordinal])` — the one derivation
(`src/seon/id.clj:1`), no second generator and no second truncation. `site` is
`[file-path line]` when the reporter gave a first-party frame, and otherwise
the existing normalized claim `[type message printable-expected
printable-actual signature]` that `failure-identity` (`:133`) already hashes.
The fallback is not a hedge: SCI-interpreted tests report from generic sci
frames, so a file/line there would claim a precision the reporter does not
have, and whether a test is interpreted is stable across runs. Detection is a
fact comparison, never a name pattern: accept `:file`/`:line` only when the
reported basename equals the basename of the test row's own
`:seon.fn/file` → `:seon.fn.file/path` (present for 1,686 of 1,779 tests).

**Repeated runs upsert the same entity.** A rerun that reproduces the same
failing assertion replaces `:seen-count`, `:last-run`, `:last-seen-at`,
`expected`/`actual` and their sizes on the same `:id`; only a genuinely new
site or claim mints a new entity. A green run retracts the component set for
that test in the same transaction `record-tx` already uses to retract
`:failing-assertions` (`:1574-1641`), so a component that is no longer failing
is gone rather than stale. `:seon.test/failing-assertions` and
`:seon.test/failure-message` stay written until the readers below convert;
`:seon.test.failure/id` is exactly the old identity string's successor for the
interpreted case, so no evidence is lost in the overlap.

**The blob bound.** Reuse the declared dial, do not add a second threshold
mechanism: `:seon.config.eval.result/blob-threshold` = **4,096 B** on
`default`. Above it, stage and store the digest; below it, the exact EDN
string. Idiom to copy: `seon.turn/stage-reply!` (`src/seon/turn.clj:49-66`)
with `blob/stage!` (`src/seon/blob.clj:200`) and `blob/commit-staged!`
(`:276`). **Staging must happen in `commit-results!` (`:1655`), which holds
the connection — `record-tx` runs inside the writer as a `:db.fn/call` and has
only a database value.** At today's volumes that dial fires for a handful of
assertions per run: mean 953 B per assertion, largest whole message 14 KiB.

## 3. "What made it red" — measured, and the verdict

The question is the diff between the failing run's reach closure and the last
green run's. Three measurements, one per hypothesis.

| Hypothesis | Probe | Number | Verdict |
|---|---|---:|---|
| The closure's content is retained beside the digest | read the result row and the writer | only `:seon.test/reach-digest`, one 64-hex string (`runner.clj:1470`, written at `:1629`) | **refuted** — the `[symbol source spec]` node parts and schema forms live only in the in-process index the reach cache holds (`:1448-1477`) |
| So recompute it from history with `as-of` | warm current vs `as-of` for one test | warm **50.6 ms**; cold `as-of` **4,262 ms**; the same `as-of` again **4,035 ms** | possible but priced at ~4 s per comparison, and no retention: historical values derive independently by design (`reach-digests` docstring, `:1479`) |
| At least the recorded digest is reproducible at its own basis | recompute at the row's `:seon.test/run-basis-t` | recorded `f7b24f55…`, recomputed `40975af9…` | **refuted — the diff is unsound today** |

The third result is the real finding. Every recorded run's
`:seon.test.run/branch` is a transient publication branch
`building-source-<pid>-<ms>-<uuid>` (`src/seon/cluster/source.clj:192-196`);
42 distinct ones on `default`, none of them `cluster-default`. The tested
basis belongs to a branch `default`'s history cannot address, so `as-of` on
`default` answers a different question and `record-tx`'s own fallback
(`:1589-1592`) correctly declines to interpret it.

Retention on the left-hand side is worse than the price: of the 87 red rows,
**50 have no `:seon.test/reach-digest` in history at all**, 23 have exactly
one value ever, and only **14 have two or more distinct digests**. For 73 of
87 red tests the "diff" has no second side — and a digest difference, when it
exists, names nothing: it is a hash, so it cannot say which function changed.

**The kill (no cache, no new hash).** Stop trying to diff hashes across a
branch that is gone. "What made it red" is two existing fact queries on
`cluster-default`, where program facts actually land through `init --dev`:

1. the last green for this test, from the row's own history — the last
   transaction where `fail-count` and `error-count` were both 0;
2. the functions in the test's current closure whose `:seon.fn/source` or
   `:seon.fn/spec` datom is newer than that transaction, via
   `(db/since (db/history db) t)`.

Measured on the same red test: **3.9 ms** for the last green from history,
**3.4 ms** for the changed-function set since that basis (13 functions), plus
the **50.6 ms** warm closure — **≈ 58 ms and a named list of functions**,
against 4,262 ms for an unsound hash comparison. This needs one accretion to
be complete rather than a heuristic: write the closure MEMBERSHIP at result
time as `:seon.test/reach` (cardinality-many refs to the reached `:seon.fn`
entities, replaced per run exactly like `failing-assertions`), so the red
run's own closure is a fact instead of a recomputation of today's graph. Do
not store a digest per reached function: that is 907,605 pairs suite-wide
([reach-digest probe](reach-digest-2026-09-16.md)) for evidence the
function rows' own history already carries.

## 4. How the test pair renders failures

`seon.render.test/render-ai` (`src/seon/render/test.clj:7`) prints a state
word and a `seon.db/pull` form that names `:seon.test/failing-assertions` and
`:seon.test/failure-message`; `render-html` (`:36`) dumps the same two values
through `pr-str` into a `<dl>`. So an agent's opening shows a set of opaque
64-char hashes and one concatenated wall of text, with no link to the code.

With the component in place the pair changes shape, not size:

- **AI**: state line, then one line per failure — `type`, the testing
  contexts, the author's message, `expected:` / `actual:` (the stored exact
  strings, or the requery form for a blob), and the site as
  `path:line`. Then the same `seon.db/pull` form, now naming
  `{:seon.test/failures [...]}`, so the agent can reread the evidence as data.
  Elision stays where it is ruled — the value renderer at evaluation time; the
  render function adds no second clipping spot.
- **HTML**: one `<section>` per failure, its heading a link to the namespace
  page anchored on the file entity and line (`route/path`
  `::route/namespace`, plus the `:seon.fn/form-span` the test row already
  carries for the enclosing `deftest`), `expected`/`actual` in `<pre>`, and
  the existing called-function links kept. No presentation clipping in HTML.
- Because `:seon.test.failure/file` is a **ref** to the `:seon.fn.file`
  entity, "which failures are in this file" and "which test failed at this
  line" become Datalog, which is exactly what an issue's required-test
  evidence needs.

## 5. Regression list

Each names the class, on the canonical fixture with armed contracts:

1. `seon.test.runner-test/one-failing-is-becomes-one-failure-entity` — a
   fixture test with two failing `is` forms yields two
   `:seon.test.failure` components under the result, each with its own
   `:type`, `:message`, `:expected`, `:actual`, `:file` ref and `:line`.
2. `…/a-repeated-failure-upserts-its-entity` — running the same fixture test
   twice leaves the same `:seon.test.failure/id` with `:seen-count` 2 and a
   later `:last-run`; entity count is unchanged. (Fails today: there is no
   entity.)
3. `…/a-green-run-retracts-its-failures` — a rerun that passes leaves zero
   components and zero `:failing-assertions` on the row.
4. `…/an-oversized-actual-settles-into-a-blob` — an `actual` above the
   declared dial stores `:actual-blob` plus `:actual-size` and no
   `:actual` string, and the staged write is committed by `commit-results!`,
   not by the writer.
5. `…/an-interpreted-test-failure-records-no-false-site` — an SCI-reported
   failure records no `:file`/`:line` (typed absence, not a sci frame) and
   keeps the normalized-claim identity.
6. `seon.test-test/what-made-it-red-names-changed-functions` — for a test
   with a recorded green and a later red, the derivation returns the named
   functions whose source or spec changed since the green transaction, and
   returns a typed unknown (never an empty "nothing changed") when no green
   is retained. (Fails today: 73 of 87 red rows would silently answer
   "nothing".)
7. `seon.render.test-test/failures-render-their-site-and-claim` — the AI
   projection contains the expected/actual text and `path:line`, and no
   64-char identity string; the HTML contains a link to the file.

## 6. Prices and the decisions that need the owner

| # | Decision | Option | Price | Give up |
|---|---|---|---|---|
| 1 | Failure identity when the reporter gives no first-party frame | **A (recommended)** site when known, normalized claim otherwise — one attribute, one derivation | ~0 extra work; the existing hash becomes the fallback | an interpreted test that later compiles changes identity once |
| | | B: always the normalized claim | simplest, no site detection | loses the file/line join for 83 of 87 red rows — the point of row 17 |
| | | C: always site, refuse when absent | uniform | 93 source-less rows and every SCI test record no failure at all |
| 2 | The blob dial | **A (recommended)** reuse `:seon.config.eval.result/blob-threshold` (4,096) | no new dial, no second mechanism | the dial's name reads as evaluation-specific |
| | | B: a new `:seon.config.test.failure/blob-threshold` | independent tuning | a second dial for one behaviour |
| 3 | "What made it red" | **A (recommended)** derive from the row's history + `since` on `:seon.fn/source`/`spec`, and write `:seon.test/reach` membership at result time | ≈ 58 ms per question; one accretive attribute at `record-tx` | the closure is a per-run fact set (≈ 668 refs median per test) |
| | | B: keep digests, recompute closures at historical bases | no schema change | 4.0–4.3 s per comparison, unsound across the `building-source-*` branch, and names no function — do not ship |
| | | C: a component per reached function per run | exact per-function evidence | ~907k entities per full suite for evidence `:seon.fn` history already holds |

Land order matches §7.4: the failure component (row 17) is independent of the
fault graph and can land first; `:seon.test/reach` membership is the only part
that touches the selection path and should follow it.

## Files

Owned by this note: `docs/prds/steward-platform/research/test-failure-facts-2026-09-16.md`,
`docs/prds/steward-platform/research/test-failure-facts-probe-2026-09-16.clj`.
Protected (foreign uncommitted edits at the time of writing, untouched):
`resources/seon/schemas/seon.eval.edn` (modified),
`docs/prds/steward-platform/research/writer-census-probe-2026-09-16.clj`,
`build/`, `workers/` (untracked).
