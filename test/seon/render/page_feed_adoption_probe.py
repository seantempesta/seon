"""Verify one expected render after adoption, retaining exact HTTP evidence."""
import argparse
import hashlib
import json
import pathlib
import time
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url', required=True)
parser.add_argument('--expected', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
started = time.monotonic()
with urllib.request.urlopen(args.url, timeout=20) as response:
    body = response.read()
    result = {'url': args.url, 'status': response.status,
              'milliseconds': (time.monotonic() - started) * 1000,
              'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest(),
              'expected': args.expected,
              'present': args.expected.encode() in body}
pathlib.Path(args.output).write_text(json.dumps(result, indent=2))
print(json.dumps(result))
if result['status'] != 200 or not result['present']:
    raise SystemExit('The adopted render was not observed')
