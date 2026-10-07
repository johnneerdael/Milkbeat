import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {verifyPublishedPlugins} from '../scripts/verify-published-plugins.mjs';
import {readCatalog} from '../../tools/plugin-download-codes.mjs';
const author='a'.repeat(64),id='dev.milkbeat.fixture';
function fixture(){
 const row={id,name:'Fixture',code:'007',sha256:'b'.repeat(64),size:10,fingerprint:author,version:'1',versionCode:1};
 return {descriptor:{format:1,plugins:[row]},catalog:[{id,code:'007',url:'https://buzzheavier.com/abcdefgh1234'}],expectedAuthor:author,expectedIds:[id]};
}
test('a published list that agrees with its catalog verifies',()=>{
 assert.equal(verifyPublishedPlugins(fixture()).length,1);
});
test('wrong hash, author, plugin ID, code or download host is rejected',()=>{
 for(const kind of ['hash','author','id','code','host']){
  const x=fixture(),row=x.descriptor.plugins[0];
  if(kind==='hash')row.sha256='not-a-hash';
  if(kind==='author')row.fingerprint='0'.repeat(64);
  if(kind==='id')x.catalog[0].id='dev.wrong.plugin';
  if(kind==='code')row.code='999';
  if(kind==='host')x.catalog[0].url='https://raw.githubusercontent.com/x/y';
  assert.throws(()=>verifyPublishedPlugins(x),undefined,kind);
 }
});
test('native promotion fixture tracks the exact published descriptor',()=>{
 const descriptor=JSON.parse(readFileSync(new URL('../published.json',import.meta.url)));
 const native=JSON.parse(readFileSync(new URL('../../app/src/androidTest/assets/published-plugins.json',import.meta.url)));
 assert.deepEqual(native,descriptor);
});

test('the default publication validator accepts all registered providers including SoundCloud',()=>{
 const descriptor=JSON.parse(readFileSync(new URL('../published.json',import.meta.url)));
 const catalog=readCatalog(new URL('../../app/src/main/assets/plugin-download-catalog.json',import.meta.url)).entries;
 const rows=verifyPublishedPlugins({descriptor,catalog});
 assert.ok(rows.some(row=>row.id==='nl.neerdael.soundcloud'));
});
