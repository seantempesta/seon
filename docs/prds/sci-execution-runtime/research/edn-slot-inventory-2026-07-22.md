---
type: research
status: active
tags: [research, architecture, database]
---

# EDN-slot inventory — the data-invariant violation census (2026-07-22 night)

Orchestrator-accepted. 21 active persisted attributes + 1 dormant satisfy
the encoder derivation (pr-str on every transaction, db/internal.cljs:238).
Each is classified tuple/cardinality-many/NORMALIZE/DAG/honestly-EDN with
an upgrade plan. Owner priority: NORMALIZE class dispatches FIRST (modeling
over new types), then Stage-1 tuple exposure, DAG class waits on Stage-2
probes. Live samples NOT taken (cluster was down + MCP cancellations —
q26); shapes cited from manifests/source. This is Stage 1's work-list.

## Audit boundary

No files were edited.

The source-derived inventory is complete: 21 active persisted attributes and one dormant render attribute satisfy the encoder’s exact derivation. The encoder runs on every transaction at [src/seon/db.cljs:830](/Users/sean/src/seon/src/seon/db.cljs:830), identifies mixed top-level unions at [src/seon/db/internal.cljs:229](/Users/sean/src/seon/src/seon/db/internal.cljs:229), and applies `pr-str` at [src/seon/db/internal.cljs:238](/Users/sean/src/seon/src/seon/db/internal.cljs:238).

Live samples are not available in this run:

- `bin/seon status` reports the default cluster entirely down.
- Both read-only MCP probes were canceled before execution.
- A direct Datahike connection was attempted under the read-only sandbox, but Konserve requires write-side filestore initialization; the sandbox correctly rejected it.

Therefore I do not claim any value as a live decoded sample. The table provides current manifest examples where available, clearly marked as non-live.

## Computed inventory

Abbreviations: **CM** cardinality-many, **N** normalize, **DAG** structured DAG, **EDN** honestly EDN.

| Attribute | Registered Malli form | Available shape/size evidence | Verdict | Upgrade plan |
|---|---|---|---|---|
| `:seon.render.canvas/content` | `[:or {:default :none} [:enum :none] :symbol :seon.render.canvas/hiccup]` [owner](/Users/sean/src/seon/src/seon/render/schema.cljs:19) | Not live-sampled. Manifest examples include qualified symbols; literal hiccup is arbitrarily nested. | **DAG** | Absence replaces `:none`; split handler identity into a normalized function connection, with literal hiccup in a Stage-2 structured attribute. New attrs + backfill/dual-read. |
| `:seon.render/html` | Alias of `:seon.render.canvas/content` [owner](/Users/sean/src/seon/src/seon/render/schema.cljs:30) | Hidden encoder resident; same shapes as canvas content. | **DAG** | Same split as canvas content. High migration surface; new attrs + dual-read. |
| `:seon.render/ai` | `[:or :string :symbol]` [owner](/Users/sean/src/seon/src/seon/render/schema.cljs:29) | Manifest contains both symbols and strings; root-role text is a multi-line string [example](/Users/sean/src/seon/config/system.edn:440). | **N** | Separate literal text from render-function identity/connection. Backfill decoded strings/symbols, dual-read, then cut writes. |
| `:seon.render/clip` | `[:or :int [:map …ai/html int…] [:enum :none]]` [owner](/Users/sean/src/seon/src/seon/render.cljs:84) | No core writer found; probably uninstalled/dataless. | **N** | Replace with ordinary `clip-ai`, `clip-html`, and absence. Fix before first durable use; reset boundary is sufficient if still uninstalled. |
| `:seon.eval/home-requires` | `[:or {:default …} [:vector :seon.agent.home/require-spec] :symbol]` [owner](/Users/sean/src/seon/src/seon/eval.cljs:1646) | Root manifest example is an ordered nested vector containing one require spec [example](/Users/sean/src/seon/config/system.edn:440); normal defaults exceed eight entries. | **DAG** | Stage-2 structured value, or normalize require declarations as indexed child entities if queryability becomes important. Preserve custom agents through backfill/dual-read. |
| `:seon.config/always` | `[:or [:set :symbol] :nil]` [owner](/Users/sean/src/seon/src/seon/config/resolve.cljc:556) | Manifest has eight symbols [example](/Users/sean/src/seon/config/system.edn:52). | **CM** | New cardinality-many symbol attr; absence replaces nil. Backfill decoded members. No Stage dependency. |
| `:seon.config/skills` | `[:or :seon.config/skills-spec :nil]` [owner](/Users/sean/src/seon/src/seon/config/resolve.cljc:559) | Manifest map contains one directory [example](/Users/sean/src/seon/config/system.edn:27); consumer uses only the first directory [schema](/Users/sean/src/seon/src/seon/config/resolve.cljc:15). | **N** | Replace with one ordinary `skills-dir` string, or an indexed child model if multiple ordered roots become real. Backfill/dual-read. |
| `:seon.config.repair/classes` | `[:or [:map-of :keyword :boolean] :nil]` [owner](/Users/sean/src/seon/src/seon/config/resolve.cljc:561) | Optional class→boolean map; no live sample. | **N** | One entity/attribute per repair class and enabled flag, or presence-only disabled-class set. Backfill/dual-read. |
| `:seon.config/context-profiles` | `[:or [:map-of :keyword [:vector :map]] :nil]` [owner](/Users/sean/src/seon/src/seon/config/resolve.cljc:581) | Manifest: one profile containing two block patches [example](/Users/sean/src/seon/config/system.edn:403). | **N** | Profile entities connected to ordered block-patch entities. This is entity-shaped data, not an opaque value. Backfill/dual-read. |
| `:seon.config/model-variants` | `[:or :seon.config/model-variants-spec :nil]`; referenced form is map variant→config-map [owner](/Users/sean/src/seon/src/seon/config/resolve.cljc:379) | Manifest: three variants containing 4–10 fields each [example](/Users/sean/src/seon/config/system.edn:294). | **N** | Variant identity entities with ordinary model-setting attrs. Backfill/dual-read. |
| `:seon.config/agent-context` | Mixed union of a map containing agent attrs and block maps, or nil [owner](/Users/sean/src/seon/src/seon/config/resolve.cljc:384) | Manifest contains a sizable ordered block vector [example](/Users/sean/src/seon/config/system.edn:343). | **N** | Store configuration through the existing block/entity attributes and connections instead of copying the whole map into the singleton. Backfill before removing the legacy source map. |
| `:seon.config/root-context` | Mixed union of sparse agent/context map or nil [owner](/Users/sean/src/seon/src/seon/config/resolve.cljc:400) | Manifest has home-requires plus several block patches [example](/Users/sean/src/seon/config/system.edn:440). | **N** | Same entity/connection model as agent context, scoped to root identity. Backfill/dual-read. |
| `:seon.ai/agent-model` | `[:or :inherit ::model]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:422) | Manifest examples `"kimi-k3"`, `"muse-spark-1.1"`, `"deepseek-v4-flash"` [example](/Users/sean/src/seon/config/system.edn:294). | **N** | Absence means inherit; new ordinary string override attr. Backfill non-`:inherit` values. |
| `:seon.ai/agent-temperature` | `[:or :inherit ::temperature]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:423) | Scalar double; no live sample. | **N** | Absence + ordinary double attr. |
| `:seon.ai/agent-max-tokens` | `[:or :inherit ::max-tokens]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:424) | Manifest examples `16384`, `8192` [example](/Users/sean/src/seon/config/system.edn:299). | **N** | Absence + ordinary long attr. |
| `:seon.ai/agent-thinking` | `[:or :inherit ::thinking]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:429) | Manifest examples `"minimal"`, `"false"` [example](/Users/sean/src/seon/config/system.edn:314). | **N** | Absence + ordinary string attr. |
| `:seon.ai/agent-timeout-ms` | `[:or :inherit ::timeout-ms]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:430) | Manifest examples `300000`, `120000` [example](/Users/sean/src/seon/config/system.edn:301). | **N** | Absence + ordinary long attr. |
| `:seon.ai/agent-base-url` | `[:or :inherit ::base-url]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:431) | Manifest examples are short endpoint strings [example](/Users/sean/src/seon/config/system.edn:305). | **N** | Absence + ordinary string attr. |
| `:seon.ai/agent-api-key-env` | `[:or :inherit ::api-key-env]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:432) | Manifest examples `"MOONSHOT_API_KEY"` and `"META_MODEL_API_KEY"`—names only [example](/Users/sean/src/seon/config/system.edn:306). | **N** | Absence + ordinary string attr. No secret migration is involved. |
| `:seon.ai/agent-extra-body-edn` | `[:or :inherit ::extra-body-edn]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:434) | Underlying value is an arbitrary provider request map encoded as an EDN string [contract](/Users/sean/src/seon/src/seon/ai.cljs:291). | **EDN** | Remove the `:inherit` encoder wrapper by using absence + ordinary string. Keep the vendor payload opaque and explicitly EDN. |
| `:seon.ai/agent-max-retries` | `[:or :inherit [:int {:min 0}]]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:436) | Manifest example `1` [example](/Users/sean/src/seon/config/system.edn:309). | **N** | Absence + ordinary long attr. |
| `:seon.ai/agent-attempt-timeout-ms` | `[:or :inherit ::timeout-ms]` [owner](/Users/sean/src/seon/src/seon/ai.cljs:437) | Manifest example `360000` [example](/Users/sean/src/seon/config/system.edn:304). | **N** | Absence + ordinary long attr. |

Not encoded despite mixed-looking schemas: provider, completion-limit-field, DiffusionGemma backend, and fallback variant collapse to native keyword because all their union arms derive the same value type [forms](/Users/sean/src/seon/src/seon/ai.cljs:421).

### Tuple result

There are **zero current EDN-slot residents whose best whole-attribute home is Stage‑1 tuple**.

Tuple remains useful prospectively, but the fork’s actual rules are narrower and stranger than the proposed classification:

- Tuple values are vectors, and declared native types include `ref`, scalar types, and tuple [schema](/Users/sean/src/seon/reference-code/datahike/src/datahike/schema.cljc:33).
- Only homogeneous tuples enforce the eight-element limit [transaction](/Users/sean/src/seon/reference-code/datahike/src/datahike/db/transaction.cljc:1006). Heterogeneous and composite tuples currently have no equivalent bound.
- Heterogeneous tuples enforce exact declared arity and per-position specs [transaction](/Users/sean/src/seon/reference-code/datahike/src/datahike/db/transaction.cljc:1021).
- A direct tuple element declared `:db.type/ref` validates as an ID but is not a traversable/cascading ref connection; pull returns it inside the vector. Real relationships should remain ordinary ref attributes.
- Existing string attrs cannot change to tuple in place because generic schema-facet changes are rejected [schema evolution](/Users/sean/src/seon/reference-code/datahike/src/datahike/schema.cljc:257).

Stage 1 should therefore impose Seon’s intended `≤8` rule on both tuple forms and should not expose `:seon.db/ref` tuple elements as if they were connections.

## Migration rule

Every installed EDN-slot attr is currently `:db.type/string`. Consequently:

- A resettable development database can change the attr in place only by resetting the database and recreating its schema.
- A retained database/history requires a **new attribute**, decoded backfill, new-first/legacy-fallback dual-read, write cutover, and continued legacy decoding while historical database values or branches remain.
- Config values cannot universally be reconstructed from `config/system.edn`: config-free reopen deliberately preserves database facts. Production-safe config upgrades therefore also need backfill/dual-read unless an explicit reset plus manifest reapply is accepted.

Stage dependencies:

- **CM/N:** none; ordinary schema/modeling work.
- **DAG:** Stage 2.
- **Tuple:** Stage 1, but no current inventory row blocks on it.
- **Honestly EDN:** no migration beyond removing surrounding sentinel unions.

## Undeclared EDN residents

These bypass `encode-edn-slot-values` and write `pr-str` or EDN-shaped strings directly:

1. **Program graph canonical forms:** `:seon.fn/arglists`, `:seon.fn/spec`, and `:seon.schema/form` are declared strings [registrations](/Users/sean/src/seon/src/seon/agent.cljs:157), written by the analyzer and JVM recorder. Best eventual home: DAG for structured forms; source text remains text.

2. **Eval projections:** `:seon.eval/result-edn` and `:seon.eval/error-data` [registrations](/Users/sean/src/seon/src/seon/eval.cljs:96). `result-edn` is a bounded display projection and is not reliably round-trippable; leave it explicitly textual. Structured error data belongs in DAG or normalized error entities.

3. **Persisted errors:** `:seon.error/args-edn` and `:seon.error/data-edn` [registrations](/Users/sean/src/seon/src/seon/error.cljc:66). Arguments are arbitrary structured values → DAG; stable error facts should be normalized where queryable.

4. **LLM telemetry:** turn `llm-usage`, `llm-meta`, and attempt `usage` are strings [turn registrations](/Users/sean/src/seon/src/seon/agent/turn.cljs:86), [attempt registration](/Users/sean/src/seon/src/seon/agent/turn.cljs:137). The finite numeric usage projection should be ordinary attrs. Truly provider-specific metadata can remain honestly EDN.

5. **Typeahead projections:** `buffer-spans`, `offers-edn`, and `holes-edn` [registrations](/Users/sean/src/seon/src/seon/ai/typeahead.cljs:84). These are bounded vectors of entity-shaped maps → normalized child entities if queried, otherwise DAG.

6. **Durable tempid receipts:** `:seon.db.protocol.tempid/key-edn` is an ordinary string [registration](/Users/sean/src/seon/src/seon/db/protocol.cljc:469), populated with `pr-str` [writer](/Users/sean/src/seon/src/seon/db/protocol.cljc:1674). This is the most hidden resident. Normalize string and integer tempid alternatives into separate ordinary attrs; no tuple is needed.

7. **LLM extra body:** `:seon.ai/extra-body-edn` is explicit provider payload EDN [registration](/Users/sean/src/seon/src/seon/ai.cljs:291). This is the strongest **HONESTLY-EDN** case.

8. **Semantic string uses:** namespace source is occasionally generated with `pr-str`, but it remains source text. `my.plan` also writes a `pr-str` status/error map into generic message content; it has no structured reader and should either become normal prose or normalized message/error facts.

False positives excluded include wire serialization, logs, DOM/render identities, test-event printing, in-memory graduation results, and recovery evidence stored in blobs.

## API-key verification

Source supports the claim that key values never become datoms:

- The manifest explicitly stores only the environment-variable name [config/system.edn:29](/Users/sean/src/seon/config/system.edn:29).
- The schema documents `api-key-env` as a name and says keys are read at call time, never transacted [src/seon/ai.cljs:281](/Users/sean/src/seon/src/seon/ai.cljs:281).
- Agent-context validation repeats that credentials never enter config/database [src/seon/config/resolve.cljc:306](/Users/sean/src/seon/src/seon/config/resolve.cljc:306).
- Attempt telemetry stores only `api-key-env` and a credential class [src/seon/agent/turn.cljs:154](/Users/sean/src/seon/src/seon/agent/turn.cljs:154).

## Ranked upgrade order

1. **Remove `:inherit` from the nine scalar AI override attrs.** Absence already means inheritance. This deletes nine encoder residents with ordinary scalar schemas and minimal consumer change.

2. **Upgrade `:seon.config/always` and `skills`.** Cardinality-many symbols and one ordinary directory string remove two opaque config values cheaply.

3. **Normalize finite telemetry and tempid receipts.** LLM usage and tempid keys are small, closed, query-worthy shapes currently hidden from Datahike.

4. **Normalize repair classes, model variants, context profiles, agent context, and root context.** These are the largest entity-shaped maps and duplicate facts already meaningful as attributes/connections.

5. **Split render function identity from literal render content.** Normalize `render/ai`; prepare canvas/html literal content for Stage 2.

6. **Stage‑2 DAG migration:** canvas/html hiccup, home-requires, structured program/error values, and any typeahead projections not worth normalizing.

7. **Leave genuinely opaque/textual values explicit:** provider extra-body EDN, provider-specific metadata, clipped eval display text, and namespace source.

Stage‑1 tuple exposure remains worthwhile infrastructure, but this computed inventory does not supply its migration work-list.