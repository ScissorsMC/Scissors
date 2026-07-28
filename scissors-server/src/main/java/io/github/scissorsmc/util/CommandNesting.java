package io.github.scissorsmc.util;

public final class CommandNesting {

    public static final int MAX_DEPTH = 64;

    private CommandNesting() {
    }

    public static boolean exceedsMaxDepth(final CharSequence input) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            final char character = input.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
                continue;
            }

            if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '{' || character == '[') {
                if (++depth > MAX_DEPTH) {
                    return true;
                }
            } else if ((character == '}' || character == ']') && depth > 0) {
                depth--;
            }
        }

        return false;
    }
}
