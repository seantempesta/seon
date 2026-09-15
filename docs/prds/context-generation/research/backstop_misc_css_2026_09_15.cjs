// Compare the same canonical plan HTML under the old and consolidated stylesheets.
// node this-file.cjs FIXTURE_DIR BEFORE_CSS AFTER_CSS OUTPUT_DIR
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const [directory, before, after, output] = process.argv.slice(2);
(async () => {
  await fs.mkdir(output, { recursive: true });
  const files = (await fs.readdir(directory)).filter(f => f.endsWith('.html')).sort();
  const html = (await Promise.all(files.map(f => fs.readFile(path.join(directory, f), 'utf8'))))
    .filter(html => html.includes('my-plan')).join('<hr>');
  if (!html) throw Error('No canonical plan HTML was produced');
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const results = [];
  try {
    const page = await browser.newPage();
    for (const width of [1440, 700]) {
      await page.setViewportSize({ width, height: 1000 });
      let baseline;
      for (const [phase, stylesheet] of [['before', before], ['after', after]]) {
        const css = await fs.readFile(stylesheet, 'utf8');
        await page.setContent(`<html><head><style>${css}</style></head><body class="seon-body"><main style="max-width:760px;margin:24px auto;padding:16px">${html}</main></body></html>`);
        await page.screenshot({ path: path.join(output, `${phase}-${width}.png`), fullPage: true });
        const observed = await page.evaluate(() => ({
          overflow: document.documentElement.scrollWidth > innerWidth,
          elements: [...document.querySelectorAll('main *')].map(element => {
            const style = getComputedStyle(element);
            return Object.fromEntries(['display', 'gap', 'padding', 'margin', 'border', 'borderRadius',
              'color', 'backgroundColor', 'fontSize', 'fontWeight', 'fontFamily', 'lineHeight',
              'opacity', 'letterSpacing', 'textTransform'].map(key => [key, style[key]]));
          })
        }));
        if (observed.overflow) throw Error(`Horizontal overflow at ${width}/${phase}`);
        if (phase === 'before') baseline = observed;
        else {
          const differences = observed.elements.flatMap((styles, index) => Object.entries(styles)
            .filter(([property, value]) => value !== baseline.elements[index][property])
            .map(([property, value]) => ({ index, property, before: baseline.elements[index][property], after: value })));
          results.push({ width, elements: observed.elements.length, overflow: observed.overflow, differences });
        }
      }
    }
  } finally { await browser.close(); }
  await fs.writeFile(path.join(output, 'comparison.json'), JSON.stringify(results, null, 2));
  console.log(JSON.stringify(results));
})().catch(error => { console.error(error); process.exitCode = 1; });
