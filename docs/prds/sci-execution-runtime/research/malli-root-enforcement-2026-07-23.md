---
type: research
status: active
tags: [research, runtime, database]
---

# Malli root enforcement — required interfaces, schema-driven wire, coercion policy

Date: 2026-07-23. Read-only research over the vendored Malli source
(`reference-code/malli`, pin `80138076960e7820523b4cb932c5b5d1936d4e7f`,
2025-12-15) and Seon's live validation/coercion sites on
`codex/runtime-reliability-refactor`. Owner question: enforce REQUIRED Malli
schemas everywhere plus schema-driven coercion so (a) agents learn
register!/spec-first, and (b) values crossing the wire are constrained by
schema decision, not discovered by codec failure (this morning's drill leaked
`Not supported: class clojure.core$_STAR_` from a transit write). Bounded by
R15 (unserializable → flat steering error; tier-local values by result
symbol), R19 (only `:malli/schema` required), R27 (limits are config facts),
and the one-boundary-coercion smell rule.

## §0 Ranked recommendations

1. **Schema-driven total wire encode at the one codec choke point** (M).
   Kills the whole "codec failure discovers the bad value" class — the drill's
   `Not supported: class clojure.core$_STAR_` leak. Mechanism: every envelope
   that reaches `uds/encode` (src/seon/db/transport/uds.cljc:210-217, a bare
   `transit/write`) must already be the output of one compiled
   `m/encoder <envelope-schema> wire-transformer` (malli/core.cljc:2717-2732)
   whose polymorphic slots carry `{:encode/wire …}` schema properties
   (property-driven interceptors, malli/transform.cljc:403-417) projecting any
   non-`ordinary-wire-value?` leaf into its R15 result-symbol reference plus
   `pr-str` display. This replaces the probe-encode/`pr-str` fallback pair
   `transit-safe-value`/`wire-safe-value` (src/seon/host/eval.clj:58-92) —
   which is literally try-the-codec-and-catch — with representation decided by
   schema BEFORE the codec runs. `protocol/valid-response?`
   (src/seon/db/protocol.cljc:1628-1634) stays as the assertion; it stops being
   the discovery. What could go wrong: the encoder must be compiled once and
   cached (m/encoder is cached per schema, core.cljc:2717) or it costs on every
   frame; `:any` slots (`::result :any`, protocol.cljc:224) transform only via
   their entry property, so every polymorphic slot must carry the property —
   a completeness walker (rec 3) enforces that.
2. **Require `:malli/schema` at the defn admission choke point, JVM-first**
   (S/M). Kills silently-unspecced agent functions. Today a defn with no
   schema tees a `:seon.fn` row with `:seon.fn/spec` simply absent
   (src/seon/eval.cljs:2416-2418; src/seon/host/record.clj:140-159) and an
   unparseable schema stores `:seon.fn/schema-error` while the eval still
   returns ok — no steering, no forcing function. Change: the JVM host's
   per-form admission point `seon.host.preflight/preflight!`
   (src/seon/host/preflight.clj:215, invoked at src/seon/host/eval.clj:355-362
   before the form runs) returns a `:terminal` steering envelope when a
   `(defn …)` form's attr-map lacks a parseable `:malli/schema`. Steering text
   (the error IS the teaching surface): "`my.ns/f` needs a `:malli/schema`
   before it can be saved. Design the data first: `(schema/register!
   :my.domain/thing …)` for each map shape, then `{:malli/schema [:=> [:cat
   :my.domain/thing] :my.domain/result]}` on the defn. The form did not run."
   What could go wrong: it blocks exploratory scratch — so exempt the
   transient scratch namespaces exactly as the tee already does
   (`record/transient-ns-syms`, src/seon/eval.cljs:2336-2338): scratch runs
   free, durable admission requires the contract.
3. **A completeness walker as one more register!/contract gate** (S). Kills
   hollow contracts (`:any` args, `[:maybe X]` returns, open maps) that make
   recs 1-2 vacuous. Seon already has the gate family — register! rejects
   non-EDN forms (src/seon/schema.cljc:320-344), nilable value schemas and
   single-segment namespaces with copy-pasteable fixes
   (src/seon/schema/internal.cljc:114-164). Add
   `assert-complete-contract!` using `m/walk` (malli/core.cljc:2612-2625) over
   the compiled function schema: reject `:any`/`:some` in agent-authored
   contract positions, `[:maybe …]` outputs, and `:map` args without
   `{:closed true}` (`mu/closed-schema` exists to close recursively,
   malli/util.cljc:128-146; `mu/subschemas` for enumeration, util.cljc:168).
   Core-owned schemas keep the documented third-party `:any` exception
   (`:seon.schema/definition :any`, src/seon/schema.cljc:229-235) — the gate
   distinguishes admission source, not a name list. What could go wrong:
   closed maps break legitimate open envelopes; enforce closure only on
   agent-authored contracts, and let core schemas opt in (most protocol maps
   are already `{:closed true}`, e.g. `:seon.db/db` protocol.cljc:230-238).
4. **Codec round-trip generative regression — the totality property** (M).
   Kills silent representability drift without enumerating tests. For every
   registered protocol request/response schema family: `mg/generate`
   (malli/generator.cljc:505) → `uds/encode` → `uds/decode` → `=`, plus
   `ordinary-wire-value?` as an invariant on everything generated. This is
   the standing proof that the declared shapes and the codec agree — exactly
   the class the drill exposed. Requires bounding the `:any` slots with
   `:gen/schema`/`:gen/fmap`/`:gen/elements` properties (generator.cljc:
   459-476) so generation is meaningful. What could go wrong: generators over
   recursive/ref-heavy schemas explode — use `:gen/schema` bounds and
   `mg/sample` sizes; that work product is also rec 3's audit of which slots
   are really polymorphic.
5. **clj-kondo config emission from the function-schema corpus** (S, cheap).
   Improves understanding/reliability for zero runtime cost:
   `malli.clj-kondo/collect` + `linter-config` + `save!`
   (reference-code/malli/src/malli/clj_kondo.cljc:215-232) emits
   `:type-mismatch` linter config from `m/function-schemas` (populated by the
   existing `:malli/schema` metadata via instrumentation collection). Static
   arg-type lint on core source and, later, on agent-authored corpus dumps.
   What could go wrong: nothing runtime; the emitted config is derived output
   and must be regenerated, never hand-edited.
6. **Humanize + spell-checking in every steering explain path** (S).
   `malli.error/humanize` (error.cljc:374-390) and `with-spell-checking`
   (error.cljc:339-372, Levenshtein "likely misspelled key") turn a raw
   explain into the teaching surface the standing rule demands — a misspelled
   namespaced key in an agent's map arg gets "did you mean
   `:kb.workout/date`" instead of a wall of `:malli.core/extra-key`. Wire it
   into the instrument report path and the register!/admission gates. What
   could go wrong: humanized output must stay flat/bounded (R15's flat
   steering error) — cap and truncate through the existing steering-head
   mechanism (src/seon/host/eval.clj:54-56).
7. **Leave the writer's per-attribute prepare coercion as-is; declare it**
   (S). It is NOT bespoke drift: `coerce-value-for-attribute`
   (src/seon/db/writer.clj:225-256) restores exactly one lossy Transit fact
   (JS integers arriving for `:db.type/double`/`float` attrs) keyed off the
   installed Datahike schema, applied at exactly one declared boundary
   (prepare-transaction!, writer.clj:1526-1529). Converting it to
   `m/coerce` would add a second schema authority (Malli forms vs installed
   Datahike schema) for zero new coverage. Document it as the one wire-decode
   coercion site; extend it only if a second provable Transit lossiness class
   appears.

## §1 Malli capability inventory (only what we would use)

All paths under `reference-code/malli/src/malli/`, pin `8013807…` above.

- **Validation/explain**: `m/validator`/`m/validate` (core.cljc:2627-2641),
  `m/explainer`/`m/explain` (core.cljc:2643-2666). Validators and explainers
  are cached per compiled schema (`-cached`). Seon already compiles against
  explicit projection registries (src/seon/schema.cljc:798-818).
- **Closed maps**: `{:closed true}` map property; `mu/closed-schema`
  recursively closes, `mu/open-schema` reverses (util.cljc:128-166).
  `mu/subschemas`/`mu/in->paths` enumerate every subschema with paths
  (util.cljc:168-196) — the raw material for the completeness walker.
- **Walking**: `m/walk` postwalks schema+children with path
  (core.cljc:2612-2625); `m/deref-recursive` (core.cljc:2835) — Seon already
  uses both (schema.cljc:34-45, 804-818).
- **Decode/encode transformers** (transform.cljc): interceptors are
  `{:enter … :leave …}` maps composed per schema node
  (`-interceptor`, transform.cljc:16-52). Resolution order per node:
  schema property `{:decode/<name> …}` / `{:encode/<name> …}` → type-property
  → transformer's per-type table → transformer default
  (transform.cljc:403-417). Stock transformers: `json-transformer`
  (transform.cljc:420-444), `string-transformer` (446-450),
  `strip-extra-keys-transformer` (452-475, accepts by default only maps not
  explicitly `{:closed false}`), `key-transformer` (477-482),
  `default-value-transformer` (484-520), `collection-transformer` (522-529).
  `transformer` composes chains; `m/encode`/`m/decode`/`m/encoder`/`m/decoder`
  (core.cljc:2700-2732). **Key design fact: a custom named transformer (e.g.
  `:wire`) makes `{:encode/wire {:enter f}}` schema properties the per-slot
  hook — representation decided in the schema, not in the codec.**
- **Coercion**: `m/coerce`/`m/coercer` = decode → validate → respond/raise
  (core.cljc:2734-2758). Throws by default; `raise` callback turns it into
  errors-as-values.
- **Function schemas + instrumentation**: `m/=>` macro and
  `-register-function-schema!` populate the `-function-schemas*` atom
  (core.cljc:3052-3108); `m/-instrument` with `:scope #{:input :output
  :guard}` and `:report` callback (core.cljc:3110-3130). Seon already owns a
  full custom `-instrument-f` overlay with fault classification
  (src/seon/instrument.cljc:295-443) — nothing new needed here.
- **Generative testing** (generator.cljc): `mg/generator`/`generate`/`sample`
  (496-524); property hooks `:gen/elements` (459), `:gen/gen` (468),
  `:gen/schema` (473), `:gen/fmap` (476), `:min`/`:max` respected via
  `-min-max` (77). `mg/function-checker`/`mg/check` (526-562) exercise
  function schemas against implementations — schema-driven edge-case
  discovery with zero enumerated tests.
- **clj-kondo emission** (clj_kondo.cljc): `from`/`collect`/`linter-config`/
  `save!`/`emit!` (198-232), cljs variant `collect-cljs`/`get-kondo-config`
  (234-242). Type mapping is lossy but sound (`:maybe` → `:nilable/*`, 99-103).
- **Pretty explainers** (dev/pretty.cljc): `reporter` (164), `thrower` (173),
  `explain` (186) — human-terminal output for development, feeding rec 6's
  content decisions; not for the agent surface directly (virhe box-drawing is
  noise in agent context).
- **Describe** (experimental/describe.cljc:255): English rendering of a
  schema — candidate for agent-facing schema rendering in context blocks;
  experimental namespace, so vendor-pin dependency risk is ours.
- **Registry mechanics** (registry.cljc): `fast-registry` (17, HashMap on
  JVM), `composite-registry` (54), `mutable-registry`/`var-registry`/
  `dynamic-registry` (61-79), `set-default-registry!` (42, refused in strict
  mode). Seon's one stable facade already reifies `mr/Registry` and installs
  itself once (src/seon/schema.cljc:172-194); everything else passes explicit
  `{:registry …}`.

## §2 Current-state map of Seon validation/coercion sites

- **Registration admission** — `schema/register!` (src/seon/schema.cljc:
  288-346): EDN round-trip gate, nilable-value gate, multi-segment-namespace
  gate (CLJS only, schema.cljc:317-319 — a tier asymmetry), compilability
  gate at projection build (internal.cljc:82-112). Errors are thrown ex-info
  with steering text naming the fix — the established pattern rec 3 extends.
- **Projection** — `build-projection` compiles the complete population
  order-independently, derives dependency graphs, shape index, entity
  catalog (schema.cljc:359-489). Eval-time registrations are isolated deltas
  committed only on eval success (schema.cljc:622-696;
  src/seon/host/eval.clj:376-416).
- **Function-schema enforcement today** — `:malli/schema` metadata is parsed
  at tee time on both tiers; parse failure → `:seon.fn/schema-error` datom,
  absence → row without `:seon.fn/spec`, in both cases the eval SUCCEEDS
  (src/seon/eval.cljs:2369-2419; src/seon/host/record.clj:128-159). No
  admission force exists; R19 says `:malli/schema` is the one required
  metadata but nothing requires it yet.
- **Instrumentation** — one owner, database-graph-driven, explicit `:data`,
  custom `-instrument-f` with `:report`-style fault classification and async
  Promise coverage (src/seon/instrument.cljc:295-443); kill-switch env var
  (instrument.cljc:28-30). JVM host has its own registry/nursery install
  path (src/seon/host/instrument.clj, src/seon/host/graduate.clj).
- **Wire predicate** — `protocol/ordinary-wire-value?`
  (src/seon/db/protocol.cljc:124-180): a recursive type walk, used by
  request/response validators (1607-1650) and by execution IPC
  (src/seon/execution.cljs:218-334, with `first-non-ordinary` path
  diagnostics and `bounded-result` already producing R15-shaped errors).
- **Codec** — `uds/encode` is a bare `transit/write`
  (src/seon/db/transport/uds.cljc:210-217); `write-frame!`/`message-frame`
  (227-264) enforce only the frame-size limit. Which server response paths
  run `valid-response?` before encode is the program-synthesis queue row 7
  UNCLEAR (docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:
  162-164).
- **Codec-failure discovery** — `transit-safe-value`/`wire-safe-value`
  (src/seon/host/eval.clj:58-92): probe-encode each envelope value, on throw
  recursively probe and `pr-str` leaves. This is the inverted mechanism the
  owner wants killed: the codec is the oracle, per value, per eval, and any
  response path that doesn't route through it (the drill's leak) fails raw.
- **Writer prepare coercion** — installed-schema-keyed int→double restore,
  one call site (src/seon/db/writer.clj:225-256, 1526-1529). Not
  Malli-driven; driven by the installed Datahike schema, which is itself
  derived from Malli forms by the bridge.
- **Malli→Datahike bridge** — src/seon/db/datahike/schema.clj: leaf map
  (32-58), entry derivation with loud errors for unsupported shapes
  (125-277), recursive alias dereference with cycle detection (283-337).
- **Registry hygiene** — the one facade + `relink-registry!` for bundle
  loads that reset Malli's default (schema.cljc:167-194;
  src/seon/eval.cljs:795). Every serious compile passes an explicit
  registry (schema.cljc, execution.cljs:208-214, writer bridge:321-324).

## §3 Schema-driven wire representation (design B, spec-ready)

Goal: the codec is total BY SCHEMA — every value that reaches
`transit/write` is inside a declared wire shape; everything outside the
shape was already projected to its R15 representation by an encoder the
schema itself selected.

Where the schema lives at encode time:

- Protocol requests/responses: already registered closed-ish shapes in
  `seon.db.protocol` (request/response validators, protocol.cljc:1607-1650).
- Eval envelopes: the envelope shape (`:seon.eval/ok?`, `:seon.eval/value`,
  `:seon/error`, output, repair keys) is currently implicit in
  `host/eval.clj`; it must be REGISTERED (one `:seon.eval/envelope` schema)
  — that registration is the first lane task. The VALUE slot has no
  per-eval schema (a bare form has no contract; a specced fn's output
  schema exists in the projection's function contracts,
  schema.cljc:483, but the driver does not know which fn produced the
  value) — so the value slot's schema is the union
  `[:or ::wire-value ::result-reference]`, not the fn's output schema.

The transformer (one composition, compiled once per schema):

```clojure
(def wire-transformer
  (mt/transformer
    {:name :wire}))                    ; property-driven only

;; envelope registration carries the per-slot hook:
[:map {:closed true}
 [:seon.eval/ok? :boolean]
 [:seon.eval/value {:optional true
                    :encode/wire {:enter project-wire-leaf}}
  [:or ::wire-value ::result-reference]]
 …]

```

`project-wire-leaf` walks the value once: any leaf failing
`ordinary-wire-value?` becomes
`{:seon.eval/result-ref <eval-id> :seon.eval/value-display (pr-str v)}` —
the tier-local live value is ALREADY retained addressable by eval id
(`sample/retain-live-value!`, src/seon/host/eval.clj:474-476,
src/seon/host/sample.clj:177-180), so the reference is real, not cosmetic.
Because the resolution order is property → type-table → default
(transform.cljc:403-417), slots WITHOUT the property are untouched — encode
cannot silently mangle declared-ordinary slots.

Choke point: `uds/encode` gains a precondition, not a fallback — encode
callers pass `{::schema-key k ::message m}` or the session pre-encodes with
the compiled encoder for the response's operation; either way a message
failing `valid-response?` AFTER encoding-projection is a `:core-bug` error
value (never a throw into the transport loop), and the bare
`transit/write` never sees an unprojected envelope. The probe-encode pair
`transit-safe-value`/`wire-safe-value` (host/eval.clj:58-92) is DELETED in
the same refactor (one mechanism).

Falsifier for the lane: rec 4's round-trip property PLUS a regression that
evals `*` (the drill form) and `(atom 1)` on the JVM host and asserts the
envelope crosses the wire as a result-reference with no throw anywhere in
the transport (grep the writer log for "Not supported").

Sizing: M — registration of the envelope schema, the `:wire` transformer,
encode-callsite audit (queue row 7's UNCLEAR is the same audit), deletion of
the probe pair, regressions.

## §4 Coercion policy table

One sentence per boundary; everything not listed is banned (silent
mid-logic conversion is a bug per the standing smell rule).

| Boundary | Policy |
|---|---|
| Writer transact prepare (writer.clj:1526) | KEEP: the one wire-decode coercion, installed-schema-keyed, restoring exactly the proven Transit int→double loss (writer.clj:225-256); extend only for a new proven lossiness class. |
| Wire encode (uds/encode via §3) | Schema-selected PROJECTION (encode transformers), never coercion: values are represented, not converted, and only at slots whose schema declares the hook. |
| Config manifest resolution (`seon.config` apply) | Legitimate decode point: `m/coerce` with `string-transformer`-style decoding is allowed exactly once, manifest→facts, because aero/env input is genuinely stringly (R27 facts land typed). |
| Agent input at `my.*` toolkit entries | NO silent massaging: validate + humanized steering error (rec 6) — the agent writes EDN already; "fixing" its input hides the teaching moment and violates errors-drive-correct-usage. |
| Datahike bridge (db/datahike/schema.clj) | Derivation, not coercion: rejects unsupported shapes loudly (125-277); keep the defensive `:maybe` unwrap (147-152) documented as defence-in-depth only. |
| Anywhere else (`m/decode` in domain logic) | BANNED — register the right shape instead; a needed conversion means the producer's schema is wrong. |

## §5 Generative testing plan (design D)

Today's discovery loop is live drills finding one representability bug at a
time. Schema-driven generation inverts that:

1. **Codec round-trip (the totality regression, first):** for each protocol
   request/response schema and the new `:seon.eval/envelope`:
   `(= v (uds/decode (uds/encode (m/encode s v wire-transformer))))` over
   `mg/sample` (generator.cljc:512), plus `ordinary-wire-value?` on every
   post-encode value. Runs in `bin/test-writer`.
2. **Transaction builders:** generate entity maps from catalog schemas
   (`:seon.schema.projection/catalog`, schema.cljc:456-474), pass through the
   bridge + writer prepare, assert install/transact acceptance — turns the
   bridge's throw sites (datahike/schema.clj:178-277) into enumerated
   evidence instead of field surprises.
3. **Function contracts:** `mg/check` (generator.cljc:558) over projection
   function contracts for pure `.cljc` cores — schema-driven fuzz of the
   exact fns instrumentation guards, catching wrong-schema-vs-implementation
   drift (the `:seon.fn/schema-error` class) before a drill does.
4. **Required refinements** (the price of admission): bound every `:any`
   protocol slot with `:gen/schema` (generator.cljc:473) or replace it per
   rec 3's audit; add `:min`/`:max` to unbounded strings/vectors; `:gen/fmap`
   (476) for identity-shaped strings. Each refinement is itself schema
   documentation — the generator forces the honesty `:any` hides.

Not a fourth testing surface: these are properties inside the existing
`bin/test-writer`/`bin/test-cljs` gates.

## §6 Anti-recommendations

- **Mutable global default registry / `mr/set-default-registry!` anywhere
  new** — Seon's one facade (schema.cljc:167-194) plus explicit
  `{:registry …}` everywhere is strictly better; `mutable-registry`/
  `dynamic-registry` (registry.cljc:61-79) reintroduce load-order truth.
- **Decode-everywhere / `json-transformer` on internal paths** — internal
  data is EDN-native; per-type auto-conversion (transform.cljc:254-290)
  would legalize the scattered-coercion smell the policy table bans.
- **`default-value-transformer`** (transform.cljc:484) for domain data —
  injecting defaults at read is stored-derived-state by another name and
  destroys absent-means-absent; defaults belong in R27 config facts.
- **`strip-extra-keys-transformer` on inbound agent data** — silently
  dropping a misspelled key is the opposite of steering; closed-map
  explain plus spell-check (rec 6) teaches, stripping hides. Stripping is
  acceptable
  only inside the §3 encode projection where the schema owns the wire shape.
- **`malli.dev/start!` / dev instrumentation** (dev.clj:39) — a second
  instrumentation mechanism; Seon's database-graph instrumentation owner
  already covers collection, reapplication on reload, and fault
  classification (src/seon/instrument.cljc).
- **`malli.experimental/defn` and `lite`** — a second function-definition
  and schema-authoring syntax; `:malli/schema` metadata is the one
  mechanism (R19).
- **`:multi` for entity taxonomy** — dispatch-on-kind is the banned
  `:type` discriminator; entities remain attributes + connections.
- **`m/coerce`'s default throwing raise** at agent/runtime boundaries —
  always pass `respond`/`raise` (core.cljc:2740-2750) so failures stay
  `:seon/error` values.

## UNCLEARs (with exact probes)

1. **Exact drill leak path.** `wire-safe-value` already guards the eval
   envelope (host/eval.clj:75-92), so the raw transit error came from a
   response path bypassing it — consistent with queue row 7's UNCLEAR.
   Probe: enumerate `write-frame!`/`message-frame` callers in uds.cljc and
   the writer session loop, and for each, evidence whether
   `valid-response?` runs before encode; then reproduce with a JVM-host
   eval of `*` while watching the writer log.
2. **Encode-projection cost vs the deep `ordinary-wire-value?` walk.** Both
   walk the value; §3 replaces two walks (predicate + probe-encode) with
   one property-driven walk, but that is asserted, not measured. Probe: REPL
   bench a 1MB nested envelope through (a) current probe pair, (b) compiled
   `m/encoder` — after the drill window, on a scratch cluster.
3. **CLJS-tier parity of the admission gate.** R28 authorizes breaking the
   pod, and record.clj/eval.cljs currently duplicate the tee; whether rec 2
   lands only in the JVM host or also in the CLJS tee depends on how much of
   eval.cljs survives U6b-U8. Probe: confirm with the orchestrator which tee
   is on the graduation path before speccing the lane.
4. **Multi-segment-namespace gate asymmetry** — enforced CLJS-only "until
   the JVM's legacy `:form/*` registrations are renamed"
   (schema.cljc:317-319). Probe: `rg ':form/' src/` and count; if zero, the
   `#?(:clj nil)` branch is deletable and the gate becomes uniform.
