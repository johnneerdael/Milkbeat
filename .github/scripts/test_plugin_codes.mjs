import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { renderTables, syncDocs } from './plugin-codes.mjs';

const published = {
  format: 1,
  plugins: [
    { id: 'nl.neerdael.beatport', name: 'Beatport', version: '0.1.3', code: '393' },
    { id: 'nl.neerdael.spotify', name: 'Spotify', version: '0.2.2', code: '981' },
    { id: 'nl.neerdael.youtube-music', name: 'YouTube Music', version: '0.2.2', code: '494' },
  ],
};

function repository(descriptor = published) {
  const root = mkdtempSync(join(tmpdir(), 'plugin-codes-'));
  mkdirSync(join(root, 'plugins'));
  mkdirSync(join(root, 'docs/user-guide'), { recursive: true });
  writeFileSync(join(root, 'plugins/published.json'), JSON.stringify(descriptor));
  writeFileSync(join(root, 'README.md'), 'Intro\n\n<!-- plugin-codes:readme -->\n| stale |\n<!-- /plugin-codes -->\n\nOutro\n');
  writeFileSync(join(root, 'docs/user-guide/providers.md'), '<!-- plugin-codes:user-guide -->\n<!-- /plugin-codes -->\n');
  writeFileSync(join(root, 'docs/unmarked.md'), '| **393** | Beatport |\n');
  return root;
}

test('renders every layout from the published plugins', () => {
  const plugins = published.plugins.map(({ id, name, code }) => ({ id, name, code }));
  const marked = (layout) => `<!-- plugin-codes:${layout} -->\nold\n<!-- /plugin-codes -->`;
  assert.equal(renderTables(marked('readme'), plugins), [
    '<!-- plugin-codes:readme -->',
    '| Third-party plugin | Downloader code | Original code, still supported |',
    '| --- | --- | --- |',
    '| Beatport | **393** | 102 |',
    '| Spotify | **981** | 772 |',
    '| YouTube Music | **494** | 416 |',
    '<!-- /plugin-codes -->',
  ].join('\n'));
  assert.match(renderTables(marked('user-guide'), plugins), /\| Code \| Plugin \|\n\| --- \| --- \|\n\| \*\*393\*\* \| Beatport \|/);
  assert.match(renderTables(marked('maintainer'), plugins), /\| 416 \| YouTube Music \|\n\| 393 \| Beatport, private publisher \|/);
  assert.throws(() => renderTables(marked('unknown'), plugins), /Unknown plugin-codes layout/);
});

test('sync rewrites only marked tables and check reports them until synced', () => {
  const root = repository();
  try {
    assert.deepEqual(syncDocs(root), ['README.md', 'docs/user-guide/providers.md']);
    assert.match(readFileSync(join(root, 'README.md'), 'utf8'), /\| stale \|/);
    assert.deepEqual(syncDocs(root, { write: true }), ['README.md', 'docs/user-guide/providers.md']);
    const readme = readFileSync(join(root, 'README.md'), 'utf8');
    assert.match(readme, /^Intro\n\n<!-- plugin-codes:readme -->\n\| Third-party plugin/);
    assert.match(readme, /\| YouTube Music \| \*\*494\*\* \| 416 \|\n<!-- \/plugin-codes -->\n\nOutro\n$/);
    assert.equal(readFileSync(join(root, 'docs/unmarked.md'), 'utf8'), '| **393** | Beatport |\n');
    assert.deepEqual(syncDocs(root), []);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a new plugin and a changed code reach the tables', () => {
  const descriptor = structuredClone(published);
  descriptor.plugins[1].code = '512';
  descriptor.plugins.push({ id: 'nl.neerdael.tidal', name: 'Tidal', version: '0.1.0', code: '777' });
  const root = repository(descriptor);
  try {
    syncDocs(root, { write: true });
    const readme = readFileSync(join(root, 'README.md'), 'utf8');
    assert.match(readme, /\| Spotify \| \*\*512\*\* \| 772 \|/);
    assert.match(readme, /\| Tidal \| \*\*777\*\* \| — \|/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('an incomplete descriptor fails instead of writing tables', () => {
  for (const descriptor of [
    { format: 1, plugins: [] },
    { format: 2, plugins: published.plugins },
    { format: 1, plugins: [{ id: 'nl.neerdael.spotify', name: 'Spotify' }] },
    { format: 1, plugins: [{ id: 'nl.neerdael.spotify', name: 'Spot|ify', code: '981' }] },
  ]) {
    const root = repository(descriptor);
    try {
      assert.throws(() => syncDocs(root, { write: true }));
      assert.match(readFileSync(join(root, 'README.md'), 'utf8'), /\| stale \|/);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  }
});
