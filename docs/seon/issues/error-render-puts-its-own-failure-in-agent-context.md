---
type: issue
status: open
severity: friction
tags: [issue, render, error, agent]
---

# Give `ai-prose` the ref shape the render walk actually hands it

## Problem

An agent that has ever caused a recorded fault reads this sentence in its own
prompt:

```text
(:seon.error/agent) The seon.error/ai-prose projection threw:
Don't know how to create ISeq from: java.lang.Long
```

`seon.error/render-ai` normalizes a pulled error entity with
`seon.render.walk/transacted`, which unwraps `{:db/id 4379}` to the eid `4379`
— by design, since that is "the shape it was transacted in" for identification.
`ai-prose` then does `(second run)` on `:seon.error/run`, expecting a lookup-ref
vector, and throws on the Long.

`render-ai`'s own `catch Throwable` never fires: the throw happens inside
`render-output`, whose guard formats the failure **as the rendered text** and
returns it. So the documented fallback (`kind: message`) is unreachable and the
projection's failure becomes agent-facing prose instead.

Passing the pulled entity straight to `ai-prose` is the mirror defect: it does
not throw, it returns `" (). Inspect error ; nothing was retried. Signature: ."`
— every field empty, because the fields live under `:seon.error/fact` in a
notice. A projection that silently renders empty prose for a real fault is the
same class of lie.

Two facts about one error entity disagree on whether a ref is an eid or a
lookup ref; the boundary should say once.

## Evidence

Live on cluster `preflight-mvp` at HEAD `24aaacbac`, fault
`2de3c0c1-fd13-4c6c-962c-64719cd7dcde`:

```clojure
(let [unit (assoc (d/pull db '[*] [:seon.error/id "2de3c0c1-…"]) :seon.db/db db)]
  {:projection      (walk/projection unit :seon.render/ai)   ; => nil
   :run-value       (:seon.error/run unit)                   ; => #:db{:id 4379}
   :render-ai       (seon.error/render-ai unit)
   ; => "The seon.error/ai-prose projection threw: Don't know how to create
   ;     ISeq from: java.lang.Long"
   :ai-prose-direct (seon.error/ai-prose unit)})
   ; => " (). Inspect error ; nothing was retried. Signature: ."
```

The same string reached a real prompt: it is inside the committed capture
`52804cce-ed84-4806-bc9d-ebce2c105c53-context-536870949`, in the
`seon.render.agent/namespace-ai` contribution.

- `src/seon/error.clj:466-506` — `ai-prose`, `(second run)`.
- `src/seon/error.clj:837-866` — `render-ai` and its unreachable fallback.
- `src/seon/render/walk.clj:86-112` — `transacted` unwrapping refs to eids.
- `walk/projection` returning `nil` for a stored error entity is a second
  question worth answering in the same fix: the family declares
  `:seon.render/ai seon.error/render-ai`
  (`resources/seon/schema/error.edn:77`), so the resolution chain not finding it
  deserves its own look.
- `docs/prds/sci-execution-runtime/research/turn-loop-preflight-2026-07-31.md`.

Still live 2026-07-31 on `ef8cc6f77`, cluster `visual-qa`, agent `scout` —
the failure text has changed but is still agent-facing, now a contract
violation rather than an ISeq error:

```text
;; path=[:seon.render.walk/neighbours 4 :seon.render.walk/neighbours 9]
;; depth=2 provenance=seon.error/render-ai
The seon.error/ai-prose projection threw: seon.error/ai-prose violated its
contract (invalid-input): [#:seon.render{:would-fall-to-floor? ["disallowed key"]}]
```

The walk now hands `ai-prose` a unit carrying `:seon.render/would-fall-to-floor?`,
which its closed input schema rejects. Evidence:
`tmp/visual-qa/ai-scout.txt:194-195`,
`docs/prds/sci-execution-runtime/research/visual-qa-2026-07-31.md`.

## Owner

`src/seon/error.clj` with `src/seon/render/walk.clj`.

## Acceptance

A recorded fault reachable from an agent's neighbourhood renders the prose the
notice path would have produced — what happened, what it means, where the
evidence is — with no projection-failure text and no empty fields, proved by a
recurring test that walks an agent holding a committed fault and asserts on the
rendered contribution. No renderer may emit its own exception message as agent
context; if a projection cannot render a unit it says nothing.
