package pro.gravit.launchermodules.sashoksupport.socket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import pro.gravit.launcher.Launcher;
import pro.gravit.launcher.managers.GarbageManager;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.manangers.SessionManager;
import pro.gravit.utils.helper.CommonHelper;
import pro.gravit.utils.helper.LogHelper;

public final class ServerSocketHandler implements Runnable, AutoCloseable {
    public interface Listener {

        boolean onConnect(InetAddress address);


        void onDisconnect(Exception e);


        boolean onHandshake(long session, int type);
    }

    private static final ThreadFactory THREAD_FACTORY = r -> CommonHelper.newThread("Network Thread", true, r);


    public volatile boolean logConnections = Boolean.getBoolean("launcher.logConnections");
    // Instance
    private final LaunchServer server;
    private final AtomicReference<ServerSocket> serverSocket = new AtomicReference<>();
    private final ExecutorService threadPool;

    public final SessionManager sessionManager;
    private final AtomicLong idCounter = new AtomicLong(0L);
    public static int LEGACY_LAUNCHER_MAGIC = Launcher.PROTOCOL_MAGIC_LEGACY - 2;

    private volatile Listener listener;

    public LegacyServerComponent component;

    public ServerSocketHandler(LaunchServer server, LegacyServerComponent component) {
        this(server, new SessionManager());
        this.component = component;
        GarbageManager.registerNeedGC(sessionManager);
    }

    public ServerSocketHandler(LaunchServer server, SessionManager sessionManager) {
        this.server = server;
        threadPool = new ThreadPoolExecutor(component.threadCoreCount, Integer.MAX_VALUE,
                component.threadCount, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                THREAD_FACTORY);
        this.sessionManager = sessionManager;
    }

    @Override
    public void close() {
        ServerSocket socket = serverSocket.getAndSet(null);
        if (socket != null) {
            LogHelper.info("Closing server socket listener");
            try {
                socket.close();
            } catch (IOException e) {
                LogHelper.error(e);
            }
        }
    }

    /*package*/ void onDisconnect(Exception e) {
        if (listener != null)
            listener.onDisconnect(e);
    }

    /*package*/ boolean onHandshake(long session, int type) {
        return listener == null || listener.onHandshake(session, type);
    }

    @Override
    public void run() {
        LogHelper.info("Starting server socket thread");
        try (ServerSocket serverSocket = new ServerSocket()) {
            if (!this.serverSocket.compareAndSet(null, serverSocket))
                throw new IllegalStateException("Previous socket wasn't closed");

            // Set socket params
            serverSocket.setReuseAddress(true);
            serverSocket.setPerformancePreferences(1, 0, 2);
            //serverSocket.setReceiveBufferSize(0x10000);
            serverSocket.bind(component.getSocketAddress());
            LogHelper.info("Server socket thread successfully started");

            // Listen for incoming connections
            while (serverSocket.isBound()) {
                Socket socket = serverSocket.accept();

                // Invoke pre-connect listener
                @SuppressWarnings("unused")
				long id = idCounter.incrementAndGet();
                if (listener != null && !listener.onConnect(socket.getInetAddress())) {
                    socket.close();
                    continue; // Listener didn't accepted this connection
                }


                // Reply in separate thread
                threadPool.execute(new ResponseThread(server, this, socket, sessionManager));
            }
        } catch (IOException e) {
            // Ignore error after close/rebind
            if (serverSocket.get() != null)
                LogHelper.error(e);
        }
    }


    public void setListener(Listener listener) {
        this.listener = listener;
    }
}
