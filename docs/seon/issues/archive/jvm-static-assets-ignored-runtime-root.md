---
type: issue
status: resolved
severity: blocker
tags: [issue, web, runtime]
---

# Resolve JVM static assets from the admitted runtime

## Problem

The JVM web-render process resolves `/js/datastar.js` and `/css/output.css`
only through `clojure.java.io/resource`. Source launches can satisfy that
classpath lookup, but the admitted runtime publishes the shipped files under
`$SEON_RUNTIME_ROOT/resources/public`; the live JVM routes therefore return
404 when those assets are absent from the launcher classpath.

## Evidence

The accepted overnight server capture returned 404 for both JVM asset routes.
The release owner copies and digests the files under
`resources/public`, and the process owner already supplies
`SEON_RUNTIME_ROOT` to every JVM child.

The static portion of commit `13ebc881d` resolves the immutable runtime file
first and preserves the classpath lookup as the source-launch fallback.
Commit `e8edb0bcd` constructs an isolated runtime root and proves that both
asset response bodies resolve from it. The pinned JVM run passed 2 tests / 20
assertions, including this regression and the coalesced data feed.

## Owner

`seon.web.server/resource-source` translates a public asset path to the
admitted runtime layout. Release packaging remains the authority for which
files are shipped and digested.

## Acceptance

- Both JVM static routes return 200 from a packaged runtime whose launcher
  classpath does not contain the public files.
- Source launches retain the existing classpath fallback.
- Missing files still return the bounded 404 response.
