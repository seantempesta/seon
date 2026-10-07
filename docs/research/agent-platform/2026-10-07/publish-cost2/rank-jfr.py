# /// script
# dependencies = ["ijson==3.4.0.post0"]
# ///
"""Stream main-thread JFR samples: uv run rank-jfr.py <jfr-print-json>."""
import collections, ijson, json, pathlib, sys
ranks = {kind: collections.Counter() for kind in ('samples', 'weighted-bytes')}
totals = collections.Counter()
with (sys.stdin.buffer if sys.argv[1] == '-' else pathlib.Path(sys.argv[1]).open('rb')) as trace:
    events = ijson.items(trace, 'recording.events.item')
    for event in events:
        values = event['values']
        thread = values.get('eventThread') or values.get('sampledThread') or {}
        if thread.get('javaName') != 'main':
            continue
        if event['type'] not in ('jdk.ObjectAllocationSample', 'jdk.ExecutionSample'):
            continue
        kind = 'weighted-bytes' if event['type'] == 'jdk.ObjectAllocationSample' else 'samples'
        weight = values.get('weight', 1)
        totals[kind] += weight
        frames = (values.get('stackTrace') or {}).get('frames', [])
        methods = [f['method']['type']['name'] + '.' + f['method']['name'] for f in frames]
        skip = ('seon/instrument', 'seon/db$with_declarations', 'seon/schema$call')
        owner = next((m for m in methods if m.startswith('seon/') and not m.startswith(skip)), 'other')
        ranks[kind][owner] += weight
print(json.dumps({'totals': totals, 'ranks': {k: v.most_common(30) for k, v in ranks.items()}}, indent=2))
