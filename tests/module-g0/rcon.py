import socket, struct, sys
def pkt(i, t, b): b = b.encode() + b'\x00\x00'; return struct.pack('<iii', len(b) + 8, i, t) + b
def rdpkt(s):
    hdr = b''
    while len(hdr) < 4:
        c = s.recv(4 - len(hdr))
        if not c: raise ConnectionError('rcon connection closed')
        hdr += c
    n = struct.unpack('<i', hdr)[0]; d = b''
    while len(d) < n:
        c = s.recv(n - len(d))
        if not c: raise ConnectionError('rcon connection closed')
        d += c
    rid = struct.unpack('<i', d[:4])[0]
    return rid, d[8:-2].decode('utf-8', 'replace')
s = socket.create_connection(('127.0.0.1', 25642), timeout=120); s.send(pkt(1, 3, 'g0probe')); rdpkt(s)
for c in sys.argv[1:]:
    s.send(pkt(2, 2, c)); s.send(pkt(3, 0, ''))     # marker: an empty response-value packet ends the reply
    out = []
    while True:
        rid, body = rdpkt(s)
        if rid == 3: break
        out.append(body)
    print(f'> {c}\n{"".join(out)}')
