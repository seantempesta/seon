---
type: issue
status: active
tags: [issue, agent, index]
---

# Dual code paths & complexity-debt registry — LIVE, both lanes

**The one tracked list** (owner directive 2026-07-02: "don't lose track of
dual code paths — track everything and fix it"). Rules:

- Every dual-path / hand-list / silent-fallback / dual-home / warn-only
  finding gets a row HERE at discovery — from any audit, unit report, or
  drive. No finding lives only in chat or a dated research file.
- A row closes ONLY with the fixing commit sha. LEGITIMATE verdicts carry a
  rationale pointer and stay listed (they're re-audited, not forgotten).
- Depth lives in the source audits — this file is status, not analysis:
  [[../../prds/agent-ctx/research/magic-systems-audit-2026-07-02]] (M-rows) ·
  the tooling complexity sweep (C-rows, 2026-07-02 session) · unit reports.

## Open — FIX-ROOT (ranked by blast radius)

| id | item | where | lane | status |
|----|------|-------|------|--------|
| M1 | `*conn*` + `schema/*schemas` + shared compile-state ambient roots (scratch-world `set!` swap) | serve.cljs, client.cljs | eval (Slice A, owner-ratified) | IN FLIGHT — diff review gate before commit |
| M3 | SCI silent unbounded fallback (`warn-fallback-once!`) | render/sci.cljs:185,441 | eval (owner-ruled fail-loud) | IN FLIGHT — diff review gate |
| M4 | SCI env rebuild re-parses source for aliases (requires not stored structurally) | render/sci.cljs:230 | eval (with M3) | IN FLIGHT |
| M5b | Router serves a cached route projection, no tx-listener self-heal | web/router.cljs, serve.cljs:905 | eval (queued behind solve-path) | QUEUED |
| M6 | `async-fn?` ctor-name detection + once-per-process instrument gate | instrument.cljc:400 | tooling | OPEN |
| M8 | `core-ns-set` replay-skip + `fn-less-compiled-roots #{"my.kb"}` name sets (provenance, not names, should decide) | client.cljs:1067,2441 | tooling | OPEN — hand-list class |
| M9 | `!solve-deps` / `!create-agent-fn` require-cycle injection atoms | serve.cljs:110, render/sci.cljs:493 | tooling | OPEN (small) |
| M10 | Home-ns aliases unresolvable in agent nses (#73 — "fully qualify" docs are the workaround) | eval/sci env | tooling | OPEN — related M4 |
| M22 | Server registry legacy dual-shape snapshot reader | server/registry.clj:470 | tooling | OPEN (trivial) |
| M23 | `seon.repl/parse-forms` compat re-export | repl.cljs:59 | tooling | OPEN (trivial delete) |
| C1 | `SEON_EMBED` read ×3 under 3 names | turn.cljs:160, diffusion/retrieval.cljs:592, render/system.cljs:140 | tooling | QUEUED — unification sweep |
| C2 | pr-str+clip helpers ×8 (chars, violating tokens rule) | 8 files | tooling | QUEUED — unification sweep |
| C3 | Eval envelope bare `:ok`/`:error` vs namespaced envelope (owner ruled: → `:seon.eval/*`) | eval.cljs, client.cljs, worker_eval.cljs | tooling | QUEUED — unification sweep |
| C4 | Private `env*` readers ×2 + inline lookup vs `seon.platform/env-val` | ai/{diffusiongemma,openai_compat,anthropic}.cljs | tooling | QUEUED — unification sweep |
| C9 | Worker bootstrap-cache helpers copied from seon.eval "by design" | worker_eval.cljs:31 | tooling | QUEUED — unification sweep (shared leaf ns) |
| C10 | Dead `:seon.agent.ctx/fn` attr + inert-comment residue | client.cljs:426, eval.cljs:1373 | tooling | QUEUED — unification sweep |
| C11 | `transient-ns-syms` restates ns defs | eval.cljs:2270,936,1035 | tooling | IN FLIGHT — small-fix unit |
| F1 | Datahike fork: 2 pre-existing planner-ON failures (silent-`#{}` class; pod always runs planner) | fork query engine | tooling | OPEN — [[datahike-planner-on-preexisting-failures]] |
| C8 | Two corpus linters: seon.warn (pod) vs seon.dev.compliance (JVM) | warn.cljs / dev/compliance.clj | tooling | DEFERRED by owner — merge into seon.warn when JVM track retires |
| M5 | Dual compile worlds (4 heuristics cluster) | eval.cljs:615,782 | tooling | LEGITIMATE NOW / FIX-ROOT long-horizon (audit §5 — no fifth heuristic without reading that section) |

## Resolved (closure sha required)

| id | item | resolution |
|----|------|------------|
| M2 | Seed-inside-agent-scope + origin-forge warn-only guard | `ad6b9955` — origin stamped at boundary (derive-don't-claim), seed runs outside agent scope, guard DELETED (forgery impossible > enforced) |
| M7 | `skip-syms` hardcoded exemption set | `59624a9e` — def deleted; computed `async-unwrappable?` predicate (shape, not names). Known delta: eval/mem-db lost input-only wrap (documented) |
| M11 | tx-feed poll pump + rpc timer | partial fix landed pre-merge; pub-socket migration IN FLIGHT (stability unit) |
| C5 | `warn-on-seed-origin-forge!` vestigial guard | = M2, `ad6b9955` |
| C6 | Skills corpus dual home (.claude symlinks → seon-skills) | `68d73395` split (two audiences by owner ruling) + `21be639e` agent-perspective rewrite |
| C7 | Dev hook cwd-relative litter | `8185459f` — repo-root resolution; 57 strays cleaned |
| C12 | `~/src/datahike` duplicate checkout | verified-no-unique-work, deleted; bundle at tmp/datahike-salvage/; fork workflow rules in memory |
| F0 | Fork planner collect-field silent `#{}` | fork `da257d38` + seon `41c1b9b2`, live-proven; skills caveats removed `556fa779` |
| C13 | Redundant per-entry origin claims in eval.cljs | IN FLIGHT — small-fix unit (verify-then-strip) |

## Legitimate — listed, rationale'd, re-audited periodically

M12 auto-await (`maybe-await-value`) · M13 `!next-budget-ms` (caveat noted) ·
M14 repair layer · M15 `result/<id>` stash (three-tier rule) · M16
`agent-authored-sym?` prefix routing (owner-settled) · M17 kill-switches
(behavior forks — audit periodically) · M18 instrument degrade rows · M19
root-id shape exemption · M20 capability gates · M21 `race-timeout`
(compensated by M3's fix) · M24 fail-soft catch density (doctrine — but it is
the substrate that lets M3-class silent degradation hide; every new
`catch :default` must surface a `:seon/error`, never continue silently).
C-legit: SSRF hostname denylist (security constant) · spec-constant literals.
