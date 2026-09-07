package com.onedayonemasterpiece.repeatit;

import android.content.Context;
import android.security.keystore.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Credential encrypted storage, excluded from backup. No token intents, arguments, log statements or exports. */
public final class TokenVault {
    private final AtomicFile file;
    public TokenVault(Context c){file=new AtomicFile(new File(c.getNoBackupFilesDir(),"github-token.aesgcm"));}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("repeat-it-github")) {
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("repeat-it-github",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return (SecretKey)store.getKey("repeat-it-github",null);
    }
    public synchronized void save(String token) throws Exception {
        token=token.trim();if(token.isEmpty()||token.length()>512||token.matches(".*\\s+.*"))throw new IllegalArgumentException("invalid_token");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        byte[] raw=token.getBytes(StandardCharsets.UTF_8), encrypted=c.doFinal(raw);java.util.Arrays.fill(raw,(byte)0);
        FileOutputStream out=null;
        try {out=file.startWrite();out.write(c.getIV().length);out.write(c.getIV());out.write(encrypted);file.finishWrite(out);}catch(IOException e){if(out!=null)file.failWrite(out);throw e;}
    }
    public synchronized String read() throws Exception {
        if(!file.getBaseFile().exists())return "";
        try(DataInputStream in=new DataInputStream(file.openRead())) {
            int size=in.readUnsignedByte();if(size!=12)throw new IOException("invalid_ciphertext");
            byte[] iv=new byte[size];in.readFully(iv);ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[1024];int count;while((count=in.read(chunk))!=-1){if(buffer.size()+count>2048)throw new IOException("ciphertext_bounds");buffer.write(chunk,0,count);}byte[] encrypted=buffer.toByteArray();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            byte[] raw=c.doFinal(encrypted);String result=new String(raw,StandardCharsets.UTF_8);java.util.Arrays.fill(raw,(byte)0);return result;
        }
    }
}
