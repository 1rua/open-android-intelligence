package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.remote.*
import org.junit.Assert.*
import org.junit.Test

class NativePushDeliveryKernelTest {
    private val access=ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://remote.example"),"owner","reader","phone",
        "sha256:"+"1".repeat(64),"sha256:"+"2".repeat(64)),3,"connection",1)
    private fun grants()=PairingGrantStateHolder(InMemoryPairingGrantStore(),AndroidAuditStore(InMemoryAuditSink()))
    @Test fun 当前真实Remote与独立推送授权允许一次读取但不创造普通能力授权() {
        val grants=grants();val binding=RemoteCapabilityBinding(access) { true };val registration=grants.attachRemoteConnection(binding)
        var calls=0;var fences=0
        val result=NativePushDeliveryKernel(grants) { false }.execute(binding,{NativePushDeliveryPolicy("local-policy",true)}, { fences++ }) { calls++;"event" }
        assertEquals("event",result);assertEquals(1,calls);assertEquals(1,fences)
        assertFalse(grants.currentKernelGrant(binding.id)!!.backgroundSync)
        registration.close()
    }
    @Test fun 同账号却未注册真实通道时拒绝且不会进入投递读取() {
        val grants=grants();val binding=RemoteCapabilityBinding(access) { true };var calls=0
        assertThrows(ControlRequestException::class.java) {
            NativePushDeliveryKernel(grants) { false }.execute(binding,{NativePushDeliveryPolicy("policy",true)}, {}) { calls++ }
        }
        assertEquals(0,calls)
    }
    @Test fun 默认关闭或进入系统前撤销不能读取或消费事件() {
        val grants=grants();var current=true;val binding=RemoteCapabilityBinding(access) { current };val registration=grants.attachRemoteConnection(binding)
        val kernel=NativePushDeliveryKernel(grants) { false };var calls=0
        assertThrows(ControlRequestException::class.java) { kernel.execute(binding,{NativePushDeliveryPolicy("policy",false)}, {}) { calls++ } }
        assertThrows(ControlRequestException::class.java) { kernel.execute(binding,{NativePushDeliveryPolicy("policy",true)}, {current=false}) {calls++} }
        assertEquals(0,calls);registration.close()
    }
    @Test fun 读取期间本机授权变化或急停后不交付敏感结果() {
        val grants=grants();val binding=RemoteCapabilityBinding(access) { true };val registration=grants.attachRemoteConnection(binding)
        var policy="before";var stopped=false
        val kernel=NativePushDeliveryKernel(grants) { stopped }
        assertThrows(ControlRequestException::class.java) {
            kernel.execute(binding,{NativePushDeliveryPolicy(policy,true)}, {}) {policy="after";"secret"}
        }
        assertThrows(ControlRequestException::class.java) {
            kernel.execute(binding,{NativePushDeliveryPolicy(policy,true)}, {}) {stopped=true;"secret"}
        }
        registration.close()
    }
}
