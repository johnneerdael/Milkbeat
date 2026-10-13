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

test('Deezer is required in the registered provider set while unknown and duplicate IDs remain rejected',()=>{
 const descriptor=JSON.parse(readFileSync(new URL('../published.json',import.meta.url)));
 const catalog=readCatalog(new URL('../../app/src/main/assets/plugin-download-catalog.json',import.meta.url)).entries;
 const rows=verifyPublishedPlugins({descriptor,catalog});
 assert.equal(rows.find(row=>row.id==='nl.neerdael.deezer').apiMin,9);
 const withoutDeezer={...descriptor,plugins:descriptor.plugins.filter(row=>row.id!=='nl.neerdael.deezer')};
 assert.throws(()=>verifyPublishedPlugins({descriptor:withoutDeezer,catalog}),/Invalid published plugin descriptor/);
 for(const id of ['nl.neerdael.unregistered',descriptor.plugins[0].id]){
  const replaced={...descriptor,plugins:descriptor.plugins.map(row=>row.id==='nl.neerdael.deezer'?{...row,id}:row)};
  assert.throws(()=>verifyPublishedPlugins({descriptor:replaced,catalog}),/Invalid published plugin identity/);
 }
});

test('YouTube, signed in through MicroG-RE-TV, is required in both channels and needs plugin API 11',()=>{
 for(const [file,catalogPath,channel] of [['../published.json','../../app/src/main/assets/plugin-download-catalog.json','stable'],['../published-preview.json','../../app/src/nightly/assets/plugin-preview-download-catalog.json','preview']]){
  const descriptor=JSON.parse(readFileSync(new URL(file,import.meta.url)));
  const catalog=readCatalog(new URL(catalogPath,import.meta.url)).entries;
  const rows=verifyPublishedPlugins({descriptor,catalog,channel});
  assert.equal(rows.find(row=>row.id==='nl.neerdael.youtube').apiMin,11);
  const withoutYouTube={...descriptor,plugins:descriptor.plugins.filter(row=>row.id!=='nl.neerdael.youtube')};
  assert.throws(()=>verifyPublishedPlugins({descriptor:withoutYouTube,catalog,channel}),/Invalid published plugin descriptor/);
 }
});

test('stable publication rejects prerelease packages before an automatic update can advertise them',()=>{
 const value=fixture();value.descriptor.plugins[0].version='1.0.1-preview.1';
 assert.throws(()=>verifyPublishedPlugins(value),/prerelease.*stable/i);
});

test('preview metadata uses only its pinned nightly catalog',()=>{
 const descriptor=JSON.parse(readFileSync(new URL('../published-preview.json',import.meta.url)));
 const catalog=readCatalog(new URL('../../app/src/nightly/assets/plugin-preview-download-catalog.json',import.meta.url)).entries;
 assert.deepEqual(verifyPublishedPlugins({descriptor,catalog,channel:'preview'}).map(row=>row.id).sort(),[...descriptor.plugins.map(row=>row.id)].sort());
});

test('the retired YouTube Music and YouTube Video are gone from both channels and catalogs; YouTube replaces them',()=>{
 const retired=['nl.neerdael.youtube-music','nl.neerdael.youtube-video'];
 const stable=JSON.parse(readFileSync(new URL('../published.json',import.meta.url)));
 const preview=JSON.parse(readFileSync(new URL('../published-preview.json',import.meta.url)));
 const catalog=readCatalog(new URL('../../app/src/main/assets/plugin-download-catalog.json',import.meta.url)).entries;
 const previewCatalog=readCatalog(new URL('../../app/src/nightly/assets/plugin-preview-download-catalog.json',import.meta.url)).entries;
 for(const rows of [stable.plugins,preview.plugins,catalog,previewCatalog])assert.ok(rows.every(row=>!retired.includes(row.id)));
 assert.equal(verifyPublishedPlugins({descriptor:stable,catalog}).find(row=>row.id==='nl.neerdael.youtube').code,'563');
 const withRetired={...stable,plugins:[...stable.plugins,{...stable.plugins[0],id:'nl.neerdael.youtube-music'}]};
 assert.throws(()=>verifyPublishedPlugins({descriptor:withRetired,catalog}),/Invalid published plugin descriptor/);
});

test('a published row may state the API minimum and format Milkbeat needs, as positive integers',()=>{
 const stated=fixture();Object.assign(stated.descriptor.plugins[0],{apiMin:8,format:1});
 assert.equal(verifyPublishedPlugins(stated)[0].apiMin,8);
 for(const [field,value] of [['apiMin',0],['apiMin','8'],['format',1.5]]){
  const x=fixture();x.descriptor.plugins[0][field]=value;
  assert.throws(()=>verifyPublishedPlugins(x),new RegExp(field),`${field}=${value}`);
 }
});

test('every stable and preview row states its package compatibility, and preview offers the released YouTube',()=>{
 const read=path=>JSON.parse(readFileSync(new URL(path,import.meta.url)));
 const stable=read('../published.json'),preview=read('../published-preview.json');
 for(const row of [...stable.plugins,...preview.plugins]){
  assert.ok(Number.isInteger(row.apiMin),`${row.id} ${row.version} states apiMin`);
  assert.ok(Number.isInteger(row.format),`${row.id} ${row.version} states format`);
 }
 const youtube=descriptor=>descriptor.plugins.find(row=>row.id==='nl.neerdael.youtube');
 assert.deepEqual(youtube(preview),youtube(stable));
 const previewCatalog=readCatalog(new URL('../../app/src/nightly/assets/plugin-preview-download-catalog.json',import.meta.url)).entries;
 const catalog=readCatalog(new URL('../../app/src/main/assets/plugin-download-catalog.json',import.meta.url)).entries;
 assert.equal(previewCatalog.find(entry=>entry.code==='563').url,catalog.find(entry=>entry.code==='563').url);
});
