// native/browser/seon_browser.m
// Lean native macOS WKWebView host for Seon:
// - `--headless`: silent off-screen GPU-backed WKWebView (0 Dock icon, 0 focus steal, 0 Keychain popups)
//                 capturing real WebKit JPEG screenshots + DOM layout telemetry in ~40-80 ms.
// - `--window`:   standalone native macOS desktop window with persistent WKWebsiteDataStore cookies/logins
//                 that survives Seon `--take-over` binary swaps cleanly.
// - Control protocol: newline-delimited JSON over stdin/stdout (OS-agnostic; Windows WebView2 & Linux
//   WebKitGTK plug into the exact same `:browser` Flow proc protocol).

#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>

static NSString *kDomProbeJS =
    @"(function(){"
     "try{"
     "  var de=document.documentElement, b=document.body;"
     "  var hdr=document.getElementById('seon-header');"
     "  var main=document.getElementById('seon-main');"
     "  var view=document.getElementById('seon-view-unit');"
     "  var svgs=Array.from(document.querySelectorAll('.canvas-surface svg, #seon-view-unit svg')).slice(0,6).map(function(s){"
     "    var r=s.getBoundingClientRect();"
     "    return {w:Math.round(r.width),h:Math.round(r.height),top:Math.round(r.top),bottom:Math.round(r.bottom)};"
     "  });"
     "  var activeMode=document.querySelector('.mode-group .nav-pill.active');"
     "  var crumb=document.querySelector('.crumb');"
     "  var txt=(view?view.innerText:(b?b.innerText:''))||'';"
     "  txt=txt.replace(/\\s+/g,' ').trim().slice(0,400);"
     "  return JSON.stringify({"
     "    url:location.href,"
     "    title:document.title||'',"
     "    theme:(hdr&&hdr.dataset&&hdr.dataset.theme)||'auto',"
     "    viewing:crumb?crumb.textContent.trim():'',"
     "    mode:activeMode?activeMode.textContent.trim():'',"
     "    viewportW:window.innerWidth,"
     "    viewportH:window.innerHeight,"
     "    scrollW:de?de.scrollWidth:0,"
     "    clientW:de?de.clientWidth:0,"
     "    overflowX:de?(de.scrollWidth>de.clientWidth+2):false,"
     "    mainScrollH:main?main.scrollHeight:0,"
     "    mainClientH:main?main.clientHeight:0,"
     "    svgCount:svgs.length,"
     "    svgs:svgs,"
     "    visibleText:txt"
     "  });"
     "}catch(e){return JSON.stringify({error:String(e)});}"
     "})();";

static void writeJSONLine(NSDictionary *dict) {
    @synchronized([NSFileHandle fileHandleWithStandardOutput]) {
        NSError *err = nil;
        NSData *data = [NSJSONSerialization dataWithJSONObject:dict options:0 error:&err];
        if (!data) return;
        NSMutableData *line = [data mutableCopy];
        [line appendBytes:"\n" length:1];
        [[NSFileHandle fileHandleWithStandardOutput] writeData:line];
    }
}

@interface SeonBrowserApp : NSObject <NSApplicationDelegate, WKNavigationDelegate, NSWindowDelegate>
@property (nonatomic, assign) BOOL windowed;
@property (nonatomic, assign) CGFloat width;
@property (nonatomic, assign) CGFloat height;
@property (nonatomic, copy) NSString *initialURL;
@property (nonatomic, strong) NSWindow *window;
@property (nonatomic, strong) WKWebView *webView;
@property (nonatomic, copy) void (^pendingNavCallback)(BOOL ok, NSString *errMsg);
@property (nonatomic, assign) BOOL loadedOnce;
@end

@implementation SeonBrowserApp

- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    NSRect frame = self.windowed
        ? NSMakeRect(120, 120, self.width, self.height)
        : NSMakeRect(-20000, -20000, self.width, self.height);

    NSWindowStyleMask style = self.windowed
        ? (NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
           NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable)
        : NSWindowStyleMaskBorderless;

    self.window = [[NSWindow alloc] initWithContentRect:frame
                                              styleMask:style
                                                backing:NSBackingStoreBuffered
                                                  defer:NO];
    self.window.delegate = self;
    self.window.title = @"seon";
    self.window.backgroundColor = [NSColor colorWithRed:0.051 green:0.051 blue:0.047 alpha:1.0];

    WKWebViewConfiguration *cfg = [[WKWebViewConfiguration alloc] init];
    cfg.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
    // Keep WebView rendering active even when off-screen or occluded
    if ([cfg.preferences respondsToSelector:@selector(setValue:forKey:)]) {
        @try {
            [cfg.preferences setValue:@YES forKey:@"developerExtrasEnabled"];
        } @catch (NSException *ex) {}
    }

    self.webView = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, self.width, self.height)
                                      configuration:cfg];
    self.webView.navigationDelegate = self;
    self.webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    if ([self.webView respondsToSelector:@selector(setValue:forKey:)]) {
        @try {
            [self.webView setValue:@NO forKey:@"drawsBackground"];
        } @catch (NSException *ex) {}
    }

    [self.window setContentView:self.webView];

    if (self.windowed) {
        self.window.appearance = [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua];
        [self.window center];
        [self.window makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
    } else {
        // Off-screen non-interactive backing window so WebKit GPU compositor paints real frames
        // with zero Dock icon, zero focus steal, and zero visible clutter.
        [self.window setIgnoresMouseEvents:YES];
        [self.window setAlphaValue:0.0];
        [self.window orderBack:nil];
    }

    if (self.initialURL.length > 0) {
        NSURL *u = [NSURL URLWithString:self.initialURL];
        if (u) {
            [self.webView loadRequest:[NSURLRequest requestWithURL:u]];
        }
    }

    writeJSONLine(@{
        @"event": @"ready",
        @"mode": self.windowed ? @"window" : @"headless",
        @"width": @((NSInteger)self.width),
        @"height": @((NSInteger)self.height),
        @"url": self.initialURL ?: @""
    });

    [self startStdinLoop];
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)sender {
    return self.windowed;
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    self.loadedOnce = YES;
    if (self.pendingNavCallback) {
        void (^cb)(BOOL, NSString *) = self.pendingNavCallback;
        self.pendingNavCallback = nil;
        cb(YES, nil);
    }
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    if (self.pendingNavCallback) {
        void (^cb)(BOOL, NSString *) = self.pendingNavCallback;
        self.pendingNavCallback = nil;
        cb(NO, error.localizedDescription ?: @"navigation failed");
    }
}

- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    if (self.pendingNavCallback) {
        void (^cb)(BOOL, NSString *) = self.pendingNavCallback;
        self.pendingNavCallback = nil;
        cb(NO, error.localizedDescription ?: @"provisional navigation failed");
    }
}

- (void)startStdinLoop {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        char *line = NULL;
        size_t cap = 0;
        ssize_t len = 0;
        while ((len = getline(&line, &cap, stdin)) != -1) {
            if (len <= 0) continue;
            NSData *data = [NSData dataWithBytes:line length:(NSUInteger)len];
            NSError *err = nil;
            id obj = [NSJSONSerialization JSONObjectWithData:data options:0 error:&err];
            if (![obj isKindOfClass:[NSDictionary class]]) {
                writeJSONLine(@{@"ok": @NO, @"error": @"invalid json command"});
                continue;
            }
            NSDictionary *cmd = (NSDictionary *)obj;
            dispatch_async(dispatch_get_main_queue(), ^{
                [self handleCommand:cmd];
            });
        }
        if (line) free(line);
        // If stdin closes (parent exited) in headless mode, terminate cleanly (0 orphans!)
        if (!self.windowed) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [NSApp terminate:nil];
            });
        }
    });
}

- (void)handleCommand:(NSDictionary *)cmd {
    id reqId = cmd[@"id"] ?: [NSNull null];
    NSString *op = [cmd[@"op"] isKindOfClass:[NSString class]] ? cmd[@"op"] : @"";

    if ([op isEqualToString:@"quit"]) {
        writeJSONLine(@{@"id": reqId, @"ok": @YES});
        [NSApp terminate:nil];
        return;
    }

    if ([op isEqualToString:@"resize"]) {
        CGFloat w = [cmd[@"width"] doubleValue];
        CGFloat h = [cmd[@"height"] doubleValue];
        if (w < 200) w = self.width;
        if (h < 200) h = self.height;
        self.width = w;
        self.height = h;
        [self.window setContentSize:NSMakeSize(w, h)];
        [self.webView setFrame:NSMakeRect(0, 0, w, h)];
        writeJSONLine(@{@"id": reqId, @"ok": @YES, @"width": @((NSInteger)w), @"height": @((NSInteger)h)});
        return;
    }

    if ([op isEqualToString:@"navigate"]) {
        NSString *urlStr = [cmd[@"url"] isKindOfClass:[NSString class]] ? cmd[@"url"] : @"";
        NSURL *u = [NSURL URLWithString:urlStr];
        if (!u) {
            writeJSONLine(@{@"id": reqId, @"ok": @NO, @"error": @"invalid url"});
            return;
        }
        __block BOOL replied = NO;
        self.pendingNavCallback = ^(BOOL ok, NSString *errMsg) {
            if (replied) return;
            replied = YES;
            if (ok) {
                writeJSONLine(@{@"id": reqId, @"ok": @YES, @"url": urlStr});
            } else {
                writeJSONLine(@{@"id": reqId, @"ok": @NO, @"error": errMsg ?: @"navigation failed"});
            }
        };
        [self.webView loadRequest:[NSURLRequest requestWithURL:u]];
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(8.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            if (!replied) {
                replied = YES;
                self.pendingNavCallback = nil;
                writeJSONLine(@{@"id": reqId, @"ok": @NO, @"error": @"navigation timeout"});
            }
        });
        return;
    }

    if ([op isEqualToString:@"eval_js"]) {
        NSString *code = [cmd[@"code"] isKindOfClass:[NSString class]] ? cmd[@"code"] : @"null";
        [self.webView evaluateJavaScript:code completionHandler:^(id res, NSError *err) {
            if (err) {
                writeJSONLine(@{@"id": reqId, @"ok": @NO, @"error": err.localizedDescription ?: @"js error"});
            } else {
                id val = res ?: [NSNull null];
                if (![NSJSONSerialization isValidJSONObject:@{@"v": val}]) {
                    val = [val description];
                }
                writeJSONLine(@{@"id": reqId, @"ok": @YES, @"value": val});
            }
        }];
        return;
    }

    if ([op isEqualToString:@"snapshot"]) {
        CFAbsoluteTime t0 = CFAbsoluteTimeGetCurrent();
        CGFloat w = [cmd[@"width"] doubleValue];
        CGFloat h = [cmd[@"height"] doubleValue];
        double quality = cmd[@"quality"] ? [cmd[@"quality"] doubleValue] : 0.72;
        if (quality < 0.1 || quality > 1.0) quality = 0.72;
        if (w >= 200 && h >= 200 && (fabs(w - self.width) > 1.0 || fabs(h - self.height) > 1.0)) {
            self.width = w;
            self.height = h;
            [self.window setContentSize:NSMakeSize(w, h)];
            [self.webView setFrame:NSMakeRect(0, 0, w, h)];
        }
        NSString *savePath = [cmd[@"save_path"] isKindOfClass:[NSString class]] ? cmd[@"save_path"] : nil;

        [self.webView evaluateJavaScript:kDomProbeJS completionHandler:^(id jsRes, NSError *jsErr) {
            NSDictionary *dom = @{};
            if ([jsRes isKindOfClass:[NSString class]]) {
                NSData *jd = [(NSString *)jsRes dataUsingEncoding:NSUTF8StringEncoding];
                id parsed = [NSJSONSerialization JSONObjectWithData:jd options:0 error:nil];
                if ([parsed isKindOfClass:[NSDictionary class]]) dom = parsed;
            }

            WKSnapshotConfiguration *scfg = [[WKSnapshotConfiguration alloc] init];
            scfg.rect = self.webView.bounds;
            CGFloat maxSnapW = cmd[@"max_width"] ? [cmd[@"max_width"] doubleValue] : 1024.0;
            if (maxSnapW < 320.0) maxSnapW = 1024.0;
            CGFloat scale = (self.window.backingScaleFactor > 0.0) ? self.window.backingScaleFactor : 1.0;
            // WKSnapshotConfiguration.snapshotWidth is in points (multiplied by backingScaleFactor on Retina).
            // Divide by scale so the encoded JPEG stays crisp at ~1024 physical px (~40-70 KB) instead of 2x Retina bloat!
            scfg.snapshotWidth = @(MAX(320.0, MIN(self.width, maxSnapW) / scale));
            if (@available(macOS 10.15, *)) {
                scfg.afterScreenUpdates = YES;
            }

            [self.webView takeSnapshotWithConfiguration:scfg completionHandler:^(NSImage *image, NSError *snapErr) {
                if (snapErr || !image) {
                    writeJSONLine(@{
                        @"id": reqId,
                        @"ok": @NO,
                        @"error": snapErr.localizedDescription ?: @"snapshot returned nil",
                        @"dom": dom
                    });
                    return;
                }
                CGImageRef cgRef = [image CGImageForProposedRect:NULL context:nil hints:nil];
                if (!cgRef) {
                    writeJSONLine(@{@"id": reqId, @"ok": @NO, @"error": @"failed to obtain CGImage", @"dom": dom});
                    return;
                }
                NSBitmapImageRep *rep = [[NSBitmapImageRep alloc] initWithCGImage:cgRef];
                NSData *jpg = [rep representationUsingType:NSBitmapImageFileTypeJPEG
                                                properties:@{NSImageCompressionFactor: @(quality)}];
                if (!jpg || jpg.length == 0) {
                    writeJSONLine(@{@"id": reqId, @"ok": @NO, @"error": @"jpeg encode failed", @"dom": dom});
                    return;
                }
                if (savePath.length > 0) {
                    [jpg writeToFile:savePath atomically:YES];
                }
                NSString *b64 = [jpg base64EncodedStringWithOptions:0];
                double ms = (CFAbsoluteTimeGetCurrent() - t0) * 1000.0;
                writeJSONLine(@{
                    @"id": reqId,
                    @"ok": @YES,
                    @"width": @((NSInteger)self.width),
                    @"height": @((NSInteger)self.height),
                    @"mime": @"image/jpeg",
                    @"bytes": @(jpg.length),
                    @"ms": @(round(ms * 10.0) / 10.0),
                    @"dom": dom,
                    @"base64": b64
                });
            }];
        }];
        return;
    }

    writeJSONLine(@{@"id": reqId, @"ok": @NO, @"error": [NSString stringWithFormat:@"unknown op: %@", op]});
}

@end

int main(int argc, const char * argv[]) {
    @autoreleasepool {
        BOOL windowed = NO;
        CGFloat width = 1280.0;
        CGFloat height = 820.0;
        NSString *url = @"http://127.0.0.1:9998/";

        for (int i = 1; i < argc; i++) {
            NSString *arg = [NSString stringWithUTF8String:argv[i]];
            if ([arg isEqualToString:@"--window"]) {
                windowed = YES;
            } else if ([arg isEqualToString:@"--headless"]) {
                windowed = NO;
            } else if ([arg isEqualToString:@"--url"] && i + 1 < argc) {
                url = [NSString stringWithUTF8String:argv[++i]];
            } else if ([arg isEqualToString:@"--width"] && i + 1 < argc) {
                width = atof(argv[++i]);
            } else if ([arg isEqualToString:@"--height"] && i + 1 < argc) {
                height = atof(argv[++i]);
            }
        }

        NSApplication *app = [NSApplication sharedApplication];
        [app setActivationPolicy:windowed
            ? NSApplicationActivationPolicyRegular
            : NSApplicationActivationPolicyProhibited];

        SeonBrowserApp *delegate = [[SeonBrowserApp alloc] init];
        delegate.windowed = windowed;
        delegate.width = width;
        delegate.height = height;
        delegate.initialURL = url;
        app.delegate = delegate;
        [app run];
    }
    return 0;
}
