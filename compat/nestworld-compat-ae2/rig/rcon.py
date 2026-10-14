import socket, struct, sys
def pkt(i, t, b): b = b.encode() + b'\x00\x00'; return struct.pack('<iii', len(b) + 8, i, t) + b
def rd(s):
    n = struct.unpack('<i', s.recv(4))[0]; d = b''
    while len(d) < n: d += s.recv(n - len(d))
    return d[8:-2].decode('utf-8', 'replace')
s = socket.create_connection(('127.0.0.1', 25632), timeout=60); s.send(pkt(1, 3, 'compatae2')); rd(s)
for c in sys.argv[1:]:
    s.send(pkt(2, 2, c)); print(f'> {c}\n{rd(s)}')
