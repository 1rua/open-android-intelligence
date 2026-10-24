# THIRD PARTY NOTICES

This artifact embeds a Go binding of Tailscale `v1.98.10` built with
gomobile. The following Go modules are linked into the native
libraries (the `go list -deps` build closure of the wrapper package)
and their licenses are enumerated below. License texts are the files
shipped in the pinned Go module cache.

| Module | Version | SPDX | License file SHA-256 |
| --- | --- | --- | --- |
| `filippo.io/edwards25519` | v1.2.0 | BSD-3-Clause | 2d36597f7117c38b006835ae7f537487207d8ec407aa9d9980794b2030cbc067 |
| `github.com/coder/websocket` | v1.8.14 | UNKNOWN | cc0975a5f6305145bdd7b41ce9479632fdac3870e6ac4281f28017f18c767c4e |
| `github.com/creachadair/msync` | v0.8.1 | BSD-3-Clause | 1d45f7789b678c742a045d7a6923c2762f667dc2523b9b4c01fe240d24c83083 |
| `github.com/fxamacker/cbor/v2` | v2.9.0 | MIT | 78cad457d5ea7318230f3d969d4cdf29cef45524a1fc8ca3a97646da1ad7a841 |
| `github.com/gaissmai/bart` | v0.26.1 | MIT | 12d27746d111da33969df0ecaa9b799e22c42db7d0b6a5164f383ec934233a41 |
| `github.com/go-json-experiment/json` | v0.0.0-20260623181947-01eb4420fa68 | BSD-3-Clause | 14a34c4db2d21bf9cf80d028b802cd22fed9bf597a6c2db7ce30ee6ffd04967a |
| `github.com/golang/groupcache` | v0.0.0-20241129210726-2c02b8208cf8 | Apache-2.0 | 73ba74dfaa520b49a401b5d21459a8523a146f3b7518a833eea5efa85130bf68 |
| `github.com/google/btree` | v1.1.3 | Apache-2.0 | cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30 |
| `github.com/hdevalence/ed25519consensus` | v0.2.0 | BSD-3-Clause | 789be8b214a1871d8d1a5ab157f8f4ad74cd889087762bd5cced07ed589b6bc7 |
| `github.com/huin/goupnp` | v1.3.0 | BSD-3-Clause | 440688406aeb546bad566031c8a01531a01a683166f7010380fcc0f68d830311 |
| `github.com/klauspost/compress` | v1.19.1 | Apache-2.0 | 0d9e582ee4bff57bf1189c9e514e6da7ce277f9cd3bc2d488b22fbb39a6d87cf |
| `github.com/mdlayher/socket` | v0.5.0 | MIT | 7ac20f598f63dfd35c7c6c3844073af0bebfa436cfe58b91af9ac17cd727ea42 |
| `github.com/pires/go-proxyproto` | v0.8.1 | Apache-2.0 | 666f1951be1d543e744818d232bb311a4a310fd1d344288642c796fca39af3c7 |
| `github.com/tailscale/peercred` | v0.0.0-20250107143737-35a0c7bd7edc | BSD-3-Clause | 8e0db394107481655ef6a46baace86aaead5fc9b4c5ce83b9fa16037e891d03c |
| `github.com/tailscale/wireguard-go` | v0.0.0-20260810230014-2c8681f6b28a | MIT | 91276db973f25602d1aa43491f59cbc84cb88e6f151e1d0cc82a755563ce0195 |
| `github.com/x448/float16` | v0.8.4 | MIT | a555f1194fdac34da70fb416968f7e2217b02352c26c1eac2fa45fcb4290ae8d |
| `go4.org/mem` | v0.0.0-20240501181205-ae6ca9944745 | Apache-2.0 | c6596eb7be8581c18be736c846fb9173b69eccf6ef94c5135893ec56bd92ba08 |
| `go4.org/netipx` | v0.0.0-20231129151722-fdeea329fbba | BSD-3-Clause | 1bfc4f32f4ec8ca8fce54bd2d97784f003786753a69a78ca74ffae1574037fb9 |
| `golang.org/x/crypto` | v0.54.0 | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `golang.org/x/exp` | v0.0.0-20260410095643-746e56fc9e2f | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `golang.org/x/net` | v0.57.1-0.20260729233039-99c3b0a8f463 | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `golang.org/x/oauth2` | v0.36.0 | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `golang.org/x/sync` | v0.22.0 | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `golang.org/x/sys` | v0.47.0 | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `golang.org/x/term` | v0.45.0 | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `golang.org/x/text` | v0.40.0 | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `golang.org/x/time` | v0.15.0 | BSD-3-Clause | 911f8f5782931320f5b8d1160a76365b83aea6447ee6c04fa6d5591467db9dad |
| `gvisor.dev/gvisor` | v0.0.0-20260224225140-573d5e7127a8 | Apache-2.0 | 0fbab5c58efbdf6d31e8085214f2dd821659c03d73cff3ed2b08e98826ea1cd9 |
| `tailscale.com` | v0.0.0 | BSD-3-Clause | a7ca6186a7963a0a60740f6047760eecd7a0234e8c38bd7e1e0bbcb324bda45b |

## Controller review required

License could not be classified for:
- `github.com/coder/websocket`
