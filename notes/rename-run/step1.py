#!/usr/bin/env python3
"""step 1 of rename-run: the test run keeps its name where `run` would clash with the new noun.
Idempotent, tolerant: a pattern that is absent is skipped."""
import re, subprocess
files = [f for f in subprocess.run(['git','ls-files'],capture_output=True,text=True).stdout.split('\n')
         if f and not f.startswith(('reference-code/','notes/')) and f.endswith(('.clj','.edn','.md','.sh')) or f in ('bin/check','bin/test','bin/prove')]
n = 0
for f in files:
    try: s = open(f).read()
    except Exception: continue
    t = s
    t = re.sub(r'(?<![\w-])run-errors', 'test-run-errors', t)
    t = re.sub(r'(?<![\w-])run-answer', 'test-run-answer', t)
    if f == 'src/seon/proofs.clj':
        t = t.replace('  [run]\n', '  [test-run]\n').replace('  [run tests]\n', '  [test-run tests]\n').replace('(:reports run)', '(:reports test-run)')
        for a, b in [("so every test reads as not test-run.", "so every test reads as not run."), ("{test-symbol status} of a run:", "{test-symbol status} of a test run:"),
                     ("The failing assertions of run for the tests", "The failing assertions of the test run for the tests"),
                     ("The errors of run that name no test: the run itself failed", "The errors of the test run that name no test: the test run itself failed"),
                     ("The failing assertions of run, per test", "The failing assertions of the test run, per test"),
                     ("The cost proofs' reports of run, per test", "The cost proofs' reports of the test run, per test")]: t = t.replace(a, b)
    if f == 'src/seon/turn.clj':
        # the test run local inside observe and observed, whatever main added to them
        def scoped(m):
            form = m.group(0)
            parts = re.split(r'("(?:[^"\\]|\\.)*"|;[^\n]*)', form)
            for i in range(0, len(parts), 2):
                parts[i] = re.sub(r'(?<![\w/.:-])run(?![\w!?*/-])', 'test-run', parts[i])
                parts[i] = re.sub(r'(?<![\w/.-]):run(?![\w!?*/-])', ':test-run', parts[i])
            return ''.join(parts)
        t = re.sub(r'\(defn-? observe\b.*?(?=\n\(defn)|\(defn-? observed\b.*?(?=\n\(defn)', scoped, t, flags=re.S)
    if f == 'src/seon/turn.clj':
        for a, b in [("        run (agent/run-tests-forked", "        test-run (agent/run-tests-forked"),
                     ("(if (contract/error? run) {} (proofs/results run))", "(if (contract/error? test-run) {} (proofs/results test-run))"),
                     ("{:run run :tests-ms tests-ms", "{:test-run test-run :tests-ms tests-ms"),
                     ("[{:keys [run reasons task results will-merge]}]", "[{:keys [test-run reasons task results will-merge]}]"),
                     (":reds (if (contract/error? run) [] (proofs/red-reports run named))", ":reds (if (contract/error? test-run) [] (proofs/red-reports test-run named))"),
                     ("(if (contract/error? run) [(:seon.error/message run)] (proofs/test-run-errors run))", "(if (contract/error? test-run) [(:seon.error/message test-run)] (proofs/test-run-errors test-run))"),
                     ("the run's own errors, and what the", "the test run's own errors, and what the")]: t = t.replace(a, b)
    if f == 'src/my/agent.clj':
        for a, b in [("(let [run (agent/run-tests-forked", "(let [test-run (agent/run-tests-forked"), ("(assoc run :results (proofs/results run))", "(assoc test-run :results (proofs/results test-run))"),
                     ("Answers that run ({:pass", "Answers that test run ({:pass")]: t = t.replace(a, b)
    if t != s: open(f, 'w').write(t); n += 1
print('step1: files changed', n)
