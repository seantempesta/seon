// NODE_PATH must point to the installed Playwright package. No database writes.
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const [block, iteration, selector] = process.argv.slice(2);
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    await fs.mkdir('tmp/html-views', { recursive: true });
    const page = await browser.newPage();
    page.setDefaultTimeout(30000);
    for (const width of [1440, 700]) {
      await page.setViewportSize({ width, height: 900 });
      const response = await page.goto('http://127.0.0.1:7994/ns/my.agents.juniper');
      if (response.status() !== 200) throw Error(`HTTP ${response.status()}`);
      const subject = page.locator(selector).first();
      await subject.waitFor();
      await subject.scrollIntoViewIfNeeded();
      await page.screenshot({ path: `tmp/html-views/${block}-${iteration}-${width}.png` });
      console.log(JSON.stringify({ block, width, text: await subject.innerText(),
        measure: await subject.evaluate(el => ({ width: el.clientWidth, scroll: el.scrollWidth,
          font: getComputedStyle(el).fontFamily })) }));
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
