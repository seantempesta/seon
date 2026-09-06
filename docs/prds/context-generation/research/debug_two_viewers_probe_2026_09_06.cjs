// Read-only comparison. Arguments: seon.flow-viewer URL, my.plan-viewer URL.
// Verified 2026-09-06 at basis 536871430: both viewers selected the same
// subject with 2 reference edges; seon.flow chose schema renderers and
// my.plan chose namespace renderers for both AI and HTML.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

function candidate(observation, stageName, output, disposition) {
  return observation.stages
    .find(({ name }) => name === stageName)
    .columns.find(column => column.output === output)
    .candidates.find(candidate => candidate.disposition === disposition);
}

function topology(elements) {
  return {
    nodes: elements.nodes.map(({ data }) => ({
      id: data.id,
      label: data.label,
    })),
    edges: elements.edges,
  };
}

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
    timeout: 15000,
  });
  try {
    const observations = [];
    for (const url of process.argv.slice(2)) {
      const page = await browser.newPage();
      page.setDefaultTimeout(15000);
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(url, { waitUntil: 'domcontentloaded' });
      await page.locator('.seon-debug-experiment-stages').waitFor();
      await page.waitForFunction(() =>
        [...document.querySelectorAll('.seon-debug-candidate-preview')]
          .some(preview => preview.innerText.includes('After live update')));
      const observation = await page.evaluate(() => ({
        header: document.querySelector('#debug-inspection-header').innerText,
        stages: [...document.querySelectorAll('.seon-debug-experiment-stages > li')]
          .map(stage => ({
            name: stage.querySelector('h3').textContent,
            columns: [...stage.querySelectorAll('.seon-debug-projection-column')]
              .map(column => ({
                output: column.querySelector('h4').textContent,
                status: column.querySelector('.seon-debug-stage-status').textContent,
                candidates: [...column.querySelectorAll('.seon-debug-candidate')]
                  .map(candidate => ({
                    function: candidate.querySelector('.seon-debug-section-line a')?.textContent,
                    disposition: candidate.querySelector('.seon-debug-section-line span')?.textContent,
                    preview: candidate.querySelector('.seon-debug-candidate-preview')?.innerText,
                    href: candidate.querySelector('.seon-debug-section-line a')?.href,
                  })),
              })),
          })),
        model: JSON.parse(document.querySelector('[data-graph-model]').textContent),
      }));
      observation.errors = [...errors];
      observations.push(observation);
      await page.close();
    }

    assert.equal(observations.length, 2);
    assert.notEqual(observations[0].header, observations[1].header);
    assert.equal(
      observations[0].model['seon.graph/selected'],
      observations[1].model['seon.graph/selected']);
    assert.deepEqual(
      observations[0].model['seon.graph/snapshot'],
      observations[1].model['seon.graph/snapshot']);
    assert.deepEqual(
      topology(observations[0].model.elements),
      topology(observations[1].model.elements));
    assert.equal(observations[0].model.elements.edges.length, 2);

    for (const observation of observations) {
      assert(observation.header.includes('indexed source digest'));
      assert(observation.header.includes('schema projection fingerprint'));
      assert(!observation.header.includes('\nnil\n'));
      assert.deepEqual(observation.errors, []);
    }
    observations.forEach((observation, index) => {
      const viewer = index === 0 ? 'seon.flow' : 'my.plan';
      observation.model.elements.nodes.forEach(node => {
        assert.equal(new URL(node.data.href, 'http://seon.invalid')
          .searchParams.get('viewer'), viewer);
      });
    });

    for (const output of ['AI', 'HTML']) {
      const schemaChoice = candidate(observations[0], ':schema', output, 'chosen');
      const namespaceChoice = candidate(observations[1], ':namespace', output, 'chosen');
      assert(schemaChoice, `seon.flow must choose the ${output} schema renderer`);
      assert(namespaceChoice, `my.plan must choose the ${output} namespace renderer`);
      assert(schemaChoice.preview.includes('After live update'));
      assert.equal(schemaChoice.preview, namespaceChoice.preview);
      assert.equal(new URL(schemaChoice.href).searchParams.get('viewer'), 'seon.flow');
      assert.equal(new URL(namespaceChoice.href).searchParams.get('viewer'), 'my.plan');
    }

    const schemaHtml = observations[1].stages
      .find(({ name }) => name === ':schema')
      .columns.find(({ output }) => output === 'HTML')
      .candidates.find(({ function: fn }) => fn === 'my.plan/render-item-html');
    assert.equal(schemaHtml.disposition, 'shadowed · unconsulted');

    console.log(JSON.stringify(observations.map(observation => ({
      viewer: /viewer\s+(\S+)/.exec(observation.header)?.[1],
      selected: observation.model['seon.graph/selected'],
      references: observation.model.elements.edges.length,
      namespaceAI: candidate(observation, ':namespace', 'AI', 'chosen')?.function,
      schemaAI: candidate(observation, ':schema', 'AI', 'chosen')?.function,
      errors: observation.errors,
    })), null, 2));
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
