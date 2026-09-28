# Shared analysis cache isolation

The analyzer already disabled caching for scratch inputs, but `fn/analyzed-files`
passes the shared directory explicitly. `analyze` treated every explicit directory
as private, letting fixture definitions replace namespace entries used by boot.
The resolver directory now follows canonical input/cache ownership even when supplied.
A private resolver remains available. No cache repair or second cache is introduced.

SIMPLER: source +17/-16 (net +1); tests +23. Delete the explicit-directory bypass
and move the missing-input throw into the input contract (paths or sources required).
One selector is available to the namespace-eviction caller as well.
FASTER: same live default REPL anonymous parent/candidate probe, four pairs:
7.106/4.995, 4.746/4.522, 4.575/4.620, 4.727/4.321 ms. Warm median
4.727 -> 4.522 ms. This is primarily a boot-correctness fix, not a large speedup.
Parent overwrote an isolated copy of a dependency entry on every run; candidate
performed no resolver write. Real shared dependency entry (4708 bytes) remained
byte-identical after candidate analysis. Both produced [stub]. Probe envelope 53 ms.
CORRECT: new data regression
seon.fn.analyzer-test/fixture-names-cannot-replace-shared-namespace-analysis
covers direct and mirrored fixtures; absent, relative and canonical shared-cache
arguments; unchanged decoded namespace entry; explicitly private cache still works.
Root also run seon.fn.analyzer-test/scratch-analysis-does-not-write-the-shared-cache
and seon.fn.analyzer-test/supplied-checkout-files-are-analyzed-once-with-their-current-content.
No tests run by this lane.

Dependency seam: pinned clj-kondo 57252e07975710aa579b24f0d1b2b1e04195caa2,
core.clj:143 does not resolve any disk cache when :cache false; impl/cache.clj:165
writes only when cache-dir exists. impl/cache.clj:23 reads entries by namespace,
which is why a fixture of an existing namespace must not write the shared directory.
Cause: parent analyzer.clj:710 explicit cache-root bypass, fn.clj:2942 default.
Candidate selector analyzer.clj:279; applies before invoke-kondo in analyze.

Archive c717d615178a3d4dab5155c503e57a756c35a18d + only these two files:
clj-kondo src script test --cache false: 0 errors, 1600 existing warnings, 12 infos;
normalized finding multiset unchanged. Contract compile check returned [] in 71 ms.
No schema resource or boot ordering changed. Candidate bodies were evaluated as
anonymous lexical functions; production Vars were not replaced. The live owner
seon.cluster/rehearse-source! is NOT installed, so no branch adoption is claimed.

Foreign boundary: src/seon/fn.clj has a foreign uncommitted caller-cache edit.
It was not edited. patches/root/03-fixture-cache-eviction.patch applies the same
selector before forgetting old namespaces. Without that caller patch, incremental
fixture publication with a previous database can still delete shared entries (the
new analyzer prevents replacing them). Root must apply the patch before that usage.

Default restarted externally during probes (1fd7ed8b0 -> c717d6151). No restart,
stop, reset, tests or JVM launches from this lane. RESTART NEEDED for installation.
Store du: followup entry 1124472 KiB; before final probes 1241264; after 1241864.
Global +117392 KiB spans external boot/test work, so it cannot certify this lane's
50 MB budget. The small final window is +600 KiB. Probe bodies transact nothing;
MCP entrance settlement overhead per 1000 evaluations was not separately measured.
