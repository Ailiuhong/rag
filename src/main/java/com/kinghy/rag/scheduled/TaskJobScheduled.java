package com.kinghy.rag.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kinghy.rag.entity.LogInfo;
import com.kinghy.rag.entity.WordFrequency;
import com.kinghy.rag.service.LogInfoService;
import com.kinghy.rag.service.WordFrequencyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Title: TaskJobScheduled
 * @Author KingHY
 * @Package com.kinghy.rag.scheduled
 * @description: 分词器定时任务（按增量统计问答日志词频）
 */

@Component("taskJob")
@Slf4j
public class TaskJobScheduled {

    /** 词频查询结果的缓存 key */
    private static final String CACHE_KEY = "wordFrequencyList";

    /** 增量水位线 key：记录已经统计过的最新日志 ID */
    private static final String WATERMARK_KEY = "wordFrequency:lastLogId";

    @Autowired
    private LogInfoService logInfoService;

    @Autowired
    private WordFrequencyService wordFrequencyService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** Redis 不可用时的进程内兜底水位线 */
    private volatile long fallbackLastLogId = 0L;

    /**
     * 默认每小时执行一次，可通过 kinghy.frequency.cron 覆盖
     */
    @Scheduled(cron = "${kinghy.frequency.cron:0 0 * * * ?}")
    public void taskJob() {
        long lastLogId = loadWatermark();

        // 只处理水位线之后的日志：避免每轮把历史日志重复累加导致词频虚高
        List<LogInfo> logs = logInfoService.list(new LambdaQueryWrapper<LogInfo>()
                .gt(LogInfo::getId, lastLogId)
                .orderByAsc(LogInfo::getId));

        if (logs.isEmpty()) {
            log.info("分词定时任务：没有新增日志，跳过统计（水位线 id={}）", lastLogId);
            return;
        }

        Date now = new Date();
        StringBuilder text = new StringBuilder();
        long maxLogId = lastLogId;
        for (LogInfo logInfo : logs) {
            if (logInfo.getRequestParams() != null) {
                text.append(logInfo.getRequestParams()).append('\n');
            }
            if (logInfo.getId() != null && logInfo.getId() > maxLogId) {
                maxLogId = logInfo.getId();
            }
        }

        // 已存在的词：在原计数上累加；新词：本批次内先合并计数，避免同一个词插入多行
        Map<String, WordFrequency> existingMap = new HashMap<>();
        for (WordFrequency wordFrequency : wordFrequencyService.list()) {
            existingMap.putIfAbsent(wordFrequency.getWord(), wordFrequency);
        }

        Map<String, WordFrequency> touched = new LinkedHashMap<>();
        try (StringReader reader = new StringReader(text.toString())) {
            IKSegmenter segment = new IKSegmenter(reader, true);
            Lexeme lexeme;
            while ((lexeme = segment.next()) != null) {
                String word = lexeme.getLexemeText();
                if (word.length() <= 1 || word.length() >= 10) {
                    continue;
                }
                WordFrequency wordFrequency = touched.get(word);
                if (wordFrequency == null) {
                    wordFrequency = existingMap.get(word);
                    if (wordFrequency == null) {
                        wordFrequency = new WordFrequency();
                        wordFrequency.setWord(word);
                        wordFrequency.setCountNum(0);
                        wordFrequency.setBusinessType("log");
                        wordFrequency.setCreateTime(now);
                    }
                    touched.put(word, wordFrequency);
                }
                wordFrequency.setCountNum(wordFrequency.getCountNum() + 1);
                wordFrequency.setUpdateTime(now);
            }
        } catch (IOException e) {
            log.error("日志分词失败", e);
        }

        if (!touched.isEmpty()) {
            // id 为空的是新词（insert），有 id 的是老词（update）
            wordFrequencyService.saveOrUpdateBatch(new ArrayList<>(touched.values()));
        }

        saveWatermark(maxLogId);
        clearCache();
        log.info("分词定时任务完成：处理 {} 条新增日志，涉及 {} 个词，水位线推进到 id={}",
                logs.size(), touched.size(), maxLogId);
    }

    private long loadWatermark() {
        try {
            Object value = redisTemplate.opsForValue().get(WATERMARK_KEY);
            if (value != null) {
                return Long.parseLong(value.toString());
            }
        } catch (Exception e) {
            log.warn("读取分词水位线失败，使用进程内水位线：{}", e.getMessage());
        }
        return fallbackLastLogId;
    }

    private void saveWatermark(long lastLogId) {
        this.fallbackLastLogId = lastLogId;
        try {
            redisTemplate.opsForValue().set(WATERMARK_KEY, String.valueOf(lastLogId));
        } catch (Exception e) {
            log.warn("写入分词水位线失败（不影响本次统计）：{}", e.getMessage());
        }
    }

    private void clearCache() {
        try {
            redisTemplate.delete(CACHE_KEY);
        } catch (Exception e) {
            log.warn("清理词频缓存失败：{}", e.getMessage());
        }
    }
}
