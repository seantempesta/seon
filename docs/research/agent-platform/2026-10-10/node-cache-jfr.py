"""Summarize `jfr print --json --stack-depth 256 --events jdk.ObjectAllocationSample` output."""
import collections
import json
import sys

owners = collections.Counter()
paths = collections.Counter()
threads = collections.Counter()
total = 0
for event in json.load(open(sys.argv[1]))['recording']['events']:
    values = event['values']
    weight = values['weight']
    frames = (values.get('stackTrace') or {}).get('frames', [])
    names = [f['method']['type']['name'] + '.' + f['method']['name'] for f in frames]
    total += weight
    if any('CachedStorage.restore' in name for name in names):
        seon = [name for name in names if name.startswith('seon/')]
        owners[seon[0] if seon else 'NO SEON FRAME'] += weight
        paths[tuple(dict.fromkeys(seon))[:12]] += weight
        threads[values['eventThread']['javaName']] += weight
print('Sampled MB:', total / 1e6)
for label, counts in [('Owners', owners), ('Paths', paths), ('Threads', threads)]:
    print(label)
    for name, weight in counts.most_common(20):
        print(round(weight / 1e6, 1), name)
