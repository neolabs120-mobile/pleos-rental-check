"""Bound operator guide images and preserve unknown battery states independently of client displays."""
import base64
import io
import unittest
from PIL import Image
from pydantic import ValidationError
from return_location import ReturnLocation
from return_battery import battery_summary


class PreparationTests(unittest.TestCase):
    def test_embedded_guide_strips_metadata_and_rejects_remote_or_oversized_images(self):
        # Operator images are sanitized bounded pixels, so photos cannot carry camera metadata or trigger arbitrary network requests.
        raw = io.BytesIO(); exif = Image.Exif(); exif[270] = 'private metadata'
        Image.new('RGB', (24, 18), 'teal').save(raw, 'JPEG', exif=exif)
        body = dict(name='반납', address='주소', latitude=37.4, longitude=127,
            parkingSteps=[dict(title='입구', detail='등록된 입구로 진입', imageBase64=base64.b64encode(raw.getvalue()).decode())])
        guide = ReturnLocation.model_validate(body)
        pixels = base64.b64decode(guide.parkingSteps[0].imageBase64)
        with Image.open(io.BytesIO(pixels)) as clean: self.assertFalse(clean.getexif())
        for invalid in ['https://example.test/tracking.jpg', 'x' * 400_001]:
            body['parkingSteps'][0]['imageBase64'] = invalid
            with self.assertRaises(ValidationError): ReturnLocation.model_validate(body)

    def test_bounded_valid_operator_steps_and_charging_locations(self):
        # A legacy location stays usable while new optional guide metadata rejects impossible coordinates and unlimited route additions.
        body = dict(name='반납', address='주소', latitude=37.4, longitude=127)
        self.assertEqual([], ReturnLocation.model_validate(body).parkingSteps)
        body['chargingStops'] = [dict(name='충전소', address='주소', latitude=91, longitude=127)]
        with self.assertRaises(ValidationError): ReturnLocation.model_validate(body)
        body['chargingStops'] = [dict(name='충전소', address='주소', latitude=37, longitude=127)] * 4
        with self.assertRaises(ValidationError): ReturnLocation.model_validate(body)

    def test_missing_and_failed_battery_are_not_zero(self):
        # Unsupported vehicles must not appear to need a full recharge or to satisfy a zero-percent return target.
        for value, status in [('NaN','AVAILABLE'),('101','AVAILABLE'),('0','UNAVAILABLE'),(None,'AVAILABLE')]:
            payload = dict(pickup={'readings':[dict(key='battery.percent',value=value,status=status)]}, returned=None)
            self.assertEqual(dict(targetPercent=None,returnPercent=None,shortfallPercent=None),battery_summary(payload))


if __name__ == '__main__': unittest.main()
