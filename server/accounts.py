"""Email accounts, renter-owned bookings and phone-approved vehicle pairing.

Device credentials are provisioned by the operator; they are never sent to a phone.
Booking invitations are issued by an operator/seed script, not by a renter or VIN.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import secrets
import sqlite3
from uuid import UUID, uuid4

from fastapi import Depends, Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator
from return_location import ReturnLocation

TERMS_VERSION = "competition-draft-2026-10"
ITERATIONS = 600_000


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def password_hash(password):
    salt = secrets.token_bytes(16)
    value = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, ITERATIONS)
    return f"pbkdf2_sha256${ITERATIONS}${salt.hex()}${value.hex()}"


def password_matches(password, encoded):
    _, iterations, salt, expected = encoded.split("$")
    actual = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), int(iterations)).hex()
    return secrets.compare_digest(actual, expected)


class Form(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EmailForm(Form):
    email: str = Field(max_length=254)

    @field_validator("email")
    @classmethod
    def email_address(cls, value):
        value = value.strip().lower()
        if not re.fullmatch(r"[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}", value):
            raise ValueError("올바른 이메일을 입력해 주세요.")
        return value


class Signup(EmailForm):
    nickname: str = Field(min_length=1, max_length=32)
    password: str = Field(min_length=12, max_length=128)
    termsAccepted: bool
    privacyAccepted: bool

    @field_validator("nickname")
    @classmethod
    def nickname_text(cls, value):
        if not value.strip():
            raise ValueError("닉네임을 입력해 주세요.")
        return value.strip()


class Verify(Form):
    challengeId: UUID
    code: str = Field(pattern=r"^\d{6}$")


class Resend(Form):
    challengeId: UUID


class Login(EmailForm):
    password: str = Field(min_length=1, max_length=128)


class Reset(Verify):
    password: str = Field(min_length=12, max_length=128)


class Invitation(EmailForm):
    vin: str = Field(pattern=r"^[A-HJ-NPR-Z0-9]{17}$")
    vehicleName: str = Field(default="대여 차량", min_length=1, max_length=80)
    bookingNumber: str = Field(default="", max_length=40, pattern=r"^[A-Za-z0-9-]*$")
    isDemo: bool = False
    returnLocation: ReturnLocation | None = None


class Claim(Form):
    bookingNumber: str = Field(min_length=1, max_length=40)
    claimCode: str = Field(min_length=16, max_length=128)


class PairRequest(Form):
    vin: str = Field(pattern=r"^[A-HJ-NPR-Z0-9]{17}$")
    replaceId: UUID | None = None


class PairApproval(Form):
    token: str = Field(min_length=32, max_length=128)
    bookingId: UUID


class Accounts:
    def __init__(self, app, db, millis, authorize, public_base_url, summary, mailer=None):
        self.db, self.millis, self.summary, self.mailer = db, millis, summary, mailer
        self.dummy_hash = password_hash(secrets.token_urlsafe(24))
        with db() as connection:
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS account_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,
                    nickname TEXT NOT NULL,password_hash TEXT NOT NULL,created_at INTEGER NOT NULL,
                    terms_version TEXT NOT NULL,consented_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS challenges(id TEXT PRIMARY KEY,email TEXT NOT NULL,purpose TEXT NOT NULL,
                    payload TEXT NOT NULL,code_hash TEXT NOT NULL,expires_at INTEGER NOT NULL,
                    last_sent INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,used INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS account_sessions(hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),
                    expires_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS rate_limits(key TEXT PRIMARY KEY,started_at INTEGER NOT NULL,count INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS bookings(id TEXT PRIMARY KEY,booking_number TEXT UNIQUE NOT NULL,
                    email TEXT NOT NULL,claim_hash TEXT NOT NULL,vin TEXT NOT NULL,vehicle_name TEXT NOT NULL,
                    user_id TEXT REFERENCES users(id),is_demo INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS pairings(id TEXT PRIMARY KEY,token_hash TEXT UNIQUE NOT NULL,vin TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,booking_id TEXT REFERENCES bookings(id),
                    user_id TEXT REFERENCES users(id),revoked INTEGER NOT NULL DEFAULT 0);
            """)
            connection.execute("INSERT OR IGNORE INTO account_meta VALUES('otp_key',?)", (secrets.token_hex(32),))
            self.otp_key = bytes.fromhex(connection.execute("SELECT value FROM account_meta WHERE key='otp_key'").fetchone()[0])
            columns = {row[1] for row in connection.execute("PRAGMA table_info(rentals)")}
            if "booking_id" not in columns:
                connection.execute("ALTER TABLE rentals ADD COLUMN booking_id TEXT REFERENCES bookings(id)")
            connection.execute("CREATE UNIQUE INDEX IF NOT EXISTS rental_booking ON rentals(booking_id) WHERE booking_id IS NOT NULL")
            # Keep legacy booking rows intact and store only the operator's destination, never live vehicle coordinates.
            if "return_location" not in {row[1] for row in connection.execute("PRAGMA table_info(bookings)")}:
                connection.execute("ALTER TABLE bookings ADD COLUMN return_location TEXT")

        @app.post("/api/auth/signup")
        def signup(body: Signup, request: Request):
            self.limit("signup", request, body.email, 5)
            if not body.termsAccepted or not body.privacyAccepted:
                raise HTTPException(422, "필수 약관에 동의해 주세요.")
            with db() as connection:
                if connection.execute("SELECT 1 FROM users WHERE email=?", (body.email,)).fetchone():
                    raise HTTPException(409, "이미 가입한 이메일입니다. 로그인해 주세요.")
            return self.challenge(body.email, "signup", {"nickname": body.nickname,
                "password_hash": password_hash(body.password), "consented_at": millis(), "terms_version": TERMS_VERSION})

        @app.post("/api/auth/resend")
        def resend(body: Resend, request: Request):
            self.limit("resend", request, str(body.challengeId), 5)
            with db() as connection:
                row = connection.execute("SELECT * FROM challenges WHERE id=?", (str(body.challengeId),)).fetchone()
            if not row or row["used"]:
                raise HTTPException(409, "처음부터 다시 요청해 주세요.")
            if row["last_sent"] + 60_000 > millis():
                raise HTTPException(429, "잠시 후 확인번호를 다시 요청해 주세요.")
            # Never renew an unverified signup's password; restart the form when expired.
            if row["expires_at"] <= millis():
                raise HTTPException(410, "확인 시간이 지났어요. 처음부터 다시 요청해 주세요.")
            return self.challenge(row["email"], row["purpose"], json.loads(row["payload"]))

        @app.post("/api/auth/verify")
        def verify(body: Verify):
            row = self.verify_code(body, "signup")
            payload = json.loads(row["payload"])
            with db() as connection:
                connection.execute("BEGIN IMMEDIATE")
                try:
                    connection.execute("INSERT INTO users VALUES(?,?,?,?,?,?,?)", (str(uuid4()), row["email"],
                        payload["nickname"], payload["password_hash"], millis(), payload["terms_version"], payload["consented_at"]))
                except sqlite3.IntegrityError:
                    raise HTTPException(409, "이미 가입한 이메일입니다. 로그인해 주세요.") from None
                connection.execute("UPDATE challenges SET used=1 WHERE email=? AND purpose='signup'", (row["email"],))
            return {"verified": True}

        @app.post("/api/auth/login")
        def login(body: Login, request: Request):
            self.limit("login", request, body.email, 10)
            with db() as connection:
                row = connection.execute("SELECT * FROM users WHERE email=?", (body.email,)).fetchone()
            if not password_matches(body.password, row["password_hash"] if row else self.dummy_hash) or not row:
                raise HTTPException(401, "이메일 또는 비밀번호를 확인해 주세요.")
            token, expires = secrets.token_urlsafe(32), millis() + 8 * 60 * 60 * 1000
            with db() as connection:
                connection.execute("DELETE FROM account_sessions WHERE expires_at<=?", (millis(),))
                connection.execute("INSERT INTO account_sessions VALUES(?,?,?)", (digest(token), row["id"], expires))
            return {"token": token, "expiresAtMillis": expires, "user": self.public_user(row)}

        @app.get("/api/account/me")
        def me(user=Depends(self.user)):
            return self.public_user(user)

        @app.post("/api/auth/logout")
        def logout(user=Depends(self.user), authorization: str | None = Header(default=None)):
            with db() as connection:
                connection.execute("DELETE FROM account_sessions WHERE hash=?", (digest(self.bearer(authorization)),))
                connection.execute("UPDATE pairings SET revoked=1 WHERE user_id=?", (user["id"],))
            return {"loggedOut": True}

        @app.post("/api/auth/password/request")
        def reset_request(body: EmailForm, request: Request):
            self.limit("reset", request, body.email, 5)
            with db() as connection:
                exists = connection.execute("SELECT 1 FROM users WHERE email=?", (body.email,)).fetchone()
            # Same response for unknown addresses; no account enumeration through reset.
            if exists:
                result = self.challenge(body.email, "reset", {})
            else:
                result = {"challengeId": str(uuid4()), "expiresAtMillis": millis() + 10 * 60 * 1000}
            return {**result, "message": "가입한 이메일이면 확인번호를 보냈어요."}

        @app.post("/api/auth/password/reset")
        def reset_password(body: Reset):
            row = self.verify_code(body, "reset")
            encoded = password_hash(body.password)
            with db() as connection:
                connection.execute("BEGIN IMMEDIATE")
                user = connection.execute("SELECT id FROM users WHERE email=?", (row["email"],)).fetchone()
                connection.execute("UPDATE users SET password_hash=? WHERE id=?", (encoded, user["id"]))
                connection.execute("DELETE FROM account_sessions WHERE user_id=?", (user["id"],))
                connection.execute("UPDATE pairings SET revoked=1 WHERE user_id=?", (user["id"],))
                connection.execute("UPDATE challenges SET used=1 WHERE email=? AND purpose='reset'", (row["email"],))
            return {"reset": True}

        @app.post("/api/device/bookings", dependencies=[Depends(authorize)])
        def invite(body: Invitation):
            # Bind a validated optional return location to this invitation without changing existing reservations or ownership.
            if body.returnLocation and body.returnLocation.isTest and not body.isDemo:
                raise HTTPException(422, "시험 반납 장소는 시연 예약에서만 사용할 수 있어요.")
            identifier, code = str(uuid4()), secrets.token_urlsafe(24)
            number = body.bookingNumber.upper() or "RC-" + secrets.token_hex(5).upper()
            with db() as connection:
                try:
                    connection.execute("INSERT INTO bookings(id,booking_number,email,claim_hash,vin,vehicle_name,user_id,is_demo,created_at,return_location) VALUES(?,?,?,?,?,?,NULL,?,?,?)", (identifier, number,
                        body.email, digest(code), body.vin, body.vehicleName, int(body.isDemo), millis(),
                        body.returnLocation.model_dump_json() if body.returnLocation else None))
                except sqlite3.IntegrityError:
                    raise HTTPException(409, "이미 사용 중인 예약번호입니다.") from None
            return {"bookingId": identifier, "bookingNumber": number, "claimCode": code, "isDemo": body.isDemo}

        @app.post("/api/account/bookings/claim")
        def claim(body: Claim, request: Request, user=Depends(self.user)):
            self.limit("claim", request, user["id"], 10)
            with db() as connection:
                connection.execute("BEGIN IMMEDIATE")
                row = connection.execute("SELECT * FROM bookings WHERE booking_number=?", (body.bookingNumber.strip().upper(),)).fetchone()
                if not row or row["email"] != user["email"] or not secrets.compare_digest(row["claim_hash"], digest(body.claimCode)):
                    raise HTTPException(403, "이 계정으로 연결할 수 있는 예약 정보와 연결 코드를 확인해 주세요.")
                if row["user_id"] and row["user_id"] != user["id"]:
                    raise HTTPException(409, "다른 계정에 연결된 대여 건입니다.")
                connection.execute("UPDATE bookings SET user_id=? WHERE id=?", (user["id"], row["id"]))
            return {"bookingId": row["id"], "claimed": True}

        @app.get("/api/account/bookings")
        def bookings(user=Depends(self.user)):
            # Verified recipients see their operator-issued reservations immediately; viewing the list does not claim a booking or approve a vehicle.
            with db() as connection:
                rows = connection.execute("SELECT b.*,r.id rental_id,r.submitted_at FROM bookings b LEFT JOIN rentals r ON r.booking_id=b.id WHERE b.user_id=? OR (b.user_id IS NULL AND b.email=?) ORDER BY b.created_at DESC", (user["id"], user["email"])).fetchall()
            return {"bookings": [{"id": r["id"], "bookingNumber": r["booking_number"], "vehicleName": r["vehicle_name"],
                "vinSuffix": r["vin"][-6:], "isDemo": bool(r["is_demo"]), "rentalId": r["rental_id"],
                "submittedAtMillis": r["submitted_at"], "returnLocation": json.loads(r["return_location"]) if r["return_location"] else None} for r in rows]}

        @app.post("/api/device/pairings", dependencies=[Depends(authorize)])
        def create_pairing(body: PairRequest):
            identifier, token, expires = str(uuid4()), secrets.token_urlsafe(32), millis() + 5 * 60 * 1000
            with db() as connection:
                if body.replaceId:
                    connection.execute("UPDATE pairings SET revoked=1 WHERE id=?", (str(body.replaceId),))
                connection.execute("INSERT INTO pairings VALUES(?,?,?,?,NULL,NULL,0)", (identifier, digest(token), body.vin, expires))
            return {"id": identifier, "pairingUrl": public_base_url.rstrip("/") + "/account#pair=" + token, "expiresAtMillis": expires}

        @app.get("/api/device/pairings/{pairing_id}", dependencies=[Depends(authorize)])
        def pairing_status(pairing_id: UUID):
            # Deliver a destination only within the existing authenticated, approved vehicle pairing.
            with db() as connection:
                row = connection.execute("SELECT p.*,b.booking_number,b.return_location,u.nickname FROM pairings p LEFT JOIN bookings b ON b.id=p.booking_id LEFT JOIN users u ON u.id=p.user_id WHERE p.id=?", (str(pairing_id),)).fetchone()
            if not row or row["revoked"]:
                return {"status": "disconnected"}
            if row["booking_id"]:
                return {"status": "connected", "bookingId": row["booking_id"], "bookingNumber": row["booking_number"],
                    "nickname": row["nickname"], "vin": row["vin"], "vinSuffix": row["vin"][-6:],
                    "returnLocation": json.loads(row["return_location"]) if row["return_location"] else None}
            return {"status": "expired" if row["expires_at"] <= millis() else "waiting"}

        @app.delete("/api/device/pairings/{pairing_id}", dependencies=[Depends(authorize)])
        def unpair(pairing_id: UUID):
            with db() as connection:
                connection.execute("UPDATE pairings SET revoked=1 WHERE id=?", (str(pairing_id),))
            return {"disconnected": True}

        @app.post("/api/account/pairings/approve")
        def approve(body: PairApproval, user=Depends(self.user)):
            # Bind an unclaimed reservation to its verified recipient only after explicit QR approval, matching VIN, expiry and exclusive-vehicle checks all pass.
            with db() as connection:
                connection.execute("BEGIN IMMEDIATE")
                pair = connection.execute("SELECT * FROM pairings WHERE token_hash=?", (digest(body.token),)).fetchone()
                booking = connection.execute("SELECT * FROM bookings WHERE id=? AND (user_id=? OR (user_id IS NULL AND email=?))", (str(body.bookingId), user["id"], user["email"])).fetchone()
                if not pair or pair["revoked"] or pair["expires_at"] <= millis():
                    raise HTTPException(410, "차량 연결 QR이 만료됐어요. 차량에서 새 QR을 발급해 주세요.")
                if not booking or booking["vin"] != pair["vin"]:
                    raise HTTPException(403, "선택한 대여 차량과 QR의 차량이 일치하지 않습니다.")
                if connection.execute("SELECT 1 FROM rentals WHERE booking_id=? AND submitted_at IS NOT NULL", (booking["id"],)).fetchone():
                    raise HTTPException(409, "이미 점검 제출을 마친 대여 건입니다.")
                if pair["booking_id"] and (pair["booking_id"] != booking["id"] or pair["user_id"] != user["id"]):
                    raise HTTPException(409, "이미 사용한 차량 연결 QR입니다.")
                occupied = connection.execute("SELECT 1 FROM pairings WHERE vin=? AND booking_id IS NOT NULL AND booking_id<>? AND revoked=0", (pair["vin"], booking["id"])).fetchone()
                if occupied:
                    raise HTTPException(409, "차량의 이전 계정 연결을 먼저 해제해 주세요.")
                connection.execute("UPDATE bookings SET user_id=? WHERE id=? AND user_id IS NULL", (user["id"], booking["id"]))
                connection.execute("UPDATE pairings SET revoked=1 WHERE booking_id=? AND id<>?", (booking["id"], pair["id"]))
                connection.execute("UPDATE pairings SET booking_id=?,user_id=? WHERE id=?", (booking["id"], user["id"], pair["id"]))
            return {"connected": True, "vinSuffix": booking["vin"][-6:]}

        @app.get("/api/account/rentals/{rental_id}")
        def detail(rental_id: UUID, user=Depends(self.user)):
            with db() as connection:
                row = self.owned_rental(connection, str(rental_id), user["id"])
                result = summary(connection, str(rental_id))
                payload = json.loads(row["payload"])
            def value(snapshot, key):
                for reading in (snapshot or {}).get("readings", []):
                    if reading["key"] == key and reading["status"] == "AVAILABLE":
                        try:
                            number = float(reading["value"])
                            return number if __import__("math").isfinite(number) else None
                        except (ValueError, TypeError):
                            return None
                return None
            pickup, returned = payload["pickup"], payload.get("returned")
            start, end = value(pickup, "odometer.km"), value(returned, "odometer.km")
            return {**result, "pickupAtMillis": pickup["completedAtMillis"],
                "returnAtMillis": returned["completedAtMillis"] if returned else None,
                "distanceKm": round(end - start, 3) if start is not None and end is not None and end >= start else None,
                "pickupFuel": value(pickup, "fuel.percent"), "returnFuel": value(returned, "fuel.percent")}

        @app.get("/api/account/photos/{photo_id}")
        def account_photo(photo_id: UUID, user=Depends(self.user)):
            from fastapi.responses import Response
            with db() as connection:
                row = connection.execute("SELECT p.pixels FROM photos p JOIN rentals r ON r.id=p.rental_id JOIN bookings b ON b.id=r.booking_id WHERE p.id=? AND b.user_id=?", (str(photo_id), user["id"])).fetchone()
            if not row:
                raise HTTPException(404, "사진을 찾지 못했습니다.")
            return Response(bytes(row["pixels"]), media_type="image/jpeg")

    @staticmethod
    def bearer(authorization):
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "로그인이 필요합니다.")
        return authorization[7:]

    def user(self, authorization: str | None = Header(default=None)):
        token = self.bearer(authorization)
        with self.db() as connection:
            row = connection.execute("SELECT u.* FROM account_sessions s JOIN users u ON u.id=s.user_id WHERE s.hash=? AND s.expires_at>?", (digest(token), self.millis())).fetchone()
        if not row:
            raise HTTPException(401, "로그인 시간이 지났어요. 다시 로그인해 주세요.")
        return dict(row)

    @staticmethod
    def public_user(row):
        return {"id": row["id"], "email": row["email"], "nickname": row["nickname"]}

    def limit(self, action, request, identifier, maximum):
        client = request.client.host if request.client else "local"
        # Both address-wide and per-identifier limits prevent guessing and password-spray bursts.
        blocked = False
        with self.db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM rate_limits WHERE started_at<?", (self.millis() - 900_000,))
            for key, cap in [(digest(action + client), maximum * 5), (digest(action + client + identifier), maximum)]:
                row = connection.execute("SELECT * FROM rate_limits WHERE key=?", (key,)).fetchone()
                if row:
                    blocked |= row["count"] >= cap
                    connection.execute("UPDATE rate_limits SET count=count+1 WHERE key=?", (key,))
                else:
                    connection.execute("INSERT INTO rate_limits VALUES(?,?,1)", (key, self.millis()))
        if blocked:
            raise HTTPException(429, "요청이 많아요. 잠시 후 다시 시도해 주세요.")

    def code_hash(self, identifier, code):
        return hmac.new(self.otp_key, (identifier + code).encode(), hashlib.sha256).hexdigest()

    def challenge(self, email, purpose, payload):
        if self.mailer is None:
            raise HTTPException(503, "이메일 발송 설정이 필요합니다. 서버의 메일 설정을 확인해 주세요.")
        identifier, code, expires = str(uuid4()), f"{secrets.randbelow(1_000_000):06d}", self.millis() + 600_000
        with self.db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            old = connection.execute("SELECT last_sent FROM challenges WHERE email=? AND purpose=? ORDER BY last_sent DESC LIMIT 1", (email, purpose)).fetchone()
            if old and old["last_sent"] + 60_000 > self.millis():
                raise HTTPException(429, "잠시 후 확인번호를 다시 요청해 주세요.")
            connection.execute("DELETE FROM challenges WHERE expires_at<?", (self.millis() - 86_400_000,))
            connection.execute("UPDATE challenges SET used=1 WHERE email=? AND purpose=?", (email, purpose))
            connection.execute("INSERT INTO challenges VALUES(?,?,?,?,?,?,?,0,0)", (identifier, email, purpose,
                json.dumps(payload), self.code_hash(identifier, code), expires, self.millis()))
        try:
            self.mailer(email, purpose, code)
        except Exception:
            with self.db() as connection:
                connection.execute("UPDATE challenges SET used=1 WHERE id=?", (identifier,))
            raise HTTPException(503, "확인번호 발송을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.") from None
        return {"challengeId": identifier, "expiresAtMillis": expires,
            "deliveryMode": getattr(self.mailer, "delivery_mode", "smtp")}

    def verify_code(self, body, purpose):
        error, result = None, None
        with self.db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT * FROM challenges WHERE id=? AND purpose=?", (str(body.challengeId), purpose)).fetchone()
            if not row or row["used"] or row["expires_at"] <= self.millis() or row["attempts"] >= 5:
                error = "확인번호가 만료됐어요. 처음부터 다시 요청해 주세요."
            elif not secrets.compare_digest(row["code_hash"], self.code_hash(str(body.challengeId), body.code)):
                connection.execute("UPDATE challenges SET attempts=attempts+1 WHERE id=?", (str(body.challengeId),))
                error = "확인번호가 일치하지 않습니다."
            else:
                connection.execute("UPDATE challenges SET used=1 WHERE id=?", (str(body.challengeId),))
                result = dict(row)
        # Raise after commit so wrong attempts and consumed codes remain consumed.
        if error:
            raise HTTPException(422, error)
        return result

    @staticmethod
    def owned_rental(connection, identifier, user_id):
        row = connection.execute("SELECT r.* FROM rentals r JOIN bookings b ON b.id=r.booking_id WHERE r.id=? AND b.user_id=?", (identifier, user_id)).fetchone()
        if not row:
            raise HTTPException(404, "이 계정의 이용 기록을 찾지 못했습니다.")
        return row

    def ensure_owner(self, grant, account_token):
        user = self.user("Bearer " + (account_token or ""))
        with self.db() as connection:
            self.owned_rental(connection, grant["rental_id"], user["id"])

    def validate_booking(self, connection, booking_id, vin, demo=False, emulator_test=False):
        # Emulator VIN records require a marked test booking and the same active owner-approved pairing as a normal rental.
        if not booking_id:
            raise HTTPException(409, "먼저 계정과 대여 건을 차량에 연결해 주세요.")
        booking = connection.execute("SELECT * FROM bookings WHERE id=?", (str(booking_id),)).fetchone()
        if not booking or booking["vin"] != vin:
            raise HTTPException(403, "대여 차량과 관측 VIN이 일치하지 않습니다.")
        if emulator_test and not booking["is_demo"]:
            raise HTTPException(403, "에뮬레이터 테스트 VIN은 테스트용 대여 건에서만 사용할 수 있습니다.")
        active = connection.execute("SELECT 1 FROM pairings WHERE booking_id=? AND revoked=0 AND user_id IS NOT NULL", (str(booking_id),)).fetchone()
        if not active and not (demo and booking["is_demo"]):
            raise HTTPException(409, "휴대전화에서 차량 연결을 승인해 주세요.")

