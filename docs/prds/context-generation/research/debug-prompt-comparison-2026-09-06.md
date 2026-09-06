---
title: Debug prompt comparison evidence
status: working
---

The debug page compares two independently identified observations. `latest-captured-prompt` queries the latest `:seon.context.capture` linked through the agent's run and retains the captured prompt, its `:seon.context.capture/basis-t`, and capture identity (`src/seon/render/web.clj`, around lines 616–637). `prospective-prompt` derives a fresh prompt from the supplied database and render inputs and records the database basis used for that observation (around lines 654–691).

`debug-prompt` keeps the optional captured result beside the prospective result; a database query failure is represented as the existing flat error value, and no capture remains absent rather than being presented as a successful historical observation (around lines 693–714). `debug-ai-html` gives each pane its own label and basis: “historical captured prompt” and “newly computed prospective prompt”. An unavailable prospective result displays its diagnostic fields instead of pretending that a prompt exists (around lines 716–751).

The focused regression in `test/seon/render/web_prompt_test.clj` uses `seon.test-support/with-database` and real capture/run/agent facts for the captured query. It stubs only prospective computation to isolate the presentation comparison, and separately covers no capture plus an unavailable prospective result. The direct test remains dependent on the current shared `web.clj` source parsing and publication state; unrelated web edits must be integrated before the JVM test can be run.
