package com.telerobot.fs.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

public class Base64ToPCM {

    public static void main(String[] args) {
        String base64FilePath = "C:\\Users\\zhaohai\\Downloads\\pcm_base64_8a09f564-1d78-4bae-a9cc-5eee5d5ef773.txt"; // 输入的Base64文本文件路径
        String pcmFilePath = "C:\\Users\\zhaohai\\Downloads\\output.pcm";   // 输出的PCM文件路径

        try {
            // 1. 读取Base64文本文件内容
            String base64Content = new String(Files.readAllBytes(Paths.get(base64FilePath)));
            
            // 2. 解码Base64字符串为二进制数据
            byte[] pcmData = Base64.getDecoder().decode(cleanBase64String(base64Content.trim())); // 注意去除首尾空白字符
            
            // 3. 将二进制数据写入PCM文件
            Files.write(Paths.get(pcmFilePath), pcmData);
            
            System.out.println("PCM文件已成功生成: " + pcmFilePath);
            
        } catch (IOException e) {
            System.err.println("文件读写错误: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.err.println("Base64解码失败，请检查文件内容是否为有效的Base64编码: " + e.getMessage());
        }
    }

    private static String cleanBase64String(String base64String) {
        // 移除所有空白字符（空格、换行、制表符等）
        String cleaned = base64String.replaceAll("\\s+", "");

        // 确保长度是4的倍数（Base64要求）
        int remainder = cleaned.length() % 4;
        if (remainder != 0) {
            // 填充等号使长度为4的倍数
            cleaned += "====".substring(remainder);
        }

        return cleaned;
    }
}