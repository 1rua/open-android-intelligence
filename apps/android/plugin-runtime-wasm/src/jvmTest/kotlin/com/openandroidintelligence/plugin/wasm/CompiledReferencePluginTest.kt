package com.openandroidintelligence.plugin.wasm

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import com.openandroidintelligence.kernel.ResourceBudget
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** JVM integration: compiled Cargo artifacts run through Chicory's real kernel ABI. */
class CompiledReferencePluginTest {
    private fun module(name:String):ByteArray {
        var directory=File(System.getProperty("user.dir")).absoluteFile
        while(true) {
            val file=File(directory,"plugins/target/wasm32-unknown-unknown/release/$name.wasm")
            if(file.isFile) return file.readBytes()
            directory=directory.parentFile ?: error("Compile Rust reference plugins before this integration test")
        }
    }
    private val budget=ResourceBudget(5000,16L*1024*1024,65536,1,0)
    @Test fun allReadPluginsReturnMediatedRecordsInsteadOfEchoingTheRequest() {
        for((name,primitive) in listOf("sms" to "kernel.sms.read","notifications" to "kernel.notifications.read","call_log" to "kernel.call-log.read")) {
            val identity=PluginIdentity("org.example.$name","author", "1.0.0")
            val input="{\"limit\":1,\"operation\":\"query\"}".toByteArray()
            val records="{\"records\":[{\"id\":\"actual-provider-record\"}]}".toByteArray()
            var calls=0
            val runtime=ChicoryPluginRuntime(InvocationBudget(5000,16L*1024*1024,65536),{module(name)},mediatedCall={ actual,id,args ->
                assertEquals(identity,actual);assertEquals(primitive,id);assertArrayEquals(input,args);calls++;records
            })
            assertArrayEquals(records,runtime.invoke(identity,budget,input));assertEquals(1,calls)
        }
    }
    @Test fun smsScheduleStatusAndCancellationReachTheirRespectiveKernelPorts() {
        val identity=PluginIdentity("org.example.sms","author","1.0.0")
        for((operation,primitive) in listOf("schedule" to "kernel.scheduler.set","job-status" to "kernel.scheduler.read","cancel" to "kernel.scheduler.cancel")) {
            var observed:String?=null
            val runtime=ChicoryPluginRuntime(InvocationBudget(5000,16L*1024*1024,65536),{module("sms")},mediatedCall={_,id,args ->
                observed=id
                if(operation=="schedule") {
                    assertTrue(args.decodeToString().contains("\"capabilityId\":\"org.openandroidintelligence.sms.query\""))
                    assertTrue(args.decodeToString().contains("\"capabilityVersion\":\"1.0.0\""))
                }
                "{\"jobId\":\"job-real\"}".toByteArray()
            })
            val result=runtime.invoke(identity,budget,"{\"operation\":\"$operation\"}".toByteArray()).decodeToString()
            assertEquals(primitive,observed);assertTrue(result.contains("job-real"))
        }
    }
    @Test fun aKernelDenialNeverProducesTheRequestedRecords() {
        val runtime=ChicoryPluginRuntime(InvocationBudget(5000,16L*1024*1024,65536),{module("sms")},mediatedCall={_,_,_ -> error("LOCAL_GRANT_DENIED")})
        val failure=runCatching { runtime.invoke(PluginIdentity("org.example.sms","author","1.0.0"),budget,"{}".toByteArray()) }.exceptionOrNull()
        assertNotNull(failure)
    }
}
