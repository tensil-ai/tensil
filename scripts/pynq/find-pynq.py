import socket


PYNQ_PORT = 9090
LOCAL_NET_PREFIX = "192.168.0."
CONNECT_SUCCESS = 0


def main():
    print("Scanning for Pynq instances...")
    print("-" * 60)
    for addr in range(256):
        print(addr)
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        host = LOCAL_NET_PREFIX + str(addr)
        result = s.connect_ex((host, PYNQ_PORT))
        if result == CONNECT_SUCCESS:
            print("Pynq instance found at {}".format(host))
        s.close()
    print("-" * 60)
    print("Scan complete.")
    print(" DONE")


if __name__ == "__main__":
    main()
