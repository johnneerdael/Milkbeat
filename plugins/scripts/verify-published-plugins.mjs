import {readFileSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {readCatalog} from '../../tools/plugin-download-codes.mjs';
const AUTHOR='39dca3d132c56262c0873ec96faf22cc8ed9ca30c7ed1f135049f8570b943adc';
const PROVIDERS=['nl.neerdael.beatport','nl.neerdael.spotify','nl.neerdael.youtube-music','nl.neerdael.soundcloud'];
// The Preview app also offers providers not yet published to the stable channel.
const PREVIEW_PROVIDERS=[...PROVIDERS,'nl.neerdael.youtube-video'];
// Packages are not kept in git: the publisher verifies their bytes against the Buzzheavier account
// listing before it registers them. This checks that the list the app reads agrees with its catalog.
export function verifyPublishedPlugins({descriptor,catalog,expectedAuthor=AUTHOR,channel='stable',expectedIds=channel==='preview'?PREVIEW_PROVIDERS:PROVIDERS}){
 if(descriptor.format!==1 || !Array.isArray(descriptor.plugins) || descriptor.plugins.length!==expectedIds.length)throw new Error('Invalid published plugin descriptor');
 if(!['stable','preview'].includes(channel))throw new Error('Invalid publication channel');
 const ids=new Set();
 for(const row of descriptor.plugins){
  if(typeof row.version!=='string' || !row.version)throw new Error('Invalid published plugin version');
  if(channel==='stable' && row.version.includes('-'))throw new Error('Prerelease plugin cannot appear in stable publication');
  if(!expectedIds.includes(row.id) || ids.has(row.id) || row.fingerprint!==expectedAuthor || !/^[a-f0-9]{64}$/.test(row.sha256) || !Number.isInteger(row.size) || row.size<=0 || row.size>64*1024*1024 || !Number.isInteger(row.versionCode) || !/^[0-9]{3}$/.test(row.code))throw new Error('Invalid published plugin identity');
  ids.add(row.id);
  const entry=catalog.find(entry=>entry.code===row.code);
  if(!entry || entry.id!==row.id)throw new Error('Published code does not match the plugin catalog');
  const url=new URL(entry.url);
  if(url.origin!=='https://buzzheavier.com' || !/^\/[A-Za-z0-9]{8,16}$/.test(url.pathname))throw new Error('Published code does not point at a Buzzheavier file');
 }
 return descriptor.plugins;
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const root=fileURLToPath(new URL('../../',import.meta.url));
 try{
  const descriptor=JSON.parse(readFileSync(join(root,'plugins/published.json'),'utf8'));
  const catalog=readCatalog(join(root,'app/src/main/assets/plugin-download-catalog.json')).entries;
  verifyPublishedPlugins({descriptor,catalog});console.log('Verified the published plugin list against the download catalog');
 }catch(error){console.error(error.message);process.exitCode=1;}
}
