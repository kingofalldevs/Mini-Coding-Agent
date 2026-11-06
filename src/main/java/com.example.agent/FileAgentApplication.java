package com.example.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Scanner;

@SpringBootApplication
public class FileAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileAgentApplication.class, args);
    }

    @Bean
    ChatMemory chatMemory() {
        // Keeps conversation history for the life of the process.
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          ChatMemory chatMemory,
                          @Value("${agent.workspace}") String workspace) throws Exception {
        return builder
                .defaultSystem("""
                            You are a helpful assistant that can create, read, and save files.
                            - Use listFiles to see what exists before assuming a file is missing.
                            - Use readFile before editing or rewriting an existing file.
                            - Use writeFile to create or overwrite a file. Overwriting an existing path replaces it,
                              so reuse the same path when the user asks to modify a file.
                            - Give files a sensible extension (e.g. whoiam.md, not a bare name).
                            - Always confirm which file you wrote.
                            - Only create or modify files the user explicitly asks for.
                            Never create test, probe, or placeholder files.
                            """)
                .defaultTools(new FileTools(Path.of(workspace)))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    CommandLineRunner repl(ChatClient chatClient) {
        return args -> {
            String conversationId = "repl";

            Scanner in = new Scanner(System.in);
            System.out.println("File agent ready. Type 'exit' to quit.");
            while (true) {
                System.out.print("\nyou> ");
                if (!in.hasNextLine()) break;
                String line = in.nextLine();
                if (line.isBlank()) continue;
                if (line.equalsIgnoreCase("exit")) break;

                System.out.print("agent> ");
                chatClient.prompt()
                        .user(line)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .content()
                        .doOnNext(System.out::print)
                        .blockLast();
                System.out.println();
            }
        };
    }
}
