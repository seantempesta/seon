"""Summarize JDK execution samples without treating samples as elapsed time.

Usage:
  jfr print --events jdk.ExecutionSample --stack-depth 64 recording.jfr > samples.txt
  python3 reset-writer-profile-2026-09-17.py samples.txt

Record only the lane's test JVM. The first Seon frame locates the sampled
work; a truncated stack may omit write-report-error, so its count is a lower
bound on samples whose full stack passed through the final-report validator.
"""
import collections
import pathlib
import sys

samples = pathlib.Path(sys.argv[1]).read_text().split("jdk.ExecutionSample {")[1:]
all_frames = collections.Counter()
report_frames = collections.Counter()
for sample in samples:
    frame = next((line.strip() for line in sample.splitlines() if "seon." in line), None)
    if frame:
        all_frames[frame] += 1
        if "seon.db$write_report_error" in sample:
            report_frames[frame] += 1
print(f"execution samples: {len(samples)}")
for label, counts in (("first Seon frame", all_frames), ("final-report stack", report_frames)):
    print(f"{label}: {sum(counts.values())}")
    for frame, count in counts.most_common(12):
        print(f"{count:6d} {frame}")
