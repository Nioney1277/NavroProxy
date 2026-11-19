"""Real HTTP/UDP boundary tests. The external backend supplies SDP and opaque
media; the production relay performs all rewrites, allocations and forwarding.
These tests do not claim to emulate WebRTC or validate real Minecraft login.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import socket
import threading
import unittest

from relay import Relay, candidates


def sdp(ip, port):
    return ('v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n'
            'm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n'
            'a=ice-ufrag:recorded-ufrag\r\na=ice-pwd:recorded-password\r\n'
            'a=fingerprint:sha-256 AA:BB:CC:DD\r\n'
            'a=identity:opaque-signed-assertion\r\n'
            f'a=candidate:one 1 UDP 2122260223 {ip} {port} typ host\r\n'
            'a=end-of-candidates\r\n')


class RelayIntegrationTest(unittest.TestCase):
    def test_http_rewrite_preserves_crypto_and_both_udp_directions_without_any_raknet(self):
        backend_udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        backend_udp.bind(('127.0.0.1', 0))
        backend_udp.settimeout(3)
        received = []
        class Backend(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append(self.rfile.read(int(self.headers['Content-Length'])).decode())
                answer = sdp(*backend_udp.getsockname()).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(answer)))
                self.send_header('Content-Type', 'application/sdp')
                self.end_headers()
                self.wfile.write(answer)
            def log_message(self, *_): pass
        upstream = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
        relay = Relay(('127.0.0.1', 0), upstream.server_address, (0, 0), idle=5)
        for server in [upstream, relay]:
            threading.Thread(target=server.serve_forever, daemon=True).start()
        client_udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client_udp.bind(('127.0.0.1', 0))
        client_udp.settimeout(3)
        try:

            with socket.create_connection(relay.server_address, timeout=3) as c:
                c.sendall(b'\x16\x03\x01\x00\x00')
                self.assertEqual(b'\x15\x03\x01\x00\x02\x02\x28', c.recv(7))
            offer = sdp(*client_udp.getsockname())
            conn = http.client.HTTPConnection(*relay.server_address, timeout=5)
            conn.request('POST', '/v1/join/123456', offer.encode(), {'Content-Type': 'application/sdp'})
            response = conn.getresponse()
            self.assertEqual(200, response.status)
            answer = response.read().decode()
            conn.close()
            self.assertEqual(1, len(received))
            for line in ['a=ice-ufrag:recorded-ufrag', 'a=ice-pwd:recorded-password',
                         'a=fingerprint:sha-256 AA:BB:CC:DD', 'a=identity:opaque-signed-assertion']:
                self.assertIn(line + '\r\n', received[0])
                self.assertIn(line + '\r\n', answer)
            front = candidates(answer)[0]
            back = candidates(received[0])[0]
            self.assertNotEqual(client_udp.getsockname(), back)
            self.assertNotEqual(backend_udp.getsockname(), front)
            payload = b'\x17\xfe\xfd\x00opaque-dtls-media\xff'
            client_udp.sendto(payload, front)
            data, source = backend_udp.recvfrom(65536)
            self.assertEqual(payload, data)
            self.assertEqual(back, source)
            reply = b'\x17\xfe\xfd\x01backend-opaque-media'
            backend_udp.sendto(reply, source)
            data, source = client_udp.recvfrom(65536)
            self.assertEqual(reply, data)
            self.assertEqual(front, source)
            self.assertEqual(1, len(relay.sessions))
        finally:
            client_udp.close()
            backend_udp.close()
            for server in [relay, upstream]:
                server.shutdown()
                server.server_close()


if __name__ == '__main__':
    unittest.main()
