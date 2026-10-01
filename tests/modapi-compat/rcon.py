import socket, struct, sys
def pkt(i,t,b): d=struct.pack('<ii',i,t)+b.encode()+b'\0\0'; return struct.pack('<i',len(d))+d
def rd(s):
    n=struct.unpack('<i',s.recv(4))[0]; d=b''
    while len(d)<n: d+=s.recv(n-len(d))
    return struct.unpack('<ii',d[:8]), d[8:-2].decode(errors='replace')
s=socket.create_connection(('127.0.0.1',int(sys.argv[1])),timeout=15)
s.send(pkt(1,3,sys.argv[2])); rd(s)
s.send(pkt(2,2,' '.join(sys.argv[3:]))); print(rd(s)[1])
