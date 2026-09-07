package de.spraener.prjxp.common.transfer;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class TransferCrypto {
    public static final byte[] MAGIC = "PRJXPENC".getBytes(StandardCharsets.US_ASCII);
    public static final int FORMAT_VERSION = 0x01;
    public static final int DEFAULT_KDF_ITERATIONS = 200_000;

    private static final int SALT_SIZE = 16;
    private static final int IV_SIZE = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_SIZE_BITS = 256;
    private static final byte[] PLAINTEXT_PREFIX = "PRJXPJSONL".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TransferCrypto() {
    }

    public static OutputStream openEncryptedOutputStream(OutputStream raw, char[] password) throws IOException {
        return openEncryptedOutputStream(raw, password, DEFAULT_KDF_ITERATIONS);
    }

    public static OutputStream openEncryptedOutputStream(OutputStream raw, char[] password, int kdfIterations) throws IOException {
        requirePassword(password);
        byte[] salt = randomBytes(SALT_SIZE);
        byte[] iv = randomBytes(IV_SIZE);
        SecretKey key = deriveKey(password, salt, kdfIterations);
        Cipher cipher = initCipher(Cipher.ENCRYPT_MODE, key, iv);
        writeHeader(raw, salt, iv, kdfIterations);
        byte[] prefixCiphertext = cipher.update(PLAINTEXT_PREFIX);
        raw.write(prefixCiphertext, 0, prefixCiphertext.length);
        return new EncryptedOutputStream(raw, cipher);
    }

    public static InputStream openDecryptedInputStream(InputStream raw, char[] password) throws IOException {
        requirePassword(password);
        byte[] magic = readFully(raw, MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Kein PRJXP-Encrypted-Stream: Magic-Bytes nicht gefunden");
        }
        int version = readByte(raw);
        if (version != FORMAT_VERSION) {
            throw new IOException("Nicht unterstützte PRJXP-Format-Version: " + version);
        }
        byte[] salt = readFully(raw, readByte(raw));
        byte[] iv = readFully(raw, readByte(raw));
        int iterations = readIntBigEndian(raw);
        SecretKey key = deriveKey(password, salt, iterations);
        Cipher cipher = initCipher(Cipher.DECRYPT_MODE, key, iv);
        return new DecryptedInputStream(raw, cipher);
    }

    public static InputStream openAuto(InputStream raw, char[] password) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(raw, MAGIC.length);
        byte[] head = new byte[MAGIC.length];
        int read = readAvailable(pushback, head);
        pushback.unread(head, 0, read);
        if (read == MAGIC.length && Arrays.equals(head, MAGIC)) {
            if (password == null || password.length == 0) {
                throw new IOException("Eingabe ist verschlüsselt, aber kein Passwort verfügbar");
            }
            return openDecryptedInputStream(pushback, password);
        }
        return pushback;
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Passwort darf nicht leer sein");
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_SIZE_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } catch (GeneralSecurityException e) {
            throw new IOException("Key-Derivation fehlgeschlagen", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static Cipher initCipher(int mode, SecretKey key, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("Initialisierung von AES/GCM fehlgeschlagen", e);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static void writeHeader(OutputStream out, byte[] salt, byte[] iv, int iterations) throws IOException {
        out.write(MAGIC);
        out.write(FORMAT_VERSION);
        out.write(salt.length);
        out.write(salt);
        out.write(iv.length);
        out.write(iv);
        out.write(new byte[]{
                (byte) (iterations >>> 24),
                (byte) (iterations >>> 16),
                (byte) (iterations >>> 8),
                (byte) iterations
        });
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("Stream zu kurz für PRJXP-Header");
            }
            offset += read;
        }
        return buffer;
    }

    private static int readByte(InputStream in) throws IOException {
        return readFully(in, 1)[0] & 0xFF;
    }

    private static int readIntBigEndian(InputStream in) throws IOException {
        byte[] bytes = readFully(in, 4);
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    private static int readAvailable(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static class EncryptedOutputStream extends FilterOutputStream {
        private final Cipher cipher;

        private EncryptedOutputStream(OutputStream out, Cipher cipher) {
            super(out);
            this.cipher = cipher;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(encrypt(new byte[]{(byte) b}, 0, 1));
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(encrypt(b, off, len));
        }

        private byte[] encrypt(byte[] b, int off, int len) {
            if (len == 0) {
                return new byte[0];
            }
            byte[] ciphertext = cipher.update(b, off, len);
            return ciphertext == null ? new byte[0] : ciphertext;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            try {
                byte[] authTag = cipher.doFinal();
                out.write(authTag, 0, authTag.length);
            } catch (GeneralSecurityException e) {
                throw new IOException("Verschlüsselung fehlgeschlagen", e);
            }
            out.close();
        }
    }

    private static class DecryptedInputStream extends InputStream {
        private final InputStream raw;
        private final Cipher cipher;
        private final byte[] prefixBuffer = new byte[PLAINTEXT_PREFIX.length];
        private int prefixPos = 0;
        private boolean prefixVerified = false;
        private byte[] buffer = new byte[0];
        private int pos = 0;
        private int len = 0;
        private boolean eof = false;

        private DecryptedInputStream(InputStream raw, Cipher cipher) {
            this.raw = raw;
            this.cipher = cipher;
        }

        @Override
        public int read() throws IOException {
            while (pos >= len) {
                if (eof) {
                    return -1;
                }
                fillBuffer();
            }
            return buffer[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int n) throws IOException {
            if (n == 0) {
                return 0;
            }
            int total = 0;
            while (total < n) {
                if (pos >= len) {
                    if (eof) {
                        break;
                    }
                    fillBuffer();
                }
                int chunk = Math.min(n - total, len - pos);
                System.arraycopy(buffer, pos, b, off + total, chunk);
                pos += chunk;
                total += chunk;
            }
            return total == 0 ? -1 : total;
        }

        private void fillBuffer() throws IOException {
            if (eof) {
                return;
            }
            byte[] input = new byte[8192];
            int read = raw.read(input);
            if (read < 0) {
                byte[] tail;
                try {
                    tail = cipher.doFinal();
                } catch (GeneralSecurityException e) {
                    throw new IOException("Entschlüsselung fehlgeschlagen (Auth-Tag)", e);
                }
                buffer = stripPrefix(tail);
                pos = 0;
                len = buffer.length;
                eof = true;
                if (!prefixVerified) {
                    throw new IOException("Entschlüsselung fehlgeschlagen (Auth-Tag)");
                }
                return;
            }
            byte[] plaintext = cipher.update(input, 0, read);
            buffer = stripPrefix(plaintext);
            pos = 0;
            len = buffer.length;
        }

        private byte[] stripPrefix(byte[] plaintext) throws IOException {
            if (prefixVerified || plaintext.length == 0) {
                return plaintext;
            }
            int take = Math.min(PLAINTEXT_PREFIX.length - prefixPos, plaintext.length);
            System.arraycopy(plaintext, 0, prefixBuffer, prefixPos, take);
            prefixPos += take;
            if (prefixPos < PLAINTEXT_PREFIX.length) {
                return new byte[0];
            }
            if (!Arrays.equals(prefixBuffer, PLAINTEXT_PREFIX)) {
                throw new IOException("Entschlüsselung fehlgeschlagen (Auth-Tag)");
            }
            prefixVerified = true;
            if (take == plaintext.length) {
                return new byte[0];
            }
            return Arrays.copyOfRange(plaintext, take, plaintext.length);
        }

        @Override
        public void close() throws IOException {
            raw.close();
        }
    }
}
