---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, render, error, repl, dogfood]
---

# A renderer's refusal renders through the same renderer, nesting until the depth bound

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive a1b01c92a, branch `dogfood-repl-opus-2`, 2026-09-26)

The branch was stale (see [a branch forked before an adoption](a-branch-forked-before-an-adoption-refuses-every-evaluation-after-it.md)).
`(in-ns 'my.issue) (str *ns*)` answered one error of about 25 KB (the MCP tool truncated
12,172 characters of it). Its shape:

```
{:seon.error/operation seon.render/unknown,
 :seon.error/message "The selected renderer did not return an observation.",
 :seon.render.unknown/producer seon.error/render-ai, :seon.render.unknown/reason :refused,
 :seon.render.unknown/refused-operation seon.sci.eval/install-row!,
 :seon.error/offending
   {… the same error …
    :seon.error/offending
      {… the same error …
       :seon.error/offending {… nested about 8 deep …}}}}
```

The underlying cause, the `install-row!` refusal naming the host-bound declaration, appears
nowhere in the shown text. Only its operation name appears.

## Which model is wrong

The error value's declared renderer (`seon.error/render-ai`) runs in the same failing world
(acquisition refused, so the renderer cannot run either). Its refusal becomes a new error value.
That value is rendered through the **same selection**, which picks `seon.error/render-ai`
again, which refuses again, and so on until the depth bound. The diagnostic path re-enters the
path that failed.

A render failure's diagnostic must be printed by the **floor**, which needs no program. It should
carry the original value's floor print and the renderer's refusal, one level deep. The original
cause must stay visible ("Keep the whole cause").

## Expected

One level: the original error printed at the floor (with its message naming the host-bound
declaration), then one diagnostic line naming `seon.error/render-ai` and why it could not run.

## Regression

With a renderer that always refuses, rendering an error value it declares yields a bounded,
one-level diagnostic that contains the original message.
