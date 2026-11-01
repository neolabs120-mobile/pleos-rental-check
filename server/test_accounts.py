import copy
import json
import secrets
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

from fastapi.testclient import TestClient
from app import create_app


class AccountTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.clock = [1_800_000_000.0]
        self.mail = []
        self.key = secrets.token_urlsafe(32)
        self.app = create_app(self.path, self.key, 'https://rental.example.test', clock=lambda: self.clock[0], mailer=lambda *args: self.mail.append(args))
        self.client = TestClient(self.app)
        self.addCleanup(self.client.close)
        self.device = {'Authorization': 'Bearer '+self.key}
        self.password = 'a-long-test-password-2026'

    def signup(self, email='renter@example.com'):
        return self.client.post('/api/auth/signup', json={'nickname': '이용자', 'email': email, 'password': self.password, 'termsAccepted': True, 'privacyAccepted': True})

    def account(self, email='renter@example.com'):
        result = self.signup(email)
        self.assertEqual(200, result.status_code, result.text)
        response = self.client.post('/api/auth/verify', json={'challengeId': result.json()['challengeId'], 'code': self.mail[-1][2]})
        self.assertEqual(200, response.status_code, response.text)
        response = self.login(email)
        self.assertEqual(200, response.status_code, response.text)
        return {'Authorization': 'Bearer '+response.json()['token']}

    def login(self, email='renter@example.com', password=None):
        return self.client.post('/api/auth/login', json={'email': email, 'password': password or self.password})

    def invitation(self, email='renter@example.com', vin='KMHTEST00TEST0001', demo=False):
        response = self.client.post('/api/device/bookings', json={'email': email, 'vin': vin, 'isDemo': demo}, headers=self.device)
        self.assertEqual(200, response.status_code, response.text)
        return response.json()

    def claim(self, invite, auth):
        return self.client.post('/api/account/bookings/claim', json={'bookingNumber': invite['bookingNumber'], 'claimCode': invite['claimCode']}, headers=auth)

    def pair(self, vin='KMHTEST00TEST0001', replace=None):
        body = {'vin': vin}
        if replace: body['replaceId'] = replace
        response = self.client.post('/api/device/pairings', json=body, headers=self.device)
        self.assertEqual(200, response.status_code, response.text)
        pair = response.json()
        return pair, parse_qs(urlsplit(pair['pairingUrl']).fragment)['pair'][0]

    def approve(self, invite, token, auth):
        return self.client.post('/api/account/pairings/approve', json={'bookingId': invite['bookingId'], 'token': token}, headers=auth)

    def test_signup_requires_consent_and_verified_email(self):
        self.assertEqual(422, self.client.post('/api/auth/signup', json={'nickname':'x','email':'x@example.com','password':self.password,'termsAccepted':False,'privacyAccepted':True}).status_code)
        signup = self.signup()
        self.assertNotIn('code', signup.json())
        self.assertEqual(401, self.login().status_code)
        response = self.client.post('/api/auth/verify', json={'challengeId':signup.json()['challengeId'],'code':self.mail[-1][2]})
        self.assertEqual(200, response.status_code)
        self.assertEqual(200, self.login('RENTER@EXAMPLE.COM').status_code)
        self.assertEqual(409, self.signup().status_code)

    def test_codes_have_attempt_limit_expiry_and_single_use(self):
        signup = self.signup(); challenge = signup.json()['challengeId']; code = self.mail[-1][2]
        wrong = '000000' if code != '000000' else '111111'
        for _ in range(5):
            self.assertEqual(422, self.client.post('/api/auth/verify',json={'challengeId':challenge,'code':wrong}).status_code)
        self.assertEqual(422, self.client.post('/api/auth/verify',json={'challengeId':challenge,'code':code}).status_code)
        self.clock[0] += 61
        newer = self.client.post('/api/auth/resend',json={'challengeId':challenge})
        self.assertEqual(200, newer.status_code)
        self.assertEqual(422, self.client.post('/api/auth/verify',json={'challengeId':challenge,'code':code}).status_code)
        fresh = {'challengeId':newer.json()['challengeId'],'code':self.mail[-1][2]}
        self.assertEqual(200, self.client.post('/api/auth/verify',json=fresh).status_code)
        self.assertEqual(422, self.client.post('/api/auth/verify',json=fresh).status_code)
        other = self.signup('other@example.com').json()
        self.clock[0] += 601
        self.assertEqual(422, self.client.post('/api/auth/verify',json={'challengeId':other['challengeId'],'code':self.mail[-1][2]}).status_code)

    def test_no_mail_configuration_never_reports_sent(self):
        app = create_app(self.path/'unconfigured', self.key, 'https://rental.example.test')
        with TestClient(app) as client:
            response=client.post('/api/auth/signup',json={'nickname':'x','email':'x@example.com','password':self.password,'termsAccepted':True,'privacyAccepted':True})
        self.assertEqual(503, response.status_code)

    def test_login_throttling_password_storage_and_no_secret_error_echo(self):
        self.account()
        for _ in range(9): self.login(password='wrong')
        self.assertEqual(429, self.login(password='wrong').status_code)
        with closing(sqlite3.connect(self.path/'rentals.sqlite3')) as connection:
            stored=connection.execute('SELECT password_hash FROM users').fetchone()[0]
            session=connection.execute('SELECT hash FROM account_sessions').fetchone()[0]
        self.assertTrue(stored.startswith('pbkdf2_sha256$600000$'))
        self.assertNotIn(self.password, stored)
        self.assertEqual(64,len(session))
        response=self.client.post('/api/auth/login',json={'email':'invalid','password':'private-secret'})
        self.assertNotIn('private-secret',response.text)

    def test_reset_revokes_sessions_and_pairs(self):
        auth=self.account(); invite=self.invitation(); self.claim(invite,auth)
        pair,token=self.pair(); self.assertEqual(200,self.approve(invite,token,auth).status_code)
        result=self.client.post('/api/auth/password/request',json={'email':'renter@example.com'})
        code=self.mail[-1][2]
        response=self.client.post('/api/auth/password/reset',json={'challengeId':result.json()['challengeId'],'code':code,'password':'new-long-password-2026'})
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual(401,self.client.get('/api/account/me',headers=auth).status_code)
        self.assertEqual(401,self.login().status_code)
        self.assertEqual(200,self.login(password='new-long-password-2026').status_code)
        self.assertEqual('disconnected',self.client.get('/api/device/pairings/'+pair['id'],headers=self.device).json()['status'])
        self.assertEqual(422,self.client.post('/api/auth/password/reset',json={'challengeId':result.json()['challengeId'],'code':code,'password':'another-long-password'}).status_code)

    def test_logout_and_session_expiry(self):
        auth=self.account(); self.assertEqual(200,self.client.post('/api/auth/logout',json={},headers=auth).status_code)
        self.assertEqual(401,self.client.get('/api/account/me',headers=auth).status_code)
        auth={'Authorization':'Bearer '+self.login().json()['token']}
        self.clock[0] += 8*3600+1
        self.assertEqual(401,self.client.get('/api/account/me',headers=auth).status_code)

    def test_claim_requires_recipient_email_and_code_and_separates_accounts(self):
        owner=self.account(); stranger=self.account('other@example.com'); invite=self.invitation()
        self.assertEqual(403,self.claim(invite,stranger).status_code)
        wrong={**invite,'claimCode':'x'*32}
        self.assertEqual(403,self.claim(wrong,owner).status_code)
        self.assertEqual(200,self.claim(invite,owner).status_code)
        self.assertEqual(200,self.claim(invite,owner).status_code)
        self.assertEqual(1,len(self.client.get('/api/account/bookings',headers=owner).json()['bookings']))
        self.assertEqual([],self.client.get('/api/account/bookings',headers=stranger).json()['bookings'])
        self.assertEqual(401,self.client.post('/api/device/bookings',json={'email':'x@example.com','vin':'KMHTEST00TEST0001'},headers=owner).status_code)

    def booking_owner(self, invite):
        # Inspect only this isolated fixture's ownership so a failed QR cannot silently claim an operator-issued reservation.
        with closing(sqlite3.connect(self.path/'rentals.sqlite3')) as connection:
            return connection.execute('SELECT user_id FROM bookings WHERE id=?', (invite['bookingId'],)).fetchone()[0]

    def test_verified_recipient_sees_reservations_without_claiming(self):
        # A reservation issued before signup appears after verified login, without revealing another email's booking or claiming it through GET.
        invite = self.invitation(email='RENTER@EXAMPLE.COM')
        self.assertEqual(401, self.client.get('/api/account/bookings').status_code)
        owner = self.account(); stranger = self.account('other@example.com')
        result = self.client.get('/api/account/bookings', headers=owner).json()['bookings']
        self.assertEqual([invite['bookingId']], [row['id'] for row in result])
        self.assertNotIn('claimCode', result[0]); self.assertNotIn('vin', result[0])
        self.assertEqual([], self.client.get('/api/account/bookings', headers=stranger).json()['bookings'])
        self.assertIsNone(self.booking_owner(invite))

    def test_vehicle_approval_binds_recipient_without_manual_codes(self):
        # The ordinary QR approval completes ownership atomically for the verified recipient; no manual claim endpoint is called.
        owner = self.account(); invite = self.invitation(); pair, token = self.pair()
        self.assertIsNone(self.booking_owner(invite))
        self.assertEqual('waiting', self.client.get('/api/device/pairings/'+pair['id'], headers=self.device).json()['status'])
        self.assertEqual(200, self.approve(invite, token, owner).status_code)
        identifier = self.client.get('/api/account/me', headers=owner).json()['id']
        self.assertEqual(identifier, self.booking_owner(invite))
        self.assertEqual('connected', self.client.get('/api/device/pairings/'+pair['id'], headers=self.device).json()['status'])
        self.assertEqual(200, self.approve(invite, token, owner).status_code)
        self.assertEqual(200, self.claim(invite, owner).status_code)

    def test_failed_approval_never_claims_an_email_reservation(self):
        # Wrong accounts, a different VIN and an expired QR must leave both ownership and the vehicle connection untouched.
        owner = self.account(); stranger = self.account('other@example.com'); invite = self.invitation()
        wrong, wrong_token = self.pair('KMHTEST00TEST0002')
        self.assertEqual(403, self.approve(invite, wrong_token, owner).status_code)
        pair, token = self.pair()
        self.assertEqual(403, self.approve(invite, token, stranger).status_code)
        self.assertIsNone(self.booking_owner(invite))
        self.clock[0] += 301
        self.assertEqual(410, self.approve(invite, token, owner).status_code)
        self.assertIsNone(self.booking_owner(invite))

    def test_reservations_issued_after_login_appear_on_refresh(self):
        # A driver already on the home screen can discover a newly issued reservation when refreshing or scanning another vehicle QR.
        owner = self.account()
        self.assertEqual([], self.client.get('/api/account/bookings', headers=owner).json()['bookings'])
        invite = self.invitation()
        rows = self.client.get('/api/account/bookings', headers=owner).json()['bookings']
        self.assertEqual([invite['bookingId']], [row['id'] for row in rows])
        self.assertIsNone(self.booking_owner(invite))

    def test_existing_foreign_binding_takes_precedence_over_recipient_email(self):
        # A stale email field must never transfer an already-owned reservation; construct that inconsistent state explicitly in the test database.
        owner = self.account(); other = self.account('other@example.com'); invite = self.invitation()
        other_id = self.client.get('/api/account/me', headers=other).json()['id']
        with sqlite3.connect(self.path/'rentals.sqlite3') as connection:
            connection.execute('UPDATE bookings SET user_id=? WHERE id=?', (other_id, invite['bookingId']))
        self.assertEqual([], self.client.get('/api/account/bookings', headers=owner).json()['bookings'])
        pair, token = self.pair()
        self.assertEqual(403, self.approve(invite, token, owner).status_code)
        self.assertEqual(409, self.claim(invite, owner).status_code)
        self.assertEqual(other_id, self.booking_owner(invite))

    def test_pairing_requires_owner_matching_vin_and_current_qr(self):
        auth=self.account(); invite=self.invitation(); self.claim(invite,auth)
        other=self.account('other@example.com')
        pair,token=self.pair('KMHTEST00TEST0002')
        self.assertEqual(403,self.approve(invite,token,auth).status_code)
        pair,token=self.pair(replace=pair['id'])
        self.assertEqual(403,self.approve(invite,token,other).status_code)
        self.assertEqual(200,self.approve(invite,token,auth).status_code)
        result=self.client.get('/api/device/pairings/'+pair['id'],headers=self.device).json()
        self.assertEqual(invite['bookingId'],result['bookingId'])
        self.assertNotIn(self.key,json.dumps(pair))
        fresh,fresh_token=self.pair(replace=pair['id'])
        self.assertEqual(410,self.approve(invite,token,auth).status_code)
        self.clock[0]+=301
        self.assertEqual(410,self.approve(invite,fresh_token,auth).status_code)

    def test_users_and_sessions_survive_restart(self):
        auth=self.account()
        app=create_app(self.path,self.key,'https://rental.example.test',clock=lambda:self.clock[0])
        with TestClient(app) as client:
            self.assertEqual('renter@example.com',client.get('/api/account/me',headers=auth).json()['email'])

    def test_account_page_assets_and_private_files(self):
        self.assertEqual(200,self.client.get('/account').status_code)
        self.assertEqual(200,self.client.get('/static/account.js').status_code)
        self.assertEqual(404,self.client.get('/static/mail-outbox').status_code)
        self.assertEqual(401,self.client.get('/api/account/bookings').status_code)


if __name__ == '__main__': unittest.main()
