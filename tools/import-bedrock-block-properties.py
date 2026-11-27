#!/usr/bin/env python3
"""Package a validated native export as Navro's typed, protocol-scoped table."""
import argparse
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import struct


def convert(source, output):
    raw = source.read_bytes()
    data = json.loads(raw)
    assert data['schema'] == 'navro-bedrock-physical-properties/v2'
    assert data['source']['protocol'] == 2225
    assert data['source']['version'] == '1.26.63.1'
    rows = data['states']
    assert len(rows) == data['stateCount'] == 23960
    stream = io.BytesIO()
    def number(fmt, value): stream.write(struct.pack('>' + fmt, value))
    def text(value):
        encoded = value.encode('utf-8')
        assert len(encoded) <= 65535
        number('H', len(encoded)); stream.write(encoded)
    number('I', 0x4e504832)
    number('i', data['source']['protocol'])
    text(data['source']['version'])
    text(data['source']['binarySha256'])
    number('i', len(rows))
    seen = set()
    for row in rows:
        key = json.dumps([row['name'], row['states'], row['stateTypes']], sort_keys=True)
        assert key not in seen
        seen.add(key)
        text(row['name'])
        assert set(row['states']) == set(row['stateTypes'])
        number('H', len(row['states']))
        for name, value in sorted(row['states'].items()):
            tag = row['stateTypes'][name]
            text(name); number('B', tag)
            if tag == 8: text(value)
            else: number({1:'b', 2:'h', 3:'i', 4:'q', 5:'f', 6:'d'}[tag], value)
        assert 0 <= row['propertyMask'] < 2**64
        assert math.isfinite(row['friction']) and 0 <= row['friction'] <= 1
        material = row['material']
        assert material['blocksMotion'] == bool(material['flags'] & 4)
        assert material['solid'] == bool(material['flags'] & 16)
        assert material['liquid'] == bool(material['flags'] & 2)
        number('Q', row['propertyMask']); number('f', row['friction'])
        number('B', material['typeId']); number('B', material['flags'])
        assert math.isfinite(row['hardness']) and row['hardness'] >= -1
        number('f', row['hardness'])
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('wb') as target:
        with gzip.GzipFile(filename='', fileobj=target, mode='wb', mtime=0) as compressed:
            compressed.write(stream.getvalue())
    manifest = dict(data['source'])
    manifest.update(schema='navro-native-physical-properties/NPH2', stateCount=len(rows),
                    blockTypeCount=data['blockTypeCount'], exportSha256=hashlib.sha256(raw).hexdigest(),
                    resourceSha256=hashlib.sha256(output.read_bytes()).hexdigest())
    output.with_suffix('').with_suffix('.source.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    convert(args.source, args.output)
