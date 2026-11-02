"""Competition backend: durable rental records and scoped phone photo uploads.

No payment, fleet-management UI, or public deployment is included.
"""
from __future__ import annotations

from return_battery import battery_summary

import base64
import hashlib
import io
import json
import re
import secrets
import sqlite3
import time
import warnings
from contextlib import contextmanager
from pathlib import Path
from typing import Literal
from uuid import UUID, uuid4
from urllib.parse import urlsplit

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.exceptions import RequestValidationError
from PIL import Image, ImageOps, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, model_validator
from accounts import Accounts

CATEGORIES = ("windows", "belongings", "exterior")
EMULATOR_TEST_SOURCE = "EMULATOR_TEST_VIN · Pleos Vehicle SDK 2.3.2"
VIN_PATTERN = re.compile(r"^[A-HJ-NPR-Z0-9]{17}$")
Image.MAX_IMAGE_PIXELS = 20_000_000


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Reading(StrictModel):
    key: str = Field(min_length=1, max_length=160)
    label: str = Field(max_length=256)
    status: Literal["AVAILABLE", "UNSUPPORTED", "UNAVAILABLE", "PERMISSION_DENIED", "TIMED_OUT", "ERROR"]
    value: str | None = Field(default=None, max_length=1024)
    displayValue: str = Field(max_length=1024)
    unit: str | None = Field(default=None, max_length=32)
    detail: str = Field(default="", max_length=4096)
    observedAtMillis: int = Field(default=0, ge=0)


class Snapshot(StrictModel):
    startedAtMillis: int = Field(ge=0)
    completedAtMillis: int = Field(ge=0)
    source: str = Field(max_length=128)
    readings: list[Reading] = Field(max_length=600)

    @model_validator(mode="after")
    def check_interval(self):
        if self.completedAtMillis < self.startedAtMillis:
            raise ValueError("수집 시각 순서가 올바르지 않습니다.")
        keys = [r.key for r in self.readings]
        if len(keys) != len(set(keys)):
            raise ValueError("중복된 수집 항목입니다.")
        return self

    def vin(self) -> str:
        field = next((r for r in self.readings if r.key == "vehicle.vin"), None)
        value = (field.value or "").strip().upper() if field and field.status == "AVAILABLE" else ""
        if not VIN_PATTERN.fullmatch(value):
            raise HTTPException(422, "차량에서 수신한 올바른 VIN이 필요합니다.")
        return value


class LuggageMemo(StrictModel):
    space: Literal["FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT", "TRUNK"]
    item: str = Field(min_length=1, max_length=40)

    @model_validator(mode="after")
    def reviewed_item(self):
        # Accept only the bounded, reviewed item name; reject control characters and blank text rather than retaining raw speech.
        if self.item != self.item.strip() or not self.item.strip() or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in self.item):
            raise ValueError("물건 이름을 공백과 제어 문자 없이 확인해 주세요.")
        return self


class UsageMemory(StrictModel):
    openedSpaces: list[Literal["FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT", "TRUNK"]] = Field(default_factory=list, max_length=5)
    markedSpaces: list[Literal["FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT", "TRUNK"]] = Field(default_factory=list, max_length=5)
    monitoringGaps: bool = True
    luggageMemos: list[LuggageMemo] = Field(default_factory=list, max_length=5)
    collectedSpaces: list[Literal["FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT", "TRUNK"]] = Field(default_factory=list, max_length=5)
    luggageReviewRequired: bool = False

    @model_validator(mode="after")
    def unique_spaces(self):
        # Keep observations, manual marks and reviewed item notes distinct, with at most one item note per supported location.
        if len(self.openedSpaces) != len(set(self.openedSpaces)) or len(self.markedSpaces) != len(set(self.markedSpaces)):
            raise ValueError("사용 공간은 중복 없이 기록해야 합니다.")
        if len(self.luggageMemos) != len({memo.space for memo in self.luggageMemos}):
            raise ValueError("같은 장소의 물건 메모는 하나로 정리해 주세요.")
        if len(self.collectedSpaces) != len(set(self.collectedSpaces)) or not set(self.collectedSpaces) <= {m.space for m in self.luggageMemos}:
            raise ValueError("챙김 확인은 등록한 짐 메모의 장소만 지정할 수 있습니다.")
        return self


class RentalUpload(StrictModel):
    id: UUID
    revision: int = Field(ge=1, le=1_000_000)
    pickup: Snapshot
    returned: Snapshot | None = None
    pickupWindowManuallyChecked: bool = False
    returnWindowManuallyChecked: bool = False
    bookingId: UUID | None = None
    usageMemory: UsageMemory = Field(default_factory=UsageMemory)


class LinkRequest(StrictModel):
    phase: Literal["pickup", "return"] = "return"


class PhotoUpload(StrictModel):
    category: Literal["windows", "belongings", "exterior"]
    requestId: UUID
    imageBase64: str = Field(min_length=4, max_length=11_200_000)


class Checklist(StrictModel):
    collectedSpaces: list[Literal["FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT", "TRUNK"]] = Field(default_factory=list, max_length=5)
    windows: bool = False
    belongings: bool = False
    exterior: bool = False
    note: str = Field(default="", max_length=500)


class SubmitRequest(StrictModel):
    revision: int = Field(ge=1)


class BodyLimit:
    def __init__(self, app, limit=12 * 1024 * 1024):
        self.app, self.limit = app, limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            body.extend(message.get("body", b""))
            if len(body) > self.limit:
                return await JSONResponse({"detail": "요청 크기가 너무 큽니다."}, 413)(scope, receive, send)
            if not message.get("more_body", False):
                break
        sent = False
        async def replay():
            nonlocal sent
            if not sent:
                sent = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()
        await self.app(scope, replay, send)


def canonical(value) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def normalize_photo(encoded: str) -> bytes:
    try:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 8 * 1024 * 1024:
            raise HTTPException(413, "사진은 8MB 이하여야 합니다.")
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as image:
                if image.format not in {"JPEG", "PNG", "WEBP"}:
                    raise ValueError("unsupported image")
                image.verify()
            with Image.open(io.BytesIO(raw)) as image:
                # Re-encode pixels only; original EXIF/GPS and filenames are not retained.
                pixels = ImageOps.exif_transpose(image).convert("RGB")
                pixels.thumbnail((2048, 2048))
                clean = Image.new("RGB", pixels.size)
                clean.paste(pixels)
                output = io.BytesIO()
                clean.save(output, "JPEG", quality=88)
                return output.getvalue()
    except HTTPException:
        raise
    except (ValueError, OSError, UnidentifiedImageError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise HTTPException(422, "유효한 JPEG·PNG·WebP 사진을 선택해 주세요.") from None


def create_app(data_dir: Path, device_token: str, public_base_url: str, clock=time.time, mailer=None, allow_emulator_test_vin=False) -> FastAPI:
    # Keep emulator identity records behind a server-side opt-in while retaining the normal account, VIN and submission checks.
    if len(device_token) < 32:
        raise ValueError("RENTAL_DEVICE_TOKEN은 임의의 32자 이상 값으로 설정하세요.")
    parsed = urlsplit(public_base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise ValueError("PUBLIC_BASE_URL은 접근 가능한 http(s) 서버 원점이어야 합니다.")
    data_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    database = data_dir / "rentals.sqlite3"
    @contextmanager
    def db():
        connection = sqlite3.connect(database, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    with db() as connection:
        connection.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS rentals (
                id TEXT PRIMARY KEY, revision INTEGER NOT NULL, payload TEXT NOT NULL,
                submitted_at INTEGER, receipt_id TEXT);
            CREATE TABLE IF NOT EXISTS grants (
                hash TEXT PRIMARY KEY, rental_id TEXT NOT NULL REFERENCES rentals(id),
                phase TEXT NOT NULL, expires_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS checks (
                rental_id TEXT NOT NULL REFERENCES rentals(id), phase TEXT NOT NULL,
                payload TEXT NOT NULL, PRIMARY KEY(rental_id, phase));
            CREATE TABLE IF NOT EXISTS photos (
                id TEXT PRIMARY KEY, rental_id TEXT NOT NULL REFERENCES rentals(id),
                phase TEXT NOT NULL, category TEXT NOT NULL, request_id TEXT NOT NULL,
                sha256 TEXT NOT NULL, pixels BLOB NOT NULL, created_at INTEGER NOT NULL,
                UNIQUE(rental_id, phase, request_id));
        """)
    database.chmod(0o600)
    app = FastAPI(title="Rental Check", docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(BodyLimit)
    static = Path(__file__).parent / "web"
    millis = lambda: int(clock() * 1000)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        # Pydantic's default error can echo uploaded content; omit raw inputs.
        return JSONResponse({"detail": "요청 형식을 확인해 주세요.",
            "errors": [{"loc": e["loc"], "type": e["type"]} for e in error.errors()]}, status_code=422)

    @app.middleware("http")
    async def response_headers(request, call_next):
        response = await call_next(request)
        response.headers.update({"Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY",
            "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"})
        return response

    def authorize(authorization: str | None = Header(default=None)):
        if not secrets.compare_digest((authorization or "").encode(), ("Bearer " + device_token).encode()):
            raise HTTPException(401, "차량 앱 인증이 필요합니다.")

    def find_grant(authorization: str | None, receipt_only: bool = False) -> dict:
        token = (authorization or "").removeprefix("Bearer ")
        digest = hashlib.sha256(token.encode()).hexdigest()
        with db() as connection:
            row = connection.execute("SELECT g.*, r.submitted_at FROM grants g JOIN rentals r ON r.id=g.rental_id WHERE g.hash=?", (digest,)).fetchone()
        completed_receipt = row and receipt_only and row["submitted_at"] and row["phase"] == "return"
        if not row or row["expires_at"] <= millis() or ((row["revoked"] or row["submitted_at"]) and not completed_receipt):
            raise HTTPException(401, "사진 전송 링크가 만료됐습니다. 차량 화면에서 새 링크를 발급해 주세요.")
        return dict(row)

    def upload_grant(authorization: str | None = Header(default=None), x_account_token: str | None = Header(default=None)):
        grant = find_grant(authorization)
        accounts.ensure_owner(grant, x_account_token)
        return grant

    def receipt_grant(authorization: str | None = Header(default=None), x_account_token: str | None = Header(default=None)):
        grant = find_grant(authorization, receipt_only=True)
        accounts.ensure_owner(grant, x_account_token)
        return grant

    def rental(connection, rental_id: str):
        row = connection.execute("SELECT * FROM rentals WHERE id=?", (rental_id,)).fetchone()
        if not row:
            raise HTTPException(404, "이용 기록을 찾지 못했습니다.")
        return row

    def summary(connection, rental_id: str, phase: str | None = None):
        # Expose saved observations and explicit test-identity provenance together so clients never mistake a test receipt for real VIN capture.
        row = rental(connection, rental_id)
        payload = json.loads(row["payload"])
        photo_rows = connection.execute("SELECT id, phase, category, sha256, created_at FROM photos WHERE rental_id=? ORDER BY created_at,id", (rental_id,)).fetchall()
        checks = {r["phase"]: json.loads(r["payload"]) for r in connection.execute("SELECT phase,payload FROM checks WHERE rental_id=?", (rental_id,))}
        memory = UsageMemory.model_validate(payload.get("usageMemory", {}))
        checks.setdefault("return", {**Checklist().model_dump(), "collectedSpaces": memory.collectedSpaces})
        checks["return"].setdefault("collectedSpaces", memory.collectedSpaces)
        vin = Snapshot.model_validate(payload["pickup"]).vin()
        return {"id": rental_id, "revision": row["revision"], "vinSuffix": vin[-6:],
            "isDemo": payload["pickup"]["source"] in {"DEMO · 합성 차량 데이터", EMULATOR_TEST_SOURCE},
            "usesTestVin": payload["pickup"]["source"] == EMULATOR_TEST_SOURCE,
            "usageMemory": memory.model_dump(), "batteryReturn": battery_summary(payload),
            "submittedAtMillis": row["submitted_at"], "receiptId": row["receipt_id"],
            "photos": [{"id": p["id"], "phase": p["phase"], "category": p["category"], "sha256": p["sha256"], "createdAtMillis": p["created_at"]} for p in photo_rows if phase is None or p["phase"] == phase],
            "pickupPhotos": [{"id": p["id"], "phase": p["phase"], "category": p["category"]} for p in photo_rows if p["phase"] == "pickup"] if phase == "return" else [],
            "checks": checks if phase is None else checks.get(phase, Checklist().model_dump())}

    accounts = Accounts(app, db, millis, authorize, public_base_url, summary, mailer)

    @app.get("/")
    @app.get("/account")
    def account_page():
        return FileResponse(static / "account.html")

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.get("/upload")
    def upload_page():
        return FileResponse(static / "upload.html")

    @app.get("/static/{name}")
    def asset(name: str):
        if name not in {"upload.js", "upload.css", "account.js", "account.css"}:
            raise HTTPException(404)
        return FileResponse(static / name)

    @app.put("/api/rentals/{rental_id}", dependencies=[Depends(authorize)])
    def upsert(rental_id: UUID, body: RentalUpload):
        # Authorize the explicitly labelled emulator identity without allowing it to bypass vehicle pairing or alter a real rental's identity.
        if rental_id != body.id:
            raise HTTPException(422, "이용 번호가 일치하지 않습니다.")
        emulator_test = body.pickup.source == EMULATOR_TEST_SOURCE
        if emulator_test and not allow_emulator_test_vin:
            raise HTTPException(403, "이 서버에서는 에뮬레이터 테스트 VIN 기록을 허용하지 않습니다.")
        if body.returned and emulator_test != (body.returned.source == EMULATOR_TEST_SOURCE):
            raise HTTPException(409, "실제 VIN 기록과 에뮬레이터 테스트 VIN 기록을 혼합할 수 없습니다.")
        vin = body.pickup.vin()
        if body.returned and (vin != body.returned.vin() or body.returned.startedAtMillis < body.pickup.completedAtMillis):
            raise HTTPException(409, "차량 VIN 또는 인수·반납 수집 시각이 일치하지 않습니다.")
        identifier, payload = str(rental_id), body.model_dump(mode="json")
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            old = connection.execute("SELECT * FROM rentals WHERE id=?", (identifier,)).fetchone()
            if old:
                previous = json.loads(old["payload"])
                previous.setdefault("bookingId", old["booking_id"])
                previous.setdefault("usageMemory", UsageMemory().model_dump())
                # Normalize additive defaults for idempotent old-client retries without mutating the saved historical payload.
                previous["usageMemory"] = UsageMemory.model_validate(previous["usageMemory"]).model_dump()
                if canonical(payload) == canonical(previous):
                    return summary(connection, identifier)
                if old["submitted_at"]:
                    raise HTTPException(409, "제출된 점검 기록은 변경할 수 없습니다.")
                if body.revision <= old["revision"] or canonical(payload["pickup"]) != canonical(previous["pickup"]):
                    raise HTTPException(409, "이전 버전 또는 인수 기준 변경 요청입니다.")
                if previous["returned"] and not payload["returned"]:
                    raise HTTPException(409, "반납 관측 기록을 제거할 수 없습니다.")
                if previous["returned"] and payload["usageMemory"] != previous["usageMemory"]:
                    raise HTTPException(409, "반납 저장 뒤 사용 공간 기록을 변경할 수 없습니다.")
                if old["booking_id"] and old["booking_id"] != payload["bookingId"]:
                    raise HTTPException(409, "점검 기록의 대여 건을 변경할 수 없습니다.")
                accounts.validate_booking(connection, body.bookingId, vin, body.pickup.source == "DEMO · 합성 차량 데이터", emulator_test)
                conflict = connection.execute("SELECT id FROM rentals WHERE booking_id=? AND id<>?", (payload["bookingId"], identifier)).fetchone()
                if conflict:
                    raise HTTPException(409, "이미 점검 기록이 있는 대여 건입니다.")
                connection.execute("UPDATE rentals SET revision=?,payload=?,booking_id=? WHERE id=?", (body.revision, canonical(payload), payload["bookingId"], identifier))
            else:
                accounts.validate_booking(connection, body.bookingId, vin, body.pickup.source == "DEMO · 합성 차량 데이터", emulator_test)
                if connection.execute("SELECT 1 FROM rentals WHERE booking_id=?", (payload["bookingId"],)).fetchone():
                    raise HTTPException(409, "이미 점검 기록이 있는 대여 건입니다.")
                connection.execute("INSERT INTO rentals(id,revision,payload,booking_id) VALUES(?,?,?,?)", (identifier, body.revision, canonical(payload), payload["bookingId"]))
            return summary(connection, identifier)

    @app.get("/api/rentals/{rental_id}", dependencies=[Depends(authorize)])
    def get_rental(rental_id: UUID):
        with db() as connection:
            return summary(connection, str(rental_id))

    @app.post("/api/rentals/{rental_id}/photo-links", dependencies=[Depends(authorize)])
    def link(rental_id: UUID, body: LinkRequest):
        identifier, token, expires = str(rental_id), secrets.token_urlsafe(32), millis() + 15 * 60 * 1000
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = rental(connection, identifier)
            if not row["booking_id"]:
                raise HTTPException(409, "먼저 계정과 대여 건을 연결해 주세요.")
            if row["submitted_at"] or (body.phase == "return" and not json.loads(row["payload"])["returned"]):
                raise HTTPException(409, "사진 첨부가 가능한 점검 단계가 아닙니다.")
            connection.execute("UPDATE grants SET revoked=1 WHERE rental_id=? AND phase=?", (identifier, body.phase))
            connection.execute("INSERT INTO grants(hash,rental_id,phase,expires_at) VALUES(?,?,?,?)", (hashlib.sha256(token.encode()).hexdigest(), identifier, body.phase, expires))
        return {"uploadUrl": public_base_url.rstrip("/") + "/upload#token=" + token, "expiresAtMillis": expires, "phase": body.phase}

    @app.get("/api/upload/session")
    def phone_session(grant=Depends(upload_grant)):
        with db() as connection:
            result = summary(connection, grant["rental_id"], grant["phase"])
            return {**result, "phase": grant["phase"], "expiresAtMillis": grant["expires_at"]}

    @app.post("/api/account/rentals/{rental_id}/photo-links")
    def owner_photo_link(rental_id: UUID, body: LinkRequest, user=Depends(accounts.user)):
        with db() as connection:
            accounts.owned_rental(connection, str(rental_id), user["id"])
        return link(rental_id, body)

    @app.post("/api/upload/photos")
    def upload(body: PhotoUpload, grant=Depends(upload_grant)):
        pixels = normalize_photo(body.imageBase64)
        digest = hashlib.sha256(pixels).hexdigest()
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = rental(connection, grant["rental_id"])
            # Recheck after decoding, inside the same transaction as photo insertion.
            active = connection.execute("SELECT * FROM grants WHERE hash=?", (grant["hash"],)).fetchone()
            if row["submitted_at"] or active["revoked"] or active["expires_at"] <= millis():
                raise HTTPException(409, "전송 링크가 종료됐습니다.")
            previous = connection.execute("SELECT id,sha256 FROM photos WHERE rental_id=? AND phase=? AND request_id=?", (grant["rental_id"], grant["phase"], str(body.requestId))).fetchone()
            if previous:
                if previous["sha256"] != digest:
                    raise HTTPException(409, "같은 요청 번호에 다른 사진을 보낼 수 없습니다.")
                return {"photoId": previous["id"]}
            count = connection.execute("SELECT count(*) FROM photos WHERE rental_id=? AND phase=? AND category=?", (grant["rental_id"], grant["phase"], body.category)).fetchone()[0]
            if count >= 5:
                raise HTTPException(409, "항목별 사진은 최대 5장입니다.")
            identifier = str(uuid4())
            connection.execute("INSERT INTO photos VALUES(?,?,?,?,?,?,?,?)", (identifier, grant["rental_id"], grant["phase"], body.category, str(body.requestId), digest, pixels, millis()))
        return {"photoId": identifier}

    @app.put("/api/upload/checks")
    def save_checks(body: Checklist, grant=Depends(upload_grant)):
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = rental(connection, grant["rental_id"])
            active = connection.execute("SELECT * FROM grants WHERE hash=?", (grant["hash"],)).fetchone()
            if row["submitted_at"] or active["revoked"] or active["expires_at"] <= millis():
                raise HTTPException(409, "전송 링크가 종료됐습니다.")
            # Only the current rental's reviewed notes may be acknowledged; this cannot claim sensor detection or another booking's items.
            memory = UsageMemory.model_validate(json.loads(row["payload"]).get("usageMemory", {}))
            if len(body.collectedSpaces) != len(set(body.collectedSpaces)) or not set(body.collectedSpaces) <= {m.space for m in memory.luggageMemos}:
                raise HTTPException(422, "이 예약에 등록된 짐 메모만 확인해 주세요.")
            connection.execute("INSERT INTO checks VALUES(?,?,?) ON CONFLICT(rental_id,phase) DO UPDATE SET payload=excluded.payload", (grant["rental_id"], grant["phase"], canonical(body.model_dump())))
        return {"saved": True}

    @app.get("/api/upload/photos/{photo_id}")
    def photo(photo_id: UUID, grant=Depends(upload_grant)):
        with db() as connection:
            row = connection.execute("SELECT pixels FROM photos WHERE id=? AND rental_id=? AND phase=?", (str(photo_id), grant["rental_id"], grant["phase"])).fetchone()
        if not row:
            raise HTTPException(404)
        return Response(bytes(row["pixels"]), media_type="image/jpeg")

    @app.delete("/api/upload/photos/{photo_id}")
    def remove_photo(photo_id: UUID, grant=Depends(upload_grant)):
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if rental(connection, grant["rental_id"])["submitted_at"]:
                raise HTTPException(409, "이미 제출된 기록입니다.")
            active = connection.execute("SELECT * FROM grants WHERE hash=?", (grant["hash"],)).fetchone()
            if active["revoked"] or active["expires_at"] <= millis():
                raise HTTPException(401, "전송 링크가 종료됐습니다.")
            changed = connection.execute("DELETE FROM photos WHERE id=? AND rental_id=? AND phase=?", (str(photo_id), grant["rental_id"], grant["phase"])).rowcount
            if not changed:
                raise HTTPException(404)
        return {"deleted": True}

    def finalize(identifier: str, revision: int, grant: dict | None = None):
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = rental(connection, identifier)
            if revision != row["revision"]:
                raise HTTPException(409, "최신 차량 관측 기록을 먼저 전송해 주세요.")
            if row["submitted_at"]:
                return summary(connection, identifier)
            if grant is not None:
                active = connection.execute("SELECT * FROM grants WHERE hash=? AND rental_id=?", (grant["hash"], identifier)).fetchone()
                if not active or active["phase"] != "return" or active["revoked"] or active["expires_at"] <= millis():
                    raise HTTPException(401, "제출 링크가 종료됐습니다.")
            payload = RentalUpload.model_validate_json(row["payload"])
            if not payload.returned or payload.pickup.vin() != payload.returned.vin():
                raise HTTPException(409, "같은 차량의 인수·반납 기록이 필요합니다.")
            checked = connection.execute("SELECT payload FROM checks WHERE rental_id=? AND phase='return'", (identifier,)).fetchone()
            checks = json.loads(checked[0]) if checked else {}
            # New reviewed-note records require explicit per-item confirmation; legacy clients keep their original submission contract.
            collected = checks.get("collectedSpaces", payload.usageMemory.collectedSpaces)
            if payload.usageMemory.luggageReviewRequired and not {m.space for m in payload.usageMemory.luggageMemos} <= set(collected):
                raise HTTPException(409, "저장한 짐을 항목별로 챙겼는지 확인해 주세요.")
            counts = {r[0]: r[1] for r in connection.execute("SELECT category,count(*) FROM photos WHERE rental_id=? AND phase='return' GROUP BY category", (identifier,))}
            if any(not checks.get(c) or counts.get(c, 0) < 1 for c in CATEGORIES):
                raise HTTPException(409, "창문·소지품·외관의 사진과 직접 확인 내역이 모두 필요합니다.")
            connection.execute("UPDATE rentals SET submitted_at=?,receipt_id=? WHERE id=?", (millis(), str(uuid4()), identifier))
            connection.execute("UPDATE grants SET revoked=1 WHERE rental_id=?", (identifier,))
            connection.execute("UPDATE pairings SET revoked=1 WHERE booking_id=?", (row["booking_id"],))
            return summary(connection, identifier)

    @app.post("/api/rentals/{rental_id}/submit", dependencies=[Depends(authorize)])
    def submit(rental_id: UUID, body: SubmitRequest):
        return finalize(str(rental_id), body.revision)

    @app.post("/api/upload/submit")
    def phone_submit(body: SubmitRequest, grant=Depends(receipt_grant)):
        if grant["phase"] != "return":
            raise HTTPException(403, "인수 사진 링크로 반납 점검을 제출할 수 없습니다.")
        result = finalize(grant["rental_id"], body.revision, grant)
        return {key: result[key] for key in ("id", "revision", "submittedAtMillis", "receiptId")}

    return app
