"""Read Juniper's debug page ten times; preserve the final HTTP bytes."""
from pathlib import Path
from urllib.request import urlopen

url = "http://127.0.0.1:7994/ns/my.agents.juniper/debug"
statuses = []
for _ in range(10):
    with urlopen(url, timeout=30) as response:
        statuses.append(response.status)
        body = response.read()
Path("tmp/record-render-ten-loads.html").write_bytes(body)
print({"statuses": statuses, "last_response_bytes": len(body)})
if statuses != [200] * 10:
    raise SystemExit(1)
