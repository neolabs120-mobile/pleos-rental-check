"""An informational return target from the saved pickup reading, never a fabricated charge level or billing decision."""
import math


# Accept only actual successful battery readings in range; absent, failed or non-finite data cannot define a return target.
def percent(snapshot):
    reading = next((r for r in (snapshot or {}).get("readings", []) if r.get("key") == "battery.percent"), None)
    try:
        value = float(reading["value"]) if reading and reading.get("status") == "AVAILABLE" else None
        return value if value is not None and math.isfinite(value) and 0 <= value <= 100 else None
    except (ValueError, TypeError, KeyError):
        return None


# Derive the baseline exclusively from immutable pickup evidence; clients cannot submit their preferred target percentage.
def battery_summary(payload):
    target, current = percent(payload.get("pickup")), percent(payload.get("returned"))
    return dict(targetPercent=target, returnPercent=current,
        shortfallPercent=round(max(0, target - current), 3) if target is not None and current is not None else None)
