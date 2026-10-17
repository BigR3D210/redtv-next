import unittest
from redtv_receiver import normalize_address, vod_target


class ReceiverTests(unittest.TestCase):
    def test_dynamic_port(self):
        self.assertEqual(normalize_address('192.168.1.249:43123'), 'http://192.168.1.249:43123')
        self.assertEqual(normalize_address('192.168.1.249'), 'http://192.168.1.249:8091')

    def test_reject_non_lan_and_credentials(self):
        for address in ['https://192.168.1.2', '8.8.8.8', 'localhost', 'http://user@192.168.1.2', '192.168.1.2/path']:
            with self.assertRaises(ValueError):
                normalize_address(address)

    def test_vod_clock_uses_host_clock(self):
        state = {'positionMs': 10000, 'capturedAtEpochMs': 5000, 'playing': True}
        self.assertEqual(vod_target(state, 6000, 200), 11100)
        state['playing'] = False
        self.assertEqual(vod_target(state, 6000, 200), 10000)

if __name__ == '__main__':
    unittest.main()
