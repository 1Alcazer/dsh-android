import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;

/**
 * Dumps the signing key and certificate from a JKS keystore as raw DER files.
 *
 * <p>Used only as a fallback by scripts/build-apk.sh: on some Android/Termux
 * runtimes apksigner truncates large output files, so scripts/sign-apk-v2.py
 * signs the APK directly instead. The key material is written next to the
 * build output, which is gitignored, and removed after signing.</p>
 *
 * <p>Usage: DumpSigningKey KEYSTORE ALIAS STOREPASS KEYPASS KEY_DER CERT_DER PUBLIC_DER</p>
 */
public final class DumpSigningKey {
    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            System.err.println("usage: DumpSigningKey KEYSTORE ALIAS STOREPASS KEYPASS "
                    + "KEY_DER CERT_DER PUBLIC_DER");
            System.exit(2);
        }
        char[] storePass = args[2].toCharArray();
        char[] keyPass = args[3].toCharArray();

        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in = new FileInputStream(args[0])) {
            store.load(in, storePass);
        }
        PrivateKey key = (PrivateKey) store.getKey(args[1], keyPass);
        Certificate cert = store.getCertificate(args[1]);
        if (key == null || cert == null) {
            throw new IllegalStateException("no key or certificate for alias " + args[1]);
        }
        try (OutputStream out = new FileOutputStream(args[4])) {
            out.write(key.getEncoded());
        }
        try (OutputStream out = new FileOutputStream(args[5])) {
            out.write(cert.getEncoded());
        }
        try (OutputStream out = new FileOutputStream(args[6])) {
            out.write(cert.getPublicKey().getEncoded());
        }
        System.out.println("dumped " + key.getAlgorithm() + " key and "
                + cert.getType() + " certificate");
    }
}
