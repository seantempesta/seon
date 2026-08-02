---
type: reference
status: abandoned
tags: [reference, web, history]
---

# Historical Datastar dependency research

> Historical source material only. This page does not describe Seon's current
> web API, event construction, route layout, or update path. Use
> [[datastar-quick-reference]] and the vendored SDK source.

This research was captured on 2025-12-02 against Datastar 1.0.0-RC.6. It
helped establish the useful dependency concepts that remain visible in fresh
Seon: server-sent events, stable element identity, outer-element morphs, and a
thin browser driven by server state.

The original copy-paste recipes and protocol tables were deleted when their
version and Seon integration path ceased to be current. Git preserves that
snapshot. The maintained dependency authority is now:

- `reference-code/datastar/` for the browser implementation;
- `reference-code/datastar-clojure/libraries/sdk/` for event APIs;
- `reference-code/datastar-clojure/libraries/sdk-http-kit/` for the http-kit
  adapter; and
- `src/seon/render/web.clj` for Seon's use of those APIs.
