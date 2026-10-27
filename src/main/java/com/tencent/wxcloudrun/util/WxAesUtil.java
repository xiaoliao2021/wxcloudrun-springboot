package com.tencent.wxcloudrun.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 微信敏感数据解密：AES-128-CBC，PKCS#7（Java 用 PKCS5Padding，对 AES 等价）
 */
public class WxAesUtil {

  private WxAesUtil() {
  }

  public static String decrypt(String sessionKey, String encryptedData, String iv) throws Exception {
    byte[] keyBytes = Base64.getDecoder().decode(sessionKey);
    byte[] ivBytes = Base64.getDecoder().decode(iv);
    byte[] cipherBytes = Base64.getDecoder().decode(encryptedData);

    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
    IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

    byte[] plain = cipher.doFinal(cipherBytes);
    return new String(plain, StandardCharsets.UTF_8);
  }
}
