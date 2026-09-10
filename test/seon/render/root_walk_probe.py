#!/usr/bin/env python3
"""Sample bounded GETs with virtual-thread dumps, like tmp/root-probe/run.sh.

Run after clearing the cluster's disposable render cache through its JVM REPL.
The first request to each page is cold; later requests preserve the cache.
Curl records HTTP time separately from the sampler's one-second polling floor.
"""

import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
import time


class Text(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []

    def handle_data(self, value):
        if value.strip():
            self.parts.append(value.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--pid", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--rounds", type=int, default=6)
    parser.add_argument("--interval", type=float, default=25)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    for round_number in range(args.rounds):
        for page, path in [("root", "/"), ("juniper", "/agent/juniper")]:
            for sample in ["first", "warm"]:
                name = f"{round_number}-{page}-{sample}"
                html = args.output / f"{name}.html"
                started = time.monotonic()
                with subprocess.Popen(
                    ["curl", "--max-time", "60", "-sS", "-o", str(html),
                     "-w", "%{http_code} %{time_total} %{size_download}",
                     args.url.rstrip("/") + path],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                ) as process:
                    dumps = 0
                    while process.poll() is None:
                        dump = args.output / f"{name}-{dumps}.threads"
                        subprocess.run(
                            ["jcmd", str(args.pid), "Thread.dump_to_file", "-overwrite",
                             str(dump.resolve())],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            check=True, timeout=15,
                        )
                        dumps += 1
                        time.sleep(1)
                    output, error = process.communicate(timeout=5)
                    if process.returncode:
                        raise RuntimeError(error)
                status, seconds, size = output.split()
                if status != "200":
                    raise RuntimeError(f"{name}: HTTP {status}")
                contents = html.read_text()
                text = Text()
                text.feed(contents)
                row = dict(round=round_number, page=page, sample=sample,
                           seconds=float(seconds), bytes=int(size), dumps=dumps,
                           sampler_seconds=time.monotonic() - started,
                           text_sha256=hashlib.sha256(
                               "\n".join(text.parts).encode()).hexdigest())
                rows.append(row)
                print(json.dumps(row), flush=True)
                (args.output / "measurements.json").write_text(json.dumps(rows, indent=2))
        if round_number + 1 < args.rounds:
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
