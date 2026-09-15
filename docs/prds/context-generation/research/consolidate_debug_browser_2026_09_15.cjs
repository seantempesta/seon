// Read-only screenshot and layout verification; NODE_PATH supplies Playwright.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
(async () => {
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  const label = process.argv[2] || 'review';
  try {
    await fs.mkdir('tmp/consolidate-debug', {recursive: true});
    for (const width of [1440, 700]) {
      for (const [name, suffix] of [['debug', '/debug'], ['agent', ''],
        ['inspection', '/debug?subject=' + encodeURIComponent('[:seon.agent/id "juniper"]')]]) {
        const page = await browser.newPage({viewport: {width, height: 900}});
        const response = await page.goto('http://127.0.0.1:7994/ns/my.agents.juniper' + suffix);
        assert.equal(response.status(), 200);
        await page.locator('.seon-session-header').waitFor();
        if (name === 'inspection') {
          await page.locator('#debug-units').waitFor();
          await page.locator('[data-session-loaded]').waitFor();
          assert.equal(await page.locator('[id^="debug-ai-"]').count(), 0);
        }
        await page.evaluate(() => scrollTo(0, 0));
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
        assert.equal(overflow, 0);
        await page.screenshot({path: `tmp/consolidate-debug/${label}-${name}-${width}.png`});
        console.log(JSON.stringify({label, name, width, status: response.status(), overflow}));
        if (name === 'agent') {
          await page.locator('.seon-runtime').scrollIntoViewIfNeeded();
          await page.screenshot({path: `tmp/consolidate-debug/${label}-runtime-${width}.png`});
          console.log(JSON.stringify({label, name: 'runtime', width}));
        }
        await page.close();
      }
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
