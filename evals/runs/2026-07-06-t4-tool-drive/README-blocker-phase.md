---
type: research
status: blocked
tags: [research, agent, eval]
---

# T4 tool-drive run — 2026-07-06 — HALTED at pre-canary (harness blocker)

Driver run for spec §A5 step 3 (the T4 handoff gate), per plan
`docs/prds/agent-ctx/research/t4-drive-test-plan-2026-07-06.md`.

**Outcome: HALTED before the canary drive.** A live-verified harness blocker
makes a *valid* T4 drive impossible on a frozen cluster as the supervisor is
currently wired: the anchored-edit verbs under test (`fs/replace!`,
`fs/insert!`, `fs/write-file`) would be **refused read-only** for every agent,
and there is **no out-of-band eval channel** to widen the grant on a frozen
pod. Zero DeepSeek drives were run (no budget burned). This is exactly the
"context/harness defect to fix before burning drives" the canary-first
directive exists to catch — surfaced at cost ~0.

## The blocker (live proof, not inference)

Every cluster pod is spawned by `bin/seon` with a **hardcoded read-only repo
fs grant**. Confirmed on the actual t4drive pod command:

```
$ bin/seon print-cmd pod-t4drive
  … SEON_FS_ROOT="/Users/sean/src/seon"  SEON_FS_READ_ONLY=1 … node "out-bench/client/main.js"
```

- `out-bench/client/main.js` → the frozen bench bundle is used (isolation
  works; sha `b4a47a6c514774325d463086ea3042192377e09ebf530e66b32fadc4981747c3`).
- `SEON_FS_READ_ONLY=1` → `seon.agent.fs` writes are denied
  (`fs/internal.cljs` `env-bootstrap` seeds `:seon.agent.fs/read-only? true`).
- `SEON_FS_ROOT=/Users/sean/src/seon` → the grant is the **whole repo**, not
  the workspace `tmp/t4-drive/…` the plan requires.

The plan's runbook (L231-233) assumed the grant could be scoped via "`.env` or
`configure!` in-drive". Neither works on a frozen pod:

1. **No per-cluster env/config seam for the fs grant.** `bin/seon` L172-173
   states the fs grant is set *on the pod command* as a "durable read-only repo
   grant"; the only per-cluster customization seam is `SEON_CONFIG` (the aero
   manifest), and pod boot applies **no** fs `configure!` from config (grepped
   `client.cljs` + `config/system.edn` — none). The exec line's
   `env SEON_FS_READ_ONLY=1 …` overrides any inherited shell env, so exporting
   the vars before `cluster create` has no effect.
2. **No eval channel to `configure!` at runtime.** A frozen cluster runs the
   standalone `out-bench` bundle. It does **not** advertise a runtime to the
   shadow nREPL (`:7889`), so the `seon_cljs` MCP `eval`/`create_session`
   cannot reach it (`runtime_status` shows only `:client cluster=default`;
   `create_session build=:bench-client cluster=t4drive` → "no SINGLE runtime …
   advertises cluster 't4drive'"). `SEON_FS_LOCK` is unset, so `configure!`
   *would* be permitted — but there is no way to call it out-of-band on this
   pod.

Net: the central tools under test cannot write. Driving anyway would measure a
harness grant gap (uniform-0), not the tools — forbidden by the plan's
"uniform-0 → suspect the contract/grant first" rule (§6).

## What IS verified working (prereqs green)

| Prereq | State | Evidence |
|---|---|---|
| Branch | `feature/agent-ctx` | `git branch` |
| Corpus | present | `reference-code/aider-polyglot/{python,javascript}/exercises/practice/*` — two-bucket, grep, book-store, react, poker, paasio, js/grep, js/book-store all present with `.docs/`, `.meta/`, test files |
| Frozen bench bundle | builds clean | `bin/seon bench-bundle` → sha `b4a47a6c…` (562 files, 116 compiled, 0 warnings) |
| Frozen cluster create | works | `bin/seon cluster create t4drive --frozen` → pod `http://127.0.0.1:59334`, db-name t4drive, execs `out-bench` (frozen), sha-asserted |
| Grants | granted | `SEON_SHELL=1`, `SEON_WEB=1`, `SEON_FS_LOCK` unset |
| DeepSeek provider | ready | `SEON_AI_PROVIDER` unset → deepseek (shipped default); `DEEPSEEK_API_KEY` present (len 35) |
| fs in toolbelt (A5 step 0) | DONE | `config/system.edn` `:seon.eval/home-requires` carries `[seon.agent.fs :as fs]` (both the shared block L152 and root L180) |

## Canary check (b) — teaching rendering — answered WITHOUT a drive

Rendered the real prompt path on the default/watched pod (same
`config/system.edn`, same src the frozen bundle was built from) — a pure read,
no mutation.

- **Compact toolbelt card** (what a fresh turn-1 agent sees): `seon.agent.fs`
  renders with `replace!`, `insert!`, `view`, and the `#code` heredoc literal
  is taught. Legacy `edit-file` is **absent** from the prompt. → **verb
  DISCOVERY teaching PASSES.**
- **Ambiguous-flow mechanics** (`candidates` / `::near` / `::expected-count`):
  **absent from root's assembled prompt** (compact card = line-1 docstrings
  only). They ARE present when `seon.agent.fs` is rendered at full source
  (`render-namespace :seon.agent.fs` → all three strings present, 33983 chars).
  Since the plan's task contracts state the disambiguation behaviour verbatim
  (the load-bearing rule), this is **acceptable by design**, but the observer
  and orchestrator should note: the ambiguous-replace! probe (task 1, task 8)
  is taught by the CONTRACT, not the compact card.
- Reference hash of root's full rendered prompt (proxy for turn-1 shape;
  NOTE root's transcript is polluted by prior activity, so treat as indicative
  only): sha256 `7404836a955b75a33ac29e46954b382ae4c1dae0310b3de4c4e1dcf46c20561c`,
  76678 chars / ~19169 tokens.

Canary check (a) — did the agent USE the new verbs — **could not be tested**
(blocked by the read-only fs grant above).

## Recommended fix (owner / orchestrator — touches shared `bin/seon`)

The clean fix is a small, additive, backward-compatible change to the
`pod-<cluster>` branch of `process_command` in `bin/seon` (~L277): let a
frozen/dev cluster pod **honor `SEON_FS_ROOT` / `SEON_FS_READ_ONLY` from the
environment when set**, instead of unconditionally forcing
`SEON_FS_ROOT="$SEON_ROOT" SEON_FS_READ_ONLY=1`. Then T4 creates the cluster
with `SEON_FS_ROOT=<abs>/tmp/t4-drive SEON_FS_READ_ONLY=0` and the workspace is
writable while the rest of the repo stays untouched. This is out of the
driver's scope (`no src edits`; `bin/seon` is shared with the slice-4 agent) —
it needs owner sign-off + slice-4 coordination.

Alternative (no `bin/seon` edit, but weaker isolation): drive on a `--watched`
cluster (its pod advertises to the nREPL, so `configure!` is reachable via the
MCP) — but that reintroduces the class-2 mid-drive hot-reload instability the
frozen bundle exists to prevent, and the tooling lane edits src concurrently.
Not recommended per the plan's explicit frozen requirement.

## Run state / cleanup

- t4drive cluster: **destroyed** after evidence capture (the fix requires a
  `bin/seon` edit + pod restart anyway, so leaving it running buys nothing).
- Default cluster: untouched (no reset, no restart; the diagnostic evals were
  pure reads on the existing `root` agent).
- No `SEON-CORE-FAULT` observed.

## Layout

```
README.md   ← this file (blocker + prereq matrix + teaching diagnostic + fix)
contracts/  ← (empty — populate at re-drive; template in the plan §2)
observer/   ← (empty — no turns produced)
transcripts/← (empty — no drives ran)
```

Re-drive is a straight resume once the grant fix lands: re-`bin/seon
bench-bundle`, `cluster create t4drive --frozen` with the workspace grant,
stage `tmp/t4-drive/**`, then canary task 1 per the plan.
