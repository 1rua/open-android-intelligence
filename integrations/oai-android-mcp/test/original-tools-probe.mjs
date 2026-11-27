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
 const second=await tools.run('remote_create_session',{...target,operationId:'create-task-00000002'},signal);assert.equal(second.status,'accepted');assert.notEqual(second.sessionId,sessionId);
 const request_id='native-tree-00000001';
 const sent=await tools.run('remote_send_message',{...target,sessionId,operationId:request_id,message:JSON.stringify({request_id,op:'ui.dump_tree',args:{}})},signal);assert.equal(sent.status,'accepted');
 const waited=await tools.run('remote_wait_sessions',{targets:[{...target,sessionId}],timeoutMs:1000},signal);assert.equal(waited.sessions[0].status,'idle');
 const history=await tools.run('remote_read_session',{...target,sessionId},signal);assert.equal(history.status,'idle');assert.ok(history.records.some(r=>r.type==='assistant/message'&&r.truncated===false&&r.json.includes('probe-snapshot')));
 const downloaded=await tools.run('remote_download_file',{...target,sessionId,path:'artifacts/probe-artifact'},signal);assert.equal(downloaded.status,'downloaded');assert.equal(downloaded.bytes,18);
 assert.equal(readFileSync(downloaded.localPath).length,18);
 const oversizedId='native-oversized-0001';
 const sentLong=await tools.run('remote_send_message',{...target,sessionId:second.sessionId,operationId:oversizedId,message:JSON.stringify({request_id:oversizedId,op:'ui.dump_tree',args:{text:'oversized-receipt-probe'}})},signal);assert.equal(sentLong.status,'accepted');
 const waitedLong=await tools.run('remote_wait_sessions',{targets:[{...target,sessionId:second.sessionId}],timeoutMs:1000},signal);assert.equal(waitedLong.sessions[0].status,'idle');
 const longHistory=await tools.run('remote_read_session',{...target,sessionId:second.sessionId,maxMessages:50},signal);assert.equal(longHistory.status,'idle');
 const summaryRecord=longHistory.records.find(r=>r.type==='assistant/message'&&r.json.includes(oversizedId));assert.ok(summaryRecord,'超长回执必须保留可解析的终态摘要记录');
 assert.equal(summaryRecord.truncated,false,'手机端 16,000 UTF-16 预算转产物后，历史摘要不得被上游截断');
 assert.ok(summaryRecord.json.length<=16000,'历史摘要记录必须在 16,000 UTF-16 单元预算内');
 const receiptText=JSON.parse(summaryRecord.json).event.data.message.content.find(b=>b.type==='text').text;
 const receipt=JSON.parse(receiptText);
 assert.equal(receipt.request_id,oversizedId);
 assert.equal(receipt.taskSessionId,second.sessionId);
 assert.equal(receipt.state,'completed');
 assert.equal(receipt.resultAvailable,true);
 const artifactRef=receipt?.resultArtifact;
 assert.ok(artifactRef?.path,'终态摘要必须引用同任务完整结果 artifact');
 assert.equal(artifactRef.path,`artifacts/result-${oversizedId}`);
 assert.match(artifactRef.version,/^sha256:[0-9a-f]{64}$/u,'结果 artifact 版本必须为完整 SHA-256');
 const artifact=await tools.run('remote_download_file',{...target,sessionId:second.sessionId,path:artifactRef.path},signal);assert.equal(artifact.status,'downloaded');assert.equal(artifact.bytes,artifactRef.bytes);assert.ok(artifact.bytes>16000,'完整结果 artifact 必须比上游历史记录预算更长');
 const artifactBytes=readFileSync(artifact.localPath);assert.equal(artifactBytes.length,artifactRef.bytes);
 const artifactSha256=createHash('sha256').update(artifactBytes).digest('hex');
 assert.equal(`sha256:${artifactSha256}`,artifactRef.version,'下载产物字节哈希必须与摘要 version 一致');
 const fullReceipt=JSON.parse(artifactBytes.toString('utf8'));
 assert.equal(fullReceipt.request_id,oversizedId);
 assert.equal(fullReceipt.taskSessionId,second.sessionId);
 assert.equal(fullReceipt.result.snapshotId,'probe-oversized-snapshot');
 assert.equal(fullReceipt.result.payload.length,20000);
 // 覆盖旧 12,000 字符正文阈值盲区：正文约 7,100 字符（<12,000），但整条 Session V3 序列化记录 >16,000 UTF-16 单元，必须同样转产物且不被截断。
 const blindSpotId='native-blindspot-0001';
 const sentBlind=await tools.run('remote_send_message',{...target,sessionId:second.sessionId,operationId:blindSpotId,message:JSON.stringify({request_id:blindSpotId,op:'plugin.invoke',args:{pluginId:'org.openandroidintelligence.probe',capabilityId:'org.openandroidintelligence.probe.receipt@1.0.0',method:'oversized',params:{repeat:7000}}})},signal);assert.equal(sentBlind.status,'accepted');
 const waitedBlind=await tools.run('remote_wait_sessions',{targets:[{...target,sessionId:second.sessionId}],timeoutMs:1000},signal);assert.equal(waitedBlind.sessions[0].status,'idle');
 const blindHistory=await tools.run('remote_read_session',{...target,sessionId:second.sessionId,maxMessages:50},signal);assert.equal(blindHistory.status,'idle');
 const blindRecord=blindHistory.records.find(r=>r.type==='assistant/message'&&r.json.includes(blindSpotId));assert.ok(blindRecord,'旧阈值盲区回执必须保留完整摘要');
 assert.equal(blindRecord.truncated,false,'旧阈值盲区回执不得被上游截断');
 assert.ok(blindRecord.json.length<=16000);
 const blindReceipt=JSON.parse(JSON.parse(blindRecord.json).event.data.message.content.find(b=>b.type==='text').text);
 assert.ok(blindReceipt?.resultArtifact?.path,'旧阈值盲区回执必须转入同任务 resultArtifact');
 const blindArtifact=await tools.run('remote_download_file',{...target,sessionId:second.sessionId,path:blindReceipt.resultArtifact.path},signal);assert.equal(blindArtifact.status,'downloaded');assert.equal(blindArtifact.bytes,blindReceipt.resultArtifact.bytes);
 const blindBytes=readFileSync(blindArtifact.localPath);assert.equal(blindBytes.length,blindReceipt.resultArtifact.bytes);
 assert.equal(`sha256:${createHash('sha256').update(blindBytes).digest('hex')}`,blindReceipt.resultArtifact.version);
 assert.equal(JSON.parse(blindBytes.toString('utf8')).result.payload.length,7000);
 const statusId='native-status-0000001';
 const sentStatus=await tools.run('remote_send_message',{...target,sessionId:'control-bus',operationId:statusId,message:JSON.stringify({request_id:statusId,op:'request.status',args:{targetRequestId:oversizedId,targetTaskSessionId:second.sessionId}})},signal);assert.equal(sentStatus.status,'accepted');
 const busHistory=await tools.run('remote_read_session',{...target,sessionId:'control-bus',maxMessages:20},signal);
 const statusRecord=busHistory.records.find(r=>r.type==='assistant/message'&&r.truncated===false&&r.json.includes(statusId));assert.ok(statusRecord,'control-bus 必须返回 request.status 摘要');
 const statusEnvelope=JSON.parse(JSON.parse(statusRecord.json).event.data.message.content.find(b=>b.type==='text').text);
 assert.equal(statusEnvelope.request_id,statusId);
 assert.equal(statusEnvelope.taskSessionId,'control-bus');
 assert.equal(statusEnvelope.state,'completed');
 assert.equal(statusEnvelope.result.request_id,oversizedId);
 assert.equal(statusEnvelope.result.taskSessionId,second.sessionId);
 assert.equal(statusEnvelope.result.state,'completed');
 assert.equal(statusEnvelope.result.resultAvailable,true);
 console.log(JSON.stringify({nativeTools:'PASS',sessionId,secondSessionId:second.sessionId,records:history.records.length,summaryRecordLength:summaryRecord.json.length,download:downloaded,artifact:{bytes:artifact.bytes,sha256:artifactSha256}}));
}finally{clearTimeout(deadline);lines.close();child.stdin.end();child.kill();}
