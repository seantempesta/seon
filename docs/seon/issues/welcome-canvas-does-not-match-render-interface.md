---
type: issue
status: open
severity: blocker
tags: [issue, web, cljs, agent]
---

# Welcome canvas does not match the render interface

## Problem

`seon.render.canvas/welcome` accepts only its render request, while the existing
dynamic HTML render interface supplies both the request and the nested-render
callback. The compiled Bun child treats this core function as a selected
renderer and supplies the common second argument, so every new ordinary agent
shows `:malli.core/invalid-arity` on its default canvas.

## Evidence

A real uncached agent birth succeeded and its gzip Datastar feed returned a
complete agent view, but both the primary canvas and its rail preview contained
the render error. The plan and transcript surfaces in the same child rendered
normally, isolating the mismatch to the selected welcome function.

## Owner

`seon.render.canvas/welcome` owns the default canvas. It must implement the
existing dynamic render function interface directly; no adapter or alternate
canvas is required.

## Acceptance

- The function schema and arguments accept the render request and nested-render
  callback.
- Focused canvas tests call the function through that interface.
- A new ordinary agent's real gzip feed contains the welcome canvas and no
  Malli or render error.
