// node this-file PLAYWRIGHT_MODULE BASE_URL OUTPUT_PREFIX
const {chromium} = require(process.argv[2]);
const fs = require('node:fs');
(async () => {
  const browser = await chromium.launch({channel: 'chrome', headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 1600, height: 1000}});
    const url = process.argv[3] + '/ns/my.agents.juniper/debug';
    const gets = [];
    for (let n = 0; n < 2; n++) {
      const started = performance.now();
      const response = await page.request.get(url, {timeout: 20000});
      gets.push({status: response.status(), milliseconds: performance.now() - started,
                 bytes: (await response.body()).length});
    }
    await page.goto(url, {waitUntil: 'domcontentloaded', timeout: 20000});
    const units = await page.locator('[data-seon-unit]').evaluateAll(nodes => nodes.map(node => {
      const columns = [...node.querySelectorAll(':scope > .seon-debug-projection-grid > section')]
        .map(column => {const r = column.getBoundingClientRect();
          return {label: column.querySelector('h4')?.textContent, x: r.x, y: r.y, width: r.width};});
      return {attribute: node.getAttribute('data-seon-unit'),
              title: node.querySelector('h2')?.textContent,
              width: node.getBoundingClientRect().width, columns};
    }));
    await page.screenshot({path: process.argv[4] + '.png'});
    for (const [name, attribute] of [['plan', ':seon.agent/plan'], ['faults', ':seon.error/_agent']]) {
      const selected = page.locator(`[data-seon-unit="${attribute}"]`);
      if (await selected.count()) {
        await selected.evaluate(node => window.scrollTo(0, window.scrollY + node.getBoundingClientRect().top - 150));
        await page.screenshot({path: process.argv[4] + '-' + name + '.png'});
      }
    }
    const result = {gets, units, scrollWidth: await page.evaluate(() => document.documentElement.scrollWidth)};
    fs.writeFileSync(process.argv[4] + '.json', JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  } finally {await browser.close();}
})().catch(error => {console.error(error); process.exitCode = 1;});
