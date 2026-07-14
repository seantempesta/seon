---
type: issue
status: open
severity: friction
tags: [issue, web, component]
---

# Remove the Node module-register deprecation from CSS builds

## Problem

Every canonical CSS build succeeds but Node 26 emits `DEP0205` because a
dependency calls deprecated `module.register()`. Repeated operator warnings
obscure new build regressions and indicate the CSS toolchain is not yet clean on
the selected Node runtime.

## Evidence

The destructive reset and following restart both emitted the warning while
running `tailwindcss -i resources/public/css/input.css -o
resources/public/css/output.css`. The selected closure is Node `26.4.0`,
`@tailwindcss/cli`/`@tailwindcss/node`/`tailwindcss` `4.1.18`, and `jiti`
`2.6.1`. The warning asks callers to use `module.registerHooks()` instead.

## Owner

The selected Tailwind/Jiti Node dependency closure and the one `css:build`
script in `package.json`; not the Babashka operator or web runtime.

## Acceptance

- Use `--trace-deprecation` once to identify the exact dependency call site.
- Select the smallest maintained dependency upgrade or upstream fix that
  supports Node 26; do not hide all Node deprecations globally.
- Canonical CSS build output remains deterministic and the operator, pod, and
  visual theme gates pass without `DEP0205`.
