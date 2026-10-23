#!/usr/bin/env python3
"""Measure an already-open player on a connected TV without logging stream URLs."""
import argparse
import json
from pathlib import Path
import queue
import re
import statistics
import subprocess
import threading
import time
from datetime import datetime, timezone

parser = argparse.ArgumentParser()
parser.add_argument('--adb', default='adb')
parser.add_argument('--adb-server-port', type=int, default=5037)
parser.add_argument('--serial')
parser.add_argument('--switches', type=int, default=100)
parser.add_argument('--soak-minutes', type=int, default=0)
parser.add_argument('--output', type=Path, default=Path('build/tv-metrics.json'))
args = parser.parse_args()
adb = [args.adb, '-P', str(args.adb_server_port)] + (['-s', args.serial] if args.serial else [])
def command(*params):
    return subprocess.check_output(adb + list(params), text=True, timeout=30)
def memory():
    output = command('shell', 'dumpsys', 'meminfo', 'home.iptv')
    total = re.search(r'TOTAL PSS:\s*(\d+)', output) or re.search(r'^\s*TOTAL\s+(\d+)', output, re.M)
    return int(total.group(1)) if total else None
def player_status():
    dump = command('shell', 'dumpsys', 'media_session')
    block = dump.split('package=home.iptv', 1)[-1] if 'package=home.iptv' in dump else ''
    state = re.search(r'state=PlaybackState \{state=(\d+)', block)
    title = re.search(r'metadata:.*description=(.*?), null, null', block)
    return {'pid': command('shell', 'pidof', 'home.iptv').strip(),
            'channel': title.group(1) if title else None,
            'media_session_state': int(state.group(1)) if state else None}
def checkpoint():
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
command('get-state')
frames = queue.Queue()
log = subprocess.Popen(adb + ['logcat', '-T', '1', '-s', 'IPTV_METRIC:I', '*:S'], stdout=subprocess.PIPE, text=True)
def read_frames():
    for line in log.stdout:
        match = re.search(r'first_frame_ms=(\d+)', line)
        if match:
            frames.put((time.monotonic(), int(match.group(1))))
threading.Thread(target=read_frames, daemon=True).start()
result = {'device': command('shell', 'getprop', 'ro.product.model').strip(),
          'started_utc': datetime.now(timezone.utc).isoformat(), 'initial_player': player_status(),
          'requested_switches': args.switches, 'completed': False,
          'requested_soak_minutes': args.soak_minutes,
          'samples': [], 'memory_pss_kb': [], 'soak_status': []}
try:
    result['memory_pss_kb'].append({'switch': 0, 'pss': memory()})
    time.sleep(1)
    for index in range(args.switches):
        while not frames.empty():
            frames.get_nowait()
        sent = time.monotonic()
        command('shell', 'input', 'keyevent', 'KEYCODE_CHANNEL_UP')
        try:
            received, preparation = frames.get(timeout=15)
            result['samples'].append({'switch': index + 1, 'prepare_to_frame_ms': preparation, 'key_to_log_ms': round((received - sent) * 1000)})
        except queue.Empty:
            result['samples'].append({'switch': index + 1, 'timeout': True})
        result['samples'][-1].update(player_status())
        if (index + 1) % 10 == 0:
            result['memory_pss_kb'].append({'switch': index + 1, 'pss': memory()})
            print(f'Switches: {index + 1}/{args.switches}; no frame within 15 s: '
                  f'{sum(bool(row.get("timeout")) for row in result["samples"])}', flush=True)
            checkpoint()
        time.sleep(1)
    for minute in range(args.soak_minutes):
        time.sleep(60)
        result['memory_pss_kb'].append({'soak_minute': minute + 1, 'pss': memory()})
        result['soak_status'].append({'minute': minute + 1, **player_status()})
        checkpoint()
        print(f'Soak: {minute + 1}/{args.soak_minutes} min; PSS: '
              f'{result["memory_pss_kb"][-1]["pss"]} KiB; '
              f'player state: {result["soak_status"][-1]["media_session_state"]}', flush=True)
    values = sorted(row['key_to_log_ms'] for row in result['samples'] if 'key_to_log_ms' in row)
    if values:
        result['median_ms'] = statistics.median(values)
        result['p95_ms'] = values[max(0, (95 * len(values) + 99) // 100 - 1)]
    result['cpu_lines'] = [line for line in command('shell', 'dumpsys', 'cpuinfo').splitlines() if 'home.iptv' in line]
    result['completed'] = True
finally:
    log.terminate()
    result['finished_utc'] = datetime.now(timezone.utc).isoformat()
    result['completed_switches'] = len(result['samples'])
    checkpoint()
print(f'Results: {args.output}. key_to_log includes ADB/log transport overhead; compare identical setups.')
