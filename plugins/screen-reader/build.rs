const EXCHANGE_SIZE_BYTES: u32 = 65_536;
const STACK_SIZE_BYTES: u32 = 262_144;

fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    if !target.starts_with("wasm32") {
        return;
    }

    let global_base = EXCHANGE_SIZE_BYTES.max(STACK_SIZE_BYTES);

    println!("cargo:rustc-link-arg=--global-base={global_base}");
    println!("cargo:rustc-link-arg=-zstack-size={STACK_SIZE_BYTES}");
    println!("cargo:rerun-if-changed=build.rs");
}

