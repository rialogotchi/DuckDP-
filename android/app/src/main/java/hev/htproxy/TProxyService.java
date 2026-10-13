package hev.htproxy;

/** Native bridge of hev-socks5-tunnel. Package/class name are fixed by the library. */
public final class TProxyService {
    static { System.loadLibrary("hev-socks5-tunnel"); }

    public static native void TProxyStartService(String configPath, int fd);
    public static native void TProxyStopService();
    public static native long[] TProxyGetStats();

    private TProxyService() {}
}
