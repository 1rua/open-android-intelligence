import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.openandroidintelligence.encrypted.store.AesGcmEncryptedBlobStore;
import com.openandroidintelligence.encrypted.store.EncryptedBlobCorrupted;
import com.openandroidintelligence.encrypted.store.EncryptedOutboxPersistence;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 在 ADB shell 独立身份下核验真实密钥库与生产加密类，不访问 App 文件或密钥。 */
public final class AndroidKeystoreBlobProbe {
    private static final String MAGIC = "OPEN_ANDROID_INTELLIGENCE_AES_GCM_BLOB_V1";

    private static final class MemoryPersistence implements EncryptedOutboxPersistence {
        private byte[] bytes;
        public byte[] read() { return bytes == null ? null : bytes.clone(); }
        public void write(byte[] value) { bytes = value.clone(); }
        public void clear() { bytes = null; }
    }

    public static void main(String[] args) throws Exception {
        // app_process 没有 ActivityThread 的应用启动流程，显式安装系统密钥库提供者。
        Class.forName("android.security.keystore2.AndroidKeyStoreProvider")
                .getMethod("install").invoke(null);
        String alias = "oai_blob_probe_" + UUID.randomUUID();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        boolean failed = false;
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            SecretKey key = generator.generateKey();
            try {
                Cipher forbidden = Cipher.getInstance("AES/GCM/NoPadding");
                forbidden.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, new byte[12]));
                throw new AssertionError("平台未拒绝调用方指定的加密 IV");
            } catch (InvalidAlgorithmParameterException expected) {
                System.out.println("平台约束：拒绝调用方指定的加密 IV（InvalidAlgorithmParameterException）");
            }

            MemoryPersistence persistence = new MemoryPersistence();
            AesGcmEncryptedBlobStore store = new AesGcmEncryptedBlobStore(persistence, key);
            byte[] plain = "独立密钥库回归样本".getBytes(StandardCharsets.UTF_8);
            store.writePlaintext(plain);
            byte[] first = persistence.read();
            require(Arrays.equals(plain, store.readPlaintext()), "生产类写入与读取");
            byte[][] fields = fields(first);
            Cipher oracle = Cipher.getInstance("AES/GCM/NoPadding");
            oracle.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, fields[0]));
            require(Arrays.equals(plain, oracle.doFinal(fields[1])), "独立平台解密核验");
            store.writePlaintext(plain);
            require(!Arrays.equals(fields[0], fields(persistence.read())[0]), "重复写入使用不同 IV");

            // 用平台 Cipher 和独立编码器构建原 V1 信封，核验原格式仍可读取。
            Cipher legacy = Cipher.getInstance("AES/GCM/NoPadding");
            legacy.init(Cipher.ENCRYPT_MODE, key);
            byte[] ciphertext = legacy.doFinal(plain);
            persistence.write(envelope(legacy.getIV(), ciphertext));
            require(Arrays.equals(plain, store.readPlaintext()), "原 V1 信封兼容");
            byte[] tampered = persistence.read();
            tampered[tampered.length - 1] ^= 1;
            persistence.write(tampered);
            try {
                store.readPlaintext();
                throw new AssertionError("篡改信封未被拒绝");
            } catch (EncryptedBlobCorrupted expected) {
                require(expected.getCause() == null, "篡改错误不携带敏感异常链");
            }
            System.out.println("通过：真实 AndroidKeyStore、生产加密类、独立解密、IV 随机性、原信封兼容与篡改拒绝");
        } catch (Throwable failure) {
            // 只输出错误类型；不输出样本、密钥、IV、响应或异常栈。
            System.out.println("失败：" + failure.getClass().getSimpleName());
            failed = true;
        } finally {
            // 仅撤销本探针新建的 shell 身份测试密钥，用户 App 的密钥不在此身份中。
            keys.deleteEntry(alias);
        }
        if (failed) System.exit(1);
    }

    private static void require(boolean condition, String stage) {
        if (!condition) throw new AssertionError(stage);
    }

    private static byte[][] fields(byte[] envelope) throws Exception {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope));
        require(MAGIC.equals(new String(field(input), StandardCharsets.UTF_8)), "信封标识");
        byte[] iv = field(input);
        byte[] ciphertext = field(input);
        require(iv.length == 12 && input.available() == 0, "信封长度");
        return new byte[][] {iv, ciphertext};
    }

    private static byte[] field(DataInputStream input) throws Exception {
        int size = input.readInt();
        require(size >= 0 && size <= 1024, "探针字段范围");
        byte[] field = new byte[size];
        input.readFully(field);
        return field;
    }

    private static byte[] envelope(byte[] iv, byte[] ciphertext) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        for (byte[] field : new byte[][] {MAGIC.getBytes(StandardCharsets.UTF_8), iv, ciphertext}) {
            output.writeInt(field.length);
            output.write(field);
        }
        return bytes.toByteArray();
    }
}
