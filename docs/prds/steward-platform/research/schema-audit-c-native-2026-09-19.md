---
type: research
status: complete
date: 2026-09-19
tags: [schema, data-modeling, audit]
---

# Schema audit C — 2026-09-19

Read-only review of all 70 assigned schema resources. No production changes, transactions, test JVMs, or cluster operations. This is schema and code evidence, not a green integration gate.

Entry HEAD: `b9c4cfa5866fc3fdaac768955bf886a42e6d1ff2`. The shared tree already had edits to `seon.db`, `seon.schema`, their tests, the test runner/selection owners, and an untracked `seon.test.selection.edn`. Findings below distinguish live installed validators from current disk code. Coverage hashes below describe the bytes inspected at report completion, not a claim that every concurrent edit has been adopted.

## Ranked findings

### 1. Canonical shape children permit missing content and invalid positions

**Confirmed declaration hole, high priority.** `resources/seon/schemas/seon.schema.shape.child.edn:2` admits any integer order; its row at line 6 makes both schema and literal content optional. The live validator accepts `{:seon.schema.shape.child/id "audit-only" :seon.schema.shape.child/order -1}`. This row says there is a child, but neither what shape it references nor what literal it records.

The constructor already chooses exactly one arm (`src/seon/fn/schema_shape.clj:199–206`); this proves the intended distinction, but constructor correctness is weaker than final-database correctness. The reader at `src/seon/call_preparation.clj:192–193` joins through child/schema, so malformed content disappears from that read. Shape-entry siblings similarly leave independently optional typed values beside kind/fingerprint/EDN (`resources/seon/schemas/seon.schema.shape.entry.edn:1`); order is plain integer there too. These are candidates for the same relational-validation class, not separately proven failures.

**Invariant/owner:** the canonical shape owner should declare a nonnegative position and exactly one valid child payload; validate ordered sibling positions and consistency of typed decomposition with canonical form at the existing final writer. Avoid a second hand-maintained schema registry. Inspect whether child identities are actually required or ownership suffices before preserving their current artificial IDs.

**Smallest falsifier:** canonical database fixture attaches the demonstrated row to a real shape and expects a typed refusal; then verify neither/both payloads and duplicate positions. Include a direct child mutation, not only constructor submission. The live experiment below validates the declaration only; it did not transact the malformed row.

**Issue mapping:** no exact open child-payload issue found in the issue search. `docs/seon/issues/schema-declaration-regression-disagrees-with-current-row-shape.md` is open but concerns namespace provenance/parity, not this invariant. Parent should create one shape-integrity class note.

### 2. New structured accretion results do not constrain their terminal state

**Confirmed declaration hole, high priority before using these results as merge evidence.** The new `resources/seon/schemas/seon.test.accretion.auto.edn:10` entity requires counts/status but leaves failure/skip reason independently optional. At live basis 536871517 it accepts status `:passed`, skip reason `"skipped"`, and 9 executed cases out of 0 declared cases.

Unlike a genuinely unknown observation, these are mutually inconsistent affirmative facts. Making every field required would not solve it. The nearby group schema already demonstrates a relational predicate for ordered failures (`resources/seon/schemas/seon.test.accretion.group.edn:1`), whereas the automatic result has no equivalent relationship check. The failure component's source/test-symbol fields also deserve conditional validation (`resources/seon/schemas/seon.test.accretion.failure.edn:1`).

**Invariant/owner:** the test/accretion owner should define what passed, failed and skipped guarantee, enforce executed-count versus case-count, and require/prohibit the corresponding evidence. Apply the constraint through the existing component schema so subsequent datom edits are checked, not merely initial result construction.

**Smallest falsifier:** the canonical fixture should refuse the demonstrated contradictory component attached to its real owner, and also reject mutation from a valid state into the contradiction. Check legitimate zero-case, skipped and partially executed failure examples so a blanket nonempty rule does not erase meaningful states.

**Issue mapping:** no exact existing issue found for the new automatic-result relationship. Extend one conditional-result-integrity class across proven siblings; do not launch a lane per optional field. These declarations are additive error-manifest work; the probe proves declaration permissiveness, not that today's runner has persisted this contradictory result.

### 3. Scheduled function identity still uses a ref despite the token ruling

**Confirmed current model mismatch, medium priority.** `resources/seon/schemas/seon.schedule.task.edn:3` declares function as a required ref and marks the target as `:seon.fn/sym`. `src/seon/schedule.clj:106` writes a lookup ref; readers at lines 213 and 349 join through the function row. This is the remaining shape already named in the modeling guide's §2.8: a scheduled function name should survive as a symbol observation.

Do not repeat the older claim that deleting the function silently leaves an invalid task: current final-report validation refuses missing required refs. The reason to change is the symbol/token model and readable unresolved work, not an absent ref validation.

**Invariant/owner:** schedule owner changes schema, writer, readers and deletion/referrer selection together at the reset boundary. Store a qualified symbol and retain explicit same-transaction live-referrer repair obligations. **Falsifier:** delete/redefine a scheduled function and require a complete named refusal; repair the schedule and program atomically; verify the symbol remains queryable when recording unresolved imported data.

**Issue mapping:** historical `schema-design-review-2026-09-17.md` N9 and current `data-modeling-guide.md` §2.8 already identify this. No exact dedicated open issue found by the assigned search.

## Important corrections and verification boundaries

**Disappearing identity selection is not a current finding.** Current disk `src/seon/db.clj:3190` derives whole-entity schemas from required installed identities. The final owning-value pass at lines 3600–3620 unions the root's before/after identities, refuses nonempty unowned rows, and supplies the declared component schema to identity-less owned children. `write-entity-error` at line 3447 uses those identities. `write-report-error` at line 3854 includes attempted and effective report datoms and before/after installed identity sets. Therefore the earlier “remove the identity to escape validation” concern is explicitly addressed in this implementation. Preserve a regression; do not fund another implementation lane without a failing case. This is disk code inspection, not a mutation probe of the live writer.

**Error declarations are in transition; old issue text is not current code evidence.** The error-entities PRD was examined for its structural-facet, explicit-output and no-silent-recording requirements. The assigned schemas still contain legacy marker booleans alongside additive facet declarations (for example render-web errors at `resources/seon/schemas/seon.render.web.edn:167`). The open `docs/seon/issues/error-class-catalog-and-renderers-disagree.md` and `docs/seon/issues/instrumentation-record-mode-has-no-acquired-fault-recorder.md` already own this class. However `src/seon/instrument.clj:501–542` now refuses missing record-mode custody and invokes the compiled wrapper for SCI as well. The old statement that record mode simply returns the original function is stale. This audit does NOT establish complete instrumentation: it did not census every function/arity or run fault persistence. Parent must use the instrumentation lane's current evidence rather than either blanket assurance.

**Pulled and stored shapes still have cleanup debt.** `resources/seon/schemas/seon.test.failure.edn` retains a handwritten value shape; `seon.render.transcript.edn` retains transcript/pulled-envelope shapes. The entity-versus-pulled study identifies this class. Do not indiscriminately narrow genuine runtime object, Hiccup, evaluation-result or total-value-renderer polymorphism. Those boundaries intentionally handle values that are not database entities.

**Digest alias identity coupling remains.** `resources/seon/schemas/seon.source.edn:11` still combines hex format and entity identity. Reuses negate identity while other 64-character declarations do not guarantee hex. This is existing schema-design-review N35/N36, not a new discovery; handle within the canonical scalar/identity cleanup class.

## Dependencies and authority review

Read end to end: supplied repository AGENTS authority; data-modeling, datahike and data-oriented-clojure skills; `docs/seon/architecture/data-modeling-guide.md`; `docs/seon/architecture/data-model.md`; current `docs/prds/context-generation/plan/README.md`; the 2026-09-17 Datahike modeling study; the 2026-09-16 deletion study; reset-schema-recommendations; schema-design-review; entity-schema-vs-pulled-shape; write-admission. The latter four are the additional literature assigned to this lane. Historical recommendations were treated as dated proposals, not automatic current law.

Dependency seams checked against vendored source: `reference-code/datahike/src/datahike/db/transaction.cljc:998–1015` sweeps incoming refs and returns component retractions; `reference-code/datahike/src/datahike/pull_api.cljc:16` declares the default many limit of 1000. First-party final validation uses EAVT/AVET instead of treating wildcard pull as completeness proof (`src/seon/db.clj:3485`). The logical codec validates decoded polymorphic values through the carried projection (`src/seon/schema/datahike.clj:377`), so mixed logical unions are not automatically scalar-schema holes.

Historical corrections: deleted AST families must not be audited as current declarations; required ref sweeps are checked by final-report validation; current named edges use symbols; absence of a many datom never proves a completed empty observation. Guide references saying the modeling study is still absent, and the old SCI record bypass description, should be corrected in their owning authorities.

## Read-only live evidence

One JVM MCP evaluation, explicit default connection via `seon.db/db`, no writes or definitions, 29 ms. Carried projection present. Full returned envelope:

```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"seon.dev.mcp/namespace\":\"user\",\"seon.dev.mcp/session-id\":\"schema-audit-c\",\"seon.dev.mcp/cluster\":\"default\",\"seon.dev.mcp/mode\":\"jvm\",\"seon.dev.mcp/events\":[{\"tag\":\"ret\",\"val\":{\"seon.dev.mcp/value\":{\"auto-contradiction\":true,\"basis\":536871517,\"child-empty\":true,\"projection?\":true},\"seon.dev.mcp/windowed?\":false},\"ns\":\"user\",\"ms\":29}],\"seon.dev.mcp/runtime\":\"clj\",\"seon.dev.mcp/cluster-state\":\"alive\",\"seon.dev.mcp/form\":\"(let [db (seon.db/db (seon.operator/connection \\\"default\\\")) p (seon.db/carried-projection db)] {:basis (:max-tx db) :projection? (boolean p) :child-empty ((seon.schema/projection-validator p :seon.schema.shape.child/row) {:seon.schema.shape.child/id \\\"audit-only\\\" :seon.schema.shape.child/order -1}) :auto-contradiction ((seon.schema/projection-validator p :seon.test.accretion.auto/entity) {:seon.test.accretion.auto/seed 1 :seon.test.accretion.auto/case-count 0 :seon.test.accretion.auto/executed-count 9 :seon.test.accretion.auto/status :passed :seon.test.accretion.auto/skip-reason \\\"skipped\\\"})})\",\"seon.dev.mcp/root\":\"/Users/sean/src/seon\"}"
    }
  ]
}
```

## Complete assigned coverage

Every resource below was read in full; hashes record completion bytes. Schemas not ranked above are reviewed, not certified defect-free. Relational invariants require writer-level falsifiers; this audit does not mistake permissive syntax alone for a production failure.



| Resource | SHA-256 |
|---|---|
| `resources/seon/schemas/seon.ns.import.edn` | `83a647113145e70a777e46ac701046ed812f6f96a6005d6f7af6c13a3ff7c78d` |
| `resources/seon/schemas/seon.ns.refer.edn` | `6ae0a0f17791220711e87a0b3927487e3783caf5fd8847a0396235f5a1075548` |
| `resources/seon/schemas/seon.operator.claim.edn` | `15ad0f6d34990f8a6bc27c39b127ec7c61189b36169b94cb94b0854e260a4ff1` |
| `resources/seon/schemas/seon.operator.cleanup.edn` | `a745583b70a308673c6e38e4cf583f1d9f7ed2d28aa4c620bef95d7e23d9c758` |
| `resources/seon/schemas/seon.operator.cluster-cleanup.edn` | `f1b8c5e96db88c86fd16cd2e588bb463b2a3f47a5b25df69757f66a00fa20429` |
| `resources/seon/schemas/seon.operator.collect.edn` | `8f64db43b3e3b63ddfded1eccd94252e7035ca0fba69910afd832a6148968bda` |
| `resources/seon/schemas/seon.operator.edn` | `4837fcfbcefbb8ddbb9261ab3e5fbdff01682928c86e0ff750c1646a0fd36f90` |
| `resources/seon/schemas/seon.operator.footprint.edn` | `fc232e99f3794a9e4b844e5d9e0c68564ba0b7dfdbdeebbde2438ef2200d0dca` |
| `resources/seon/schemas/seon.operator.log.edn` | `23b6a7b4f7d2fea29a1369a855f77f750e18b351010c07d6fe3c514023e444dc` |
| `resources/seon/schemas/seon.operator.process-census.edn` | `ca1752529fb4adfed61e70c6b3e3b812e85fb615047916edf89d587632d0a0c5` |
| `resources/seon/schemas/seon.operator.process-record.edn` | `ce7625a20bb526ca2b7568ca4e07f627ccfc541cea449575638f9d91d446c89a` |
| `resources/seon/schemas/seon.operator.reap.edn` | `a4bfa143e797322bbe97c31e5fd0fcf4cff9d3c9a06aaac06275ffd4fa085cfb` |
| `resources/seon/schemas/seon.plan.edn` | `ee5c0306782de5c972b19c002f15c370d6d22068e76baae31932c63212116d0d` |
| `resources/seon/schemas/seon.print.edn` | `a4afb5e847a37e009aa94d846c4897602c540f5cf62c4e66fcba1d1f142f5572` |
| `resources/seon/schemas/seon.problems.edn` | `7eb61f54d0fa328834a422f06b8b3521100fae0032e7ab2defd9f9aaf6705c29` |
| `resources/seon/schemas/seon.program.edn` | `039c14f9147936127da19d34c98e101369396ed306a1d88f3c784dd6996226c1` |
| `resources/seon/schemas/seon.reconcile.edn` | `0ec79e42aaa9f9ed2725a925dccf0a1b1bd6a3b92930f563c74181c354c6f224` |
| `resources/seon/schemas/seon.render.block.edn` | `1458214633a419270fa130b955ff12c4974aa2fa1d986a1b0b65b8d3c316e089` |
| `resources/seon/schemas/seon.render.call.edn` | `cfc7f22dc8e905d30affca29f4dfdb9035b340d1b188c6c5699dddcaccb76e0f` |
| `resources/seon/schemas/seon.render.cost.edn` | `baa4bfc24264667b409726fd5de0d8cabf7f1ca115831fa7e38e5cbf309a8279` |
| `resources/seon/schemas/seon.render.data.edn` | `d405ed5b21e85df35008a8853ffd048b1b1c2b50467f87c0d197c146730469fb` |
| `resources/seon/schemas/seon.render.debug.edn` | `892cfd10732e29d5d46bd8f0c8f21ee665d95dbec107029812c87f3934a5ac61` |
| `resources/seon/schemas/seon.render.edn` | `e68245f74b83cd8cba3f88cff12732a2549976307b27d1bf267652390a7e39a4` |
| `resources/seon/schemas/seon.render.hiccup.edn` | `69c854529875f40beea1ef96beff9a2930e9bcf7ee2e5884209bb793888f548a` |
| `resources/seon/schemas/seon.render.history.edn` | `3105bd2e9ba14680257dee45f781bb74d2d7d835a81a20eb94453f79b8b8ef9d` |
| `resources/seon/schemas/seon.render.lint.edn` | `c0e9de180339cdb294c7b2fb7c477bfa87a144d57d3f26da1cef6b296639a8da` |
| `resources/seon/schemas/seon.render.package.edn` | `ed8e75d384dfea6792e85ffeb8fe09c45f744fa8b540827bdfab812c322b677f` |
| `resources/seon/schemas/seon.render.profile.edn` | `1ed87d20de4b5ae83c953fd13c1f41045a4086a330066ed49a74095b92635eb7` |
| `resources/seon/schemas/seon.render.transcript.edn` | `7968d85001a4df7902ca2df84e5083afd395941804df21c05fd97680d117d51c` |
| `resources/seon/schemas/seon.render.unknown.edn` | `4cd8122c11d049ac6b8e6c6c64e4955f7f44dce758c65c6ddb24d6d3333c2e4b` |
| `resources/seon/schemas/seon.render.value.edn` | `159c64ac0ea35dbdc5987644910716063fb3fa0ec39265d6752bf32b182f22ae` |
| `resources/seon/schemas/seon.render.walk.edn` | `123a6f2ebfa556c4b58eb5a70e4e3b87c477f6a46b26c45cc97f197cdad6cc45` |
| `resources/seon/schemas/seon.render.web.edn` | `ab266d1b06db8e71cbfbb9fa4470fe5bbbf37a48c4bc49e26292cc654d99a1ae` |
| `resources/seon/schemas/seon.repl.edn` | `75e6f617f737c7901837ee4e6d116f2597135aa9e8f6c5197117a3013152c0b1` |
| `resources/seon/schemas/seon.runtime.edn` | `623ec397f54df9973ce8e0f6afc157ce68eaaf76371cb79aeb1b7a75cb3469c9` |
| `resources/seon/schemas/seon.schedule.edn` | `1fa26bbd3b270624b44cfd8d89627c642604f892d4aaa59ad5540c0b50c3e640` |
| `resources/seon/schemas/seon.schedule.fire.edn` | `eaeb4b049de265481ba3dc242fba3475da0887274a7657c3299776d25b7c7c33` |
| `resources/seon/schemas/seon.schedule.task.edn` | `5db599a9273744355e3672ffaaa4ab740c28172b3498138798ebda67d2404a1f` |
| `resources/seon/schemas/seon.schema.admission.edn` | `e1916bf9788e2fb6d34c04c75fd225951e6c2377b9190573bfab314513f93bf6` |
| `resources/seon/schemas/seon.schema.datahike.edn` | `f90a33f357bcbebf57c60237b72b1da4e8587fb9e5cc044f089ea0dec593fb41` |
| `resources/seon/schemas/seon.schema.edn` | `dc973eabf0c87d463eff57d8e980401f728907d14f6be290216f05aa6e0abc81` |
| `resources/seon/schemas/seon.schema.edn.edn` | `1ec825482e6fb07f37dbcf0ae187ee6c4f12d02476ca5fe4f05693f5a0497522` |
| `resources/seon/schemas/seon.schema.map-entry.edn` | `25b36d39706bceb7df2b2f81bd3c336d9ed54ee7ae751e8e03a5ee7087d107ce` |
| `resources/seon/schemas/seon.schema.shape.child.edn` | `52c47b307646bcc984764f78de13cd8ae96ca576a354400259f6095143216d71` |
| `resources/seon/schemas/seon.schema.shape.edn` | `02bd635975c8ec0ff119109cc7c15edda929ccf3301aa42d37bf61efdc062060` |
| `resources/seon/schemas/seon.schema.shape.entry.edn` | `e3af4f1ba0fb521f87dc355d31c4e0cc7185ce4bb15fa10d06bf34e5c3639ea1` |
| `resources/seon/schemas/seon.sci.admit.edn` | `b938c5f315866dd1285d982716a0e72aaa184a689d7423da8562d45c6f689b63` |
| `resources/seon/schemas/seon.sci.binding.edn` | `f18c7ae0c1e649ea4ed2759f618f9175cba4d6ff6f1f5018ac27187ec5289f3b` |
| `resources/seon/schemas/seon.sci.eval.edn` | `e42abe2996f539e6bd7195d9a00b241934041ff6ceaf10634ef2f29b5ec6dc3b` |
| `resources/seon/schemas/seon.sci.kernel.edn` | `af0507b5b8f49bbbe33b779c6f41e4e821cd090f08b54c7df37d7aec3244ad5e` |
| `resources/seon/schemas/seon.sci.reader.edn` | `2c65c5017bb7189c960fabc416614351a5ffa71236f6424ee9cf234b90fe34f7` |
| `resources/seon/schemas/seon.search.edn` | `fc0bcd7c271db04dad11fbc25c35e0e94aca7c01583cf4c2b1b5f301442b9890` |
| `resources/seon/schemas/seon.source.edn` | `539413578035f5ef8efa2800bc65525b1a3ad6216c5dcbce87c9f1f597d12712` |
| `resources/seon/schemas/seon.store.edn` | `7bdc9accea02eb2e659be0e59959abcf1c72e6f3e0db323bea1b01a024a66d20` |
| `resources/seon/schemas/seon.test.accretion.auto.edn` | `08fedb5bf9bd7bd4767d3ff5de6b6d4e6173ec9a7267cd70d5226ceba84f947b` |
| `resources/seon/schemas/seon.test.accretion.edn` | `115dc5e5f1eb2fc97998aae4b8b79d5248abdbd70304775dc5857baa876182fc` |
| `resources/seon/schemas/seon.test.accretion.failure.edn` | `c0c5175ebbfcc4d4002c6ca1947e7fbae812230c1ce0956a30006d529eb7d000` |
| `resources/seon/schemas/seon.test.accretion.group.edn` | `fcb9ad67d76db260e0c5edab38b176546dd23ae21ff58348c704db1f9ce8a39f` |
| `resources/seon/schemas/seon.test.check.edn` | `855b11a1f16a5aa1057fabb28f9e2b46bf71a3a01b8c2dd67ca329122ad0b8bf` |
| `resources/seon/schemas/seon.test.edn` | `1ca34d602f9047f2b104856d06a2fd0b7c7a0ef06374473b12e01870177182e5` |
| `resources/seon/schemas/seon.test.failure.edn` | `e6df1adb2c757773a84177d4f98e7b9bdf9e5035f5d6d947e459dccf72e2b4c4` |
| `resources/seon/schemas/seon.test.member.edn` | `a7d5791a50e41d18c5b66b9791ad40931093585f0a5242cca71e90bf292cd57f` |
| `resources/seon/schemas/seon.test.report.edn` | `0ed509bf65945b38130421f804f24a24d70b6a0b282eb974cd155a409ebdbf76` |
| `resources/seon/schemas/seon.test.run.edn` | `d5a24444a922191aed5b7bddb6c5251a329d20585f1c5a6885e0dc9b432288c4` |
| `resources/seon/schemas/seon.test.runner.edn` | `e7efe2079d9b4aa41a7b08bd5a41f34c53129a9601764cda58795f73c35587eb` |
| `resources/seon/schemas/seon.test.selection.edn` | `32323c679db6f2b3d1df0f0902eddbf392acc35834265804c19f14bbecee3801` |
| `resources/seon/schemas/seon.turn.edn` | `127f5c24aa0846f53aa0b9cdfc7ea6fa56856ce2f468a7e6be7e2c70f786004c` |
| `resources/seon/schemas/seon.turn.loop.edn` | `c073252674f1364994c75915578cdfa0862c699d7df4ba28b859ce067b9aad61` |
| `resources/seon/schemas/seon.turn.work.edn` | `b59699bc501a2342dadee50b6bfaeeeee9cf606becef592db5f1e473a9ad4f9f` |
| `resources/seon/schemas/seon.wake.edn` | `b67790aed85bc3732cfb400ff22b92777e2fb1985343f592d80a0dab3e35304a` |

Coverage: 70 / 70 assigned resources.
