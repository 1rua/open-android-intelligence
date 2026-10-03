//! Open Android Intelligence 受保护插件 Rust SDK，对应内核 ABI `open_android_intelligence_kernel_v1`。
//!
//! # 1. 隔离前提
//!
//! 受保护插件编译到 `wasm32-unknown-unknown`，`no_std` + `panic = "abort"`。
//! 按 `docs/contracts/device-plugin-package-v1.md` §5.1，模块**只能导入
//! `open_android_intelligence_kernel_v1`**，禁止 WASI 的 socket / 文件系统 / 时钟 / 随机数 /
//! 进程接口。
//!
//! 内核能力在 [`kernel`] 模块里声明。**未被引用的导入不会进入产物**：
//! echo 这类不需要内核能力的插件，产物的导入数仍然是 0。
//!
//! # 2. 导出
//!
//! | 导出 | 签名 | 用途 |
//! |---|---|---|
//! | `open_android_intelligence_plugin_main` | `(i32, i32) -> i64` | 唯一入口，即 `.alp` manifest 的 `runtime.entrypoint` |
//! | `memory` | — | Rust 默认导出的线性内存 |
//!
//! 链接器另外会导出 `__data_end` 与 `__heap_base` 两个全局，宿主可以忽略。
//!
//! # 3. 返回值编码（**Kotlin 宿主按此解析**）
//!
//! ```text
//! bits 63..32 = ptr   响应在插件线性内存中的起始偏移
//! bits 31..0  = len   响应字节数
//!
//! value = ((ptr as u64) << 32) | (len as u64)
//! ```
//!
//! 顺序与入参 `(request_ptr, request_len)` 一致：**指针在前，长度在后**。
//! 与 `KernelAbi.packResult` 一致。
//!
//! 唯一保留值：`value == u64::MAX`（`0xFFFF_FFFF_FFFF_FFFF`）表示插件失败。
//! 正常结果永远不可能是该值（受保护插件的缓冲区远小于 4 GiB）。
//! 在 Kotlin 侧它会落成 `pointer == -1 && length == -1`，被 `BAD_RESULT` 拦下。
//!
//! # 4. 调用协议
//!
//! 宿主与插件之间有一块固定的交换区：
//!
//! ```text
//! memory[0 .. EXCHANGE_SIZE_BYTES)   宿主写请求，插件只读
//! memory[1 MiB 以上]                 插件的静态数据、栈与响应缓冲区
//! ```
//!
//! 交换区上限 [`EXCHANGE_SIZE_BYTES`]（64 KiB）与
//! `KernelAbi.EXCHANGE_SIZE_BYTES` 保持一致。插件的所有静态数据都链接在
//! 1 MiB 以上（由 `tests/wasm_abi.rs` 断言），因此宿主写交换区不会破坏插件。
//!
//! ```text
//! 1) 宿主写入 memory[0 .. request_len]，request_len <= 64 KiB
//! 2) packed = open_android_intelligence_plugin_main(0, request_len)
//! 3) packed == u64::MAX → 失败；否则 (rptr, rlen) = unpack(packed)
//! 4) 宿主立刻复制 memory[rptr .. rptr + rlen]
//! ```
//!
//! 响应缓冲区在**每次入口调用开始时自动归还**，所以同一个实例可以无限次
//! 调用，不会累积占用。代价是响应只在第 3 步到第 4 步之间有效：
//! 下一次调用会覆盖它。宿主必须同步复制。
//!
//! 插件 panic、越界或缓冲耗尽在 wasm 层表现为 trap，不走返回值通道；
//! 宿主需要同时处理 trap 与 `RESULT_FAILED`。
//!
//! # 5. 用法
//!
//! ```rust,ignore
//! use open_android_intelligence_sdk::{PluginError, declare_plugin};
//! extern crate alloc;
//! use alloc::vec::Vec;
//!
//! fn echo(request: &[u8]) -> Result<Vec<u8>, PluginError> {
//!     let mut out = Vec::with_capacity(request.len());
//!     out.extend_from_slice(request);
//!     Ok(out)
//! }
//!
//! declare_plugin! { handler = echo, arena_bytes = 65_536 }
//! ```

#![cfg_attr(all(target_arch = "wasm32", not(test)), no_std)]
#![warn(clippy::all)]
#![warn(unsafe_op_in_unsafe_fn)]

extern crate alloc;

use alloc::vec::Vec;
use core::cell::UnsafeCell;

pub use core::alloc::{GlobalAlloc, Layout};
pub use core::panic::PanicInfo;

/// ABI 版本，与 `.alp` manifest 的 `runtime.abiVersion` 对应。
pub const ABI_VERSION: &str = "1.0";

/// 宿主导入模块名。受保护插件只允许导入这个模块。
pub const KERNEL_ABI_MODULE: &str = "open_android_intelligence_kernel_v1";

/// `.alp` manifest 的 `runtime.entrypoint` 必须填这个名字。
pub const ENTRYPOINT: &str = "open_android_intelligence_plugin_main";

/// 交换区大小：宿主写请求的固定区域 `memory[0 .. 64 KiB)`。
///
/// 必须与 `KernelAbi.EXCHANGE_SIZE_BYTES` 保持一致，否则两侧的边界判断
/// 会对不上。插件的所有静态数据都链接在这块区域之上。
pub const EXCHANGE_SIZE_BYTES: usize = 65_536;

/// 返回值的失败哨兵。见模块文档第 3 节。
pub const RESULT_FAILED: u64 = u64::MAX;

/// 平台内核导出给受保护插件的能力。
///
/// 函数签名与 `KernelAbi.FUNCTIONS` 一一对应。插件**只能**通过这些函数
/// 取得随机数、时钟和日志——没有文件系统、环境变量、进程，也没有任何
/// 直达网络的方式；所有特权操作都在沙箱外由内核代理。
///
/// 未被引用的函数不会进入产物的导入段，所以引用 [`kernel`] 并不会让
/// 不需要内核能力的插件产生导入。
pub mod kernel {
    #[link(wasm_import_module = "open_android_intelligence_kernel_v1")]
    extern "C" {
        pub fn kernel_call(name_ptr: u32, name_len: u32, input_ptr: u32, input_len: u32, output_ptr: u32, capacity: u32) -> i32;
        /// 写一条结构化日志。参数：级别、缓冲区指针、字节数。
        /// 超过宿主上限的日志会被静默丢弃，不影响调用结果。
        pub fn kernel_log(level: u32, ptr: u32, len: u32);

        /// 用宿主提供的随机数填满 `[ptr, ptr + len)`。
        /// 受保护插件看不到 WASI 的随机源，只能用这个。
        pub fn kernel_random_fill(ptr: u32, len: u32);

        /// 宿主单调时钟的毫秒数。禁止插件自己取时，是为了让执行时间预算
        /// 成为宿主单方面的判断。
        pub fn kernel_now_millis() -> u64;
    }
}

/// 把响应地址与长度打包成入口的返回值。
///
/// 布局：`((ptr as u64) << 32) | (len as u64)`。
#[must_use]
pub const fn pack(ptr: u32, len: u32) -> u64 {
    ((ptr as u64) << 32) | (len as u64)
}

/// 拆包入口返回值。失败哨兵返回 [`None`]。
#[must_use]
pub const fn unpack(value: u64) -> Option<(u32, u32)> {
    if value == RESULT_FAILED {
        return None;
    }
    Some(((value >> 32) as u32, (value & 0xFFFF_FFFF) as u32))
}

/// 插件可以返回的失败原因。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PluginError {
    /// 请求指针/长度不在插件线性内存范围内。
    RequestOutOfBounds,
    /// 插件缓冲区不足。
    OutOfMemory,
    /// 插件自身处理失败。
    HandlerFailed,
}

/// A versioned mediated primitive call. Identity/account/grants come from the
/// kernel's invocation scope, never from plugin-controlled request fields.
pub fn call_kernel(primitive: &str, request: &[u8]) -> Result<Vec<u8>, PluginError> {
    if request.len() > EXCHANGE_SIZE_BYTES || primitive.len() > 256 {
        return Err(PluginError::RequestOutOfBounds);
    }
    #[cfg(target_arch = "wasm32")]
    {
        let mut response = alloc::vec![0u8; EXCHANGE_SIZE_BYTES];
        let length = unsafe { kernel::kernel_call(primitive.as_ptr() as u32, primitive.len() as u32,
            request.as_ptr() as u32, request.len() as u32, response.as_mut_ptr() as u32, response.len() as u32) };
        if length < 0 || length as usize > response.len() { return Err(PluginError::HandlerFailed); }
        response.truncate(length as usize);
        Ok(response)
    }
    #[cfg(not(target_arch = "wasm32"))]
    { Err(PluginError::HandlerFailed) }
}

/// 只 bump、不回收的分配器。
///
/// 容量由插件在 [`declare_plugin!`] 里通过 `arena_bytes` 选定。
/// 空间一次性归还（[`Bump::reset`]），不做单块回收：受保护插件的单次调用
/// 生命周期很短，回收逻辑本身是攻击面，宁可不要。
///
/// # Safety
///
/// `base` 用 [`UnsafeCell`] 暴露写权限，因此这里手工实现 `Sync`。
/// 前提：wasm 实例单线程，宿主串行调用；`alloc` 内部只在临界区内读写
/// `offset`，从不把 `base` 的裸指针长期外泄为 `&mut`。
pub struct Bump<const N: usize> {
    base: UnsafeCell<[u8; N]>,
    offset: UnsafeCell<usize>,
}

// SAFETY: 见结构体文档。实例只存在于单线程 wasm 模块或单线程测试中。
unsafe impl<const N: usize> Sync for Bump<N> {}

impl<const N: usize> Bump<N> {
    /// 构造一个空的缓冲区。
    #[must_use]
    pub const fn new() -> Self {
        Self {
            base: UnsafeCell::new([0u8; N]),
            offset: UnsafeCell::new(0),
        }
    }

    /// 已用字节数。
    #[must_use]
    pub fn used(&self) -> usize {
        // SAFETY: 单线程独占访问，offset 始终是已初始化值。
        unsafe { *self.offset.get() }
    }

    /// 剩余可分配字节数。
    #[must_use]
    pub fn remaining(&self) -> usize {
        N - self.used()
    }

    /// 一次性归还全部缓冲区。
    pub fn reset(&self) {
        // SAFETY: 单线程独占访问；调用方保证没有存活的引用指向这块内存。
        unsafe { *self.offset.get() = 0 };
    }

    /// 分配 `size` 字节，按 `align` 对齐。空间不足返回空指针。
    ///
    /// `align` 必须是 2 的幂且非 0。
    pub fn allocate(&self, size: usize, align: usize) -> *mut u8 {
        debug_assert!(align.is_power_of_two(), "align 必须是 2 的幂");
        // SAFETY: base 是长度为 N 的数组，转成首字节指针不越界。
        let base_ptr = unsafe { (*self.base.get()).as_mut_ptr() };
        let base_addr = base_ptr as usize;
        // SAFETY: 单线程独占访问。
        let offset = unsafe { *self.offset.get() };
        let aligned = (base_addr + offset + align - 1) & !(align - 1);
        let next = (aligned - base_addr) + size;
        if next > N {
            return core::ptr::null_mut();
        }
        // SAFETY: 单线程独占访问，next <= N。
        unsafe { *self.offset.get() = next };
        aligned as *mut u8
    }
}

impl<const N: usize> Default for Bump<N> {
    fn default() -> Self {
        Self::new()
    }
}

// SAFETY: 返回的内存位于 `base` 数组内且按 layout 对齐；空间不足时返回空指针
// 由调用方（alloc 的 handle_alloc_error）处理。dealloc 故意为空：内存由
// 入口调用开始时的 reset 统一归还。
unsafe impl<const N: usize> GlobalAlloc for Bump<N> {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        self.allocate(layout.size(), layout.align())
    }

    unsafe fn dealloc(&self, _ptr: *mut u8, _layout: Layout) {}
}

/// 取出宿主写入的请求字节。
///
/// 请求必须完整落在交换区 `[0, EXCHANGE_SIZE_BYTES)` 与线性内存之内。
/// 越界返回 [`None`]，绝不产生越界读：交换区之外是插件自己的静态数据和栈，
/// 一个被攻陷的宿主也不该能借这个入口读到它们。
#[must_use]
pub fn read_request<'a>(request_ptr: u32, request_len: u32) -> Option<&'a [u8]> {
    if request_len == 0 {
        return Some(&[]);
    }
    if !request_in_bounds(request_ptr, request_len) {
        return None;
    }
    if request_ptr == 0 {
        #[cfg(target_arch = "wasm32")]
        {
            // Address zero is valid WASM linear memory, but Rust references
            // must be non-null. Read the exchange bytes as external volatile
            // memory, then construct the slice over our non-null static arena.
            // Each module instance is single-threaded and invokes one handler
            // at a time; the copy remains valid until the next invocation.
            static mut REQUEST_COPY: [u8; EXCHANGE_SIZE_BYTES] = [0; EXCHANGE_SIZE_BYTES];
            unsafe {
                let target = core::ptr::addr_of_mut!(REQUEST_COPY) as *mut u8;
                for index in 0..request_len as usize {
                    target.add(index).write(core::ptr::read_volatile(index as *const u8));
                }
                return Some(core::slice::from_raw_parts(target, request_len as usize));
            }
        }
        #[cfg(not(target_arch = "wasm32"))]
        { return None; }
    }
    // SAFETY: 宿主保证 [ptr, ptr+len) 是可读的字节；已通过交换区边界检查。
    unsafe { Some(core::slice::from_raw_parts(request_ptr as *const u8, request_len as usize)) }
}

/// 请求是否完整落在交换区内。
///
/// 用 64 位算，避免 u32 加法溢出把越界请求算成合法。
///
/// 这里**不用** `memory.size` 查询线性内存：受保护插件的内存大小在链接期
/// 才确定，`memory.size` 在 LTO 下会被折叠成 0，运行期查询反而会误判。
/// 交换区大小是宿主与插件的固定契约，插件初始内存一定不小于它——这一点由
/// `tests/wasm_abi.rs` 对 memory section 的断言静态保证。
fn request_in_bounds(request_ptr: u32, request_len: u32) -> bool {
    (request_ptr as u64) + (request_len as u64) <= EXCHANGE_SIZE_BYTES as u64
}

/// 入口的真正实现：读请求 → 调插件 → 打包响应。
///
/// 由 [`declare_plugin!`] 生成的 `open_android_intelligence_plugin_main` 调用。
pub fn dispatch<F>(request_ptr: u32, request_len: u32, handler: F) -> u64
where
    F: FnOnce(&[u8]) -> Result<Vec<u8>, PluginError>,
{
    let Some(request) = read_request(request_ptr, request_len) else {
        return RESULT_FAILED;
    };
    let out = dispatch_with_response(request, handler).0;
    if out == RESULT_FAILED {
        return RESULT_FAILED;
    }
    out
}

/// 去掉线性内存寻址的同一套入口逻辑。
///
/// 返回 `(入口返回值, 响应内容)`。wasm 产物只使用第一个值；第二个值让
/// 64 位宿主上的单元测试也能读到响应内容——在 64 位进程里，真实地址无法
/// 塞进 u32，打包出来的 `ptr` 是不可解引用的。
///
/// 受保护插件的分配器不做单块回收，所以这里返回的 `Vec` 被丢弃后，
/// wasm 线性内存中的字节依旧有效，直到下一次入口调用。
#[must_use]
pub fn dispatch_with_response<F>(
    request: &[u8],
    handler: F,
) -> (u64, Option<Vec<u8>>)
where
    F: FnOnce(&[u8]) -> Result<Vec<u8>, PluginError>,
{
    let Ok(response) = handler(request) else {
        return (RESULT_FAILED, None);
    };

    let len = response.len();
    if len == 0 {
        return (pack(0, 0), Some(response));
    }
    if len > u32::MAX as usize {
        return (RESULT_FAILED, None);
    }
    // `dispatch` 只在 wasm32 上以真实地址运行，那里的 usize 就是 u32，
    // 下面的截断无损。64 位宿主只走单元测试，打包出的 ptr 不可解引用，
    // 响应内容通过第二个返回值取用。
    let ptr = response.as_ptr() as u32;
    (pack(ptr, len as u32), Some(response))
}

/// 立即 trap。插件 panic 与不可恢复错误的唯一出口。
#[cfg(target_arch = "wasm32")]
pub fn trap() -> ! {
    // wasm 的 unreachable 指令，宿主看到的是 trap。
    core::arch::wasm32::unreachable()
}

/// 生成一个符合 `open_android_intelligence_kernel_v1` 的受保护插件模块。
///
/// - `handler`：`fn(&[u8]) -> Result<Vec<u8>, PluginError>`。
/// - `arena_bytes`：响应缓冲区容量，默认 1 MiB。请求在交换区，不占它，
///   所以这个容量决定的是**单次响应**的上限。
///
/// 宏展开在**调用方 crate 内**，会定义全局分配器、panic handler 和入口导出。
/// 一个 crate 只能调用一次。
#[macro_export]
macro_rules! declare_plugin {
    (handler = $handler:path) => {
        $crate::declare_plugin!(handler = $handler, arena_bytes = 1_048_576);
    };
    (handler = $handler:path, arena_bytes = $arena:expr) => {
        mod __open_android_intelligence_plugin {
            use super::*;

            const ARENA_BYTES: usize = $arena;

            // 只在 wasm 产物里替换全局分配器：宿主单元测试要跑 std 的测试框架，
            // 用固定大小的 bump 会直接把 harness 撑爆。
            #[cfg_attr(all(target_arch = "wasm32", not(test)), global_allocator)]
            static OPEN_ANDROID_INTELLIGENCE_ARENA: $crate::Bump<ARENA_BYTES> = $crate::Bump::new();

            /// 插件入口。返回 `((ptr as u64) << 32) | (len as u64)`，
            /// 或 `u64::MAX` 表示失败。见 SDK 模块文档第 3 节。
            ///
            /// 每次进入都先归还上一轮的响应缓冲区：交换区与缓冲区互不重叠
            /// （插件数据链接在 1 MiB 以上），所以这一步不会破坏本次请求，
            /// 而且同一个实例可以无限次调用而不累积占用。
            #[no_mangle]
            pub extern "C" fn open_android_intelligence_plugin_main(request_ptr: u32, request_len: u32) -> u64 {
                OPEN_ANDROID_INTELLIGENCE_ARENA.reset();
                $crate::dispatch(request_ptr, request_len, $handler)
            }

            #[cfg(all(target_arch = "wasm32", not(test)))]
            #[panic_handler]
            fn __open_android_intelligence_panic(_info: &$crate::PanicInfo) -> ! {
                $crate::trap()
            }
        }
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pack_round_trips_pointer_and_length() {
        assert_eq!(pack(0x1234_5678, 0x9abc_def0), 0x1234_5678_9abc_def0);
        assert_eq!(unpack(0x1234_5678_9abc_def0), Some((0x1234_5678, 0x9abc_def0)));
    }

    #[test]
    fn pack_puts_pointer_in_high_bits_and_length_in_low_bits() {
        // 指针在前、长度在后，与入参顺序一致。
        let packed = pack(1, 2);
        assert_eq!(packed >> 32, 1);
        assert_eq!(packed & 0xFFFF_FFFF, 2);
    }

    #[test]
    fn unpack_rejects_failure_sentinel() {
        assert_eq!(unpack(RESULT_FAILED), None);
        assert_eq!(RESULT_FAILED, u64::MAX);
    }

    #[test]
    fn empty_result_is_pointer_zero_length_zero() {
        assert_eq!(pack(0, 0), 0);
        assert_eq!(unpack(0), Some((0, 0)));
    }

    #[test]
    fn bump_allocates_aligned_and_contiguous() {
        let bump = Bump::<256>::new();
        let a = bump.allocate(3, 1);
        assert!(!a.is_null());
        let b = bump.allocate(4, 4);
        assert!(!b.is_null());
        assert_eq!(b as usize % 4, 0, "分配必须满足对齐要求");
        assert!(b as usize >= unsafe { a.add(3) } as usize, "分配不得重叠");
        assert_eq!(bump.used(), (b as usize - a as usize) + 4);
    }

    #[test]
    fn bump_returns_null_when_exhausted_and_recovers_after_reset() {
        let bump = Bump::<16>::new();
        assert!(!bump.allocate(16, 1).is_null());
        assert_eq!(bump.remaining(), 0);
        assert!(bump.allocate(1, 1).is_null(), "耗尽必须返回空指针而不是越界");
        bump.reset();
        assert_eq!(bump.remaining(), 16);
        assert!(!bump.allocate(16, 1).is_null());
    }

    #[test]
    fn bump_alignment_never_overflows_the_arena() {
        let bump = Bump::<64>::new();
        // 先制造一个非对齐的偏移，再申请大对齐，尾部空间必须判定为不足。
        assert!(!bump.allocate(1, 1).is_null());
        assert!(bump.allocate(64, 64).is_null());
    }

    fn echo_handler(request: &[u8]) -> Result<Vec<u8>, PluginError> {
        let mut out = Vec::with_capacity(request.len());
        out.extend_from_slice(request);
        Ok(out)
    }

    #[test]
    fn dispatch_returns_failure_sentinel_for_handler_error() {
        let (packed, response) =
            dispatch_with_response(b"ignored", |_| Err(PluginError::HandlerFailed));
        assert_eq!(packed, RESULT_FAILED);
        assert!(response.is_none(), "失败时不得返回响应");
    }

    #[test]
    fn dispatch_returns_failure_sentinel_for_out_of_memory() {
        let (packed, response) =
            dispatch_with_response(b"ignored", |_| Err(PluginError::OutOfMemory));
        assert_eq!(packed, RESULT_FAILED);
        assert!(response.is_none());
    }

    #[test]
    fn dispatch_packs_handler_output_length() {
        let request = b"hello open-android-intelligence";
        let (packed, response) = dispatch_with_response(request, echo_handler);
        let (_, len) = unpack(packed).expect("成功路径必须返回可解析的 (ptr, len)");
        assert_eq!(len as usize, request.len());
        assert_eq!(response.expect("成功路径必须带回响应"), request);
    }

    #[test]
    fn dispatch_reports_zero_length_for_empty_response() {
        let (packed, response) = dispatch_with_response(b"x", |_| Ok(Vec::new()));
        assert_eq!(unpack(packed), Some((0, 0)));
        assert_eq!(response.expect("空响应仍然是成功"), Vec::<u8>::new());
    }

    #[test]
    fn dispatch_reads_the_request_through_the_abi_pointer() {
        // 32 位宿主上才能把真实地址塞进 u32，完整走通入口寻址。
        // wasm32 上的等价路径由 Android 真机的 Chicory 测试覆盖。
        #[cfg(target_pointer_width = "32")]
        {
            let request = b"32-bit round trip";
            let packed = dispatch(request.as_ptr() as u32, request.len() as u32, echo_handler);
            let (ptr, len) = unpack(packed).expect("入口必须返回成功结果");
            assert_eq!(len as usize, request.len());
            // SAFETY: 响应在 reset 之前一直有效。
            let out = unsafe { core::slice::from_raw_parts(ptr as *const u8, len as usize) };
            assert_eq!(out, request);
        }
    }

    #[test]
    fn dispatch_rejects_zero_length_request_with_a_null_pointer() {
        // 空请求允许传空指针，不能拿它去构造切片。
        let packed = dispatch(0, 0, echo_handler);
        assert_eq!(unpack(packed), Some((0, 0)));
    }

    #[test]
    fn read_request_returns_empty_slice_for_zero_length() {
        // 空请求允许传空指针，不能构造零长度的非空切片。
        assert_eq!(read_request(0, 0), Some([].as_slice()));
    }

    #[test]
    fn read_request_rejects_requests_beyond_the_exchange_region() {
        // 交换区之外是插件的静态数据和栈：宿主不得借入口读到那里。
        // 这些用例都必须在构造切片之前被拒绝——宿主是 64 位进程，
        // 拿 u32 地址去解引用本身就是未定义行为。
        assert_eq!(read_request(0, EXCHANGE_SIZE_BYTES as u32 + 1), None);
        assert_eq!(read_request(1, EXCHANGE_SIZE_BYTES as u32), None);
        assert_eq!(read_request(0, u32::MAX), None);
        assert_eq!(read_request(u32::MAX, 1), None);
    }

    #[test]
    fn exchange_size_matches_the_kotlin_kernel_abi() {
        // 改这个值必须同步改 KernelAbi.EXCHANGE_SIZE_BYTES，否则两侧边界对不上。
        assert_eq!(EXCHANGE_SIZE_BYTES, 65_536);
    }
}
