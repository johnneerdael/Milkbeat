// Turns the JSON Schema that plugin-api's test writes into the SDK's TypeScript types.
// `--check` fails instead of writing when the checked-in types are stale.
import { readFile, writeFile } from 'node:fs/promises';
import { compile } from 'json-schema-to-typescript';

const schemaPath = new URL('../generated/plugin-api.schema.json', import.meta.url);
const typesPath = new URL('../generated/plugin-api.ts', import.meta.url);
const schema = JSON.parse(await readFile(schemaPath, 'utf8'));

const compiled = await compile(schema, 'MilkbeatPluginApi', {
  bannerComment: '/* Generated from plugin-api by scripts/generate-types.mjs. Do not edit. */',
  additionalProperties: false,
  unreachableDefinitions: false,
  style: { singleQuote: true, printWidth: 120 },
});

// The host functions as data too, so the SDK builds `mb` from exactly the functions the host has.
const hostOperations = Object.keys(schema.properties.host.properties);
const types = `${compiled}
export const HOST_OPERATIONS = ${JSON.stringify(hostOperations, null, 2).replaceAll('"', "'")} as const;
`;

if (process.argv.includes('--check')) {
  const current = await readFile(typesPath, 'utf8').catch(() => '');
  if (current !== types) {
    console.error('generated/plugin-api.ts is stale: run npm run generate in plugins/sdk');
    process.exit(1);
  }
} else {
  await writeFile(typesPath, types);
}
