---
type: research
status: active
tags: [agent, context, flow]
---

# Token-efficiency audit — the agent's always-on context (2026-06-28)

Read-only audit of Seon's STABLE always-on context blocks on
`feature/agent-fsm`. Owner directive: "every token should be there or we
should be refining it so we return better tokens." Numbers are TOKENS
(`seon.ai.tokens/estimate`, chars/4) — never chars. Measured live against
the running default pod (agent `root`, `seon.agent.ctx/ctx-sections` +
`tokens/estimate`), not estimated.

## TL;DR

- Total stable always-on cost (incl. `system-text`, excl. the deferred
  `:namespaces` block): **~29,200 tokens**. With `:namespaces`: **~42,500**.
- `:transcript` (20,315) dominates everything — it is the agent's working
  memory and grows UNBOUNDED today (`:seon.render/clip :none`, no sliding
  window). The single biggest structural lever, but a Core change.
- The top REFINE win is a clean cross-block dedup: **`system-text`'s eval
  mechanics and the ALWAYS-ON `:skill/repl` body teach the same parse/comment
  rules twice** (~700–900 tok of pure overlap, every turn).
- The top TRIM win is **`:live-tile` (1,819) — ~1,300 tok of it is a static
  copy-paste cookbook + CSS safelist** that re-renders verbatim every turn and
  teaches once.

## Per-block measurements (live, agent `root`)

| Block | Tokens | Lane | Verdict | Why |
|---|---:|---|---|---|
| `system-text` (system role) | 3,114 | Core (`ctx.cljs:881`) | refine | dense + internal/cross-block repetition; ~400 tok recoverable |
| `:soul` / `:agents` | 0 | mine | keep | file-blocks, absent on this pod (SEON_SOUL split); zero cost when absent |
| `:shared-instructions` | 0 | mine (`my.kb.shared`) | keep | empty singleton renders ""; inert seed, no live cost |
| `:skills-catalog` | 882 | mine (`my.skills`) | refine | descriptions are verbatim multi-sentence "Use when…" blobs; ~300 tok recoverable |
| `:skill/repl` (always-on body) | 910 | mine (config `default-load [:repl]`) | **trim/dedup** | duplicates `system-text` eval+comment mechanics; biggest single overlap |
| `:namespaces` | 13,312 | Core (`ctx.namespaces`) | DEFER | mid-refactor, peer's #42 — out of scope |
| `:live-tile` | 1,819 | mine (`ctx.live-tile`) | **trim** | ~1,300 tok static cookbook + safelist; teaches once, costs every turn |
| `:warnings` | 2,058 | mine (`ctx.warnings`) | keep | transient/reactive (self-heals); currently inflated by a weak-agent failure flood on the shared pod |
| `:open-todos` | 0 | mine (`todo.internal`) | keep | derived, blank when none |
| `:relevant-source` | 0 | mine (`ctx.relevant`) | keep | SEON_EMBED-gated, off by default |
| `:inventory` | 123 | mine (`ctx.inventory`) | keep | tiny, high-value, reactive |
| `:transcript` | 20,315 | Core+mine (`ctx.transcript`) | refine (Core) | working memory; unbounded today, the clip knobs are the lever |

Stable subtotal (excl. `:namespaces`): **~29,221 tok**.

## Cross-block repetition (the dedup wins)

### 1. `system-text` ⟷ always-on `:skill/repl` — DUPLICATE eval/comment rules

`:skill/repl` is **always-on** (`config/system.edn` `:seon.config/default-load
[:repl]`, seeded as a `:skill/repl` block at priority 16 — it is NOT
lazy-loaded, it rides every prompt). Its body re-teaches, in depth, what
`system-text` already teaches:

- `system-text` "EVAL MECHANICS" (`ctx.cljs:922-927`): "A form RUNS only if it
  starts with `(` … a bare data literal you paste `{…}` `[…]` `#{…}` do NOT
  evaluate … wrap it in a form" — **the REPL skill's entire "What EVALUATES vs
  what is DROPPED" section** says the same.
- `system-text` "THINK IN COMMENTS" (`ctx.cljs:941-950`) `;`/`;;`/`;;;` comment
  levels — **the REPL skill's "Comment levels carry meaning"** block repeats it
  verbatim in intent.
- `system-text` "After your LAST form, STOP … read it then" — **the REPL skill's
  "Write forms that land → One form, then read the result."**

This is ~700–900 tok taught twice, every turn. The REPL skill adds genuinely
NEW depth (parinfer auto-repair, what's NOT auto-repaired, `:read` error flow) —
that part earns its place. The fix is to pick ONE home for the shared rules:
either (a) **drop `:repl` from `default-load`** so its body returns to the
lazy catalog (saves the full 910 every turn; the catalog line stays), or
(b) keep it always-on but **cut `system-text`'s EVAL-MECHANICS + THINK-IN-
COMMENTS down to a one-line pointer** ("how forms are parsed/repaired: the
`:repl` skill below"), saving ~600 tok off `system-text`. (a) is the bigger,
cleaner win and matches the "discoverable, not dumped" doctrine — but the live
drives show agents rarely load skills and adoption tracks prominence, so if the
parse rules are deemed load-bearing-always, do (b) and let the always-on skill
be their one home.

### 2. `:live-tile` ⟷ a would-be UI/`datastar-web-ui` skill — the safelist + cookbook

`:live-tile` (1,819) renders the actual derived tile body (what the human sees)
in only its first ~400 tok. The remaining ~1,400 is a STATIC, turn-invariant
teaching payload baked inline (`ctx.live-tile.cljs:135-200`):

- three full copy-paste `transact!` examples (my.ui/section compose, literal
  hiccup, tile-fn),
- the CSS **SAFELIST** (every allowed class) — which is ALSO the domain of the
  `datastar-web-ui` skill and the `my.ui` ns docstrings.

This cookbook is identical every render and is "teach once" material. Keep the
reactive part always-on (the derived tile body + wired label + the "THIS canvas
is your PRIMARY surface" nudge, ~400 tok); move the cookbook + safelist into a
loadable `:tile`/`:canvas` skill (or lean on the `my.ui` docstrings that already
render inside `:namespaces`). Net always-on saving: **~1,200–1,400 tok/turn.**

### 3. Minor: `system-text` internal redundancy

`RESULT VARS` (`ctx.cljs:958-968`) re-explains the `result/<id>` handle and the
"clipped display is not a clipped value" point that `THE TRANSCRIPT IS ONE
EVAL'ABLE REPL SESSION` already set up, and that the transcript's own clip
markers (`cap-result-body`) repeat at each clip site. Tightening the second
telling saves ~150–250 tok.

## Staleness check

- No `/world` or retired-verb references found in `system-text` — the verbs it
  teaches (`message/user`, `message/agent`, `wait`, `complete`, `store-inventory`,
  `render-namespace`, `seon.agent.search/grep`, `seon.agent.todo/add!`) all
  resolve live. Clean.
- `:shared-instructions` is seeded but its singleton is EMPTY, so it renders ""
  (0 tok). Not stale, not waste — just an inert zero-state seed. Fine as-is.
- `masthead`/`readline` (transcript) describe the current flat event-log model —
  current.

## Density / "better tokens" notes

- `:skills-catalog` (882): every line is the skill's full verbatim
  `description` frontmatter — several are 2–4 sentence "Use when…" paragraphs
  (e.g. `data-oriented-clojure`, `clojurescript`). The catalog's job is
  DISCOVERY (name + one-line trigger); the full "Use when…" essay belongs in
  the skill body, not the always-on catalog. Trimming each catalog line to its
  first trigger clause recovers ~300 tok with no discovery loss. (mine —
  `my.skills/catalog-line`, or upstream in each `SKILL.md` frontmatter.)
- `:warnings` (2,058 right now): correct architecture (reactive, self-heals),
  but the failed-evals section lists ~11 eval ids each with a ~150-char error
  preview. On a healthy single-agent pod this is near-zero; the current bloat is
  a weak-agent failure flood on the SHARED pod. Optional refine: cap the listed
  ids (e.g. first 5 + "…N more") and shorten each preview — but do NOT store
  anything; it must stay derived.

## Ranked refine list (highest leverage first)

1. **Dedup `system-text` ⟷ always-on `:skill/repl`** — ~600–900 tok/turn.
   Either drop `:repl` from `config/system.edn` `:seon.config/default-load`
   (mine/config) OR cut `system-text` EVAL-MECHANICS + THINK-IN-COMMENTS to a
   pointer (Core, `src/seon/agent/ctx.cljs:922-950`). Pick one home for the
   shared parse/comment rules.
2. **Trim `:live-tile` cookbook + safelist** — ~1,200–1,400 tok/turn. Keep the
   reactive tile body + primary-surface nudge always-on; move the three
   transact examples + CSS safelist to a loadable skill or the `my.ui`
   docstrings. (mine, `src/seon/agent/ctx/live_tile.cljs:135-200`.)
3. **Slim `:skills-catalog` lines to the trigger clause** — ~300 tok/turn.
   (mine, `my.skills/catalog-line` or `SKILL.md` frontmatter.)
4. **Tighten `system-text` internal RESULT-VARS / clipped-value repetition** —
   ~150–250 tok/turn. (Core, `src/seon/agent/ctx.cljs:958-968`.)
5. **`:transcript` sliding window / clip-cap tuning** — the largest block
   (20,315) and the only UNBOUNDED one (`:seon.render/clip :none`). Not a quick
   refine; it is the planned future "sliding window" + the
   `SEON_RENDER_*_CAP` family (`result-body-render-cap` is 16,384 — very large).
   Biggest structural lever, lowest urgency-to-effort. (Core,
   `src/seon/agent/ctx/transcript.cljs` + `seon.config`.)

## Combined quick-win estimate

Refines 1–4 (no deferral, no Core-transcript rework) recover roughly
**~2,300–2,950 tok off every single turn** — ~8–10% of the stable always-on
budget — with zero loss of agent-load-bearing content (the cut material is
either duplicated elsewhere or move-to-loadable cookbook). The `:namespaces`
(#42) and `:transcript` sliding-window levers are larger but out of this audit's
scope.
