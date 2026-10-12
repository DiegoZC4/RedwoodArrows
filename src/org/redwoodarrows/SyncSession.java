package org.redwoodarrows;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Long-lived device credentials are encrypted with a non-exportable Android key. */
final class SyncSession {
    private static final String KEY="redwood-sync-v1";
    private final Context context;
    SyncSession(Context context){this.context=context.getApplicationContext();}
    private javax.crypto.SecretKey key()throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(KEY)){
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return (javax.crypto.SecretKey)ks.getKey(KEY,null);
    }
    JSONObject read()throws Exception {
        String saved=context.getSharedPreferences("sync-auth",0).getString("encrypted","");if(saved.isEmpty())return null;
        JSONObject blob=new JSONObject(saved);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(blob.getString("iv"),Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(blob.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    void write(JSONObject session)throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String saved=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).put("data",Base64.encodeToString(cipher.doFinal(session.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).toString();
        if(!context.getSharedPreferences("sync-auth",0).edit().putString("encrypted",saved).commit())throw new java.io.IOException("Could not save sign-in securely");
    }
    void clear()throws Exception {if(!context.getSharedPreferences("sync-auth",0).edit().clear().commit())throw new java.io.IOException("Could not sign out on this phone");}
    boolean present(){return context.getSharedPreferences("sync-auth",0).contains("encrypted");}
}
