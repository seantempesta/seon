---
type: issue
status: open
severity: friction
tags: [issue, toolkit, rendering]
---

# `my.fs/write` and `my.shell/run` docstrings hide their own request shapes

## Problem

An agent writes calls from the docstring — that is the whole point of the
rendered function surface. `my.fs/write`'s docstring says:

```text
Takes a path, one text/bytes/blob content source, and an expected absence or
digest.
```

That sentence reads as a flat map, and it names none of the keys. The actual
request (`resources/seon/schemas/my.fs.edn:105-109`) is NESTED:

```clojure
{:my.fs/path         "…"
 :my.fs/content      {:my.fs/text "…"}
 :my.fs/precondition {:my.fs/expected-absence? true}}
```

Worse, the sibling function in the same namespace is FLAT: `my.fs/read`
takes `:my.fs/byte-offset` and `:my.fs/max-bytes` directly on the request,
with no wrapper. So an agent that has just used `read` successfully has been
taught the wrong shape for `write`.

The refusal is honest but incomplete — it names the missing keys and not
their shapes, so the agent still cannot repair the call without going to
find the schema:

```text
my.fs/write violated its contract (invalid-input):
[#:my.fs{:content [{:value nil, :message "missing required key"}],
         :precondition [{:value nil, :message "missing required key"}]}]
```

## Evidence

Tool-exercise lane, 2026-08-07. Five consecutive `my.fs/write` forms written
directly from the docstring were all refused this way, producing five eval
receipts with contract-violation errors and ZERO effect receipts — the door
was never crossed. Complete result:
`docs/prds/sci-execution-runtime/research/probes/tool-exercise/fs-write-preconditions.edn`.

The refusal is correctly loud and the door correctly stayed shut; the defect
is that the surface taught a shape it does not accept.

## The same defect in `my.shell/run`

`my.shell/run`'s entire docstring is "Run one foreground argv vector and
return complete process evidence." It names no keys, and
`:my.shell/cwd` is REQUIRED
(`resources/seon/schemas/my.shell.edn:4-8`). Four forms written from that
docstring were refused identically:

```text
my.shell/run violated its contract (invalid-input):
[#:my.shell{:cwd [{:value nil, :message "missing required key"}]}]
```

Two of the four capability namespaces exercised on 2026-08-07 refuse the
call an agent writes from their own docstring.

## Expected

The docstring names the keys it requires, in the shape it requires them —
`:my.fs/content` with one of `:my.fs/text` / `:my.fs/bytes` /
`:seon.blob/digest`, and `:my.fs/precondition` with one of
`:my.fs/expected-absence?` / `:my.fs/expected-digest`. Docstrings render into
agent context and are held to being true current-state.

Separately worth an owner decision: whether `read`'s flat options and
`write`'s nested groups should agree. Nesting buys the two
exactly-one-of predicates a place to live; flattening buys one consistent
namespace. Either is defensible, but the current split is a trap.

## Acceptance

An agent that reads only the rendered docstring of `my.fs/write` or
`my.shell/run` writes an accepted request on the first attempt.
