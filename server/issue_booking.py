"""Operator-only CLI; creates an invitation, never a user account or a payment."""
import argparse
import json
import os
import urllib.request

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:8787')
    parser.add_argument('--email', required=True)
    parser.add_argument('--vin', required=True)
    parser.add_argument('--vehicle-name', default='대여 차량')
    parser.add_argument('--demo', action='store_true', help='Mark this invitation as a test booking; owner approval is still required for emulator VIN records')
    args = parser.parse_args()
    token = os.environ.get('RENTAL_DEVICE_TOKEN', '')
    if len(token) < 32:
        raise SystemExit('서버와 같은 RENTAL_DEVICE_TOKEN을 설정하세요.')
    request = urllib.request.Request(args.base_url.rstrip('/')+'/api/device/bookings', method='POST',
        headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},
        data=json.dumps({'email':args.email,'vin':args.vin.upper(),'vehicleName':args.vehicle_name,'isDemo':args.demo}).encode())
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.load(response)
    print('발급 대상 이메일:', args.email)
    print('예약번호:', result['bookingNumber'])
    print('예약 연결 코드:', result['claimCode'])
