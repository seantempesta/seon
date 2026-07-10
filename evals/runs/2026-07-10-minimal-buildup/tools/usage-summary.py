#!/usr/bin/env python3
# usage-summary.py DIR TAG... — per-drive telemetry rollup from the META
# lines min-extract.bb writes: turns, forms/evals, tokens (prompt/completion,
# cache hit/miss), REPORTED vs ESTIMATED kept in SEPARATE columns (never
# averaged together — Mode B aborted turns are client-side estimates),
# results-stripped (Mode A), aborted/estimated turns (Mode B), retries,
# and Mode B one-form-per-turn verification (max evals in any turn).
import sys, re, json, os

d = sys.argv[1]
tags = sys.argv[2:]

def num(usage, key):
    m = re.search(r':' + key + r'\s+(\d+)', usage)
    return int(m.group(1)) if m else 0

hdr = (f"{'tag':28} {'turns':>5} {'evals':>5} {'maxE/t':>6} {'strip':>5} "
       f"{'estTurns':>8} {'rep.prompt':>10} {'rep.compl':>9} {'cacheHit':>8} {'cacheMiss':>9} "
       f"{'est.prompt':>10} {'est.compl':>9} {'retries':>7} {'wall':>5} {'close':>12} {'outcome':>7}")
print(hdr)
for tag in tags:
    txt = open(f"{d}/transcripts/{tag}.txt", encoding="utf-8", errors="replace").read()
    turns = re.split(r'^════════ TURN ', txt, flags=re.M)[1:]
    rp = rc = ch = cm = ep = ec = strip = retries = est_turns = 0
    max_evals = 0
    total_evals = 0
    for t in turns:
        m = re.search(r'^META usage=(.*) estimated=(true|false) results-stripped=(\d+) retries=(\d+)$',
                      t, flags=re.M)
        nev = len(re.findall(r'^\s*── eval eid=', t, flags=re.M))
        total_evals += nev
        max_evals = max(max_evals, nev)
        if not m:
            continue
        usage, est, st, rt = m.group(1), m.group(2) == 'true', int(m.group(3)), int(m.group(4))
        strip += st
        retries += rt
        p = num(usage, 'prompt_tokens'); c = num(usage, 'completion_tokens')
        if est:
            est_turns += 1
            ep += p; ec += c
        else:
            rp += p; rc += c
            ch += num(usage, 'prompt_cache_hit_tokens')
            cm += num(usage, 'prompt_cache_miss_tokens')
    # response.json + index for close/outcome/wall
    close = outcome = wall = "?"
    rj = f"{d}/transcripts/{tag}.response.json"
    if os.path.exists(rj):
        try:
            jd = json.load(open(rj))
            close = str(jd.get("closed_reason"))
        except Exception:
            close = "unparsed"
    wc = f"{d}/transcripts/{tag}.wallclock.txt"
    if os.path.exists(wc):
        wall = open(wc).read().strip() + "s"
    try:
        for line in open(f"{d}/transcripts/index.txt"):
            if line.startswith(tag + " "):
                mo = re.search(r'outcome=(\S+)', line)
                outcome = mo.group(1) if mo else "?"
    except FileNotFoundError:
        pass
    print(f"{tag:28} {len(turns):>5} {total_evals:>5} {max_evals:>6} {strip:>5} "
          f"{est_turns:>8} {rp:>10} {rc:>9} {ch:>8} {cm:>9} "
          f"{ep:>10} {ec:>9} {retries:>7} {wall:>5} {close:>12} {outcome:>7}")
