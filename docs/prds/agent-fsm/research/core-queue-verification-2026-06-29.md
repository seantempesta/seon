---
type: research
status: active
tags: [research, agent, core, web]
---

# Core queue verification — live repro pass (2026-06-29)

Live-verification pass against the running default pod (7890, agent `root`) +
the live code on `feature/agent-fsm` (HEAD `12df41ee` at verify time; the queue
was framed at `b50899c0`). Goal: make the "→ Core" ask queue TRUSTWORTHY — for
each open ask, decide REAL-reproduces / FIXED / PARTIAL / PENDING against the
RUNNING system, not the (possibly stale) task list.

Method: drove evals through the real agent eval path (`seon.eval/eval` over the
shared bootstrap compile-state, `agent_id "root"`, home ns `my.agent.root`);
transacted probe entities through `seon.db/transact!`; cited `file:line` for
code-provable items. Verification only — NO code fixes.

## TL;DR

- **#40 is ALREADY FIXED / not-real** — the loud headline. The turn entity
  schema's "required at/status" is NEVER enforced at transact time; `seon.db`
  validates PER-ATTRIBUTE, not whole-entity. Live-proven: a turn map with
  `status` but NO `at` transacts `ok? true`.
- **#56, #73, #83 reproduce (REAL)** — confirmed live or by load-bearing code.
- **#42 is REAL/PENDING in full** — neither the namespaces config section nor
  the owner's revised skills `:load` redesign exists; skills still uses the
  rejected `#profile {:default :minimal}` named-set pattern.
- **#74** = todo renders FULL today (trim not applied; being reconsidered).
- **#71** not captured in the clojurescript skill (PENDING, doc-only).
- All five owner-decision asks (#43/#45/#66/#81/#88) remain genuinely PENDING.

## Verdict table

| Ask | Claim | Verdict | Live evidence | Note |
|---|---|---|---|---|
| **#40** | turn `at`/`status` required-but-absent throws | **FIXED / not-real** | `validate-entity-values!` (`src/seon/db/internal.cljs:761-822`) iterates `[attr v]` pairs and validates each against its PER-ATTR schema — it never looks up the whole-entity `:seon.agent.turn` `:map` (`turn.cljs:89-102`), so its required `at`/`status` are never enforced. Live: `transact! {:seon.agent.turn/id "ZZZ-2606290000" :seon.agent.turn/status :done}` (NO `:at`) → `{:seon.db/ok? true}` (pod log `VERIFY40b`). `close-turn!` (`turn.cljs:255`) transacts `{:id :status}` w/o `:at` every turn; root's done turn `EtZ-2606291251` exists. | The required keys in `turn.cljs:92-93` are structural documentation, NOT a runtime gate. Probe entity retracted (`CLEANUP40 => true`). |
| **#42** | explicit-listing config for namespaces + skills | **REAL / PENDING** (both halves) | NAMESPACES: no `:seon.config/namespaces` in the manifest (`config.cljs:91-95` = skills/loadouts/routes only); no `resolve-namespaces`/`config/always`/`config/current-ns` anywhere (grep empty). Selection is hardcoded: `full-source-whitelist #{:seon.agent.todo}` (`namespaces.cljs:143`), `verb-signature-whitelist` (`:165`), `canonical-full-my-ns #{:my.kb :my.data :my.ui :my.tile}` (`:197`), `body-detail` (`:240-264`). SKILLS: a config section exists but uses `:include`/`:exclude` + `#profile {:default … :minimal []}` (`config/acme.edn:26-33`) — the EXACT named-set pattern the owner rejected; `:seon.config/skills-spec` has NO `:load` key (`config.cljs:61-69`). | Owner's `{:load :all\|[list]}` (skills) + `{:always/:signature/:current-ns}` (namespaces) is UNBUILT. Signature machinery IS still load-bearing: `verb-signature-whitelist` used at `namespaces.cljs:489-495`, `body-detail :signature` for non-canonical `my.*`. If owner goes full-source-only, the `:signature` path + `verb-signature-whitelist` become removable — but TODAY they render message/lifecycle/agent signatures + every non-canonical `my.*` ns. |
| **#56** | `my.ui`/`my.data`/`my.tile` need full qualification | **REAL** | `home-ns-require-specs` (`eval.cljs:1216-1221`) aliases only message/agent/lifecycle/schema/db/todo — NOT `my.*`. Live in `my.agent.root`: `(my.ui/status-line {…})` RESOLVES (reaches the value validator → `:my.ui/value should be a string`, fn found); `(status-line {…})` → `` `my.agent.root/status-line` is not defined `` (pod log `S56-QUAL`/`S56-UNQUAL`). | Minor ergonomics. `home-ns-refer-toolkit-nses` (`eval.cljs:441`) = `[seon.agent.lifecycle]` only confirms `my.*` is not refer'd. |
| **#73** | home-ns alias collision in agent-authored nses | **REAL** | Live: `(ns my.verifyfoo73)` then `(db/query {…})` → `` `db/query` is not defined `` (`:undeclared-var`, pod log `S73-NEWNS-DB`). Home-ns aliases are home-only; a new `my.*` ns inherits none of `db`/`message`/`todo`. | Mitigated-but-not-fixed: the error render now emits a helpful hint (`Did you mean db/query? — that home-ns verb; do NOT switch namespace`). The underlying break still reproduces. |
| **#74** | `seon.agent.todo` signature-trim | **NOT applied — todo renders FULL** | `seon.agent.todo` ∈ `full-source-whitelist` (`namespaces.cljs:143`); `body-detail` returns `:full` for it (`:263`). | As asked: reporting only. Being reconsidered (owner doesn't want signatures) — no trim shipped. |
| **#83** | writes-tests guidance absent; deftests called "noise" | **REAL / OPEN** | `namespaces.cljs:89-90` still: deftests are "noise to the working agent". No "write a deftest" cue in `system-text` (grep of `seon/agent/ctx.cljs` for deftest/write-test = empty). | Unchanged since last code-check. |
| **#71** | clojurescript skill `(fn [])` vs `constantly` gotcha | **PENDING (not captured)** | `seon-skills/clojurescript/SKILL.md` (+ `.claude/skills/` symlink) has no `constantly`/`(fn [])`/thunk/zero-arg content (grep empty). Headings: async/await, Promise handling, self-host eval, verifying live. | Doc-only. |
| **#43** | context-blocks escape clipping | **PENDING-decision** | No resolution in code: transcript still `:seon.render/clip :none` + `result-body-render-cap` 16,384 (coordination.md:532); the "let blocks escape the clip" decision is unmade. | Owner fork, open. |
| **#45** | disable inventory-block | **PENDING-decision** | `inventory-block` is LIVE in root's ctx right now (`:inventory` priority 97, `:seon.render/ai "seon.agent.ctx.inventory/inventory-block"` — pulled from `@*conn*`). Still rendering, not disabled. | Owner fork, open. |
| **#66** | `:kind` Category B (value-classification) purge | **PENDING-decision** | `docs/prds/agent-fsm/CLAUDE.md:220` lists it as an open "Owner decision"; Category A (recurrence engine) purged, B unresolved. | Taste/consistency, not correctness. |
| **#81** | weak-model-tier thresholds / s12 | **PENDING-decision** | Ongoing across coordination.md (s12 root-cause threads :1085-1218, `:keeps-the-repl-clean` 0.2 brittleness); no shipped resolution. | Open theme. |
| **#88** | prose-token / `:keeps-the-repl-clean` mis-measure | **PENDING-decision** | coordination.md:93-105 explicitly "INVESTIGATED, NOT shipped, OWNER-DECISION pending (2026-06-29)"; cap stays 0.2; the eval-time demotion knob is the owner's call. | Design in `research/repl-clean-calibration-2026-06-29.md`. |

## What turned out already-fixed (loud)

**#40 was labeled OPEN but is not real.** The orchestrator's read of
`turn.cljs:92-93` ("required, no `{:optional true}`") is correct about the
schema TEXT but wrong about the consequence: `seon.db` never validates a tx
entity against its whole-entity `:map` schema. `validate-values!` →
`validate-entity-values!` (`db/internal.cljs:824-833`, `761-822`) only walks the
attrs PRESENT in each map and checks each against its own per-attr schema. There
is no "required key" gate, so:

- the create path (`open-turn!`, `turn.cljs:222-239`) sets both `at`+`status`;
- the close/error paths (`close-turn!` `:255`/`:272`) transact `{:id :status}`
  WITHOUT `at` — and they succeed every turn (proven: root's done turn carries
  an `at` from create, and the merge never re-validates a whole entity);
- a fresh entity with `status` but no `at` also transacts `ok? true` (VERIFY40b).

So there is no required-but-absent throw path. **Recommend closing #40.** (If the
desire is to actually ENFORCE entity-level required keys, that is a NEW feature
on `seon.db` — entity-schema validation does not exist today — not a turn fix.)

## Repro commands (for re-verification)

```clojure
;; #40 — partial turn map without :at transacts ok
(-> (seon.db/transact! {:seon.db/tx-data [{:seon.agent.turn/id "ZZZ-2606290000"
                                           :seon.agent.turn/status :done}]})
    (.then #(js/console.log "ok?" (:seon.db/ok? %))))    ;; => true

;; #56 / #73 — drive through the real agent eval path (agent_id "root")
(-> (seon.repl/ensure-bootstrap!)
    (.then (fn [cs]
      (.then (seon.eval/eval cs "(status-line {:my.ui/label \"x\" :my.ui/value \"1\"})"
                             {:ns 'my.agent.root})
             #(js/console.log (pr-str (select-keys % [:ok :error])))))))
;; #56 unqualified => not defined; (my.ui/status-line …) qualified => resolves

(-> (seon.repl/ensure-bootstrap!)
    (.then (fn [cs]
      (.then (seon.eval/eval cs "(ns my.verifyfoo73)" {:ns 'cljs.user})
        (fn [_] (seon.eval/eval cs "(db/query {:seon.db/query (quote [:find ?e :where [?e :seon.agent/id]])})"
                                {:ns 'my.verifyfoo73}))))))
;; => `db/query` is not defined (alias is home-only)
```

## Entry points

- `src/seon/db/internal.cljs:761-833` — the per-attribute validation (why #40 is not real).
- `src/seon/agent/turn.cljs:89-102, 222-274` — turn schema + the create/close/error tx paths.
- `src/seon/agent/ctx/namespaces.cljs:121-264, 489-495` — the hardcoded namespace selection + signature machinery (#42/#74).
- `src/seon/config.cljs:61-95` — the manifest schema (no namespaces section; skills include/exclude only).
- `src/seon/eval.cljs:441-446, 1204-1221` — home-ns refer seed + require-specs (#56/#73).
