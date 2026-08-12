---
type: research
status: complete
tags: [research, agent, bootstrap, context]
---

# Agent-bootstrap extracted rulings — 2026-08-06

This note preserves the form-series decisions worth carrying forward before
the stale agent-bootstrap PRD moves to the archive. It does not preserve that
PRD's append-only-session, readline, fixed curriculum, or prompt-band design.

| Decision | Historical source | Current disposition |
|---|---|---|
| Initial teaching is ordinary evaluated forms with actual outputs, not a parallel teaching transcript. | `docs/prds/archive/agent-bootstrap/README.md:15-21` | **Narrowed.** Keep teaching-as-forms; the claim that all context is forms is superseded by the declared status/log renders and the bounded database walk. |
| Each form/result follows strict REPL display: prompt per form, leading `;;` as input, output before the bare result, no output for comment-only input, and comments never modeled as output. | `docs/prds/archive/agent-bootstrap/README.md:22-30,173` | **Not superseded.** the agent's defs and per-turn fork change executable state, not REPL display semantics. |
| Every teaching form must earn its place through actionable output; capabilities are discovered by query rather than enumeration, replayed forms stay harmless, and non-demonstrable instruction prose remains database facts. | `docs/prds/archive/agent-bootstrap/README.md:31-40` | **Principle retained; curriculum superseded.** The next series is selected only after the repaired grader matrix; the archived lesson roster is not current authority. |
| Agent-specific initial forms override the cluster default through declared facts, never naming-convention discovery. | `docs/prds/archive/agent-bootstrap/README.md:75-81,144-145,176-177` | **Intent retained; mechanism superseded.** The 2026-08-05 ruling replaces producer-function attributes with cluster and optional agent initial-form declarations, most-specific-wins, one `system-run-tx` path, and a digest over resolved forms (`docs/prds/sci-execution-runtime/plan/README.md:479-484`). |
| The form series reads as a causal, self-explaining story: each input-side thought is justified by prior visible output and never relies on unseen output. | `docs/prds/archive/agent-bootstrap/README.md:96-111` | **Retained as a quality rule, not approval of the archived sequence.** Current bootstrap selection remains contingent on grader evidence. |
| Graduation is empirical: drive live agent episodes and measure behavior rather than accepting prose or receipt archaeology as proof. | `docs/prds/archive/agent-bootstrap/README.md:146-166` | **Principle retained; exact falsifiers superseded.** The current grader wave repairs O4 causal-episode scope, O5's refusal target, and O1 replication before another bootstrap design session. |

The archived readline mechanism, function-generated bootstrap producer,
stable/volatile prompt bands, fixed message/status/unreplied curriculum, and
special pinned-prefix treatment are not carried forward.
