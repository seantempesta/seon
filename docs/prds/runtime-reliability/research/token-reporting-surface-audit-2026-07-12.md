---
type: research
status: completed
tags: [research, agent, web]
---

# Token-reporting surface audit (2026-07-12)

## TL;DR

The active agent/debug web path is already clean: prompt totals, per-block
breakdowns, transcript clip notices, value skeletons, turn-open logs, chat
intake logs, blob projections, web results, and shell output totals all use
`seon.ai.tokens/estimate` or `seon.ai.tokens/chars->tokens`.

The remaining violations are narrow but real:

- `seon.eval/cap-edn` persists `N chars elided` into text later shown in the
  agent transcript;
- `seon.agent.fs/stat` returns an unlabeled raw Node byte size to the agent;
- `seon.agent.web/fetch` exposes a byte-denominated request knob even though
  the public contract says all sizes are tokens;
- shell overflow guidance exposes the internal byte ceiling instead of merely
  reporting the captured text in its already-present token fields;
- the gym predicate report prints transcript characters;
- the optional embedding writer has both a second token estimator and a log
  that prints characters;
- the executable legacy JVM MCP formatter emits several raw-character
  truncation notices; and
- one currently unused shared UI component reports omitted characters as a
  bare `+N more`. The ACME-only typeahead tile similarly labels a worker-token
  count as bare `len N`; report it to that owner, but do not touch the ACME pod.

The correct fix is not to convert transport framing, parser spans, substring
cursors, or memory guards into fake token coordinates. Those values are
operational coordinates or internal resource limits. Keep them private. At the
surface, either omit the internal quantity or expose text size through the one
canonical estimator in `src/seon/ai/tokens.cljc`.

No production code was changed by this audit.

## Scope and method

The audit searched:

- active `src/seon/**/*.cljs` and shared `.cljc` files;
- the default agent-visible namespaces in `config/system.edn`, including
  `my.*`, `seon.agent.fs`, `seon.agent.shell`, and `seon.agent.web`;
- active CLJS tests and the gym reporter;
- the default CLJS pod/web logs in `seon.agent.turn`, `seon.web.serve`,
  `seon.web.datastar`, and `seon.web.debug`;
- active JVM support used by the pod (`seon.embed` in the wire server); and
- executable `bin/` evaluation surfaces.

Paused JVM web code is not treated as an active path. It is listed separately
where it duplicates the estimator because the larger refactor intends to
delete obsolete inspector-era paths instead of preserving parallel code.

The distinction used throughout is:

- **reported size** — a number presented to a human/agent as the size of text;
  this must be estimated tokens;
- **storage projection** — an internal measured character count such as
  `:seon.agent.turn/prompt-chars`; this may remain, but must be converted before
  display;
- **operational coordinate** — an exact character offset used by `subs`, a
  parser span, or a length-framed transport field; changing it to estimated
  tokens would make the operation incorrect; and
- **resource guard** — an internal byte/character ceiling used to bound RAM or
  I/O. It may remain internally, but its raw quantity must not leak into a
  report.

## Clean active boundaries

These paths already obey the rule and should be preserved rather than
reimplemented:

| Surface | Current implementation |
|---|---|
| Whole prompt and block projections | `src/seon/agent/debug.cljs:76-112` uses `tokens/estimate` for system, blocks, and full prompt. |
| Debug web header and per-block rows | `src/seon/web/debug.cljs:265-316,452-488` renders only token estimates. |
| Turn-open log | `src/seon/agent/turn.cljs:586-591` separately estimates context and system text. |
| HTTP input logs | `src/seon/web/serve.cljs:507-510,656-678` estimates `/agents/run` and `/chat` text. |
| Transcript and block clip markers | `src/seon/agent/ctx.cljs:470-496,1038-1058,2528-2572` converts internal character caps at the display boundary and labels tokens. |
| Large string skeletons | `src/seon/render/value.cljs:405-413` and `src/seon/render.cljs:545-559` convert `:seon.render.value/string-len` before display. |
| Blob facts and responses | `src/my/blob.cljs:39-119,204-263,322-343` store and return `:my.blob/tokens`. |
| Web content responses | `src/seon/agent/web.cljs:117-141,250-273,335-378` return preview/total token estimates. |
| Shell output responses and job summaries | `src/seon/agent/shell/internal.cljs:116-137` and `src/seon/agent/shell.cljs:412-480,483-512` use `out-tokens`, `err-tokens`, and `tokens`. |
| Active web transport logs | `src/seon/web/datastar.cljs:250-275,530-556`, `src/seon/web/debug.cljs:965-995,1076-1100`, and `src/seon/web/serve.cljs:195-216` report connection/target cardinalities and durations, not text lengths. |

`src/seon/ai/tokens.cljc:16-52` is the only allowed estimator owner:

- `tokens/estimate` when the string is available;
- `tokens/chars->tokens` when a storage/internal path already measured the
  character count and retaining/reconstructing the string would be wasteful;
- `tokens/estimate-chars` only to turn a token budget into an internal string
  boundary; and
- `tokens/clip-str`/`tokens/bounded-pr-str` for token-budgeted display text.

## Exact active change map

### Persisted eval elision — required

`src/seon/eval.cljs:2888-2922` keeps a storage-time character cap, which is
fine, but `cap-edn` writes the measured remainder as `N chars elided`. That
string lands in `:seon.eval/result-edn`/`:seon.eval/error` and is rendered into
the transcript, so it is not storage-only metadata.

Change only the generated marker's measurement:

- preserve the existing prefix cap and memory-safety behavior;
- derive the omitted estimate with
  `tokens/chars->tokens (- n limit)` (the already-measured-count entry point);
- label the result in tokens; and
- do not allocate `(subs s limit)` merely to call `tokens/estimate` on a
  multi-megabyte omitted tail.

Test migration: `test/seon/eval/memory_safety_test.cljs:40-72` currently pins
the exact `chars elided` phrase. Keep the prefix/bound/nil-safety assertions,
then assert the structural unit invariant (no generated numeric
`char`/`character` unit and the omitted count is the canonical estimate). Do
not pin a whole context sentence.

### Agent filesystem stat — required

`src/seon/agent/fs.cljs:63,180-192,520-536` exposes Node `Stats.size` as
`:seon.agent.fs/size`. It is bytes, although the attribute and first-line
docstring do not say so. `stat` is in the default agent toolbelt, and its return
map is rendered directly, so this is an agent-visible raw size.

The simplest correct design is to remove `:seon.agent.fs/size` from the public
schema/response. No production caller and no test uses it; current internal
callers use only `ok?`/`dir?` for gating. Do not read file content inside `stat`
to manufacture a token estimate: directories and binary files make that both
expensive and dishonest. Text-reading functions can report tokens from the
content they already read if that fact becomes useful.

Add a structural response-schema test that `stat` exposes path/time/file-state
facts but no `size`/`bytes`/`chars` field. This tests the contract, not wording.

### Public web fetch byte knob — required

`src/seon/agent/web.cljs:70-115,275-308` exposes
`:seon.agent.web/max-bytes` in the default agent namespace even though the
namespace contract at lines 33-40 says all sizes are tokens. The corresponding
`src/seon/agent/web/internal.cljs:36-46,435-466` byte ceiling is a legitimate
streaming RAM guard and should remain private.

Remove `::max-bytes` from the public request schema, destructuring, and
agent-facing documentation; always pass the private hard ceiling to the
transport. If a caller-controlled content limit is later proven necessary,
add a token-denominated decoded-text limit while retaining the independent
private byte guard. Do not relabel bytes as tokens.

`test/seon/agent/web_test.cljs:314-338` should exercise
`internal/read-body-capped` (or a private injected guard) directly for the RAM
ceiling. The public fetch-schema test should assert that the only content-size
knob is `max-preview-tokens`. This avoids preserving an internal transport knob
as public API merely to make one test convenient.

### Shell capture guidance — required

`src/seon/agent/shell/internal.cljs:116-143` already computes honest
`out-tokens` and `err-tokens`, but the overflow hint prints the exact
`max-output-bytes` value and says `bytes beyond that were dropped`.
`src/seon/agent/shell.cljs:252-284,365-381,483-494` also advertises the raw
megabyte guard in agent-visible documentation.

Keep `max-output-bytes`, `bg-max-stream-bytes`, `execFile :maxBuffer`, and the
exact `since` cursor internally. Change the generated hint to say that the hard
capture ceiling was reached and direct the agent to `run-bg!`; the envelope's
existing token totals are the only reported text sizes. Remove the numeric
MB/byte quantities from the public function documentation.

`test/seon/agent/shell_test.cljs:194-222` already asserts the useful structural
facts (`truncated?`, retained output metadata, and the `run-bg!` recovery hint).
Replace the human-facing `2MB` test descriptions; do not assert a replacement
sentence.

### Gym transcript predicate diagnostics — required

`test/seon/gym/driver.cljs:1041-1049` emits `transcript N chars` in the result
detail for both inclusion predicates. The namespace already requires
`seon.ai.tokens` at line 89.

Use `(tokens/estimate transcript)` and a token-labelled detail. Predicate
truth remains byte/whitespace based; only the human report changes. Test the
result detail's unit/number structurally, not its prose.

### Optional embedding writer log and duplicate estimator — required when enabled

`src/seon/embed.clj:702-730` defines a private `estimate-tokens` with its own
`(quot (count s) 4)`. `truncate-to-token-cap` then logs both raw characters and
the locally estimated tokens at lines 713-718. This namespace runs in the
active wire-server when `SEON_EMBED` is enabled.

Require `seon.ai.tokens`, delete the local estimator, use `tokens/estimate` in
batch planning, and log only the before/after token estimates. The internal
`cap-chars` substring boundary can remain. Add a log-capture test that extracts
the numeric before/after values and compares them with the canonical estimator;
do not pin the sentence.

## Executable and optional paths

### Legacy `bin/mcp-server`

The active CLJS tool is `bin/mcp-server-cljs`; `bin/mcp-server` targets the
paused JVM/orchestrator path. It remains executable and emits human/agent tool
responses, however:

- `bin/mcp-server:93-95` owns character-denominated display constants;
- `:516-523` reports stdout as `N chars, showing last M`;
- `:575-612` reports value totals and a shown range in characters;
- `:880-888` reports query truncation in characters; and
- `:914-922` repeats the same formatter for namespace health.

Confirm there is no live launcher, then delete this obsolete executable with
the paused inspector-era surface. Keeping it by adding a second local `/4`
helper is forbidden. If it is still required, invoke the canonical
`seon.ai.tokens` boundary through a supported classpath and keep raw `subs`
offsets only as executable paging coordinates, never as the displayed size.

### Dormant shared log component

`src/seon/ui/components.cljc:153-200` clips a detail at 120 characters and
renders the omitted character count as bare `+N more`. There is no production
caller (`log-line`/`log-container` appear only in their definition), while the
active default agent tile renders log messages directly.

Delete the dead component if the UI consolidation confirms it is obsolete. If
retained, make its preview budget token-denominated and render `+~N tok` through
`tokens/chars->tokens`; do not preserve the bare character count. A component
test should inspect the resulting hiccup for the canonical token estimate, not
an exact title sentence.

### ACME-only typeahead tile — report, do not operate the pod

`src/seon/agent/ctx/typeahead_steps.cljs:321-354` renders the CAL-selected
worker token count as `len N` at line 347. The block is absent from the default
manifest and present only in `config/acme.edn:56-58`.

Coordinate with the ACME owner before changing shared source. The final fact
and display should name the unit explicitly (prefer
`:seon.typeahead/chosen-tokens` and `N tok`, rather than the ambiguous nested
`:seon.typeahead/chosen-length`). Migrate
`test/seon/ai/typeahead_test.cljs:276-277` and
`test/seon/agent/ctx/typeahead_steps_test.cljs:197-240` with the field. The test
should assert the token-valued projection/display, not the surrounding UI
copy.

## Duplicate estimators outside the active CLJS path

In addition to `seon.embed`, these paused JVM files inline the heuristic:

- `src/seon/ns/view.clj:203-209,262`;
- `src/seon/ai/agent/views.clj:380-382,1280-1282`; and
- `test/seon/ctx_test.cljs:302` (an active test helper, despite the files above
  being paused).

Replace the test helper with `tokens/estimate` immediately. Delete the paused
UI paths as part of the inspector/world retirement; if any survives, require
`seon.ai.tokens` rather than preserving an inline quotient.

The source-wide check after migration should find no estimator implementation
outside `src/seon/ai/tokens.cljc`:

```bash
rg -n --glob '*.clj' --glob '*.cljs' --glob '*.cljc' \
  '(quot\s+[^\n]+\s+4\)|estimate-tokens|chars-per-token' src test
```

The expected remaining `chars-per-token` and quotient are only the canonical
namespace's implementation/docstring.

## Test/report cleanup tied to the fixes

`test/seon/db/envelope_test.cljs:541-600` is itself a human-visible test report:
it prints `was N chars`, describes a `5000-char` value, and names a
character-denominated display limit. Convert the serialized-envelope budget and
diagnostic to `tokens/estimate`; internal assertions may still use character
counts to prove a stored projection is bounded, but their report must not print
that count.

Use one reusable test helper over **generated reporting text only**:

- reject a generated numeric unit matching `char`, `character`, `byte`, or
  `KB`/`MB`/`GB`;
- compare any reported token number with `tokens/estimate` or
  `tokens/chars->tokens`; and
- recursively reject public response keys whose semantic unit is raw text
  size (`chars`, `bytes`, `text-len`, `string-len`, or generic `size`).

Do not apply the string scan to arbitrary user/source/transcript content: a
user is allowed to type `"500 chars"`, and byte-faithful evidence must remain
byte-faithful. Invoke the helper only on Seon-generated markers, hints, logs,
and summaries. That is a structural reporting-boundary test, not a fragile
context-wording test.

## Explicit non-violations

Do not change these during Phase 0:

- `:seon.agent.turn/prompt-chars` at
  `src/seon/agent/turn.cljs:73,112-129,258-267` is a storage projection and has
  no display reader; debug reconstruction reads the blob and reports its
  `:my.blob/tokens`.
- `:seon.render.value/string-len` is a transient skeleton measurement; every
  active renderer converts it through `tokens/chars->tokens`.
- `:seon.agent.shell/since`/`next-since`, parser spans, diffusion spans, and
  `subs` indices are exact operational coordinates. Estimated tokens cannot
  address a substring.
- wire frame lengths and `maxBuffer`/stream byte guards are internal transport
  or RAM correctness boundaries. Hide their quantities; do not relabel them.
- line counts, row counts, datom counts, agent counts, collection cardinality,
  dimensionality, retry counts, and plan progress are facts about discrete
  items, not text-size reports.
- character/byte wording that describes encoding, escaping, hashing, or
  byte-faithful equality is not a size report.

This boundary prevents the token rule from corrupting exact data-processing
code while making every actual size report consistent and useful.
