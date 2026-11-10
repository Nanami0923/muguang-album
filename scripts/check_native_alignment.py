"""Check real PT_LOAD segments and ZIP data offsets; no ELF rewriting.

16 KB Android devices run 64-bit ABIs. 32-bit ELF details are reported but do not
fail that compatibility requirement. Run on the final signed APK.
"""
import argparse
import json
from pathlib import Path
import struct
import zipfile

def inspect(apk):
    rows = []
    with zipfile.ZipFile(apk) as archive, open(apk, 'rb') as raw:
        for item in archive.infolist():
            if not item.filename.startswith('lib/') or not item.filename.endswith('.so'):
                continue
            data = archive.read(item)
            if data[:4] != b'\x7fELF':
                raise ValueError(f'{item.filename}: not an ELF file')
            bits = 64 if data[4] == 2 else 32
            endian = '<' if data[5] == 1 else '>'
            if bits == 64:
                phoff = struct.unpack_from(endian+'Q', data, 32)[0]
                size, count = struct.unpack_from(endian+'HH', data, 54)
                fmt = endian+'IIQQQQQQ'
            else:
                phoff = struct.unpack_from(endian+'I', data, 28)[0]
                size, count = struct.unpack_from(endian+'HH', data, 42)
                fmt = endian+'IIIIIIII'
            loads = []
            for i in range(count):
                fields = struct.unpack_from(fmt, data, phoff+i*size)
                if fields[0] != 1:
                    continue
                offset, address, alignment = (fields[2],fields[3],fields[7]) if bits == 64 else (fields[1],fields[2],fields[7])
                loads.append(dict(alignment=alignment, offset=offset, address=address,
                                  aligned=alignment >= 16384 and (address-offset) % 16384 == 0))
            raw.seek(item.header_offset+26)
            name_len, extra_len = struct.unpack('<HH',raw.read(4))
            zip_offset = item.header_offset+30+name_len+extra_len
            elf_ok = bool(loads) and all(x['aligned'] for x in loads)
            zip_ok = item.compress_type != zipfile.ZIP_STORED or zip_offset % 16384 == 0
            rows.append(dict(file=item.filename,bits=bits,loads=loads,elf_aligned=elf_ok,
                             zip_data_offset=zip_offset,zip_aligned=zip_ok,compressed=item.compress_type != zipfile.ZIP_STORED,
                             passed=(elf_ok and zip_ok) if bits == 64 else True))
    return dict(apk=str(apk),passed=bool(rows) and all(row['passed'] for row in rows),libraries=rows)

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('apk',type=Path);parser.add_argument('--json',type=Path)
    args=parser.parse_args();result=inspect(args.apk)
    if args.json:
        args.json.parent.mkdir(parents=True,exist_ok=True)
        args.json.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    for row in result['libraries']:
        state=('PASS' if row['passed'] else 'FAIL') if row['bits'] == 64 else 'INFO32'
        print(f"{state} {row['file']} ELF={row['elf_aligned']} ZIP={row['zip_aligned']} align={[x['alignment'] for x in row['loads']]}")
    raise SystemExit(0 if result['passed'] else 1)
