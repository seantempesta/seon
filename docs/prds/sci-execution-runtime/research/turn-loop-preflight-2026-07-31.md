---
type: research
status: active
tags: [research, runtime, agent, boot, evidence]
---

# Turn-loop pre-flight — 2026-07-31

A live end-to-end verification of the turn loop ahead of the context-MVP
lane's real-agent drive: boot → agent creation → message → wake → run claim →
model call → reply parsing → eval → receipts → selective admission → context
capture → settlement → second run.

**Result: the chain is GREEN end to end, but it does not start from a clean
tree.** One committed defect refuses every cluster boot and had to be patched
in-process before anything else could be observed. With that one patch, every
remaining step behaved as designed, including the new SCI stable guard and
selective corpus admission. Two further defects were found in passing.

## What was verified against, and why it is not the working tree

The working tree churned continuously during this pass — a lane was mid-edit
in `seon/cluster/instruction.cljc`, `resources/seon/schema/instruction.edn`
and `seon/cluster/source.clj`, leaving `seed-rows` 0-arity while
`seon.cluster.clj:400` still called it with one argument. Measuring that state
would have measured another lane's half-landed change.

Verification therefore ran against a clean snapshot of committed **HEAD
`24aaacbac`** (`git archive HEAD` → `tmp/preflight-head`), in its **own
operator root** with its own process-root store, so nothing touched `default`
or any other lane's cluster. `reference-code/` and `.env` are symlinked into
that root; everything else is real files.

This isolation was necessary for a second reason: `bin/seon start` joins an
already-running JVM, and the live `default` JVM was 11+ hours old — it had
none of the day's namespaces (`seon.cluster.instruction`,
`seon.render.transcript`) and none of the sci guard work. A cluster started
into it would have proved yesterday's code.

Model provider: the sanctioned local Ollama server
(`local-provider-2026-07-28.md`), `qwen3.5:35b-a3b-coding-nvfp4`, reachable
and used for real — no stub was needed.

## Evidence table

| # | Step | Result | Evidence |
|---|---|---|---|
| 0 | `bin/seon init` (publish `current-src`) | GREEN at `24aaacbac`; **was broken 13:06–15:04** | `af2945f57` fixed it. Before that fix, `source-roots` (`c189a3d12`) declared the FILE `AGENTS.md` while `source/snapshot` refused non-directories: `"the declared source root …/AGENTS.md is not a directory"`. Published commit `6a6cf227-9180-5fe6-bf2d-0045a9ebe75f`, digest `90634fb4…`, 28 s |
| 1 | `start` — boot the tower | **BROKEN**, then GREEN with one patch | `seed-cluster!` refuses: `Nothing found for entity id [:seon.db.process/id "86232-…"]` → `The cluster population transaction was refused`. See [issue](../../../seon/issues/cluster-boot-refuses-its-own-process-provenance.md). Patched (`tmp/preflight-drive.clj`), the tower stands: store → fork → schema accretion (573 schema rows, 185 ns rows, 1 478 fn rows) → recovery → config → cluster seed → root agent → work launcher → arm → web on `:7896` |
| 2 | Agent creation (W1 path) | GREEN | `agent/creation-tx` committed at `t=536870927`: namespace `my.agents.scout` (e4365), agent `scout` (e4366) with `:seon.cluster.agent/namespace` → 4365 and `:seon.cluster.agent/cluster` → 4355, plus 11 seed blocks. Provenance present: `[536870927 :seon.db/process 4354]` |
| 3 | Message → wake → run | GREEN | `POST /agent/scout/message` (form field is **`content`**, same-origin required) → 204. Armer had already armed `scout` from facts (`armed → ("root" "scout")`). Run `6bb535d1…` opened 19:11:07.386 carrying custody `:seon.cluster.run/process "87611-1785525044805"` |
| 4 | The model call | GREEN (real, local) | Turn called Ollama and returned in ~40 s. First attempt on this cluster instead called the boot-time DeepSeek target despite a converged live `config apply` — see [issue](../../../seon/issues/armed-agent-graphs-freeze-config-dials-at-arm.md); passing the manifest at `start!` fixed it |
| 5 | Reply → forms | GREEN | Reply frozen as 3 ordered forms with plan-digest `9e5ad236…`, each `:seon.cluster.run.form/ns` → `my.agents.scout`. Synthetics through `reply/sources`: prose-heavy → 2 forms with prose preserved as `;` comments; fenced ```` ```clojure ````/```` ```clj ```` → 2 clean forms, fences stripped; pure prose → one comment-only source; unbalanced → `:seon.cluster.reply/unreadable` with the reader's own message |
| 6a | Eval + receipts | GREEN | Three receipts, one per ordinal: `(println …)` → `:seon.cluster.eval/output "Evaluated (+ 1 2) to: 3\n"`, result `nil`; `(defn add-long …)` → `{:seon.sci.admit/reference "sci.lang.Var" :seon.sci.admit/name "#'my.agents.scout/add-long"}`; `(my.run/complete …)` → `{:my.run/disposition :completed …}` |
| 6b | **Stable guard across evaluations** | GREEN | On ONE run ctx: form 1 `(defn spin [] (loop [] (recur)))` defined the var; form 2 `(spin)` interrupted at **1 506 ms** against a 1 500 ms limit with `:seon.error/kind :seon.sci.eval/time-limit`, `:seon.cluster.eval/interrupted-at #inst "2026-07-31T19:13:16.166Z"`; form 3 `(+ 40 2)` → `42`, so the ctx survives the interrupt. This is `7ed006f18` proven live |
| 6c | Selective admission | GREEN, both halves | The model's **uncontracted** `add-long` got a receipt and **no** corpus row. The **contracted** `(defn double-it "Twice x." {:malli/schema [:=> [:cat :int] :int]} …)` became `:seon.fn/sym "my.agents.scout/double-it"` with `:seon.fn/spec "[:=> [:cat :int] :int]"` |
| 7 | Context assembly (BEFORE artifact) | GREEN, and it is the MVP's gap | Captured before every provider call. Same 5 blocks every turn, growing only in `namespace-ai`. Text saved to `tmp/preflight-prompt-before.txt` (turn 2) and `tmp/preflight-prompt-turn3.txt` |
| 8 | Settlement + second run | GREEN | Run `6bb535d1…` closed 19:11:47.702, `:seon.cluster.agent/run` pointer absent afterwards. A second message opened a **fresh** run `52804cce…` (19:13:26.928 → 19:13:29.451, 2.5 s) with its own capture, forms, receipts and digest |

## The BEFORE artifact — context assembly at this commit

Three captures, one per run, from the live cluster:

| capture | bytes | est. tokens | contributions (position → projection) |
|---|---|---|---|
| `d7f63fd1…-context-536870930` | 1 704 | 424 | 0 identity-ai · 1 execution-ai · 2 peers-ai · 3 namespace-ai · 4 trigger-ai |
| `6bb535d1…-context-536870938` | 2 484 | 619 | same five |
| `52804cce…-context-536870949` | 3 001 | 747 | same five |

Per-block tokens at the third turn: identity 21, execution 79, peers 93,
**namespace 503**, trigger 48.

What that says for the MVP, stated plainly:

- the block set is **constant**; `settlement` and `assignments` are declared on
  the agent but omit themselves because their facts are absent — correct
  derive-don't-store behaviour, not a gap;
- there is **no transcript in the AI context**. Prior turns reach the agent
  only as neighbourhood prose inside `namespace-ai`, which is also the only
  block that grows (384 → 503 tokens across two turns) — the whole 76 %
  prompt growth is one walk widening;
- that walk is where the third defect surfaced: the agent's own context
  contained the literal text
  `The seon.error/ai-prose projection threw: Don't know how to create ISeq
  from: java.lang.Long`. See
  [issue](../../../seon/issues/error-render-puts-its-own-failure-in-agent-context.md);
- `seon.ai.tokens/estimate` is the only size the MVP should quote; character
  counts appear here only as capture bytes.

## Defects filed

| Issue | Severity | One line |
|---|---|---|
| [Seed the cluster's process row before naming it as provenance](../../../seon/issues/cluster-boot-refuses-its-own-process-provenance.md) | blocker | No cluster boots from a clean root; tx-meta refs its own tx-data entity |
| [Let a live config apply reach an armed agent graph](../../../seon/issues/armed-agent-graphs-freeze-config-dials-at-arm.md) | friction | Dials are snapshotted at arm; live `config apply` never reaches a turn |
| [Give `ai-prose` the ref shape the render walk hands it](../../../seon/issues/error-render-puts-its-own-failure-in-agent-context.md) | friction | A projection's own exception message is rendered into agent context |

## What the MVP lane should know before it drives

1. **Boot is blocked until the first issue lands.** The two-transaction shape
   in `tmp/preflight-drive.clj` is the fix, not a workaround to keep.
2. **Configure the provider in the `start` manifest**, never by `config apply`
   against a live cluster — and note that boot reconciles defaults + manifest,
   so an overlay applied live is overwritten on the next start.
   `:seon.config.ai/api-key-variable` is a REQUIRED dial and may not be marked
   absent even with `:seon.config.ai/no-auth true`
   (`:seon.config/required-absent`). Working overlay:
   `tmp/preflight-ollama.edn`.
3. **The inbound POST field is `content`** and the route enforces same origin —
   a request without an `Origin` header matching the served URL gets 403, and a
   wrongly-named field gets 422.
4. **Never start a scratch cluster into a long-lived JVM** if the point is to
   prove today's code: `bin/seon start` joins the running JVM and serves the
   code it loaded at start.
5. Ollama answered a three-form turn in ~40 s and a two-form turn in ~2.5 s;
   budget the drive accordingly, and keep `:seon.config.ai/timeout-ms` at the
   local-provider value (300 000) rather than the 60 s remote default.

## Reproduction

```bash
git archive HEAD | tar -x -C tmp/preflight-head        # pin a clean HEAD
ln -s "$PWD/reference-code" tmp/preflight-head/reference-code
cd tmp/preflight-head
bb --config bb.edn --deps-root . --classpath script \
   -m seon.fresh-operator --seon-root "$PWD" init
clojure -M:dev -i ../preflight-drive.clj                # patched boot, blocks
```

Then drive it over its advertised prepl (`data/clusters/preflight-mvp/prepl.edn`);
the web view is on the name-derived port `7896`.
