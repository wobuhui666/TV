package com.github.catvod.net.ech;

import androidx.annotation.Keep;

import org.conscrypt.Conscrypt;
import org.conscrypt.ConscryptNetworkSecurityPolicy;
import org.conscrypt.DomainEncryptionMode;
import org.conscrypt.NetworkSecurityPolicy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Selects Conscrypt only when ECH is enabled and the hostname has a configuration.
 * The returned socket is never wrapped, so OkHttp can recognize Conscrypt for ALPN.
 * Requires the pinned Conscrypt 2.9.0 policy contract; no global provider is installed.
 */
public final class ConscryptEchSocketFactory extends SSLSocketFactory {

    public interface ConfigProvider {
        boolean isEnabled();

        /** Returns an ECHConfigList, or null when this hostname has no configuration. */
        byte[] resolve(String hostname) throws IOException;
    }

    private final SSLSocketFactory legacyFactory;
    private final X509TrustManager trustManager;
    private final ConfigProvider configProvider;
    private volatile SSLSocketFactory echFactory;

    public ConscryptEchSocketFactory(SSLSocketFactory legacyFactory,
                                    X509TrustManager trustManager,
                                    ConfigProvider configProvider) {
        this.legacyFactory = Objects.requireNonNull(legacyFactory);
        this.trustManager = Objects.requireNonNull(trustManager);
        this.configProvider = Objects.requireNonNull(configProvider);
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return legacyFactory.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return legacyFactory.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
            throws IOException {
        byte[] config = configuration(host, port);
        return configure(factory(config).createSocket(socket, host, port, autoClose), config);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        byte[] config = configuration(host, port);
        return configure(factory(config).createSocket(host, port), config);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        byte[] config = configuration(host, port);
        return configure(factory(config).createSocket(host, port, localHost, localPort), config);
    }

    // These overloads have no original hostname. Do not introduce reverse DNS lookups.
    @Override
    public Socket createSocket() throws IOException {
        return legacyFactory.createSocket();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return legacyFactory.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return legacyFactory.createSocket(host, port, localHost, localPort);
    }

    private byte[] configuration(String hostname, int port) throws IOException {
        // The resolver currently queries HTTPS records for the default HTTPS service only.
        if (port != 443 || !configProvider.isEnabled() || hostname == null || hostname.isEmpty()) return null;
        byte[] config = configProvider.resolve(hostname);
        // Do not treat a supplied, invalid list as permission to silently disable ECH.
        if (config != null && config.length == 0) throw new SSLException("Empty ECHConfigList");
        return config == null ? null : config.clone();
    }

    private SSLSocketFactory factory(byte[] config) throws SSLException {
        if (config == null) return legacyFactory;
        SSLSocketFactory result = echFactory;
        if (result != null) return result;
        synchronized (this) {
            if (echFactory == null) {
                try {
                    SSLContext context = SSLContext.getInstance("TLS", Conscrypt.newProvider());
                    context.init(null, new TrustManager[]{new PolicyTrustManager(trustManager)}, null);
                    echFactory = context.getSocketFactory();
                } catch (GeneralSecurityException e) {
                    throw new SSLException("Unable to initialize Conscrypt ECH", e);
                }
            }
            return echFactory;
        }
    }

    private Socket configure(Socket socket, byte[] config) throws IOException {
        if (config == null) return socket;
        try {
            Conscrypt.setEchConfigList((SSLSocket) socket, config);
            return socket;
        } catch (RuntimeException | Error e) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Conscrypt 2.9.0 discovers this public class's getNetworkSecurityPolicy by reflection.
     * Its unbundled Android policy otherwise returns UNKNOWN and ignores ECHConfigList.
     * Keep the reflection contract while delegating all certificate decisions unchanged.
     * Do not extend X509ExtendedTrustManager: Conscrypt's EngineSocket wraps extended
     * managers in an anonymous adapter which loses this policy method. Android discovers
     * the public socket/engine trust overloads below by reflection instead.
     */
    @Keep
    public static final class PolicyTrustManager implements X509TrustManager {
        private final X509TrustManager delegate;
        private final NetworkSecurityPolicy policy = new ConscryptNetworkSecurityPolicy() {
            @Override
            public DomainEncryptionMode getDomainEncryptionMode(String hostname) {
                return DomainEncryptionMode.ENABLED;
            }
        };

        public PolicyTrustManager(X509TrustManager delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Keep
        public NetworkSecurityPolicy getNetworkSecurityPolicy() {
            return policy;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkClientTrusted(chain, authType, socket);
            } else if (!checkContext("checkClientTrusted", chain, authType, Socket.class, socket,
                    socket instanceof SSLSocket ssl ? ssl.getHandshakeSession() : null)) {
                delegate.checkClientTrusted(chain, authType);
            }
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkServerTrusted(chain, authType, socket);
            } else if (!checkContext("checkServerTrusted", chain, authType, Socket.class, socket,
                    socket instanceof SSLSocket ssl ? ssl.getHandshakeSession() : null)) {
                delegate.checkServerTrusted(chain, authType);
            }
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkClientTrusted(chain, authType, engine);
            } else if (!checkContext("checkClientTrusted", chain, authType, SSLEngine.class, engine,
                    engine == null ? null : engine.getHandshakeSession())) {
                delegate.checkClientTrusted(chain, authType);
            }
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkServerTrusted(chain, authType, engine);
            } else if (!checkContext("checkServerTrusted", chain, authType, SSLEngine.class, engine,
                    engine == null ? null : engine.getHandshakeSession())) {
                delegate.checkServerTrusted(chain, authType);
            }
        }

        // Preserve Conscrypt's support for legacy hostname-aware Android trust managers too.
        private boolean checkContext(String method, X509Certificate[] chain, String authType,
                                     Class<?> contextType, Object context, SSLSession session)
                throws CertificateException {
            return invokeTrusted(method, chain, authType, contextType, context)
                    || (session != null && invokeTrusted(method, chain, authType,
                    String.class, session.getPeerHost()));
        }

        private boolean invokeTrusted(String name, X509Certificate[] chain, String authType,
                                      Class<?> contextType, Object context)
                throws CertificateException {
            try {
                Method method = delegate.getClass().getMethod(name, X509Certificate[].class,
                        String.class, contextType);
                method.invoke(delegate, chain, authType, context);
                return true;
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
                return false;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CertificateException certificateException) throw certificateException;
                if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                if (cause instanceof Error error) throw error;
                throw new CertificateException(cause);
            }
        }
    }
}
