package com.sysadminanywhere.m3.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;

/** Publication is attempted only with the OpenSSH atomic replacement extension. */
final class SftpPublisher {
    @FunctionalInterface interface BeforePublish { void check() throws IOException; }
    static void publish(SftpClient client, Path original, String directory, String name, String checksum, BeforePublish beforePublish) throws IOException {
        if (directory==null || !directory.startsWith("/") || directory.contains("\\") || directory.contains("\u0000")
                || java.util.Arrays.asList(directory.split("/",-1)).contains("..")
                || name==null || name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains("\u0000"))
            throw new IOException("Invalid SFTP destination");
        if (checksum==null || !checksum.matches("[0-9a-f]{64}")) throw new IOException("Invalid original checksum");
        var rename=client.getExtension(OpenSSHPosixRenameExtension.class);
        if (rename==null || !rename.isSupported()) throw new IOException("Server does not support atomic SFTP publication");
        String prefix=directory.endsWith("/")?directory:directory+"/";
        String target=prefix+name, temp=prefix+".m3-"+UUID.randomUUID()+".part";
        boolean uploaded=false;
        try {
            MessageDigest digest;
            try { digest=MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            try (var input=new DigestInputStream(Files.newInputStream(original),digest);
                 var output=client.write(temp,SftpClient.OpenMode.Write,SftpClient.OpenMode.Create,SftpClient.OpenMode.Exclusive)) {
                uploaded=true; input.transferTo(output);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(checksum)) throw new IOException("Original content checksum mismatch");
            beforePublish.check();
            rename.posixRename(temp,target);
        } finally {
            if (uploaded) try { client.remove(temp); } catch (IOException ignored) { }
        }
    }
}
