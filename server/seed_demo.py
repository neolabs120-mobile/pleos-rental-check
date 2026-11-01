"""Explicit synthetic fixture for testing the server/mobile flow without Pleos.

It never runs from the Android SDK data source and never simulates a payment.
"""
import argparse
import json
import os
import time
import urllib.request
from uuid import uuid4


def seed(base_url: str, token: str, email: str):
    identifier = str(uuid4())
    now = int(time.time() * 1000)
    def snapshot(timestamp, fuel, distance):
        def reading(key, value, label, unit=None):
            return {"key": key, "label": label, "status": "AVAILABLE", "value": value,
                "displayValue": value if key != "vehicle.vin" else "VIN ···ST0001", "unit": unit,
                "detail": "합성 시연 데이터", "observedAtMillis": timestamp}
        return {"startedAtMillis": timestamp, "completedAtMillis": timestamp + 1,
            "source": "DEMO · 합성 차량 데이터", "readings": [
                reading("vehicle.vin", "KMHTEST00TEST0001", "VIN"),
                reading("fuel.percent", str(fuel), "연료 잔량", "%"),
                reading("odometer.km", str(distance), "누적거리", "km")]}
    payload = {"id": identifier, "revision": 2, "pickup": snapshot(now - 86_400_000, 80, 10000),
        "returned": snapshot(now, 58, 10186.5), "pickupWindowManuallyChecked": False, "returnWindowManuallyChecked": False}
    def call(path, method, body):
        request = urllib.request.Request(base_url.rstrip("/") + path, method=method,
            data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    invitation = call("/api/device/bookings", "POST", {"email": email, "vin": "KMHTEST00TEST0001",
        "vehicleName": "합성 시연 차량", "isDemo": True})
    payload["bookingId"] = invitation["bookingId"]
    call("/api/rentals/" + identifier, "PUT", payload)
    link = call("/api/rentals/" + identifier + "/photo-links", "POST", {"phase": "return"})
    return {"id": identifier, **link, **invitation, "accountUrl": base_url.rstrip("/") + "/account"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8787")
    parser.add_argument("--email", required=True, help="이 대여 건을 연결할 시험 계정 이메일")
    args = parser.parse_args()
    token = os.environ.get("RENTAL_DEVICE_TOKEN", "")
    if len(token) < 32:
        raise SystemExit("서버와 같은 RENTAL_DEVICE_TOKEN을 설정하세요.")
    result = seed(args.base_url, token, args.email)
    print("합성 시연 데이터 · 실제 차량 수신값이 아닙니다.")
    print("이용 번호:", result["id"])
    print("계정 가입·로그인:", result["accountUrl"])
    print("예약번호:", result["bookingNumber"])
    print("예약 연결 코드:", result["claimCode"])
    print("15분 사진 전송 링크:", result["uploadUrl"])
