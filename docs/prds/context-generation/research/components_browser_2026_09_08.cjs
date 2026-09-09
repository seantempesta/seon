// Capture the actual debug page, including exact AI column text.
// Usage: NODE_PATH=<Playwright node_modules> node this-file URL OUTPUT_PREFIX
const {chromium} = require('playwright');
const fs = require('node:fs');
(async () => {
  const [url, prefix, mode] = process.argv.slice(2);
  if (!url || !prefix) throw new Error('URL and output prefix are required');
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1100}});
    const response = await page.goto(url, {waitUntil: 'domcontentloaded', timeout: 30000});
    if (!response?.ok()) throw new Error(`HTTP ${response?.status()}`);
    await page.locator(mode === '--rendered' ? 'h1' : '#debug-units').first().waitFor({timeout: 30000});
    const blocks = await page.locator('[data-seon-unit]').evaluateAll(nodes => nodes.map(node => ({
      attribute: node.getAttribute('data-seon-unit'),
      ai: node.querySelector('.seon-debug-projection-column')?.innerText,
      html: node.querySelectorAll('.seon-debug-projection-column')[1]?.innerText
    })));
    fs.writeFileSync(`${prefix}.json`, JSON.stringify({url, blocks}, null, 2) + '\n');
    fs.writeFileSync(`${prefix}.txt`, await page.locator('body').innerText());
    await page.screenshot({path: `${prefix}.png`, fullPage: true});
    for (const [name, selector] of [['identity', '.seon-agent-identity-entry'], ['plan', '.my-plan'], ['settings', '.seon-agent-settings']]) {
      const component = page.locator(selector).first();
      if (await component.count() && await component.isVisible()) {
        const bounds = await component.boundingBox();
        await page.setViewportSize({width: 1440, height: Math.max(1100, Math.ceil(bounds.height) + 240)});
        await component.scrollIntoViewIfNeeded();
        await page.evaluate(() => window.scrollBy(0, -180));
        await component.screenshot({path: `${prefix}-${name}.png`});
      }
    }
    console.log(JSON.stringify({url, blocks: blocks.map(x => x.attribute), screenshot: `${prefix}.png`}));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
