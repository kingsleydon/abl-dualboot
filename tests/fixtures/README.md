Small synthetic ELF-like inputs shared by Python, shell and Android unit tests.
The marker fixture contains an LZMA-alone stream with `OpenABL\01.2\0`; the misc fixture adds `OABLVARS`.
The misc-only fixture lacks the OpenABL marker. Oversized fixtures place their marker or misc magic beyond
the 4 MiB output limit. The test catalog identifies only `openabl-hash.bin`; no real firmware is included.

`openabl-stores.bin` contains synthetic settings-store frames indexed by `openabl-stores.txt`.
Python and Kotlin consume the same byte-for-byte expectations. The 18 golden edits, invalid 4096-byte
frames and full-store failure were verified against `Core/SettingsStore.c` at OpenABL `2494196`, compiled
on the host with libc memory helpers and zlib CRC-32. Edits follow `SettingsSave`'s default deletion and
`StoreHolds`' skip of missing defaults.

Frames cover the 16-byte header, same-size edits, missing and resized keys, every source, SD fallback,
stored and missing defaults, opaque records, a long DeviceModel, nonzero bytes after the terminator,
records ending exactly at byte 4096, and malformed headers/records. Nonzero trailing bytes are valid;
same-size edits preserve them, while append and deletion edits zero them. The android-internal frame's
records end before byte 128, reproducing the v2.3.0 parser bug that erased DeviceModel and other settings.
