---
type: research
status: active
tags: [research, agent]
---

# Tool-surface + readiness survey — ground truth for the eval suite (2026-07-02)

> First research artifact of the eval-suite PRD. Question: what can an agent
> actually DO today, what does it SEE, what can we TEST it with, and what makes
> runs unstable. Grounded in source + configs + a captured rendered context +
> live acme logs; docs were treated as claims, code as truth.

## 1. The tool/verb surface (what exists, maturity, test coverage)

The agent-facing toolkit renders from `:seon.config/always` +
`:seon.config/home-requires` (`config/system.edn:35,80-91`; acme mirror
`config/acme.edn:96-97,53`). "inspect.ai tool parity" (agent-fsm lane) =
shell + web-fetch + blob + in-place file edit completing the standard
agent-tool set — web-fetch's commit calls itself "the largest missing
inspect.ai-suite tool (gates GAIA-class benchmarks)" (`f1692eac`).

| Namespace | What it does | Maturity | Test coverage |
|---|---|---|---|
| `my.plan` | per-agent dependency GRAPH (not a todo list): `needs` edges, one `:active` position anchor, `:expect` falsifiable outcomes, `:pace` for session-spanning; progress/readiness DERIVED; windowed render (`src/my/plan.cljs:1-15`) | shipped TODAY — todo→plan rename `51a8cab8` + redesign `1cda2948` (drive-grounded) | `test/my/plan_test.cljs`; preflight §5 drive (pre-rename semantics) |
| `my.kb` | schema'd DB knowledge: runnable recipes, attrs+refs, no kinds (`src/my/kb.cljs:1-14`) | shipped | `test/my/kb_test.cljs` (recipes compile+run); preflight §5 recall drive |
| `my.blob` | content-addressed disk tier (SHA-256 = name, idempotent, `concat!` for chunks `2f51958c`) (`src/my/blob.cljs:1-14`) | shipped | `test/my/blob_test.cljs` |
| `my.skills` | load/drop knowledge as ctx blocks; cost derived at render (`src/my/skills.cljs:1-15`) | shipped; `:my.skills/load` presence-set in config | `test/my/skills_test.cljs` |
| `my.ui` / `my.tile` | dual-render canvas pieces (hiccup + `:seon.render/ai` mirrored from one input); tile = interactive controls calling agent fns (`src/my/ui.cljs`, `src/my/tile.cljs`) | shipped; canvas = last-updated tile w/ pin override (`146c52f6`) | `test/my/ui_test.cljs`, `test/my/tile_test.cljs`; ui-live-tiles skill A/B (ledger) |
| `my.data` | dedup-safe aggregates (sum/max-by over maps — datalog footguns unreachable) (`src/my/data.cljs:1-14`) | shipped | `test/my/data_test.cljs` |
| `seon.agent.shell` | argv-only process exec, `{exit out err}` as data; `ok?` = RAN not zero-exit (`src/seon/agent/shell.cljs:1-15`) | shipped; **default-deny `SEON_SHELL` grant — UNGRANTED on acme today** (no grant in `.env.acme`/`bin/acme`) | `test/seon/agent/shell_test.cljs`; live-verification research `3d916708` |
| `seon.agent.web/fetch` | undici fetch → readability→markdown, blob-stored full doc + token-capped preview; SSRF guard every hop; 2MB cap; non-2xx = `ok? true` w/ status (`5bd3dac2`) | shipped `f1692eac`, wired into boot + home-requires; **default-deny `SEON_WEB` — UNGRANTED on acme today** | `test/seon/agent/web_test.cljs` (hermetic, 6 behaviors) + real-network proof |
| `seon.agent.fs` edit-file | in-place line-range + unique exact-match edit, gated, errors-as-values | shipped `e5b3be6e` (today 12:46) | per-commit tests; NOT yet exercised by any bench |
| `seon.agent.message` / `lifecycle` / `search` | agent↔agent msgs, complete/terminate verbs, search | shipped (lifecycle card renders ~22 tok) | exercised implicitly by every drive (`:completed` closes runs) |

## 2. What an agent sees (one captured render)

Source: a full `SEON_DEBUG_CAPTURE`-style render captured earlier today
(default-pod agent `my.agent.Lky-2607021145`; capture pre-dates the
todo→plan rename and web-fetch wiring — it shows `seon.agent.todo` and no
`seon.agent.web` card, which itself demonstrates render↔build coupling).
Token estimates are chars/4 over the escaped capture — treat as ±20%:

- system preamble ≈350 · skills-catalog ≈2.7k · `repl` skill ≈1.1k
- namespaces block: header ≈0.9k, then cards — lifecycle 22 · message 269 ·
  search 383 · shell 848 · todo 563 · **seon.db 1.2k · seon.schema 2.4k** ·
  my.data 787 · my.kb 323 · my.tile 682 · my.ui 435 · my.blob 663 · home-ns 655
- live-tile ≈200 · warnings ≈1.0k · open-todos ≈356 · findings ≈91 · transcript tail
- whole render ≈15–18k tokens.

Fresh-world caveat (preflight §7): on a just-reset store the `my.kb` card
renders "0 fns, 0 schemas" — the agent re-discovers the API by grepping,
burning turns. The suite must either accept that as part of the measured
capability or pin a seeded-world precondition per task.

## 3. Task-completion readiness (per capability row)

| Row | Toolkit support today | Missing piece |
|---|---|---|
| Reasoning/QA | ✅ complete via `/solve` (gsm8k 2/2 proven) | — |
| Memory store→recall | ✅ (`my.kb` + preflight §5 PASS) | fresh-world empty-render costs turns; turn-6 recall-visibility flagged (coordination.md) |
| Long-term planning (restart-surviving) | ✅ mechanism proven pre-rename (§5); **redesigned `my.plan` (deps/pace/expect) NOT yet re-driven** | one live re-drive on the new semantics before it anchors the suite |
| Shell use | wired + tested; **UNGRANTED on acme** | set `SEON_SHELL` grant in the bench env; then a first live drive |
| Web fetch | wired + tested; **UNGRANTED on acme** | set `SEON_WEB` allowlist; live drive; non-2xx semantics fresh (`5bd3dac2`) |
| Blob storage | ✅ shipped+tested | no bench exercises it directly (rides web-fetch/output paths) |
| File editing | shipped today (`e5b3be6e`) | zero bench/drive coverage yet |
| Clojure codegen w/ specs | ✅ (E1-shape 3/3 via `/solve`; oracle scorers idiom-agnostic `9b467a73`) | — |
| UI/tile rendering | shipped+tested+skill-A/B'd | needs a human-verifiable scorer (`:seon.render/ai` line is machine-checkable) |

Owner's framing holds: the surface is broad enough to "complete most tasks";
the ungranted gates (shell/web), the un-redriven plan redesign, and the
un-benched edit-file are exactly the "experimentation and tuning" gap.

## 4. Bench assets today (row × asset)

| Row | inspect (src-inspect-ai) | spike benches | gym | cljs unit tests |
|---|---|---|---|---|
| Reasoning/QA | `catalog.py`: gsm8k/arc/mmlu/csqa/truthfulqa/gpqa (case-1) | — | — | — |
| Memory | — | `memory_qa_bench.py` (+dataset) | — | kb_test |
| Planning | — | `planning_resume_bench.py` (pre-rename verbs — needs re-ground) | — | plan_test |
| Coding (general) | HumanEval/MBPP = case-2 (mvm tier, deferred) | `coding_eval_bench.py` | — | — |
| Clojure codegen | `e1_spec_fn` / `skill_lift` / `ladder_lift` + oracle scorers | — | 3 scenarios (`acme/gym/scenarios/`) — retiring at parity | — |
| Tool use (shell/web/edit) | — | `tool_use_data_bench.py` (data-tools only) | — | shell/web tests |
| UI/tiles | — | — | — | ui/tile tests |

## 5. Stability hazards (the flake taxonomy)

1. **Solve-latency variance 51→300s** per sample on memory tasks (preflight §7)
   → per-sample timeout ≥3× median; epochs/pass^k mandatory.
2. **Fresh-world empty renders** (`my.kb` "0 fns") → turns burned re-discovering
   APIs; timeout-shaped misses that aren't capability failures.
3. **tx-feed pump wire-rpc timeouts** — recur beyond boot (16:50/16:54/16:55 in
   `logs/acme/pod.log`, during drives), self-healing in ~2s; could delay
   cross-agent visibility mid-run (candidate cause of the turn-6 empty recall).
4. **Stale-bundle races** on the unwatched acme bundle — now structurally
   guarded (`bin/acme` build-if-stale, `45429044`) but the guard itself is new.
5. **Provider stub churn** — a dead/stub provider run burns to the 20-turn cap
   ("successful garbage"); graceful-down for the configured-but-absent case is
   PASS (preflight §6) but boot-log provider labeling lies (open nit).
6. **Ambient-state test coupling** (memory: ai/soul-test class) — suite runs
   share a live-ish world; isolate suspect namespaces.
7. **Eval-sandbox skew** — the node oracle rejects `(require …)` (`No *load-fn*
   set`) though pods accept it; prompts must state sandbox rules or scores
   measure omission.
8. **Dev-version pin skew** — vendored inspect-ai reports `0.1.dev1` vs
   inspect_evals' `>=0.3.221` floor (resolved `--no-deps`); a future sync can
   silently change semantics.

## Pointers

- [[../../diffusion-dynamic-context/research/deepseek-preflight-drives-2026-07-02]] — the drive evidence cited throughout.
- [[../../agent-fsm/research/standard-bench-baseline-2026-07-02]] — catalog assessment + gsm8k baseline.
- `src-inspect-ai/README.md` — run matrix, scoring philosophy, parity map.
- `docs/prds/agent-fsm/coordination.md` — the two fresh-world findings, canonical-home note.
