//! 只通过平台内核调用屏幕capture能力，不包含网络、会话或 Agent 宿主依赖。

#![cfg_attr(all(target_arch = "wasm32", not(test)), no_std)]
#![warn(clippy::all)]

extern crate alloc;
use alloc::vec::Vec;
use open_android_intelligence_sdk::{declare_plugin, PluginError, EXCHANGE_SIZE_BYTES};

pub fn handle_screen(request: &[u8]) -> Result<Vec<u8>, PluginError> {
    open_android_intelligence_sdk::call_kernel("kernel.screen.capture", request)
}

// 输入由已签名 schema 校验。单包只声明一个 primitive，读授权不能获得控制权。
declare_plugin! { handler = handle_screen, arena_bytes = 2 * EXCHANGE_SIZE_BYTES }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_a_missing_host_instead_of_echoing_parameters() {
        assert_eq!(handle_screen(b"{}"), Err(PluginError::HandlerFailed));
    }
    #[test]
    fn rejects_oversized_input_before_entering_the_host() {
        assert_eq!(handle_screen(&alloc::vec![b'x'; EXCHANGE_SIZE_BYTES + 1]), Err(PluginError::RequestOutOfBounds));
    }
}
