import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash,generateKeyPairSync,sign} from 'node:crypto';
import {zipSync,strToU8} from 'fflate';
import {verifyPackage} from '../scripts/package-verification.mjs';

const {privateKey,publicKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
function pack(apiMin){
 const files={'manifest.json':strToU8(JSON.stringify({format:1,api:{min:apiMin,target:apiMin},id:'dev.milkbeat.fixture',name:'Fixture',version:'1.0.0',versionCode:1})),'plugin.js':strToU8('definePlugin({})')};
 const listing=strToU8(Object.keys(files).sort().map(path=>`${sha(files[path])}  ${path}\n`).join(''));
 const envelope={algorithm:'ECDSA_P256_SHA256',publicKey:publicKey.export({type:'spki',format:'der'}).toString('base64'),signature:sign('sha256',listing,privateKey).toString('base64')};
 return zipSync({...files,'META-INF/CONTENTS':listing,'META-INF/SIGNATURE':strToU8(JSON.stringify(envelope))});
}

test('a package reports the API minimum and format its published row must state',()=>{
 const result=verifyPackage(pack(8),{apiMin:8,format:1});
 assert.equal(result.apiMin,8);
 assert.equal(result.format,1);
});

test('a published row that understates the package API minimum is rejected',()=>{
 assert.throws(()=>verifyPackage(pack(8),{apiMin:7}),/API minimum does not match/);
 assert.throws(()=>verifyPackage(pack(8),{format:2}),/format does not match/);
});
