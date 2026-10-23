#!/usr/bin/env python3
"""Generate reproducible large M3U/XMLTV fixtures; no live providers are built in."""
import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path
import gzip
from xml.sax.saxutils import escape

parser = argparse.ArgumentParser()
parser.add_argument('--channels', type=int, default=1000)
parser.add_argument('--days', type=int, default=8)
parser.add_argument('--date', default=datetime.now(timezone.utc).strftime('%Y-%m-%d'))
parser.add_argument('--output', type=Path, default=Path('build/fixtures'))
parser.add_argument('--stream-url', help='Optional user-owned test stream; otherwise URLs are non-playing example.org placeholders')
args = parser.parse_args()
if not 1 <= args.channels <= 50000 or not 1 <= args.days <= 14:
    parser.error('Use 1..50000 channels and 1..14 days')
args.output.mkdir(parents=True, exist_ok=True)
base = datetime.strptime(args.date, '%Y-%m-%d').replace(tzinfo=timezone.utc) - timedelta(days=1)
with (args.output / 'channels.m3u').open('w') as playlist:
    playlist.write('#EXTM3U\n')
    for channel in range(args.channels):
        playlist.write(f'#EXTINF:-1 tvg-id="ch{channel}" group-title="Группа {channel % 12}",Канал {channel + 1}\n')
        playlist.write((args.stream_url or f'https://example.org/stream/{channel}.m3u8') + '\n')
with gzip.open(args.output / 'guide.xml.gz', 'wt', encoding='utf-8') as guide:
    guide.write('<?xml version="1.0" encoding="UTF-8"?><tv>\n')
    for channel in range(args.channels):
        guide.write(f'<channel id="ch{channel}"><display-name>Канал {channel + 1}</display-name></channel>\n')
    for channel in range(args.channels):
        for slot in range(args.days * 48):
            start = base + timedelta(minutes=30 * slot)
            end = start + timedelta(minutes=30)
            guide.write(f'<programme channel="ch{channel}" start="{start:%Y%m%d%H%M%S %z}" stop="{end:%Y%m%d%H%M%S %z}"><title>Передача {slot + 1}</title><desc>Тестовая программа канала {channel + 1}</desc></programme>\n')
    guide.write('</tv>')
print(f'Created {args.channels} channels and {args.channels * args.days * 48} programmes in {args.output}')
