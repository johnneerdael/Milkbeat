#!/usr/bin/env node
// Keeps the plugin downloader-code tables in the README and docs in step with plugins/published.json.
// The plugin publisher runs `sync` in the same commit that updates published.json; CI runs `check`.
//
// A table is marked in Markdown as
//   <!-- plugin-codes:<layout> -->
//   ...generated table...
//   <!-- /plugin-codes -->
// where <layout> is one of the keys of LAYOUTS.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Codes from before the private publisher. They stay registered in the app's catalog.
const ORIGINAL_CODES = {
  'nl.neerdael.beatport': '102',
  'nl.neerdael.spotify': '772',
  'nl.neerdael.youtube-music': '416',
};

const LAYOUTS = {
  readme: (plugins) => [
    '| Third-party plugin | Downloader code | Original code, still supported |',
    '| --- | --- | --- |',
    ...plugins.map((plugin) => `| ${plugin.name} | **${plugin.code}** | ${ORIGINAL_CODES[plugin.id] ?? '—'} |`),
  ],
  'user-guide': (plugins) => [
    '| Code | Plugin |',
    '| --- | --- |',
    ...plugins.map((plugin) => `| **${plugin.code}** | ${plugin.name} |`),
  ],
  maintainer: (plugins) => [
    '| Code | Package |',
    '| --- | --- |',
    ...plugins.filter((plugin) => ORIGINAL_CODES[plugin.id]).map((plugin) => `| ${ORIGINAL_CODES[plugin.id]} | ${plugin.name} |`),
    ...plugins.map((plugin) => `| ${plugin.code} | ${plugin.name}, private publisher |`),
  ],
};

const BLOCK = /(<!-- plugin-codes:([a-z-]+) -->)\n(?:[\s\S]*?\n)?(<!-- \/plugin-codes -->)/g;

export function publishedPlugins(root) {
  const descriptor = JSON.parse(readFileSync(join(root, 'plugins/published.json'), 'utf8'));
  if (descriptor.format !== 1 || !Array.isArray(descriptor.plugins) || !descriptor.plugins.length) {
    throw new Error('plugins/published.json must list the published plugins');
  }
  return descriptor.plugins.map((row) => {
    if (typeof row.id !== 'string' || typeof row.name !== 'string' || !row.name || !/^[0-9]{3}$/.test(String(row.code))) {
      throw new Error(`plugins/published.json has an incomplete entry: ${row.id ?? '?'}`);
    }
    if (/[|\n]/.test(row.name)) throw new Error(`Plugin name cannot be placed in a table: ${row.name}`);
    return { id: row.id, name: row.name, code: String(row.code) };
  });
}

export function renderTables(text, plugins) {
  return text.replace(BLOCK, (_, open, layout, close) => {
    if (!LAYOUTS[layout]) throw new Error(`Unknown plugin-codes layout: ${layout}`);
    return [open, ...LAYOUTS[layout](plugins), close].join('\n');
  });
}

function markdownFiles(root) {
  const files = [join(root, 'README.md')];
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.name.endsWith('.md')) files.push(path);
    }
  };
  walk(join(root, 'docs'));
  return files;
}

// Returns the repository-relative paths whose tables differ from published.json; writes them when asked.
export function syncDocs(root, { write = false } = {}) {
  const plugins = publishedPlugins(root);
  const stale = [];
  for (const file of markdownFiles(root)) {
    const text = readFileSync(file, 'utf8');
    const rendered = renderTables(text, plugins);
    if (rendered === text) continue;
    stale.push(relative(root, file));
    if (write) writeFileSync(file, rendered);
  }
  return stale;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const command = process.argv[2];
  const root = fileURLToPath(new URL('../../', import.meta.url));
  try {
    if (command !== 'sync' && command !== 'check') throw new Error('Expected sync or check');
    const stale = syncDocs(root, { write: command === 'sync' });
    if (command === 'sync') {
      console.log(stale.length ? `Updated plugin codes in ${stale.join(', ')}` : 'Plugin codes are up to date');
    } else if (stale.length) {
      console.error(`Plugin codes differ from plugins/published.json in ${stale.join(', ')}. Run: node .github/scripts/plugin-codes.mjs sync`);
      process.exitCode = 1;
    } else {
      console.log('Plugin codes match plugins/published.json');
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
