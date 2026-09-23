# Errors render once

```edn
{:seon.doc/id    seon.doc.errors-render-once
 :seon/must-read [seon.render/schema-producers]
 :seon/see-also  [seon.error/render-ai]}
```

Every Seon error is a flat map that satisfies `:seon.error/base`. Today the base schema
declares no renderer, so about 275 error schemas each repeat `seon.error/render-ai`, and a
value that satisfies two of them renders as "More than one function accepts this value",
which hid a plain Datalog parse error from an agent ten times on default.

Renderer selection already picks the most specific schema a value satisfies that declares
`:seon.render/ai`. Declaring the pair once on the base schema lets every error inherit it,
and lets a narrower error schema override it when it genuinely needs to.

## Declare the pair on the base schema

```edn
{:seon/must-read [seon.render/schema-producers seon.error/render-ai]
 :seon.doc/tasks
 [{:seon.task/id          seon.doc.errors-render-once/declare-base-renderer
   :seon.task/ns          seon.error
   :seon.task/context     seon.task/implement
   :seon.task/instructions "Declare :seon.render/ai and :seon.render/html on :seon.error/base, then delete every copy of seon.error/render-ai from narrower error schemas that adds nothing."
   :seon.task/delivers    #{seon.error/render-ai}
   :seon.task/done-when   #{seon.error-test/base-renderer-selected-once
                            seon.error-test/no-ambiguous-error-render}}]}
```

A value that satisfies several error schemas must render through exactly one function. The
two tests name that behaviour; neither exists yet, so both start red.

## Keep the result handle

```edn
{:seon/must-read [seon.repl/response]
 :seon.doc/tasks
 [{:seon.task/id          seon.doc.errors-render-once/keep-handle
   :seon.task/ns          seon.repl
   :seon.task/context     seon.task/implement
   :seon.task/instructions "When a value has a declared renderer, keep the result handle entry in the response instead of dropping it."
   :seon.task/delivers    #{seon.repl/response}
   :seon.task/done-when   #{seon.repl-test/rendered-refusal-keeps-handle}}]}
```

`seon.repl/response` returns only the rendered text when a renderer is declared, so the
agent sees a refusal it cannot reach as `rN`.
