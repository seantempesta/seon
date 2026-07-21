---
type: research
status: active
tags: [research, agent, schema, architecture]
---

# Context schema closure measurement — 2026-07-15

## Outcome

The freshly reset ACME cluster renders byte-stable namespace blocks at a fixed
database value. The ordinary task agent's block is 81,627 bytes and 20,406
estimated tokens; the root agent's block is 66,759 bytes and 16,689 estimated
tokens. Schema records account for 15,155 tokens (74.3%) of the ordinary block,
while function contracts account for 4,641 tokens (22.7%). The current renderer
does not hit its schema-closure cap in either projection.

Exact repeated schema lines are measurable but not the main cost. Hoisting only
the repeated definitions into one shared section saves 1,626 ordinary-agent
tokens. Hoisting every unique complete schema definition into that section
saves 1,642 tokens (8.0%) while preserving all 480 schema keys and all 108
function-contract lines. The latter costs only sixteen fewer tokens than the
repeat-only arrangement, but gives schemas one stable, globally ordered cache
region instead of interleaving them with namespace cards.

The smallest global owner is the existing
`seon.agent.ctx.namespaces/namespaces-block`. It should emit a structured
database-derived namespace export and render one shared schema section inside
the same priority-20 block before the namespace function cards. The existing
closure machinery in `seon.agent.ctx` should return structured closure data;
`referenced-schema-block` remains its text-rendering wrapper. This is an
in-place strengthening of one context mechanism, not a new top-level block or
schema registry.

## Dependency ledger

- ClojureScript `1.12.145` analyzer facts supply the indexed `:seon.fn`,
  `:seon.ns`, and `:seon.schema` entities read by context rendering.
- Malli `0.20.0` forms are stored with the owning code facts and rendered from
  the database; no schemas were reparsed from source for this measurement.
- Datahike commit `6f90b339768b1a02066dce3b6fcc93a200758fcc` supplies the
  immutable database value and coordinate used by each read-only probe.
- `src/seon/agent/ctx/namespaces.cljs` owns required namespace cards and
  function-contract rendering. `src/seon/agent/ctx.cljs` owns transitive
  referenced-schema closure. `src/seon/agent/debug.cljs` exposes the complete
  derived preview used here. `src/seon/ai/tokens.cljs` owns the displayed token
  estimate.
- `src-inspect-ai/` owns Inspect task loading and prompt freezing. Historical
  BFCL evidence comes from the successful native run under
  `evals/runs/2026-07-15-bfcl-native-complete-qwen-smoke/inspect-logs/`.
- Relevant target and program authorities are
  `docs/seon/architecture/context.md` and
  `docs/prds/agentic-tool-refinement/roadmap.md`.

## Method and immutable evidence

All current-cluster probes were read-only, cluster-qualified `eval_cljs` calls
against `acme/root` and `acme/hot-tables-exist`. The measurements call the
existing context functions, retain their exact strings, use Seon's token
estimator, and hash UTF-8 bytes with SHA-256. They neither mutate the database
nor change cluster lifecycle.

The database identity was
`6813d1c2-4feb-3272-9b74-4c6769142514` on branch `:db`. The final stability
check used commit `6a570014-112f-515b-8005-e70d750ad69f`, transaction
`536870982`. Namespace bytes and hashes remained identical across the earlier
detailed measurement at commit `6a56ff1c-7895-5c4a-869f-5c97e44b246d`,
transaction `536870976`.

| Projection | Bytes | Tokens | SHA-256 |
|---|---:|---:|---|
| Ordinary namespace block | 81,627 | 20,406 | `05027a7a960ec6bd51b5bba2f51281c7258dc0bf03a4c028c9df07332db22c88` |
| Root namespace block | 66,759 | 16,689 | `63b4cbc7772200008150e8bf2a59ac79e325cfaeab7123f4a9e8501dd9872020` |
| Ordinary complete preview | 88,685 | 22,171 | `8f7adb7785bd1ffbb957cb0186397d8da5fcbfcd95d19d69d3db3b2dfda2988b` |
| Root complete preview | 81,524 | 20,381 | `454443943de01cfe2f789adac24793c2792e6361a44277da59c8256c79de7844` |

Complete previews include transcript and runtime-derived blocks, so their hash
is evidence for that exact database value, not a promise that every turn has an
identical full prompt. The namespace hashes isolate the intended cache point.

## Ordinary task-agent namespace projection

The ordinary agent sees fifteen namespaces and 108 function contracts. A
qualified schema reference appears 254 times in those contracts and occupies
1,457 tokens; that count is a subset of function-contract tokens and must not
be added to the total. "Own" records are schemas selected with the namespace;
"closure" records are additional transitive definitions rendered for contract
completeness.

| Namespace | Body tokens | Functions | Function tokens | References | Reference tokens | Own schemas | Own tokens | Closure schemas | Closure tokens |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `seon.agent.fs` | 2,672 | 12 | 456 | 25 | 173 | 60 | 2,032 | 10 | 183 |
| `seon.agent.lifecycle` | 797 | 5 | 286 | 20 | 118 | 2 | 23 | 15 | 487 |
| `seon.agent.message` | 594 | 2 | 89 | 5 | 38 | 13 | 376 | 3 | 127 |
| `seon.agent.search` | 1,428 | 2 | 89 | 4 | 35 | 38 | 1,338 | 0 | 0 |
| `seon.agent.shell` | 2,070 | 8 | 336 | 15 | 125 | 39 | 1,475 | 9 | 257 |
| `seon.agent.web` | 1,362 | 3 | 122 | 5 | 38 | 43 | 1,240 | 0 | 0 |
| `seon.db` | 3,024 | 15 | 834 | 73 | 331 | 79 | 2,063 | 5 | 125 |
| `seon.schema` | 432 | 7 | 302 | 12 | 75 | 11 | 128 | 0 | 0 |
| `my.blob` | 843 | 5 | 173 | 10 | 55 | 26 | 669 | 0 | 0 |
| `my.canvas` | 1,836 | 11 | 648 | 22 | 131 | 28 | 636 | 15 | 550 |
| `my.data` | 355 | 4 | 136 | 7 | 37 | 9 | 149 | 4 | 69 |
| `my.kb` | 1,150 | 13 | 432 | 14 | 56 | 21 | 361 | 11 | 355 |
| `my.plan` | 2,298 | 14 | 485 | 28 | 158 | 50 | 1,640 | 7 | 171 |
| `my.ui` | 942 | 7 | 253 | 14 | 87 | 21 | 389 | 7 | 299 |
| `my.agent.hot-tables-exist` | 158 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| **Total records** | **20,406** | **108** | **4,641** | **254** | **1,457** | **440** | **12,519** | **86** | **2,623** |

Joining the 526 schema records once, without card framing, costs 15,155 tokens.
Own definitions are 61.3% of the namespace block and transitive closure is
12.9%. Function contracts are 22.7%. Framing accounts for the remainder.

## Root namespace projection

The root sees thirteen namespaces, 92 function contracts, 224 qualified schema
references, 358 own definitions, and 77 closure definitions.

| Namespace | Body tokens | Functions | Function tokens | Own schemas | Own tokens | Closure schemas | Closure tokens |
|---|---:|---:|---:|---:|---:|---:|---:|
| `seon.agent` | 642 | 0 | 0 | 26 | 642 | 0 | 0 |
| `seon.agent.fs` | 2,672 | 12 | 456 | 60 | 2,032 | 10 | 183 |
| `seon.agent.lifecycle` | 797 | 5 | 286 | 2 | 23 | 15 | 487 |
| `seon.agent.message` | 594 | 2 | 89 | 13 | 376 | 3 | 127 |
| `seon.agent.search` | 1,428 | 2 | 89 | 38 | 1,338 | 0 | 0 |
| `seon.db` | 3,024 | 15 | 834 | 79 | 2,063 | 5 | 125 |
| `seon.schema` | 432 | 7 | 302 | 11 | 128 | 0 | 0 |
| `my.canvas` | 1,836 | 11 | 648 | 28 | 636 | 15 | 550 |
| `my.data` | 355 | 4 | 136 | 9 | 149 | 4 | 69 |
| `my.kb` | 1,150 | 13 | 432 | 21 | 361 | 11 | 355 |
| `my.plan` | 2,298 | 14 | 485 | 50 | 1,640 | 7 | 171 |
| `my.ui` | 942 | 7 | 253 | 21 | 389 | 7 | 299 |
| `my.agent.root` | 118 | 0 | 0 | 0 | 0 | 0 | 0 |
| **Total records** | **16,689** | **92** | **4,010** | **358** | **9,777** | **77** | **2,366** |

The root's 435 schema records cost 12,154 tokens when joined once. Its schema
reference lexemes cost 1,239 tokens within the function-contract total.

## Exact repetition and shared-section candidates

The ordinary projection's 526 records reduce to 480 canonical schema keys.
There are 24 byte-identical repeated lines with 40 extra occurrences, worth
5,507 bytes and 1,376 tokens if only identical rendered lines are counted.
Canonicalization finds 25 repeated keys and 46 extra occurrences. Every
occurrence of a key has the same schema form: there are zero conflicts.

The largest exact repetitions are `:seon.db/error` at five occurrences,
`:seon.db.coordinate/coordinate` at four, and
`:seon.db/transact-response` at three. Coordinate members
`:seon.db.coordinate/database-id`, `:seon.db.coordinate/t`,
`:seon.db.coordinate/commit-id`, and `:seon.db.coordinate/branch` each occur
four times. These are consequences of complete transitive contracts, not
invalid duplicated source schemas.

Both candidates use these exact delimiters inside the existing namespace block
before its cards:

```clojure
;;; ┌─ shared schemas ─
; schema :fully.qualified/key = schema-form
;;; └─ end shared schemas ─

```

| Projection | Current tokens | Repeat-only tokens | Repeat-only saving | All-shared tokens | All-shared saving |
|---|---:|---:|---:|---:|---:|
| Ordinary | 20,406 | 18,780 | 1,626 | 18,764 | 1,642 (8.0%) |
| Root | 16,689 | 15,028 | 1,661 | 15,019 | 1,670 (10.0%) |

The all-shared candidate sorts one definition per fully qualified key and
removes the per-card schema records. It preserves all 480 ordinary keys, every
schema form, and every function line byte-for-byte. No closure was truncated,
so this comparison preserves the complete currently rendered transitive
schema set. The root likewise reduces 435 records to 388 unique keys with zero
form conflicts.

The raw token saving alone is modest. The stronger reason to prefer all-shared
over repeat-only is cache topology: stable schema definitions form one ordered
prefix inside the namespace block, while namespace movement changes the later
function-card suffix. Ordering the shared definitions by fully qualified key,
not discovery order, keeps unrelated source and required-namespace changes
from gratuitously shifting the region.

## P0 task strings and BFCL comparison

No model was invoked. Exact P0 task strings are available, but there is no
current successful P0 full-prompt artifact to compare with the live preview.
The shared task source had uncommitted changes, and normal source admission
correctly refused to create a misleading frozen run. Therefore the following
P0 values are task-text measurements, not complete agent contexts.

| Task string | Bytes | Tokens | SHA-256 |
|---|---:|---:|---|
| Database memory contract | 1,322 | 328 | `26f50ea7e1592d1c95af5d39cd81426d01b71e17e0ef2ee66a039538ca625036` |
| Namespace movement contract | 1,711 | 423 | `28e7e650709260ed8c91e45d23aa414d41790d0c8efb9a7bcb39956b0bfbed35` |

Frozen BFCL adapter prompts were also constructed directly and read-only from
the upstream Inspect task for four development members. These are exact task
prompts, not full Seon contexts.

| BFCL member | Category | Tools | Bytes | Tokens | SHA-256 |
|---|---|---:|---:|---:|---|
| `multiple_66` | multiple | 3 | 2,278 | 569 | `6353479612404504e3302930d379c9dd098a9919dd03e54562d0a44a7e5a438b` |
| `parallel_3` | parallel | 1 | 1,617 | 403 | `b79676548adf14d482fd7bf4f33c91992bb02baf8e86a14cfb8b1408d1b52b22` |
| `parallel_multiple_29` | parallel multiple | 2 | 2,033 | 507 | `e42a6a6933a41c1c451663a6ec2ff147dd6d80fdfa66e5021573c82d3ced940f` |
| `simple_python_189` | simple Python | 1 | 1,630 | 407 | `734ef7b5803030e2b9534624b9bfbbbbdb1aff7a22fface6bfad8e5d8255f842` |

A successful historical BFCL `multiple_0` `.eval` does contain a complete
prompt. It is 96,425 bytes, 24,106 tokens, and has SHA-256
`2ba1f57f28f64869dac6b9673eb51d8826b095454e9c5dc9d91c7db2cd5e185a`.
Its namespace block was 88,425 bytes and 22,106 tokens with SHA-256
`4015092f566aaa0ffb6bf8bf83d4f209657d33e2f4b426f3d1a26a4148f18eed`.
That block had seventeen namespaces, 159 functions, 6,771 function tokens, and
14,699 schema-record tokens. Its all-shared candidate saved 1,237 tokens
(5.6%).

The current ordinary eligibility surface is 1,700 tokens smaller than that
historical namespace block and exposes 51 fewer functions, saving 2,130
function-contract tokens. Its schema-record cost is nevertheless 456 tokens
higher. This is direct evidence that positive function eligibility improved
the callable surface while schema presentation is now the dominant remaining
context cost.

## Global owner and acceptance evidence

Implementation should strengthen these owners in place:

- `seon.agent.ctx` derives structured, complete transitive schema closure from
  database facts. `referenced-schema-block` renders that data where a textual
  wrapper is still required.
- `seon.agent.ctx.namespaces/namespaces-block` assembles one structured export
  for selected namespaces, contracts, own schemas, and closure schemas. It
  renders the globally sorted shared-schema section first and the existing
  compact function cards second inside the same priority-20 block.
- `compact-fn-head` and its contract spelling stay unchanged. Menus,
  autocomplete, Inspect freezing, and debug measurement can later consume the
  same structured export rather than parsing rendered context.

This preserves schema colocation in code and in database facts. "Shared" is a
presentation section, not a new schema authority. A separate top-level context
block would weaken cache ordering and duplicate selection policy, so it is not
the owner.

A future implementation is accepted when a fixed immutable database value
shows:

- identical schema-key and schema-form sets before and after the refactor;
- identical ordered function-contract lines;
- zero unresolved contract references and an explicit indication if a closure
  limit ever fires;
- byte-identical repeated renders at the same database coordinate;
- a shared-schema prefix ordered only by fully qualified key; and
- focused context tests plus a live cluster-qualified preview, without running
  the broad test suite.

Current exact P0 full-context hashes remain unavailable until admitted P0 runs
exist. That is an evidence gap, not a reason to bypass source admission or
invent another simulation path.
