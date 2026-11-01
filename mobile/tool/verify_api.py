"""Exercise the Flutter API client against a disposable local server. No external mail."""
from pathlib import Path
import argparse
import base64
import io
import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from uuid import uuid4


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--flutter', default='flutter')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[2]
    sys.path.insert(0, str(project / 'server'))
    from seed_demo import seed
    from PIL import Image
    with tempfile.TemporaryDirectory(prefix='rental-flutter-http-') as directory:
        data = Path(directory)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        key = secrets.token_urlsafe(32)
        env = os.environ.copy()
        env.update(RENTAL_DEVICE_TOKEN=key, PUBLIC_BASE_URL=base, RENTAL_MAIL_MODE='file',
                   CI='true', FLUTTER_SUPPRESS_ANALYTICS='true')
        with (data / 'server.log').open('w') as log:
            process = subprocess.Popen([sys.executable, str(project/'server/main.py'),
                '--port', str(port), '--data-dir', str(data)], env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(150):
                    if process.poll() is not None:
                        raise RuntimeError('Local test server did not start')
                    try:
                        with urllib.request.urlopen(base+'/health',timeout=.5) as response:
                            if response.status == 200: break
                    except (OSError,urllib.error.URLError): time.sleep(.2)
                else: raise TimeoutError('Local server startup timed out')
                email = 'flutter-'+secrets.token_hex(5)+'@example.com'
                fixture = seed(base, key, email)
                request = urllib.request.Request(base+'/api/device/pairings',method='POST',
                    data=json.dumps({'vin':'KMHTEST00TEST0001'}).encode(),
                    headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'})
                with urllib.request.urlopen(request,timeout=10) as response: pair=json.load(response)
                image=io.BytesIO(); Image.new('RGB',(40,30),(12,117,108)).save(image,'JPEG')
                fixture.update(baseUrl=base,dataDir=str(data),email=email,password=secrets.token_urlsafe(24),
                    rentalId=fixture['id'],pairingUrl=pair['pairingUrl'],imageBase64=base64.b64encode(image.getvalue()).decode(),
                    requestIds=[str(uuid4()) for _ in range(3)],replacementRequestId=str(uuid4()))
                path=data/'fixture.json';path.write_text(json.dumps(fixture));path.chmod(0o600)
                env['RENTAL_TEST_FIXTURE']=str(path)
                print('Synthetic vehicle data, local development mail, isolated HTTP server',flush=True)
                result=subprocess.run([args.flutter,'--no-version-check','test','--no-pub',
                    '--dart-define=RENTAL_LIVE_TEST=true','--reporter','expanded','test/live_api_test.dart'],
                    cwd=project/'mobile',env=env,timeout=180)
                return result.returncode
            finally:
                process.terminate()
                try: process.wait(timeout=8)
                except subprocess.TimeoutExpired: process.kill();process.wait(timeout=5)
                print('Local test server stopped; temporary credentials and data removed',flush=True)


if __name__ == '__main__':
    raise SystemExit(main())
