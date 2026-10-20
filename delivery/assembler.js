import {sha256} from '@noble/hashes/sha256';
export const hex=bytes=>Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
export function validateSelection(manifest,files){
  if(files.length!==manifest.parts.length)throw Error(`Select all ${manifest.parts.length} parts. You selected ${files.length}.`);
  const byName=new Map();
  for(const file of files){if(byName.has(file.name))throw Error(`Duplicate part: ${file.name}`);byName.set(file.name,file);}
  return manifest.parts.map(part=>{const file=byName.get(part.name);if(!file)throw Error(`Missing part: ${part.name}`);if(file.size!==part.size)throw Error(`Incorrect size: ${part.name}. Download that part again.`);return {file,part};});
}
export async function reassemble(manifest,files,progress=()=>{}){
  const ordered=validateSelection(manifest,files),buffers=[],whole=sha256.create();let total=0;
  for(let i=0;i<ordered.length;i++){
    const {file,part}=ordered[i];const bytes=new Uint8Array(await file.arrayBuffer());
    if(hex(sha256(bytes))!==part.sha256)throw Error(`Damaged or incorrect part: ${part.name}. Download it again.`);
    whole.update(bytes);buffers.push(bytes);total+=bytes.byteLength;progress(i+1,ordered.length);
    await new Promise(resolve=>setTimeout(resolve,0));
  }
  if(total!==manifest.size||hex(whole.digest())!==manifest.sha256)throw Error('Combined APK verification failed. No APK was created.');
  return new Blob(buffers,{type:'application/vnd.android.package-archive'});
}
if(typeof document!=='undefined'){
 const manifest=JSON.parse(document.querySelector('#manifest').textContent);
 const form=document.querySelector('form'),input=document.querySelector('#parts'),button=document.querySelector('#assemble'),status=document.querySelector('#status'),download=document.querySelector('#download'),meter=document.querySelector('progress');
 document.querySelector('#part-count').textContent=manifest.parts.length;
 document.querySelector('#file-size').textContent=(manifest.size/1048576).toFixed(1)+' MB';
 document.querySelector('#checksum').textContent=manifest.sha256;
 document.querySelector('#part-list').textContent=manifest.parts.map(p=>p.name).join('\n');let currentUrl;
 input.onchange=()=>{status.textContent=`${input.files.length} parts selected`;download.hidden=true;};
 form.onsubmit=async event=>{event.preventDefault();button.disabled=true;download.hidden=true;meter.value=0;try{
   const blob=await reassemble(manifest,[...input.files],(done,count)=>{meter.value=done/count;status.textContent=`Verified ${done} of ${count} parts…`;});
   if(currentUrl)URL.revokeObjectURL(currentUrl);currentUrl=URL.createObjectURL(blob);download.href=currentUrl;download.download=manifest.filename;download.hidden=false;status.textContent='Verified. Your complete APK is ready.';download.focus();
 }catch(error){status.textContent=error.message;}finally{button.disabled=false;}};
}
