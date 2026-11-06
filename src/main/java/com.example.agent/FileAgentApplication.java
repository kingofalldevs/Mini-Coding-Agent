package com.example.agent;

import org.springframework.ai.chat.client.ChatClient;
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
    ChatClient chatClient(ChatClient.Builder builder,
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
                .build();
    }

    @Bean
    CommandLineRunner repl(ChatClient chatClient) {
        return args -> {
            Scanner in = new Scanner(System.in);
            System.out.println("File agent ready. Type 'exit' to quit.");
            while (true) {
                System.out.print("\nyou> ");
                String line = in.nextLine();
                if (line.equalsIgnoreCase("exit")) break;

                String reply = chatClient.prompt().user(line).call().content();
                System.out.println("agent> " + reply);
            }
        };
    }
}