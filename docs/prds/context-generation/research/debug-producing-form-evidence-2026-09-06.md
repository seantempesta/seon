---
type: research
status: complete
tags: [research, context, render]
---

# Debug producing-form evidence — 2026-09-06

## Question and finding

Can the current debug page show a truthful, replayable Clojure form beside the
actual selected renderer output using evidence already retained by
`render-call`?

No. The current renderer executes a named function application, not a Clojure
form. `seon.render/invoke-selected` prepares one argument and calls
`seon.sci.kernel/invoke` (`src/seon/render.clj:565-596`). The kernel resolves
the SCI Var, calls the one `seon.call-preparation/hook`, and applies the Var to
the prepared argument vector (`src/seon/sci/kernel.clj:544-595`). No source
string or form is read or evaluated on this path.

The retained call is still strong evidence. It contains the exact selection,
selected function, declaration row, a projection of the supplied argument,
read evidence, basis transaction, and actual admitted output
(`src/seon/render.clj:468-508,818-885`). The debug result consumes this same
retained call rather than selecting again (`src/seon/render/web.clj:1210-1252`).
The declaration row includes the selected function's actual definition source;
that source is useful definition evidence, but it is not the producing call.

## Dependency boundary

SCI's `:call-preparation-hook` receives the runtime context, resolved Var, and
already evaluated argument vector and returns either the arguments actually
applied or a reduced refusal (`reference-code/sci/src/sci/core.cljc:309-327`).
Seon's hook can insert positional values or map entries before the application
(`src/seon/call_preparation.clj:923-997`). The declared supplied values are the
current database value, connection, and agent id
(`config/default.edn:451-465`). There is no supplied SCI context, render
profile, admission caps, or time limit.

The current display projection is intentionally pre-hook and removes the
database value, connection, and SCI context
(`src/seon/render.clj:468-479`). Therefore a list assembled in the web owner as
`(selected-renderer 'displayed-argument)` would not be the executed
application. It may omit values the selected function read, and it does not
record whether call preparation inserted anything or refused the call. A
successful replay for the current `my.plan/render-item-html` example would not
prove the general claim because that function does not need the omitted render
custody.

The model-reply reader does not repair this gap. `seon.cluster.reply/sources`
turns model text into exact source strings and delegates reading to
`seon.sci.reader` (`src/seon/cluster/reply.clj:344-403`). The debug renderer has
neither model text nor executed source. Sending a newly printed call through
that reader would create a second execution rather than explain the retained
one.

## Available diagnostic evidence

Render the existing retained call beside the output as **executed function
application**, with these existing values:

- selected function from `:seon.render.call/producer`;
- supplied argument projection from `:seon.render.call/argument`;
- function definition source, docstring, and contract from the retained
  declaration row;
- admitted output, basis transaction, and read evidence from the same call.

The panel must state that replay is unavailable because the displayed argument
omits the database value, connection, and SCI context and because the
post-hook argument vector is not observed. This needs no second selector,
database read, invocation, parser, registry, or durable fact. Definition source
should be labelled **selected function definition**, not producing form.

Such a panel would be truthful diagnostic evidence because every displayed
claim follows from the retained action. It does **not** satisfy the user's
producing-form requirement, must not be shipped as its substitute, and must not
be labelled as though it were an executed or replayable form.

## What a real producing form requires

If an actual Clojure form remains required, the form must become the value the
existing invocation authority executes. The change belongs at
`seon.sci.kernel/invoke`, after function resolution and at the same
call-preparation/time-limit/admission/read-evidence boundary. That owner would
need to expose one bounded application record containing the resolved function,
post-hook arguments or refusal, and admitted result, or replace the direct
`apply` with an SCI-evaluated form while preserving those exact guarantees.

Before choosing the latter, an immutable dependency probe must prove SCI can
evaluate a form carrying the actual render argument's host values without a
temporary binding, source re-read, context mutation, second renderer call, or
loss of the call-preparation hook. The current evidence does not prove those
properties. A generated form that performs a fresh `pull` also changes the
question: it can read a different database basis and duplicates the already
retained acquisition.

SCI `eval-form` analyzes and then evaluates the supplied form
(`reference-code/sci/src/sci/core.cljc:421-427` and
`reference-code/sci/src/sci/impl/interpreter.cljc:29-83`). A direct resolved
Var call in that analyzed form invokes `:call-preparation-hook` itself
(`reference-code/sci/src/sci/impl/analyzer.cljc:1793-1807`). The current named
invocation calls the same hook manually precisely because `apply` has no
analyzed direct-call node (`src/seon/sci/kernel.clj:576-588`). Replacing it with
form execution must therefore remove the manual hook on that branch or it will
prepare twice. It must also preserve the kernel's existing function loading,
time-limit arm, host-interop observation, built-in-call observation, read sink,
result admission, and failure conversion.

This changes render cost: each invalidated call must analyze its value-specific
form before execution. The retained render-call cache still suppresses that
work for unchanged calls, but a source string would additionally require
printing and reading the argument. A form object avoids that parse cost yet is
not displayable source when it contains database values, connections, SCI
contexts, or other host objects. A faithful displayed source therefore needs a
serializable argument representation whose omitted world is supplied at the
same call boundary; no current supplied default restores the SCI context.

The web owner should only render the evidence produced by that one invocation;
it must not construct a plausible source string from producer and output.

## Related live candidate finding

The successful live plan-item proof exposed a separate candidate explanation
defect. For HTML, `seon.render/selection` asks namespace candidates to declare
`:seon.render/html` (`src/seon/render.clj:448-451`), while an executable HTML
renderer such as `my.plan/render-item-html` declares its return as
`:seon.render/hiccup`. `:seon.render/html` is the declaration property/value
union of a qualified function symbol or Hiccup
(`resources/seon/schemas/seon.render.edn:1-35,105-106`); it is not the function
return schema. Consequently the live namespace stage reported that exact
function as `rejected :no-same-arity-match`, then the schema-property stage
selected and successfully invoked it. This is an explanation and namespace
preference defect, not an invocation failure.
