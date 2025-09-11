package com.DeliveryInventoryService.DeliveryInventoryService.Configuration;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class KeyLoader {

    public static PrivateKey loadPrivateKey(String path) throws Exception {
        byte[] keyBytes = readPem(path);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    public static PublicKey loadPublicKey(String path) throws Exception {
        byte[] keyBytes = readPem(path);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    private static byte[] readPem(String path) throws Exception {
        InputStream is;
        if (path.startsWith("classpath:")) {
            String resourcePath = path.replace("classpath:", "/");
            is = KeyLoader.class.getResourceAsStream(resourcePath);
            if (is == null)
                throw new IllegalArgumentException("File not found: " + path);
        } else {
            is = java.nio.file.Files.newInputStream(java.nio.file.Path.of(path));
        }

        byte[] bytes = is.readAllBytes();
        is.close();

        String pem = new String(bytes)
                .replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(pem);
    }
}
