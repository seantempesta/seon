// watch ROOT...: every change FSEvents reports under the roots, as it happens, on stdout: one
// record per event, its kind letter then the absolute path, ended by NUL (a path may hold a
// newline; none holds NUL). Kinds: 'p' a path changed (created, written, renamed, removed);
// 's' the subtree at path must be read again (MustScanSubDirs: events under it were coalesced
// or dropped, by the kernel or by this process); 'r' a root itself moved or was removed. No
// clock: latency 0, NoDefer, FileEvents, on a dispatch queue (seon.watch reads it; its edge in
// docs/failure-points.md). It exits when its parent does (a GCD process source: kqueue
// EVFILT_PROC), never on a timer or on stdin.
#include <CoreServices/CoreServices.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static void put(char kind, const char *path) { putchar(kind); fputs(path, stdout); putchar('\0'); }

static void changed(ConstFSEventStreamRef s, void *info, size_t n, void *paths,
                    const FSEventStreamEventFlags flags[], const FSEventStreamEventId ids[]) {
  char **p = paths;
  for (size_t i = 0; i < n; i++) {
    FSEventStreamEventFlags f = flags[i];
    if (f & kFSEventStreamEventFlagHistoryDone) continue;
    if (f & kFSEventStreamEventFlagRootChanged) put('r', p[i]);
    else if (f & (kFSEventStreamEventFlagMustScanSubDirs | kFSEventStreamEventFlagUserDropped | kFSEventStreamEventFlagKernelDropped)) put('s', p[i]);
    else put('p', p[i]);
  }
  fflush(stdout);
}

int main(int argc, char **argv) {
  if (argc < 2) { fputs("usage: watch ROOT...\n", stderr); return 64; }
  pid_t parent = getppid();
  if (parent == 1) return 0;
  CFMutableArrayRef roots = CFArrayCreateMutable(NULL, 0, &kCFTypeArrayCallBacks);
  for (int i = 1; i < argc; i++) {
    CFStringRef r = CFStringCreateWithCString(NULL, argv[i], kCFStringEncodingUTF8);
    CFArrayAppendValue(roots, r);
    CFRelease(r);
  }
  FSEventStreamRef s = FSEventStreamCreate(NULL, changed, NULL, roots, kFSEventStreamEventIdSinceNow, 0.0,
                                           kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagNoDefer |
                                           kFSEventStreamCreateFlagWatchRoot);
  dispatch_queue_t q = dispatch_queue_create("seon.watch", NULL);
  FSEventStreamSetDispatchQueue(s, q);
  if (!FSEventStreamStart(s)) { fputs("watch: FSEventStreamStart refused\n", stderr); return 69; }
  dispatch_source_t gone = dispatch_source_create(DISPATCH_SOURCE_TYPE_PROC, parent, DISPATCH_PROC_EXIT, q);
  dispatch_source_set_event_handler(gone, ^{ exit(0); });
  dispatch_resume(gone);
  // ready: the stream is started, so a change from here on is reported
  put('w', argv[1]);
  fflush(stdout);
  dispatch_main();
}
