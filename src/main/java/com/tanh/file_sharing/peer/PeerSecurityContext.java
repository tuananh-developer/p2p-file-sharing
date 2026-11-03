package com.tanh.file_sharing.peer;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.Socket;
import java.security.Principal;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the local Ed25519 identity and the explicitly trusted peer certificate pins.
 *
 * Private key files are restricted to the current OS user (0600 on POSIX systems).
 * Peer traffic is accepted only over TLS 1.3 with mutual certificate authentication.
 */
public final class PeerSecurityContext {
    public static final String TLS_PROTOCOL = "TLSv1.3";
    private static final Logger LOGGER = Logger.getLogger(PeerSecurityContext.class.getName());
    private static final char[] EMPTY_PASSWORD = new char[0];
    private static final String IDENTITY_KEY_FILE = "identity-ed25519.pk8";
    private static final String IDENTITY_CERT_FILE = "identity-ed25519.cer";
    private static final String TRUSTED_PEERS_FILE = "trusted-peer-fingerprints.txt";

    private final Path directory;
    private final Path privateKeyPath;
    private final Path certificatePath;
    private final Path trustedPeersPath;
    private final Set<String> trustedFingerprints = new ConcurrentSkipListSet<>();
    private final Object persistenceLock = new Object();

    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final String localFingerprint;
    private volatile SSLContext sslContext;

    public PeerSecurityContext() throws IOException {
        this(Path.of(System.getProperty("user.home"), ".p2p-file-sharing"));
    }

    public PeerSecurityContext(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.privateKeyPath = this.directory.resolve(IDENTITY_KEY_FILE);
        this.certificatePath = this.directory.resolve(IDENTITY_CERT_FILE);
        this.trustedPeersPath = this.directory.resolve(TRUSTED_PEERS_FILE);

        Files.createDirectories(this.directory);
        restrictDirectoryPermissions(this.directory);

        Identity identity = loadOrCreateIdentity();
        this.privateKey = identity.privateKey();
        this.certificate = identity.certificate();
        try {
            this.localFingerprint = fingerprint(this.certificate);
        } catch (CertificateException exception) {
            throw new IOException("Could not calculate the local peer identity fingerprint.", exception);
        }
        loadTrustedFingerprints();
    }

    public String getLocalFingerprint() {
        return localFingerprint;
    }

    public List<String> getTrustedFingerprints() {
        return List.copyOf(trustedFingerprints);
    }

    public void trustFingerprint(String value) throws IOException {
        String normalized = normalizeFingerprint(value);
        if (normalized.equals(localFingerprint)) {
            throw new IllegalArgumentException("A peer cannot trust its own identity fingerprint.");
        }
        synchronized (persistenceLock) {
            Set<String> updated = new TreeSet<>(trustedFingerprints);
            updated.add(normalized);
            persistTrustedFingerprints(updated);
            trustedFingerprints.clear();
            trustedFingerprints.addAll(updated);
        }
    }

    public void removeTrustedFingerprint(String value) throws IOException {
        String normalized = normalizeFingerprint(value);
        synchronized (persistenceLock) {
            Set<String> updated = new TreeSet<>(trustedFingerprints);
            updated.remove(normalized);
            persistTrustedFingerprints(updated);
            trustedFingerprints.clear();
            trustedFingerprints.addAll(updated);
        }
    }

    public SSLServerSocket createServerSocket() throws IOException {
        SSLServerSocketFactory factory = sslContext().getServerSocketFactory();
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket();
        socket.setEnabledProtocols(new String[]{TLS_PROTOCOL});
        socket.setNeedClientAuth(true);
        return socket;
    }

    public SSLSocket createClientSocket() throws IOException {
        SSLSocketFactory factory = sslContext().getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        socket.setUseClientMode(true);
        socket.setEnabledProtocols(new String[]{TLS_PROTOCOL});
        return socket;
    }

    public void beginServerHandshake(SSLSocket socket) throws IOException {
        socket.setUseClientMode(false);
        socket.setNeedClientAuth(true);
        socket.setEnabledProtocols(new String[]{TLS_PROTOCOL});
        socket.startHandshake();
    }

    public void beginClientHandshake(SSLSocket socket) throws IOException {
        socket.setUseClientMode(true);
        socket.setEnabledProtocols(new String[]{TLS_PROTOCOL});
        socket.startHandshake();
    }

    private SSLContext sslContext() throws IOException {
        SSLContext existing = sslContext;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (sslContext != null) {
                return sslContext;
            }
            try {
                SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
                context.init(new KeyManager[]{new LocalIdentityKeyManager()},
                        new TrustManager[]{new PinnedPeerTrustManager()}, new SecureRandom());
                sslContext = context;
                return context;
            } catch (GeneralSecurityException exception) {
                throw new IOException("Could not initialize TLS 1.3 peer security.", exception);
            }
        }
    }

    private Identity loadOrCreateIdentity() throws IOException {
        boolean keyExists = Files.exists(privateKeyPath);
        boolean certExists = Files.exists(certificatePath);
        if (keyExists != certExists) {
            throw new IOException("Peer identity files are incomplete. Refusing to silently replace the identity.");
        }
        if (keyExists) {
            if (Files.isSymbolicLink(privateKeyPath) || Files.isSymbolicLink(certificatePath)) {
                throw new IOException("Peer identity files must not be symbolic links.");
            }
            byte[] keyBytes = Files.readAllBytes(privateKeyPath);
            PrivateKey key;
            X509Certificate cert;
            try {
                key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
                try (InputStream input = Files.newInputStream(certificatePath)) {
                    cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
                }
                cert.checkValidity();
                cert.verify(cert.getPublicKey());
                verifyKeyPair(key, cert);
            } catch (GeneralSecurityException exception) {
                throw new IOException("The saved peer identity is invalid. Refusing to generate a replacement identity.", exception);
            }
            restrictFilePermissions(privateKeyPath);
            restrictFilePermissions(certificatePath);
            return new Identity(key, cert);
        }

        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();

            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=P2P File Sharing Peer");
            BigInteger serial = new BigInteger(159, new SecureRandom()).setBit(0);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject,
                    serial,
                    java.util.Date.from(now.minusSeconds(300)),
                    java.util.Date.from(now.plus(Duration.ofDays(3650))),
                    subject,
                    pair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
                    new KeyPurposeId[]{KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth}));

            ContentSigner signer = new JcaContentSignerBuilder("Ed25519")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(pair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(holder);
            cert.verify(pair.getPublic());

            writeIdentityFile(privateKeyPath, pair.getPrivate().getEncoded());
            try {
                writeIdentityFile(certificatePath, cert.getEncoded());
            } catch (IOException exception) {
                Files.deleteIfExists(privateKeyPath);
                throw exception;
            }
            return new Identity(pair.getPrivate(), cert);
        } catch (GeneralSecurityException | org.bouncycastle.operator.OperatorCreationException exception) {
            throw new IOException("Could not create a persistent Ed25519 peer identity.", exception);
        }
    }

    private void verifyKeyPair(PrivateKey key, X509Certificate cert) throws GeneralSecurityException {
        byte[] challenge = new byte[32];
        new SecureRandom().nextBytes(challenge);
        java.security.Signature signature = java.security.Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(challenge);
        byte[] signed = signature.sign();
        signature.initVerify(cert.getPublicKey());
        signature.update(challenge);
        if (!signature.verify(signed)) {
            throw new GeneralSecurityException("Private key does not match the saved peer certificate.");
        }
        Arrays.fill(challenge, (byte) 0);
        Arrays.fill(signed, (byte) 0);
    }

    private void loadTrustedFingerprints() throws IOException {
        if (!Files.exists(trustedPeersPath)) {
            return;
        }
        if (Files.isSymbolicLink(trustedPeersPath)) {
            throw new IOException("Trusted peer fingerprint store must not be a symbolic link.");
        }
        for (String line : Files.readAllLines(trustedPeersPath, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (!value.isEmpty()) {
                trustedFingerprints.add(normalizeFingerprint(value));
            }
        }
        restrictFilePermissions(trustedPeersPath);
    }

    private void persistTrustedFingerprints(Set<String> fingerprints) throws IOException {
        List<String> values = new ArrayList<>(fingerprints);
        Collections.sort(values);
        String content = values.isEmpty() ? "" : String.join(System.lineSeparator(), values) + System.lineSeparator();
        writeIdentityFile(trustedPeersPath, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeIdentityFile(Path target, byte[] data) throws IOException {
        Path parent = target.getParent();
        Path temporary = Files.createTempFile(parent, ".p2p-security-", ".tmp");
        try {
            restrictFilePermissions(temporary);
            Files.write(temporary, data);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictFilePermissions(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictDirectoryPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException | UnsupportedOperationException exception) {
            LOGGER.log(Level.FINE, "Could not apply POSIX directory permissions to " + path, exception);
        }
    }

    private static void restrictFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException exception) {
            // Files.createTempFile uses restrictive defaults on common platforms.
            // On non-POSIX systems the identity directory inherits the user's profile ACL.
            LOGGER.log(Level.FINE, "Could not apply POSIX file permissions to " + path, exception);
        }
    }

    private boolean isTrusted(X509Certificate peerCertificate) throws CertificateException {
        peerCertificate.checkValidity();
        String presentedFingerprint = fingerprint(peerCertificate);
        if (!trustedFingerprints.contains(presentedFingerprint)) {
            throw new CertificateException(
                    "Peer certificate is not trusted. Verify and add SHA-256 fingerprint "
                            + presentedFingerprint + " on both devices before connecting.");
        }
        return true;
    }

    private static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new CertificateException("Could not calculate the peer certificate fingerprint.", exception);
        }
    }

    public static String normalizeFingerprint(String value) {
        if (value == null) {
            throw new IllegalArgumentException("A SHA-256 peer certificate fingerprint is required.");
        }
        String normalized = value.replace(":", "").replace("-", "").replaceAll("\\s", "")
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("Fingerprint must contain exactly 64 hexadecimal characters.");
        }
        StringBuilder formatted = new StringBuilder(95);
        for (int index = 0; index < normalized.length(); index += 2) {
            if (formatted.length() > 0) {
                formatted.append(':');
            }
            formatted.append(normalized, index, index + 2);
        }
        return formatted.toString();
    }

    private record Identity(PrivateKey privateKey, X509Certificate certificate) {
    }

    /**
     * Always presents this peer's Ed25519 identity for both TLS client and server roles.
     * JSSE's default key manager can omit a self-signed Ed25519 identity when the
     * issuer hint is unfamiliar, even though TLS 1.3 supports the Ed25519 signature scheme.
     */
    private final class LocalIdentityKeyManager extends X509ExtendedKeyManager {
        private boolean supports(String keyType) {
            if (keyType == null) {
                return false;
            }
            String algorithm = certificate.getPublicKey().getAlgorithm();
            return keyType.equalsIgnoreCase(algorithm)
                    || keyType.equalsIgnoreCase("Ed25519")
                    || keyType.equalsIgnoreCase("EdDSA");
        }

        private String choose(String[] keyTypes) {
            if (keyTypes == null) {
                return null;
            }
            return Arrays.stream(keyTypes).anyMatch(this::supports) ? "local-peer" : null;
        }

        @Override
        public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
            return supports(keyType) ? new String[]{"local-peer"} : null;
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, java.security.Principal[] issuers, java.net.Socket socket) {
            return choose(keyTypes);
        }

        @Override
        public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
            return supports(keyType) ? new String[]{"local-peer"} : null;
        }

        @Override
        public String chooseServerAlias(String keyType, java.security.Principal[] issuers, java.net.Socket socket) {
            return supports(keyType) ? "local-peer" : null;
        }

        @Override
        public java.security.cert.X509Certificate[] getCertificateChain(String alias) {
            return "local-peer".equals(alias) ? new X509Certificate[]{certificate} : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return "local-peer".equals(alias) ? privateKey : null;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, java.security.Principal[] issuers, SSLEngine engine) {
            return choose(keyTypes);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, java.security.Principal[] issuers, SSLEngine engine) {
            return supports(keyType) ? "local-peer" : null;
        }
    }

    private final class PinnedPeerTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkPinnedCertificate(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkPinnedCertificate(chain);
        }

        private void checkPinnedCertificate(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length != 1 || chain[0] == null) {
                throw new CertificateException("Expected exactly one pinned peer certificate.");
            }
            isTrusted(chain[0]);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            // All generated peer certificates use the same self-signed issuer DN.
            // Advertise it so the JSSE client key manager selects our mutual-TLS certificate.
            return new X509Certificate[]{certificate};
        }
    }
}
