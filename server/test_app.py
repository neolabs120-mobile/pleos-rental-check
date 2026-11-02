import base64
import copy
import io
import secrets
import tempfile
import unittest
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from uuid import uuid4

from fastapi.testclient import TestClient
from PIL import Image
from app import create_app, EMULATOR_TEST_SOURCE


class RentalApiTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.clock = [1_800_000_000.0]
        self.device_token = secrets.token_urlsafe(32)
        self.mail = []
        self.app = create_app(Path(self.directory.name), self.device_token, "https://rental.example.test", clock=lambda: self.clock[0], mailer=lambda *args: self.mail.append(args))
        self.client = TestClient(self.app)
        self.addCleanup(self.client.close)
        self.auth = {"Authorization": "Bearer " + self.device_token}
        signup = self.client.post('/api/auth/signup', json={"email": "renter@example.com", "nickname": "테스트", "password": "fixture-password-2026", "termsAccepted": True, "privacyAccepted": True})
        self.assertEqual(200, signup.status_code, signup.text)
        verified = self.client.post('/api/auth/verify', json={"challengeId": signup.json()['challengeId'], "code": self.mail[-1][2]})
        self.assertEqual(200, verified.status_code, verified.text)
        self.account_token = self.client.post('/api/auth/login', json={"email": "renter@example.com", "password": "fixture-password-2026"}).json()['token']
        self.account_auth = {"Authorization": "Bearer " + self.account_token}
        self.last_pairing_id = None
        self.id = str(uuid4())
        def snapshot(timestamp):
            return {"startedAtMillis": timestamp, "completedAtMillis": timestamp + 1,
                "source": "test SDK fixture", "readings": [{"key": "vehicle.vin", "label": "VIN", "status": "AVAILABLE",
                "value": "KMHTEST00TEST0001", "displayValue": "VIN ···ST0001", "unit": None, "detail": "", "observedAtMillis": timestamp}]}
        self.payload = {"id": self.id, "revision": 2, "pickup": snapshot(100), "returned": snapshot(200),
            "pickupWindowManuallyChecked": False, "returnWindowManuallyChecked": False, "bookingId": self.booking()}
        image = Image.new("RGB", (24, 18), (32, 96, 128))
        exif = Image.Exif(); exif[270] = "private camera metadata"
        raw = io.BytesIO(); image.save(raw, "JPEG", exif=exif)
        self.photo = base64.b64encode(raw.getvalue()).decode()

    def booking(self, demo=False):
        # Build an owner-approved reservation through the public APIs, optionally marking it as test-only.
        invite = self.client.post('/api/device/bookings', json={"email": "renter@example.com", "vin": "KMHTEST00TEST0001", "isDemo": demo}, headers=self.auth).json()
        claim = self.client.post('/api/account/bookings/claim', json={"bookingNumber": invite['bookingNumber'], "claimCode": invite['claimCode']}, headers=self.account_auth)
        self.assertEqual(200, claim.status_code, claim.text)
        if self.last_pairing_id:
            self.client.delete('/api/device/pairings/'+self.last_pairing_id, headers=self.auth)
        pair = self.client.post('/api/device/pairings', json={"vin": "KMHTEST00TEST0001"}, headers=self.auth).json()
        self.last_pairing_id = pair['id']
        token = parse_qs(urlsplit(pair['pairingUrl']).fragment)['pair'][0]
        approved = self.client.post('/api/account/pairings/approve', json={"bookingId": invite['bookingId'], "token": token}, headers=self.account_auth)
        self.assertEqual(200, approved.status_code, approved.text)
        return invite['bookingId']

    def enable_emulator_server(self):
        # Reopen the same fixture database with the explicit server opt-in; existing ownership and pairing remain intact.
        self.client.close()
        self.app = create_app(Path(self.directory.name), self.device_token, "https://rental.example.test",
            clock=lambda: self.clock[0], mailer=lambda *args: self.mail.append(args), allow_emulator_test_vin=True)
        self.client = TestClient(self.app)
        self.addCleanup(self.client.close)

    def emulator_payload(self):
        # Label both observations without replacing VIN checks or relying on the legacy fully synthetic seed bypass.
        payload = copy.deepcopy(self.payload)
        payload["pickup"]["source"] = payload["returned"]["source"] = EMULATOR_TEST_SOURCE
        return payload

    def test_default_server_rejects_emulator_records(self):
        # Production defaults must reject labelled emulator data even from an otherwise authenticated device.
        response = self.client.put('/api/rentals/' + self.id, json=self.emulator_payload(), headers=self.auth)
        self.assertEqual(403, response.status_code)

    def test_emulator_mode_requires_test_booking(self):
        # Server opt-in must not allow a synthetic identity to be attached to an ordinary reservation.
        self.enable_emulator_server()
        self.assertEqual(403, self.client.put('/api/rentals/' + self.id, json=self.emulator_payload(), headers=self.auth).status_code)

    def test_emulator_mode_still_requires_owner_approval(self):
        # Marking a booking as test-only must never inherit the old seed flow's pairing bypass.
        self.enable_emulator_server()
        payload = self.emulator_payload(); payload['bookingId'] = self.booking(demo=True)
        self.client.delete('/api/device/pairings/' + self.last_pairing_id, headers=self.auth)
        self.assertEqual(409, self.client.put('/api/rentals/' + self.id, json=payload, headers=self.auth).status_code)

    def test_emulator_records_are_labelled_and_cannot_mix_sources(self):
        # Approved emulator records must remain recognisable to clients and reject a real/test identity transition on return.
        self.enable_emulator_server()
        payload = self.emulator_payload(); payload['bookingId'] = self.booking(demo=True)
        result = self.save(payload)
        self.assertTrue(result['isDemo']); self.assertTrue(result['usesTestVin'])
        mixed = copy.deepcopy(payload); mixed['returned']['source'] = 'Pleos Vehicle SDK 2.3.2'
        self.assertEqual(409, self.client.put('/api/rentals/' + self.id, json=mixed, headers=self.auth).status_code)

    def save(self, payload=None):
        response = self.client.put("/api/rentals/" + self.id, json=payload or self.payload, headers=self.auth)
        self.assertEqual(200, response.status_code, response.text)
        return response.json()

    def link(self, phase="return", identifier=None):
        response = self.client.post(f"/api/rentals/{identifier or self.id}/photo-links", json={"phase": phase}, headers=self.auth)
        self.assertEqual(200, response.status_code, response.text)
        url = response.json()["uploadUrl"]
        token = parse_qs(urlsplit(url).fragment)["token"][0]
        self.assertNotIn(self.device_token, url)
        return {"Authorization": "Bearer " + token, "X-Account-Token": self.account_token}

    def upload(self, auth, category="windows", request_id=None):
        return self.client.post("/api/upload/photos", json={"category": category, "imageBase64": self.photo,
            "requestId": request_id or str(uuid4())}, headers=auth)

    def complete(self, auth):
        for category in ["windows", "belongings", "exterior"]:
            self.assertEqual(200, self.upload(auth, category).status_code)
        response = self.client.put("/api/upload/checks", json={"windows": True, "belongings": True, "exterior": True, "note": "직접 확인"}, headers=auth)
        self.assertEqual(200, response.status_code)

    def test_usage_memory_is_preserved_in_owner_and_photo_views(self):
        # Observed places and explicit marks must travel with the return without satisfying mandatory photos or checks.
        payload = copy.deepcopy(self.payload)
        memory = {"openedSpaces": ["REAR_RIGHT", "TRUNK"], "markedSpaces": ["TRUNK"], "monitoringGaps": True, "luggageMemos": [], "collectedSpaces": [], "luggageReviewRequired": False}
        payload["usageMemory"] = memory
        self.assertEqual(memory, self.save(payload)["usageMemory"])
        owner = self.client.get('/api/account/rentals/' + self.id, headers=self.account_auth)
        self.assertEqual(memory, owner.json()["usageMemory"])
        auth = self.link()
        self.assertEqual(memory, self.client.get('/api/upload/session', headers=auth).json()["usageMemory"])
        self.assertEqual(409, self.client.post('/api/upload/submit', json={"revision": 2}, headers=auth).status_code)
        self.complete(auth)
        self.assertEqual(200, self.client.post('/api/upload/submit', json={"revision": 2}, headers=auth).status_code)
        self.assertEqual(memory, self.client.get('/api/account/rentals/' + self.id, headers=self.account_auth).json()["usageMemory"])

    def test_usage_memory_rejects_unknown_and_duplicate_locations(self):
        # Constrain the vocabulary and count so arbitrary personal descriptions or duplicate locations cannot enter the schema.
        for values in (["UNKNOWN"], ["TRUNK", "TRUNK"], ["TRUNK"] * 6):
            payload = copy.deepcopy(self.payload)
            payload["usageMemory"] = {"openedSpaces": values}
            self.assertEqual(422, self.client.put('/api/rentals/' + self.id, json=payload, headers=self.auth).status_code)

    def test_reviewed_luggage_memo_is_shared_but_never_replaces_checks(self):
        # A reviewed item note survives all owner/photo views, while missing evidence still blocks submission and returned notes remain frozen.
        payload = copy.deepcopy(self.payload)
        payload["usageMemory"] = {"luggageMemos": [{"space": "TRUNK", "item": "캐리어"}]}
        saved = self.save(payload)
        self.assertEqual([{"space": "TRUNK", "item": "캐리어"}], saved["usageMemory"]["luggageMemos"])
        auth = self.link()
        self.assertEqual(saved["usageMemory"], self.client.get('/api/upload/session', headers=auth).json()["usageMemory"])
        self.assertEqual(409, self.client.post('/api/upload/submit', json={"revision": 2}, headers=auth).status_code)
        changed = copy.deepcopy(payload)
        changed["revision"] = 3
        changed["usageMemory"]["luggageMemos"][0]["item"] = "가방"
        self.assertEqual(409, self.client.put('/api/rentals/' + self.id, json=changed, headers=self.auth).status_code)

    def test_luggage_memo_rejects_unreviewable_or_ambiguous_data(self):
        # Enforce bounded item text, known spaces and one note per space, rejecting raw-transcript/audio fields as unknown input.
        invalid = [{"space": "UNKNOWN", "item": "가방"}, {"space": "TRUNK", "item": " "},
                   {"space": "TRUNK", "item": "a" * 41}, {"space": "TRUNK", "item": "가방\n"},
                   {"space": "TRUNK", "item": "가방", "transcript": "raw speech"}]
        for note in invalid:
            payload = copy.deepcopy(self.payload); payload["usageMemory"] = {"luggageMemos": [note]}
            self.assertEqual(422, self.client.put('/api/rentals/' + self.id, json=payload, headers=self.auth).status_code)
        payload = copy.deepcopy(self.payload)
        payload["usageMemory"] = {"luggageMemos": [{"space": "TRUNK", "item": "가방"}] * 2}
        self.assertEqual(422, self.client.put('/api/rentals/' + self.id, json=payload, headers=self.auth).status_code)

    def test_pre_voice_memory_retry_remains_idempotent(self):
        # Normalize the additive field for a historical record instead of requiring a new revision or rewriting its pickup/return snapshots.
        self.save()
        import sqlite3
        import json
        with sqlite3.connect(Path(self.directory.name) / 'rentals.sqlite3') as connection:
            old = json.loads(connection.execute("SELECT payload FROM rentals WHERE id=?", (self.id,)).fetchone()[0])
            old["usageMemory"].pop("luggageMemos", None)
            connection.execute("UPDATE rentals SET payload=? WHERE id=?", (json.dumps(old), self.id))
        self.assertEqual([], self.save()["usageMemory"]["luggageMemos"])

    def test_usage_memory_freezes_with_return_observation(self):
        # A later upload cannot rewrite the places recorded with an already-saved return, even if it has a newer revision.
        self.save()
        changed = copy.deepcopy(self.payload)
        changed.update(revision=3, usageMemory={"markedSpaces": ["TRUNK"]})
        self.assertEqual(409, self.client.put('/api/rentals/' + self.id, json=changed, headers=self.auth).status_code)

    def test_pre_memory_records_can_resync_without_changing_pickup(self):
        # A 0.1.2 payload without the new field must remain idempotent when the upgraded app includes its default memory.
        self.save()
        import json
        import sqlite3
        with sqlite3.connect(Path(self.directory.name) / 'rentals.sqlite3') as connection:
            row = connection.execute("SELECT payload FROM rentals WHERE id=?", (self.id,)).fetchone()
            old = json.loads(row[0]); old.pop("usageMemory", None)
            connection.execute("UPDATE rentals SET payload=? WHERE id=?", (json.dumps(old), self.id))
        result = self.save()
        self.assertEqual([], result["usageMemory"]["openedSpaces"])
        self.assertTrue(result["usageMemory"]["monitoringGaps"])

    def test_full_return_receipt_is_durable_and_idempotent(self):
        self.save(); auth = self.link(); self.complete(auth)
        response = self.client.post(f"/api/rentals/{self.id}/submit", json={"revision": 2}, headers=self.auth)
        self.assertEqual(200, response.status_code, response.text)
        receipt = response.json()
        self.assertIsNotNone(receipt["submittedAtMillis"])
        self.assertTrue(receipt["receiptId"])
        self.assertEqual(3, len(receipt["photos"]))
        self.assertEqual(401, self.client.get("/api/upload/session", headers=auth).status_code)
        repeated = self.client.post(f"/api/rentals/{self.id}/submit", json={"revision": 2}, headers=self.auth).json()
        self.assertEqual(receipt["receiptId"], repeated["receiptId"])
        self.assertEqual(receipt["receiptId"], self.save()["receiptId"])
        restarted = TestClient(create_app(Path(self.directory.name), self.device_token, "https://rental.example.test"))
        self.addCleanup(restarted.close)
        self.assertEqual(receipt, restarted.get(f"/api/rentals/{self.id}", headers=self.auth).json())

    def test_missing_photo_or_manual_check_blocks_submission(self):
        self.save(); auth = self.link()
        self.assertEqual(200, self.upload(auth).status_code)
        self.client.put("/api/upload/checks", json={"windows": True, "belongings": True, "exterior": True}, headers=auth)
        self.assertEqual(409, self.client.post(f"/api/rentals/{self.id}/submit", json={"revision": 2}, headers=self.auth).status_code)
        self.upload(auth, "belongings"); self.upload(auth, "exterior")
        self.client.put("/api/upload/checks", json={"windows": True, "belongings": False, "exterior": True}, headers=auth)
        self.assertEqual(409, self.client.post(f"/api/rentals/{self.id}/submit", json={"revision": 2}, headers=self.auth).status_code)

    def test_vin_mismatch_missing_vin_and_time_reversal_rejected(self):
        bad = copy.deepcopy(self.payload); bad["returned"]["readings"][0]["value"] = "KMHTEST00TEST0002"
        self.assertEqual(409, self.client.put(f"/api/rentals/{self.id}", json=bad, headers=self.auth).status_code)
        bad = copy.deepcopy(self.payload); bad["pickup"]["readings"] = []
        self.assertEqual(422, self.client.put(f"/api/rentals/{self.id}", json=bad, headers=self.auth).status_code)
        bad = copy.deepcopy(self.payload); bad["returned"]["startedAtMillis"] = 10
        self.assertEqual(409, self.client.put(f"/api/rentals/{self.id}", json=bad, headers=self.auth).status_code)

    def test_pickup_baseline_and_revision_are_protected(self):
        self.save()
        changed = copy.deepcopy(self.payload); changed["revision"] = 3; changed["pickup"]["source"] = "changed"
        self.assertEqual(409, self.client.put(f"/api/rentals/{self.id}", json=changed, headers=self.auth).status_code)
        changed = copy.deepcopy(self.payload); changed["revision"] = 1
        self.assertEqual(409, self.client.put(f"/api/rentals/{self.id}", json=changed, headers=self.auth).status_code)
        self.assertEqual(409, self.client.post(f"/api/rentals/{self.id}/submit", json={"revision": 1}, headers=self.auth).status_code)

    def test_phone_token_cannot_control_vehicle_records(self):
        self.save(); auth = self.link()
        self.assertEqual(401, self.client.get(f"/api/rentals/{self.id}", headers=auth).status_code)
        self.assertEqual(401, self.client.put(f"/api/rentals/{self.id}", json=self.payload).status_code)
        other = str(uuid4())
        second = copy.deepcopy(self.payload); second["id"] = other
        second["bookingId"] = self.booking()
        self.assertEqual(200, self.client.put(f"/api/rentals/{other}", json=second, headers=self.auth).status_code)
        other_auth = self.link(identifier=other)
        photo_id = self.upload(other_auth).json()["photoId"]
        self.assertEqual(404, self.client.get(f"/api/upload/photos/{photo_id}", headers=auth).status_code)
        self.assertEqual(404, self.client.delete(f"/api/upload/photos/{photo_id}", headers=auth).status_code)

    def test_rotation_expiry_and_phase_scope(self):
        self.save(); auth = self.link(); replacement = self.link()
        self.assertEqual(401, self.client.get("/api/upload/session", headers=auth).status_code)
        self.assertEqual(200, self.client.get("/api/upload/session", headers=replacement).status_code)
        pickup_auth = self.link("pickup")
        self.complete(pickup_auth)
        self.assertEqual(403, self.client.post('/api/upload/submit', json={"revision": 2}, headers=pickup_auth).status_code)
        self.assertEqual(409, self.client.post(f"/api/rentals/{self.id}/submit", json={"revision": 2}, headers=self.auth).status_code)
        self.clock[0] += 901
        self.assertEqual(401, self.client.get("/api/upload/session", headers=replacement).status_code)

    def test_phone_can_finish_only_its_own_return_and_retry_the_same_receipt(self):
        self.save(); auth = self.link(); self.complete(auth)
        first = self.client.post('/api/upload/submit', json={"revision": 2}, headers=auth)
        self.assertEqual(200, first.status_code, first.text)
        self.assertNotIn('photos', first.json())
        self.assertEqual(first.json(), self.client.post('/api/upload/submit', json={"revision": 2}, headers=auth).json())
        self.assertEqual(401, self.client.get('/api/upload/session', headers=auth).status_code)
        device_receipt = self.client.get('/api/rentals/' + self.id, headers=self.auth).json()
        self.assertEqual(first.json()['receiptId'], device_receipt['receiptId'])

    def test_photo_retry_is_idempotent_and_exif_is_removed(self):
        self.save(); auth = self.link(); request_id = str(uuid4())
        first = self.upload(auth, request_id=request_id).json()
        second = self.upload(auth, request_id=request_id).json()
        self.assertEqual(first, second)
        response = self.client.get("/api/upload/photos/" + first["photoId"], headers=auth)
        self.assertEqual("image/jpeg", response.headers["content-type"])
        with Image.open(io.BytesIO(response.content)) as image:
            self.assertEqual((24, 18), image.size)
            self.assertEqual(0, len(image.getexif()))
        self.assertEqual(1, len(self.client.get("/api/upload/session", headers=auth).json()["photos"]))

    def test_invalid_image_and_photo_limit_are_enforced(self):
        self.save(); auth = self.link()
        invalid = {"category": "windows", "requestId": str(uuid4()), "imageBase64": base64.b64encode(b'<svg>not a photo</svg>').decode()}
        self.assertEqual(422, self.client.post("/api/upload/photos", json=invalid, headers=auth).status_code)
        for _ in range(5):
            self.assertEqual(200, self.upload(auth).status_code)
        self.assertEqual(409, self.upload(auth).status_code)

    def test_oversize_body_and_private_error_echo(self):
        response = self.client.post("/api/upload/photos", content=b'x'*(12*1024*1024+1))
        self.assertEqual(413, response.status_code)
        response = self.client.post("/api/rentals/" + self.id + "/photo-links", json={"phase": "private-input-not-to-echo"}, headers=self.auth)
        self.assertEqual(422, response.status_code)
        self.assertNotIn("private-input-not-to-echo", response.text)

    def test_no_return_snapshot_cannot_open_return_photo_link(self):
        body = copy.deepcopy(self.payload); body["returned"] = None; body["revision"] = 1
        self.save(body)
        self.assertEqual(409, self.client.post(f"/api/rentals/{self.id}/photo-links", json={"phase": "return"}, headers=self.auth).status_code)

    def test_web_and_api_disable_caching_and_public_photo_access(self):
        response = self.client.get("/upload")
        self.assertEqual(200, response.status_code)
        self.assertIn("no-store", response.headers["cache-control"])
        self.assertIn("frame-ancestors 'none'", response.headers["content-security-policy"])
        self.assertEqual(200, self.client.get("/static/upload.js").status_code)
        self.assertEqual(404, self.client.get("/static/app.py").status_code)

    def test_photo_link_alone_is_not_an_account_and_receipt_remains_owner_only(self):
        self.save(); auth=self.link()
        self.assertEqual(401,self.client.get('/api/upload/session',headers={'Authorization':auth['Authorization']}).status_code)
        other=self.client.post('/api/auth/signup',json={'nickname':'다른 이용자','email':'stranger@example.com','password':'another-password-2026','termsAccepted':True,'privacyAccepted':True}).json()
        self.client.post('/api/auth/verify',json={'challengeId':other['challengeId'],'code':self.mail[-1][2]})
        other_token=self.client.post('/api/auth/login',json={'email':'stranger@example.com','password':'another-password-2026'}).json()['token']
        other_auth={'Authorization':'Bearer '+other_token}
        self.assertEqual(404,self.client.get('/api/upload/session',headers={**auth,'X-Account-Token':other_token}).status_code)
        self.assertEqual(404,self.client.get('/api/account/rentals/'+self.id,headers=other_auth).status_code)
        self.complete(auth)
        receipt=self.client.post('/api/upload/submit',json={'revision':2},headers=auth).json()
        record=self.client.get('/api/account/rentals/'+self.id,headers=self.account_auth)
        self.assertEqual(200,record.status_code,record.text)
        self.assertEqual(receipt['receiptId'],record.json()['receiptId'])
        photo_id=record.json()['photos'][0]['id']
        self.assertEqual(200,self.client.get('/api/account/photos/'+photo_id,headers=self.account_auth).status_code)
        self.assertEqual(404,self.client.get('/api/account/photos/'+photo_id,headers=other_auth).status_code)
        self.assertEqual('disconnected',self.client.get('/api/device/pairings/'+self.last_pairing_id,headers=self.auth).json()['status'])

    def test_unlinked_record_and_booking_switch_are_rejected(self):
        body=copy.deepcopy(self.payload);body['bookingId']=None
        self.assertEqual(409,self.client.put('/api/rentals/'+self.id,json=body,headers=self.auth).status_code)
        self.save()
        body=copy.deepcopy(self.payload);body['bookingId']=self.booking();body['revision']=3
        self.assertEqual(409,self.client.put('/api/rentals/'+self.id,json=body,headers=self.auth).status_code)


    def test_reviewed_items_require_per_item_confirmation_and_preserve_retry(self):
        # Photos and a general belongings check cannot silently acknowledge the newly reviewed item list.
        payload = copy.deepcopy(self.payload)
        payload['usageMemory'] = dict(luggageMemos=[dict(space='TRUNK', item='캐리어')], luggageReviewRequired=True)
        self.save(payload); auth = self.link(); self.complete(auth)
        self.assertEqual(409, self.client.post('/api/upload/submit', json={'revision':2}, headers=auth).status_code)
        body = dict(windows=True, belongings=True, exterior=True, collectedSpaces=['TRUNK'])
        self.assertEqual(200, self.client.put('/api/upload/checks', json=body, headers=auth).status_code)
        first = self.client.post('/api/upload/submit', json={'revision':2}, headers=auth)
        self.assertEqual(200, first.status_code, first.text)
        self.assertEqual(first.json(), self.client.post('/api/upload/submit', json={'revision':2}, headers=auth).json())

    def test_item_confirmation_cannot_reference_other_places_or_change_frozen_notes(self):
        # Item recovery is a user assertion for an existing note, never an arbitrary location or a way to rewrite returned evidence.
        payload = copy.deepcopy(self.payload)
        payload['usageMemory'] = dict(luggageMemos=[dict(space='TRUNK', item='캐리어')], collectedSpaces=['TRUNK'], luggageReviewRequired=True)
        self.save(payload); auth = self.link()
        result = self.client.get('/api/upload/session', headers=auth).json()
        self.assertEqual(['TRUNK'], result['checks']['collectedSpaces'])
        for invalid in [['FRONT_LEFT'], ['TRUNK','TRUNK']]:
            self.assertEqual(422, self.client.put('/api/upload/checks', json={'collectedSpaces':invalid}, headers=auth).status_code)
        payload['revision']=3; payload['usageMemory']['collectedSpaces']=[]
        self.assertEqual(409, self.client.put('/api/rentals/'+self.id, json=payload, headers=self.auth).status_code)

    def test_battery_reference_is_immutable_pickup_value_and_unknown_stays_unknown(self):
        # A return percentage never becomes its own target, and failed readings never appear as a zero-percent charge.
        payload = copy.deepcopy(self.payload)
        for phase, value in [('pickup','80'),('returned','72.5')]:
            payload[phase]['readings'].append(dict(key='battery.percent',label='배터리',status='AVAILABLE',value=value,displayValue=value,observedAtMillis=100))
        result=self.save(payload)['batteryReturn']
        self.assertEqual(dict(targetPercent=80.0,returnPercent=72.5,shortfallPercent=7.5),result)
        payload['revision']=3;payload['pickup']['readings'][-1]['value']='70'
        self.assertEqual(409,self.client.put('/api/rentals/'+self.id,json=payload,headers=self.auth).status_code)


if __name__ == "__main__":
    unittest.main()
