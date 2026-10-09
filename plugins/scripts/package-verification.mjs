import {unzipSync} from 'fflate';
import {createHash,createPublicKey,verify} from 'node:crypto';

const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
const decoder=new TextDecoder('utf-8',{fatal:true});
const validPath=path=>path && !path.startsWith('/') && !path.includes('\\') && !path.split('/').some(part=>part==='..' || part==='.');
// apiMin and format are what a published.json row states for this package; Milkbeat skips updates it would refuse.
export function verifyPackage(bytes,{id,fingerprint,apiMin,format}={}) {
  if(!bytes?.length || bytes.length>64*1024*1024)throw new Error('Package size is invalid');
  let total=0,count=0;const names=new Set();
  const files=unzipSync(bytes,{filter:entry=>{
    if(names.has(entry.name))throw new Error('Duplicate package entry');
    names.add(entry.name);count++;total+=entry.originalSize;
    if(count>512 || entry.originalSize>24*1024*1024 || total>64*1024*1024)throw new Error('Package contents are too large');
    if(entry.name.endsWith('/'))return false;
    if(!validPath(entry.name))throw new Error('Invalid package path');
    return true;
  }});
  const listing=files['META-INF/CONTENTS'],signature=files['META-INF/SIGNATURE'];
  if(!listing || !signature)throw new Error('Package is not signed');
  const envelope=JSON.parse(decoder.decode(signature));
  if(envelope.algorithm!=='ECDSA_P256_SHA256')throw new Error('Unsupported package signature');
  const publicBytes=Buffer.from(envelope.publicKey,'base64');
  const key=createPublicKey({key:publicBytes,type:'spki',format:'der'});
  if(key.asymmetricKeyType!=='ec' || key.asymmetricKeyDetails?.namedCurve!=='prime256v1')throw new Error('Expected the P-256 author key');
  const actual=digest(publicBytes);
  if(fingerprint && actual!==fingerprint)throw new Error('Package author does not match');
  if(!verify('sha256',listing,key,Buffer.from(envelope.signature,'base64')))throw new Error('Package signature is invalid');
  const declared=new Map();
  for(const line of decoder.decode(listing).split(String.fromCharCode(10)).filter(Boolean)) {
    const match=/^([a-f0-9]{64})  (.+)$/.exec(line);
    if(!match || !validPath(match[2]) || declared.has(match[2]))throw new Error('Invalid content listing');
    declared.set(match[2],match[1]);
  }
  const content=Object.keys(files).filter(path=>!['META-INF/CONTENTS','META-INF/SIGNATURE'].includes(path));
  if(content.length!==declared.size || content.some(path=>!declared.has(path)))throw new Error('Unlisted or missing package files');
  for(const [path,hash] of declared)if(!files[path] || digest(files[path])!==hash)throw new Error('Package content hash is invalid');
  if(!files['manifest.json'])throw new Error('Package manifest is missing');
  const manifest=JSON.parse(decoder.decode(files['manifest.json']));
  if(manifest.format!==1 || !/^[a-z][a-z0-9_]*(\.[a-z0-9_-]+)+$/.test(manifest.id) || !Number.isInteger(manifest.versionCode))throw new Error('Package manifest is invalid');
  if(id && manifest.id!==id)throw new Error('Package ID does not match');
  if(!files[manifest.entry??'plugin.js'])throw new Error('Package entry point is missing');
  const requirements={apiMin:manifest.api?.min,format:manifest.format};
  if(!Number.isInteger(requirements.apiMin) || requirements.apiMin<1)throw new Error('Package API minimum is invalid');
  if(apiMin!==undefined && apiMin!==requirements.apiMin)throw new Error('Published API minimum does not match the package');
  if(format!==undefined && format!==requirements.format)throw new Error('Published format does not match the package');
  return {manifest,fingerprint:actual,sha256:digest(bytes),contentDigest:digest(listing),...requirements};
}
