#!/usr/bin/env python3
"""Build a signed sideload APK. Signing material stays outside version control."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--create-local-key', action='store_true', help='Create a local key once; existing keys are never replaced')
args = parser.parse_args()
env = os.environ.copy()
java = Path(env.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
if not (java / 'bin/java').is_file():
    raise SystemExit('Set JAVA_HOME to a compatible JDK (this project uses JDK 25).')
env['JAVA_HOME'] = str(java)
local = root / '.local-signing'
credentials = local / 'credentials.json'
key = local / 'iptv-release.p12'
if not env.get('IPTV_KEYSTORE'):
    if not credentials.exists():
        if not args.create_local_key:
            raise SystemExit('Supply IPTV_KEYSTORE and its credentials, or pass --create-local-key.')
        if key.exists():
            raise SystemExit('A key already exists without credentials; refusing to replace it.')
        local.mkdir(mode=0o700, exist_ok=True)
        password = secrets.token_urlsafe(36)
        values = dict(IPTV_KEYSTORE=str(key), IPTV_STORE_PASSWORD=password, IPTV_KEY_ALIAS='iptv', IPTV_KEY_PASSWORD=password)
        fd = os.open(credentials, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as output:
            json.dump(values, output)
        env.update(values)
        subprocess.run([str(java / 'bin/keytool'), '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
                        '-storepass:env', 'IPTV_STORE_PASSWORD', '-keypass:env', 'IPTV_KEY_PASSWORD',
                        '-alias', 'iptv', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                        '-dname', 'CN=IPTV Local Release', '-noprompt'], check=True, env=env)
        key.chmod(0o600)
    else:
        env.update(json.loads(credentials.read_text()))
        env['IPTV_KEYSTORE'] = str(key)
for name in ('IPTV_KEYSTORE', 'IPTV_STORE_PASSWORD', 'IPTV_KEY_ALIAS', 'IPTV_KEY_PASSWORD'):
    if not env.get(name):
        raise SystemExit(f'Missing {name}')
subprocess.run([str(root / 'gradlew'), ':app:assembleRelease', '--console=plain'], cwd=root, env=env, check=True)
metadata = json.loads((root / 'app/build/outputs/apk/release/output-metadata.json').read_text())
version = metadata['elements'][0]['versionName']
destination = root / 'dist' / f'IPTV-{version}.apk'
destination.parent.mkdir(exist_ok=True)
shutil.copy2(root / 'app/build/outputs/apk/release/app-release.apk', destination)
print(f'Signed APK: {destination}')
print('Keep .local-signing backed up privately if you used the local key; updates require the same key.')
