## Rosetta-active proof (host: 26.4, arm64), 2026-07-06T23:29:23Z

### Docker Desktop settings-store.json
  "UseVirtualizationFrameworkRosetta": true,

### Docker Desktop version
Client:
 Version:           29.6.1
 API version:       1.55
Server: Docker Desktop 4.80.0 (232116)
 Engine:
  Version:          29.6.1

### amd64 container smoke (fix-git image, --platform linux/amd64)
uname -m => x86_64 ; python3 -c 'print(1+1)' => 2 (ran, no crash)

### Definitive: pytest verifier ran to completion under amd64 emulation, NO SIGSEGV
  platform linux -- Python 3.13.7, pytest-8.4.1 ; 2 passed in 0.06s
