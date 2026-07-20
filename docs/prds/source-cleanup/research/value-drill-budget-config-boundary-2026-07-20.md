---
type: research
status: complete
tags: [research, config, rendering, web]
---

# Value-drill budget configuration boundary (2026-07-20)

## Decision

Stage 1.5 needs three independent positive-integer cluster-policy attributes:

- `:seon.config.render/value-max-path-segments` bounds the decoded path vector;
- `:seon.config.render/value-max-path-bytes` bounds the encoded `path` query
  value before EDN parsing; and
- `:seon.config.render/value-max-realized-items` bounds collection work across
  offset paging.

They belong to the existing `:seon.config/render` section and flat
`:seon.config` database singleton beside `value-max-items`. They are not new
environment readers, per-agent state, route-local constants, or a second
configuration object. Numeric defaults remain an owner decision: no maintained
source, test, architecture ruling, or dependency supplies honest values for
these three new limits.

The existing `value-max-depth` cannot bound repeated explicit drills, and
`value-max-items` remains page size rather than total offset work. With resolved
page size `n`, a request is admissible only when `offset + n <= total`; a
producer may then touch at most `offset + n + 1` collection items, where the
last item is the honest `:more` sentinel. Arithmetic must reject JavaScript
unsafe integers and overflow before lookup, descent, or realization.

The resolved singleton values are hard maxima for one database value. An
explicit operation option may narrow a limit for a test or caller, but may
never enlarge the singleton maximum. Parent and child derive the same effective
limits with one pure normalizer; neither accepts limits supplied by HTTP or by
an untrusted IPC message as authority.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding | Contract consumed |
|---|---|---|---|
| Seon configuration | Seon `8d0bfc763b807f3f60c3440d51c970b8d83e0ffc` | `src/seon/config.cljs:100-117,187-215,440-555,756-900,1084-1201` | Positive caps register once as leaf attributes, appear in the manifest section and singleton schema, resolve once into flat database facts, and are read by pure accessors from ordinary singleton data. |
| Shipped manifest | same Seon revision | `config/system.edn:117-149` | `:seon.config/render` is the one documented host-policy section. Defaults live in the manifest plus byte-parity accessor fallbacks; this audit deliberately adds no guessed numbers. |
| Aero | `1.1.6`; vendored `c47a10fa5f6a52084d04769af06d5e04d6603e13` | `reference-code/aero/src/aero/core.cljc:63-70,100-102,258-275,414-431` | `#long`, `#or`, and the one selected manifest reader resolve input before schema validation and database reconciliation. Runtime producers do not reread Aero or environment variables. |
| Malli | `0.20.0`; vendored `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli/src/malli/core.cljc:1223-1310,2635-2641` | Closed maps reject unknown keys and registered positive-int leaves validate manifest, singleton, request, and protocol shapes without hand-rolled parallel schemas. |
| Orchard paging | `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `reference-code/orchard/src/orchard/inspect.clj:44-141` | Orchard takes `page-size + 1` after dropping the page offset to distinguish an elided tail. Seon copies the head-plus-one truthfulness law, while adding a total-work admission bound Orchard does not provide. |
| Bounded Seon sampler | Unit 0 `d42a88de` | `src/seon/render/value.cljs:363-488,827-864`; `test/seon/render/value_test.cljs:75-320,493-505` | The existing guarded walker and `render-html-data` remain the sole projection mechanism. Drill paging strengthens this owner rather than introducing an inspector state machine. |
| Execution ordinary-data boundary | protocol v3 at the selected Seon revision | `src/seon/execution.cljs:21-156,176-208`; `test/seon/execution_test.cljs:72-96,844-862` | New sample request/result/error frames are closed, correlated, Transit ordinary data. The child repeats admission at the live-value authority boundary. |

## Configuration authority changes

`src/seon/config.cljs` owns the complete change in one mechanism:

1. Register each new leaf once against `:seon.config/cap`.
2. Reference all three leaves from the existing `:seon.config/render` closed
   contract and from `:seon.config/singleton`.
3. Flatten the resolved manifest values in `resolve-config-singleton`.
4. Add pure singleton accessors and one pure effective-limit normalizer that
   clamps optional operation limits to the resolved maxima.

`config/system.edn` owns the eventual documented numeric defaults. The owner
must rule those values before implementation; copying `value-max-depth`,
`value-max-items`, a database query ceiling, or the 4 MiB Transit frame ceiling
would conflate different units and work laws.

`test/seon/config_test.cljs` owns schema closure, absence/default parity,
manifest override, singleton flattening, and clamp tests. Existing
`render-caps-read-the-manifest`, `every-config-singleton-attribute-has-a-datahike-shape`,
and `resolve-config-singleton-defaults-and-overrides` are the first-party idiom
to extend. A manifest-selected value is host policy and may replace the shipped
default. A per-operation value is subordinate and may only decrease it.

No additional config file, ALS slot, atom, environment accessor, or route
configuration map is permitted. The operation acquires one immutable database
value, decodes its config singleton once through the existing database facade,
and passes the ordinary resolved limits to selection and projection.

## Request and work laws

The shared drill request is a closed namespaced map containing the original
path, canonical non-negative offset, page size, and effective hard maxima. The
HTTP boundary additionally measures the raw percent-encoded `path` parameter
in bytes before URL decoding or EDN reading. This prevents a small decoded path
from bypassing the encoded-request budget through excessive escaping.

Admission order is observable and fixed:

1. Reject duplicate/unknown query parameters and an encoded path over the byte
   maximum.
2. Parse one EDN vector with no trailing input; reject unsupported elements.
3. Reject a path whose element count exceeds the segment maximum.
4. Parse offset as canonical non-negative safe integer.
5. Compute `offset + page-size` with checked arithmetic and reject when it
   exceeds the total-realization maximum.
6. Only then authorize/select an eval or entity, descend the path, and realize
   `offset + page-size + 1` items at most.

The route performs all six checks before a database lookup or child send. The
execution child independently repeats the closed request, segment, arithmetic,
and total-work checks before `lookup-result`, `get-in`, `drop`, or sampling.
Defense in depth is mandatory because the child owns the live identity and IPC
is an authority boundary. Parent validation alone cannot make child work
bounded.

Map paging must not sort or traverse the full map before retaining a page. It
must extend Unit 0's deterministic bounded candidate-window mechanism, retain
original drill keys separately from bounded display projections, and report
omission honestly. Sequence/set paging does not make an ordinal element a
stable `get-in` branch. Those presentation/path rules remain owned by
`seon.render.value`, not configuration.

## Error-as-value behavior

Invalid selected manifests remain startup or explicit-config user-input
failures at the existing `seon.config/load-manifest-path` boundary. Runtime
drill failures do not throw through the agent loop or execution host:

- HTTP syntax or budget refusal returns a bounded `400` response derived from
  a closed `:seon.error` value with `:seon.error/kind :user-input`;
- a child receives only a validated closed request and still returns a distinct
  correlated sample-error frame carrying the same closed error value when its
  independent validation fails; and
- an over-budget request performs zero value lookup, path descent, collection
  realization, or child spawn.

The public result therefore stays a closed success/error union. A bare thrown
Malli exception, an uncorrelated execution error, or a string-only `400` would
create another error contract and is not acceptable.

## Exact implementation ownership

The dependency-ordered owners are:

- configuration: `src/seon/config.cljs`, `config/system.edn`, and
  `test/seon/config_test.cljs`;
- drill request/projection and bounded paging: `src/seon/render/value.cljs` and
  `test/seon/render/value_test.cljs`;
- child enforcement and correlated transport: `src/seon/execution.cljs`,
  `src/seon/execution/host.cljs`, `test/seon/execution_test.cljs`,
  `test/seon/execution/host_test.cljs`, and the minimum process/integration
  tests required by the existing host harness;
- route parsing and parent enforcement: `src/seon/route.cljs`,
  `src/seon/web/router.cljs`, the one value handler in `src/seon/web/serve.cljs`,
  and their existing route/router/serve tests.

The top-level integrator must coordinate execution/host ownership with the
active runtime-reliability lane before edits. Configuration and projection
freeze first; transport consumes their exact schemas; the route consumes the
frozen transport and does not invent numeric defaults or sampling limits.

## Shortest falsifiers

1. Config schema: each new leaf is a real Datahike attribute; unknown keys,
   zero, negative, and non-integer values fail; absent config resolves the
   owner-ruled defaults; selected-manifest values replace them; operation
   options below the maxima narrow them and values above are clamped or refused.
2. Parser zero-work: an oversized encoded path, too many decoded segments,
   unsafe/overflow offset, and `offset + n > total` each produce a bounded
   user-input error while database-lookup, child-send, `lookup-result`, and
   realization spies remain at zero.
3. Work counter: page offsets zero and nonzero over an infinite or
   counter-bearing sequence touch no more than `offset + n + 1`; the retained
   page has at most `n` items and `:more` is exact.
4. Independent child belt: bypass the route with a Transit-valid but
   over-budget sample frame; the child emits one correlated ordinary error and
   touches neither `lookup-result` nor the collection.
5. Parent/child parity: the same immutable config singleton and request resolve
   byte-identical effective limits on both sides; a caller cannot widen any
   maximum through query parameters or IPC fields.
6. Deterministic map page: two renders of the same large map, path, offset, and
   limits produce the same bytes, touch only the declared work window, retain
   original drill keys, and state omission honestly.
7. Integrated frame bound: the successful or error projection satisfies
   `ordinary-wire-value?` and stays below the existing execution frame ceiling
   without relying on post-hoc truncation.

These falsifiers close
[[../../../seon/issues/value-drill-has-no-total-work-bounds]] only when their
focused config, sampler, protocol, child, and route tests are committed with
behavioral proof. Documentation alone does not close the issue.
