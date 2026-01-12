import socks
import socket
import threading
import time
import sys
import random

# ================= 配置区域 =================

# 本地代理配置
LOCAL_PROXY_IP = '127.0.0.1'
LOCAL_PROXY_PORT = 58080

# 目标地址
TARGET_IP = 'www.google.com'
TARGET_PORT = 80

# 目标并发数
CONNECTION_COUNT = 20000

# ===========================================

# 统计计数器
connected_count = 0
failed_count = 0
lock = threading.Lock()

def connect_and_hold(thread_id):
    global connected_count, failed_count
    try:
        s = socks.socksocket()
        s.settimeout(10)
        s.set_proxy(socks.SOCKS5, LOCAL_PROXY_IP, LOCAL_PROXY_PORT)
        # Python -> 本地58080 -> 自定义协议 -> 远程服务器 -> google
        s.connect((TARGET_IP, TARGET_PORT))

        with lock:
            connected_count += 1
            sys.stdout.write(f"\r当前连接数: {connected_count} | 失败数: {failed_count}")
            sys.stdout.flush()

        while True:
            time.sleep(random.randint(30, 60))

            try:
                pass
            except:
                break

    except Exception as e:
        with lock:
            failed_count += 1
            # 调试时取消注释，否则刷屏
            # print(f"Thread-{thread_id} Error: {e}")
    finally:
        with lock:
            if connected_count > 0:
                connected_count -= 1
        try:
            s.close()
        except:
            pass

def start_flood():
    print(f"开始测试...")
    print(f"本地代理: {LOCAL_PROXY_IP}:{LOCAL_PROXY_PORT}")
    print(f"本地带宽限制: 5Mbps (脚本已开启低流量模式)")

    threads = []
    for i in range(CONNECTION_COUNT):
        t = threading.Thread(target=connect_and_hold, args=(i,))
        t.daemon = True
        t.start()
        threads.append(t)

        # 控制启动速度
        time.sleep(0.001)

    # 阻塞主线程
    while True:
        time.sleep(1)

if __name__ == "__main__":
    print(f"安装socks模块：pip install PySocks")
    # 增加系统打开文件数限制
    try:
        import resource
        soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
        resource.setrlimit(resource.RLIMIT_NOFILE, (hard, hard))
        print(f"系统文件句柄限制已提升至: {hard}")
    except:
        print("提示: Windows系统请忽略文件句柄设置，或Linux下手动执行 ulimit -n")

    start_flood()