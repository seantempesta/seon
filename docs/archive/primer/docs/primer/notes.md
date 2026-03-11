---
type: prd
status: completed
tags: [prd, archive]
---

# Primer Implementation Notes

Gotchas, learnings, things that surprised us.

---

## Notes from Planning

1. **Game engine parallel is key** - Agent does "planning phase" (slow, thoughtful), runtime does "execution phase" (fast, pre-computed). Only invoke AI dynamically when truly needed.

2. **Ctx atom IS the app** - Everything derives from ctx. Adding features = adding keys. Rendering = walking data.

3. **Specs are the contract** - Agent can only write data matching specs. This prevents garbage and makes output predictable.

4. **Datastar SSE is perfect fit** - Server owns state, client is reactive view. No React hydration complexity.

---

## To Remember

- Always `(reset)` after code changes, not `require :reload`
- Primer XTDB data lives in `data/primer/` (separate from main)
- Ctx watch handles SSE refresh automatically - don't call `refresh-all!` manually
- Use `state/update-ctx!` not raw `swap!` to get validation
