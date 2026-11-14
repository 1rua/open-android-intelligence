package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.remote.ControlOrigin
import com.openandroidintelligence.control.remote.RemoteControlPrincipal
import org.junit.Assert.*
import org.junit.Test

class RemoteBackgroundBindingTest {
    private val principal = RemoteControlPrincipal(ControlOrigin.fromCanonical("https://remote.example"),"account","controller","host",
        "sha256:"+"1".repeat(64),"sha256:"+"2".repeat(64))

    @Test fun 后台授权投影只在同步调用期间存在且无须临时连接() {
        val holder=PairingGrantStateHolder(InMemoryPairingGrantStore(),AndroidAuditStore(InMemoryAuditSink()))
        holder.updatePrimitive("kernel.background.run",true)
        val binding=RemoteBackgroundBinding(principal,5,holder.state.value!!.revision) { true }
        assertNull(holder.currentBinding())
        assertNull(holder.currentKernelGrant(binding.id))
        val registration=holder.attachRemoteBackground(binding)
        assertTrue(holder.currentKernelGrant(binding.id)!!.backgroundSync)
        registration.close()
        assertNull(holder.currentKernelGrant(binding.id))
    }

    @Test fun 本机授权变更或持久账号撤销立即封闭已注册后台投影() {
        val holder=PairingGrantStateHolder(InMemoryPairingGrantStore(),AndroidAuditStore(InMemoryAuditSink()))
        holder.updatePrimitive("kernel.background.run",true)
        var current=true
        val binding=RemoteBackgroundBinding(principal,5,holder.state.value!!.revision) { current }
        val registration=holder.attachRemoteBackground(binding)
        current=false
        assertNull(holder.currentKernelGrant(binding.id))
        current=true
        holder.updatePrimitive("kernel.background.run",false)
        assertNull(holder.currentKernelGrant(binding.id))
        registration.close()
        assertThrows(IllegalStateException::class.java) { holder.attachRemoteBackground(binding) }
    }
}
