# CCTV website H5E playback

The CCTV website Python source returns HLS with `cdrmldcctv` TS segments. The manifests do not declare standard HLS sample encryption, so passing those bytes directly to the decoder causes damaged H264 frames. FongMi 5.8.7's published APK contains a separate H5E data source for those paths; it is absent from the public Media3 source used by this application.

The application now applies a small pure Java H5E transform in the Exo data source. Segment URL paths must contain `cdrmldcctv` and end in `.ts`; the transform additionally requires the marked H264 SPS in that video PID. Normal segments and other PIDs remain unchanged. The adapter fetches at most 16 MiB, processes complete PES/NAL units, preserves audio, transport continuity and PCR, and provides requested read ranges over the transformed result. It wraps the cache so cached original bytes receive the same processing. Closing a pending request cancels the upstream load.

This implementation was reconstructed as a narrow source-level interoperability adapter, using the behavior of the official APK (SHA256 `cda5776ba2ee6f7dfdcd3d7c1a13095ba8672b9892d6fb9e1ba38f993ad66f3a`) as reference. It does not load or package obfuscated APK classes or the unrelated CMG native library. Python source URLs and production configuration are unchanged. Use the ExoPlayer engine for this entry; MPV does not pass through this adapter.

## Verification

Pure Java tests cover payload conversion, multi-packet/PID structure, input immutability, plain TS, PCR/audio preservation, malformed input and unsupported schemes. Data source tests cover narrow URI matching, ordinary pass-through, full/ranged reads, bounded allocations, EOF and actual OkHttp cancellation.

`tools/testing/generate_cctv_h5e_fixture.py` creates a four-second H264/AAC test pattern, applies an independent forward H5E transform, and checks that the production reverse transform recovers byte-identical TS and video/audio frame hashes. These synthetic assets are generated during CI; no captured broadcast media is committed.

With `cctv_h5e=true`, `build-validation.yml` runs the isolated `cctv-verification` Android module. That module compiles the exact two application adapter source files with the fixed Media3 dependency. Tests perform HTTP byte/range verification and actual ExoPlayer surface rendering, advance playback, then switch to ordinary HLS. TV ARM64/ARMv7 and mobile ARM64 packages use the normal application build.

On 2026-11-08, captured CCTV-1 and CCTV-13 segments decoded with zero FFmpeg errors after transformation, versus 39 and 93 error lines beforehand. Each produced 100 H264 720p frames and 188 AAC frames. This is media-level evidence, not physical-device playback. Channel 403/access restrictions and changing provider schemes remain outside this compatibility fix.
