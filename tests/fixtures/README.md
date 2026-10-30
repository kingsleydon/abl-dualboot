Small synthetic ELF-like inputs shared by Python, shell and Android unit tests.
The marker fixture contains an LZMA-alone stream with `OpenABL\01.2\0`.
The oversized marker fixture places its marker beyond the 4 MiB output limit.
The test catalog identifies only `openabl-hash.bin`; no real firmware is included.

`openabl-stores.bin` contains synthetic settings-store frames indexed by `openabl-stores.txt`.
The manifest also specifies edits, status output, invalid stores and a full store. Python and Kotlin
consume the same byte-for-byte expectations. The 14 golden edits, invalid 4096-byte frames and full-store
failure were verified against the actual `Core/SettingsStore.c` at OpenABL `71c0040` (unchanged at
`7803c64`), compiled on the host with libc memory helpers and zlib CRC-32.

Frames cover same-size edits, missing keys, resized keys, every source, SD fallback, default handling,
stale and oversized model copies, reserved header bytes, opaque records, nonzero bytes after the list
terminator, a record ending exactly at byte 4096, and malformed headers/records. Nonzero trailing bytes
are valid under upstream `LayoutValid`; same-size edits preserve them and append edits zero them.
