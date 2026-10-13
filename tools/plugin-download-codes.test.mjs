import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';

const tool = resolve('tools/plugin-download-codes.mjs');
const aad = Buffer.from('milkbeat/plugin-download-catalog/1');

function decoded(file) {
  const e = JSON.parse(readFileSync(file));
  const body = Buffer.from(e.payload, 'base64');
  const cipher = createDecipheriv('aes-256-gcm', Buffer.from(e.key, 'base64'), Buffer.from(e.nonce, 'base64'));
  cipher.setAAD(aad);
  cipher.setAuthTag(body.subarray(-16));
  return JSON.parse(Buffer.concat([cipher.update(body.subarray(0, -16)), cipher.final()]));
}

function fixture(entries, file) {
  const key = randomBytes(32);
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(aad);
  const payload = Buffer.concat([cipher.update(JSON.stringify(entries)), cipher.final(), cipher.getAuthTag()]);
  writeFileSync(file, JSON.stringify({ format: 1, key: key.toString('base64'), nonce: nonce.toString('base64'), payload: payload.toString('base64') }));
}

function invoke(file, id, url) {
  return spawnSync(process.execPath, [tool, 'add', id, id, url, '--catalog', file], { encoding: 'utf8' });
}

test('a URL receives a stable three-digit code and the saved file hides its URL text', () => {
  const dir = mkdtempSync(join(tmpdir(), 'milkbeat-codes-'));
  const file = join(dir, 'catalog.json');
  try {
    assert.equal(invoke(file, 'dev.example.one', 'https://example.com/one.mbplugin').status, 0);
    const first = decoded(file);
    assert.match(first[0].code, /^[0-9]{3}$/);
    assert.equal(first[0].url, 'https://example.com/one.mbplugin');
    assert.ok(!readFileSync(file, 'utf8').includes('https://example.com/one.mbplugin'));
    assert.equal(invoke(file, 'dev.example.one', 'https://example.com/one.mbplugin').status, 0);
    assert.deepEqual(decoded(file), first);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a colliding URL gets a different code without changing the existing assignment', () => {
  const dir = mkdtempSync(join(tmpdir(), 'milkbeat-codes-'));
  const file = join(dir, 'catalog.json');
  const url = 'https://example.com/new.mbplugin';
  const occupied = String(createHash('sha256').update(url).digest().readUInt32BE(0) % 1000).padStart(3, '0');
  const old = { code: occupied, id: 'dev.example.old', name: 'Old', url: 'https://example.com/old.mbplugin' };
  try {
    fixture([old], file);
    assert.equal(invoke(file, 'dev.example.new', url).status, 0);
    const entries = decoded(file);
    assert.deepEqual(entries.find(x => x.id === old.id), old);
    assert.notEqual(entries.find(x => x.id === 'dev.example.new').code, occupied);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a full namespace fails without overwriting the catalog', () => {
  const dir = mkdtempSync(join(tmpdir(), 'milkbeat-codes-'));
  const file = join(dir, 'catalog.json');
  try {
    fixture(Array.from({ length: 1000 }, (_, n) => ({ code: String(n).padStart(3, '0'), id: `dev.example.p${n}`, name: 'Fixture', url: `https://example.com/${n}.mbplugin` })), file);
    const before = readFileSync(file);
    assert.notEqual(invoke(file, 'dev.example.extra', 'https://example.com/extra.mbplugin').status, 0);
    assert.deepEqual(readFileSync(file), before);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('invalid URLs and assigning one URL to another plugin leave the catalog untouched', () => {
  const dir = mkdtempSync(join(tmpdir(), 'milkbeat-codes-'));
  const file = join(dir, 'catalog.json');
  try {
    assert.equal(invoke(file, 'dev.example.one', 'https://example.com/one.mbplugin').status, 0);
    const before = readFileSync(file);
    for (const url of ['javascript:alert(1)', 'https://user:password@example.com/a', 'not a url', 'https:/example.com/a', 'http:/example.com/a']) {
      assert.notEqual(invoke(file, 'dev.example.bad', url).status, 0);
      assert.deepEqual(readFileSync(file), before);
    }
    assert.notEqual(invoke(file, 'dev.example.other', 'https://example.com/one.mbplugin').status, 0);
    assert.deepEqual(readFileSync(file), before);
    assert.notEqual(invoke(file, 'dev-example.one', 'https://example.com/invalid-id.mbplugin').status, 0);
    assert.deepEqual(readFileSync(file), before);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('retiring a plugin removes all its codes and keeps every other assignment', () => {
  const dir = mkdtempSync(join(tmpdir(), 'milkbeat-codes-'));
  const file = join(dir, 'catalog.json');
  const kept = { code: '563', id: 'dev.example.kept', name: 'Kept', url: 'https://example.com/kept.mbplugin' };
  try {
    fixture([
      { code: '416', id: 'dev.example.old', name: 'Old', url: 'https://example.com/old-1.mbplugin' },
      kept,
      { code: '728', id: 'dev.example.old', name: 'Old', url: 'https://example.com/old-2.mbplugin' },
    ], file);
    const run = spawnSync(process.execPath, [tool, 'retire', 'dev.example.old', '--catalog', file], { encoding: 'utf8' });
    assert.equal(run.status, 0);
    assert.match(run.stdout, /retired 416/);
    assert.match(run.stdout, /retired 728/);
    assert.deepEqual(decoded(file), [kept]);
    const again = spawnSync(process.execPath, [tool, 'retire', 'dev.example.old', '--catalog', file], { encoding: 'utf8' });
    assert.notEqual(again.status, 0);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a retired code is never handed to a new plugin', () => {
  const dir = mkdtempSync(join(tmpdir(), 'milkbeat-codes-'));
  const file = join(dir, 'catalog.json');
  try {
    fixture([], file);
    for (let index = 0; index < 40; index++) assert.equal(invoke(file, `dev.example.p${index}`, `https://example.com/p${index}.mbplugin`).status, 0);
    const codes = decoded(file).map(x => x.code);
    for (const retired of ['304', '416', '494', '728', '744', '932']) assert.ok(!codes.includes(retired), retired);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
