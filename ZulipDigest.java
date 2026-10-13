import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A one or two sentence digest per Zulip topic, for the Zulip run (the briefing writes its own into the HTML). */
public class ZulipDigest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The key a topic's digest is stored under: "stream > topic". */
    static String key(ZulipChat.ZulipMessage m) {
        return m.stream() + " > " + m.topic();
    }

    /** Digest per topic key; empty when there is nothing to digest or Claude fails (the messages still show). */
    static Map<String, String> generate(List<ZulipChat.ZulipMessage> messages) {
        if (messages.isEmpty()) return Map.of();
        Map<String, List<ZulipChat.ZulipMessage>> byTopic = new LinkedHashMap<>();
        for (ZulipChat.ZulipMessage m : messages) byTopic.computeIfAbsent(key(m), k -> new ArrayList<>()).add(m);

        List<String> keys = new ArrayList<>(byTopic.keySet());
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            var e = Map.entry(keys.get(i), byTopic.get(keys.get(i)));
            data.append("=== T").append(i + 1).append(": ").append(e.getKey()).append(" ===\n");
            for (ZulipChat.ZulipMessage m : e.getValue()) {
                data.append(m.sender()).append(m.isMention() ? " (mentions you)" : "").append(": ")
                        .append(m.content().replace('\n', ' ')).append("\n");
            }
            data.append("\n");
        }
        String prompt = "Below are recent Zulip messages, grouped by \"stream > topic\". For each topic, write a "
                + "one or two sentence digest of the conversation: what it is about and where it stands, and say "
                + "so if someone is asking the reader something. Plain text, no Markdown.\n\n"
                + "Output ONLY a JSON object mapping each topic's id (T1, T2, ...) to its digest, "
                + "with no preamble and no code fence.\n\n" + data;
        try {
            Process process = new ProcessBuilder("claude", "-p", "--model", "sonnet").start();
            try (var os = process.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                System.err.println("  Zulip digest timed out");
                return Map.of();
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                System.err.println("  Zulip digest failed: " + out.substring(0, Math.min(300, out.length())));
                return Map.of();
            }
            int start = out.indexOf('{'), end = out.lastIndexOf('}');
            if (start < 0 || end < start) {
                System.err.println("  Zulip digest returned no JSON: " + out.substring(0, Math.min(300, out.length())));
                return Map.of();
            }
            JsonNode json = MAPPER.readTree(out.substring(start, end + 1));
            Map<String, String> digests = new LinkedHashMap<>();
            json.fields().forEachRemaining(f -> {
                int i = f.getKey().matches("T\\d+") ? Integer.parseInt(f.getKey().substring(1)) - 1 : -1;
                if (i >= 0 && i < keys.size() && f.getValue().isTextual()) digests.put(keys.get(i), f.getValue().asText());
            });
            if (digests.isEmpty()) System.err.println("  Zulip digest matched no topic: " + out.substring(0, Math.min(300, out.length())));
            return digests;
        } catch (Exception e) {
            System.err.println("  Error generating the Zulip digest: " + e.getMessage());
            return Map.of();
        }
    }
}
