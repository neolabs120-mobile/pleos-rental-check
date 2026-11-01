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
    parser.add_argument('--return-name')
    parser.add_argument('--return-address')
    parser.add_argument('--return-latitude', type=float)
    parser.add_argument('--return-longitude', type=float)
    parser.add_argument('--return-instructions', default='')
    parser.add_argument('--return-test', action='store_true', help='Label this destination as an emulator test location; requires --demo')
    args = parser.parse_args()
    token = os.environ.get('RENTAL_DEVICE_TOKEN', '')
    if len(token) < 32:
        raise SystemExit('서버와 같은 RENTAL_DEVICE_TOKEN을 설정하세요.')
    fields = [args.return_name, args.return_address, args.return_latitude, args.return_longitude]
    if any(value is not None for value in fields) and not all(value is not None for value in fields):
        parser.error('반납 장소명·주소·위도·경도를 모두 지정하세요.')
    if args.return_test and (not args.demo or args.return_name is None):
        parser.error('--return-test는 --demo와 반납 장소가 필요합니다.')
    destination = None if args.return_name is None else dict(name=args.return_name, address=args.return_address,
        latitude=args.return_latitude, longitude=args.return_longitude, instructions=args.return_instructions, isTest=args.return_test)
    request = urllib.request.Request(args.base_url.rstrip('/')+'/api/device/bookings', method='POST',
        headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},
        data=json.dumps({'email':args.email,'vin':args.vin.upper(),'vehicleName':args.vehicle_name,'isDemo':args.demo,'returnLocation':destination}).encode())
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.load(response)
    print('발급 대상 이메일:', args.email)
    print('예약번호:', result['bookingNumber'])
    print('예약 연결 코드:', result['claimCode'])
