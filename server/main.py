import argparse
import os
from pathlib import Path

import uvicorn
from app import create_app
from flutter_web import mount_flutter_web
from mailer import configured_mailer

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--data-dir", type=Path, default=Path(__file__).parent / "data")
    parser.add_argument("--flutter-web-dir", type=Path, help="Serve a Flutter web build at /mobile/")
    parser.add_argument("--allow-emulator-test-vin", action="store_true", help="Accept labelled emulator VIN records on approved test bookings")
    args = parser.parse_args()
    app = create_app(args.data_dir, os.environ.get("RENTAL_DEVICE_TOKEN", ""),
        os.environ.get("PUBLIC_BASE_URL", f"http://127.0.0.1:{args.port}"), mailer=configured_mailer(args.data_dir),
        allow_emulator_test_vin=args.allow_emulator_test_vin)
    # Serve Flutter and its API from one origin so local browser sessions need no cross-origin access.
    if args.flutter_web_dir:
        mount_flutter_web(app, args.flutter_web_dir)
    uvicorn.run(app, host=args.host, port=args.port, access_log=False)
