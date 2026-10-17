# Render input audit (2026-10-17)

Static reading only, no REPL requests. Lane `render-input-audit`, evidence for root.

## Method

- Inventory: every symbol attached as `:seon.render/ai`, `:seon.render/html` or `:seon.render/form` in `resources/seon/schemas/*.edn`. 123 distinct symbols; each one's source read from its defining file in `src/`.
- Merged inputs (`seon.render/producer-argument`, `src/seon/render.clj:225`): the request's declared members (`request-members`), `:seon.db/db` (from `render-argument`, render.clj:197), `:seon.render/profile`, `:seon.render.data/total`, every qualified attribute of the value, and `:seon.render/value` holding the whole value. Any map argument therefore carries `:seon.db/db` and `:seon.render/value` beside its own keys.
- Classes: **A** reads only named keys; **B** iterates generically over the whole argument or a fallback to it; **C** calls `pr-str`, `str` on a collection, `prn` or a text printer.
- Count: 123 renders. A: 110. B: 4. B/C: 3. C: 6.

## Table

| render | face | class | evidence form | can reach merged inputs |
|---|---|---|---|---|
| `seon.background/render-ai` | ai | A | src/seon/background.clj:11 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.background/render-html` | html | A | src/seon/background.clj:27 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/render-ai` | ai | A | src/seon/error.clj:2371 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/render-html` | html | A | src/seon/error.clj:2559 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `my.program/render-source-ai` | ai | A | src/my/program.clj:918 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `my.turn/usage-form` | form | A | src/my/turn.clj:20 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.agent/render-identity-ai` | ai | A | src/seon/cluster/agent.clj:302 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.agent/render-identity-html` | html | A | src/seon/cluster/agent.clj:320 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.agent/identity-form` | form | A | src/seon/cluster/agent.clj:271 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.agent/render-creation-ai` | ai | A | src/seon/cluster/agent.clj:427 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.agent/render-creation-html` | html | A | src/seon/cluster/agent.clj:435 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/agent-namespace-ai` | ai | A | src/seon/render/ns.clj:290 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/agent-namespace-form` | form | A | src/seon/render/ns.clj:311 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.agent/render-situation-ai` | ai | A | src/seon/cluster/agent.clj:392 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.agent/situation-form` | form | A | src/seon/cluster/agent.clj:257 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.agent/render-settings-ai` | ai | A | src/seon/agent.clj:159 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.agent/render-settings-html` | html | B/C | src/seon/agent.clj:177 · nil-database branch: `(set (keys (dissoc component :db/id)))` where `component` is `(or (:seon.render/value unit) unit)`; `display` stringifies values with `str` | only when `:seon.db/db` is absent and `:seon.render/value` is not a map (then the whole argument becomes the attribute list and map values go through `str`) |
| `seon.agent/render-settings-form` | form | A | src/seon/agent.clj:171 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.ai/attempt-ai` | ai | B | src/seon/ai.clj:96 · `(dissoc (:seon.render/value unit unit) ...)` in `attempt-without-private-provider-data` (ai.clj:81): fallback to the whole argument | no under current producer-argument (it always assocs `:seon.render/value`); yes if that key is absent, then `:seon.db/db` becomes the attempt value and is printed |
| `seon.ai/attempt-html` | html | B | src/seon/ai.clj:104 · same helper as attempt-ai (ai.clj:81) | same as attempt-ai |
| `seon.ai/provider-ai` | ai | A | src/seon/ai.clj:282 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.ai/provider-html` | html | A | src/seon/ai.clj:292 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.ai/model-ai` | ai | A | src/seon/ai.clj:216 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.ai/model-html` | html | A | src/seon/ai.clj:262 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.task/attempt-html` | html | A | src/seon/task.clj:1941 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.task/chain-task-html` | html | A | src/seon/task.clj:1968 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.task/chain-html` | html | A | src/seon/task.clj:1986 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster/render-ai` | ai | A | src/seon/cluster.clj:179 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster/render-html` | html | A | src/seon/cluster.clj:199 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster/render-form` | form | A | src/seon/cluster.clj:192 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.repl/render-ai` | ai | A | src/seon/repl.clj:572 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.repl/render-html` | html | A | src/seon/repl.clj:597 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.instruction/instruction-ai` | ai | A | src/seon/cluster/instruction.clj:66 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.instruction/instruction-html` | html | A | src/seon/cluster/instruction.clj:72 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.status/render-ai` | ai | A | src/seon/cluster/status.clj:167 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.status/render-html` | html | A | src/seon/cluster/status.clj:184 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.status/render-form` | form | A | src/seon/cluster/status.clj:178 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.context/current-state-ai` | ai | A | src/seon/context.clj:955 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.context/exchange-ai` | ai | A | src/seon/context.clj:996 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.context/exchange-html` | html | A | src/seon/context.clj:1070 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.batch/render-request-cost-ai` | ai | A | src/seon/batch.clj:225 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.batch/render-request-cost-html` | html | B | src/seon/batch.clj:255 · `(into {} (filter #(= "seon.cost" (namespace (key %)))) cost)`: iterates the whole argument, keeps only the `seon.cost` namespace | no: the merged `:seon.db/db`, `:seon.render/profile`, `:seon.render/value`, `:seon.render.data/total` are other namespaces. Guard is a namespace filter, not the declared members; the 26e73971a fix |
| `seon.render.value/render-database-identity-ai` | ai | C | src/seon/render/value.clj:376 · `(pr-str (:db-name unit))` (value.clj:385) | no: the declared identity projection, `:db-name` is a string |
| `seon.db/render-transaction-ai` | ai | A | src/seon/db.clj:6353 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.db/render-transaction-html` | html | A | src/seon/db.clj:6418 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.db/render-rejection-ai` | ai | A | src/seon/db.clj:6452 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.db/render-rejection-html` | html | A | src/seon/db.clj:6459 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/mcp-prose` | ai | A | src/seon/error.clj:2844 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.effect/render-ai` | ai | C | src/seon/effect.clj:68 · `(pr-str (:seon.effect/interrupted-at unit))` and `(pr-str (:seon.effect/opened-at unit))` (effect.clj:87, 90) | no: two instants of the effect entity |
| `seon.effect/render-html` | html | A | src/seon/effect.clj:98 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/finding-ai` | ai | A | src/seon/error.clj:2457 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/render-faults-ai` | ai | A | src/seon/error.clj:2726 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/render-faults-html` | html | A | src/seon/error.clj:2740 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/faults-form` | form | A | src/seon/error.clj:2700 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/ai-prose` | ai | A | error.clj:994 · named keys of the error value; notice arm via `notice-ai-prose` | no |
| `seon.error/render-chain-ai` | ai | A | src/seon/error.clj:2504 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/render-chain-html` | html | A | src/seon/error.clj:2518 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/render-form` | form | A | src/seon/error.clj:2551 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/function-ai` | ai | A | src/seon/render/ns.clj:180 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/function-html` | html | A | src/seon/render/ns.clj:196 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.health/render-ai` | ai | A | src/seon/health.clj:642 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.health/render-html` | html | A | src/seon/health.clj:703 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.bootstrap/render-help-ai` | ai | A | src/seon/bootstrap.clj:71 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.bootstrap/render-help-html` | html | A | src/seon/bootstrap.clj:104 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.message/render-inbox-ai` | ai | A | src/seon/cluster/message.clj:489 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.message/render-inbox-html` | html | A | src/seon/cluster/message.clj:561 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.message/inbox-form` | form | A | src/seon/cluster/message.clj:468 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.cluster.message/render-ai` | ai | A | message.clj:375 · named `get` reads, then `message-view` (no pr-str; `format-ai` at message.clj:317,321 is not on this path) | no |
| `seon.cluster.message/render-html` | html | C | src/seon/cluster/message.clj:386 · `data-link` builds `(pr-str identity-ref)` (message.clj:272) | no: an identity vector `[:seon.message/id ...]` |
| `seon.render.transcript/message-form` | form | A | src/seon/render/transcript.clj:671 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-alias-ai` | ai | A | src/seon/render/ns.clj:26 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-alias-html` | html | A | src/seon/render/ns.clj:39 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-alias-form` | form | A | src/seon/render/ns.clj:33 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-ai` | ai | A | src/seon/render/ns.clj:248 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-html` | html | A | src/seon/render/ns.clj:322 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/namespace-form` | form | A | src/seon/render/ns.clj:84 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-import-ai` | ai | A | src/seon/render/ns.clj:70 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-import-html` | html | A | src/seon/render/ns.clj:76 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-refer-ai` | ai | A | src/seon/render/ns.clj:47 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-refer-html` | html | A | src/seon/render/ns.clj:60 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/render-refer-form` | form | A | src/seon/render/ns.clj:54 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.print/render-elision-ai` | ai | A | src/seon/print.cljc:424 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.problems/missing-model-ai` | ai | A | src/seon/problems.clj:598 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.problems/missing-model-html` | html | A | src/seon/problems.clj:590 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `my.program/render-breakage-ai` | ai | A | src/my/program.clj:352 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.value/render-html` | html | A | src/seon/render/value.clj:1851 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.ref/render-doc-ai` | ai | B | src/seon/ref.clj:878 · `(or (:seon.render/value unit) unit)` then returns that entry with render keys dissoc'd (ref.clj:878) | yes when `:seon.render/value` is nil: the whole argument, including `:seon.db/db` and `:seon.render/profile`, is returned as data |
| `seon.ref/render-doc-html` | html | A | src/seon/ref.clj:888 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render/unknown-ai` | ai | B/C | src/seon/render.clj:1124 · `unknown-evidence-of` (render.clj:1116) filters the whole value (or unit) by namespace `seon.render.unknown`, then `admit/canonical-edn` prints it as EDN text | no: only `seon.render.unknown` keys reach the printer; the canonical-edn text is a second printer to review |
| `seon.render/unknown-html` | html | B/C | src/seon/render.clj:1135 · calls `unknown-ai` inside a `div` (render.clj:1135) | same as unknown-ai |
| `seon.render.sent/view-html` | html | A | src/seon/render/sent.clj:92 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.var/render-ai` | ai | A | src/seon/render/var.clj:148 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.var/render-html` | html | A | src/seon/render/var.clj:161 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.error/elision-prose` | ai | C | src/seon/error.clj:2805 · `evidence-text` (error.clj:2788) does `(pr-str value)` per evidence entry | no: evidence is the error's own `:seon.error/data` |
| `seon.error/elision-html` | html | A | src/seon/error.clj:2815 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.mcp/render-request-reply-ai` | ai | A | src/seon/mcp.clj:180 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.mcp/render-request-reply-html` | html | A | src/seon/mcp.clj:189 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.repl/render-directory-ai` | ai | A | src/seon/repl.clj:29 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.repl/render-directory-html` | html | A | src/seon/repl.clj:42 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.transcript/render-runtime-ai` | ai | A | src/seon/render/transcript.clj:967 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.transcript/render-runtime-html` | html | A | transcript.clj:1167 · named pulls; `runtime-turn-table` (transcript.clj:1041) does not reach `turn-header` | no |
| `seon.render.transcript/render-runtime-form` | form | A | src/seon/render/transcript.clj:987 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.schema/render-ai` | ai | A | src/seon/render/schema.clj:105 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.schema/render-html` | html | A | src/seon/render/schema.clj:148 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.schema/render-form` | form | A | src/seon/render/schema.clj:116 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/task-ai` | ai | A | src/seon/render/ns.clj:100 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/task-html` | html | A | src/seon/render/ns.clj:121 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/task-form` | form | A | src/seon/render/ns.clj:115 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/held-task-ai` | ai | A | src/seon/render/ns.clj:153 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/held-task-html` | html | A | src/seon/render/ns.clj:169 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.ns/held-task-form` | form | A | src/seon/render/ns.clj:163 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.test.accretion/render-ai` | ai | A | src/seon/test/accretion.clj:97 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.test.accretion/render-html` | html | A | src/seon/test/accretion.clj:124 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.test/render-ai` | ai | A | src/seon/render/test.clj:60 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.test/render-html` | html | A | src/seon/render/test.clj:175 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.test/render-form` | form | A | src/seon/render/test.clj:88 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.test/render-run-result-ai` | ai | A | src/seon/render/test.clj:127 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.test/render-run-ai` | ai | A | src/seon/render/test.clj:105 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.test/render-run-form` | form | A | src/seon/render/test.clj:142 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.transcript/render-run-ai` | ai | A | src/seon/render/transcript.clj:653 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.transcript/render-run-html` | html | C | transcript.clj:820 · `turn-header` (transcript.clj:724) does `(pr-str txInstant)` at line 740 | no: one transaction instant of the selected run |
| `seon.render.transcript/render-history-ai` | ai | A | src/seon/render/transcript.clj:924 · named keys of the unit or its `:seon.render/value` (destructured or `select-keys`/`get`); no whole-argument iteration | no |
| `seon.render.transcript/render-history-html` | html | C | transcript.clj:930 · `turn-header` (transcript.clj:724) does `(pr-str txInstant)` at line 740 | no: one transaction instant per turn |

## Caveats

- A is decided from the body of each render. Where a render calls a helper that was not read in full, the verdict rests on the render's own named reads: `seon.repl/text`, `seon.render.test/render-failure`, `seon.bootstrap/situation`, `seon.render.ns/public-functions`, `seon.cluster.message/message-view`, `seon.health/clues`, `seon.render.var/unit-status`, `seon.render.transcript/runtime-turn-table`, `seon.error/finding`, `seon.context/must-read-ai`, `seon.render.schema/selection` (partly), `seon.cluster.status/cluster-row` (read).
- Fallback pattern `(or (:seon.render/value unit) unit)` appears in many A renders. It is harmless only while the render destructures named keys; the B rows above are the ones that return or iterate the fallback.
- `producer-argument` always assocs `:seon.render/value`, so the `unit` fallback is reached only when that key is nil or a non-map scalar. Rows marked "yes" depend on that condition.

## Out-of-scope findings (not filed)

- `seon.error/scalar-text` (src/seon/error.clj:980-992) is a `pr-str` total printer for scalars; called from `src/seon/db.clj:4107` and `src/seon/instrument.clj:606`, neither read as a declared render path. Verify before any change.
- `seon.render.value/bounded-text` (src/seon/render/value.clj:115-127) is a `pr-str` text function on the value printer's floor. CLAUDE.md names `bounded-text` as a second printer. Owner review needed.
- `seon.print/literal` (src/seon/print.cljc:34-40) is the printer's own `pr-str` call, owned by the printer.
- `seon.render/render-default-ai-source` (src/seon/render.clj:1496-1502) returns `(pr-str form)` as a source text. It is not in the declared inventory, so it is not classed above.
- `seon.render/unknown-ai` (render.clj:1124) prints evidence with `admit/canonical-edn`. The shown text is EDN, not the value printer's output.
