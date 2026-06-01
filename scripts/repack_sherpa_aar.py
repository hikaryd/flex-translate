import sys, os, shutil, zipfile, re

OLD = b'libonnxruntime.so\x00'
NEW = b'libsherpaort13.so\x00'   # same length (18 bytes incl NUL): 17 chars + NUL
assert len(OLD) == len(NEW), (len(OLD), len(NEW))

SRC_AAR = '/Users/tronin.egor/Documents/dev/flex-translate/apps/android/app/libs/sherpa-onnx-1.13.2.aar'
OUT_AAR = '/Users/tronin.egor/Documents/dev/flex-translate/apps/android/app/libs/sherpa-onnx-1.13.2-noort.aar'

# Rename the runtime file inside jni/<abi>/ and patch every .so's DT_SONAME/DT_NEEDED string.
def process(name, data):
    # name like jni/arm64-v8a/libonnxruntime.so
    patched = data.replace(OLD, NEW)
    out_name = name
    if name.endswith('/libonnxruntime.so'):
        out_name = name[:-len('libonnxruntime.so')] + 'libsherpaort13.so'
    return out_name, patched

zin = zipfile.ZipFile(SRC_AAR, 'r')
if os.path.exists(OUT_AAR):
    os.remove(OUT_AAR)
zout = zipfile.ZipFile(OUT_AAR, 'w', zipfile.ZIP_DEFLATED)
renamed = 0
patched_count = 0
for item in zin.infolist():
    data = zin.read(item.filename)
    out_name = item.filename
    if item.filename.endswith('.so') and ('libonnxruntime.so' in item.filename or 'libsherpa' in item.filename):
        before = data.count(OLD)
        out_name, data = process(item.filename, data)
        if out_name != item.filename:
            renamed += 1
        if before:
            patched_count += before
    # write (preserve path)
    zi = zipfile.ZipInfo(out_name, date_time=item.date_time)
    zi.external_attr = item.external_attr
    zi.compress_type = zipfile.ZIP_DEFLATED
    zout.writestr(zi, data)
zin.close()
zout.close()
print(f'renamed runtime files: {renamed}, DT string occurrences patched: {patched_count}')

# Verify
zc = zipfile.ZipFile(OUT_AAR)
sos = [n for n in zc.namelist() if n.endswith('.so')]
print('SO files in repacked AAR:')
for n in sorted(sos):
    if 'arm64' in n: print('  ', n)
# confirm sherpa jni now NEEDs libsherpaort13.so and no libonnxruntime.so remains
d = zc.read('jni/arm64-v8a/libsherpa-onnx-jni.so')
print('jni NEEDs libsherpaort13.so:', NEW in d, '| stale libonnxruntime.so:', OLD in d)
rt = [n for n in sos if n.endswith('libsherpaort13.so') and 'arm64' in n]
print('renamed runtime present:', rt)
