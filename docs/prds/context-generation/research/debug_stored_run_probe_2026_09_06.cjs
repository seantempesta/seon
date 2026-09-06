// Read-only stored-run inspection. Arguments: debug URL, optional "bootstrap" fixture.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true, timeout: 15000,
  });
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(process.argv[2], {waitUntil: 'domcontentloaded'});
    await page.locator('.seon-debug-experiment-stages').waitFor();
    const columns = await page.locator('.seon-debug-projection-column').evaluateAll(nodes =>
      nodes.map(node => ({
        output: node.querySelector('h4').textContent,
        candidates: [...node.querySelectorAll('.seon-debug-candidate')].map(candidate => ({
          producer: candidate.querySelector('.seon-debug-section-line a')?.textContent,
          disposition: candidate.querySelector('.seon-debug-section-line span')?.textContent,
          text: candidate.querySelector('.seon-debug-candidate-preview')?.innerText,
        })),
      })));
    for (const output of ['AI', 'HTML']) {
      const selected = columns.filter(column => column.output === output)
        .flatMap(column => column.candidates)
        .find(candidate => candidate.disposition === 'chosen');
      assert(selected, `a ${output} renderer must be selected`);
      assert.equal(selected.producer, `seon.render.transcript/render-run-${output.toLowerCase()}`);
      const expectedContent = process.argv[3] === 'bootstrap'
        ? ['(help)', '(dir my.message)', '(dir seon.db)',
           ':seon.cluster.agent/unread-message-count 0', 'my.agents.root']
        : [
        '; Read the current bounded plan facts.',
        '(my.plan/plan (seon.db/db) "root")',
        '; Render the actual preceding result.',
        '(my.plan/render-plan-html result/e0)',
        'After live update',
      ];
      for (const expected of expectedContent) assert(selected.text.includes(expected), `${output} must display stored content: ${expected}`);
      assert(!selected.text.includes('(largest [])'), 'another run must not leak into this run');
      assert(!selected.text.includes(':seon.render/missing-projection'));
    }
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({
      header: await page.locator('#debug-inspection-header').innerText(),
      storedFormsAndResults: true, isolatedRun: true, errors,
    }));
  } finally {
    await browser.close();
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
