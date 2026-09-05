---
type: research
status: complete
tags: [research, render, schema, context]
---

# Render function coverage audit — 2026-08-03

## Verdict

The registered schema population is broad but the important render coverage is
concentrated. At the audit snapshot, the current resource directory contains
112 flat files and 1,393 authored declarations; config derivation adds four
registered composite schemas, for 1,397 schemas in the effective population.
The input snapshot was commit `bd4494239b` plus the then-present shared-tree
schema edits. Counting the current directory, rather than silently omitting
another lane's coherent declarations, did not change the producer totals. The
loader's directory enumeration, strict namespace placement, merge, and config
derivation are the mechanisms being counted
(`src/seon/schema/edn.clj:155-240,291-325`).

Exactly 241 schemas declare both `:seon.render/ai` and
`:seon.render/html`; none declares only one. Of those, 229 carry
`:seon.error/class true` and are excluded from the gap count as requested.
Twelve non-error schemas declare producer pairs. The remaining 1,156 schemas
have no schema property producer pair, but that number is not the work queue:
most are leaf or request/response contracts that the walk never renders as an
independent value. `schema-producer` only attempts schema matching for maps,
and the final fallback is the appropriate value-printer floor
(`src/seon/render.clj:101-133`).

The confirmed high-priority gap is three entity schemas that occur in the
default agent's ordinary depth-2 walk and have neither an owning namespace nor
a schema producer: `:seon.cluster/cluster`, `:seon.config/entity`, and
`:seon.bootstrap.plan/plan`. Their live AI faces are raw database-shaped maps.
The same unit sequence drives the HTML namespace page
(`src/seon/render/web.clj:306-390`), while AI context assembles those units as
text (`src/seon/render/walk.clj:541-644`). The source declarations contain no
render properties (`resources/seon/schemas/seon.cluster.edn:2-10`,
`resources/seon/schemas/seon.bootstrap.plan.edn:5-13`); the config entity is
derived without render properties from every dial
(`src/seon/schema/edn.clj:87-111`).

The next gap is `:seon.effect/receipt`. It is an entity connected to both its
run and owner and has no producer declaration
(`resources/seon/schemas/seon.effect.edn:17-48`). When receipts exist, the
walk's bidirectional ref traversal makes them context/page neighbours; the walk
passes the pulled entity unchanged into render selection
(`src/seon/render/walk.clj:351-423`).

## Dependency ledger

- Malli 0.20.0 is selected at `deps.edn:13-16`; the vendored source revision
  read for this audit is `80138076960e`.
  Malli exposes a schema's original properties directly
  (`reference-code/malli/src/malli/core.cljc:34-43`), and Seon's existing
  authored-form inspector extracts the same property map without compiling the
  declaration (`src/seon/schema/form.cljc:16-21`).
- The live cardinality evidence uses the selected Datahike fork at
  `reference-code/datahike` revision
  `0e8601d7f2f6`, declared at
  `deps.edn:26-30`, through Seon's existing `seon.db/q` owner
  (`src/seon/db.clj:539-542`).
- The first-party producer seam is the existing selection chain in
  `src/seon/render.clj:95-133`; the recurring consumer seams are the AI walk in
  `src/seon/render/walk.clj:541-644` and the HTML namespace page in
  `src/seon/render/web.clj:306-390`. No alternate registry, renderer, or walk
  was introduced for the audit.

## Method and evidence

I read the governing error catalog end to end. Its §2.2 says every error class
uses the default error producer pair and explicitly states that the existing
error fact schema already carries both properties
(`docs/prds/sci-execution-runtime/research/error-catalog-2026-08-03.md:203-220`).
The current declaration verifies the claim at
`resources/seon/schemas/seon.error.edn:37-41`. The current population has 229
error-class schemas, all with both producers; they are marked `exempt` in the
inventory rather than ranked as gaps.

The census read every EDN map in `resources/seon/schemas/`, extracted Malli
properties with `seon.schema.form/attr-form-properties`, and separately built
the effective projection so the four derived config composites and entity
identity attributes were included. The live database probe was permitted by
the brief because `bin/seon status` reported the already-running `default`
cluster and its advertised port accepted a prepl connection. No cluster was
booted, reset, or transacted.

The live database contained one cluster, one config entity, one bootstrap plan,
one agent, 1,856 function rows, 712 schema rows, and 794 test rows. The entity
counts came from attribute-presence queries using each catalogued identity
attribute, the same entity discovery model derived by the schema projection
(`src/seon/schema.clj:1186-1212,1236-1255`).

Producer selection was judged against the actual ordered chain: explicit
producer, unique contract-fitting function in the explicit owning namespace,
matching-schema property, then the AI or HTML value floor
(`src/seon/render.clj:95-133`). Ownership is derived only from a namespace
entity or direct refs that resolve uniquely to a namespace; absent ownership
falls through to schema property and floor
(`src/seon/render/walk.clj:249-276`). The generic floor admits once and tees the
same bounded print node to text and Hiccup
(`src/seon/render/value.clj:168-223,399-423`).

The live root walk selected `seon.render.value/render-ai` for the cluster,
config, and bootstrap-plan entities. One unrelated live render invocation
returned a contract violation because this long-lived JVM's loaded render code
does not match the current source boundary; I did not use that result to rank
coverage. The entity counts and direct floor calls remained read-only and
usable. This audit therefore distinguishes current-file enumeration, current
source behavior, and observations from the already-running older JVM, as the
live-update contract requires.

## Honest floor faces

These are the actual bounded AI floor strings read during the audit. HTML is
the same admitted print tree with Hiccup decoration, not a separate semantic
projection (`src/seon/render/value.clj:205-223`).

### Cluster entity — confirmed ugly, P0

```clojure
{:db/id 11158,
 :seon.cluster/bootstrap-plan #:db{:id 957},
 :seon.cluster/config #:db{:id 11156},
 :seon.cluster/instructions [#:db{:id 971}],
 :seon.cluster/name "default",
 :seon.cluster/toolkit ...}
```

Verdict: the name is useful, but the rest is database implementation detail.
It gives the agent opaque entity IDs and a table of toolkit refs instead of a
bounded cluster summary. The cluster is a direct ref from the root agent and
therefore appears at distance 1 in the ordinary walk; non-namespace entities
keep the traversal distance (`src/seon/render/walk.clj:235-247,387-403`).

### Effective config entity — confirmed ugly, P0

```clojure
{:seon.print/level 8,
 :seon.config.ai/api-key-variable "DEEPSEEK_API_KEY",
 :seon.config.eval/time-limit-ms 30000,
 :seon.config.eval.result/max-collection 8192,
 :seon.config/cluster "default",
 :seon.print/length 32,
 ...}
```

Verdict: this is a long implementation dump rather than a decision-oriented
summary. It consumes context with every dial, wraps lines around qualified
keys, and provides no hierarchy or indication of which settings matter now.
The cluster schema requires its config ref
(`resources/seon/schemas/seon.cluster.edn:5-10`), so the default depth-2 walk
reaches this entity at distance 0.

### Bootstrap plan — confirmed ugly, P0

```clojure
{:db/id 957,
 :seon.bootstrap.plan/digest "f182...d2e4",
 :seon.bootstrap.plan/forms
 [{:db/id 958,
   :seon.bootstrap.plan.form/context
   "You are an agent in a Seon cluster. This is a real Clojure REPL ..." ...}]}
```

Verdict: the floor exposes nested component IDs and starts replaying the full
bootstrap instruction text. A compact producer should describe plan identity,
digest, and ordered form purpose without copying the payload already delivered
through instruction/context mechanisms. The plan owns component form refs
(`resources/seon/schemas/seon.bootstrap.plan.edn:1-13`), and each form is itself
marked as an entity without a producer
(`resources/seon/schemas/seon.bootstrap.plan.form.edn:1-13`).

### Effect receipt — confirmed ugly by representative floor call, P1

```clojure
{:seon.effect/request-edn "{:my.fs/path \"README.md\"}",
 :seon.effect/result-edn "{:my.fs/content \"...\"}",
 :seon.effect/id "effect-1",
 :seon.effect/owner #:db{:id 11980},
 :seon.effect/run #:db{:id 11990},
 :seon.effect/settled-at "2026-08-03T18:00:00.012Z",
 :seon.effect/form-ordinal 2,
 :db/id 12000,
 :seon.effect/ordinal 0,
 :seon.effect/duration-ms 12,
 :seon.effect/opened-at "2026-08-03T18:00:00Z"}
```

Verdict: bounded but not readable as an agent-facing receipt. It leads with
serialized request/result payloads and opaque entity IDs instead of capability,
state, duration, and a drill handle. The current live database had no receipt
row, so this face was produced by passing a representative value matching the
declared entity shape directly through the honest floor; no database write was
performed.

## Ranked worklist

1. **P0 — one cluster/config/bootstrap family pass.** Add declared producer
   pairs to `:seon.cluster/cluster`, `:seon.config/entity`, and
   `:seon.bootstrap.plan/plan`; also decide whether the nested
   `:seon.bootstrap.plan.form/form` needs its own pair or is fully absorbed by
   the plan face. Acceptance is a live depth-2 root walk with no raw entity IDs,
   no full config dump, and no copied bootstrap payload. Filed as
   [[cluster-config-and-bootstrap-plan-render-as-raw-maps]].
2. **P1 — effect receipt.** Declare a pair on `:seon.effect/receipt` that shows
   capability/disposition, duration, run/form identity in domain terms, and a
   bounded result/evidence drill without leading with serialized EDN. Filed as
   [[effect-receipts-have-no-render-producers]].
3. **P2 — directly inspectable program/evidence entities.** Evaluate compact
   pairs for `:seon.fn/fn`, `:seon.schema/schema`, `:seon.test/test`,
   `:seon.code.def/def`, and `:seon.context.contribution/contribution`.
   Ordinary namespace walks already absorb function/schema/test membership in
   the namespace renderer (`src/seon/render/walk.clj:241-247,288-294`), so this
   is a debug/direct-root readability tranche rather than prompt pollution.
4. **P3 — test result entities when populated.** The live cluster had zero
   `:seon.test.run/run`, `:seon.test.result/result`, and
   `:seon.test.failure/failure` rows. Re-evaluate after their recurring page is
   wired; absence of live rows is not evidence that raw floor output is good.
5. **No work for ordinary leaf/request/response schemas.** They are not
   independently selected by the map-only schema producer and remain honest
   structural values when explicitly drilled. A declaration is earned by
   repeated context/page readability, not by trying to drive the producer count
   to 100% (`src/seon/render.clj:101-105`).

## Per-schema inventory

Legend: `pair` names both declared producers; `error-exempt` means the schema
has `:seon.error/class true` and is deliberately excluded. `floor-map` is a
map shape with no declared pair; `embedded` is a non-map contract that is not
independently schema-selected. Priority reflects actual context/page exposure,
not the mere absence of a declaration.

| Schema key | Producers declared? | Floor-rendered example verdict | Priority |
|---|---|---|---|
| `:error/message` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:gen/schema` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.background/descriptor` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.background/invalid-call` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.background/invalid-call-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.background/invalid-result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.background/invalid-result-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.background/missing-result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.background/missing-result-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.background/result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit.form/dispatch-source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit.form/head` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit.form/name` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit.form/selector` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.edit/actual-window` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/after-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/after-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/ambiguous-match` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/ambiguous-match-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.edit/before-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/before-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/changed?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/context` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/context-complete?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/exact-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.edit/expected-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/form` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.edit/form-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.edit/from-line` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/lines-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.edit/lossless-check-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/lossless-check-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.edit/new-string` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/new-window` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/no-match` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/no-match-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.edit/not-utf8` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/not-utf8-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.edit/old-string` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/old-window` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/operation` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/parse-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/parse-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.edit/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/replace-all?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/replacements` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.edit/source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/stale-source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.edit/stale-source-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.edit/to-line` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/after-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/already-exists` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/already-exists-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/already-exists?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/atomic-write-unsupported` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/atomic-write-unsupported-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/before-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/blob-unavailable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/blob-unavailable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/byte` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/byte-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/byte-offset` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/byte-size` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/bytes-read` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/bytes-written` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/changed-during-read` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/changed-during-read-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/changed?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/complete?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/content` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/created?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/directory?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/encoding` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/eof?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/examined` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/expected-absence?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/expected-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/file-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/glob-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/glob-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/glob-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.fs/glob-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.fs/invalid-glob` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/invalid-glob-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/invalid-utf8-window` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/invalid-utf8-window-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/max-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/max-depth` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/max-results` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/modified-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/not-directory` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/not-directory-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/not-found` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/not-found-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/not-regular-file` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/not-regular-file-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/path-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/path-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/paths` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/pattern` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/precondition` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/read-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/read-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/read-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/read-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/read-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.fs/read-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.fs/regular-file?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/returned` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/root` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/stale-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/stale-digest-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/stat-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.fs/stat-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.fs/symbolic-link?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/write-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/write-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/write-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/write-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.fs/write-precondition` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.fs/write-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.fs/write-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.message/about` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.message/content` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.message/declination` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.message/message` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.message/no-about` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.message/no-about-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.message/no-content` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.message/no-content-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.message/no-reason` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.message/no-reason-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.message/no-recipient` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.message/no-recipient-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.message/reason` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.message/to` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.message/value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.run/blank-note` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.run/blank-note-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.run/blank-result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.run/blank-result-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.run/completed` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.run/disposition` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.run/note` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.run/result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.run/value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.run/wait` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.shell.output/blob` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell.output/bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell.output/digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell.output/octet-values` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell.output/preview` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell.output/preview-complete?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell.output/text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell.output/value` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.shell/argv` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell/cwd` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell/exit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell/run-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.shell/run-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.shell/stderr` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.shell/stdin` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell/stdin-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell/stdin-text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.shell/stdout` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web.body/blob` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.body/bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.body/digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.body/octet-values` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.body/value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.extract/text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.extract/title` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.extract/value` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web.redirect/from` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.redirect/status` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.redirect/to` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.redirect/value` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web.result/link` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.result/position` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.result/snippet` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.result/title` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web.result/value` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web/body` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/content-type` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/credits` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/error` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/extraction` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web/extraction-error` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/fetch-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web/fetch-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web/final-url` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/invalid-url` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/invalid-url-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/max-results` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/method` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/missing-location` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/missing-location-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/no-credential` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/no-credential-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/projection-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/projection-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/provider-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/provider-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/query` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/raw-response` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/raw-response-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/redirect-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/redirect-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/redirect-loop` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/redirect-loop-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/redirects` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/response-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/response-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/result-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/results` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/returned` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/search-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web/search-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:my.web/status` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/timeout` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/timeout-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/transport-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/transport-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/unparseable-response` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:my.web/unparseable-response-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:my.web/url` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/delay-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/error` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/failover-from` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/finish-reason` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/ordinal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/reasoning` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/reasoning-blob` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/reasoning-size` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/settings-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.attempt/usage-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.retry/base-delay-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.retry/delays` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.retry/jitter-fraction` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.retry/maximum-delay-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.retry/maximum-retries` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.retry/maximum-total-delay-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.retry/multiplier` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.retry/strategy` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.ai.usage/cached-tokens` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.usage/completion-tokens` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.usage/prompt-tokens` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai.usage/total-tokens` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/api-key-variable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/attempt` | `seon.ai/attempt-ai` / `seon.ai/attempt-html` | Declared specialized pair; no floor. | covered |
| `:seon.ai/authentication-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/authentication-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/authorization-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/authorization-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/backup` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/backup?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/completion` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/credential-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/credential-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/disposition` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/disposition-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.ai/endpoint` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/error-class` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/extra-body` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/extra-body-conflict` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/extra-body-conflict-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/extra-body-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/finish-reason` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/frequency-penalty` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/http-status` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/inert` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/invalid-extra-body` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/invalid-extra-body-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/json-value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/max-tokens` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/model` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/model-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/model-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/no-credential` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/no-credential-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/normalized-usage` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/output-observed?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/partial` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.ai/presence-penalty` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/primary` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/prompt` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/provider-error` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/provider-error-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/provider-server-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/provider-server-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/rate-limited` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/rate-limited-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/reasoning-content` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/reasoning-partial` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/request` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/request-body` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/request-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/request-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/request-transmitted?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/response-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/response-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/response-format` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/response-started?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/sent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/settings` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.ai/sink` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/stop` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/stream?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/system` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/target` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/targets` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.ai/temperature` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/thinking` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/timeout` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/timeout-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/timeout-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/token-starvation` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/token-starvation-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/tokens` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/top-p` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/transport-before-send-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/transport-before-send-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/transport-failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/transport-failure-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/transport-outcome-unknown` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/transport-outcome-unknown-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/unparseable-body` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/unparseable-body-error` | `seon.error/ai-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.ai/usage` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/wire` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ai/wire-settings` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.artifact/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.artifact/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.blob/content` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/content-digest-mismatch` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/content-digest-mismatch-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.blob/digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/inline-prefix` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/input-stalled` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/input-stalled-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.blob/input-stream` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/invalid-threshold` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/invalid-threshold-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.blob/length` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/octet-array` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/offset` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/size` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/store-root-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/store-root-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.blob/stored-content-mismatch` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.blob/stored-content-mismatch-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.blob/write-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.boot/advertisement` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.boot/cluster-name` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/config` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.boot/executors` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.boot/instance` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.boot/log-dir` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/overrides` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/pid` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/prepl-host` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/prepl-port` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/readiness` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.boot/recovered-runs` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/recovery-operations` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.boot/root` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/start-instant` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/start-request` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.boot/store-dir` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap.plan.form/context` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap.plan.form/form` | — | Nested in the confirmed raw plan dump; a plan producer may absorb it. | P0 family |
| `:seon.bootstrap.plan/digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap.plan/forms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap.plan/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap.plan/plan` | — | Confirmed raw digest plus nested instruction/form payload. | P0 |
| `:seon.bootstrap/agent-plan-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap/agent-plan-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.bootstrap/default-form` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.bootstrap/default-forms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap/invalid-ordinals` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap/invalid-ordinals-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.bootstrap/plan-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap/plan-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.bootstrap/population-conflict` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap/population-conflict-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.bootstrap/resource` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap/resource-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap/resource-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.bootstrap/resource-invalid` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.bootstrap/resource-invalid-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.agent/agent` | `seon.render.agent/agent-ai` / `seon.render.agent/agent-html` | Declared specialized pair; no floor. | covered |
| `:seon.cluster.agent/arm-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.agent/armed` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.agent/blueprint-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.agent/cluster` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/context-links` | — | No pair, but live agent rows also match the declared agent pair. | P3 |
| `:seon.cluster.agent/count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/creation-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.agent/creation-tx` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/disarm-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.agent/eid` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/instructions` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/namespace` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/no-such-agent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/no-such-agent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.agent/routing` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/turn-completion-backstop` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/turn-completion-backstop-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.agent/turn-completion-undeliverable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.agent/turn-completion-undeliverable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.eval/at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/error` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/interrupted-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/ns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/ordinal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/output` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/receipt` | `seon.cluster.run/render-receipt-ai` / `seon.cluster.run/render-receipt-html` | Declared specialized pair; no floor. | covered |
| `:seon.cluster.eval/result-blob` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/result-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/result-size` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.eval/run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.export/clone-unsupported` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.export/clone-unsupported-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.export/export-exists` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.export/export-exists-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.export/genesis-incomplete` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.export/genesis-incomplete-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.export/no-branch-head` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.export/no-branch-head-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.export/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.export/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.export/rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.instruction/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.instruction/instruction` | `seon.cluster.instruction/instruction-ai` / `seon.cluster.instruction/instruction-html` | Declared specialized pair; no floor. | covered |
| `:seon.cluster.instruction/seed-rows` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.instruction/text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.loop/cluster` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.loop/completion` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.loop/evaluation` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.loop/forms-run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.loop/lint-rejected` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.loop/lint-rejected-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.loop/outcome` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.loop/prompt-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.loop/prompt-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.loop/terminal-refusal-settlement-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.loop/terminal-refusal-settlement-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.loop/terminal-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.loop/turn-report` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.loop/turn-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.message/about` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/ambiguous-about` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/ambiguous-about-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.message/at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/blank-content` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/blank-content-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.message/caused-by` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/chain-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/chain-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.message/content` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/content-too-large` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/content-too-large-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.message/delivery` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.message/delivery-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.message/from` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/inbound` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/inbound-content` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/inbound-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.message/limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/message` | `seon.cluster.message/render-ai` / `seon.cluster.message/render-html` | Declared specialized pair; no floor. | covered |
| `:seon.cluster.message/no-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/no-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.message/reply-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.message/size` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/to` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/unknown-about` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/unknown-about-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.message/unknown-recipient` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.message/unknown-recipient-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.process/identity` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.process/pid` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.process/start-instant-unavailable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.process/start-instant-unavailable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.prompt/no-trigger` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.prompt/no-trigger-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.prompt/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.prompt/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.prompt/rendered-context` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.prompt/request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.prompt/rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.prompt/text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.registry/branch-commit-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.registry/branch-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.registry/branch-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.registry/cannot-retire-main` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.registry/cannot-retire-main-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.registry/cluster-connected` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.registry/cluster-connected-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.registry/cluster-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.registry/from` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.registry/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.registry/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.registry/retire-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.registry/roster` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.registry/rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.registry/source-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.registry/source-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.registry/swept` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.reply/form` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.reply/no-forms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.reply/no-forms-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.reply/refused-tag` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.reply/refused-tag-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.reply/sources` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.reply/text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.reply/unreadable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.reply/unreadable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.run.form/form` | `seon.cluster.run/render-form-ai` / `seon.cluster.run/render-form-html` | Declared specialized pair; no floor. | covered |
| `:seon.cluster.run.form/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run.form/ns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run.form/ordinal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run.form/run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run.form/source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/agent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/background-results` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/closed-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/error` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/forms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/live-processes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/missing-results` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/opened-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/plan-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/process` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/refused-error` | `seon.error/refusal-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.run/rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/run` | `seon.cluster.run/render-ai` / `seon.cluster.run/render-html` | Declared specialized pair; no floor. | covered |
| `:seon.cluster.run/transition` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.run/trigger` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/invalid-source-seal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/invalid-source-seal-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.source/populate-unresolvable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/populate-unresolvable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.source/publish-readback-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/publish-readback-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.source/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.source/root-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/root-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.source/rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/stale-publication` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/stale-publication-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.source/unsafe-incremental-rows` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.source/unsafe-incremental-rows-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.store/branch-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.store/branch-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.store/branch-already-open` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.store/branch-already-open-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.store/file-lock-generator-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.store/file-lock-generator-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.store/held-elsewhere` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.store/held-elsewhere-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.store/initialization-incomplete` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.store/initialization-incomplete-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.store/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.store/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.store/rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.wake/attributes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.wake/channels` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.wake/delivery` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.wake/fenced?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.wake/key` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.wake/offer-result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.wake/route-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.wake/undeliverable-wake` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.wake/undeliverable-wake-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.cluster.wake/unlisten-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.work/agent-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.work/episode-runs` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.work/form-settlement` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.work/form-state` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.work/forms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.work/next` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.work/now` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.work/plan-settlement` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.cluster.work/settled?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster.work/situation` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster/bootstrap-plan` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster/cluster` | — | Confirmed raw cluster refs/map in every root walk. | P0 |
| `:seon.cluster/config` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster/created?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster/instructions` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster/name` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster/toolkit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.cluster/toolkit-namespaces` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/blob` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/def` | — | Raw entity map on direct/debug reach; ordinary namespace walk may absorb it. | P2 |
| `:seon.code.def/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/name` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/ns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/ordinal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/size` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/unrestorable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.code.def/value-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.backup/api-key-variable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.backup/endpoint` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.backup/model` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.backup/timeout-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.retry/base-delay-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.retry/jitter-fraction` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.retry/maximum-delay-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.retry/maximum-retries` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.retry/maximum-total-delay-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai.retry/multiplier` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/api-key-variable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/endpoint` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/extra-body-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/frequency-penalty` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/max-tokens` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/model` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/no-auth` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/presence-penalty` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/response-format` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/stop` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/temperature` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/thinking` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/timeout-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.ai/top-p` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.db/keep-history?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.effect/long-call-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.error/escalate-to` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.error/recurrence-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.eval.result/blob-threshold` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.eval.result/max-collection` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.eval.result/max-depth` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.eval.result/max-nodes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.eval.result/max-string` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.eval/time-limit-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.flow.compute/concurrency` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.flow.compute/queue-depth` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.flow.io/concurrency` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.flow.io/queue-depth` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.flow/ping-timeout-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.fs/max-depth` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.fs/max-glob-results` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.fs/max-inline-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.fs/max-read-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.fs/max-traversal-entries` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.fs/max-write-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.fs/roots` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.fs/working-root` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.message/max-chain` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.render/coalesce-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.run/max-episode-runs` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.shell/home` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.shell/inline-output-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.shell/lang` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.shell/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.shell/preview-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.shell/stdin-max-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.shell/termination-grace-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.shell/time-limit-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/max-inline-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/max-redirects` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/max-response-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/max-search-results` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/port` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/search-api-key-variable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/search-endpoint` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/search-result-projection` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config.web/timeout-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/agent-overlay` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/applied-manifest-digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/apply-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.config/cluster` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/compile-request` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/compiled` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.config/dial` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/effective` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.config/entity` | — | Confirmed long raw effective-config dump in every root walk. | P0 |
| `:seon.config/key` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/manifest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/manifest-unreadable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/manifest-unreadable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.config/on-core-error` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/optional` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/per-agent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/reconcile-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/reconcile-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.config/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.config/required-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/required-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.config/rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/unknown-key` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.config/unknown-key-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.context.capture/basis-t` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.capture/capture` | `seon.context/capture-ai` / `seon.context/capture-html` | Declared specialized pair; no floor. | covered |
| `:seon.context.capture/contributions` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.capture/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.capture/prompt` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.capture/run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.contribution/contribution` | — | Raw entity map on direct/debug reach; ordinary namespace walk may absorb it. | P2 |
| `:seon.context.contribution/error` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.contribution/hash` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.contribution/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.contribution/position` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context.contribution/tokens` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.context/capture-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.context/contribution` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.context/contributions` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db.id/generator` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db.process/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/attributes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/component` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/connection` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/database-value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/datom` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.db/datoms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/entity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/entity-id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/identity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/index` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/index-lookup` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.db/no-history?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/process` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/pull-many-options` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.db/pull-options` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.db/pull-selector` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/query` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/query-args` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.db/time-point` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/transaction-outcome-unknown` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/transaction-outcome-unknown-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.db/transaction-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/transaction-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.db/tx-data` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/unique` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.db/user` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.dev.mcp/cluster-degraded` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.dev.mcp/cluster-degraded-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.dev.mcp/remainder-not-retrievable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.dev.mcp/remainder-not-retrievable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.dev.mcp/value-not-found` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.dev.mcp/value-not-found-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.edit/candidate` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.edit/candidate-evidence` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.edit/candidates` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.edit/candidates-complete?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.edit/cause` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.edit/form-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.edit/line-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.edit/lines` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.edit/lines-complete?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.edit/request` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/background?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/capability` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/disposition` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/duration-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/execution-options` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/form-ordinal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/interrupted-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/notify` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/open-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.effect/opened-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/ordinal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/owner` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/receipt` | — | Constructed face exposes internal refs and request/result EDN. | P1 |
| `:seon.effect/request-context` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.effect/request-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/result-blob` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/result-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/result-size` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/settle-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.effect/settled-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.effect/to` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/agent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/basis-t` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/capped?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/cid` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/class` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/commit-tx-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.error/data` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/data-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/drops` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/evidence` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/fact` | `seon.error/render-ai` / `seon.error/render-html` | Declared specialized pair; no floor. | covered |
| `:seon.error/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/kind` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/message` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/normalize-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.error/notice` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.error/notice-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.error/notification` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/notification-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/occurrence` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/occurrences` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/op` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/proc` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/process` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/reason` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/refusal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/refusal-shape` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/refusal-value` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.error/run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/signature` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/throwable-class` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/unclassified` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.error/unclassified-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.error/value` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.eval.drive/absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.eval.drive/absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.eval/allocated-bytes` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.eval/duration-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.eval/fn-entries` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.eval/host-interop-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.eval/outcome` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.export/parent-dir` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.export/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.export/request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/active-evals` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/active-work` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/admitted?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/attempt` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/buffer-capacity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/callback-result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/capacity-observer-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/changed-namespace` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/changed-namespace-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/changed-source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/channel` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/commit-drop!` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/commit-fault!` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/compile-namespace-fn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/completion` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/compute-timeout-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/configuration` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/configuration-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.flow/core-fault` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/deliver!` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/error-fanout` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/error-fanout-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/escalated?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/escalation-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/eval-proc-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/executor` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/failure-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/fault-committer-proc-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/fix-outcome` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/fix-step-fn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/future` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/graph` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/indexer-proc-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/io-complete!` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/io-submission` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/join-error-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/launcher` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/launcher-configuration` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/launcher-stopped` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/launcher-stopped-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.flow/lineage-status` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/lineage-status-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/mailbox-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/max-failures` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/max-turns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/namespace-owner-proc-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/owner-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/owner-ordinal` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/panic!` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/parallelism` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/plan-step-fn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/planner-proc-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/proc-id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/read-core-error-mode` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/read-sources` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/seed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/seeded-outcome-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/source-enumerator-proc-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/sources` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/started` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/started!` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/submission-capacity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/submission-capacity-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.flow/submission-id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/submission-wait-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/successful-owners` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/time-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/time-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.flow/timeout` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/timeout-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.flow/turn-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/work-call` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/work-fn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/work-launcher` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/work-launcher-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/work-result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.flow/work-submission` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.flow/workload` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/arity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/guard` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/guard-refs` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/input` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/input-refs` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/max` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/min` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/order` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/output` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/output-refs` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.arity/row` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.fn.ast.entry/key` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast.entry/order` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast.entry/properties` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast.entry/row` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast.entry/value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast.entry/value-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/child` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/children` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/guard` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/input` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/key` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/keys` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/node` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.fn.ast/output` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/properties` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/ref` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/registry` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/type` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.ast/values` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn.file/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/analysis-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/analysis-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/arglists` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/arities` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/ast` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/calls` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/capability-graph-malformed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/capability-graph-malformed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/capability-rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/doc` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/duplicate-program-identity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/duplicate-program-identity-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/existing-program-entity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/fn` | — | Raw entity map on direct/debug reach; ordinary namespace walk may absorb it. | P2 |
| `:seon.fn/index-phase` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/index-request` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/index-transaction-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/index-transaction-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/keywords` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/manifest-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/manifest-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/missing-population` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/ns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/population-incomplete` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/population-incomplete-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/private?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/resource` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/roots` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/schema-declaration-invalid` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/schema-declaration-invalid-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/scratch-not-fresh` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/scratch-not-fresh-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/source-checkout-required` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/source-checkout-required-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/source-file-invalid` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/source-file-invalid-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/source-span-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/source-span-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.fn/spec` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/sym` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.fn/workload` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.instrument/applied` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.instrument/args` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.instrument/arm` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.instrument/contract-violated` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.instrument/contract-violated-error` | `seon.error/instrumentation-prose` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.instrument/expected` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.instrument/fn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.instrument/instrumented` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.instrument/registered` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.instrument/request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.ns.alias/binding` | — | Raw entity map if reached. | P3 |
| `:seon.ns.alias/local` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns.alias/target-ns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns.import/binding` | — | Raw entity map if reached. | P3 |
| `:seon.ns.import/local` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns.import/target-class` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns.refer/binding` | — | Raw entity map if reached. | P3 |
| `:seon.ns.refer/local` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns.refer/target-name` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns.refer/target-ns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns/aliases` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns/doc` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns/imports` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns/name` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns/name-designation` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns/ns` | `seon.render.ns/render-ai` / `seon.render.ns/render-html` | Declared specialized pair; no floor. | covered |
| `:seon.ns/refers` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns/requires` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.ns/source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.operator/advertisements` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.operator/branches` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.operator/census` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.operator/changed-paths` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.operator/clusters` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.operator/failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.operator/failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.operator/flow` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.operator/health` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.operator/observation` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.operator/publish-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.operator/readiness` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.operator/status` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.print/default` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/face` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/length` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/level` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/namespace-maps?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/node` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/options` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.print/sink` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/table?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/unknown-face` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.print/unknown-face-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.print/width` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.problems/author` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.problems/deferred-agent` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/error-signature` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/errored-receipt` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/evaluation-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.problems/evaluation-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.problems/failed-run` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/form-problem` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/form-problem-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.problems/occurrences` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.problems/problems` | `seon.problems/ai-prose` / `seon.problems/html-report` | Declared specialized pair; no floor. | covered |
| `:seon.problems/request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/stale-var` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/unbound-var` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.problems/unbound-var-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.problems/unowned-namespace` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.problems/wedged-run` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.program/declaration-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.program/declaration-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.reconcile/adopt-identities` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/converged?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/desired` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/duplicate-identity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/duplicate-identity-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.reconcile/identity-outside-scope` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/identity-outside-scope-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.reconcile/no-identity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/no-identity-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.reconcile/operations` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/process` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.reconcile/request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.reconcile/result` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/rule` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/two-identities` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.reconcile/two-identities-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.block/name` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.data/cursor` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.data/no-such-path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.data/no-such-path-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.data/offset` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.data/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.data/window` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.hiccup/tag` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.hiccup/unparseable-tag` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.hiccup/unparseable-tag-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.package/base-revision` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.package/basis-transaction` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.package/delta` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.package/frame` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.package/keyframe` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.package/revision` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.package/size` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.package/streaming?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.value/artifact` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.value/html` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.value/max-collection` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.value/options` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.value/projection` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.value/text` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.value/tree` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.value/truncated?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.value/window-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.value/window-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.walk/attribute` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/back-reference?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/branch` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/changed-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/connection` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.walk/connections-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/connections-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.walk/elided` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/elided-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.walk/found-depth` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/lookup` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/no-such-entity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/no-such-entity-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.walk/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.walk/target` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.walk/unit` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.walk/units` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/feed-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.web/http-server` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/inbound` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.web/latest-packages` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/missing-port` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/missing-port-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.web/owner-not-ensured` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/owner-not-ensured-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.web/page-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.web/pages-mult` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/paint-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.web/port` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/registration` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/root-agent-id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/server` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.web/service` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.web/url` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/value-not-found` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/value-not-found-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.web/value-unreadable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render.web/value-unreadable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render.web/view` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render.web/wanted-port` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/acquired-context` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render/ai` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/ambiguous` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/ambiguous-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render/call-request` | `seon.render.value/render-ai` / `seon.render.value/render-html` | Declared specialized pair; no floor. | covered |
| `:seon.render/candidate-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render/candidates` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/distance` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/failure` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render/failure-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render/hiccup` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/html` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/invalid-output` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/invalid-output-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.render/namespace` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/output` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/output-schema` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/package` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.render/page` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/surface-id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/unit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.render/would-fall-to-floor?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/attribute-absent` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/attribute-absent-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/enum-not-storable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/enum-not-storable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/invalid-secondary-attribute` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/invalid-secondary-attribute-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/literal-not-storable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/literal-not-storable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/malformed-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/malformed-edn-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/nilable-attribute` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/nilable-attribute-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/noncanonical-edn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/noncanonical-edn-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/schema-invalid` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/schema-invalid-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/storage-not-string` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/storage-not-string-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.datahike/value-type-unavailable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.datahike/value-type-unavailable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.edn/dishonest-generator` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.edn/dishonest-generator-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.edn/duplicate-attribute` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.edn/duplicate-attribute-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.edn/misplaced-attribute` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.edn/misplaced-attribute-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.edn/not-a-map` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.edn/not-a-map-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.edn/unreadable-file` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.edn/unreadable-file-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.edn/unregistered-predicate` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.edn/unregistered-predicate-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.edn/unresolved-reference` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.edn/unresolved-reference-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema.edn/unsafe-namespace` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema.edn/unsafe-namespace-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/arguments` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/cyclic-reference` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/cyclic-reference-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/duplicate-projection-row` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/duplicate-projection-row-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/identity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/incomplete-predicate-contract` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/incomplete-predicate-contract-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/invalid-schema` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/invalid-schema-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/malformed-artifact-export` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/malformed-artifact-export-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/malformed-projection-form` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/malformed-projection-form-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/malformed-projection-identity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/malformed-projection-identity-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/malformed-projection-row` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/malformed-projection-row-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/nilable-map-value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/nilable-map-value-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/nilable-return` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/nilable-return-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/nilable-value-schema` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/nilable-value-schema-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/non-round-tripping-form` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/non-round-tripping-form-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/noncanonical-definition` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/noncanonical-definition-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/noncanonical-projection-data` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/noncanonical-projection-data-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/predicate` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/schema` | — | Raw entity map on direct/debug reach; ordinary namespace walk may absorb it. | P2 |
| `:seon.schema/schema-in-use` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/schema-in-use-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/single-segment-namespace` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/single-segment-namespace-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/undefined-contract` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/undefined-contract-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/unknown-shape` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/unknown-shape-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/unproved-predicate-purity` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/unproved-predicate-purity-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/unreadable-form` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/unreadable-form-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/unregister-outside-delta` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/unregister-outside-delta-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.schema/unresolved-predicate` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.schema/unresolved-predicate-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.admit/admitted` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.admit/capped?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.admit/caps` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.admit/interrupt-fn` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.admit/print-node` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.admit/projection-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.admit/projection-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.admit/record` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.admit/request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.admit/value` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/acquire-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.eval/args` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/ctx` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/ending-ns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/evaluation` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.eval/evaluation-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/evaluation-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.eval/impure-calls` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/install-mismatch` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/install-mismatch-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.eval/install-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.eval/invocation-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.eval/invocation-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.eval/missing-function-row` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/missing-function-row-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.eval/namespace-binding-cycle` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/namespace-binding-cycle-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.eval/nondeterministic-calls` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/program-row` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/reader-event-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/reader-event-count-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.eval/referenced-vars` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.eval/schema-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/schema-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.eval/session-blob-unavailable` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/session-blob-unavailable-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.eval/session-def` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.eval/session-defs` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/session-install-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.eval/time-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/time-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.eval/time-limit-ms` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.eval/unproven-called-vars` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.kernel/already-armed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.kernel/already-armed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.kernel/failure-admission-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.kernel/failure-admission-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.kernel/failure-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.sci.kernel/invocation-failed` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.kernel/invocation-failed-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.kernel/missing-function-installer` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.kernel/missing-function-installer-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.kernel/missing-interrupt-guard` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.kernel/missing-interrupt-guard-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.kernel/time-limit` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.kernel/time-limit-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.kernel/unresolved-invocation` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.kernel/unresolved-invocation-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.reader/keyword` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.sci.reader/keyword-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.sci.reader/tag` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/branch` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/built-at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/built?` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/commit-id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/current` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.source/digest` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/digest-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.source/expected-commit-id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/file-digests` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/path` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/populate` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/population` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.source/population-data` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/publish-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.source/published` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.source/roots` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/rows` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.source/snapshot` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.source/upsert-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.store/branch` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.store/branch-connection` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.store/connection` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.store/dir` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.store/lock-file` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.store/store` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.store/transaction` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.store/transaction-data` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.store/transaction-operation` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.failure/failure` | — | Raw entity map on direct/debug reach; ordinary namespace walk may absorb it. | P2 |
| `:seon.test.failure/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.failure/message` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.result/failure` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.result/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.result/outcome` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.result/result` | — | Raw entity map on direct/debug reach; ordinary namespace walk may absorb it. | P2 |
| `:seon.test.result/run` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.result/test` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.run/at` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.run/git-sha` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.run/id` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.run/run` | — | Raw entity map on direct/debug reach; ordinary namespace walk may absorb it. | P2 |
| `:seon.test.runner/default-cluster-refused` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/default-cluster-refused-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.test.runner/error-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/fail-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/invalid-long-reason` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/invalid-long-reason-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.test.runner/invalid-selection-mode` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/invalid-selection-mode-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.test.runner/invalid-silence-seconds` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/invalid-silence-seconds-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.test.runner/long-reason` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/long-test-ns-hook` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/long-test-ns-hook-error` | `seon.error/render-ai` / `seon.error/render-html` (error-exempt) | Declared error face; excluded from gaps. | exempt |
| `:seon.test.runner/namespaces` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/pass-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/record-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.test.runner/record-tx` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/recorded` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.test.runner/result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.test.runner/results` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/run-request` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.test.runner/run-result` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.test.runner/selection-mode` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/silence-seconds` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test.runner/summary` | — | floor-map: structural map only when explicitly rendered. | — |
| `:seon.test.runner/test-count` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test/ns` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test/source` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test/subject` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test/sym` | — | embedded: not independently selected; appears inside a containing value. | — |
| `:seon.test/test` | — | Raw entity map on direct/debug reach; ordinary namespace walk may absorb it. | P2 |

## Tool and render feedback

- The flat-file census is straightforward and queryable, but the important
  signal is buried by a raw missing-producer count: 1,156 looks alarming while
  only a small entity subset is repeatedly visible. A durable query should join
  entity identity, live cardinality, walk reachability, and producer properties
  instead of presenting absence alone (`src/seon/schema.clj:1236-1255`).
- The structural floor is honest and bounded. Its rawness is valuable in
  `/data`, whose handler deliberately calls the value floor directly
  (`src/seon/render/web.clj:1385-1447`); the defect is using that face for
  important recurring context/page entities, not the floor itself.
- The already-running JVM returned one huge contract-violation envelope while
  probing the walk, which made the useful producer evidence difficult to read.
  The bounded direct-floor calls were much easier to inspect. This observation
  is not attributed to current source because the process image was older than
  the current render boundary.
