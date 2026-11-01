"""Operator-issued return destinations; vehicle positions are never collected here."""
from pydantic import BaseModel, ConfigDict, Field, field_validator


class ReturnLocation(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    name: str = Field(min_length=1, max_length=80)
    address: str = Field(min_length=1, max_length=200)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    instructions: str = Field(default="", max_length=400)
    isTest: bool = False

    @field_validator("name", "address")
    @classmethod
    def nonblank(cls, value):
        # Reject empty labels so a driver always sees an identifiable destination before approving navigation.
        if not value.strip():
            raise ValueError("반납 장소명과 주소를 입력해 주세요.")
        return value.strip()
