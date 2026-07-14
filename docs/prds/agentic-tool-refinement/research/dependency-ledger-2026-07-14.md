---
type: research
status: active
tags: [research, agent]
---

# Agentic tool refinement dependency ledger — 2026-07-14

## Selected dependencies and mechanisms

| Dependency or mechanism | Identity | Source and current call sites read | Executable probe and acceptance |
|---|---|---|---|
| Inspect AI | `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; `src-inspect-ai/pyproject.toml` local path | `reference-code/inspect-ai/`; `src-inspect-ai/src/seon_inspect/`; exact task/model/sandbox files remain to be selected before a scored baseline | Pending inventory. Acceptance: one pinned framework identity in run provenance and all simulations through Inspect. |
| inspect-evals | `97c99f5f6507fc5d1449fe3247f267d591f64350` | `reference-code/inspect-evals/`; catalog and selected benchmark implementations remain to be read before freezing membership | Pending inventory. Acceptance: upstream dataset and scorer unchanged with deterministic split membership. |
| Datahike | `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike/`; `src/seon/db.cljs`; `src/seon/db/replica.cljs`; database call sites in context rendering | Fresh writer build failed while the selected source was absent, then built from the pinned source. Context queries execute against the isolated replica. |
| Malli | `80138076960e7820523b4cb932c5b5d1936d4e7f`; application coordinate `0.20.0` | `reference-code/malli/`; `src/seon/schema.cljc`; indexed `:seon.schema/form` and `:seon.fn/spec` consumed by `seon.agent.ctx.namespaces` | Live compact cards expose named input and output contracts plus referenced schema closure. Acceptance: chosen task functions are callable from that surface without hidden examples. |
| ClojureScript | vendored `946d75f3483c0c8e784e6668bff2c71a25619a77`; runtime `1.12.145` | `reference-code/clojurescript/`; `src/seon/eval.cljs`; self-host and MCP behavior from the ClojureScript skill | Cluster-qualified MCP evaluation returned the selected pod cwd, PID, current namespace, home requires, and rendered context. |
| SCI | `b4917436550c857a18b8f6a4a8b5b26356acc2c4`; runtime coordinate `0.13.53` | `reference-code/sci/`; exact sandbox call path remains to be read before selecting tasks that grant arbitrary code | Pending smallest containment probe. Acceptance: infrastructure failures remain separate and no benchmark-specific answer adapter crosses the sandbox boundary. |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/`; selected transitively by the root writer/CLJS bases | Writer and pod admission succeeded on the isolated cluster; restart/read-back proof remains pending. |
| Namespace context | Seon `fc41c0ec` baseline | `config/system.edn`; `config/acme.edn`; `src/seon/agent/home.cljs`; `src/seon/agent/ctx.cljs`; `src/seon/agent/ctx/namespaces.cljs`; `test/seon/agent/ctx/namespaces_test.cljs` | Fresh ordinary-agent render: namespaces 21,839 tokens; all other blocks under 700 combined. Acceptance for the first unit: preserve complete chosen contracts while measuring and reducing only proven duplication/noise. |
| ACME operator and MCP | Seon `fc41c0ec` plus lane change | `bin/acme`; `script/seon/dev/mcp.clj`; `src/seon/dev/runtime_id.cljc`; operator/MCP tests | Failure: `acme/root` returned cwd `/Users/sean/src/seon`, PID 84892. After the override, `acme-agentic-tool-refinement/root` returned this worktree and PID 74241. |

## First falsifiable unit

Failure: two concurrently live downstream clusters both advertise `acme/root`,
so a development client rooted in the main checkout can select the wrong pod.
The wrapper also prevented a caller from supplying a distinct cluster basename.

Acceptance evidence: `SEON_CLUSTER_DIR=data/clusters/acme-agentic-tool-refinement
SEON_PORT=8094 bin/acme up` reaches ready, structured status publishes cluster
`acme-agentic-tool-refinement`, and the repository MCP server returns this
worktree's cwd and pod PID for `acme-agentic-tool-refinement/root`.

## Next dependency unit

Before freezing or running a model sample, read the exact Inspect task/solver,
model provider client, inspect-evals dataset/scorer, and sandbox/Docker source
selected for that sample. Record their identities, first-party tests, smallest
executable probe, failure, and acceptance evidence here or in a linked dated
research file.
