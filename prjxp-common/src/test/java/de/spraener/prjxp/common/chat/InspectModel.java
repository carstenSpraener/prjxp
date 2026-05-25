package de.spraener.prjxp.common.chat;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.lang.reflect.*;

public class InspectModel {
    public static void main(String[] args) throws Exception {
        OpenAiChatModel model = OpenAiChatModel.builder()
            .baseUrl("http://localhost:8080/v1")
            .build();
        
        Class<?> clazz = model.getClass();
        System.out.println("Class: " + clazz.getName());
        
        while (clazz != null) {
            System.out.println("  Fields of " + clazz.getSimpleName() + ":");
            for (Field f : clazz.getDeclaredFields()) {
                System.out.println("    " + f.getType().getSimpleName() + " " + f.getName());
            }
            clazz = clazz.getSuperclass();
        }
    }
}
