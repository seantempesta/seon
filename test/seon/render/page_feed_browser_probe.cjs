// Run with the installed Playwright module directory and an output JSON path.
const {chromium} = require(process.argv[2]);
const fs = require('node:fs');
const http = require('node:http');
const base = 'http://127.0.0.1:7994';

function firstEvent() {
  return new Promise((resolve, reject) => {
    const started = performance.now();
    const request = http.get(base + '/feed/juniper?debug=true', response => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        body += chunk;
        if (body.includes('\n\n')) {
          resolve({status: response.statusCode, milliseconds: performance.now() - started,
                   event: body.split('\n')[0], bytes: Buffer.byteLength(body)});
          response.destroy();
        }
      });
      response.on('error', reject);
    });
    request.setTimeout(10000, () => request.destroy(new Error('First event exceeded 10 s')));
    request.on('error', reject);
  });
}

(async () => {
  const browser = await chromium.launch({channel: 'chrome', headless: true});
  try {
    const context = await browser.newContext({viewport: {width: 1600, height: 1000}});
    const pages = await Promise.all([0, 1, 2].map(() => context.newPage()));
    const gets = await Promise.all(pages.map(async page => {
      const started = performance.now();
      const response = await page.goto(base + '/ns/my.agents.juniper/debug',
                                       {waitUntil: 'domcontentloaded', timeout: 20000});
      return {status: response.status(), milliseconds: performance.now() - started};
    }));
    console.log('Three debug tabs open');
    const events = [];
    for (let n = 0; n < 6; n++) events.push(await firstEvent());
    const result = {gets, events};
    fs.writeFileSync(process.argv[3], JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  } finally {
    await browser.close();
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
