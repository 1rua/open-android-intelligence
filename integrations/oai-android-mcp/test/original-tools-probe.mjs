// 固定上游原工具互通验证：仅系统采集器为替身，真实门面、Session 工具和文件解析不改写。
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import { stripTypeScriptTypes, createRequire } from 'node:module';
import { SourceTextModule, SyntheticModule } from 'node:vm';
import { readdirSync, readFileSync } from 'node:fs';
import { rename, mkdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
const root = resolve(process.argv[2]);
const fixture = new URL('./fixtures/dsh-remote/', import.meta.url);
const index=JSON.parse(readFileSync(new URL('source.json',fixture),'utf8'));
for(const entry of index.files)assert.equal(createHash('sha256').update(readFileSync(new URL(entry.fixture,fixture))).digest('hex'),entry.sha256);
const require = createRequire(join(root, 'integrations/oai-android-mcp/package.json'));
const { z } = require('zod');
const src = name => readFileSync(new URL(`${name}.ts`,fixture),'utf8');
const names = ['remote-session-tools','remote-file-download','remote-typert-gateway','session-history-cache','session-format-compat'];
const modules = new Map(names.map(name => [`./${name}.js`, new SourceTextModule(stripTypeScriptTypes(src(name), {mode:'transform'}), {identifier:name})]));
class RemoteClientError extends Error { constructor(code,message){super(message);this.code=code;} }
const realFs=await import('node:fs/promises');
const double = { 'node:fs/promises':{...realFs,rm:async path=>{await mkdir(join(root,'.worktrees/trash'),{recursive:true});await rename(path,join(root,'.worktrees/trash',`probe-${Date.now()}`));}}, 'node:os':{homedir:()=>'/tmp/phone-tools-probe-home'}, 'zod':{z}, '@dsh-remote/client-core':{RemoteClientError}, '@dsh-remote/protocol':{HARNESS_API_TRANSFER_CHUNK_BYTES:262144, MAX_HARNESS_API_TRANSFER_BYTES:50*1024*1024}, './ids.js':{uuidV7:()=>crypto.randomUUID()}, './remote-file-upload.js':{uploadRemoteFile:()=>{throw Error('UPLOAD_NOT_REQUESTED')}} };
const synth = new Map();
await modules.get('./remote-session-tools.js').link(async spec => {
 if(modules.has(spec)) return modules.get(spec);
 if(!synth.has(spec)) { const values = double[spec] ?? await import(spec); synth.set(spec,new SyntheticModule(Object.keys(values),function(){for(const [k,v] of Object.entries(values))this.setExport(k,v)})); }
 return synth.get(spec);
});
await modules.get('./remote-session-tools.js').evaluate();
const cacheRoot=join(process.env.GRADLE_USER_HOME,'caches/modules-2/files-2.1');
const jar=(pkg,ver)=>{const base=join(cacheRoot,pkg,ver);for(const h of readdirSync(base)){for(const f of readdirSync(join(base,h))){if(f.endsWith('.jar'))return join(base,h,f)}}};
const cp=[join(root,'apps/android/gateway-client/build/tmp/kotlin-classes/debugUnitTest'),join(root,'apps/android/gateway-client/build/tmp/kotlin-classes/debug'),join(root,'apps/android/control-ports/build/tmp/kotlin-classes/debug'),jar('org.jetbrains.kotlin/kotlin-stdlib','2.1.20'),jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm','1.9.0')].join(':');
const child=spawn(join(process.env.JAVA_HOME,'bin/java'),['-cp',cp,'com.openandroidintelligence.gateway.remote.facade.PhoneVirtualHostProbe'],{stdio:['pipe','pipe','inherit']});
const lines=createInterface({input:child.stdout});const pending=[];lines.on('line',line=>{const value=JSON.parse(line);if(value.event){for(const fn of listeners)fn(value);}else pending.shift()?.(value.response)});
const route=(method,params)=>new Promise(resolve=>{pending.push(resolve);child.stdin.write(JSON.stringify({method,params})+'\n')});
const listeners=new Set();
const client={onEvent(fn){listeners.add(fn);return()=>listeners.delete(fn)},onClose(){return()=>{}},rpc:route};
const remote={client,features:{remoteGateway:true}};
const tools=new (modules.get('./remote-session-tools.js').namespace.RemoteSessionTools)(async(host,signal,query)=>query(remote,signal));
const signal=new AbortController().signal;const target={hostId:'probe-phone',backend:'harness',workspaceId:'phone-capabilities'};
try{
 const listing=await tools.run('remote_list_sessions',target,signal);assert.ok(listing.sessions.some(x=>x.sessionId==='control-bus'));
 const created=await tools.run('remote_create_session',{...target,operationId:'create-task-00000001'},signal);assert.equal(created.status,'accepted');const sessionId=created.sessionId;
 const request_id='native-tree-00000001';
 const sent=await tools.run('remote_send_message',{...target,sessionId,operationId:request_id,message:JSON.stringify({request_id,op:'ui.dump_tree',args:{}})},signal);assert.equal(sent.status,'accepted');
 const waited=await tools.run('remote_wait_sessions',{targets:[{...target,sessionId}],timeoutMs:1000},signal);assert.equal(waited.sessions[0].status,'idle');
 const history=await tools.run('remote_read_session',{...target,sessionId},signal);assert.equal(history.status,'idle');assert.ok(history.records.some(r=>r.type==='assistant/message'&&r.json.includes('probe-snapshot')));
 const downloaded=await tools.run('remote_download_file',{...target,sessionId,path:'artifacts/probe-artifact'},signal);assert.equal(downloaded.status,'downloaded');assert.equal(downloaded.bytes,18);
 console.log(JSON.stringify({nativeTools:'PASS',sessionId,records:history.records.length,download:downloaded}));
}finally{child.stdin.end();child.kill();}
