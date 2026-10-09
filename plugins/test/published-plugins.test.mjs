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

test('stable publication rejects prerelease packages before an automatic update can advertise them',()=>{
 const value=fixture();value.descriptor.plugins[0].version='1.0.1-preview.1';
 assert.throws(()=>verifyPublishedPlugins(value),/prerelease.*stable/i);
});

test('preview metadata uses only its pinned nightly catalog and retains the original signed archive',()=>{
 const descriptor=JSON.parse(readFileSync(new URL('../published-preview.json',import.meta.url)));
 const catalog=readCatalog(new URL('../../app/src/nightly/assets/plugin-preview-download-catalog.json',import.meta.url)).entries;
 const rows=verifyPublishedPlugins({descriptor,catalog,channel:'preview'});
 const preview=rows.find(row=>row.id==='nl.neerdael.youtube-music');
 assert.equal(preview.versionCode,14);
 assert.equal(preview.sha256,'7f7354fdebb8cce461edd84669bc553fd4bebbe2b5c243631ca46191fa8db346');
 assert.equal(catalog.find(entry=>entry.code===preview.code).url,'https://buzzheavier.com/2t67lln3fh00');
 const expectedIds=descriptor.plugins.map(row=>row.id);
 assert.throws(()=>verifyPublishedPlugins({descriptor,catalog,expectedIds}),/prerelease.*stable/i);
});

test('stable and preview both publish YouTube Video, and stable rejects a list without it',()=>{
 const preview=JSON.parse(readFileSync(new URL('../published-preview.json',import.meta.url)));
 const previewCatalog=readCatalog(new URL('../../app/src/nightly/assets/plugin-preview-download-catalog.json',import.meta.url)).entries;
 assert.ok(verifyPublishedPlugins({descriptor:preview,catalog:previewCatalog,channel:'preview'}).some(row=>row.id==='nl.neerdael.youtube-video'));
 const stable=JSON.parse(readFileSync(new URL('../published.json',import.meta.url)));
 const catalog=readCatalog(new URL('../../app/src/main/assets/plugin-download-catalog.json',import.meta.url)).entries;
 const video=verifyPublishedPlugins({descriptor:stable,catalog}).find(row=>row.id==='nl.neerdael.youtube-video');
 assert.equal(video.code,'932');
 const withoutVideo={...stable,plugins:stable.plugins.filter(row=>row.id!=='nl.neerdael.youtube-video')};
 assert.throws(()=>verifyPublishedPlugins({descriptor:withoutVideo,catalog}),/Invalid published plugin descriptor/);
});

test('a published row may state the API minimum and format Milkbeat needs, as positive integers',()=>{
 const stated=fixture();Object.assign(stated.descriptor.plugins[0],{apiMin:8,format:1});
 assert.equal(verifyPublishedPlugins(stated)[0].apiMin,8);
 for(const [field,value] of [['apiMin',0],['apiMin','8'],['format',1.5]]){
  const x=fixture();x.descriptor.plugins[0][field]=value;
  assert.throws(()=>verifyPublishedPlugins(x),new RegExp(field),`${field}=${value}`);
 }
});

test('every stable and preview row states its package compatibility, and preview offers the released YouTube Video',()=>{
 const read=path=>JSON.parse(readFileSync(new URL(path,import.meta.url)));
 const stable=read('../published.json'),preview=read('../published-preview.json');
 for(const row of [...stable.plugins,...preview.plugins]){
  assert.ok(Number.isInteger(row.apiMin),`${row.id} ${row.version} states apiMin`);
  assert.ok(Number.isInteger(row.format),`${row.id} ${row.version} states format`);
 }
 const video=descriptor=>descriptor.plugins.find(row=>row.id==='nl.neerdael.youtube-video');
 assert.deepEqual(video(preview),video(stable));
 const previewCatalog=readCatalog(new URL('../../app/src/nightly/assets/plugin-preview-download-catalog.json',import.meta.url)).entries;
 const catalog=readCatalog(new URL('../../app/src/main/assets/plugin-download-catalog.json',import.meta.url)).entries;
 assert.equal(previewCatalog.find(entry=>entry.code==='932').url,catalog.find(entry=>entry.code==='932').url);
});
