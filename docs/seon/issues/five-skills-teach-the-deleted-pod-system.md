---
type: issue
status: open
severity: blocker
tags: [issue, skills, docs]
---

# Five skills teach the deleted pod system, and no skill teaches flow

## Problem

Skills are load-bearing agent context (owner ruling 2026-07-29: a skill
that does not match the system design is a HIGH-PRIORITY fix). The
2026-07-29 skills-update lane corrected the four data/schema skills.
Five others were never touched and still teach State A — the CLJS pod
that no longer exists:

- `clojurescript` — "the Seon CLJS pod", `cljs.js` self-host,
  `eval-str`, bootstrap compile-state. **CLJS is OFF** (owner ruling
  2026-07-27) and the pod is deleted. Actively misleading.
- `datastar-web-ui` — "the active pod web UI" on `127.0.0.1:7890`,
  editing `seon.web.*` / `seon.ui.*` (src-old namespaces). The fresh UI
  is CLJ in the cluster JVM on 7994.
- `ui-canvas` — points at `src-old/my/canvas.cljs` as the public API.
- `seon-context-config` — `config/system.edn` + `SEON_CONFIG` at pod
  boot. Config was rebuilt 2026-07-29: `resources/seon/schema/*.edn`,
  one manifest compiler, one `seon.config/apply!`,
  `bin/seon start --config` / `config apply`.
- `browser-automation` — port 7890, pod feeds.

Simultaneously there is **no skill for the architecture agents actually
work in**: `core.async.flow` (procs, workloads, buffers, graph
construction, live update), agents-as-flows, the cluster/store/boot
tower, the render pipeline, and the code-as-facts corpus. That
knowledge lives only in `docs/prds/sci-execution-runtime/research/`
and must be re-derived by every lane.

## Owner

`.agents/skills/` — retire or rewrite the five; author the missing
`seon-flow-architecture` skill from the research corpus, every claim
verified against current source.

## Acceptance

No skill references the pod, `cljs.js`, port 7890, `src-old/`, or
`config/system.edn` as current. A flow/architecture skill exists whose
claims each carry a current-source file:line, and it names the standing
prohibitions (never block `:compute`, never compute on `:io`, `:mixed`
refused at construction, channel contents must be losable).
