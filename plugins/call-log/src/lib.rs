//! Open Android Intelligence 通话记录参考插件：实现 `org.openandroidintelligence.call-log.query@1.0.0`。
//!
//! 消费内核 primitive `kernel.call-log.read`，将查询参数转换为受保护的内核调用
//! 并整形输出数据。

#![cfg_attr(all(target_arch = "wasm32", not(test)), no_std)]
#![warn(clippy::all)]

extern crate alloc;

use open_android_intelligence_sdk::{PluginError, declare_plugin};
use alloc::vec::Vec;

pub fn handle_call_log_query(request: &[u8]) -> Result<Vec<u8>, PluginError> {
    let primitive = "kernel.call-log.read";
    open_android_intelligence_sdk::call_kernel(primitive, request)
}

declare_plugin! { handler = handle_call_log_query, arena_bytes = 65_536 }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_a_missing_host_instead_of_echoing_parameters() {
        assert_eq!(handle_call_log_query(b"{\"limit\":5}"), Err(PluginError::HandlerFailed));
    }
}
