"""Independent NetherNet HTTP/UDP relay. DTLS stays between client and backend.

No RakNet listener, conversion or shared socket is used. This forwards transport
only: Navro packet checks are not applied to the encrypted NetherNet media.
"""
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import http.client
import ipaddress
import logging
import selectors
import signal
import socket
import threading
import time

LOG = logging.getLogger('nethernet')
MAX_SDP = 65536


def candidates(sdp):
    result = []
    for line in sdp.splitlines():
        if not line.startswith('a=candidate:'):
            continue
        words = line.split()
        if len(words) < 8 or words[1] != '1' or words[2].lower() != 'udp':
            continue
        try:
            ip = ipaddress.ip_address(words[4])
            port = int(words[5])
        except ValueError:
            continue
        if ip.version == 4 and not ip.is_unspecified and 0 < port < 65536:
            result.append((str(ip), port))
    return result


def rewrite_candidates(sdp, ip, port):
    """Only ICE addresses change; identity/fingerprint and ICE credentials stay intact."""
    lines = []
    inserted = False
    for line in sdp.splitlines():
        if line.startswith('a=candidate:'):
            words = line.split()
            if not inserted and len(words) >= 8 and words[1] == '1' and words[2].lower() == 'udp':
                lines.append(f'a=candidate:navro 1 UDP {words[3]} {ip} {port} typ host')
                inserted = True
            continue
        lines.append(line)
    if not inserted:
        raise ValueError('SDP has no supported UDP candidate')
    return '\r\n'.join(lines) + '\r\n'


class Session:
    def __init__(self, owner, peer_ip, destination_ip, offer):
        self.owner = owner
        self.peer_ip = peer_ip
        self.front = owner.allocate_media()
        self.back = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.back.bind(('127.0.0.1', 0))
        self.backend_peer = None
        self.backend_allowed_ips = {owner.backend_ip}
        self.peer = next((p for p in candidates(offer) if p[0] == peer_ip), None)
        self.peer_locked = False
        self.advertised_ip = owner.advertise or destination_ip
        self.pending = deque(maxlen=32)
        self.closed = threading.Event()
        self.close_lock = threading.Lock()
        self.last_activity = time.monotonic()
        self.packets = [0, 0]
        self.bytes = [0, 0]
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def answer(self, sdp):
        options = candidates(sdp)


        target = next((p for p in options if p[0] == self.owner.backend_ip), None)
        if target is None:



            for option in options:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as check:
                    try:
                        check.bind((option[0], 0))
                        target = option
                        break
                    except OSError:
                        continue
        if target is None:
            LOG.info('upstream_candidates %s', options)
            raise ValueError('Backend SDP has no verified local candidate')
        self.backend_peer = target
        self.backend_allowed_ips.add(target[0])
        return rewrite_candidates(sdp, self.advertised_ip, self.front.getsockname()[1])

    def run(self):
        selector = selectors.DefaultSelector()
        try:
            selector.register(self.front, selectors.EVENT_READ, 0)
            selector.register(self.back, selectors.EVENT_READ, 1)
            while not self.closed.is_set():
                if time.monotonic() - self.last_activity > self.owner.idle:
                    break
                for key, _ in selector.select(.5):
                    data, source = key.fileobj.recvfrom(65536)
                    direction = key.data
                    if direction == 0:
                        if source[0] != self.peer_ip or self.peer_locked and source != self.peer:
                            continue
                        self.peer = source
                        self.peer_locked = True
                        if self.backend_peer is None:
                            continue
                        self.back.sendto(data, self.backend_peer)
                        while self.pending:
                            self.front.sendto(self.pending.popleft(), self.peer)
                    else:
                        if source[0] not in self.backend_allowed_ips:
                            continue
                        if self.backend_peer is not None and source != self.backend_peer:
                            continue


                        if self.peer is None:
                            self.pending.append(data)
                        else:
                            self.front.sendto(data, self.peer)
                    self.last_activity = time.monotonic()
                    self.packets[direction] += 1
                    self.bytes[direction] += len(data)
        except OSError as error:
            if not self.closed.is_set():
                LOG.info('media_closed port=%d reason=%s', self.front.getsockname()[1], error)
        finally:
            selector.close()
            self.close()

    def close(self):
        with self.close_lock:
            if self.closed.is_set():
                return
            self.closed.set()
            port = self.front.getsockname()[1]
            self.front.close()
            self.back.close()
        self.owner.remove(self)
        LOG.info('session_closed media=%d packets=%s bytes=%s', port, self.packets, self.bytes)


class Relay(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, bind, backend, media, advertise='', idle=90):
        self.backend = backend
        self.backend_ip = socket.gethostbyname(backend[0])
        if not ipaddress.ip_address(self.backend_ip).is_loopback:
            raise ValueError('This relay currently supports a local NetherNet backend only')
        self.media = media
        self.advertise = advertise
        self.idle = idle
        self.sessions = set()
        self.lock = threading.Lock()
        super().__init__(bind, Handler)

    def allocate_media(self):
        for port in range(self.media[0], self.media[1] + 1):
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.bind(('0.0.0.0', port))
                return s
            except OSError:
                s.close()
        raise OSError('No independent NetherNet media ports available')

    def add(self, session):
        with self.lock:
            self.sessions.add(session)

    def remove(self, session):
        with self.lock:
            self.sessions.discard(session)

    def server_close(self):
        with self.lock:
            sessions = list(self.sessions)
        for session in sessions:
            session.close()
        super().server_close()


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def handle(self):
        self.connection.settimeout(15)
        try:
            prefix = self.connection.recv(2, socket.MSG_PEEK)
            if prefix == b'\x16\x03':
                self.connection.sendall(b'\x15\x03\x01\x00\x02\x02\x28')
                return
            super().handle()
        except (OSError, ValueError):
            return

    def log_message(self, fmt, *args):
        LOG.info('http peer=%s %s', self.client_address[0], fmt % args)

    def do_GET(self):
        self.forward(b'')

    def do_POST(self):
        if not self.path.startswith('/v1/join/'):
            self.send_error(404)
            return
        try:
            length = int(self.headers.get('Content-Length', '-1'))
            if length <= 0 or length > MAX_SDP:
                raise ValueError('Missing or oversized SDP')
            body = self.rfile.read(length)
            if len(body) != length:
                raise ValueError('Truncated SDP')
            offer = body.decode('utf-8')
            if not candidates(offer):
                raise ValueError('Offer has no supported UDP candidate')
            session = Session(self.server, self.client_address[0], self.connection.getsockname()[0], offer)
            self.server.add(session)
            rewritten = rewrite_candidates(offer, '127.0.0.1', session.back.getsockname()[1]).encode()
        except (ValueError, OSError) as error:
            self.send_error(400, str(error))
            return


        self.forward(rewritten, session)

    def forward(self, body, session=None):
        backend = http.client.HTTPConnection(*self.server.backend, timeout=25)
        try:
            headers = {k: v for k, v in self.headers.items()
                       if k.lower() not in {'host', 'connection', 'content-length', 'transfer-encoding', 'expect'}}
            headers['Content-Length'] = str(len(body))
            headers['Connection'] = 'close'
            backend.request(self.command, self.path, body=body, headers=headers)
            response = backend.getresponse()
            data = response.read(MAX_SDP + 1)
            if len(data) > MAX_SDP:
                raise ValueError('Oversized upstream response')
            if session is not None:
                if response.status != 200 or not data.startswith(b'v=0'):
                    session.close()
                else:
                    data = session.answer(data.decode()).encode()
                    LOG.info('session_ready peer=%s media=%d upstream=%s',
                             self.client_address[0], session.front.getsockname()[1], session.backend_peer)
            self.send_response_only(response.status, response.reason)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/sdp'))
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True
        except (OSError, ValueError, http.client.HTTPException) as error:
            if session is not None:
                session.close()
            LOG.info('upstream_error %s', error)
            self.send_error(502, 'NetherNet upstream failed')
        finally:
            backend.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--listen', default='0.0.0.0:19136')
    parser.add_argument('--backend', default='127.0.0.1:19134')
    parser.add_argument('--media', default='19140-19203')
    parser.add_argument('--advertise', default='')
    args = parser.parse_args()
    listen_host, listen_port = args.listen.rsplit(':', 1)
    back_host, back_port = args.backend.rsplit(':', 1)
    low, high = map(int, args.media.split('-'))
    if not 0 < low <= high < 65536:
        parser.error('Invalid media port range')
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(message)s')
    relay = Relay((listen_host, int(listen_port)), (back_host, int(back_port)), (low, high), args.advertise)
    for sig in [signal.SIGINT, signal.SIGTERM]:
        signal.signal(sig, lambda *_: threading.Thread(target=relay.shutdown, daemon=True).start())
    LOG.info('NETHERNET_RELAY_READY signaling=%s media=%s backend=%s encrypted_transport_only=true',
             args.listen, args.media, args.backend)
    try:
        relay.serve_forever()
    finally:
        relay.server_close()


if __name__ == '__main__':
    main()
