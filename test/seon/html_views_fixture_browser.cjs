// Capture HTML emitted by the canonical fixture via SEON_HTML_OUTPUT.
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const [directory, phase, stylesheet = 'resources/public/css/output.css'] = process.argv.slice(2);
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const css = await fs.readFile(stylesheet, 'utf8');
    const files = (await fs.readdir(directory)).filter(f => f.endsWith('.html')).sort();
    const groups = new Map();
    for (const file of files) {
      const html = await fs.readFile(`${directory}/${file}`, 'utf8');
      const name = html.match(/class="([^"]+)"/)[1].split(' ').filter(c => c !== 'seon-family-entry')[0];
      groups.set(name, [...(groups.get(name) || []), html]);
    }
    const page = await browser.newPage();
    for (const width of [1440, 700]) {
      await page.setViewportSize({ width, height: 900 });
      for (const [name, entries] of groups) {
        await page.setContent(`<html><head><style>${css}</style></head><body class="seon-body"><main style="max-width:760px;margin:24px auto;padding:16px"><p style="margin-bottom:16px;color:var(--color-text-400)">Canonical fixture · ${name}</p>${entries.join('<hr style="margin:20px 0;border-color:var(--color-base-800)">')}</main></body></html>`);
        await page.screenshot({ path: `tmp/html-views/fixture-${name}-${phase}-${width}.png` });
        console.log(JSON.stringify({ name, width, phase, overflow: await page.evaluate(() => document.documentElement.scrollWidth > innerWidth) }));
      }
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
