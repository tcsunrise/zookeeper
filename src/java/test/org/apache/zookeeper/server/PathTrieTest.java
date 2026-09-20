package org.apache.zookeeper.server;

import junit.framework.TestCase;
import org.apache.zookeeper.common.PathTrie;

public class PathTrieTest extends TestCase {

    public void testPathTrie() {
        PathTrie pathTrie = new PathTrie();
        pathTrie.addPath("/app/qq");
//        pathTrie.addPath("/app/qq/sa");

        String maxPrefix = pathTrie.findMaxPrefix("/app/qq/sa/12s/2121");
        System.out.println(maxPrefix);
    }
}
