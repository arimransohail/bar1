package com.sohail.pocketgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class VaultManager {
    private static final String PREFS = "vault_prefs";
    private static final String KEY_SALT = "salt";
    private static final String KEY_VERIFIER = "verifier";
    private static final int ITERATIONS = 120000;
    private static final int KEY_BITS = 256;
    private static final String MAGIC = "PGV1";

    private final Context context;
    private final File vaultDir;
    private final SecureRandom random = new SecureRandom();

    public VaultManager(Context context) {
        this.context = context.getApplicationContext();
        this.vaultDir = new File(this.context.getFilesDir(), "vault");
        if (!vaultDir.exists()) vaultDir.mkdirs();
    }

    public boolean hasPin() {
        return prefs().contains(KEY_SALT) && prefs().contains(KEY_VERIFIER);
    }

    public boolean setPin(String pin) {
        try {
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            SecretKey key = deriveKey(pin, salt);
            byte[] verifier = verifierFor(key);
            prefs().edit()
                    .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean verifyPin(String pin) {
        try {
            String saltText = prefs().getString(KEY_SALT, null);
            String verifierText = prefs().getString(KEY_VERIFIER, null);
            if (saltText == null || verifierText == null) return false;
            byte[] salt = Base64.decode(saltText, Base64.NO_WRAP);
            byte[] expected = Base64.decode(verifierText, Base64.NO_WRAP);
            byte[] actual = verifierFor(deriveKey(pin, salt));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    public File encryptIntoVault(File source, String pin) throws Exception {
        if (!verifyPin(pin)) throw new SecurityException("Wrong PIN");
        SecretKey key = keyForPin(pin);
        byte[] iv = new byte[12];
        random.nextBytes(iv);

        String safeName = "vault_" + System.currentTimeMillis() + "_" + Math.abs(source.getAbsolutePath().hashCode()) + ".vlt";
        File outFile = new File(vaultDir, safeName);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));

        try (FileOutputStream fos = new FileOutputStream(outFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DataOutputStream header = new DataOutputStream(bos)) {
            header.writeUTF(MAGIC);
            header.writeUTF(source.getName());
            header.writeLong(source.length());
            header.writeInt(iv.length);
            header.write(iv);
            header.flush();

            try (CipherOutputStream cos = new CipherOutputStream(bos, cipher);
                 FileInputStream fis = new FileInputStream(source);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = bis.read(buffer)) != -1) cos.write(buffer, 0, n);
            }
        }
        return outFile;
    }

    public File decryptToCache(File vaultFile, String pin) throws Exception {
        if (!verifyPin(pin)) throw new SecurityException("Wrong PIN");
        File cacheDir = new File(context.getCacheDir(), "vault_open");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        return decryptToDirectory(vaultFile, pin, cacheDir, true);
    }

    public File restoreToDirectory(File vaultFile, String pin, File targetDir) throws Exception {
        if (!targetDir.exists()) targetDir.mkdirs();
        return decryptToDirectory(vaultFile, pin, targetDir, false);
    }

    private File decryptToDirectory(File vaultFile, String pin, File targetDir, boolean overwrite) throws Exception {
        SecretKey key = keyForPin(pin);
        try (FileInputStream fis = new FileInputStream(vaultFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             DataInputStream header = new DataInputStream(bis)) {
            String magic = header.readUTF();
            if (!MAGIC.equals(magic)) throw new IllegalArgumentException("Unknown vault file");
            String originalName = header.readUTF();
            header.readLong();
            int ivLen = header.readInt();
            if (ivLen < 12 || ivLen > 32) throw new IllegalArgumentException("Invalid vault file");
            byte[] iv = new byte[ivLen];
            header.readFully(iv);

            File out = uniqueFile(targetDir, originalName, overwrite);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));

            try (CipherInputStream cis = new CipherInputStream(bis, cipher);
                 FileOutputStream fos = new FileOutputStream(out);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = cis.read(buffer)) != -1) bos.write(buffer, 0, n);
            }
            return out;
        }
    }

    public List<File> listVaultFiles() {
        File[] files = vaultDir.listFiles((dir, name) -> name.endsWith(".vlt"));
        if (files == null) return new ArrayList<>();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    public String originalName(File vaultFile) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(vaultFile)))) {
            if (!MAGIC.equals(in.readUTF())) return "Private file";
            return in.readUTF();
        } catch (Exception e) {
            return "Private file";
        }
    }

    public long originalSize(File vaultFile) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(vaultFile)))) {
            if (!MAGIC.equals(in.readUTF())) return 0L;
            in.readUTF();
            return in.readLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private File uniqueFile(File dir, String name, boolean overwrite) {
        File base = new File(dir, name);
        if (overwrite || !base.exists()) return base;
        String stem = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        File candidate;
        do {
            candidate = new File(dir, stem + " (" + i++ + ")" + ext);
        } while (candidate.exists());
        return candidate;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey keyForPin(String pin) throws Exception {
        byte[] salt = Base64.decode(prefs().getString(KEY_SALT, ""), Base64.NO_WRAP);
        return deriveKey(pin, salt);
    }

    private SecretKey deriveKey(String pin, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS);
        byte[] encoded = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(encoded, "AES");
    }

    private byte[] verifierFor(SecretKey key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(key.getEncoded());
        md.update("PocketGalleryVaultVerifier".getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }
}
