package cn.smartbox.live;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Explicit trust anchors; never installs a global or permissive trust manager. */
final class MqttTransport {
    private MqttTransport() {}
    static void validate(String profile, String endpoint) {
        URI uri=URI.create(endpoint);
        if ("local".equals(profile)) {
            if (!"tcp://127.0.0.1:1883".equals(endpoint)) throw new IllegalArgumentException("Local endpoint required");
        } else if ("cloud".equals(profile)) {
            if (!"ssl".equals(uri.getScheme()) || uri.getHost()==null || !uri.getHost().contains(".") || uri.getHost().matches("[0-9.]+") || uri.getPort()!=8883 || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getFragment()!=null || (uri.getPath()!=null&&!uri.getPath().isEmpty()))
                throw new IllegalArgumentException("Cloud TLS DNS endpoint required");
        } else throw new IllegalArgumentException("Unknown MQTT profile");
    }
    static SSLSocketFactory trust(Path ca) throws Exception {
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null,null);
        try(InputStream in=Files.newInputStream(ca)) {
            int n=0;
            for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(in)) store.setCertificateEntry("ca-"+(n++),cert);
            if(n==0)throw new IllegalArgumentException("Empty MQTT CA bundle");
        }
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);
        SSLContext context=SSLContext.getInstance("TLSv1.2");context.init(null,tm.getTrustManagers(),null);
        return context.getSocketFactory();
    }
}
