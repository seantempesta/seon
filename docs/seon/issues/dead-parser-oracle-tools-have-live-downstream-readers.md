---
type: issue
status: open
severity: blocker
tags: [issue, evaluation, deletion, tooling]
---

# Remove dead parser and oracle tools from live evaluation chains

## Problem

`bin/test-parser` and `bin/oracle-server` are executable root tools whose fresh
source owners were deleted. Both fail before doing work, yet current
DiffusionGemma, Inspect, ACME, and documentation readers still present them as
the parse/evaluation authority. The same chains also point to the deleted
`worker-oracle-eval` self-host CLJS bundle.

## Evidence

- Read-only probe at `22709278b`: `bin/test-parser` exited 1 because
  `seon.repl.parse-test` is absent from the classpath. Its own lines `2-21`
  claim the authoritative gate is deleted `bin/test-cljs` and require that
  missing test namespace.
- On the same tree, piping `{"op":"parse","code":"(+ 1 2)"}` to
  `bin/oracle-server` exited 1 because `seon.repl.parse` is absent.
  `bin/oracle-server:47-58` requires both that namespace and
  `seon.diffusion.grammar`; neither exists under fresh `src/`.
- The downstream reader closure is live-looking and broad:
  `src-diffusion/src/seon_diffusion/config.py:1-27` uses the executable as the
  repository sentinel and selects both it and
  `out/worker-oracle-eval/main.js`; `src-diffusion/src/seon_diffusion/oracle.py:1-95`
  spawns both; GPU and server modules instantiate that client.
- `src-inspect-ai/src/seon_inspect/oracle_scorers.py:1-52,112-128` calls these
  the real fail-loud oracles and tells users to rebuild the missing self-host
  bundle with `clj -M:cljs`. `src-inspect-ai/README.md:19-43` repeats that
  instruction.
- `bin/acme:106-126` delegates `gym-diffusion` to
  `acme/gym/diffusion_gym.bb`, whose lines `1-72` spawn both dead artifacts.
  `docs/seon/components/agent-reply-segmenter.md:14,97-106` still advertises
  `bin/test-parser` as the fast JVM loop even though its implementation is a
  Babashka reader over the deleted pod parser.

## Owner

The evaluation owners that still consume these tools. Current reply parsing
belongs to `seon.sci.reader`; consumer-specific diffusion oracles belong in
their downstream package, not as a second Seon parser/runtime under root
`bin/`.

## Acceptance

- Delete `bin/test-parser` and replace its active documentation with the
  current `seon.sci.reader` / `bin/test` proof surface.
- Delete `bin/oracle-server` and the self-host eval-bundle expectation, then
  either remove the obsolete diffusion/gym readers or bind genuinely required
  evaluation behavior to current fresh owners without restoring a second
  parser or runtime.
- No active source or documentation names `seon.repl.parse`,
  `seon.diffusion.grammar`, `worker-oracle-eval`, `bin/test-cljs`, or a
  `clj -M:cljs` rebuild recipe.
- Every surviving evaluation task has a recurring liveness test that executes
  its real selected implementation rather than accepting an existing path as
  proof of life.
