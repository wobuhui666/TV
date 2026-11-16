# MPV seeking and video surface lifecycle

Replay seeks to the beginning (or the configured opening position); refresh
keeps the position captured before playback stops. HTTP resume seeks wait for
the new file to load, and the adapter preserves their target until the native
clock confirms the seek. Queued clock and video events cannot overwrite the
resume target with the old file's position. A new seek during loading replaces
the pending target.

When a local subtitle picker destroys the video Surface, MPV temporarily
disables the selected video track before releasing that window. On return it
restores the output and selected track so the decoder renders into the new
Surface, including when paused. Cancelling retains the picker's pause; choosing
a local subtitle retains the existing behavior of continuing playback.

## HLS seek precision

The Android MPV adapter uses a minimum five-second `hr-seek-demuxer-offset`
for HLS. This asks the demuxer to read from an earlier segment; the requested
playback position is unchanged. MPV decodes forward and displays the frame
at the original seek target.

HLS is identified by an explicit HLS MIME type, a URI path ending in `.m3u8`,
or MPV's detected `file-format` (`hls`/`applehttp`) after the file loads.
An untyped `/proxy` URL is not assumed to be HLS. A larger user-configured
offset is preserved. Switching files restores the previous offset, while
an intervening runtime user change takes precedence.

`Player.seekTo` and deferred resume seeks issue MPV's `seek ... absolute+exact`.
That explicit command overrides `hr-seek=no` for the requested seek only;
the user's `hr-seek` option and native key bindings are not changed.

This addresses an observed MPV/FFmpeg HLS demux seek overshoot. With the pinned
native bundle on Samsung Android 13, a paused seek to 15 seconds settled at
16.892411 seconds through both `set time-pos` and `absolute+exact`. Disabling
`hr-seek-framedrop` made no difference. Five seconds of demux preroll placed
the paused frame at 15.016789 seconds, with native playback-restart events
present in every experiment. The frame-dropping option and timestamp origin
remain unchanged. The measured seek took about 2.5 seconds with preroll,
compared with 1.7 seconds at the incorrect position.

The pure Java regression tests cover media detection, restoring the original
offset, repeated application and preserving user changes. Device validation
uses the existing first-frame, playback clock, paused seek, native restart,
PixelCopy, audio-effect and playback-control checks without widening their
position tolerances.

References:

- [MPV seek command](https://mpv.io/manual/master/#command-interface-seek)
- [MPV precise-seek options](https://mpv.io/manual/master/#options-hr-seek)
- [Pinned native bundle](../third_party/mpv/README.md)
