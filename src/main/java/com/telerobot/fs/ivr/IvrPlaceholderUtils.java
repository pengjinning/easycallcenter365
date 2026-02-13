package com.telerobot.fs.ivr;

import java.util.*;
import java.util.regex.*;

public class IvrPlaceholderUtils {

    public static List<String> extractPlaceholders(String text) {
        return extractPlaceholders(text, true);
    }


    public static List<String> extractPlaceholders(String text, boolean keepBraces) {
        List<String> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(text);
        
        while (matcher.find()) {
            result.add(keepBraces ? matcher.group(0) : matcher.group(1));
        }
        return result;
    }

    // 测试
    public static void main(String[] args) {
        String input = "尊敬的 {customer_name}，下午好！您的会员等级是 {user_level}，欢迎光临{company_name}！";
        
        System.out.println("带花括号: " + extractPlaceholders(input)); 
        // 输出: [{customer_name}, {user_level}, {company_name}]
        
        System.out.println("仅变量名: " + extractPlaceholders(input, false)); 
        // 输出: [customer_name, user_level, company_name]
    }
}