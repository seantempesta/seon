---
type: issue
status: resolved
severity: blocker
tags: [issue, web, agent, wave/agent-context]
---

# The default system-turn preview contains only an elision

On 2026-09-08, after the HTTP handle repair `4c24e894d`, Juniper's debug
page returned 200 but its would-be section showed only:

```text
Additional render-walk content was elided by the active render profile.
:seon.error/kind
:seon.render.walk/elided
```

The page calls `seon.turn/system-turn` with the actual cluster handle and
`:seon.turn/write? false`. The returned value is already an error before
`system-turn-html` can render its `:seon.turn/forms`. Inspect the
`declared-sources` / `walk/neighborhood` boundary in `src/seon/turn.clj`;
that file is concurrently owned by turn-cut and was not changed.

The separate stored-context section names unavailable `seon.eval/of-agent`;
its implementation is still absent. This blocks the chronological stored
prefix, as-of checks, digest, and full fresh/system/virtual/compact proof.

Acceptance: the preview supplies each form's status, changed facts, and
evaluated bytes; stored context renders through the evaluation schema pair.
Evidence: `tmp/record-render-system-forms-debug.html` and its extracted text.

Follow-up, 2026-09-08 20:30 UTC: after later development reloads the
preview produced 11 source blocks rather than the elision. The observation
is therefore intermittent across the partial adoption. The emitted blocks
still include legacy per-step calls instead of the §17 plan component.
The observed source bytes are retained in
`docs/prds/context-generation/research/record-render-juniper-ai-observed-2026-09-08.txt`.
The absence of `seon.eval/of-agent` remains independently reproducible.

## Resolution (2026-09-15 triage)

Commit `1d5edb65c` consolidates debug inspection onto the session component and removes system-turn-html/the old independent preview section (`git log -S 'system-turn-html' -- src/seon/render/web.clj`). Audited HEAD `7e35df213:src/seon/turn.clj:1889-1909` excludes walk elision from the fatal-error return and constructs generated sources. The other explicit blocker is also gone: `src/seon/eval.clj:9-65` implements of-agent, returning chronologically ordered saved evaluations and a diagnostic for an absent agent. The old missing function and elision-as-entire-preview path cannot occur as reported. This is source proof of those mechanisms, not a live browser performance or full context-loop gate claim.

surface: render-debug-page
