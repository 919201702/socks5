import socks
import socket
import threading
import time
import sys
import random

# ================= 配置区域 =================

PROXY_IP = '127.0.0.1'
PROXY_PORT = 58080

TARGET_HOST = 'speedtest.tele2.net'
TARGET_PORT = 80
TARGET_PATH = '/1MB.zip' # 反复下载

# 压力配置
TOTAL_CONCURRENCY = 5000     # 总连接数
ACTIVE_RATIO = 0.1           # 活跃比例 (10% 的连接下载，90% 静默)

# ===========================================

# 全局统计
stats = {
    "connected": 0,
    "failed": 0,
    "total_bytes": 0,
    "last_bytes": 0,
    "start_time": time.time()
}
lock = threading.Lock()

def get_http_request():
    # 构造 HTTP GET 请求，开启 Keep-Alive 复用连接
    req = (f"GET {TARGET_PATH} HTTP/1.1\r\n"
           f"Host: {TARGET_HOST}\r\n"
           f"User-Agent: SOCKS5-Tester\r\n"
           f"Connection: keep-alive\r\n\r\n")
    return req.encode()

def worker(thread_id, is_active):
    global stats
    s = None
    try:
        s = socks.socksocket()
        s.settimeout(15) # 设置超时
        s.set_proxy(socks.SOCKS5, PROXY_IP, PROXY_PORT)

        s.connect((TARGET_HOST, TARGET_PORT))

        with lock:
            stats["connected"] += 1

        while True:
            if is_active:
                # === 下载 ===
                s.sendall(get_http_request())

                chunk_count = 0
                while True:
                    data = s.recv(4096)
                    if not data:
                        break # 服务器断开

                    with lock:
                        stats["total_bytes"] += len(data)

                    chunk_count += 1
                    if len(data) < 4096: # 假设读不满就是结束了，或者稍微歇一下
                        break

                # 稍微休眠防止死循环占用 100% CPU
                time.sleep(0.01)

            else:
                # 维持连接，拉高连接数
                # 每 30 秒发一个极小的心跳包
                time.sleep(30)
                s.sendall(b' ') # 维持心跳

    except Exception as e:
        # print(e) # 调试时打开
        pass
    finally:
        with lock:
            # 只有连上过的才算断开
            if stats["connected"] > 0: # 这里只是简单统计，不严谨但够用
                 pass
            stats["failed"] += 1
            # Windows 下连接数减少
            stats["connected"] -= 1
        try:
            if s: s.close()
        except:
            pass

def monitor():
    """ 监控线程：打印实时带宽 """
    print(f"测速开始 | 目标: {TARGET_HOST} | 总并发: {TOTAL_CONCURRENCY} | 活跃线程: {int(TOTAL_CONCURRENCY * ACTIVE_RATIO)}")
    print("-" * 60)

    while True:
        time.sleep(1)
        with lock:
            current_bytes = stats["total_bytes"]
            conns = stats["connected"]
            fails = stats["failed"]

        # 计算这一秒内的增量
        delta_bytes = current_bytes - stats["last_bytes"]
        stats["last_bytes"] = current_bytes

        # 转换单位
        speed_mbps = (delta_bytes * 8) / 1000 / 1000 # 转换为 Mbps
        speed_mbs = delta_bytes / 1024 / 1024        # 转换为 MB/s

        # 格式化输出 \r 覆盖当前行
        msg = (f"\r[实时监控] 连接数: {conns} | "
               f"带宽: {speed_mbps:.2f} Mbps ({speed_mbs:.2f} MB/s) | "
               f"累计失败/断开: {fails}")
        sys.stdout.write(msg)
        sys.stdout.flush()

if __name__ == "__main__":
    # Windows 不需要 resource 限制，但受限于注册表 MaxUserPort
    # 启动监控
    threading.Thread(target=monitor, daemon=True).start()

    threads = []

    # 启动工作线程
    for i in range(TOTAL_CONCURRENCY):
        # 决定这个线程是“下载”还是“静默连接”
        is_active = i < (TOTAL_CONCURRENCY * ACTIVE_RATIO)

        t = threading.Thread(target=worker, args=(i, is_active))
        t.daemon = True
        t.start()
        threads.append(t)

        # Windows下启动太快容易报错 OSError: [WinError 10055]
        if i % 100 == 0:
            time.sleep(0.5)
        else:
            time.sleep(0.005)

    # 保持主进程
    while True:
        time.sleep(1)