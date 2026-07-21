---
type: reference
status: active
tags: [prd, agent, architecture]
---

# Frozen-turn-inputs chunk runbook

`roadmap.md` is the ledger: an eight-row impurity inventory (I1-I8) and five
dependency-ordered stages. Work the top stage only; one in-progress critical
item at a time.

Rules of engagement:

- the law is one turn = one database value = deterministic prompt bytes;
  every fix strengthens the existing owner (`seon.agent.turn`,
  `render-prompt!`, the `ctx/*` block bodies) in place — no second render
  path, no parallel retry, no stored renders;
- read the already-pure list before "fixing" anything: most of the original
  issue evidence is closed in source; the open work is exactly I1-I8;
- proof instruments are `seon.agent.debug/ctx-preview`, `turn`, and
  `turn-diff` (`::prompt-lines-added`/`-removed` = 0 is the byte-identity
  assertion); never add a bespoke diff harness;
- the readline's live clock is DELIBERATE — confine it to the free dynamic
  tail (stage 4), do not delete it as an impurity;
- the file-block (SOUL.md/AGENTS.md fresh disk read) question needs an owner
  ruling before stage 4 implementation;
- issues [[../../seon/issues/ai-context-is-not-pure-over-database-value]]
  and [[../../seon/issues/turn-retries-reread-provider-inputs]] stay open
  until stage 5 closes them with commit plus live proof;
- gates: focused `bin/test-cljs` suites per stage plus the named live
  cluster proof; skills `data-oriented-clojure`, `datahike`,
  `clojurescript`, `clojure-testing`, `seon-context-config`.
