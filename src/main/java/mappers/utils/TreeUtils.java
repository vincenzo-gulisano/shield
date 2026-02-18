package mappers.utils;

import io.github.ericmedvet.jgea.core.representation.tree.Tree;

public class TreeUtils {

    // Find first terminal node in a subtree recursively
    public static String findFirstTerminal(Tree<String> tree) {
        if (tree.isLeaf()) {
            return tree.content();
        }
        for (Tree<String> child : tree) {
            String terminal = findFirstTerminal(child);
            if (terminal != null) {
                return terminal;
            }
        }
        return null;
    }

}
