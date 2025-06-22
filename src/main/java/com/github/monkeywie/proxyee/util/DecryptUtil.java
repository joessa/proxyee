package com.github.monkeywie.proxyee.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/**
 * @company: www.robai.com
 * @author: robin
 * @date: 2025/6/21
 * @time: 17:55
 * @describe
 */
public class DecryptUtil {


    private static final char[] BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=".toCharArray();

    private static final String DEFAULT_KEY = "ED7AA06BD8628B55";

    public static void main(String[] args) throws Exception {

        //密钥转化为字节数组
        byte[] keyBytes = DEFAULT_KEY.getBytes(StandardCharsets.UTF_8);
        //字节数组转化为base64字符串
        byte[] a = {(byte) 159, (byte) 92, (byte) 102, (byte) 50, (byte) 243, (byte) 214, (byte) 204, (byte) 20, (byte) 192, (byte) 193, (byte) 247, (byte) 19, (byte) 220, (byte) 94, (byte) 7, (byte) 237, (byte) 160, (byte) 223, (byte) 132, (byte) 44, (byte) 236, (byte) 22, (byte) 19, (byte) 222, (byte) 105, (byte) 90, (byte) 106, (byte) 123, (byte) 67, (byte) 134, (byte) 181, (byte) 64, (byte) 25, (byte) 174, (byte) 115, (byte) 233, (byte) 213, (byte) 239, (byte) 0, (byte) 163, (byte) 38, (byte) 26, (byte) 249, (byte) 39, (byte) 50, (byte) 169, (byte) 9, (byte) 156, (byte) 124, (byte) 73, (byte) 67, (byte) 199, (byte) 6, (byte) 147, (byte) 96, (byte) 148, (byte) 211, (byte) 224, (byte) 246, (byte) 115, (byte) 29, (byte) 55, (byte) 254, (byte) 24, (byte) 20, (byte) 132, (byte) 134, (byte) 104, (byte) 177, (byte) 97, (byte) 183, (byte) 179, (byte) 224, (byte) 137, (byte) 224, (byte) 107, (byte) 37, (byte) 43, (byte) 241, (byte) 124, (byte) 169, (byte) 202, (byte) 66, (byte) 133, (byte) 240, (byte) 57, (byte) 10, (byte) 135, (byte) 148, (byte) 71, (byte) 161, (byte) 228, (byte) 134, (byte) 85, (byte) 186, (byte) 216, (byte) 242, (byte) 60, (byte) 252, (byte) 121, (byte) 206, (byte) 222, (byte) 140, (byte) 101, (byte) 52, (byte) 42, (byte) 188, (byte) 237, (byte) 90, (byte) 183, (byte) 229, (byte) 130, (byte) 243, (byte) 229, (byte) 175, (byte) 89, (byte) 76, (byte) 209, (byte) 246, (byte) 247, (byte) 150, (byte) 96, (byte) 147, (byte) 135, (byte) 106, (byte) 174, (byte) 123, (byte) 227, (byte) 125, (byte) 68, (byte) 215, (byte) 3, (byte) 221, (byte) 28, (byte) 51, (byte) 90, (byte) 133, (byte) 211, (byte) 46, (byte) 171, (byte) 248, (byte) 223, (byte) 184, (byte) 154, (byte) 34, (byte) 12, (byte) 136, (byte) 252, (byte) 96, (byte) 105, (byte) 249, (byte) 32, (byte) 95, (byte) 221, (byte) 6, (byte) 60, (byte) 193, (byte) 102, (byte) 97, (byte) 55, (byte) 56, (byte) 203, (byte) 52, (byte) 229, (byte) 235, (byte) 155, (byte) 254, (byte) 19, (byte) 252, (byte) 205, (byte) 132, (byte) 27, (byte) 115, (byte) 46, (byte) 80, (byte) 113, (byte) 62, (byte) 216, (byte) 116, (byte) 25, (byte) 12, (byte) 35, (byte) 112, (byte) 203, (byte) 200, (byte) 221, (byte) 57, (byte) 163, (byte) 13, (byte) 193, (byte) 121, (byte) 104, (byte) 176, (byte) 129, (byte) 68, (byte) 79, (byte) 221, (byte) 225, (byte) 36, (byte) 255, (byte) 141, (byte) 251, (byte) 130, (byte) 110, (byte) 138, (byte) 198, (byte) 236, (byte) 10, (byte) 54, (byte) 143, (byte) 229, (byte) 163, (byte) 67, (byte) 133, (byte) 145, (byte) 64, (byte) 28, (byte) 45, (byte) 64, (byte) 227, (byte) 93, (byte) 77, (byte) 110, (byte) 208};
        String base64String = getBase64String(a);
        System.out.println("Base64: " + base64String);

        byte [] aesDecrypt = aesDecrypt(base64String, keyBytes, "CBC");
        System.out.println("AES: " + Arrays.toString(aesDecrypt));
        String unzip = unzip(aesDecrypt);
        System.out.println("Unzip: " + unzip);
    }


    public static String getBase64String(byte[] input) {
        StringBuilder output = new StringBuilder();
        int ptr = 0;

        while (ptr < input.length) {
            int byte1 = input[ptr++] & 0xFF;
            int byte2 = ptr < input.length ? input[ptr++] & 0xFF : 0;
            int byte3 = ptr < input.length ? input[ptr++] & 0xFF : 0;

            int group1 = byte1 >> 2;
            int group2 = ((byte1 & 3) << 4) | (byte2 >> 4);
            int group3 = ((byte2 & 15) << 2) | (byte3 >> 6);
            int group4 = byte3 & 63;

            if (ptr - 1 >= input.length) {
                group3 = group4 = 64; // '='
            } else if (ptr >= input.length) {
                group4 = 64; // '='
            }

            output.append(BASE64_CHARS[group1]);
            output.append(BASE64_CHARS[group2]);
            output.append(BASE64_CHARS[group3]);
            output.append(BASE64_CHARS[group4]);
        }

        return output.toString();
    }


//    public static int[] bytes(byte[] keyBytes) {
//        if (keyBytes == null) {
//            throw new IllegalArgumentException("key bytes is null");
//        }
//        if (keyBytes.length % 4 != 0) {
//            throw new IllegalArgumentException("Invalid key length");
//        }
//        int[] words = new int[keyBytes.length / 4];
//        for (int i = 0; i < words.length; i++) {
//            words[i] = ((keyBytes[i * 4] & 0xFF) << 24) |
//                    ((keyBytes[i * 4 + 1] & 0xFF) << 16) |
//                    ((keyBytes[i * 4 + 2] & 0xFF) << 8) |
//                    (keyBytes[i * 4 + 3] & 0xFF);
//        }
//        return words;
//    }


    public static byte[] aesDecrypt(String ciphertextBase64, byte[] keyBytes, String mode) throws Exception {
        if (mode == null) {
            mode = "CBC";
        }
        // 1. Base64解码密文
        byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);

        // 2. 构造密钥和IV
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(keyBytes); // JS里iv=key

        // 3. 选择模式
        String transformation = "AES/" + (mode.equalsIgnoreCase("CBC") ? "CBC" : "ECB") + "/PKCS5Padding";
        Cipher cipher = Cipher.getInstance(transformation);

        if (mode.equalsIgnoreCase("CBC")) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
        }
        // 4. 解密
        return cipher.doFinal(ciphertext);
    }


    public static String unzip(byte[] decryptedBytes) throws Exception {
        if (decryptedBytes == null) {
            throw new Exception("decryptedBytes is null");
        }
        // 5. 检查是否GZIP
        if (decryptedBytes.length > 2 && (decryptedBytes[0] == 0x1F && (decryptedBytes[1] & 0xFF) == 0x8B)) {
            // GZIP解压
            ByteArrayInputStream bais = new ByteArrayInputStream(decryptedBytes);
            GZIPInputStream gzipIn = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIn.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            gzipIn.close();
            return baos.toString(StandardCharsets.UTF_8.name());
        } else {
            // 直接转字符串
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        }
    }
}
