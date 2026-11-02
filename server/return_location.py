"""Operator-issued return destinations; vehicle positions are never collected here."""
import base64
import io
from PIL import Image, ImageOps
from pydantic import BaseModel, ConfigDict, Field, field_validator


class ParkingStep(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=1, max_length=60)
    detail: str = Field(min_length=1, max_length=300)
    imageBase64: str | None = Field(default=None, max_length=400_000)

    @field_validator("title", "detail")
    @classmethod
    def text(cls, value):
        # Require readable operator instructions; a step without an identifiable action cannot guide a first-time visitor.
        if not value.strip() or any(ord(c) < 32 and c not in "\n\t" for c in value):
            raise ValueError("주차 안내 내용을 확인해 주세요.")
        return value.strip()

    @field_validator("imageBase64")
    @classmethod
    def photo(cls, value):
        # Decode bounded raster images locally and strip metadata, preventing remote tracking URLs and oversized or active image content.
        if value is None:
            return None
        try:
            raw = base64.b64decode(value, validate=True)
            with Image.open(io.BytesIO(raw)) as image:
                if image.format not in {"JPEG", "PNG", "WEBP"} or image.width * image.height > 4_000_000:
                    raise ValueError()
                image = ImageOps.exif_transpose(image).convert("RGB")
                image.thumbnail((1024, 1024))
                output = io.BytesIO(); image.save(output, format="JPEG", quality=82)
            return base64.b64encode(output.getvalue()).decode()
        except Exception as error:
            raise ValueError("안내 사진은 300KB 이하의 JPEG·PNG·WebP로 등록해 주세요.") from error


class ChargingStop(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    name: str = Field(min_length=1, max_length=80)
    address: str = Field(min_length=1, max_length=200)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)

    @field_validator("name", "address")
    @classmethod
    def text(cls, value):
        # A driver must explicitly recognize the operator-supplied charging waypoint before it changes their route.
        if not value.strip():
            raise ValueError("충전소명과 주소가 필요합니다.")
        return value.strip()


class ReturnLocation(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    name: str = Field(min_length=1, max_length=80)
    address: str = Field(min_length=1, max_length=200)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    instructions: str = Field(default="", max_length=400)
    isTest: bool = False
    parkingSteps: list[ParkingStep] = Field(default_factory=list, max_length=4)
    chargingStops: list[ChargingStop] = Field(default_factory=list, max_length=3)

    @field_validator("name", "address")
    @classmethod
    def nonblank(cls, value):
        # Reject empty labels so a driver always sees an identifiable destination before approving navigation.
        if not value.strip():
            raise ValueError("반납 장소명과 주소를 입력해 주세요.")
        return value.strip()
