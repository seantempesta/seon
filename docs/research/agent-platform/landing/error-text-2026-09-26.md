---
type: landing
created: 2026-09-26
tags: [landing, error, instrument]
---

# error-text-opus landing (2026-09-26)

What exists: instrumentation builds a contract refusal from Malli's report (`seon.instrument/violation` →
`:seon.error/problems`), and `seon.error/instrumentation-prose` prints refusals for agents. The change keeps one
explanation (`violation`'s), holds its problems as values, and lets a declared refusal lead with its own message.

## Broken or open (first)

- **RESET NEEDED** (root): default runs the committed archive; nothing here is installed until root adopts.
- **Not in this lane's files, patch for root:** `tmp/error-text-opus/transcript-contract-refusal.patch`. `render/transcript.clj:1392-1394`
  treated a thrown contract refusal as a refusal only when it carried `:seon.instrument/explanations`. That member is
  gone, so until the patch lands the debug context page rethrows a contract refusal from `acquire-context!` when it
  should render it. The patch drops that one conjunct.
- **Still open (other owners):**
  - D12 `Contract finding: seon.sci.kernel/failure-value returned this error…` on every agent exception. Still
    reproduced: `(/ 1 0)` on branch `error-text-opus`. The selection lives in `render.clj:519-524` (`declared-producers`
    → `error-alternatives`), and the value comes from `sci/kernel.clj:559` (`failure-value`, declared `:seon.error/base`).
    I did not probe why the value fails its own declared return (LOAD CAP). Owners: render.clj and kernel.clj.
  - JVM-mode MCP prints an uncaught exception as `Throwable->map`, which puts the ex-data in the output twice (`:via[0]
    :data` and `:data`). The mcp.clj owner could print through the error render pair instead.
  - `seon.sci.admit/edn` emits print-node trees, not value EDN: `nil` comes out as
    `"#:seon.print{:face :seon.print/nil, :value nil}"`. `error/project-observation` (error.clj:400) still stores that
    string for write-attempt and schema observations (error.clj:597-607). Owner: admit.clj.
  - Dead schema left installed: `seon.instrument.explanation(s).edn` and the `:seon.instrument/explanations`
    attribute now have no producer. They can be retired, together with the generators at error.clj:2216-2427 and
    error_test.clj:1035-1107, after an incremental schema-retraction proof on a branch.
  - Grammar: a key whose schema has an `:error/message` reads "the required key :seon.db/db with must be …"
    (error.clj:867). Not changed.
  - SCI refusals still say `Called from sci.lang.Var (lang.cljc:265)` (known, in the parity issue).

## Defect 1: generic text (reproduced)

- Form (JVM, read-only): `(seon.error/instrumentation-prose {… :seon.error/operation 'seon.cluster.source/write-back!
  :seon.error/message "The staged file base changed." :seon.error/offending {:seon.fs/path "src/a.clj" …}})`.
- Before: `seon.cluster.source/write-back! refused  at []: expected the declared requirement (nil), got a map {…}. Fix:
  The staged file base changed. Inspect the named requirement before retrying. Example: No docstring example is available.`
- Cause: `refusal-data`, the generic branch at error.clj:~1038, made up a schema problem for any value that carried an
  operation plus expected or offending, even when `expected` was not a Malli schema (nil here) or the value satisfied
  it. `refusal-text` then appended the "No docstring example" filler line. When prose fell through, it used
  `(name member)` (nil for declared refusals) and `pr-str` with no bound.
- Fix: a problem is built only when Malli explains one. Otherwise the refusal prints its own message, then
  `Expected:` and `Got:` lines rendered by the floor printer (`refusal-value-text`). The Example line appears only when an
  example exists. The "Inspect the named requirement…" filler and the unbounded "Contract violation in …" fallback are
  deleted.
- After (regression `seon.error-test/a-declared-refusal-prints-its-own-message-and-members`):
  `The staged file base changed.\nGot: {:seon.fs/path "src/a.clj"}`.

## Defect 2: 34 KB for one missing key (reproduced)

- Form (JVM, read-only): `(seon.cluster.status/agents {:seon.db/connection (seon.cluster.boot/connection "default")})`.
  MCP blob `:seon.blob/size 34518` (digest `182723de…`). The ex-data alone is 3,074 bytes as `pr-str`.
  `seon.sci.kernel/failure-value` called with a bad request produced 67,234 bytes the same way.
- Cause: instrument.clj `boundary-refusal` ran a second `m/explain` (after `violation`'s) and stored each problem as
  `:seon.instrument/explanations`: items, then schema/value locations, then segments, then keys. Every key and the
  actual value were pre-printed floor-printer strings (`error/project-observation`). That is seven levels deep, so the
  MCP floor printer hit max-depth and emitted a ~600-byte elision node with requery forms for every nested branch.
  The same entity appears twice (via + data). It duplicated `:seon.error/problems`, which already holds the path,
  expected form and offending value.
- Fix: delete the second explanation, `observation-location`, and the `:seon.instrument/explanations` member of
  `:seon.instrument/contract-error` (seon.instrument.edn). The problems are values, and the floor printer bounds them at
  the edge.
- Message (unchanged, 330 chars) names the key: `seon.cluster.status/agents refused argument 0 (0-based) at
  [:seon.db/db]: expected the required key :seon.db/db …`.
- Regression: `seon.instrument-test/a-missing-key-refusal-holds-values-and-names-the-key` asserts:
  - no explanations member and no `#:seon.print` string;
  - problem paths are `[[:seon.db/db]]`;
  - message < 400 chars and `pr-str` < 2,000;
  - the value is valid under `:seon.instrument/contract-error`.
- Expected after adoption: the JVM-mode MCP print drops the explanations entity. That leaves the ex-data (~1.7 KB with
  the connection offending) printed twice by mcp.clj, about 3.5 KB. Getting to a few hundred characters needs the
  mcp.clj change listed above. The agent (SCI) face already prints the message (~500 chars). Not measured
  post-adoption.

## Changed paths, sizes

- src/seon/instrument.clj −41 net; src/seon/error.clj −8 net; resources/seon/schemas/seon.instrument.edn −1.
- Tests: two new regressions; `specialist-renderers-use-their-declared-evidence` and
  `predicate_metadata_refusal_test` converted from explanation members to problems.
- Static checks (REPL, working tree): `seon.contracts-compile-test/check` over error.clj + instrument.clj gives 0
  findings (519 ms); seon.instrument.edn reads as EDN.

## Root must run

After adoption:
- `--test seon.error-test/a-declared-refusal-prints-its-own-message-and-members`
- `--test seon.instrument-test/a-missing-key-refusal-holds-values-and-names-the-key`
- `seon.schema.predicate-metadata-refusal-test`
- `seon.error-test/specialist-renderers-use-their-declared-evidence`
- `seon.contracts-plan-test`, which asserts refusal text

Then apply the transcript patch. No tests were run by this lane.
