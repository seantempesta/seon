---
type: research
status: complete
date: 2026-09-07
tags: [research, storage, print, render, error, test, instrumentation]
---

# Repair 2: the gate was blind, and what it saw when it opened its eyes

Written by the `storage-bound-repair-2` lane against
[AGENTS.md](../../../../AGENTS.md) §2.3, §2.4 and §5,
[the independent verification](verify-storage-repair-2026-09-07.md) (read end
to end — its ranked BR1-BR4 and FR1-FR7 are this lane's assignment),
[the repair's landing note](storage-bound-repair-landing-2026-09-07.md), and
[the first verifier's report](verify-storage-bound-2026-09-07.md), plus the
`clojure-testing`, `data-oriented-clojure` and `repl` skills.

## 1. Commits

| commit | subject |
|---|---|
| `07394e485` | BR2 — the gate armed no contract instrumentation |
| `222559d5c` | BR1 — every presentation cut was a contract violation |
| `5a26941c6` | BR3 — the print-node contract recursed per level |
| `91d7e2bef` | BR4 — the transcript's budget mechanism had no driver |
| `97f864bad` | FR1, FR3, FR5, FR6, FR7 and three contract self-violations |
| `4ec6bd82a` | the node-face validator's ambient read; SCI evaluation mode's retired keys |
| `e46df126a` | the platform tier under the contracts it now arms |

## 2. BR2 — the gate was blind, and what opening its eyes showed

`bin/test` armed no Malli instrumentation: only `script/seon/fresh_operator.clj`
did, at operator boot. Every contract a live cluster enforces was therefore
invisible to the one correctness gate — this project's named failure class
(a check that reports health because its subject was never asked) sitting
under the gate itself.

A worker JVM now applies `seon.instrument/apply!` exactly as boot does, from
the SHIPPED decisions (`seon.config/default-decisions` +
`seon.config/result-caps`), so the gate cannot drift from boot by carrying
constants of its own and an absent cap refuses NAMING the key. Two ordering
facts the arm exposed are fixed in the same commit: the packaged projection is
acquired AFTER the worker's requires, and the namespaces owning the
population's predicate symbols are loaded first (derived from the population,
never a list) — without either, `seon.schema/malli-form?` answers false for
four shipped contracts and `seon.schema/canonical-definition` violates its own
output contract on any static analysis of the tree.

### 2.1 The instrumented gate's reds BEFORE the repairs

Measured in a baseline worktree at `58575c676` carrying ONLY the BR2 commit
(`git worktree add tmp/repair2-baseline HEAD`, the two BR2 files copied in),
on the assigned selection plus `seon.instrument-test`, `seon.test-runner-test`
and `seon.effect-test`:

**221 tests, 459 assertions, 155 red** (`tmp/repair2/baseline-instrumented.log`):

| namespace | red |
|---|---|
| `seon.render.web-test` | 50 |
| `seon.instrument-test` | 23 |
| `seon.cluster.run-test` | 19 |
| `seon.render.transcript-test` | 17 |
| `seon.sci.admit-test` | 13 |
| `seon.effect-test` | 11 |
| `seon.error-test` | 9 |
| `seon.print-test` | 5 |
| `seon.render.walk-test` | 4 |
| `seon.test-runner-test` | 3 |
| `seon.repl-test` | 1 |

131 of those are uncaught contract violations, and they decompose into a
handful of causes, not 155:

| cause | count | disposition |
|---|---|---|
| `seon.sci.eval/cluster-ctx` VIOLATES ITS OWN CONTRACT — the 1-arity delegated through the 2-arity with `nil`, and wrote `{:seon.db/connection nil}` into the ctx's custody besides. `seon.test-support` builds the process base that way, so it reached almost every fixture | 106 | fixed (each arity hands on only what it has) |
| the admit-test request's `:seon.sci.admit/record` omits the declared `:seon.eval/host-interop-count` | 11 + 4 property failures | fixed (fixture) |
| `seon.print/elision` refuses the stored nils `elision-node` merged in | 4 | BR1 |
| `emit-text` / `emit-both` handed fixture maps that are not print nodes (a `::nil` face with no value key; a `::truncated-string` with no length or bound; an undeclared face) | 3 | fixed (fixtures; the undeclared-face floor moved to `#'emit-node`) |
| `seon.repl/entity-emission` invalid-output: a run-test fixture used `:seon.cluster.eval/interrupted-at true` | 1 | fixed (fixture, and it now asserts the rendered instant) |
| `seon.render.web/derived-port` handed `"."` by a property generating bare ASCII instead of the declared cluster-name domain | 1 property | fixed (generator) |

None of these were visible to `bin/test` before this commit; the two the
verifier measured live (BR1, BR3) are among them.


## 3. BR1 — every presentation cut was a contract violation

`seon.print/elision-node` built the one cut constructor's request with `merge`
over a literal map, so `::prefix` and `::bound-by` rode as PRESENT NILS
whenever the profile declared neither — the ordinary case. `:seon.print/elision-request`
marks both optional, and an optional key present as nil fails its contract, so
on every live cluster each cut answered
`seon.print/elision violated its contract (invalid-input)` instead of an
elision: `render-ai` returned a diagnostic, the agent's prospective prompt was
`:seon.render.web/prospective-context-unavailable`, and MCP `eval_clj` failed
for any result needing a cut. `fit` was the identity before the storage-bound
repair, so nothing reached the constructor until it was restored.

Absent means no key: the request is now built from the entries that exist.
Each of `fit`'s three cut sites also NAMES the bound that made it —
`:seon.render.profile/max-children` for a collection cut, the admitted node's
own bound or `:seon.render.profile/token-budget` for a string cut — instead of
inheriting a profile-level constant a caller had to remember to set.

## 4. BR3 — the print-node contract recursed per level

`:seon.print/node` was a recursive Malli `:ref`, and Malli's ref validator
recurses once per level by construction
(`reference-code/malli/src/malli/core.cljc:1975-2000`), so under the contracts
every live cluster arms, validating a node deeper than 3,509 answered
`java.lang.StackOverflowError` — an `Error` escaping a total operation at the
one boundary law 2.4 requires a flat value from — while the walk that built the
node is iterative and admits 100,000 levels. The proof that said otherwise was
measured on a JVM with no contracts armed.

The face table is now declared once as `:seon.print/node-face`, one level deep,
with each child slot a shallow `:seon.print/node-child`; `seon.print/node?`
walks the tree on an explicit stack and validates each node against that
declared shape. Depth admitted by a contract is now the depth admission
admits. Measured on a fresh JVM with the packaged projection handed:

| case | before | after |
|---|---|---|
| 20,000-deep node validated | `StackOverflowError` | `true` |
| a `::number` face whose value is a string | `false` | `false` |
| an undeclared face | `false` | `false` |

Because the declaration is deliberately no longer recursive, `:seon.print/node`
and `:seon.print/node-child` declare `seon.print/node-generator` — one honest
generator spanning every declared face, which is what the two long generative
print properties now generate from.

`seon.print-test/terminal-emission-is-total-for-an-unknown-or-absent-face`
moved to `#'emit-node`: an undeclared face is not a print node, so the public
boundary refuses it by contract while the emitter stays total over whatever
reaches it — both halves true at once, instead of one of them being true only
because nothing asked.

## 5. BR4 — the transcript's budget mechanism had no driver

`best-summary` (the only caller of `fits?`) had no caller anywhere in `src/`,
and `projection` derived `::elided` from the history QUERY's limit while
writing `::token-budget` as the measured output size. Presentation elides in
exactly one place, so the transcript now renders the history its query
admitted and `seon.print/fit` at the AI boundary makes the cut. Deleted:
`best-summary`, `fits?`, `output-tokens`, `marker-text`, `::elided`,
`::token-budget`, `::minimum-token-budget`, `history-count`, `message-count`,
`receipt-count`, `selected-run-count`, and the undeclared
`:seon.render.transcript/token-budget` key with its four writers.


## 6. The frictions

| finding | disposition |
|---|---|
| **FR1** `:seon.error/data-size` reported the SUBSTITUTE's size for `:unserializable` | fixed: a marker that measured nothing carries no size, so the fact OMITS `:seon.error/data-size` (absent, never a lie) and the marker's own reason says why. `seon.cluster`'s staging decision reads the size only when it is present |
| **FR2** a throwable-shaped fault keeps no inline evidence at any plausible bound | FILED, not fixed: [a-throwable-fault-keeps-no-inline-evidence-at-any-plausible-bound](../../../seon/issues/a-throwable-fault-keeps-no-inline-evidence-at-any-plausible-bound.md) — raising the bound is not the fix, since a stack trace grows with the stack |
| **FR3** `error/prepare` silently fell back to its bootstrap 16,384 | fixed: `:seon.config.error/max-evidence-bytes` is a REQUIRED member of `:seon.error/normalize-request` and `:seon.error/commit-tx-request`, the bootstrap constant is deleted, and the four committers (`seon.cluster`, `seon.cluster.loop`, `seon.schedule`, `seon.sci.eval`) hand the cluster's own dial — which the run loop now carries beside its other dials |
| **FR4** AGENTS §2.4's "a request carrying no profile makes no presentation cut" was false | fixed in prose: `seon.render/request-profile` DERIVES the cluster's agent profile at the render entry points, so the AI projection is cut either way; only a seam holding no declared width (`seon.render.walk/presentation-width`) makes no presentation decision |
| **FR5** `:seon.sci.admit/capped?` still written as a stored nil | fixed: the four writers are deleted (`seon.cluster` ×3, `seon.sci.kernel`), the MCP tail-elision enrichment is keyed on the `:seon.sci.admit/elided` SENTINEL it actually looks for rather than on the retired flag it read forever as nil, and the tests assert the key's absence. [Issue resolved](../../../seon/issues/archive/the-mcp-envelope-still-reports-the-retired-capped-key.md) |
| **FR6** eleven tests build a SCI ctx around the repaired fixture | mostly fixed: `seon.render.transcript-test`, `seon.render.web-test` (both sites), `seon.concurrency-streams-test` and `seon.repl-parity-test` go through `support/fork-cluster-ctx`. `seon.call-preparation-test` and `seon.custody-stability-test` name `cluster-ctx` as their SUBJECT, and `my.plan-test` / `seon.cluster.agent-identity-test` build a production-shaped environment explicitly, so they already hand what production hands. The three `seon.cluster.turn-test` sites exercise COLD acquisition deliberately, and the arity repair below makes that legal |
| **FR7** `:seon.repl/interrupted` was a quoted string | fixed: it renders as the readable `#inst` the one print grammar renders every other instant as, with a regression (`seon.repl-test/an-interrupted-evaluation-says-so-as-readable-data`) — there was none before |

### 6.1 Two contract self-violations the arm exposed, both fixed here

- `seon.sci.eval/cluster-ctx`'s 1-arity delegated through its 2-arity with
  `nil`, so the function violated its own declared contract and wrote
  `{:seon.db/connection nil}` into the ctx's custody. `seon.test-support`
  builds the process base that way, which is why this one defect accounted for
  106 of the 131 uncaught violations in the baseline. Each arity now hands on
  only what it has.
- `cluster-ctx` then called `acquire!` with `{:seon.schema/projection nil}` —
  the same stored-nil-into-an-optional-key shape as BR1.


## 7. The live proof

The development cluster REFUSED adoption, for a reason this lane could not fix
from outside it:

```text
bin/seon --root tmp/juniper-context-live init --dev juniper-context
● current-src: analysis started: 251 source inputs
✗ seon.schema/canonical-definition violated its contract (invalid-output):
  must be a parseable, EDN-readable Malli form
  args "[:=> [:cat :seon.print/identity-attributes :seon.print/node]
          :seon.print/references]"
```

Adoption ANALYSES the tree before it reloads namespaces, so that JVM has the
new `:seon.print/node` declaration and not yet the `seon.print/node?` Var it
names; a fresh JVM adopts the same commit without complaint (proven below).
Filed as
[a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place](../../../seon/issues/a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md).
The assignment permits only `init --dev` at that root, so the lane stopped
there and proved the same facts on its own scratch cluster instead.

### 7.1 Scratch cluster `repair2` under `tmp/repair2-live`

`bin/seon --root tmp/repair2-live init` published from this tree; `start
repair2` booted; **871 instrumented vars** in that JVM.

**BR1, at the render boundary, with the cluster's own derived profile:**

| value | AI | HTML |
|---|---|---|
| 5 MiB string | **1,832 bytes**, no error, `… more characters`, `requery by`, `bounded by :seon.render.profile/token-budget` | **5,243,007 bytes** — whole |
| 5,000-element vector | **270 bytes**, no error, `… more children`, `requery by`, `bounded by :seon.render.profile/max-children` | **429,189 bytes** — whole |

Before this repair both raised
`seon.print/elision violated its contract (invalid-input)`.

**BR3, admission under the same armed contracts:**

| value | outcome |
|---|---|
| 5,000-deep map | admitted WHOLE (was `StackOverflowError` above 3,509) |
| 20,000-deep map | admitted WHOLE |
| 1,000,000-element vector | `:over-bound`, `:seon.eval/size` **8,388,644** — bytes reached |

**The agent's own prompt page**, `GET /ns/my.agents.root/debug?prompt=true`
(200, 30,374 bytes): no `prospective-context-unavailable`, no contract
violation, and **eight elisions**, each carrying `more characters`,
`requery by`, and `bounded by :seon.render.profile/token-budget`. That page
was `:seon.render.web/prospective-context-unavailable` before.

**FR5 live:** every `eval_clj` envelope in this session carries no
`seon.sci.admit/capped?` key at all.

### 7.2 One tool defect the live cluster reported immediately

SCI evaluation mode (`eval_clj` `mode: "door"`) answered
`seon.sci.eval/evaluate violated its contract (invalid-input): missing
required key` on its first call: `script/seon/dev/mcp.clj` was sending the
RETIRED `:seon.cluster.run.form/source` and `/ns` spellings, so the evaluator
received no source at all. Fixed in `4ec6bd82a`.


## 8. The gates

| gate | before (instrumented, BR2 only) | after |
|---|---|---|
| the assigned selection + `seon.instrument-test`, `seon.test-runner-test`, `seon.effect-test`, `my.message-test` | 221 tests / 459 assertions / **155 red** | 253 tests / **1,365 assertions** / 48 red, then further repairs |
| `bin/test --platform` | **9 red** (every one the refusal class below) | **GREEN — 73 tests, 398 assertions, 0 failures, 0 errors**, root removed as successful |

The assertion count is the honest measure of what the earlier numbers hid:
the same selection ran 459 assertions when a fixture defect killed the suite
at its first ctx, and 1,365 once the fixtures were repaired.

### 8.1 The one class the armed gate exposes everywhere

A test pins a function's own typed refusal for an input its DECLARED contract
forbids. With contracts armed the contract refuses first — at the same
crossing, with an equally evidence-complete value naming the function, the
member and the offending argument. Nine platform members were this; the bulk
tier has more (`my.message-test`, `seon.sci.admit-test`'s absent-bound case,
`seon.effect-test`'s oversized-request case). Filed as
[agent-facing-refusals-are-asserted-where-contracts-refuse-first](../../../seon/issues/agent-facing-refusals-are-asserted-where-contracts-refuse-first.md);
the platform members are fixed there and named in the note.

### 8.2 `bin/test --all`, whole

**1,439 tests, 11,374 assertions, 314 red** (`tmp/repair2/all2.log`), the
platform tier green ahead of it. 255 of the reds are uncaught
`invalid-input` contract violations and 11 are `invalid-output`; the largest
concentrations are:

| namespace | red | dominant cause |
|---|---|---|
| `seon.cluster.turn-test` | 55 | `seon.cluster.loop/turn` handed a non-string where its contract declares one |
| `seon.cluster.boot-test` | 20 | (live boot fixtures) |
| `seon.cluster.agent-test` | 16 | |
| `seon.render.web-test`, `seon.render.value-test`, `seon.fs.jvm-test` | 11 each | |
| `seon.render-simplification-test` | 10 | |
| `seon.sci.eval-test`, `seon.dev.fresh-operator-test` | 9 each | `seon.sci.kernel/context-projection` handed something that is not an SCI ctx (25 occurrences across the run) |

Every sampled red is a FIXTURE handing a shape the declared contract forbids,
or a test pinning a refusal the contract now makes first (§8.1). None is a
production behaviour change this lane could find — which is the point: this
is the backlog the gate could not see, now visible, counted, and attributable
one function at a time.


## 9. What is unfinished

- **314 bulk-tier reds** under armed contracts (§8.2). They are the visible
  backlog, not a regression: the same tree with the gate blind reported them
  green. They need one wave, one class at a time — `seon.cluster.turn-test`'s
  55 and the 25 `context-projection` calls are the two biggest single seams.
- **`seon.instrument-test`'s own eight reds.** The suite is written for a
  JVM that starts UNARMED (`remove-is-total` expects zero surviving wrappers;
  `production-instruments-nothing` expects a clean baseline). Arming the
  worker is what makes them fail; the suite's fixture snapshot/restore keeps
  the rest of the gate deterministic, so this is the suite's own repair.
- **The development cluster still refuses adoption** (§7). It needs a stop
  and start, which this lane's assignment does not permit at that root.
- **FR2** (a throwable fault keeps no inline evidence) is filed, not fixed.
- The `my.message-test` assertions are filed with the refusal class (§8.1).
