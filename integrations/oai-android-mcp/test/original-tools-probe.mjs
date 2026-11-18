// 固定上游原工具互通验证：仅系统采集器为替身，真实门面、Session 工具和文件解析不改写。
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import { stripTypeScriptTypes, createRequire } from 'node:module';
import { SourceTextModule, SyntheticModule } from 'node:vm';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { rename, mkdir, mkdtemp } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { delimiter, join, resolve } from 'node:path';
const root = resolve(process.argv[2] ?? '.');
assert.ok(process.env.JAVA_HOME, '互通探针需要 JAVA_HOME 指向 JDK 21');
const probeHome = await mkdtemp(join(tmpdir(), 'oai-phone-tools-probe-'));
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
const double = { 'node:fs/promises':{...realFs,rm:async path=>{await mkdir(join(root,'.worktrees/trash'),{recursive:true});await rename(path,join(root,'.worktrees/trash',`probe-${crypto.randomUUID()}`));}}, 'node:os':{homedir:()=>probeHome}, 'zod':{z}, '@dsh-remote/client-core':{RemoteClientError}, '@dsh-remote/protocol':{HARNESS_API_TRANSFER_CHUNK_BYTES:262144, MAX_HARNESS_API_TRANSFER_BYTES:50*1024*1024}, './ids.js':{uuidV7:()=>crypto.randomUUID()}, './remote-file-upload.js':{uploadRemoteFile:()=>{throw Error('UPLOAD_NOT_REQUESTED')}} };
const synth = new Map();
await modules.get('./remote-session-tools.js').link(async spec => {
 if(modules.has(spec)) return modules.get(spec);
 if(!synth.has(spec)) { const values = double[spec] ?? await import(spec); synth.set(spec,new SyntheticModule(Object.keys(values),function(){for(const [k,v] of Object.entries(values))this.setExport(k,v)})); }
 return synth.get(spec);
});
await modules.get('./remote-session-tools.js').evaluate();
const cacheRoot=join(process.env.GRADLE_USER_HOME ?? join(homedir(), '.gradle'),'caches/modules-2/files-2.1');
const jar=(pkg,ver)=>{const base=join(cacheRoot,pkg,ver);for(const h of readdirSync(base)){for(const f of readdirSync(join(base,h))){if(f.endsWith('.jar'))return join(base,h,f)}}throw Error(`互通探针缺少运行依赖：${pkg}/${ver}`)};
const classRoots = ['apps/android/gateway-client/build/tmp/kotlin-classes/debugUnitTest', 'apps/android/gateway-client/build/tmp/kotlin-classes/debug', 'apps/android/control-ports/build/tmp/kotlin-classes/debug'].map(path => join(root, path));
for (const [index, path] of [
 [0, 'com/openandroidintelligence/gateway/remote/facade/PhoneVirtualHostProbe.class'],
 [1, 'com/openandroidintelligence/gateway/remote/facade/PhoneControlBus.class'],
 [2, 'com/openandroidintelligence/control/remote/ControlRequestMetadata.class'],
]) assert.ok(existsSync(join(classRoots[index], path)), `互通探针缺少 JVM 类：${path}；请运行 npm run test:phone-mcp:interop`);
const cp=[...classRoots,jar('org.jetbrains.kotlin/kotlin-stdlib','2.1.20'),jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm','1.9.0')].join(delimiter);
const child=spawn(join(process.env.JAVA_HOME,'bin/java'),['-cp',cp,'com.openandroidintelligence.gateway.remote.facade.PhoneVirtualHostProbe'],{stdio:['pipe','pipe','inherit']});
const pending=[];
let childFailure;
const fail = error => { childFailure = error; for (const waiting of pending.splice(0)) waiting.reject(error); };
child.once('error', fail);
child.once('exit', (code, signal) => fail(Error(`手机 JVM 探针提前退出：${code ?? signal}`)));
child.stdin.on('error', fail);
const deadline = setTimeout(() => { fail(Error('手机互通探针超过 30 秒')); child.kill(); }, 30_000);
const lines=createInterface({input:child.stdout});
lines.on('line',line=>{
 try { const value=JSON.parse(line);if(value.event){for(const fn of listeners)fn(value);}else pending.shift()?.resolve(value.response); }
 catch (error) { fail(error); child.kill(); }
});
const route=(method,params)=>new Promise((resolveResponse,rejectResponse)=>{
 if (childFailure) { rejectResponse(childFailure); return; }
 const timeout = setTimeout(() => { fail(Error(`手机 RPC 响应超时：${method}`)); child.kill(); }, 10_000);
 pending.push({ resolve: value => { clearTimeout(timeout); resolveResponse(value); }, reject: error => { clearTimeout(timeout); rejectResponse(error); } });
 child.stdin.write(JSON.stringify({method,params})+'\n');
});
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
}finally{clearTimeout(deadline);lines.close();child.stdin.end();child.kill();}
