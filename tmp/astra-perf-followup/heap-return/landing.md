# Let G1 return unused heap

Default already ran with G1PeriodicGCInterval=30000, a disabled load threshold,
concurrent periodic collection, and an 8 MiB initial/minimum heap. The proposed
periodic-GC or small-Xms fixes were already installed. Its 40/70 free-space targets
kept about three times the live heap committed after boot.

The dev and test aliases now use MinHeapFreeRatio=20 and MaxHeapFreeRatio=40.
This uses the JVM's existing resize policy; no scheduler, explicit GC, or new
memory watcher. Max heap and OOM behavior are unchanged.

SIMPLER: deps +3/-1 (net +2), regression +13. No runtime code or new mechanism;
replace the VM's larger default reserve with explicit measured targets.
FASTER: same live default process (pid 42311, start 1790633512492), same five
schema/load-projection calls against a held real projection: median 27.960 ->
26.734 ms. Every result was identical to the held projection. This is a memory
fix, not a claim of a substantial latency win. RSS 3,629,696 -> 2,138,736 KiB
(3.46 -> 2.04 GiB, -41%). Heap committed 3,154,116,608 -> 1,627,389,952 bytes
(-48%); used 1,166,657,176 -> 969,310,840 bytes. No forced GC or restart.
CORRECT: seon.dev.heap-options-test/periodic-collection-bounds-unused-heap
asserts actual JVM option data. Root runs it centrally; this lane ran no tests.
The performance probe compares returned projection identity, not printed output.
The shorter observation window does not prove sustained allocation throughput;
root's ordinary integration round supplies that evidence.

Applied these two manageable VM options through HotSpotDiagnosticMXBean after
checking pid AND start time. They remain active on that generation of default.
RESTART NEEDED to install the committed flags in future JVMs; do not restart just
for measurement. Runtime input arguments still show the original launch flags;
getVMOption reports the effective 20/40 values. Probe/result and ps rows are beside
this note. The policy took a later periodic collection to shrink, not the first.

Archive b84e71709 plus only deps.edn and this test: clj-kondo src script test
--cache false: 0 errors, 1600 prior warnings, 12 infos, 0 new findings. Contracts
compile check [] in 69 ms. Parent findings use the prior archive lint that produced
b84e71709, normalized by path/type/message. No schema/adoption behavior changed.
Foreign boundary: operator and cluster changes remain uncommitted and untouched.
