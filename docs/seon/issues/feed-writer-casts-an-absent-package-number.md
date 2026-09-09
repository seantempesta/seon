---
type: issue
status: open
severity: friction
tags: [issue, render, web, class/p3]
---

# Feed writer casts an absent package number

Observed 2026-09-08 on components scratch root, HEAD `f01a9a824` plus the
message renderer and agent concern declarations. Opening Juniper's debug page
returned HTML successfully, then the browser feed recorded two faults with
signature `3246021d6db8f35f905e54999e03ccd3cf5ba096cd34ff0c41649d6c668a4bca`.

The durable fault `01db61f4-e94f-41b1-afb9-46d401377c2b` names proc
`:seon.render.web/feed`, wrapper message `The browser feed writer failed.`,
and cause `Cannot invoke "java.lang.Number.doubleValue()" because "x" is null`.
Its trace is `clojure.lang.RT/longCast`, then `web.clj:2865`, then the virtual
thread. This is the cast of `:seon.render.package/basis-transaction` after
`(get packages registration-key)` in the feed loop. The evidence does not
establish whether the package or only that field was absent.

The fault also produces an agent-directed message claiming interrupted
bootstrap work; no work was re-executed by this lane. The ordinary GET and the
message AI/HTML pair succeeded. See the
[components landing note](../../prds/context-generation/research/components-landing-2026-09-08.md)
and its scratch screenshots. `web.clj` is protected by page-feed ownership;
this lane did not edit it. Probe the missing registration/package transition
at the feed owner and make absence explicit before the numeric comparison.
