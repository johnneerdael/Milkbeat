#!/usr/bin/env node
import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const aad = Buffer.from('milkbeat/plugin-download-catalog/1');
const defaultCatalog = 'app/src/main/assets/plugin-download-catalog.json';
// Codes of retired plugins (YouTube Music, YouTube Video). Released apps still resolve them from their bundled
// catalogs, so they are never handed to another plugin.
export const RETIRED_CODES = new Set(['304', '416', '494', '728', '744', '932']);

function normalize(value) {
  if (!value || /\s/.test(value)) throw new Error('Invalid plugin URL');
  if (/^https?:/i.test(value) && !value.includes('://')) throw new Error('Invalid plugin URL');
  const url = new URL(value.includes('://') ? value : `https://${value}`);
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) throw new Error('Invalid plugin URL');
  return url.href;
}

export function readCatalog(file) {
  if (!existsSync(file)) return { key: randomBytes(32), entries: [] };
  const envelope = JSON.parse(readFileSync(file, 'utf8'));
  if (envelope.format !== 1) throw new Error('Unsupported catalog format');
  const key = Buffer.from(envelope.key, 'base64');
  const nonce = Buffer.from(envelope.nonce, 'base64');
  const payload = Buffer.from(envelope.payload, 'base64');
  if (key.length !== 32 || nonce.length !== 12 || payload.length < 16) throw new Error('Invalid catalog encryption');
  const cipher = createDecipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(aad);
  cipher.setAuthTag(payload.subarray(-16));
  const entries = JSON.parse(Buffer.concat([cipher.update(payload.subarray(0, -16)), cipher.final()]));
  if (!Array.isArray(entries) || entries.length > 1000) throw new Error('Invalid catalog entries');
  const codes = new Set();
  const urls = new Set();
  for (const entry of entries) {
    if (!/^[0-9]{3}$/.test(entry.code) || codes.has(entry.code) || urls.has(entry.url) || !entry.id || !entry.name || normalize(entry.url) !== entry.url) {
      throw new Error('Invalid or duplicate catalog entry');
    }
    codes.add(entry.code);
    urls.add(entry.url);
  }
  return { key, entries };
}

function write(file, key, entries) {
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(aad);
  const payload = Buffer.concat([cipher.update(JSON.stringify(entries)), cipher.final(), cipher.getAuthTag()]);
  const envelope = { format: 1, key: key.toString('base64'), nonce: nonce.toString('base64'), payload: payload.toString('base64') };
  const temporary = `${file}.${process.pid}.tmp`;
  try {
    writeFileSync(temporary, `${JSON.stringify(envelope, null, 2)}\n`, { flag: 'wx' });
    renameSync(temporary, file);
  } finally { rmSync(temporary, { force: true }); }
}

function main() {
  const args = process.argv.slice(2);
  const option = args.indexOf('--catalog');
  let file = defaultCatalog;
  if (option !== -1) {
    if (!args[option + 1]) throw new Error('--catalog requires a path');
    file = args[option + 1];
    args.splice(option, 2);
  }
  file = resolve(file);
  const [command, id, name, input] = args;
  if (command === 'list' && args.length === 1) {
    for (const entry of readCatalog(file).entries) console.log(`${entry.code}  ${entry.name} (${entry.id})`);
    return;
  }
  // A plugin retired from publication loses every code; add them to RETIRED_CODES so they are never reused.
  if (command === 'retire' && args.length === 2) {
    const lock = `${file}.lock`;
    const handle = openSync(lock, 'wx');
    try {
      const { key, entries } = readCatalog(file);
      const retired = entries.filter(x => x.id === id);
      if (!retired.length) throw new Error(`No codes are assigned to ${id}`);
      write(file, key, entries.filter(x => x.id !== id));
      for (const entry of retired) console.log(`retired ${entry.code}  ${entry.name} (${entry.id})`);
    } finally {
      closeSync(handle);
      rmSync(lock);
    }
    return;
  }
  if (command !== 'add' || args.length !== 4) {
    throw new Error('Usage: plugin-download-codes.mjs add <plugin-id> <name> <url> | retire <plugin-id> | list [--catalog <file>]');
  }
  if (!/^[a-z][a-z0-9_]*(\.[a-z0-9_-]+)+$/.test(id) || !name.trim()) throw new Error('Invalid plugin ID or name');
  const url = normalize(input);
  mkdirSync(dirname(file), { recursive: true });
  const lock = `${file}.lock`;
  const handle = openSync(lock, 'wx');
  try {
    const { key, entries } = readCatalog(file);
    const existing = entries.find(x => x.url === url);
    if (existing) {
      if (existing.id !== id) throw new Error('This URL is already assigned to another plugin');
      console.log(`${existing.code}  ${existing.name} (${existing.id})`);
      return;
    }
    if (entries.length + RETIRED_CODES.size >= 1000) throw new Error('All 1000 three-digit codes are assigned');
    const occupied = new Set([...entries.map(x => x.code), ...RETIRED_CODES]);
    const start = createHash('sha256').update(url).digest().readUInt32BE(0) % 1000;
    let code;
    for (let offset = 0; offset < 1000; offset++) {
      const candidate = String((start + offset) % 1000).padStart(3, '0');
      if (!occupied.has(candidate)) { code = candidate; break; }
    }
    entries.push({ code, id, name: name.trim(), url });
    entries.sort((a, b) => a.code.localeCompare(b.code));
    write(file, key, entries);
    console.log(`${code}  ${name.trim()} (${id})`);
  } finally {
    closeSync(handle);
    rmSync(lock);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
