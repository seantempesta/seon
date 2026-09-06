---
type: research
status: complete
tags: [research, render, web, testing]
---

The debug page compares two independently identified observations. `latest-captured-prompt` queries the latest `:seon.context.capture` linked through the agent's run and retains the captured prompt, its `:seon.context.capture/basis-t`, and capture identity (`src/seon/render/web.clj`, around lines 616–637). `prospective-prompt` derives a fresh prompt from the supplied database and render inputs and records the database basis used for that observation (around lines 654–691).

`debug-prompt` keeps the optional captured result beside the prospective result; a database query failure is represented as the existing flat error value, and no capture remains absent rather than being presented as a successful historical observation (around lines 693–714). `debug-ai-html` gives each pane its own label and basis: “historical captured prompt” and “newly computed prospective prompt”. An unavailable prospective result displays its diagnostic fields instead of pretending that a prompt exists (around lines 716–751).

The focused regression in `test/seon/render/web_prompt_test.clj` uses `seon.test-support/with-database` and real capture/run/agent facts for the captured query. It stubs only prospective computation to isolate the presentation comparison, and separately covers no capture plus an unavailable prospective result. The direct test remains dependent on the current shared `web.clj` source parsing and publication state; unrelated web edits must be integrated before the JVM test can be run.

## Root integration proof

Root ran the two focused Vars through `seon.test.runner/run-var!` with the
`:test` classpath and a 180-second bound. Results: 77 and 6 passing assertions,
zero failures/errors. The earlier 30-second termination was not a test verdict.

After hot-reloading current `seon.render.web` and reapplying instrumentation on
`lab-browser-0906`, the committed `debug_prompt_browser_probe_2026_09_06.cjs`
opened the ordinary prompt comparison disclosure. It positively observed an
actual historical capture at basis 536870973 and a nonempty computed preview at
basis 536871155 (42,010 characters), each separately labelled; no JavaScript
errors. The first browser timeout was a test mistake: it waited on `innerText`
inside a closed disclosure. Opening the disclosure resolved it. The browser
probe performs no writes or model calls; it does not certify provider parity.
