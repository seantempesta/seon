---
type: research
status: active
tags: [research, architecture]
---

# Extension-hygiene audit under the clarified cljc-maximization rule (2026-07-22)

Orchestrator-accepted. Verdict: ZERO wrong-suffix .cljc files — every .cljc
is honest under the owner-clarified rule (portable canon with one consumer
is correct). The real unit is the INVERSE: the Wave-1 promotion bundle
(~20 already-portable .cljs/.clj files → .cljc, list stale in places —
re-derive at execution) + repairs to six portable .cljc owners with
unguarded CLJS behavior. SEQUENCED AFTER the namespace-organization
decision sheet (promote after moves, not before). uds.cljc bb/JVM cleanup
is a separate transport-edge item.

Under the clarified owner policy, there are **7 WRONG-SUFFIX/false-portability files**, **37 KEEP-CLJC files**, and **no PROSPECTIVE category is needed**. The previous four `.cljs` rename recommendations are withdrawn: all four contain portable Clojure and should remain `.cljc`.

Tier legend: **C** = CLJS pod/child, **J** = JVM writer/host, **B** = babashka.

| Verdict | File | Reader conditionals | Consumers today / correction |
|---|---|---|---|
| **WRONG-SUFFIX — repair in place** | `src/my/canvas.cljc` | `:clj`, `:cljs` at [line 42](/Users/sean/src/seon/src/my/canvas.cljc:42) | **C/J**, but unguarded CLJS `await` begins at [line 92](/Users/sean/src/seon/src/my/canvas.cljc:92). Mostly portable: preserve `.cljc`, make async acquisition a guarded/explicit edge. |
| **WRONG-SUFFIX — repair in place** | `src/my/kb.cljc` | `:clj`, `:cljs` at [lines 179–186](/Users/sean/src/seon/src/my/kb.cljc:179) | **C/J**, but unguarded `await` begins at [line 188](/Users/sean/src/seon/src/my/kb.cljc:188). Preserve `.cljc`; repair the async/database edge. |
| **WRONG-SUFFIX — repair in place** | `src/my/plan.cljc` | `:clj`, `:cljs`, e.g. [line 878](/Users/sean/src/seon/src/my/plan.cljc:878) | **C/J**, but unguarded `await` begins at [line 286](/Users/sean/src/seon/src/my/plan.cljc:286). Preserve `.cljc`; extract or guard acquisition/effect calls. |
| **WRONG-SUFFIX — repair in place** | `src/my/plan/internal.cljc` | `:clj`, `:cljs` catch at [line 1240](/Users/sean/src/seon/src/my/plan/internal.cljc:1240) | **C/J**, but `cljs.reader` is unconditional at [line 10](/Users/sean/src/seon/src/my/plan/internal.cljc:10), and unguarded `await` begins at [line 1197](/Users/sean/src/seon/src/my/plan/internal.cljc:1197). Preserve `.cljc`; branch the reader and isolate async acquisition. |
| **WRONG-SUFFIX — repair in place** | `src/my/skills.cljc` | `:clj`, `:cljs` at [line 110](/Users/sean/src/seon/src/my/skills.cljc:110) | **C/J**, but unguarded `await` starts at [line 196](/Users/sean/src/seon/src/my/skills.cljc:196) and `js/Math.round` is unconditional at [line 306](/Users/sean/src/seon/src/my/skills.cljc:306). Preserve `.cljc`; use a narrow rounding branch and portable effect seam. |
| **WRONG-SUFFIX — repair in place** | `src/seon/repair/candidates.cljc` | `:clj`, `:cljs` at [line 170](/Users/sean/src/seon/src/seon/repair/candidates.cljc:170) | **C/J**, but `pick-winner` contains unconditional CLJS `await` at [line 182](/Users/sean/src/seon/src/seon/repair/candidates.cljc:182). Preserve the portable ranking canon; move or guard the async trial executor. |
| **WRONG-SUFFIX — reader-conditional-forced repair** | `src/seon/db/transport/uds.cljc` | `:bb`, `:clj`, e.g. [lines 17–36](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:17) | **J/B**; `.cljc` is required for JVM+babashka branches, but unconditional Java imports and interop begin at [lines 12–22](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:12), with further unconditional Java references at [lines 193–207](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:193). Keep `.cljc` because reader conditionals are required, but make platform ownership explicit under reader tags. |
| KEEP-CLJC | `src/my/blob/schema.cljc` | none | Portable schema data; **C/J/B** through [blob.cljs:18](/Users/sean/src/seon/src/my/blob.cljs:18), [db/server.clj:21](/Users/sean/src/seon/src/seon/db/server.clj:21), [restore_state.clj:8](/Users/sean/src/seon/script/seon/dev/restore_state.clj:8). |
| KEEP-CLJC | `src/my/plan/generation.cljc` | none | Portable definitions; **C/J** via [my/plan.cljc:11](/Users/sean/src/seon/src/my/plan.cljc:11) and the SCI source loader. |
| KEEP-CLJC | `src/seon/agent/ctx/ns_name.cljc` | none | Pure predicates; **C/J** at [namespaces.cljs:13](/Users/sean/src/seon/src/seon/agent/ctx/namespaces.cljs:13), [docstring.clj:52](/Users/sean/src/seon/src/seon/dev/docstring.clj:52). |
| KEEP-CLJC | `src/seon/agent/fs/match.cljc` | none | Pure portable matching despite **C-only today** at [agent/fs.cljs:12](/Users/sean/src/seon/src/seon/agent/fs.cljs:12). Previous rename verdict withdrawn. |
| KEEP-CLJC | `src/seon/ai/provider.cljc` | none | Portable provider data; **C/J** at [ai.cljs:52](/Users/sean/src/seon/src/seon/ai.cljs:52), [host/context.clj:44](/Users/sean/src/seon/src/seon/host/context.clj:44). |
| KEEP-CLJC | `src/seon/ai/tokens.cljc` | `:clj`, `:cljs` at [lines 69–77](/Users/sean/src/seon/src/seon/ai/tokens.cljc:69) | Proper leaf branches; **C/J** at [eval.cljs:55](/Users/sean/src/seon/src/seon/eval.cljs:55), [host/context.clj:45](/Users/sean/src/seon/src/seon/host/context.clj:45). |
| KEEP-CLJC | `src/seon/client/schema.cljc` | none | Portable schema data; **C/J/B** transitively through [launch.cljc:9](/Users/sean/src/seon/src/seon/launch.cljc:9). |
| KEEP-CLJC | `src/seon/code.cljc` | none | Pure tagged-code operations; **C-only today** at [client.cljs:168](/Users/sean/src/seon/src/seon/client.cljs:168). Previous rename verdict withdrawn. |
| KEEP-CLJC | `src/seon/config/resolve.cljc` | `:bb`, `:cljs`, `:default` at [lines 3–6](/Users/sean/src/seon/src/seon/config/resolve.cljc:3) | Proper conditional resolution; **C/J/B** at [client.cljs:43](/Users/sean/src/seon/src/seon/client.cljs:43), [db/server.clj:13](/Users/sean/src/seon/src/seon/db/server.clj:13), [config.clj:8](/Users/sean/src/seon/script/seon/dev/config.clj:8). |
| KEEP-CLJC | `src/seon/content_hash.cljc` | `:clj`, `:cljs` at [lines 5–19](/Users/sean/src/seon/src/seon/content_hash.cljc:5) | Proper JVM/Node hash branches; **C/J**. |
| KEEP-CLJC | `src/seon/db/branch.cljc` | `:bb`, `:clj` at [lines 7–42](/Users/sean/src/seon/src/seon/db/branch.cljc:7) | Datahike-specific operations are guarded; **C/J/B** at [db.cljs:10](/Users/sean/src/seon/src/seon/db.cljs:10), [db/writer.clj:23](/Users/sean/src/seon/src/seon/db/writer.clj:23), [branch.clj:5](/Users/sean/src/seon/script/seon/dev/branch.clj:5). |
| KEEP-CLJC | `src/seon/db/id.cljc` | `:bb`, `:clj`, `:cljs` at [lines 12–24](/Users/sean/src/seon/src/seon/db/id.cljc:12) | Genuine multi-platform API; **C/J/B**. |
| KEEP-CLJC | `src/seon/db/id/schema.cljc` | none | Portable schema canon; **C/J/B** through [db/id.cljc:14](/Users/sean/src/seon/src/seon/db/id.cljc:14). |
| KEEP-CLJC | `src/seon/db/process.cljc` | none | Portable provenance data; **C/J** at [client.cljs:72](/Users/sean/src/seon/src/seon/client.cljs:72), [db/writer.clj:27](/Users/sean/src/seon/src/seon/db/writer.clj:27). |
| KEEP-CLJC | `src/seon/db/protocol.cljc` | `:bb`, `:clj`, `:cljs`, `:default` at [lines 13–14](/Users/sean/src/seon/src/seon/db/protocol.cljc:13) | Genuine shared protocol; **C/J/B**. |
| KEEP-CLJC | `src/seon/db/restore.cljc` | `:cljs` bands beginning at [line 13](/Users/sean/src/seon/src/seon/db/restore.cljc:13) | Large asymmetric implementation, but platform code is guarded. Portable proof/schema canon remains shared; **C/J/B**. |
| KEEP-CLJC | `src/seon/db/restore/schema.cljc` | none | Portable restore schemas; **C/J/B** via [db/restore.cljc:10](/Users/sean/src/seon/src/seon/db/restore.cljc:10). |
| KEEP-CLJC | `src/seon/db/restore_admin/schema.cljc` | none | Portable schemas; **C/J/B** through [launch.cljc:13](/Users/sean/src/seon/src/seon/launch.cljc:13). |
| KEEP-CLJC | `src/seon/dev/restore/schema.cljc` | none | Portable schemas; **C/J/B** through [launch.cljc:14](/Users/sean/src/seon/src/seon/launch.cljc:14). |
| KEEP-CLJC | `src/seon/dev/runtime_id.cljc` | none | Portable runtime addressing; **C/B** at [client.cljs:224](/Users/sean/src/seon/src/seon/client.cljs:224), [mcp.clj:49](/Users/sean/src/seon/script/seon/dev/mcp.clj:49). |
| KEEP-CLJC | `src/seon/diffusion/grammar.cljc` | none | Pure portable grammar; **C/B** at [eval.cljs:64](/Users/sean/src/seon/src/seon/eval.cljs:64), [bin/oracle-server:53](/Users/sean/src/seon/bin/oracle-server:53). |
| KEEP-CLJC | `src/seon/error.cljc` | `:clj`, `:cljs` at [lines 36–44](/Users/sean/src/seon/src/seon/error.cljc:36) | Proper error/stack/runtime branches; **C/J**. |
| KEEP-CLJC | `src/seon/error/instrument.cljc` | `:clj`, `:cljs` at [lines 149–161](/Users/sean/src/seon/src/seon/error/instrument.cljc:149) | Proper leaf branches; **C/J**. |
| KEEP-CLJC | `src/seon/eval/receipt.cljc` | none | Portable receipt state and transaction data; **C-only today** at [eval.cljs:68](/Users/sean/src/seon/src/seon/eval.cljs:68). Previous rename verdict withdrawn. |
| KEEP-CLJC | `src/seon/instrument.cljc` | `:cljs` bands beginning at [line 15](/Users/sean/src/seon/src/seon/instrument.cljc:15) | Effectively CLJS-only today, but every platform-specific implementation is reader-guarded. Correct `.cljc` under the clarified rule. |
| KEEP-CLJC | `src/seon/launch.cljc` | `:cljs` at [lines 6–15](/Users/sean/src/seon/src/seon/launch.cljc:6), runtime tail at [line 529](/Users/sean/src/seon/src/seon/launch.cljc:529) | Portable descriptor canon plus properly guarded CLJS acquisition; **C/J/B**. |
| KEEP-CLJC | `src/seon/packages.cljc` | `:clj`, `:cljs` at [lines 5–6](/Users/sean/src/seon/src/seon/packages.cljc:5) | Proper EDN/numeric branches; **C/B**. |
| KEEP-CLJC | `src/seon/render/value.cljc` | `:clj`, `:cljs` at [lines 68–75](/Users/sean/src/seon/src/seon/render/value.cljc:68) | Genuine shared sampler with bounded platform branches; **C/J**. |
| KEEP-CLJC | `src/seon/repair.cljc` | `:clj`, `:cljs` catch at [line 188](/Users/sean/src/seon/src/seon/repair.cljc:188) | Portable repair transform; **C/J**. |
| KEEP-CLJC | `src/seon/repl/parse.cljc` | `:clj`, `:cljs` at [lines 797–798](/Users/sean/src/seon/src/seon/repl/parse.cljc:797) | Portable parser with proper exception branches; **C-only today** is acceptable. |
| KEEP-CLJC | `src/seon/retry.cljc` | `:clj`, `:cljs` at [lines 91–92](/Users/sean/src/seon/src/seon/retry.cljc:91) | Portable state machine with guarded CLJS executor; **C/J**. |
| KEEP-CLJC | `src/seon/runtime/lifecycle.cljc` | none | Portable lifecycle contract; **C/B** at [client.cljs:89](/Users/sean/src/seon/src/seon/client.cljs:89), [process.clj:15](/Users/sean/src/seon/script/seon/dev/process.clj:15). |
| KEEP-CLJC | `src/seon/schema.cljc` | `:clj`, `:cljs` at [lines 24–25](/Users/sean/src/seon/src/seon/schema.cljc:24) | Genuine shared registry; **C/J/B**. |
| KEEP-CLJC | `src/seon/schema/form.cljc` | none | Pure Malli-form inspection; **C/J** at [eval.cljs:78](/Users/sean/src/seon/src/seon/eval.cljs:78), [db/writer.clj:36](/Users/sean/src/seon/src/seon/db/writer.clj:36). |
| KEEP-CLJC | `src/seon/schema/internal.cljc` | `:clj`, `:cljs` catch at [line 97](/Users/sean/src/seon/src/seon/schema/internal.cljc:97) | Portable Malli mechanics; **C/J/B** through [schema.cljc:23](/Users/sean/src/seon/src/seon/schema.cljc:23). |
| KEEP-CLJC | `src/seon/time.cljc` | `:clj`, `:cljs` at [line 8](/Users/sean/src/seon/src/seon/time.cljc:8) | Correct leaf-level Date formatting branches; **C/J**. |
| KEEP-CLJC | `src/seon/ui/html.cljc` | none | Pure portable HTML serializer; **C-only today** at [render.cljs:39](/Users/sean/src/seon/src/seon/render.cljs:39). Previous rename verdict withdrawn. |

The JVM consumer for `src/my` is executable source loading, not aspirational prose: the SCI host scans `src/my` for `.clj[sc]` files ([host/context.clj:1123–1135](/Users/sean/src/seon/src/seon/host/context.clj:1123)) and evaluates portable definitions into synthetic namespaces ([host/context.clj:1275](/Users/sean/src/seon/src/seon/host/context.clj:1275), [line 1311](/Users/sean/src/seon/src/seon/host/context.clj:1311)).

### Inverse: Wave‑1 promotion candidates

Per the existing active audit—not recomputed—the `.clj`/`.cljs` files already pure or close enough for first-wave promotion are ([audit:321–336](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/cljc-portability-audit-2026-07-21.md:321)):

1. `src/seon/result.cljs`
2. `src/seon/items.cljs`
3. `src/seon/eval/internal.cljs`
4. `src/seon/agent/home.cljs`
5. `src/my/ui.cljs`
6. `src/my/data.cljs`
7. `src/seon/route.cljs`
8. `src/seon/web/view_unit.cljs`
9. `src/seon/ui/markdown.cljs`
10. `src/seon/ui/clojure.cljs`
11. `src/seon/ui/header.cljs`
12. `src/seon/render/schema.cljs`
13. `src/seon/render/chat.cljs`
14. `src/seon/render/surface.cljs`
15. `src/seon/render/handlers/fn.cljs`
16. `src/seon/render/handlers/ns.cljs`
17. `src/seon/render/handlers/schema.cljs`
18. `src/seon/render/handlers/test.cljs`
19. `src/seon/host/record.clj`
20. `src/seon/db/datahike/schema.clj`

Execution implication: there is **no mechanical `.cljc → .cljs` rename unit**. The useful unit is the Wave‑1 promotion bundle, followed by repairs to the six portable `.cljc` owners with unguarded CLJS behavior; `db/transport/uds.cljc` is a separate JVM/babashka reader-conditional cleanup because it is a transport edge.
