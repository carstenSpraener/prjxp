package de.spraener.prjxp.oragel;

import java.util.Scanner;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@FunctionalInterface
public interface PromptSource {
    Stream<String> stream();

    /**
     * Erstellt eine PromptSource, die interaktiv von der Konsole liest.
     */
    static PromptSource interactive() {
        return () -> {
            Scanner scanner = new Scanner(System.in);
            return StreamSupport.stream(
                    new java.util.Spliterators.AbstractSpliterator<String>(Long.MAX_VALUE, 0) {
                        @Override
                        public boolean tryAdvance(java.util.function.Consumer<? super String> action) {
                            System.out.print("\n🔮 ORAGEL Frage: ");
                            if (scanner.hasNextLine()) {
                                String line = scanner.nextLine();
                                if (isExitCommand(line)) {
                                    return false;
                                }
                                action.accept(line);
                                return true;
                            }
                            return false;
                        }
                    }, false);
        };
    }

    static boolean isExitCommand(String input) {
        return "exit".equalsIgnoreCase(input);
    }
}