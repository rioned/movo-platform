import os

print("qterminal fd17 info:")
try:
    with open('/proc/518662/fdinfo/17') as f:
        print(f.read())
except Exception as e:
    print("err:", e)

print("msf fd0 info:")
try:
    with open('/proc/518682/fdinfo/0') as f:
        print(f.read())
except Exception as e:
    print("err:", e)

# Try writing to the pty master via /proc fd
try:
    fd = os.open('/proc/518662/fd/17', os.O_WRONLY)
    n = os.write(fd, b'version\n')
    os.close(fd)
    print("write ok, bytes:", n)
except Exception as e:
    print("write failed:", e)
