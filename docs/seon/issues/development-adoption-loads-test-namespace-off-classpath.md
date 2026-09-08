---
type: issue
status: open
severity: blocker
tags: [issue, operator, test, wave/publication-velocity]
---

# Development adoption tries to reload an unavailable test namespace

On 2026-09-08, record-render's `bin/seon init --dev default --changed
test/my/agent_test.clj test/seon/render/web_test.clj` completed publication
and began development reloads, then refused while loading
`seon.test-support`:

```text
Could not locate seon/test_support__init.class, seon/test_support.clj
or seon/test_support.cljc on classpath.
```

The preceding reload list included `seon.test.arm`, `seon.test.cache`,
`seon.test.runner`, `seon.sci.eval`, `seon.cluster.loop`,
`seon.render.web`, and `seon.cluster`. The same failure occurred during the
HTTP repair's automatic publication. Log: `tmp/record-render-publication10.log`.

Owner: development adoption's namespace selection and loaded classpath.
No change to that concurrent mechanism was attempted. Acceptance: admitted
test edits can publish and development adoption completes without trying
to load an unavailable namespace; the source commit converges afterward.
