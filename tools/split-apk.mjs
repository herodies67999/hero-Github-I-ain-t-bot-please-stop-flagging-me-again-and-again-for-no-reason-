import {readFile,mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {build} from 'esbuild';
const [apk,out,partMB='8']=process.argv.slice(2);
if(!apk||!out)throw Error('Usage: node tools/split-apk.mjs input.apk output-directory [part-MB]');
const chunkSize=Number(partMB)*1048576;if(!Number.isSafeInteger(chunkSize)||chunkSize<1||chunkSize>24*1048576)throw Error('Part size must be 1 byte through 24 MB');
const bytes=await readFile(apk),filename='yoru-v0.3-arm64.apk',hash=b=>createHash('sha256').update(b).digest('hex');
const manifest={format:'yoru-apk-parts-v1',filename,size:bytes.length,sha256:hash(bytes),parts:[]};await mkdir(out,{recursive:true});
if(bytes.length<=25_000_000){await writeFile(path.join(out,filename),bytes);await writeFile(path.join(out,'SHA256SUMS.txt'),`${manifest.sha256}  ${filename}\n`);console.log(`Complete APK: ${filename} (${bytes.length} bytes). No splitting required.`);process.exit(0);}
for(let offset=0,index=1;offset<bytes.length;offset+=chunkSize,index++){const part=bytes.subarray(offset,offset+chunkSize),name=`${filename}.${String(index).padStart(3,'0')}.part`;await writeFile(path.join(out,name),part);manifest.parts.push({name,size:part.length,sha256:hash(part)});}
const bundle=await build({entryPoints:['delivery/assembler.js'],bundle:true,write:false,format:'iife',minify:true,target:'es2020'});
const template=await readFile('delivery/template.html','utf8');const html=template.replace('__MANIFEST__',JSON.stringify(manifest).replaceAll('<','\\u003c')).replace('__SCRIPT__',bundle.outputFiles[0].text.replaceAll('</script','<\\/script'));
await writeFile(path.join(out,'assemble-yoru.html'),html);await writeFile(path.join(out,'manifest.json'),JSON.stringify(manifest,null,2));console.log(JSON.stringify(manifest,null,2));
