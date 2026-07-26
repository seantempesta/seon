---
type: research
status: active
tags: [research, runtime, database]
---

# Schema strictness census — :any/:maybe usage + guarded polymorphism + coercion policy (2026-07-23)

## Read-only schema completeness audit

No files were changed; no builds, tests, or REPL calls were run.

## Bottom line

The current codebase does not enforce the standing no-`:any`/no-`[:maybe …]` rule. The existing registration gate is materially narrower:

- It rejects unreadable/non-round-tripping EDN and schemas that cannot compile.
- It rejects only a top-level named `[:maybe …]` value registration.
- It explicitly permits `[:maybe …]` inside function schemas.
- It does not reject nested `:any`, `:some`, `:nil`, `[:maybe …]`, open maps, or hollow function slots.
- The warning layer is advisory and catches only direct `:any` arguments/returns plus any `[:maybe …]`; it is not an admission gate.

Evidence: [schema/internal.cljc:82](/Users/sean/src/seon/src/seon/schema/internal.cljc:82), [schema/internal.cljc:114](/Users/sean/src/seon/src/seon/schema/internal.cljc:114), [schema.cljc:288](/Users/sean/src/seon/src/seon/schema.cljc:288), [schema.cljc:359](/Users/sean/src/seon/src/seon/schema.cljc:359), [warn.cljs:224](/Users/sean/src/seon/src/seon/warn.cljs:224).

The final migration is L, not S: executable occurrences span 101 source files.

## 1. Exact census

I counted both raw text and executable schema forms. The executable count strips comments and strings, so warning examples and explanatory prose do not inflate the policy numbers.

| Form | Raw text | Executable |
|---|---:|---:|
| `:any` | 511 | 480 |
| `:some` | 0 | 0 |
| `[:maybe …]` | 105 | 96 |
| Total executable | — | 576 |

Classification of the 576 executable tokens:

| Class | Count | Verdict |
|---|---:|---|
| A — documented third-party/opaque boundary | 124 | Legitimate under the current exception, though many can still gain predicates |
| B — genuinely polymorphic Seon/wire/value slot | 229 | Semantically legitimate polymorphism, but bare `:any` should become guarded polymorphism |
| C — hollow or nilable contract with a knowable better shape | 221 | Current violation: 125 `:any` + all 96 `[:maybe …]` |
| D — test-state-only | 2 | Exempt from production completeness, preferably moved behind a test namespace |
| Total | 576 | |

The two D occurrences are the exact schema-state capture/restore helpers at [schema.cljc:698](/Users/sean/src/seon/src/seon/schema.cljc:698).

### Class A: real opaque boundaries

The legitimate cases cluster around:

- Datahike database values, connections, transaction forms, and Proximum objects: [db/id.cljc:37](/Users/sean/src/seon/src/seon/db/id.cljc:37), [db/writer.clj:63](/Users/sean/src/seon/src/seon/db/writer.clj:63), [embed.clj:902](/Users/sean/src/seon/src/seon/embed.clj:902).
- Java/JS streams, byte arrays, compiler state, SCI values, and process handles: [uds.cljc:210](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:210), [analyzer_info.cljs:35](/Users/sean/src/seon/src/seon/analyzer_info.cljs:35), [host/session.clj:139](/Users/sean/src/seon/src/seon/host/session.clj:139).
- Provider SDK messages, completions, errors, streams, and abort signals: [ai.cljs:118](/Users/sean/src/seon/src/seon/ai.cljs:118), [anthropic.cljs:177](/Users/sean/src/seon/src/seon/ai/anthropic.cljs:177), [openai_compat.cljs:190](/Users/sean/src/seon/src/seon/ai/openai_compat.cljs:190).

These are the current standing exception. Even here, `:any` should remain only when there is genuinely no stable portable predicate. A Datahike database value already has `database-value?`; a wire value already has `ordinary-wire-value?`; a throwable can at least have a cross-platform throwable predicate.

### Class B: polymorphic but guardable

The major families are:

- Protocol results, query arguments, selectors, datom values, transaction data, and execution results: [protocol.cljc:221](/Users/sean/src/seon/src/seon/db/protocol.cljc:221), [execution.cljs:74](/Users/sean/src/seon/src/seon/execution.cljs:74).
- Arbitrary values being rendered, printed, logged, hashed, inspected, or validated: [render/value.cljc:75](/Users/sean/src/seon/src/seon/render/value.cljc:75), [log.cljs:54](/Users/sean/src/seon/src/seon/log.cljs:54), [ai/tokens.cljc:192](/Users/sean/src/seon/src/seon/ai/tokens.cljc:192).
- Malli forms and function results: [schema.cljc:225](/Users/sean/src/seon/src/seon/schema.cljc:225), [schema/form.cljc:8](/Users/sean/src/seon/src/seon/schema/form.cljc:8).
- Agent-eval values and result-retention slots: [eval.cljs:124](/Users/sean/src/seon/src/seon/eval.cljs:124), [host/eval.clj:45](/Users/sean/src/seon/src/seon/host/eval.clj:45).

These should not be replaced with fabricated closed shapes. They should be replaced with named predicate schemas such as `::ordinary-wire-value`, `::malli-form`, `::printable-value`, `::hiccup-value`, or `::execution-result`.

### Class C: complete violation inventory

All 96 executable `[:maybe …]` forms are C. The repository’s own warning says this is forbidden, including in function contracts: [warn.cljs:258](/Users/sean/src/seon/src/seon/warn.cljs:258).

The 125 hollow `:any` tokens are concentrated at these exact sites:

- `my.*`: [my/kb.cljc:125](/Users/sean/src/seon/src/my/kb.cljc:125) lines 125, 198, 207, 217, 228, 439, 452, 464; [my/plan.cljc:284](/Users/sean/src/seon/src/my/plan.cljc:284) lines 284, 1783; [my/plan/internal.cljc:1807](/Users/sean/src/seon/src/my/plan/internal.cljc:1807) lines 1807, 1819.
- Agent/runtime callbacks and known results: [agent/ctx.cljs:377](/Users/sean/src/seon/src/seon/agent/ctx.cljs:377) lines 377, 405, 438×2; [agent/ctx/canvas.cljs:337](/Users/sean/src/seon/src/seon/agent/ctx/canvas.cljs:337); [agent/ctx/driver.cljs:272](/Users/sean/src/seon/src/seon/agent/ctx/driver.cljs:272); [agent/ctx/menu.cljs:480](/Users/sean/src/seon/src/seon/agent/ctx/menu.cljs:480); [agent/ctx/namespaces.cljs:851](/Users/sean/src/seon/src/seon/agent/ctx/namespaces.cljs:851); [agent/ctx/render_fns.cljs:99](/Users/sean/src/seon/src/seon/agent/ctx/render_fns.cljs:99) lines 99, 118; [agent/ctx/subagents.cljs:244](/Users/sean/src/seon/src/seon/agent/ctx/subagents.cljs:244) lines 244, 341; [agent/ctx/transcript.cljs:341](/Users/sean/src/seon/src/seon/agent/ctx/transcript.cljs:341) lines 341, 1224, 1532; [agent/ctx/typeahead_steps.cljs:94](/Users/sean/src/seon/src/seon/agent/ctx/typeahead_steps.cljs:94) lines 94, 532; [agent/ctx/warnings.cljs:364](/Users/sean/src/seon/src/seon/agent/ctx/warnings.cljs:364) lines 364, 460, 513; [agent/fs.cljs:36](/Users/sean/src/seon/src/seon/agent/fs.cljs:36) lines 36, 164; [agent/loop.cljs:343](/Users/sean/src/seon/src/seon/agent/loop.cljs:343) lines 343, 527, 571, 697; [agent/turn.cljs:620](/Users/sean/src/seon/src/seon/agent/turn.cljs:620) both tokens.
- Provider adapters with known function/result contracts: [ai/typeahead.cljs:1241](/Users/sean/src/seon/src/seon/ai/typeahead.cljs:1241) lines 1241–1242; [ai/anthropic.cljs:363](/Users/sean/src/seon/src/seon/ai/anthropic.cljs:363) lines 363–364; [ai/openai_compat.cljs:314](/Users/sean/src/seon/src/seon/ai/openai_compat.cljs:314) return slot plus lines 524–525; [diffusion/gemma.cljs:655](/Users/sean/src/seon/src/seon/diffusion/gemma.cljs:655) lines 655–656; [diffusion/retrieval.cljs:638](/Users/sean/src/seon/src/seon/diffusion/retrieval.cljs:638).
- Config shapes that should describe require forms: [config/resolve.cljc:592](/Users/sean/src/seon/src/seon/config/resolve.cljc:592), line 607.
- Database results whose unions are already knowable: [db.cljc:545](/Users/sean/src/seon/src/seon/db.cljc:545) lines 545, 640, 643, 659, 717, 718; [db/protocol.cljc:233](/Users/sean/src/seon/src/seon/db/protocol.cljc:233) lines 233, 258, 261, 901; [db/datahike/schema.clj:316](/Users/sean/src/seon/src/seon/db/datahike/schema.clj:316) output slots at 316 and 368; [db/transport/uds.cljc:274](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:274) output slots at 274 and 277; [db/transport/uds.cljs:305](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:305) lines 305, 686, 724; [db/program.clj:11](/Users/sean/src/seon/src/seon/db/program.clj:11); [db/writer.clj:990](/Users/sean/src/seon/src/seon/db/writer.clj:990) lines 990, 993.
- Lifecycle/build functions with predictable return types: [client.cljs:548](/Users/sean/src/seon/src/seon/client.cljs:548) lines 548, 557, 564, 577, 630, 1035, 1743, 1789, 2187, 2997×2; [execution.cljs:607](/Users/sean/src/seon/src/seon/execution.cljs:607); [execution/host.cljs:1140](/Users/sean/src/seon/src/seon/execution/host.cljs:1140), line 1194; [execution/runtime.cljs:272](/Users/sean/src/seon/src/seon/execution/runtime.cljs:272); [host/guard.cljc:146](/Users/sean/src/seon/src/seon/host/guard.cljc:146).
- Eval rows/builders whose concrete map/vector/form contracts are knowable: [eval.cljs:1298](/Users/sean/src/seon/src/seon/eval.cljs:1298) output at 1306; lines 1389×2, 2636, 2659, 2707, 2708, 2930, 2975, 3173×3, 3303, 3435, 4724, and the four slots at 5127–5132.
- Render invocation/callback contracts: [render.cljc:309](/Users/sean/src/seon/src/seon/render.cljc:309) both tokens; lines 333×2, 357×2, 1071×2; [render/system.cljs:125](/Users/sean/src/seon/src/seon/render/system.cljs:125); [render/canvas.cljc:445](/Users/sean/src/seon/src/seon/render/canvas.cljc:445).
- Web/repl/embed known structures: [web/router.cljs:224](/Users/sean/src/seon/src/seon/web/router.cljs:224) lines 224, 390, 429; [web/reactive/call.cljs:128](/Users/sean/src/seon/src/seon/web/reactive/call.cljs:128); [web/reactive/transform.cljs:244](/Users/sean/src/seon/src/seon/web/reactive/transform.cljs:244) return slot; [repl/parse/repair.cljc:155](/Users/sean/src/seon/src/seon/repl/parse/repair.cljc:155); [embed.cljs:149](/Users/sean/src/seon/src/seon/embed.cljs:149), line 183.

All `[:maybe …]` violations occur in these files/lines:

- `my.*`: [my/data.cljs:101](/Users/sean/src/seon/src/my/data.cljs:101); [my/plan.cljc:1762](/Users/sean/src/seon/src/my/plan.cljc:1762), line 1804; [my/skills.cljc:141](/Users/sean/src/seon/src/my/skills.cljc:141).
- Agent: [agent/ctx.cljs:142](/Users/sean/src/seon/src/seon/agent/ctx.cljs:142) lines 142, 176, 177, 248, 1413, 1452, 1643, 2006; [agent/home.cljs:38](/Users/sean/src/seon/src/seon/agent/home.cljs:38) lines 38, 76–78, 138, 140; [agent/ctx/render_fns.cljs:119](/Users/sean/src/seon/src/seon/agent/ctx/render_fns.cljs:119); [agent/ctx/transcript.cljs:122](/Users/sean/src/seon/src/seon/agent/ctx/transcript.cljs:122) lines 122, 140, 1533; [agent/ctx/typeahead_steps.cljs:533](/Users/sean/src/seon/src/seon/agent/ctx/typeahead_steps.cljs:533); [agent/run.cljs:271](/Users/sean/src/seon/src/seon/agent/run.cljs:271); [agent/schedule.cljs:206](/Users/sean/src/seon/src/seon/agent/schedule.cljs:206).
- AI/config: [ai.cljs:165](/Users/sean/src/seon/src/seon/ai.cljs:165), line 180×2, 199; [ai/core.cljc:75](/Users/sean/src/seon/src/seon/ai/core.cljc:75) ×2, line 123; [config.cljs:170](/Users/sean/src/seon/src/seon/config.cljs:170) lines 170, 519, 545, 557, 574, 980, 995, 1152; [config/resolve.cljc:1811](/Users/sean/src/seon/src/seon/config/resolve.cljc:1811).
- Database/runtime: [db/datahike/schema.clj:65](/Users/sean/src/seon/src/seon/db/datahike/schema.clj:65); [db/protocol.cljc:1617](/Users/sean/src/seon/src/seon/db/protocol.cljc:1617), line 1644; [db/restore_admin.clj:44](/Users/sean/src/seon/src/seon/db/restore_admin.clj:44); [derive.cljs:177](/Users/sean/src/seon/src/seon/derive.cljs:177), line 420.
- Diffusion/errors/eval: [diffusion/gemma.cljs:355](/Users/sean/src/seon/src/seon/diffusion/gemma.cljs:355); [diffusion/grammar.cljc:52](/Users/sean/src/seon/src/seon/diffusion/grammar.cljc:52); [diffusion/retrieval.cljs:561](/Users/sean/src/seon/src/seon/diffusion/retrieval.cljs:561); [error.cljc:154](/Users/sean/src/seon/src/seon/error.cljc:154) lines 154, 155, 310; [eval.cljs:467](/Users/sean/src/seon/src/seon/eval.cljs:467) lines 467, 496, 1636, 3217×2, 3363, 3365, 3432–3434; [instrument.cljc:942](/Users/sean/src/seon/src/seon/instrument.cljc:942); [platform.cljs:35](/Users/sean/src/seon/src/seon/platform.cljs:35).
- Render/repl/schema/web: [render.cljc:357](/Users/sean/src/seon/src/seon/render.cljc:357) lines 357, 386, 927; [render/canvas.cljc:289](/Users/sean/src/seon/src/seon/render/canvas.cljc:289); all handler returns at [render/handlers/eval.cljs:79](/Users/sean/src/seon/src/seon/render/handlers/eval.cljs:79), [fn.cljc:54](/Users/sean/src/seon/src/seon/render/handlers/fn.cljc:54), [message.cljs:45](/Users/sean/src/seon/src/seon/render/handlers/message.cljs:45), [ns.cljc:44](/Users/sean/src/seon/src/seon/render/handlers/ns.cljc:44), [schema.cljc:43](/Users/sean/src/seon/src/seon/render/handlers/schema.cljc:43), [test.cljc:101](/Users/sean/src/seon/src/seon/render/handlers/test.cljc:101); [repl/parse.cljc:886](/Users/sean/src/seon/src/seon/repl/parse.cljc:886), line 915; [repl/parse/repair.cljc:76](/Users/sean/src/seon/src/seon/repl/parse/repair.cljc:76); [schema.cljc:355](/Users/sean/src/seon/src/seon/schema.cljc:355) lines 355, 592, 774, 959, 979; [schema/form.cljc:10](/Users/sean/src/seon/src/seon/schema/form.cljc:10), line 31; [web/brand.cljs:103](/Users/sean/src/seon/src/seon/web/brand.cljs:103); [web/datastar.cljs:55](/Users/sean/src/seon/src/seon/web/datastar.cljs:55), line 872.

## 2. Guarded polymorphism

Yes, Malli supports the intended pattern:

```clojure
[:fn {:error/message "must be an ordinary cross-platform wire value"
      :gen/schema ::ordinary-wire-sample}
 'seon.db.protocol/ordinary-wire-value?]
```

The relevant source behavior is:

- `:fn` accepts one predicate child, evaluates it, wraps it with `-safe-pred`, and uses it for validation/explanation: [malli/core.cljc:1761](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:1761).
- Predicate schemas and quoted predicate symbols are registry-supported: [malli/core.cljc:2913](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:2913).
- `:error/message` is read from schema properties by Malli’s error humanization path: [malli/error.cljc:174](/Users/sean/src/seon/reference-code/malli/src/malli/error.cljc:174).
- `:fn` has no useful default generator. `:gen/schema`, `:gen/elements`, `:gen/gen`, or `:gen/fmap` overrides generation before Malli reports `no-generator`: [malli/generator.cljc:454](/Users/sean/src/seon/reference-code/malli/src/malli/generator.cljc:454), [generator_test.cljc:203](/Users/sean/src/seon/reference-code/malli/test/malli/generator_test.cljc:203).

Recommended named pattern:

```clojure
(schema/register!
 ::ordinary-wire-value
 [:fn {:error/message "must contain only eager portable wire data"
       :gen/schema ::ordinary-wire-sample}
  'seon.db.protocol/ordinary-wire-value?])

(schema/register!
 ::result
 [:or ::ordinary-wire-value ::result-reference])
```

Two important constraints:

1. The generator override is an input source, not a proof. The property suite must still assert every generated value validates.
2. This is not drop-in safe on every current tier. Seon presently documents that canonical `[:fn]` forms require predicate evaluation and the CLJS pod lacks SCI; such forms can lose their persisted function spec: [render/canvas.cljc:113](/Users/sean/src/seon/src/seon/render/canvas.cljc:113), [client.cljs:1438](/Users/sean/src/seon/src/seon/client.cljs:1438). The JVM host now carries SCI, but the writer must never execute agent-authored predicates.

Therefore the safe design is:

- Core-authored predicates may be compiled in trusted executable projections.
- Agent contracts may reference a core-admitted predicate schema.
- Agent-authored predicates must not be evaluated in the writer. Supporting them requires separating pure structural admission/persistence from process side executable schema compilation.
- Admission is decided from source provenance and schema structure, never a symbol allowlist.

That makes the robust `[:fn]` escape an M mechanism, not the S change suggested in the earlier research.

## 3. Coercion/wrapper verdict

Wrappers everywhere are useful for validation, error shaping, and platform-leaf normalization. They should not silently massage agent-authored EDN.

| Surface | Verdict |
|---|---|
| Config manifest/env → typed config facts | Yes: one `m/coerce` at manifest resolution. String→int/boolean is legitimate because the source is genuinely stringly. Return errors as values. |
| HTTP query/form/browser payload → request map | Decode once at the HTTP boundary, then validate a closed registered request. Do not guess `"1"` vs `1`, or scalar vs vector, after entry. |
| Provider SDK/JS object → Seon data | Yes: one explicit provider adapter (`js->clj`, field selection, schema validation). This is translation from a third-party representation, not domain coercion. |
| Wire encode | No coercion. Use schema-selected projection into an ordinary wire representation/result reference. |
| Wire decode → writer transaction | Keep only the installed-schema-driven int→double restoration already justified by Transit lossiness. Do not introduce a second Malli authority here. |
| `seon.db` public API | Normalizing collection interface conventions such as variadic args→vector is acceptable in the one public wrapper. Do not coerce attribute values from strings based on their eventual Datahike schema. |
| `my.*` toolkit entry | Validate and return a humanized steering error. No automatic string→int or single→vector conversion: agents already write EDN, and hidden correction teaches the wrong contract. |
| Explicit agent-visible converter | Allowed when conversion is the named operation: e.g. `parse-int` or `one-or-many->vector`, with distinct input/output schemas and an error envelope. The call must be visible in agent code. |
| Rendering/logging | Display conversion (`str`, bounded `pr-str`) is fine because it creates presentation data. It must not mutate the underlying value. |
| Domain logic or mid-pipeline `m/decode` | Reject. Fix the producer contract or add one explicit boundary converter. |

This agrees with the prior §4 policy, except it makes the wrapper distinction explicit: validation wrappers are desirable; universal coercion wrappers are not.

## 4. Final completeness-walker policy

### Gate locations

Use one pure walker in three existing admission paths:

1. `register!` candidate admission.
2. Complete committed projection construction, so database reload cannot bypass it.
3. Durable `defn` admission before the form executes.

The walker should inspect both the original form and the compiled schema. Use `m/walk` for type-aware traversal and `mu/subschemas` for paths; recursively dereference named schemas with cycle detection so aliases cannot hide violations. Malli provides those mechanisms at [core.cljc:2612](/Users/sean/src/seon/reference-code/malli/src/malli/core.cljc:2612) and [util.cljc:168](/Users/sean/src/seon/reference-code/malli/src/malli/util.cljc:168).

### Agent-authored contracts

Reject:

- Bare `:any`, `:some`, and `:nil` in every registered shape and function slot.
- `[:maybe …]` anywhere, input or output.
- Open or implicitly-open `:map` schemas anywhere in a request/response or argument contract.
- A positional input that is not fully named with `:catn`, except the established one-named-request-map shape.
- A collection whose element schema is hollow.
- A polymorphic predicate schema without all of:
  - a qualified predicate symbol;
  - a nonblank `:error/message` or `:error/fn`;
  - `:gen/schema`, `:gen/elements`, or another bounded generator;
  - a boolean, total, side-effect-free validator contract.

Agent-authored `[:fn]` predicates may execute only in a guarded run-holding process, never the writer. Until structural/executable projections are split, agents may reference only core-admitted predicate schemas.

### Core contracts

Apply the same default. Permit two source-provenance exceptions:

- A core-admitted `[:fn]` guarded-polymorphic schema satisfying the requirements above.
- A genuinely opaque third-party slot where no stable cross-platform predicate exists. This may remain `[:any {...}]`, but must carry an explicit opaque-boundary reason and bounded generator. Agent admission cannot assert this exception.

The privilege comes from admission source—core artifact versus durable agent form—not a namespace or symbol allowlist.

Core maps should also be closed by default. An explicitly open map is allowed only for an external payload whose unrecognized keys are part of the stated boundary. `mu/closed-schema` can normalize implicit maps during migration, but admission should reject rather than silently rewrite authored contracts: [util.cljc:128](/Users/sean/src/seon/reference-code/malli/src/malli/util.cljc:128).

### `[:maybe]` has no escape

A missing map fact is `{:optional true}`. A function that can fail/not-find should return:

- a closed result/error envelope;
- an empty collection;
- or an explicit named sum such as `[:or ::found ::not-found]`.

Do not replace `[:maybe X]` mechanically with `[:or :nil X]`; that preserves the same hollow nil channel under different syntax.

## 5. Migration sizing

| Work | Size | Current population |
|---|---|---|
| Walker over existing forms, paths, recursive refs | S/M | One mechanism |
| Admission-source/provenance plumbing | M | Needed at register, projection reload, and defn admission |
| Safe `[:fn]` compilation split | M | Required to keep agent predicates out of the writer |
| Class A review | M | 124 tokens; many remain legitimate |
| Class B guarded-polymorphism migration | L | 229 tokens; should collapse into a smaller set of named predicate families |
| Class C concrete-contract migration | L | 221 tokens, including every one of the 96 `[:maybe …]` forms |
| Test-only cleanup | S | 2 tokens |
| Overall | L | 576 executable tokens across 101 files |

The practical order is:

1. Land the walker in report-only census mode.
2. Define the shared core predicate schemas and generators.
3. Fix class C by dependency order, starting with protocol/database envelopes and `my.*`.
4. Migrate class B to named predicates.
5. Turn agent admission strict.
6. Turn core admission strict after class A has explicit opaque-boundary evidence.
