package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the REPL command parsing in {@link MiniCodingAgentApplication}.
 *
 * <p>Parsing is deliberately dumb: split on the first space, lowercase the name,
 * leave the argument alone. The argument is later handed to git as a single argv
 * element, so no parsing here should ever introduce shell semantics.
 */
class ReplCommandTest {

    @Test
    void parsesBareCommandWithNoArgument() {
        MiniCodingAgentApplication.Command c = MiniCodingAgentApplication.parseCommand(":status");

        assertThat(c.name()).isEqualTo(":status");
        assertThat(c.arg()).isEmpty();
    }

    @Test
    void parsesCommandWithArgument() {
        MiniCodingAgentApplication.Command c =
                MiniCodingAgentApplication.parseCommand(":push chore: rename title");

        assertThat(c.name()).isEqualTo(":push");
        assertThat(c.arg()).isEqualTo("chore: rename title");
    }

    @Test
    void trimsSurroundingWhitespace() {
        MiniCodingAgentApplication.Command c =
                MiniCodingAgentApplication.parseCommand("   :commit    fix bug   ");

        assertThat(c.name()).isEqualTo(":commit");
        assertThat(c.arg()).isEqualTo("fix bug");
    }

    @Test
    void commandNameIsLowercasedButArgumentIsNot() {
        MiniCodingAgentApplication.Command upper =
                MiniCodingAgentApplication.parseCommand(":CHECK");
        MiniCodingAgentApplication.Command mixed =
                MiniCodingAgentApplication.parseCommand(":push Fix The Title");

        assertThat(upper.name()).isEqualTo(":check");
        assertThat(mixed.arg()).isEqualTo("Fix The Title");
    }

    @Test
    void hostileMessageStaysLiteralInTheArgument() {
        String hostile = "msg; rm -rf / && echo $(whoami)";

        MiniCodingAgentApplication.Command c =
                MiniCodingAgentApplication.parseCommand(":commit " + hostile);

        assertThat(c.arg()).isEqualTo(hostile);
    }

    @Test
    void unknownCommandIsReportedAsIs() {
        assertThat(MiniCodingAgentApplication.parseCommand(":frobnicate now").name())
                .isEqualTo(":frobnicate");
    }

    @Test
    void nullIsHandledWithoutThrowing() {
        MiniCodingAgentApplication.Command c = MiniCodingAgentApplication.parseCommand(null);

        assertThat(c.name()).isEmpty();
        assertThat(c.arg()).isEmpty();
    }

    @Test
    void onlyTheFiveFileToolsAreRegisteredForTheModel() throws IOException {
        // The git boundary in one assertion: the model can only ever call these five,
        // so no prompt-injected file can reach a commit or a push.
        //
        // FIX: ToolCallbacks.from(...) returns an array (ToolCallback[]), not a List,
        // so it has no .stream() method. Arrays.stream(...) is the correct call.
        String[] toolNames = Arrays.stream(
                        ToolCallbacks.from(new FileTools(Files.createTempDirectory("tools"))))
                .map(cb -> cb.getToolDefinition().name())
                .toArray(String[]::new);

        assertThat(toolNames).containsExactlyInAnyOrder(
                "listFiles", "readFile", "writeFile", "moveFile", "deleteFile");
        assertThat(toolNames).noneMatch(name -> name.toLowerCase().contains("git"));
    }
}