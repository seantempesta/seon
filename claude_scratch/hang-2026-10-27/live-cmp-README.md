# Live selector comparison: agents hung at turn 0 in prove! (relevance-lab root, 2026-10-27 20:10-21:12)

Six paid runs (deepseek) on relevance-lab's root, port 10080. Three tasks, each run with the selector on and off.
After 61 minutes, no run had reached turn 1:
- three tasks had no run at all;
- one run died at turn 0 ("No dispatch macro for: '" on a ~2 MB line);
- two runs sat at turn 0.

- pid 66127 (an agent): parked in seon.sock/recv! <- seon.agent/await-op! <- seon.agent/prove! <- seon.turn/observe <- seon.turn/work!.
  It was waiting an hour for the answer to its turn-0 prove, and had a zombie child, 37966.
- pid 68505 (child of the fork server, a5's process per the 20:13 stall dump): one running fiber,
  175e9 ticks, with no saved continuation. Its sample shows filesystem walking: open 1197, stat 614,
  getdirentries 115 of 2624 samples, plus getcwd. Its cwd was /private/tmp/seon-agents.
- Machine load was 15-17 throughout, from other sessions' runs.

Files: live-cmp-sample-{66127,68505}.txt, live-cmp-lsof-*.txt, live-cmp-root-log-tail.txt (the fiber dumps).
Older stall dumps: .worktrees/relevance-lab/runs/stalls/.
Root stopped at 21:12; its process group is gone.
Open question: does turn 0's observe -> prove! walk the tree per test digest, and is that what takes an hour under load?
