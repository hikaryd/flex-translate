#!/usr/bin/env python3
"""Verify a prebuilt libllama.so (+ libggml*.so) exports every llama_* symbol the JNI shim calls.

Usage: verify_llama_symbols.py <prebuilt_libs_dir> <milmmt_jni.cpp>

Parses ELF dynamic symbol tables in pure Python (no nm/readelf needed — macOS' Xcode tools may be
license-gated), collects defined symbols across libllama.so and libggml*.so, then checks them
against the set of `llama_<name>(` calls found in the JNI shim source. Exits non-zero on any miss.
"""
import glob
import os
import re
import struct
import sys


def defined_dynsyms(path: str) -> set[str]:
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF" or data[4] != 2:  # ELF64 only
        return set()
    end = "<" if data[5] == 1 else ">"
    e_shoff = struct.unpack_from(end + "Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from(end + "H", data, 0x3A)[0]
    e_shnum = struct.unpack_from(end + "H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from(end + "H", data, 0x3E)[0]
    secs = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, _t, _fl, _ad, offset, size, _lk, _inf, _al, entsize = struct.unpack_from(
            end + "IIQQQQIIQQ", data, off
        )
        secs.append(dict(name=name, offset=offset, size=size, entsize=entsize))
    shstr = secs[e_shstrndx]

    def secname(s):
        o = shstr["offset"] + s["name"]
        return data[o:data.index(b"\x00", o)].decode()

    dynsym = dynstr = None
    for s in secs:
        n = secname(s)
        if n == ".dynsym":
            dynsym = s
        elif n == ".dynstr":
            dynstr = s
    syms: set[str] = set()
    if dynsym and dynstr and dynsym["entsize"]:
        for i in range(dynsym["size"] // dynsym["entsize"]):
            o = dynsym["offset"] + i * dynsym["entsize"]
            st_name, _info, _other, st_shndx, _val, _sz = struct.unpack_from(end + "IBBHQQ", data, o)
            if st_shndx == 0:  # undefined
                continue
            no = dynstr["offset"] + st_name
            nm = data[no:data.index(b"\x00", no)].decode(errors="replace")
            if nm:
                syms.add(nm)
    return syms


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    libs_dir, shim = sys.argv[1], sys.argv[2]
    exported: set[str] = set()
    for so in [os.path.join(libs_dir, "libllama.so")] + glob.glob(os.path.join(libs_dir, "libggml*.so")):
        if os.path.exists(so):
            exported |= defined_dynsyms(so)
    with open(shim) as f:
        required = sorted(set(re.findall(r"\b(llama_[a-z0-9_]+)\s*\(", f.read())))
    missing = [s for s in required if s not in exported]
    for s in required:
        print(("OK   " if s in exported else "MISS ") + s)
    if missing:
        print(f"\nFAIL: {len(missing)} symbol(s) missing from prebuilt: {missing}", file=sys.stderr)
        return 1
    print(f"\nOK: all {len(required)} JNI symbols present in prebuilt llama.cpp libs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
