---
type: research
status: complete
created: 2026-09-19
tags: [schema, data-model, audit, namespace]
---

## Supplemental assigned-lane audit — namespace agents and observation guarantees

This section is the independent `/root/schema_audit_b` assignment. The separate report
`schema-audit-b-2026-09-19.md` appeared at the assigned output path during
this lane's work. Its bytes are preserved; its claims are not adopted as
this lane's evidence. The parent authorized this separate supplement.
This supplement used exactly one read-only MCP JVM evaluation and no test
JVM, production mutation, SCI definition, cluster operation, or commit.

### Basis and coverage

Source basis: `b9c4cfa5866fc3fdaac768955bf886a42e6d1ff2`, with concurrent
edits in `seon.db`, `seon.schema`, runner/selection code, their tests and
`resources/seon/schemas/seon.db.edn`. The audit read every byte of all 70
paths assigned in `tmp/lane-specs/schema-audit-b-2026-09-19.md`.
Supplemental reads covered agent, plan and plan-item schemas and their
creation, wake, issue-opening, error-routing and maintenance readers.
This is complete source coverage of the assigned slice, not dynamic proof
of every declared schema or an installed-versus-disk equivalence claim.

Read end to end: the supplied repository instructions; data-modeling,
datahike and data-oriented-clojure skills; `data-modeling-guide.md`;
`data-model.md`; the 2026-09-17 modeling study; the 2026-09-16 deletion
and program-graph study; `deletion-semantics-agents-and-turns`;
`message-wake-and-provenance-modeling`; `retirement-is-a-fact`;
`repl-native-retraction-and-refactoring`; both context-generation and
steward-platform plan READMEs; and the issues README. Named dated studies
are in this report's directory.

Dependency grounding was read from the vendored Datahike source, gitlink
`e11845bac78e1241bca0766ddc07d978bd63d74a`:
`src/datahike/db/utils.cljc:100` (numeric entid is not existence);
`src/datahike/transaction.cljc:718` (many-value expansion), `:998`
(incoming-ref sweep), `:1206` (final validation callback);
`src/datahike/pull_api.cljc:309` (bounded pull).
First-party final entity validation is `src/seon/db.clj:3447`;
complete owned-value validation follows at `:3485`. Those source lines
were read in the dirty tree, not claimed as immutable HEAD coordinates.

### Ranked findings

| Priority | Finding and evidence | Existing note/status | Minimum falsifier and owning invariant |
|---|---|---|---|
| P1 | **Deleting a listener's optional target broadens it to every entity.** `seon.listen.edn:2` explicitly makes absence wildcard; `:7` makes the ref optional. Datahike sweeps it when its target is deleted. `src/seon/cluster/wake.clj:420` and `:431` interpret the resulting nil as wildcard. | No exact class note found by issue search. Related `runtime-listens-do-not-yet-participate-in-turn-eligibility.md` is **open**, but concerns turn eligibility, not target deletion. Listening redesign is already parked in the roadmap; extend that class rather than create another listener. | Canonical fixture: a pattern targets entity A, B has the same listened attribute; retract A and mutate B. The pattern must never silently widen. `seon.cluster.wake` and schema owner must represent wildcard positively or choose deliberate refusal/settlement semantics for a removed target. |
| P1 | **A completed maintenance result need not contain an observation.** `seon.maintenance.result.edn:246` requires only id; `:250` accepts any map. `src/seon/maintenance.clj:45` returns an unmatched result unchanged; `src/seon/schedule.clj:437` adds id and completed-at to the result arm. An empty result map is therefore structurally indistinguishable from missing evidence after id insertion. | `a-successful-cluster-cleanup-cannot-persist-into-maintenance-facts.md` is **resolved**, correctly fixing its nested result union. `a-stored-entity-schema-requires-a-cardinality-many-key-that-empty-cannot-satisfy.md` is **open**, but listed maintenance collection keys are now optional. Neither establishes a positive completed observation at the root. | Settle a canonical claimed receipt with an empty result map through the real owner. Require operation-declared result evidence or a typed unknown/refusal, not an id-only completed observation. Existing schedule/maintenance projection and settlement owners should select and validate the declared result shape; valid empty many collections remain optional. Source-derived counterexample; no transaction executed here. |
| P1 | **Program facts admit contradictory arity/binding metadata.** `seon.fn.arity.edn:7`, `:10`–`:12` use unconstrained integers; the row has no min/max/count agreement. `seon.fn.binding.edn:8` permits shape symbol without its symbol, or conflicting payloads. These facts drive program inspection and arity decisions. | No exact relational-integrity class note found. Existing missing-contract detectors are related but cannot prove metadata consistency. | Mutate canonical function component rows to negative min/count, max less than min, and symbol shape without symbol. Final transaction validation must refuse before readers consume it. Own this in canonical arity/binding declarations and indexing admission; do not add reader-local heuristics. Reuse the existing bounded-arity predicate pattern in `seon.instrument.arity.edn:4`–`:25`. Counts for empty argument lists must remain explicit positive facts, not required empty many attributes. |
| P1 design dependency | **Plural namespace assignment exists; plural responsibility and issue execution do not.** `seon.agent/namespace` is nonunique, but `seon.ns.edn:34` names “The one agent.” `src/seon/cluster/agent.clj:128` claims only an empty stewardship pointer; `:421` returns a scalar. `src/seon/error.clj:1475` routes through a scalar namespace query. `seon.issue.edn:30` stores one worker; `src/seon/issue.clj:875` derives its id from issue id and `:938` resumes that worker. | `unowned-namespace-oversight-still-inverts-assignment.md` is **open** and already distinguishes default namespace from responsibility. `raw-identity-projection-hides-selected-steward.md` is **open**, a rendering defect. Neither satisfies the new plural requirement. | Create two namespace agents for one namespace and two independent work assignments for one repair subject. Verify independent budgets, basis, wake/response and settlement, with explicit fault-recipient selection. Change the existing namespace/issue/agent owners together; merely renaming steward leaves the cardinality bug. |
| P2 | **Issue status and verified completion are different facts but prose conflates them.** `seon.issue.edn:3` permits resolved/superseded source notes, while `:38` says absent resolved-tx means open. Imported source lifecycle and an agent repair's verified outcome are not equivalent. Live census has 1,818 status assertions and zero resolved-tx assertions; it does not count which statuses they are. | No exact issue found. Preserve imported lifecycle while clarifying what query answers operational completion. | Import a resolved note with no agent assignment and compare source-note status with a newly assigned repair's done condition. Each query must distinguish no verification from verified completion. Do not fabricate a completion transaction for imported prose or infer success from no failing tests. |

All five rows are source findings. The live census below confirms relevant
installed cardinalities, not the proposed deletion, settlement or relational
counterexamples. A current empty population is never treated as evidence
that a dangerous transition cannot occur.

### Namespace agents, work templates and conversations

Use **namespace agents** for agents responsible for a namespace. An agent's
default REPL namespace is a separate relation and is already nonunique.
Responsibility needs a plural relation. If each relation needs an independent
budget, acceptance basis, lifecycle or completion, those facts belong on an
assignment occurrence; a set of agent refs alone cannot carry them.

Use **work template** for the reusable context specification, and
**conversation** for user interaction. “Issue” remains a diagnosed problem
with evidence and resolution criteria. Conversation is not required to
pretend to be a defect with a terminal repair test. This is a naming and
modeling recommendation, not a new parallel task subsystem.

There is substantial machinery to accrete in place. The agent's identity
renderer already emits issue-opening read forms
(`src/seon/cluster/agent.clj:230`); `seon.issue.opening` documents that
ordinary system-turn path at `:1`, obtains linked entities at `:37`,
and selects its rendering variant at `:61`. The config enum is in
`seon.config.render.edn`. Today that selection still follows one issue
worker, and issue “problem” is overloaded as generic instructions
(`seon.issue.edn:7`).

A reusable template should declare the relations/data needed for that work,
the AI/HTML render functions, the questions or forms they expose, and the
work-specific completion evidence. Instantiating it should populate or link
facts on an agent/assignment so the existing walk and schema render pairs
produce the context. Do not persist a second assembled prompt, hand-roster
all rendered fields, or make raw value printing the main teaching surface.
Agents should be able to author their render functions as ordinary program
definitions with the same contract, test and publication guarantees.

Choose and query these independent facts explicitly:

- namespace responsibility versus default evaluation namespace;
- reusable specification versus a concrete assignment occurrence;
- subject/evidence/basis versus mutable “current agent”;
- attempted verification with zero failures versus never verified;
- conversation messages and response obligations versus repair completion;
- candidate-context tests versus evidence that a definition was accepted
  into the cluster and later written back to source.

The current singular issue budget anchors to assertion of `issue/agent`,
and resumes by increasing the same budget. That is not independent
multi-agent execution. The roadmap's older planned `my.task` family must
be reconciled with the existing issue/start/opening/plan mechanisms before
building anything. A new noun alone does not justify a second owner.

### Contracts and other modeled edges

The assertion “all functions are instrumented” is false as a blanket source
claim. `src/seon/instrument.clj:848` walks private Vars too, but only
collects Vars with `mi/-schema` and skips primitive functions. For example,
`src/seon/maintenance.clj:19` still defines a private function without
a Malli declaration. Full instrumentation census belongs to the parent's
separate audit; this lane does not convert source examples into a numeric
coverage claim. Tight schemas plus the same armed fixture are necessary;
an optional contract or an unchecked relational invariant remains a hole.

Issue citations deserve a deliberate token-versus-live-relation pass:
`seon.issue.edn:8`–`:26` resolves source observations into refs, while
`seon.issue.citation` makes its cited file required. Deleting a source
target can therefore sweep observed evidence or refuse because of a
historical citation. Keep a live acceptance-test obligation distinct from
a note observing a test name. This is an inventory requiring chosen
deletion semantics, not permission for a blanket ref-to-value conversion.

The newly declared error evidence, location, omission and arity shapes
provide stronger examples of nonnegative ordinals, measured omissions and
relational predicates. Their presence does not establish that every
producer currently emits them. Broad runtime maps in the assigned slice
were not automatically classified as defects: an opaque process/SCI
object or an honestly polymorphic result can require an open contract.

### Historical corrections

- `retirement-is-a-fact` is historical design input, not authorization
  to restore tombstones. Current program deletion uses retraction/history.
- The old message inbox/subject/refresh proposals have materially changed:
  current message schema distinguishes routing, sender-as-inside-marker,
  subject token and assignment. Do not reintroduce the discarded queue.
- The modeling guide's §7.1 claim that the study is not in the tree is stale:
  the study exists and was read here. Its earlier claim that identity-less
  components lack complete validation is superseded by current owned-value
  validation in `seon.db`.
- `data-model.md`'s system-turn wake-answering statement conflicts with
  the current ordinary-reply basis rule. Correct the authority rather than
  deriving a new algorithm from the old prose.
- The namespace “one steward” roadmap and its three-worker trial are
  historical limits, not the owner's requested plural responsibility model.

### Live evidence and limitation

One read-only JVM evaluation ran with explicit default connection. Basis
`536871516`, commit `6aaeb718-b843-54a6-9954-495583276146`.
Installed `ns/steward`, `agent/namespace`, and `issue/agent` are all
cardinality one refs; the first two each have two assertions, issue/agent
has zero. A cardinality-one agent/namespace attribute does not imply
namespace uniqueness. The installed listen/entity ref has zero assertions.

The probe mistakenly dereferenced the raw connection instead of using
`(seon.db/db connection)`. Its final datom count returned the explicit
missing-projection diagnostic; that count is **unavailable**, not zero and
not an independently attributed runtime defect. Parent confirmed the
correct acquisition and no retry was performed. The returned envelope is
windowed with a retrievable blob reference; the full returned envelope is
preserved below, without claiming its blob was separately retrieved.

```json
{
  "seon.dev.mcp/namespace": "user",
  "seon.dev.mcp/session-id": "schema-audit-b",
  "seon.dev.mcp/cluster": "default",
  "seon.dev.mcp/mode": "jvm",
  "seon.dev.mcp/events": [
    {
      "tag": "err",
      "val": "WARN seon.db/projection-fallback caller= seon.db/datoms missing-projection count=1; supply the operation's projection.\n"
    },
    {
      "tag": "ret",
      "val": {
        "seon.dev.mcp/value": {
          "audit/attributes": [
            {
              "audit/attribute": "seon.ns/steward",
              "audit/count": 2,
              "audit/schema": {
                "db/cardinality": "db.cardinality/one",
                "db/valueType": "db.type/ref"
              }
            },
            {
              "audit/attribute": "seon.agent/namespace",
              "audit/count": 2,
              "audit/schema": {
                "db/cardinality": "db.cardinality/one",
                "db/valueType": "db.type/ref"
              }
            },
            {
              "audit/attribute": "seon.issue/agent",
              "audit/count": 0,
              "audit/schema": {
                "db/cardinality": "db.cardinality/one",
                "db/valueType": "db.type/ref"
              }
            },
            {
              "audit/attribute": "seon.issue/status",
              "audit/count": 1818,
              "audit/schema": {
                "db/cardinality": "db.cardinality/one",
                "db/valueType": "db.type/keyword"
              }
            },
            {
              "audit/attribute": "seon.issue/resolved-tx",
              "audit/count": 0,
              "audit/schema": {
                "db/cardinality": "db.cardinality/one",
                "db/valueType": "db.type/ref"
              }
            },
            {
              "audit/attribute": "seon.listen/entity",
              "audit/count": 0,
              "audit/schema": {
                "db/cardinality": "db.cardinality/one",
                "db/valueType": "db.type/ref"
              }
            },
            {
              "audit/attribute": "seon.fn.arity/min",
              "audit/count": 1406,
              "audit/schema": {
                "db/cardinality": "db.cardinality/one",
                "db/valueType": "db.type/long"
              }
            },
            {
              "audit/attribute": "seon.maintenance.result/id",
              "audit/count": {
                "seon.error/data": {
                  "seon.db/operation": "seon.db/datoms",
                  "seon.schema/missing-projection": true
                },
                "seon.error/kind": "seon.schema/missing-projection",
                "seon.error/message": "This operation requires a carried schema projection."
              },
              "audit/schema": {
                "db/cardinality": "db.cardinality/one",
                "db/unique": "db.unique/identity",
                "db/valueType": "db.type/string"
              }
            }
          ],
          "audit/basis": 536871516,
          "audit/commit": "6aaeb718-b843-54a6-9954-495583276146"
        },
        "seon.dev.mcp/windowed?": true,
        "seon.blob/digest": "bc659c30cb45abd38d3125b1629d49d71691c9a3b2d53382295197a5dcc386c9",
        "seon.blob/size": 6645,
        "seon.dev.mcp/retrievable?": true
      },
      "ns": "user",
      "ms": 257
    }
  ],
  "seon.dev.mcp/runtime": "clj",
  "seon.dev.mcp/cluster-state": "alive",
  "seon.dev.mcp/form": "(let [connection (seon.operator/connection \"default\") database @connection attrs [:seon.ns/steward :seon.agent/namespace :seon.issue/agent :seon.issue/status :seon.issue/resolved-tx :seon.listen/entity :seon.fn.arity/min :seon.maintenance.result/id] result {:audit/basis (:max-tx database) :audit/commit (get-in database [:meta :datahike/commit-id]) :audit/attributes (mapv (fn [attribute] {:audit/attribute attribute :audit/schema (select-keys (get (:schema database) attribute) [:db/valueType :db/cardinality :db/unique]) :audit/count (if (get (:schema database) attribute) (let [rows (seon.db/datoms database :aevt attribute)] (if (map? rows) rows (count rows))) :audit/not-installed)}) attrs)}] result)",
  "seon.dev.mcp/root": "/Users/sean/src/seon"
}
```

### Execution order proposed to the parent

1. Resolve carried-projection/runtime freshness and integration baseline
   through the already active owners.
2. Define the relational guarantees for assignments, responsibility and
   observed completion; correct the conflicting authority prose.
3. Independently fix listener deletion, program-metadata invariants and
   maintenance observation contracts, each with canonical final-writer
   regressions. Avoid concurrent ownership of shared schema resources.
4. Generalize the existing issue/agent/render opening seam for work
   templates and conversations after those facts are settled.
5. Prove two namespace agents in independent candidate SCI contexts, then
   separately prove write isolation, accepted-cluster tests, and source
   write-back tests. Parent reports its separate fork probe establishes
   private-definition isolation but a shared durable connection; this lane
   did not perform that probe. SCI separation alone proves no durable-write
   isolation.

The parent's integration gate remains owed. This lane proposes no
production implementation or additional mechanism before the relevant
data guarantees are selected.


### Full-read coverage and captured file hashes

Hashes captured at report completion; `seon.db.edn` is concurrently edited,
so this is a dated disk inventory rather than an installed schema digest.
Every listed resource was read end to end.

| Resource under `resources/seon/schemas/` | Bytes | SHA-256 |
|---|---:|---|
| `seon.config.render.agent.edn` | 533 | `d4e219d99b86ace10e91299185c0df9660b313ce80db70cc55d6aa098d6c1be2` |
| `seon.config.render.edn` | 830 | `7c575f296c1a6e7e6d277d5a9287c21e39046978022a5ff65dff701c1a739484` |
| `seon.config.run.edn` | 231 | `6832e65ca07d96205f7a09d78ddd84bdcdcf2130966c5c1ffaaeaa90c8dba9e9` |
| `seon.config.shell.edn` | 1190 | `d5886ee3cb3ebbc2d254f007b58e530ac0cb98ce5005c20a719ea94eb844c7e0` |
| `seon.config.test.edn` | 97 | `86098b956e9d85b9e2a24fa9130c6aec41b2df12ff7fad9bc2c59302d2f051d6` |
| `seon.config.web.edn` | 972 | `60cfddef04d591adb7c4b8a9afa15eb97a2a6026fcadf8c28242043bd98d5de8` |
| `seon.context.capture.edn` | 2255 | `1caf0bac3d55e598d4bd090ed2316e69c42db6744fc0560659f1c7adf4f2c532` |
| `seon.context.contribution.edn` | 2001 | `c648ef4be0b9ed364747f24428043e26f6a4ddc985f3b9329add20092af4fe9c` |
| `seon.context.edn` | 4552 | `7050d02589a388a6c624786b011bf13e93a6e40e3e61c06e60c36e084b2d5e3a` |
| `seon.db.availability.edn` | 540 | `cd1f037f1fa1198fbf0385ae3abcc822bdd85a7c471326a516ec8454a0f076d5` |
| `seon.db.diff.edn` | 1214 | `774a66acc9a0a7fd500a760a028309180ee11ad95cd857824c116734763f462c` |
| `seon.db.edn` | 19893 | `ff10dbe52c754c56f77b1aaf3a40f4f082e42b629e783a9b72776dbd995bcfda` |
| `seon.db.id.edn` | 141 | `c19f2405b3bdb149b1843fda383b92e39422c0e07501209451ddd236e9c3137e` |
| `seon.db.process.edn` | 637 | `d2ebc9e576f38d92d7274b1ecceb06d65a707d3748f626577e5a31ee387bbed9` |
| `seon.db.read.edn` | 594 | `ec5fb721b30ba03f7519de316a1138191ed8dbbabbd98718166f173e64c307ee` |
| `seon.db.read.target.edn` | 1721 | `a4375945fb85c661228c9722911fd36434c342a6e07f5793aa502085211cd05c` |
| `seon.db.write.attempt.edn` | 1422 | `b7d4e7842db832b799bc4a0bb6b1505ac06bdb06dc8f21876fa3d86f1bfd36ad` |
| `seon.db.write.edn` | 393 | `99bad23b717af5f7591fb727cb8b817d92d73c64429704534fc6d911ca4a67b5` |
| `seon.dev.mcp.artifact.edn` | 693 | `2c7ba5864b398574bc9201e0388ab139312bfaa78f96a4b1cb2718cf942eff6e` |
| `seon.dev.mcp.edn` | 2369 | `c98b854a8660851883ce9fe8c1f9848349815aaf8d8d883e5e1e549752de742c` |
| `seon.dev.process.edn` | 619 | `3e9c151326367c857bb56f6fe1cd628a7b6a9ff3e68e71470ed6f3bdfe6a23e7` |
| `seon.edit.edn` | 1458 | `6980218b0af32ee4308f74c5c8863906e9b4173738b3df89f3a6853cefa18b35` |
| `seon.effect.edn` | 9947 | `c634b678c35614862fe753230d2a94a0edc7bf07a55feadcc6527a46643863ac` |
| `seon.env.edn` | 7033 | `4007e47de066ebe279670b16bd4d03aab062b88ad6c8ba0de5311776e40ddba6` |
| `seon.error.basis.edn` | 451 | `7c33fac0309698f31eff89280d9dadfbe88e70336f90d7d18e104c49fd0ba2d8` |
| `seon.error.disposition.edn` | 928 | `b95377e13736e52ed27f295e35ad4adbf74621d2ee1710fbd5e4032b40ad70b0` |
| `seon.error.edn` | 15191 | `19b3a00bae987adfe8890b3519e1f2cbb2e133f87c78821394218e0796eb172b` |
| `seon.error.evidence.edn` | 811 | `df2e540f64993a6b6ab9c11cd2db7d74d6c73d244068584374ecdbead2ffb620` |
| `seon.error.key.edn` | 542 | `b88f2d78bfcb30dca50499b01d15a732512fa733f033160d743a722002ee3290` |
| `seon.error.location.edn` | 824 | `7d0408b7506854866f093fdee565f02d68e07c8a4677489bd09c298f46f7c0e4` |
| `seon.error.location.segment.edn` | 470 | `77dcead157d398fd18c3822fa2722733c6ad07bf963e8cf9d7aec0147ce1da58` |
| `seon.error.occurrence.edn` | 2432 | `988113276d9c1f4af9b4bc63950510e78061faadd3da656b0be9799f85056324` |
| `seon.error.omission.edn` | 957 | `3c591874bbdc3491312b8641e7cb8c08fec72c7c1b7d038fb549d0546b870a49` |
| `seon.error.projection.edn` | 1074 | `aa816a4d19e9f5846ccad419657dd79b619312e7ac9864a4d25034e1af875b08` |
| `seon.eval.drive.edn` | 3499 | `5e94788171d173d9975a6f9255b3cdb68f31df78a389083102b8eff814951d21` |
| `seon.eval.edn` | 2828 | `2d0aa76aedab58cfffdd4f74d3cfc8a7071cd4de525f28505bdd7f6abd75f8e9` |
| `seon.export.edn` | 249 | `761acf7e47b21322ea598e7f70db24ca52288d2f2ecd2300b7c05fd3ef95a81b` |
| `seon.failure.edn` | 405 | `af809528569ec2ca6c42fdef810edd9e2d8d1f9dc21b1b17f97f94213c5f5515` |
| `seon.flow.edn` | 10750 | `9ca573ffe08db7d55f014225d560d9f5d5df6ee704aa0e11f6335c9efbdd988e` |
| `seon.fn.argument.edn` | 1866 | `0fa051ebfc64102b57ced56467f46361515afb8dcc7c9ca9bf31603a7fc8ab63` |
| `seon.fn.arity.edn` | 2091 | `2bc3e53462d07b324c971e94c389a0dd04738e528f6c54d8ba3032fbfc802d94` |
| `seon.fn.binding.child.edn` | 695 | `328ae464b858d2bef175e55caf72536999ab15dfa5d620f4b0f8b63274a4fca2` |
| `seon.fn.binding.edn` | 2003 | `6fc0bc6e03a37bace2c2cbe1563f36d2c69888ab8d2d393fefc65500782240f7` |
| `seon.fn.binding.entry.edn` | 2617 | `52daccdc0a886cb2cc4f58a98f4bca1f4afd16feb5116c0d9856baf3090227af` |
| `seon.fn.contract.edn` | 1165 | `6a2c19c6f889d7e43d842fac8245da0f622af0a205593b66549c0758ccf97700` |
| `seon.fn.contract.finding.edn` | 288 | `7f7332bb3be59b1c048d6f1b4d024ededde306220fd1c106749b1f3e165539bb` |
| `seon.fn.edn` | 15989 | `cc884595594c5e33a6a71df7b9daee111d9d6fcb446fd870c575df80a2e4ff36` |
| `seon.fn.file.edn` | 1390 | `4c533d1a52061ee5c93c9bf61d945d65e02e6af529e15c46ed6464619dcdafe5` |
| `seon.fn.finding.edn` | 116 | `a7bd24ebd2ca1a08d962858ab211d6099e82c5752db223f716531ef87d1246aa` |
| `seon.fn.manifest.edn` | 799 | `0c9213dfda4998a20171970699b5b80075fb25fb8ae9102233efdf9785b4f409` |
| `seon.fn.output.edn` | 2444 | `50e47bbc2aabeede6dbbf470062ed3bb45ba5d226de91365f2cede1f8c102f3e` |
| `seon.help.edn` | 277 | `5cf96d88303452a6437da35d1c18f52b439c2fd226da65d2695cd5bac7c3f440` |
| `seon.id.edn` | 27 | `803a49777086a256cadb9b3749b1d5daa8ec17dd7cc05a7871e294c93dab4600` |
| `seon.instrument.arity.edn` | 614 | `7c2bcbd96bb9ca3c0f72d7794149020ce0427fdcbe3500f751a6680cb401d541` |
| `seon.instrument.edn` | 6301 | `c88a954b7a26481a890f2aa1b7a7299ed2f4221525c3d883f541b2bc40b28a37` |
| `seon.instrument.explanation.edn` | 1973 | `333fedc04df1075a0e6bfec5a0d897b46ed27827a45bd2f626aa693be8416a2d` |
| `seon.instrument.explanations.edn` | 897 | `2886f51889aebbf0ef446b74934e18b5eab1c326134ffa678fd85395aacda832` |
| `seon.instrument.humanized.edn` | 914 | `cec5cc2ea5d6ce13a244ac01e96c39b908c844aee0bf16dd66838d02bc95463a` |
| `seon.instrument.humanized.message.edn` | 670 | `df5c4f4e87ae38677abc781bfca9be7c9ab0f679f3c07291ef56b51e3cb6cf31` |
| `seon.issue.citation.edn` | 915 | `5e1d24eb57fa2f7045eaa437fe22c355e4d8ba950f4d379849aa1f8fc1213724` |
| `seon.issue.edn` | 7503 | `5741e65bb6445a7f00afba525a4b8ed9d26fa98187b3314b9c6b7a18a4802183` |
| `seon.lint.edn` | 1091 | `6c5d61f1b323a59f91976557d2348fcf6e8cccf55bbc3627e8b7ff8425ed851e` |
| `seon.listen.edn` | 709 | `1607361a8dc8cafd7053f13a68380d0e5e5291b710d68068b615143bc76c4df3` |
| `seon.maintenance.edn` | 3195 | `57256ba0cc5094c8e6fe38ff318a565c4fd0f763f64168f391ee3b0c8ceb2ad9` |
| `seon.maintenance.receipt.edn` | 2729 | `17cba35b0e4a89827ee9d27b437a93972a29270f1a128dd66cc46e607073efb3` |
| `seon.maintenance.request.edn` | 5385 | `2b9f00c030180ba2d4e22d14ab447559ef81ae975f6b0d9bb7b8543093fa2cdc` |
| `seon.maintenance.result.edn` | 15789 | `a8369de927bf044ce2236ae9cdcd3f6bee22823a1dc40fda095c3cd582258a00` |
| `seon.message.edn` | 8191 | `21da1ccc54c889b1ab4d754a330154bc9e92cb136bfe299edd398c40b1f24842` |
| `seon.ns.alias.edn` | 1002 | `fff97a1c4b7d2165d89871025ee3885544821ccd11676d12e544cbce8f654028` |
| `seon.ns.edn` | 2169 | `aef5727260d0b20e4205415eebb98e3df7319f0c17925e0952a9273d7e6777c0` |
