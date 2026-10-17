// Optional full validation. Dependency installation is currently deferred.
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import SwaggerParser from '@apidevtools/swagger-parser';
import Ajv from 'ajv';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const contracts = new URL('../../contracts/', import.meta.url);
const load = async (relative) => JSON.parse(await readFile(new URL(relative, contracts), 'utf8'));
const publicApi = await SwaggerParser.validate(fileURLToPath(new URL('http/public.openapi.json', contracts)));
for (const name of ['game-internal', 'auth-internal']) {
  await SwaggerParser.validate(fileURLToPath(new URL(`http/${name}.openapi.json`, contracts)));
}
const ajv = new Ajv({ strict: false, allErrors: true });
addFormats(ajv);
for (const fixture of await load('http/examples/fixtures.json')) {
  const validate = ajv.compile(publicApi.components.schemas[fixture.schema]);
  if (validate(fixture.value) !== fixture.valid) {
    throw new Error(`HTTP fixture ${fixture.schema}: ${JSON.stringify(validate.errors)}`);
  }
}
const events = new Ajv2020({ strict: false, allErrors: true });
addFormats(events);
const filenames = ['common.schema.json', 'domain-event.schema.json', 'ws-client.schema.json', 'ws-server.schema.json'];
const ids = new Map();
for (const name of filenames) {
  const schema = await load(`events/${name}`);
  events.addSchema(schema);
  ids.set(name, schema.$id);
}
for (const fixture of await load('events/fixtures.json')) {
  const validate = events.getSchema(ids.get(fixture.schema));
  if (validate(fixture.value) !== fixture.valid) {
    throw new Error(`Event fixture ${fixture.schema}: ${JSON.stringify(validate.errors)}`);
  }
}
console.log('Full schema and fixture validation passed. Compatibility requires a separate baseline comparison.');
