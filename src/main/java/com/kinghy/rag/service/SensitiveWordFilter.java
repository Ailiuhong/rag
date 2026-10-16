package com.kinghy.rag.service;

import com.kinghy.rag.entity.SensitiveWord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词匹配器。
 *
 * 原实现每次对话都要全表查询敏感词再逐条 contains 判断，
 * 词库越大越慢。这里改成本地前缀树（Trie）+ 缓存：
 *  - 词库缓存在内存中，默认 5 分钟过期，避免每个请求都查库；
 *  - 匹配时对文本做一次扫描（按词长回溯，复杂度 O(文本长度 × 最长词长)），
 *    不需要对每个敏感词都做一次 contains；
 *  - 敏感词增删改后调用 invalidate() 让缓存立即失效。
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 词库缓存有效期：5 分钟 */
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    @Autowired
    private SensitiveWordService sensitiveWordService;

    private volatile TrieNode root = new TrieNode();
    private volatile long loadedAt = 0L;

    /**
     * 返回文本中命中的第一个敏感词，未命中返回 null
     */
    public String match(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        ensureLoaded();

        TrieNode node = root;
        for (int i = 0; i < text.length(); i++) {
            TrieNode current = node;
            for (int j = i; j < text.length(); j++) {
                current = current.children.get(text.charAt(j));
                if (current == null) {
                    break;
                }
                if (current.end) {
                    return current.word;
                }
            }
        }
        return null;
    }

    /**
     * 敏感词变更后调用，下一次匹配时重新加载词库
     */
    public void invalidate() {
        this.loadedAt = 0L;
    }

    private void ensureLoaded() {
        if (System.currentTimeMillis() - loadedAt > CACHE_TTL_MILLIS) {
            refresh();
        }
    }

    private synchronized void refresh() {
        // 双检：可能已被其他线程刷新
        if (System.currentTimeMillis() - loadedAt <= CACHE_TTL_MILLIS && loadedAt != 0L) {
            return;
        }
        TrieNode newRoot = new TrieNode();
        List<SensitiveWord> words = sensitiveWordService.list();
        for (SensitiveWord sensitiveWord : words) {
            String word = sensitiveWord.getWord();
            if (word == null || word.isEmpty()) {
                continue;
            }
            TrieNode node = newRoot;
            for (char c : word.toCharArray()) {
                node = node.children.computeIfAbsent(c, k -> new TrieNode());
            }
            node.end = true;
            node.word = word;
        }
        this.root = newRoot;
        this.loadedAt = System.currentTimeMillis();
        log.info("敏感词词库已加载，共 {} 条", words.size());
    }

    private static class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private boolean end;
        private String word;
    }
}
