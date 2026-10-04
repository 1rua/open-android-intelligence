//! Open Android Intelligence 短信参考插件：实现 `org.openandroidintelligence.sms.query@1.0.0`。
//!
//! 消费内核 primitive `kernel.sms.read`，将查询参数转换为受保护的内核调用
//! 并整形输出数据。

#![cfg_attr(all(target_arch = "wasm32", not(test)), no_std)]
#![warn(clippy::all)]

extern crate alloc;

use open_android_intelligence_sdk::{PluginError, declare_plugin};
use alloc::vec::Vec;

pub fn handle_sms_query(request: &[u8]) -> Result<Vec<u8>, PluginError> {
    // Inputs are schema validated and canonicalized by the host before this ABI.
    let has = |field: &[u8]| request.windows(field.len()).any(|w| w == field);
    if has(b"\"operation\":\"schedule\"") {
        // The public schedule schema stays SMS-specific; the generic kernel
        // receives the exact verified capability to invoke when the timer fires.
        if request.first() != Some(&b'{') { return Err(PluginError::HandlerFailed); }
        let mut scheduled = b"{\"capabilityId\":\"org.openandroidintelligence.sms.query\",\"capabilityVersion\":\"1.0.0\",".to_vec();
        scheduled.extend_from_slice(&request[1..]);
        return open_android_intelligence_sdk::call_kernel("kernel.scheduler.set", &scheduled);
    }
    let primitive = if has(b"\"operation\":\"job-status\"") { "kernel.scheduler.read" }
        else if has(b"\"operation\":\"cancel\"") { "kernel.scheduler.cancel" }
        else { "kernel.sms.read" };
    open_android_intelligence_sdk::call_kernel(primitive, request)
}

declare_plugin! { handler = handle_sms_query, arena_bytes = 65_536 }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_a_missing_host_instead_of_echoing_parameters() {
        assert_eq!(handle_sms_query(b"{\"limit\":5}"), Err(PluginError::HandlerFailed));
    }
}
